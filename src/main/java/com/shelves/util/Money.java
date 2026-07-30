package com.shelves.util;

import java.text.NumberFormat;
import java.util.Locale;

/**
 * Money is stored and passed around as a whole number of cents.
 * <p>
 * Doubles cannot represent most decimal amounts exactly, so summing prices
 * across a shelf would slowly drift away from the real total. Integer cents
 * are exact, and formatting happens only at the moment of display.
 */
public final class Money {

    private static final NumberFormat CURRENCY =
            NumberFormat.getCurrencyInstance(Locale.US);

    private Money() {
    }

    /** Formats cents for display, e.g. {@code 350} becomes {@code $3.50}. */
    public static String format(long cents) {
        return CURRENCY.format(cents / 100.0);
    }

    /** Formats cents, showing an em dash when no price has been recorded. */
    public static String formatOrDash(Long cents) {
        return cents == null ? "\u2014" : format(cents);
    }

    /**
     * Parses user input such as {@code "3.50"}, {@code "$3.50"} or {@code "3"}
     * into cents.
     *
     * @return the amount in cents, or {@code null} if the text is blank
     * @throws NumberFormatException if the text is present but not a number
     */
    public static Long parse(String text) {
        if (text == null) {
            return null;
        }
        String cleaned = text.replace("$", "").replace(",", "").trim();
        if (cleaned.isEmpty()) {
            return null;
        }
        // Round rather than truncate so "0.005" does not silently become zero.
        return Math.round(Double.parseDouble(cleaned) * 100.0);
    }

    /** Renders cents as a plain editable number, e.g. {@code "3.50"}. */
    public static String toEditableString(Long cents) {
        return cents == null ? "" : String.format(Locale.US, "%.2f", cents / 100.0);
    }
}
