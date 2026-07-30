package com.shelves.db.dao;

import com.shelves.model.PricePoint;
import com.shelves.util.Dates;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * The purchase log: what was bought, how much, at what price, when.
 * <p>
 * Keyed by product rather than by item id, so a purchase survives the item it
 * came from being used up and deleted. That is the whole point of tracking cost
 * and restocking over time: a carton of milk bought in January is a different
 * row in {@code items} from one bought in March, and the January row is gone by
 * then, but both purchases belong to the same product and should be comparable.
 */
public class PriceHistoryDao {

    /** Records a purchase, returning the new row's id. */
    public int record(Connection conn, String productKey, String itemName,
                      double quantity, long priceCents, LocalDate on,
                      Integer sourceItemId) throws SQLException {
        return record(conn, productKey, itemName, quantity, priceCents, on, sourceItemId,
                com.shelves.model.HistoryKind.PURCHASE);
    }

    /** Records a history entry of a given kind, returning the new row's id. */
    public int record(Connection conn, String productKey, String itemName,
                      double quantity, long priceCents, LocalDate on,
                      Integer sourceItemId, com.shelves.model.HistoryKind kind)
            throws SQLException {
        String sql = """
                INSERT INTO price_history
                    (product_key, item_name, quantity, price_cents, recorded_on,
                     source_item_id, kind)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, productKey);
            ps.setString(2, itemName);
            ps.setDouble(3, quantity);
            ps.setLong(4, priceCents);
            ps.setString(5, Dates.toStorage(on == null ? LocalDate.now() : on));
            if (sourceItemId == null) {
                ps.setNull(6, java.sql.Types.INTEGER);
            } else {
                ps.setInt(6, sourceItemId);
            }
            ps.setString(7, kind.name());
            ps.executeUpdate();
        }
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT last_insert_rowid()")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    /**
     * Updates the most recent entry written by a given item, if there is one.
     * <p>
     * This is what a correction uses: when the user says an edit is a typo fix
     * rather than a new purchase, the latest entry for that item is amended in
     * place instead of a new row being added. Keying on the source item, not
     * just the product, means correcting one item does not touch a purchase
     * that a different item of the same product recorded.
     *
     * @return true if an entry was found and updated
     */
    /**
     * Corrects the latest purchase in place: sets its price, and adjusts its
     * recorded quantity by a delta (the amount the item's own quantity changed).
     * <p>
     * The quantity is adjusted by a delta rather than overwritten with the item's
     * on-hand total on purpose. Purchases store the amount bought at the time, and
     * an item's current quantity is the sum of its purchases minus what has left,
     * so writing the total onto a single entry would inflate the history whenever
     * more than one purchase exists. Adjusting by the same delta the item moved
     * fixes a mistyped quantity (correcting an item from 10 to 5 brings a
     * lone purchase of 10 down to 5) while staying correct when several purchases
     * are on record. The result is floored at zero so an over-correction cannot
     * leave a negative quantity.
     *
     * @param quantityDelta how much to add to the entry's quantity (negative to
     *                      reduce it)
     * @return true if a purchase existed to correct
     */
    public boolean correctLatest(Connection conn, int sourceItemId,
                                 double quantityDelta, long priceCents) throws SQLException {
        Integer latestId = findLatestPurchaseIdForItem(conn, sourceItemId);
        if (latestId == null) {
            return false;
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE price_history SET price_cents = ?, "
                        + "quantity = MAX(0, quantity + ?) WHERE id = ?")) {
            ps.setLong(1, priceCents);
            ps.setDouble(2, quantityDelta);
            ps.setInt(3, latestId);
            return ps.executeUpdate() > 0;
        }
    }

    /**
     * Permanently deletes every history entry — purchases and depletions — for
     * one product key.
     * <p>
     * This is the one operation that erases history rather than preserving it.
     * History is normally kept by product key so it survives an item being used
     * up and rebought; this method exists only for the explicit, confirmed
     * "delete permanently" action, where the user has said to erase all of it.
     * Callers are responsible for ensuring no other item still uses the key
     * before calling.
     *
     * @return how many rows were removed
     */
    public int deleteByProductKey(Connection conn, String productKey) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM price_history WHERE product_key = ?")) {
            ps.setString(1, productKey);
            return ps.executeUpdate();
        }
    }

    /**
     * The id of the most recent purchase a given item wrote, or null.
     * <p>
     * A correction only ever amends a purchase, never a usage event, so usage
     * rows are skipped here. Correcting a price must not silently rewrite the
     * record of some stock having been used.
     */
    public Integer findLatestPurchaseIdForItem(Connection conn, int sourceItemId)
            throws SQLException {
        String sql = """
                SELECT id FROM price_history
                WHERE source_item_id = ? AND kind = 'PURCHASE'
                ORDER BY recorded_on DESC, id DESC
                LIMIT 1
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, sourceItemId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : null;
            }
        }
    }

    /** True if the given item has ever recorded a purchase. */
    public boolean hasEntryForItem(Connection conn, int sourceItemId) throws SQLException {
        return findLatestPurchaseIdForItem(conn, sourceItemId) != null;
    }

    /** Every history entry for one product, purchases and usage, newest first. */
    public List<PricePoint> findByProduct(Connection conn, String productKey)
            throws SQLException {
        String sql = """
                SELECT id, product_key, item_name, quantity, price_cents,
                       recorded_on, source_item_id, kind
                FROM price_history
                WHERE product_key = ?
                ORDER BY recorded_on DESC, id DESC
                """;
        List<PricePoint> points = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, productKey);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    points.add(mapRow(rs));
                }
            }
        }
        return points;
    }

    /** The date of the most recent purchase recorded for a product, if any. */
    public LocalDate findLatestPurchaseDate(Connection conn, String productKey)
            throws SQLException {
        String sql = """
                SELECT recorded_on FROM price_history
                WHERE product_key = ? AND kind = 'PURCHASE'
                ORDER BY recorded_on DESC, id DESC
                LIMIT 1
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, productKey);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Dates.fromStorage(rs.getString(1)) : null;
            }
        }
    }

    /** The most recent purchase price recorded for a product, if any. */
    public Long findLatestPrice(Connection conn, String productKey) throws SQLException {
        String sql = """
                SELECT price_cents FROM price_history
                WHERE product_key = ? AND kind = 'PURCHASE'
                ORDER BY recorded_on DESC, id DESC
                LIMIT 1
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, productKey);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    /**
     * Average unit price paid for a product, over purchases only.
     * Usage events carry no meaningful price, so they are excluded.
     */
    public OptionalDouble findAveragePrice(Connection conn, String productKey)
            throws SQLException {
        String sql = "SELECT AVG(price_cents) FROM price_history "
                + "WHERE product_key = ? AND kind = 'PURCHASE'";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, productKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    double average = rs.getDouble(1);
                    return rs.wasNull() ? OptionalDouble.empty() : OptionalDouble.of(average);
                }
            }
        }
        return OptionalDouble.empty();
    }

    /** Lowest and highest purchase price ever paid, for context on a chart. */
    public Optional<long[]> findPriceRange(Connection conn, String productKey)
            throws SQLException {
        String sql = "SELECT MIN(price_cents), MAX(price_cents) "
                + "FROM price_history WHERE product_key = ? AND kind = 'PURCHASE'";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, productKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long min = rs.getLong(1);
                    if (rs.wasNull()) {
                        return Optional.empty();
                    }
                    return Optional.of(new long[]{min, rs.getLong(2)});
                }
            }
        }
        return Optional.empty();
    }

    private PricePoint mapRow(ResultSet rs) throws SQLException {
        PricePoint point = new PricePoint();
        point.setId(rs.getInt("id"));
        point.setProductKey(rs.getString("product_key"));
        point.setItemName(rs.getString("item_name"));
        point.setQuantity(rs.getDouble("quantity"));
        point.setPriceCents(rs.getLong("price_cents"));
        point.setRecordedOn(Dates.fromStorage(rs.getString("recorded_on")));
        int sourceId = rs.getInt("source_item_id");
        point.setSourceItemId(rs.wasNull() ? null : sourceId);
        point.setKind(com.shelves.model.HistoryKind.fromStorage(rs.getString("kind")));
        return point;
    }
}
