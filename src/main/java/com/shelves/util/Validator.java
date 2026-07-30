package com.shelves.util;

import com.shelves.exception.ValidationException;
import com.shelves.model.Item;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Checks user input before it reaches the database.
 * <p>
 * Every rule is checked even after one fails, so the caller receives the full
 * list of problems at once. The database has its own constraints as a second
 * line of defence, but relying on them alone would mean the only feedback a
 * user gets is a constraint violation message written for a programmer.
 */
public final class Validator {

    public static final int MAX_NAME_LENGTH = 120;
    public static final int MAX_UNIT_LENGTH = 24;
    public static final int MAX_NOTES_LENGTH = 2000;
    public static final int MAX_SHELF_NAME_LENGTH = 60;
    public static final int MAX_TAG_NAME_LENGTH = 40;
    public static final int MAX_ALERT_WINDOW_DAYS = 365;
    public static final double MAX_QUANTITY = 1_000_000d;

    private Validator() {
    }

    /**
     * Validates a complete item.
     *
     * @throws ValidationException listing every problem found
     */
    public static void validateItem(Item item) {
        List<String> errors = new ArrayList<>();

        if (item == null) {
            throw new ValidationException("No item was supplied.");
        }

        // --- Name ---
        if (isBlank(item.getName())) {
            errors.add("Name is required.");
        } else if (item.getName().trim().length() > MAX_NAME_LENGTH) {
            errors.add("Name must be " + MAX_NAME_LENGTH + " characters or fewer.");
        }

        // --- Quantity ---
        // Zero is allowed: it means "none left" without deleting the item, which
        // is how a Used or Expired change can bring the count down to nothing.
        // Negative quantities are still nonsense and are rejected.
        if (Double.isNaN(item.getQuantity()) || Double.isInfinite(item.getQuantity())) {
            errors.add("Quantity must be a number.");
        } else if (item.getQuantity() < 0) {
            errors.add("Quantity cannot be negative.");
        } else if (item.getQuantity() > MAX_QUANTITY) {
            errors.add("Quantity must be less than " + (long) MAX_QUANTITY + ".");
        }

        if (item.getUnit() != null && item.getUnit().trim().length() > MAX_UNIT_LENGTH) {
            errors.add("Unit must be " + MAX_UNIT_LENGTH + " characters or fewer.");
        }

        // --- Price ---
        if (item.getPriceCents() != null && item.getPriceCents() < 0) {
            errors.add("Price cannot be negative.");
        }

        // --- Dates ---
        LocalDate today = LocalDate.now();
        LocalDate purchased = item.getPurchaseDate();
        LocalDate expires = item.getExpirationDate();
        LocalDate opened = item.getOpenedDate();

        if (purchased != null && purchased.isAfter(today)) {
            errors.add("Purchase date cannot be in the future.");
        }
        if (opened != null && opened.isAfter(today)) {
            errors.add("Opened date cannot be in the future.");
        }
        if (purchased != null && opened != null && opened.isBefore(purchased)) {
            errors.add("Opened date cannot be before the purchase date.");
        }
        if (purchased != null && expires != null && expires.isBefore(purchased)) {
            errors.add("Expiration date cannot be before the purchase date.");
        }

        // --- Shelf life ---
        if (item.getShelfLifeUnopenedDays() != null && item.getShelfLifeUnopenedDays() < 0) {
            errors.add("Unopened shelf life cannot be negative.");
        }
        if (item.getShelfLifeOpenedDays() != null && item.getShelfLifeOpenedDays() < 0) {
            errors.add("Opened shelf life cannot be negative.");
        }

        // --- Alerts ---
        if (item.getAlertWindowDays() < 0) {
            errors.add("Alert window cannot be negative.");
        } else if (item.getAlertWindowDays() > MAX_ALERT_WINDOW_DAYS) {
            errors.add("Alert window must be " + MAX_ALERT_WINDOW_DAYS + " days or fewer.");
        }

        // --- Notes ---
        if (item.getNotes() != null && item.getNotes().length() > MAX_NOTES_LENGTH) {
            errors.add("Notes must be " + MAX_NOTES_LENGTH + " characters or fewer.");
        }

        // --- Barcode ---
        if (item.getBarcode() != null && !item.getBarcode().isBlank()
                && !item.getBarcode().trim().matches("\\d{6,14}")) {
            errors.add("Barcode must be 6 to 14 digits.");
        }

        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }
    }

    /** Validates a shelf name typed by the user. Returns the cleaned name. */
    public static String validateShelfName(String name) {
        if (isBlank(name)) {
            throw new ValidationException("Shelf name is required.");
        }
        String cleaned = name.trim();
        if (cleaned.length() > MAX_SHELF_NAME_LENGTH) {
            throw new ValidationException(
                    "Shelf name must be " + MAX_SHELF_NAME_LENGTH + " characters or fewer.");
        }
        return cleaned;
    }

    /** Validates a tag name typed by the user. Returns the cleaned name. */
    public static String validateTagName(String name) {
        if (isBlank(name)) {
            throw new ValidationException("Tag name is required.");
        }
        String cleaned = name.trim();
        if (cleaned.length() > MAX_TAG_NAME_LENGTH) {
            throw new ValidationException(
                    "Tag name must be " + MAX_TAG_NAME_LENGTH + " characters or fewer.");
        }
        if (cleaned.contains(",")) {
            throw new ValidationException("Tag names cannot contain commas.");
        }
        return cleaned;
    }

    /**
     * Parses a quantity typed by the user.
     *
     * @throws ValidationException if the text is not a usable number
     */
    public static double parseQuantity(String text) {
        if (isBlank(text)) {
            throw new ValidationException("Quantity is required.");
        }
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException e) {
            throw new ValidationException("Quantity must be a number, for example 2 or 1.5.");
        }
    }

    /**
     * Parses a price typed by the user.
     *
     * @return cents, or {@code null} when the field was left empty
     * @throws ValidationException if the text is present but unusable
     */
    public static Long parsePrice(String text) {
        try {
            return Money.parse(text);
        } catch (NumberFormatException e) {
            throw new ValidationException("Price must be an amount, for example 3.50.");
        }
    }

    /**
     * Parses a whole number of days.
     *
     * @return the value, or {@code null} when the field was left empty
     */
    public static Integer parseDays(String text, String fieldLabel) {
        if (isBlank(text)) {
            return null;
        }
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            throw new ValidationException(fieldLabel + " must be a whole number of days.");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
