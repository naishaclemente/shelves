package com.shelves.db.dao;

import com.shelves.model.DeletedItem;
import com.shelves.util.Dates;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reads and clears the archive of deleted items.
 * <p>
 * The archive is a recycle bin: rows are written here when an item is deleted
 * (by {@link ItemDao#archive}) and read back to list or restore them. Restoring
 * is handled by the service, which rebuilds a real item from the snapshot and
 * then removes the archive row through {@link #remove} here.
 */
public class DeletedItemDao {

    /** Every archived item, most recently deleted first. */
    public List<DeletedItem> findAll(Connection conn) throws SQLException {
        String sql = "SELECT * FROM deleted_items ORDER BY deleted_date DESC, id DESC";
        List<DeletedItem> items = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                items.add(mapRow(rs));
            }
        }
        return items;
    }

    public Optional<DeletedItem> findById(Connection conn, int id) throws SQLException {
        String sql = "SELECT * FROM deleted_items WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        }
    }

    public int count(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM deleted_items")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    /** Removes one archive row, after its item has been restored or purged. */
    public boolean remove(Connection conn, int id) throws SQLException {
        try (PreparedStatement ps =
                     conn.prepareStatement("DELETE FROM deleted_items WHERE id = ?")) {
            ps.setInt(1, id);
            return ps.executeUpdate() > 0;
        }
    }

    /** Empties the archive entirely. */
    public int clear(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            return stmt.executeUpdate("DELETE FROM deleted_items");
        }
    }

    private DeletedItem mapRow(ResultSet rs) throws SQLException {
        DeletedItem item = new DeletedItem();
        item.setId(rs.getInt("id"));
        item.setName(rs.getString("name"));

        int shelfId = rs.getInt("original_shelf_id");
        item.setOriginalShelfId(rs.wasNull() ? null : shelfId);
        item.setLastShelfName(rs.getString("last_shelf_name"));

        item.setBarcode(rs.getString("barcode"));
        item.setPhotoPath(rs.getString("photo_path"));
        item.setQuantity(rs.getDouble("quantity"));
        item.setUnit(rs.getString("unit"));
        item.setPurchaseDate(Dates.fromStorage(rs.getString("purchase_date")));

        long price = rs.getLong("last_price_cents");
        item.setLastPriceCents(rs.wasNull() ? null : price);

        item.setTrackPriceHistory(rs.getInt("track_price_history") == 1);
        item.setExpirationDate(Dates.fromStorage(rs.getString("expiration_date")));
        item.setOpenedDate(Dates.fromStorage(rs.getString("opened_date")));

        int unopened = rs.getInt("shelf_life_unopened_days");
        item.setShelfLifeUnopenedDays(rs.wasNull() ? null : unopened);
        int opened = rs.getInt("shelf_life_opened_days");
        item.setShelfLifeOpenedDays(rs.wasNull() ? null : opened);

        item.setAlertWindowDays(rs.getInt("alert_window_days"));
        item.setTags(rs.getString("tags"));
        item.setNotes(rs.getString("notes"));
        item.setReason(rs.getString("reason"));
        item.setDeletedDate(rs.getString("deleted_date"));
        return item;
    }
}
