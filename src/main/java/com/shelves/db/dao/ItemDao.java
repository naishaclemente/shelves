package com.shelves.db.dao;

import com.shelves.model.Item;
import com.shelves.util.Dates;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reads and writes items.
 * <p>
 * Loading always attaches tags in a single extra query rather than one per row.
 */
public class ItemDao extends BaseDao {

    private static final String COLUMNS = """
            id, shelf_id, name, barcode, photo_path, quantity, unit,
            purchase_date, price_cents, track_price_history,
            expiration_date, opened_date, shelf_life_unopened_days,
            shelf_life_opened_days, alert_window_days, notes, created_date
            """;

    private final TagDao tagDao = new TagDao();

    // ==================== READ ====================

    /** Every item in the database. This is what the Master Shelf shows. */
    public List<Item> findAll(Connection conn) throws SQLException {
        String sql = "SELECT " + COLUMNS + " FROM items ORDER BY name COLLATE NOCASE ASC";
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            return collect(conn, rs);
        }
    }

    /**
     * Items filed on one specific shelf.
     * <p>
     * Note that this is never used for the Master Shelf. The service layer
     * routes that to {@link #findAll} instead, because the Master Shelf is a
     * view meaning "everything" and nothing is ever assigned to it. Querying it
     * by {@code shelf_id} would correctly return nothing at all.
     */
    public List<Item> findByShelfId(Connection conn, int shelfId) throws SQLException {
        String sql = "SELECT " + COLUMNS
                + " FROM items WHERE shelf_id = ? ORDER BY name COLLATE NOCASE ASC";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, shelfId);
            try (ResultSet rs = ps.executeQuery()) {
                return collect(conn, rs);
            }
        }
    }

    /** Items not filed on any shelf yet. */
    public List<Item> findUnfiled(Connection conn) throws SQLException {
        String sql = "SELECT " + COLUMNS
                + " FROM items WHERE shelf_id IS NULL ORDER BY name COLLATE NOCASE ASC";
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            return collect(conn, rs);
        }
    }

    public Optional<Item> findById(Connection conn, int id) throws SQLException {
        String sql = "SELECT " + COLUMNS + " FROM items WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                Item item = mapRow(rs);
                item.setTags(tagDao.findTagNamesForItem(conn, item.getId()));
                return Optional.of(item);
            }
        }
    }

    /** Items carrying a given tag, across every shelf. */
    public List<Item> findByTag(Connection conn, String tagName) throws SQLException {
        String sql = """
                SELECT i.id, i.shelf_id, i.name, i.barcode, i.photo_path, i.quantity, i.unit,
                       i.purchase_date, i.price_cents, i.track_price_history,
                       i.expiration_date, i.opened_date, i.shelf_life_unopened_days,
                       i.shelf_life_opened_days, i.alert_window_days, i.notes, i.created_date
                FROM items i
                JOIN item_tags it ON it.item_id = i.id
                JOIN tags t       ON t.id = it.tag_id
                WHERE t.name = ?
                ORDER BY i.name COLLATE NOCASE ASC
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tagName);
            try (ResultSet rs = ps.executeQuery()) {
                return collect(conn, rs);
            }
        }
    }

    /** Items with no tags at all, for the "Untagged" group. */
    public List<Item> findUntagged(Connection conn) throws SQLException {
        String sql = "SELECT " + COLUMNS + """
                 FROM items
                WHERE id NOT IN (SELECT item_id FROM item_tags)
                ORDER BY name COLLATE NOCASE ASC
                """;
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            return collect(conn, rs);
        }
    }

    /** Free text search across name, notes, barcode and unit. */
    /**
     * Items whose name contains the term, case-insensitively.
     * <p>
     * Search is deliberately name-only: the field is labelled "Search items", so
     * it matches item names and nothing else. Matching notes or barcodes under
     * the hood would make the label promise less than it did, and surface items
     * for reasons the user cannot see in the results.
     */
    public List<Item> search(Connection conn, String term) throws SQLException {
        String sql = "SELECT " + COLUMNS + """
                 FROM items
                WHERE name LIKE ? COLLATE NOCASE
                ORDER BY name COLLATE NOCASE ASC
                """;
        String pattern = "%" + term.trim() + "%";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, pattern);
            try (ResultSet rs = ps.executeQuery()) {
                return collect(conn, rs);
            }
        }
    }

    // ==================== WRITE ====================

    /**
     * Inserts an item and writes its generated id back onto the object.
     * Tags are saved too, so the item is complete when this returns.
     */
    public Item insert(Connection conn, Item item) throws SQLException {
        String sql = """
                INSERT INTO items
                    (shelf_id, name, barcode, photo_path, quantity, unit,
                     purchase_date, price_cents, track_price_history,
                     expiration_date, opened_date, shelf_life_unopened_days,
                     shelf_life_opened_days, alert_window_days, notes)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bindWritableColumns(ps, item);
            ps.executeUpdate();
        }
        item.setId(lastInsertId(conn));

        tagDao.setTagsForItem(conn, item.getId(), item.getTags());
        item.setCreatedDate(LocalDate.now());
        return item;
    }

    /**
     * Updates every editable column of an existing item, and replaces its tags.
     *
     * @return false if no row with that id exists
     */
    public boolean update(Connection conn, Item item) throws SQLException {
        String sql = """
                UPDATE items SET
                    shelf_id                 = ?,
                    name                     = ?,
                    barcode                  = ?,
                    photo_path               = ?,
                    quantity                 = ?,
                    unit                     = ?,
                    purchase_date            = ?,
                    price_cents              = ?,
                    track_price_history      = ?,
                    expiration_date          = ?,
                    opened_date              = ?,
                    shelf_life_unopened_days = ?,
                    shelf_life_opened_days   = ?,
                    alert_window_days        = ?,
                    notes                    = ?
                WHERE id = ?
                """;

        int updated;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bindWritableColumns(ps, item);
            ps.setInt(16, item.getId());
            updated = ps.executeUpdate();
        }

        if (updated == 0) {
            return false;
        }
        tagDao.setTagsForItem(conn, item.getId(), item.getTags());
        return true;
    }

    /** Moves an item to a different shelf, or to unfiled when null. */
    public boolean moveToShelf(Connection conn, int itemId, Integer shelfId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE items SET shelf_id = ? WHERE id = ?")) {
            setNullableInt(ps, 1, shelfId);
            ps.setInt(2, itemId);
            return ps.executeUpdate() > 0;
        }
    }

    /** Records the date an item was opened, which shortens its expiry. */
    public boolean markOpened(Connection conn, int itemId, LocalDate openedOn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE items SET opened_date = ? WHERE id = ?")) {
            ps.setString(1, Dates.toStorage(openedOn));
            ps.setInt(2, itemId);
            return ps.executeUpdate() > 0;
        }
    }

    public boolean adjustQuantity(Connection conn, int itemId, double newQuantity)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE items SET quantity = ? WHERE id = ?")) {
            ps.setDouble(1, newQuantity);
            ps.setInt(2, itemId);
            return ps.executeUpdate() > 0;
        }
    }

    /**
     * Copies an item into the archive with an optional reason, so it can be
     * listed on the Deleted Items page and restored to a real item later.
     * <p>
     * Everything needed to rebuild the item is captured, including its tags
     * (flattened to a comma-separated string) and the id of the shelf it was on
     * so a restore can return it there. Must run in the same transaction as the
     * delete.
     */
    public void archive(Connection conn, int itemId, String reason) throws SQLException {
        String sql = """
                INSERT INTO deleted_items
                    (name, original_shelf_id, last_shelf_name, barcode, photo_path,
                     quantity, unit, purchase_date, last_price_cents, track_price_history,
                     expiration_date, opened_date, shelf_life_unopened_days,
                     shelf_life_opened_days, alert_window_days, tags, notes, reason)
                SELECT i.name, i.shelf_id, s.name, i.barcode, i.photo_path,
                       i.quantity, i.unit, i.purchase_date, i.price_cents,
                       i.track_price_history, i.expiration_date, i.opened_date,
                       i.shelf_life_unopened_days, i.shelf_life_opened_days,
                       i.alert_window_days,
                       (SELECT group_concat(t.name, ', ')
                        FROM item_tags it JOIN tags t ON t.id = it.tag_id
                        WHERE it.item_id = i.id),
                       i.notes, ?
                FROM items i
                LEFT JOIN shelves s ON s.id = i.shelf_id
                WHERE i.id = ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (reason == null || reason.isBlank()) {
                ps.setNull(1, java.sql.Types.VARCHAR);
            } else {
                ps.setString(1, reason.trim());
            }
            ps.setInt(2, itemId);
            ps.executeUpdate();
        }
    }

    /**
     * Deletes an item. Its tag links are removed automatically by the cascade
     * declared on item_tags, so this no longer fails on tagged items.
     */
    public boolean delete(Connection conn, int itemId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM items WHERE id = ?")) {
            ps.setInt(1, itemId);
            return ps.executeUpdate() > 0;
        }
    }

    public int countAll(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM items")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    // ==================== MAPPING ====================

    /**
     * Reads a whole result set, then attaches tags for all of the rows at once.
     */
    private List<Item> collect(Connection conn, ResultSet rs) throws SQLException {
        List<Item> items = new ArrayList<>();
        while (rs.next()) {
            items.add(mapRow(rs));
        }
        if (!items.isEmpty()) {
            Map<Integer, Set<String>> tagsByItem = tagDao.findTagsForAllItems(conn);
            for (Item item : items) {
                Set<String> tags = tagsByItem.get(item.getId());
                if (tags != null) {
                    item.setTags(tags);
                }
            }
        }
        return items;
    }

    private Item mapRow(ResultSet rs) throws SQLException {
        Item item = new Item();
        item.setId(rs.getInt("id"));

        int shelfId = rs.getInt("shelf_id");
        item.setShelfId(rs.wasNull() ? null : shelfId);

        item.setName(rs.getString("name"));
        item.setBarcode(rs.getString("barcode"));
        item.setPhotoPath(rs.getString("photo_path"));
        item.setQuantity(rs.getDouble("quantity"));
        item.setUnit(rs.getString("unit"));
        item.setPurchaseDate(Dates.fromStorage(rs.getString("purchase_date")));

        long price = rs.getLong("price_cents");
        item.setPriceCents(rs.wasNull() ? null : price);

        item.setTrackPriceHistory(rs.getInt("track_price_history") == 1);
        item.setExpirationDate(Dates.fromStorage(rs.getString("expiration_date")));
        item.setOpenedDate(Dates.fromStorage(rs.getString("opened_date")));

        int unopened = rs.getInt("shelf_life_unopened_days");
        item.setShelfLifeUnopenedDays(rs.wasNull() ? null : unopened);

        int opened = rs.getInt("shelf_life_opened_days");
        item.setShelfLifeOpenedDays(rs.wasNull() ? null : opened);

        item.setAlertWindowDays(rs.getInt("alert_window_days"));
        item.setNotes(rs.getString("notes"));
        item.setCreatedDate(Dates.fromStorage(rs.getString("created_date")));
        return item;
    }

    /**
     * Binds parameters 1 to 15, which are identical for insert and update.
     * Keeping this in one place means the two statements cannot drift apart.
     */
    private void bindWritableColumns(PreparedStatement ps, Item item) throws SQLException {
        setNullableInt(ps, 1, item.getShelfId());
        ps.setString(2, item.getName() == null ? null : item.getName().trim());
        ps.setString(3, blankToNull(item.getBarcode()));
        ps.setString(4, blankToNull(item.getPhotoPath()));
        ps.setDouble(5, item.getQuantity());
        ps.setString(6, blankToNull(item.getUnit()));
        ps.setString(7, Dates.toStorage(item.getPurchaseDate()));

        if (item.getPriceCents() == null) {
            ps.setNull(8, Types.INTEGER);
        } else {
            ps.setLong(8, item.getPriceCents());
        }

        ps.setInt(9, item.isTrackPriceHistory() ? 1 : 0);
        ps.setString(10, Dates.toStorage(item.getExpirationDate()));
        ps.setString(11, Dates.toStorage(item.getOpenedDate()));
        setNullableInt(ps, 12, item.getShelfLifeUnopenedDays());
        setNullableInt(ps, 13, item.getShelfLifeOpenedDays());
        ps.setInt(14, item.getAlertWindowDays());
        ps.setString(15, blankToNull(item.getNotes()));
    }

    private void setNullableInt(PreparedStatement ps, int index, Integer value)
            throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.INTEGER);
        } else {
            ps.setInt(index, value);
        }
    }

    private String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
