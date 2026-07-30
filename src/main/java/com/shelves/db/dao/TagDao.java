package com.shelves.db.dao;

import com.shelves.model.Tag;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads and writes tags, and the links between tags and items.
 */
public class TagDao extends BaseDao {

    public List<Tag> findAll(Connection conn) throws SQLException {
        String sql = "SELECT id, name, color FROM tags ORDER BY name COLLATE NOCASE ASC";
        List<Tag> tags = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                tags.add(new Tag(rs.getInt("id"), rs.getString("name"), rs.getString("color")));
            }
        }
        return tags;
    }

    public List<String> findAllNames(Connection conn) throws SQLException {
        String sql = "SELECT name FROM tags ORDER BY name COLLATE NOCASE ASC";
        List<String> names = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                names.add(rs.getString("name"));
            }
        }
        return names;
    }

    /**
     * Finds a tag by name, creating it if it does not exist yet.
     * Returns the tag id either way.
     */
    public int findOrCreate(Connection conn, String name) throws SQLException {
        String select = "SELECT id FROM tags WHERE name = ?";
        try (PreparedStatement ps = conn.prepareStatement(select)) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("id");
                }
            }
        }

        String insert = "INSERT INTO tags (name) VALUES (?)";
        try (PreparedStatement ps = conn.prepareStatement(insert)) {
            ps.setString(1, name);
            ps.executeUpdate();
        }
        return lastInsertId(conn);
    }

    public boolean rename(Connection conn, int id, String newName) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE tags SET name = ? WHERE id = ?")) {
            ps.setString(1, newName);
            ps.setInt(2, id);
            return ps.executeUpdate() > 0;
        }
    }

    /** Deletes a tag. Its links to items disappear with it, by cascade. */
    public boolean delete(Connection conn, int id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM tags WHERE id = ?")) {
            ps.setInt(1, id);
            return ps.executeUpdate() > 0;
        }
    }

    /**
     * Replaces an item's tags with exactly the given set.
     * <p>
     * Clearing and reinserting is simpler than working out which tags were
     * added and which removed, and an item never has enough tags for the
     * difference to matter.
     */
    public void setTagsForItem(Connection conn, int itemId, Collection<String> tagNames)
            throws SQLException {

        try (PreparedStatement clear = conn.prepareStatement(
                "DELETE FROM item_tags WHERE item_id = ?")) {
            clear.setInt(1, itemId);
            clear.executeUpdate();
        }

        if (tagNames == null || tagNames.isEmpty()) {
            return;
        }

        // OR IGNORE is belt and braces: the composite primary key already makes
        // a repeated pairing impossible, and this keeps it from throwing.
        String link = "INSERT OR IGNORE INTO item_tags (item_id, tag_id) VALUES (?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(link)) {
            for (String name : tagNames) {
                if (name == null || name.isBlank()) {
                    continue;
                }
                int tagId = findOrCreate(conn, name.trim());
                ps.setInt(1, itemId);
                ps.setInt(2, tagId);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    public Set<String> findTagNamesForItem(Connection conn, int itemId) throws SQLException {
        String sql = """
                SELECT t.name
                FROM tags t
                JOIN item_tags it ON it.tag_id = t.id
                WHERE it.item_id = ?
                ORDER BY t.name COLLATE NOCASE
                """;
        Set<String> names = new LinkedHashSet<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, itemId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    names.add(rs.getString("name"));
                }
            }
        }
        return names;
    }

    /**
     * Loads the tags for every item in one query, keyed by item id.
     * <p>
     * This is what stops a table of fifty items from running fifty separate tag
     * queries as it renders.
     */
    public Map<Integer, Set<String>> findTagsForAllItems(Connection conn) throws SQLException {
        String sql = """
                SELECT it.item_id, t.name
                FROM item_tags it
                JOIN tags t ON t.id = it.tag_id
                ORDER BY it.item_id, t.name COLLATE NOCASE
                """;
        Map<Integer, Set<String>> byItem = new HashMap<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                byItem.computeIfAbsent(rs.getInt("item_id"), k -> new LinkedHashSet<>())
                      .add(rs.getString("name"));
            }
        }
        return byItem;
    }

    /** Removes tags that are no longer attached to anything. */
    public int deleteUnused(Connection conn) throws SQLException {
        String sql = "DELETE FROM tags WHERE id NOT IN (SELECT tag_id FROM item_tags)";
        try (Statement stmt = conn.createStatement()) {
            return stmt.executeUpdate(sql);
        }
    }
}
