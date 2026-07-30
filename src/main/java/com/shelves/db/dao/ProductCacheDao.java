package com.shelves.db.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/**
 * Remembers what a scanned barcode turned out to be.
 * <p>
 * The first scan of a product may need a network lookup. Every scan after that
 * is answered from here, which makes repeat scanning instant and means barcode
 * entry keeps working with no internet connection.
 */
public class ProductCacheDao {

    /** A product identified by barcode. */
    public record CachedProduct(String barcode, String name, String brand, String unit) {

        /** Brand and name together, when a brand is known. */
        public String displayName() {
            if (brand == null || brand.isBlank()) {
                return name;
            }
            return brand + " " + name;
        }
    }

    public Optional<CachedProduct> find(Connection conn, String barcode) throws SQLException {
        String sql = "SELECT barcode, name, brand, unit FROM product_cache WHERE barcode = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, barcode.trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new CachedProduct(
                        rs.getString("barcode"),
                        rs.getString("name"),
                        rs.getString("brand"),
                        rs.getString("unit")));
            }
        }
    }

    /** Stores or refreshes what a barcode maps to. */
    public void save(Connection conn, CachedProduct product) throws SQLException {
        String sql = """
                INSERT INTO product_cache (barcode, name, brand, unit)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(barcode) DO UPDATE SET
                    name = excluded.name,
                    brand = excluded.brand,
                    unit = excluded.unit,
                    cached_on = date('now')
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, product.barcode().trim());
            ps.setString(2, product.name());
            ps.setString(3, product.brand());
            ps.setString(4, product.unit());
            ps.executeUpdate();
        }
    }
}
