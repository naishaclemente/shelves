package com.shelves.service;

import com.shelves.exception.DataAccessException;
import com.shelves.model.Item;
import com.shelves.model.Shelf;
import com.shelves.util.Dates;
import com.shelves.util.Money;
import com.shelves.util.SimplePdf;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns a shelf's contents into a file the user can keep or share.
 * <p>
 * Two formats, for two purposes. CSV opens in any spreadsheet and is the right
 * choice when the data will be sorted, filtered or totalled further. PDF is a
 * fixed inventory sheet with a tick box on each row, meant to be printed and
 * carried around while counting stock. The same rows go into both, so a preview
 * of one is a faithful preview of the other.
 */
public class ExportService {

    /** The columns that appear in an export, in order. */
    public static final String[] COLUMNS =
            {"Name", "Quantity", "Shelf", "Tags", "Price", "Expires", "Status", "Notes"};

    private final InventoryService inventory;

    public ExportService(InventoryService inventory) {
        this.inventory = inventory;
    }

    /**
     * The rows that would be exported for a shelf, as plain strings.
     * <p>
     * Shared by the preview and both file formats, so what the user sees before
     * confirming is exactly what lands in the file.
     */
    public List<String[]> buildRows(Shelf shelf) {
        List<Item> items = inventory.listItems(shelf);
        LocalDate today = LocalDate.now();

        List<String[]> rows = new ArrayList<>(items.size());
        for (Item item : items) {
            rows.add(new String[]{
                    safe(item.getName()),
                    item.getQuantityDisplay(),
                    safe(shelfNameOf(item)),
                    item.getTagsDisplay(),
                    Money.formatOrDash(item.getPriceCents()),
                    Dates.formatOrDash(ExpiryService.effectiveExpiry(item)),
                    ExpiryService.describe(item, today),
                    safe(item.getNotes())
            });
        }
        return rows;
    }

    /** A heading for the export, naming the shelf. */
    public String titleFor(Shelf shelf) {
        if (shelf == null || shelf.isDefaultShelf()) {
            return "Shelves \u2014 All items";
        }
        return "Shelves \u2014 " + shelf.getName();
    }

    /** A subtitle with the date and item count. */
    public String subtitleFor(Shelf shelf, int rowCount) {
        return "Exported " + Dates.DISPLAY.format(LocalDate.now())
                + "   \u00b7   " + rowCount + (rowCount == 1 ? " item" : " items");
    }

    /** Writes the shelf's contents as CSV. */
    public void exportCsv(Shelf shelf, Path file) {
        List<String[]> rows = buildRows(shelf);
        try (OutputStream out = Files.newOutputStream(file)) {
            StringBuilder csv = new StringBuilder();
            csv.append(csvLine(COLUMNS));
            for (String[] row : rows) {
                csv.append(csvLine(row));
            }
            out.write(csv.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new DataAccessException("Could not write the CSV file. "
                    + "Check you can save to that location and try again.", e);
        }
    }

    /** Writes the shelf's contents as a printable PDF inventory sheet. */
    public void exportPdf(Shelf shelf, Path file) {
        List<String[]> rows = buildRows(shelf);

        // A tick box precedes each row on the printed sheet, and the notes
        // column is dropped from the PDF to keep rows on one line; notes belong
        // in the CSV where a cell can hold them.
        String[] headers = {"", "Name", "Quantity", "Shelf", "Price", "Expires", "Status"};
        float[] columnX = {0, 18, 190, 260, 350, 410, 480};

        List<String[]> pdfRows = new ArrayList<>(rows.size());
        for (String[] row : rows) {
            pdfRows.add(new String[]{
                    "[ ]",       // tick box
                    row[0],      // name
                    row[1],      // quantity
                    row[2],      // shelf
                    row[4],      // price
                    row[5],      // expires
                    row[6]       // status
            });
        }

        try {
            SimplePdf.write(file, titleFor(shelf), subtitleFor(shelf, rows.size()),
                    headers, columnX, pdfRows);
        } catch (IOException e) {
            throw new DataAccessException("Could not write the PDF file. "
                    + "Check you can save to that location and try again.", e);
        }
    }

    // ==================== HELPERS ====================

    private String shelfNameOf(Item item) {
        if (item.getShelfId() == null) {
            return "Unfiled";
        }
        return inventory.listShelves().stream()
                .filter(s -> s.getId() == item.getShelfId())
                .map(Shelf::getName)
                .findFirst()
                .orElse("Unfiled");
    }

    /** One CSV row, quoting fields that contain commas, quotes or newlines. */
    private String csvLine(String[] fields) {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) {
                line.append(',');
            }
            line.append(csvField(fields[i]));
        }
        line.append('\n');
        return line.toString();
    }

    private String csvField(String value) {
        if (value == null) {
            return "";
        }
        boolean needsQuoting = value.contains(",") || value.contains("\"")
                || value.contains("\n") || value.contains("\r");
        if (!needsQuoting) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
