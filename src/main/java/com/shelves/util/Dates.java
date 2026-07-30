package com.shelves.util;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Conversion helpers between {@link LocalDate} and the ISO-8601 text that
 * SQLite stores.
 * <p>
 * SQLite has no native date type. ISO-8601 is the right text format to use
 * because it sorts chronologically as a string, which means {@code ORDER BY}
 * and {@code BETWEEN} work correctly in SQL without any conversion.
 */
public final class Dates {

    /** Human-facing format used in tables and labels. */
    public static final DateTimeFormatter DISPLAY =
            DateTimeFormatter.ofPattern("MMM d, yyyy");

    private Dates() {
    }

    /** Converts a date to storage text, tolerating null. */
    public static String toStorage(LocalDate date) {
        return date == null ? null : date.toString();
    }

    /**
     * Reads storage text back into a date.
     *
     * @return the parsed date, or {@code null} if the text is absent or
     *         unreadable. Unreadable dates are treated as missing rather than
     *         fatal so that one bad row cannot stop the whole table loading.
     */
    public static LocalDate fromStorage(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(text.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** Formats a date for display, showing an em dash when absent. */
    public static String formatOrDash(LocalDate date) {
        return date == null ? "\u2014" : DISPLAY.format(date);
    }

    /** Returns the earlier of two dates, ignoring nulls. */
    public static LocalDate earliest(LocalDate a, LocalDate b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.isBefore(b) ? a : b;
    }
}
