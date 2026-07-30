package com.shelves.db.dao;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Shared behaviour for the data access objects.
 * <p>
 * Exists mainly because of one portability problem: the SQLite JDBC driver does
 * not implement {@code getGeneratedKeys()} on a {@code PreparedStatement}, and
 * throws {@link java.sql.SQLFeatureNotSupportedException} if you call it.
 * Asking SQLite directly for {@code last_insert_rowid()} works on every version
 * of the driver, so every insert here goes through {@link #lastInsertId}.
 */
public abstract class BaseDao {

    /**
     * The row id created by the most recent insert on this connection.
     * <p>
     * Scoped to the connection, so a concurrent write on another connection
     * cannot return the wrong id. It must be called on the same connection that
     * performed the insert, which is why every DAO method takes one.
     */
    protected int lastInsertId(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT last_insert_rowid()")) {
            if (rs.next()) {
                return rs.getInt(1);
            }
        }
        throw new SQLException("The database did not report an id for the inserted row.");
    }
}
