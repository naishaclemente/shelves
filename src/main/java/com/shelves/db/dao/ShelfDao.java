package com.shelves.db.dao;

import com.shelves.model.Shelf;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reads and writes shelves.
 * <p>
 * Every method takes the connection to work on rather than opening its own, so
 * that a caller can run several of them inside one transaction.
 */
public class ShelfDao extends BaseDao {

    /**
     * All shelves with their item counts, default shelf first, then A to Z.
     * <p>
     * The Master Shelf counts every item in the database, because it means
     * "everything" rather than being a container items belong to.
     */
    public List<Shelf> findAll(Connection conn) throws SQLException {
        String sql = """
                SELECT s.id,
                       s.name,
                       s.is_default,
                       CASE WHEN s.is_default = 1
                            THEN (SELECT COUNT(*) FROM items)
                            ELSE (SELECT COUNT(*) FROM items i WHERE i.shelf_id = s.id)
                       END AS item_count
                FROM shelves s
                ORDER BY s.is_default DESC, s.name COLLATE NOCASE ASC
                """;

        List<Shelf> shelves = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                shelves.add(mapRow(rs));
            }
        }
        return shelves;
    }

    public Optional<Shelf> findById(Connection conn, int id) throws SQLException {
        String sql = "SELECT id, name, is_default, 0 AS item_count FROM shelves WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        }
    }

    public Optional<Shelf> findByName(Connection conn, String name) throws SQLException {
        String sql = "SELECT id, name, is_default, 0 AS item_count FROM shelves WHERE name = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        }
    }

    /** The Master Shelf. Present in every database; created at first launch. */
    public Shelf findDefault(Connection conn) throws SQLException {
        String sql = "SELECT id, name, is_default, 0 AS item_count "
                + "FROM shelves WHERE is_default = 1 LIMIT 1";
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                return mapRow(rs);
            }
        }
        throw new SQLException("The Master Shelf is missing from the database.");
    }

    /** True if a shelf with this name already exists, ignoring case. */
    public boolean nameExists(Connection conn, String name, int exceptId) throws SQLException {
        String sql = "SELECT 1 FROM shelves WHERE name = ? AND id <> ? LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            ps.setInt(2, exceptId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** Inserts a shelf and returns it with its generated id. */
    public Shelf insert(Connection conn, String name) throws SQLException {
        String sql = "INSERT INTO shelves (name, is_default) VALUES (?, 0)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            ps.executeUpdate();
        }
        return new Shelf(lastInsertId(conn), name, false);
    }

    /** Renames a shelf. Returns false if no such shelf exists. */
    public boolean rename(Connection conn, int id, String newName) throws SQLException {
        String sql = "UPDATE shelves SET name = ? WHERE id = ? AND is_default = 0";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, newName);
            ps.setInt(2, id);
            return ps.executeUpdate() > 0;
        }
    }

    /**
     * Deletes a shelf. Items on it are not deleted; the foreign key is declared
     * {@code ON DELETE SET NULL}, so they become unfiled and stay visible on
     * the Master Shelf. Losing a shelf should never mean losing inventory.
     */
    public boolean delete(Connection conn, int id) throws SQLException {
        String sql = "DELETE FROM shelves WHERE id = ? AND is_default = 0";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            return ps.executeUpdate() > 0;
        }
    }

    private Shelf mapRow(ResultSet rs) throws SQLException {
        Shelf shelf = new Shelf();
        shelf.setId(rs.getInt("id"));
        shelf.setName(rs.getString("name"));
        shelf.setDefaultShelf(rs.getInt("is_default") == 1);
        shelf.setItemCount(rs.getInt("item_count"));
        return shelf;
    }
}
