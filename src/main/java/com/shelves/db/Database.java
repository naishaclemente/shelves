package com.shelves.db;

import com.shelves.exception.DataAccessException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Owns the SQLite connection and the schema.
 * <p>
 * Two things here matter more than they look:
 * <ul>
 *   <li>Every connection turns foreign keys on. SQLite disables them by
 *       default, per connection, for backwards compatibility.</li>
 *   <li>{@link #transaction} exists so that multi statement operations either
 *       fully happen or fully do not. Archiving an item and then deleting it
 *       are two writes that must not be allowed to come apart.</li>
 * </ul>
 */
public class Database {

    /** Bumped whenever the schema changes, so migrations can be applied. */
    private static final int SCHEMA_VERSION = 3;

    private final Path dataDirectory;
    private final Path databaseFile;
    private final Path photoDirectory;
    private final String url;

    /**
     * Opens the database in the default location, {@code ~/.shelves}.
     * Override with {@code -Dshelves.home=/some/path}, which is what the tests
     * use to avoid touching real data.
     */
    public Database() {
        this(Paths.get(System.getProperty("shelves.home",
                System.getProperty("user.home") + java.io.File.separator + ".shelves")));
    }

    public Database(Path dataDirectory) {
        this.dataDirectory = dataDirectory;
        this.databaseFile = dataDirectory.resolve("shelves.db");
        this.photoDirectory = dataDirectory.resolve("photos");
        this.url = "jdbc:sqlite:" + databaseFile.toAbsolutePath();

        try {
            Files.createDirectories(photoDirectory);
        } catch (IOException e) {
            throw new DataAccessException(
                    "Could not create the Shelves data folder at " + dataDirectory, e);
        }
    }

    public Path getPhotoDirectory() {
        return photoDirectory;
    }

    public Path getDataDirectory() {
        return dataDirectory;
    }

    public Path getDatabaseFile() {
        return databaseFile;
    }

    /**
     * Opens a connection with foreign key enforcement switched on.
     * Callers are responsible for closing it; prefer {@link #query} or
     * {@link #transaction}, which close it for you.
     */
    public Connection open() throws SQLException {
        Connection conn = DriverManager.getConnection(url);
        try (Statement pragma = conn.createStatement()) {
            pragma.execute("PRAGMA foreign_keys = ON");
        } catch (SQLException e) {
            conn.close();
            throw e;
        }
        return conn;
    }

    /**
     * Runs read work on a single connection and returns its result.
     * <p>
     * Reusing one connection for a whole operation is what keeps loading a
     * table from opening a fresh connection per row.
     *
     * @param description used in the error message if something fails
     */
    public <T> T query(String description, ConnectionCallback<T> work) {
        try (Connection conn = open()) {
            return work.run(conn);
        } catch (SQLException e) {
            throw new DataAccessException("Could not " + description + ".", e);
        }
    }

    /** Runs read work that returns nothing. */
    public void execute(String description, VoidConnectionCallback work) {
        query(description, conn -> {
            work.run(conn);
            return null;
        });
    }

    /**
     * Runs write work inside a transaction, committing on success and rolling
     * back on any failure.
     */
    public <T> T transaction(String description, ConnectionCallback<T> work) {
        Connection conn = null;
        try {
            conn = open();
            conn.setAutoCommit(false);
            try {
                T result = work.run(conn);
                conn.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                safeRollback(conn);
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new DataAccessException("Could not " + description + ".", e);
        } finally {
            closeQuietly(conn);
        }
    }

    /** Runs write work inside a transaction, returning nothing. */
    public void transact(String description, VoidConnectionCallback work) {
        transaction(description, conn -> {
            work.run(conn);
            return null;
        });
    }

    private void safeRollback(Connection conn) {
        try {
            conn.rollback();
        } catch (SQLException rollbackFailure) {
            // Nothing useful left to do; the original exception is the one
            // worth reporting, so this is deliberately not rethrown.
            System.err.println("Rollback failed: " + rollbackFailure.getMessage());
        }
    }

    private void closeQuietly(Connection conn) {
        if (conn != null) {
            try {
                conn.close();
            } catch (SQLException ignored) {
                // Closing failed after the work already finished; nothing to do.
            }
        }
    }

    // ==================== SCHEMA ====================

    /** Creates any missing tables and seeds the rows the app assumes exist. */
    public void initialise() {
        transact("set up the database", conn -> {
            createTables(conn);
            migrateColumns(conn);
            seedDefaultShelf(conn);
            setSchemaVersion(conn);
        });
    }

    /**
     * Adds columns that were introduced after a database was first created.
     * <p>
     * {@code CREATE TABLE IF NOT EXISTS} leaves an existing table exactly as it
     * was, so a database made by an earlier build would be missing newer
     * columns. Rather than force the user to delete their data, each new column
     * is added with {@code ALTER TABLE} if it is not already there. SQLite has
     * no {@code ADD COLUMN IF NOT EXISTS}, so the current columns are read first
     * and the add is skipped when the column already exists.
     */
    private void migrateColumns(Connection conn) throws SQLException {
        addColumnIfMissing(conn, "price_history", "quantity", "REAL NOT NULL DEFAULT 1");
        addColumnIfMissing(conn, "price_history", "source_item_id", "INTEGER");
        addColumnIfMissing(conn, "price_history", "kind", "TEXT NOT NULL DEFAULT 'PURCHASE'");

        // deleted_items grew from a thin record into a full archive that can
        // rebuild an item, so older databases need every new column added.
        addColumnIfMissing(conn, "deleted_items", "original_shelf_id", "INTEGER");
        addColumnIfMissing(conn, "deleted_items", "barcode", "TEXT");
        addColumnIfMissing(conn, "deleted_items", "photo_path", "TEXT");
        addColumnIfMissing(conn, "deleted_items", "purchase_date", "TEXT");
        addColumnIfMissing(conn, "deleted_items", "track_price_history",
                "INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing(conn, "deleted_items", "expiration_date", "TEXT");
        addColumnIfMissing(conn, "deleted_items", "opened_date", "TEXT");
        addColumnIfMissing(conn, "deleted_items", "shelf_life_unopened_days", "INTEGER");
        addColumnIfMissing(conn, "deleted_items", "shelf_life_opened_days", "INTEGER");
        addColumnIfMissing(conn, "deleted_items", "alert_window_days",
                "INTEGER NOT NULL DEFAULT 3");
        addColumnIfMissing(conn, "deleted_items", "tags", "TEXT");
        addColumnIfMissing(conn, "deleted_items", "reason", "TEXT");

        relaxItemsQuantityConstraint(conn);
    }

    /**
     * Rebuilds the items table if it still carries the old {@code quantity > 0}
     * check, relaxing it to {@code quantity >= 0} so an item can be marked as
     * having none left.
     * <p>
     * SQLite cannot alter a column's CHECK constraint in place, so the only way
     * to change it on an existing database is the standard table-rebuild dance:
     * make a new table with the right definition, copy the rows across, drop the
     * old one, and rename. The whole thing is guarded so it only runs when the
     * old constraint is actually present, and it runs inside the caller's
     * transaction so a failure leaves the original table untouched.
     */
    private void relaxItemsQuantityConstraint(Connection conn) throws SQLException {
        String currentSql = tableSql(conn, "items");
        if (currentSql == null || !currentSql.contains("quantity > 0")) {
            // Either a fresh table already built with the new constraint, or a
            // table that has already been migrated. Nothing to do.
            return;
        }

        try (Statement stmt = conn.createStatement()) {
            // The rebuild copies every row verbatim, shelf references included,
            // and the shelves table is untouched, so no foreign key is ever
            // left dangling. (Toggling PRAGMA foreign_keys here would be a no-op
            // anyway: SQLite ignores it inside a transaction, which this is.)
            stmt.execute("""
                    CREATE TABLE items_new (
                        id                       INTEGER PRIMARY KEY AUTOINCREMENT,
                        shelf_id                 INTEGER,
                        name                     TEXT    NOT NULL CHECK (length(trim(name)) > 0),
                        barcode                  TEXT,
                        photo_path               TEXT,
                        quantity                 REAL    NOT NULL DEFAULT 1 CHECK (quantity >= 0),
                        unit                     TEXT,
                        purchase_date            TEXT,
                        price_cents              INTEGER CHECK (price_cents IS NULL OR price_cents >= 0),
                        track_price_history      INTEGER NOT NULL DEFAULT 0,
                        expiration_date          TEXT,
                        opened_date              TEXT,
                        shelf_life_unopened_days INTEGER,
                        shelf_life_opened_days   INTEGER,
                        alert_window_days        INTEGER NOT NULL DEFAULT 3,
                        notes                    TEXT,
                        created_date             TEXT    NOT NULL DEFAULT (date('now')),
                        FOREIGN KEY (shelf_id) REFERENCES shelves(id) ON DELETE SET NULL
                    )
                    """);
            stmt.execute("""
                    INSERT INTO items_new
                        (id, shelf_id, name, barcode, photo_path, quantity, unit,
                         purchase_date, price_cents, track_price_history, expiration_date,
                         opened_date, shelf_life_unopened_days, shelf_life_opened_days,
                         alert_window_days, notes, created_date)
                    SELECT id, shelf_id, name, barcode, photo_path, quantity, unit,
                           purchase_date, price_cents, track_price_history, expiration_date,
                           opened_date, shelf_life_unopened_days, shelf_life_opened_days,
                           COALESCE(alert_window_days, 3), notes,
                           COALESCE(created_date, date('now'))
                    FROM items
                    """);
            stmt.execute("DROP TABLE items");
            stmt.execute("ALTER TABLE items_new RENAME TO items");
        }
    }

    /** The stored CREATE statement for a table, or null if it does not exist. */
    private String tableSql(Connection conn, String table) throws SQLException {
        String sql = "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private void addColumnIfMissing(Connection conn, String table, String column,
                                    String definition) throws SQLException {
        if (columnExists(conn, table, column)) {
            return;
        }
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        }
    }

    private boolean columnExists(Connection conn, String table, String column)
            throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private void createTables(Connection conn) throws SQLException {
        String shelves = """
                CREATE TABLE IF NOT EXISTS shelves (
                    id           INTEGER PRIMARY KEY AUTOINCREMENT,
                    name         TEXT    NOT NULL UNIQUE COLLATE NOCASE,
                    is_default   INTEGER NOT NULL DEFAULT 0,
                    created_date TEXT    NOT NULL DEFAULT (date('now'))
                )
                """;

        // ON DELETE SET NULL: deleting a shelf must not delete its contents.
        // The items simply become unfiled and remain on the Master Shelf.
        String items = """
                CREATE TABLE IF NOT EXISTS items (
                    id                       INTEGER PRIMARY KEY AUTOINCREMENT,
                    shelf_id                 INTEGER,
                    name                     TEXT    NOT NULL CHECK (length(trim(name)) > 0),
                    barcode                  TEXT,
                    photo_path               TEXT,
                    quantity                 REAL    NOT NULL DEFAULT 1 CHECK (quantity >= 0),
                    unit                     TEXT,
                    purchase_date            TEXT,
                    price_cents              INTEGER CHECK (price_cents IS NULL OR price_cents >= 0),
                    track_price_history      INTEGER NOT NULL DEFAULT 0,
                    expiration_date          TEXT,
                    opened_date              TEXT,
                    shelf_life_unopened_days INTEGER,
                    shelf_life_opened_days   INTEGER,
                    alert_window_days        INTEGER NOT NULL DEFAULT 3,
                    notes                    TEXT,
                    created_date             TEXT    NOT NULL DEFAULT (date('now')),
                    FOREIGN KEY (shelf_id) REFERENCES shelves(id) ON DELETE SET NULL
                )
                """;

        String tags = """
                CREATE TABLE IF NOT EXISTS tags (
                    id    INTEGER PRIMARY KEY AUTOINCREMENT,
                    name  TEXT NOT NULL UNIQUE COLLATE NOCASE,
                    color TEXT
                )
                """;

        // The composite primary key makes tagging the same item twice a no-op
        // instead of a duplicate row, and the cascades mean deleting an item or
        // a tag cleans up its links rather than failing on a constraint.
        String itemTags = """
                CREATE TABLE IF NOT EXISTS item_tags (
                    item_id INTEGER NOT NULL,
                    tag_id  INTEGER NOT NULL,
                    PRIMARY KEY (item_id, tag_id),
                    FOREIGN KEY (item_id) REFERENCES items(id) ON DELETE CASCADE,
                    FOREIGN KEY (tag_id)  REFERENCES tags(id)  ON DELETE CASCADE
                )
                """;

        // Keyed by product rather than item id, so price history survives the
        // item being used up and deleted. That is the whole point of tracking
        // cost over time. Each row is one purchase: a quantity bought at a
        // price on a date. Keyed by product so the log survives the item being
        // used up, and stamped with source_item_id so a correction can find and
        // update the most recent entry for that specific item rather than
        // adding a duplicate when the user is only fixing a typo. The kind
        // column separates purchases (stock coming in) from usage (stock going
        // out), so the same log can tell both stories.
        String priceHistory = """
                CREATE TABLE IF NOT EXISTS price_history (
                    id             INTEGER PRIMARY KEY AUTOINCREMENT,
                    product_key    TEXT    NOT NULL,
                    item_name      TEXT    NOT NULL,
                    quantity       REAL    NOT NULL DEFAULT 1,
                    price_cents    INTEGER NOT NULL,
                    recorded_on    TEXT    NOT NULL DEFAULT (date('now')),
                    source_item_id INTEGER,
                    kind           TEXT    NOT NULL DEFAULT 'PURCHASE'
                )
                """;

        // Archive of deleted items, holding enough to restore one to a real,
        // usable item rather than only showing its old details. Deleting is
        // meant to be recoverable, like a recycle bin, so everything needed to
        // rebuild the item row is kept here.
        String deletedItems = """
                CREATE TABLE IF NOT EXISTS deleted_items (
                    id                       INTEGER PRIMARY KEY AUTOINCREMENT,
                    name                     TEXT NOT NULL,
                    original_shelf_id        INTEGER,
                    last_shelf_name          TEXT,
                    barcode                  TEXT,
                    photo_path               TEXT,
                    quantity                 REAL,
                    unit                     TEXT,
                    purchase_date            TEXT,
                    last_price_cents         INTEGER,
                    track_price_history      INTEGER NOT NULL DEFAULT 0,
                    expiration_date          TEXT,
                    opened_date              TEXT,
                    shelf_life_unopened_days INTEGER,
                    shelf_life_opened_days   INTEGER,
                    alert_window_days        INTEGER NOT NULL DEFAULT 3,
                    tags                     TEXT,
                    notes                    TEXT,
                    reason                   TEXT,
                    deleted_date             TEXT NOT NULL DEFAULT (datetime('now'))
                )
                """;

        String shelfLife = """
                CREATE TABLE IF NOT EXISTS shelf_life_reference (
                    id            INTEGER PRIMARY KEY AUTOINCREMENT,
                    product_name  TEXT NOT NULL UNIQUE COLLATE NOCASE,
                    category      TEXT,
                    unopened_days INTEGER,
                    opened_days   INTEGER
                )
                """;

        // Remembers what a scanned barcode turned out to be, so the same
        // product scans instantly the second time and works offline.
        String productCache = """
                CREATE TABLE IF NOT EXISTS product_cache (
                    barcode    TEXT PRIMARY KEY,
                    name       TEXT NOT NULL,
                    brand      TEXT,
                    unit       TEXT,
                    cached_on  TEXT NOT NULL DEFAULT (date('now'))
                )
                """;

        String[] indexes = {
                "CREATE INDEX IF NOT EXISTS idx_items_shelf ON items(shelf_id)",
                "CREATE INDEX IF NOT EXISTS idx_items_expiry ON items(expiration_date)",
                "CREATE INDEX IF NOT EXISTS idx_item_tags_tag ON item_tags(tag_id)",
                "CREATE INDEX IF NOT EXISTS idx_price_history_key ON price_history(product_key)"
        };

        try (Statement stmt = conn.createStatement()) {
            stmt.execute(shelves);
            stmt.execute(items);
            stmt.execute(tags);
            stmt.execute(itemTags);
            stmt.execute(priceHistory);
            stmt.execute(deletedItems);
            stmt.execute(shelfLife);
            stmt.execute(productCache);
            for (String index : indexes) {
                stmt.execute(index);
            }
        }
    }

    /**
     * Guarantees the Master Shelf exists. It is a saved view meaning
     * "everything", so nothing is ever assigned to it, but it needs a row so it
     * can be listed and protected from renaming and deletion.
     */
    private void seedDefaultShelf(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT COUNT(*) FROM shelves WHERE is_default = 1")) {
            if (rs.next() && rs.getInt(1) == 0) {
                try (Statement insert = conn.createStatement()) {
                    insert.executeUpdate(
                            "INSERT INTO shelves (name, is_default) VALUES ('Master Shelf', 1)");
                }
            }
        }
    }

    private void setSchemaVersion(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA user_version = " + SCHEMA_VERSION);
        }
    }

    /** Reads the schema version recorded in the file. */
    public int getSchemaVersion() {
        return query("read the schema version", conn -> {
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("PRAGMA user_version")) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        });
    }

    // ==================== CALLBACK TYPES ====================

    /** Work that runs on a borrowed connection and returns a value. */
    @FunctionalInterface
    public interface ConnectionCallback<T> {
        T run(Connection conn) throws SQLException;
    }

    /** Work that runs on a borrowed connection and returns nothing. */
    @FunctionalInterface
    public interface VoidConnectionCallback {
        void run(Connection conn) throws SQLException;
    }
}
