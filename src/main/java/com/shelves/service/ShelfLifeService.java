package com.shelves.service;

import com.shelves.db.Database;
import com.shelves.db.dao.ShelfLifeDao;
import com.shelves.exception.DataAccessException;
import com.shelves.model.Item;
import com.shelves.model.ShelfLifeEntry;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The built-in shelf life reference.
 * <p>
 * The data ships as a CSV resource and is copied into the database the first
 * time the app runs. Once it is a table it can be queried, matched against and
 * extended by the user like anything else, rather than being re-parsed on every
 * lookup.
 */
public class ShelfLifeService {

    private static final String RESOURCE = "/com/shelves/shelf_life.csv";

    private final Database database;
    private final ShelfLifeDao shelfLifeDao = new ShelfLifeDao();

    public ShelfLifeService(Database database) {
        this.database = database;
    }

    /** Loads the bundled reference data if the table is still empty. */
    public int seedIfEmpty() {
        return database.transaction("load the shelf life reference", conn -> {
            if (!shelfLifeDao.isEmpty(conn)) {
                return 0;
            }
            return shelfLifeDao.insertAll(conn, readBundledEntries());
        });
    }

    /**
     * Looks up suggested shelf life for a product name the user has typed.
     * Falls back to a partial match, so "Kraft Shredded Cheddar Cheese" still
     * finds the entry for "cheddar cheese".
     */
    public Optional<ShelfLifeEntry> lookup(String productName) {
        return database.query("look up shelf life",
                conn -> shelfLifeDao.findBestMatch(conn, productName));
    }

    public List<ShelfLifeEntry> findAll() {
        return database.query("list the shelf life reference", shelfLifeDao::findAll);
    }

    public List<String> findAllProductNames() {
        return database.query("list reference products", shelfLifeDao::findAllNames);
    }

    /**
     * Fills in an item's shelf life fields from the reference, without
     * overwriting anything the user has already entered by hand.
     *
     * @return true if anything was filled in
     */
    public boolean applySuggestion(Item item) {
        if (item.getName() == null || item.getName().isBlank()) {
            return false;
        }
        Optional<ShelfLifeEntry> match = lookup(item.getName());
        if (match.isEmpty()) {
            return false;
        }

        ShelfLifeEntry entry = match.get();
        boolean changed = false;

        if (item.getShelfLifeUnopenedDays() == null && entry.getUnopenedDays() != null) {
            item.setShelfLifeUnopenedDays(entry.getUnopenedDays());
            changed = true;
        }
        if (item.getShelfLifeOpenedDays() == null && entry.getOpenedDays() != null) {
            item.setShelfLifeOpenedDays(entry.getOpenedDays());
            changed = true;
        }

        // With a purchase date and an unopened life but no printed date, we can
        // offer an estimated expiry rather than leaving the field blank.
        if (item.getExpirationDate() == null
                && item.getPurchaseDate() != null
                && item.getShelfLifeUnopenedDays() != null) {
            item.setExpirationDate(
                    item.getPurchaseDate().plusDays(item.getShelfLifeUnopenedDays()));
            changed = true;
        }

        return changed;
    }

    /**
     * Reads the bundled CSV.
     * <p>
     * Blank lines and lines starting with {@code #} are ignored, and a row with
     * the wrong number of columns is skipped rather than aborting the load. One
     * malformed line should not cost the user the entire reference table.
     */
    List<ShelfLifeEntry> readBundledEntries() {
        List<ShelfLifeEntry> entries = new ArrayList<>();

        try (InputStream in = ShelfLifeService.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new DataAccessException(
                        "The bundled shelf life reference is missing from the application.");
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {

                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                        continue;
                    }
                    String[] parts = trimmed.split(",", -1);
                    if (parts.length < 4) {
                        continue;
                    }
                    String name = parts[0].trim();
                    if (name.isEmpty()) {
                        continue;
                    }
                    entries.add(new ShelfLifeEntry(
                            name,
                            emptyToNull(parts[1]),
                            parseDaysOrNull(parts[2]),
                            parseDaysOrNull(parts[3])));
                }
            }
        } catch (IOException e) {
            throw new DataAccessException("Could not read the shelf life reference.", e);
        }

        return entries;
    }

    private static String emptyToNull(String value) {
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static Integer parseDaysOrNull(String value) {
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(trimmed);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
