package com.shelves.db.dao;

import com.shelves.model.ShelfLifeEntry;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The built-in shelf life reference: how long common products keep.
 * Seeded once from a bundled CSV, then read like any other table.
 */
public class ShelfLifeDao {

    public boolean isEmpty(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM shelf_life_reference")) {
            return !rs.next() || rs.getInt(1) == 0;
        }
    }

    /** Inserts reference rows, skipping any product already present. */
    public int insertAll(Connection conn, List<ShelfLifeEntry> entries) throws SQLException {
        String sql = """
                INSERT OR IGNORE INTO shelf_life_reference
                    (product_name, category, unopened_days, opened_days)
                VALUES (?, ?, ?, ?)
                """;
        int inserted = 0;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (ShelfLifeEntry entry : entries) {
                ps.setString(1, entry.getProductName());
                ps.setString(2, entry.getCategory());
                if (entry.getUnopenedDays() == null) {
                    ps.setNull(3, java.sql.Types.INTEGER);
                } else {
                    ps.setInt(3, entry.getUnopenedDays());
                }
                if (entry.getOpenedDays() == null) {
                    ps.setNull(4, java.sql.Types.INTEGER);
                } else {
                    ps.setInt(4, entry.getOpenedDays());
                }
                ps.addBatch();
            }
            for (int result : ps.executeBatch()) {
                if (result > 0) {
                    inserted++;
                }
            }
        }
        return inserted;
    }

    /** Exact match on product name, ignoring case. */
    public Optional<ShelfLifeEntry> findExact(Connection conn, String productName)
            throws SQLException {
        String sql = """
                SELECT product_name, category, unopened_days, opened_days
                FROM shelf_life_reference WHERE product_name = ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, productName.trim());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        }
    }

    /**
     * Best guess for a typed name, preferring an exact match and falling back
     * to a reference product whose name appears inside what the user typed.
     * "Whole Milk 2%" should still find "milk".
     */
    public Optional<ShelfLifeEntry> findBestMatch(Connection conn, String productName)
            throws SQLException {
        if (productName == null || productName.isBlank()) {
            return Optional.empty();
        }
        Optional<ShelfLifeEntry> exact = findExact(conn, productName);
        if (exact.isPresent()) {
            return exact;
        }

        // Longest matching reference name wins, so "sour cream" beats "cream".
        String sql = """
                SELECT product_name, category, unopened_days, opened_days
                FROM shelf_life_reference
                WHERE ? LIKE '%' || product_name || '%' COLLATE NOCASE
                ORDER BY length(product_name) DESC
                LIMIT 1
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, productName.trim());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        }
    }

    public List<ShelfLifeEntry> findAll(Connection conn) throws SQLException {
        String sql = """
                SELECT product_name, category, unopened_days, opened_days
                FROM shelf_life_reference
                ORDER BY category COLLATE NOCASE, product_name COLLATE NOCASE
                """;
        List<ShelfLifeEntry> entries = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                entries.add(mapRow(rs));
            }
        }
        return entries;
    }

    /** All reference product names, for the autocomplete in the item form. */
    public List<String> findAllNames(Connection conn) throws SQLException {
        List<String> names = new ArrayList<>();
        String sql = "SELECT product_name FROM shelf_life_reference ORDER BY product_name COLLATE NOCASE";
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                names.add(rs.getString(1));
            }
        }
        return names;
    }

    private ShelfLifeEntry mapRow(ResultSet rs) throws SQLException {
        int unopened = rs.getInt("unopened_days");
        Integer unopenedDays = rs.wasNull() ? null : unopened;
        int opened = rs.getInt("opened_days");
        Integer openedDays = rs.wasNull() ? null : opened;
        return new ShelfLifeEntry(
                rs.getString("product_name"), rs.getString("category"), unopenedDays, openedDays);
    }
}
