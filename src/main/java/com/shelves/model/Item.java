package com.shelves.model;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A single thing being tracked: six apples in the kitchen, one open jar of
 * mayonnaise, a case of hot dog buns at the concession stand.
 * <p>
 * Dates are {@link LocalDate} rather than {@code String} so that expiry
 * arithmetic is possible without reparsing text everywhere, and price is a
 * whole number of cents rather than a double so that totals stay exact.
 */
public class Item {

    /** Default number of days before expiry that an item starts warning. */
    public static final int DEFAULT_ALERT_WINDOW_DAYS = 3;

    private int id;

    /** Null means the item is not filed on any shelf yet. */
    private Integer shelfId;

    private String name;
    private String barcode;
    private String photoPath;

    private double quantity;
    private String unit;

    private LocalDate purchaseDate;
    private Long priceCents;
    private boolean trackPriceHistory;

    private LocalDate expirationDate;
    private LocalDate openedDate;
    private Integer shelfLifeUnopenedDays;
    private Integer shelfLifeOpenedDays;
    private int alertWindowDays = DEFAULT_ALERT_WINDOW_DAYS;

    private String notes;
    private LocalDate createdDate;

    /**
     * Tags live in the item_tags join table, not on the item row. They are held
     * here once loaded so the UI can render them without another query.
     */
    private final Set<String> tags = new LinkedHashSet<>();

    public Item() {
    }

    public Item(String name, double quantity, String unit) {
        this.name = name;
        this.quantity = quantity;
        this.unit = unit;
    }

    /**
     * The identity used to group this item with past and future purchases of
     * the same product, for price history.
     * <p>
     * A barcode is authoritative when present. Otherwise the name is normalised
     * to lower case with collapsed whitespace, so that "Whole Milk" and
     * "whole  milk" are recognised as the same product.
     */
    public String productKey() {
        if (barcode != null && !barcode.isBlank()) {
            return "upc:" + barcode.trim();
        }
        if (name == null || name.isBlank()) {
            return "unknown";
        }
        return "name:" + name.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    /** True once the user has recorded an opened date. */
    public boolean isOpened() {
        return openedDate != null;
    }

    // === Identity ===

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public Integer getShelfId() {
        return shelfId;
    }

    public void setShelfId(Integer shelfId) {
        this.shelfId = shelfId;
    }

    // === Description ===

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getBarcode() {
        return barcode;
    }

    public void setBarcode(String barcode) {
        this.barcode = barcode;
    }

    public String getPhotoPath() {
        return photoPath;
    }

    public void setPhotoPath(String photoPath) {
        this.photoPath = photoPath;
    }

    public double getQuantity() {
        return quantity;
    }

    public void setQuantity(double quantity) {
        this.quantity = quantity;
    }

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }

    // === Purchase ===

    public LocalDate getPurchaseDate() {
        return purchaseDate;
    }

    public void setPurchaseDate(LocalDate purchaseDate) {
        this.purchaseDate = purchaseDate;
    }

    public Long getPriceCents() {
        return priceCents;
    }

    public void setPriceCents(Long priceCents) {
        this.priceCents = priceCents;
    }

    public boolean isTrackPriceHistory() {
        return trackPriceHistory;
    }

    public void setTrackPriceHistory(boolean trackPriceHistory) {
        this.trackPriceHistory = trackPriceHistory;
    }

    // === Shelf life ===

    public LocalDate getExpirationDate() {
        return expirationDate;
    }

    public void setExpirationDate(LocalDate expirationDate) {
        this.expirationDate = expirationDate;
    }

    public LocalDate getOpenedDate() {
        return openedDate;
    }

    public void setOpenedDate(LocalDate openedDate) {
        this.openedDate = openedDate;
    }

    public Integer getShelfLifeUnopenedDays() {
        return shelfLifeUnopenedDays;
    }

    public void setShelfLifeUnopenedDays(Integer shelfLifeUnopenedDays) {
        this.shelfLifeUnopenedDays = shelfLifeUnopenedDays;
    }

    public Integer getShelfLifeOpenedDays() {
        return shelfLifeOpenedDays;
    }

    public void setShelfLifeOpenedDays(Integer shelfLifeOpenedDays) {
        this.shelfLifeOpenedDays = shelfLifeOpenedDays;
    }

    public int getAlertWindowDays() {
        return alertWindowDays;
    }

    public void setAlertWindowDays(int alertWindowDays) {
        this.alertWindowDays = alertWindowDays;
    }

    // === Extras ===

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public LocalDate getCreatedDate() {
        return createdDate;
    }

    public void setCreatedDate(LocalDate createdDate) {
        this.createdDate = createdDate;
    }

    /** Live view of this item's tags; a set, so adding a duplicate is a no-op. */
    public Set<String> getTags() {
        return tags;
    }

    public void setTags(Iterable<String> newTags) {
        tags.clear();
        if (newTags != null) {
            for (String tag : newTags) {
                addTag(tag);
            }
        }
    }

    public void addTag(String tagName) {
        if (tagName != null && !tagName.isBlank()) {
            tags.add(tagName.trim());
        }
    }

    public void removeTag(String tagName) {
        tags.remove(tagName);
    }

    /** Tags as a comma separated string, for table cells and printing. */
    public String getTagsDisplay() {
        return tags.isEmpty() ? "" : String.join(", ", tags);
    }

    /** Quantity without a trailing ".0" when it is a whole number. */
    public String getQuantityDisplay() {
        String number = quantity == Math.rint(quantity)
                ? String.valueOf((long) quantity)
                : String.valueOf(quantity);
        return unit == null || unit.isBlank() ? number : number + " " + unit;
    }

    /** A detached copy, used so an edit dialog can be cancelled cleanly. */
    public Item copy() {
        Item copy = new Item();
        copy.id = id;
        copy.shelfId = shelfId;
        copy.name = name;
        copy.barcode = barcode;
        copy.photoPath = photoPath;
        copy.quantity = quantity;
        copy.unit = unit;
        copy.purchaseDate = purchaseDate;
        copy.priceCents = priceCents;
        copy.trackPriceHistory = trackPriceHistory;
        copy.expirationDate = expirationDate;
        copy.openedDate = openedDate;
        copy.shelfLifeUnopenedDays = shelfLifeUnopenedDays;
        copy.shelfLifeOpenedDays = shelfLifeOpenedDays;
        copy.alertWindowDays = alertWindowDays;
        copy.notes = notes;
        copy.createdDate = createdDate;
        copy.tags.addAll(tags);
        return copy;
    }

    /** Convenience for the manual test harness. */
    public List<String> tagList() {
        return new ArrayList<>(tags);
    }

    @Override
    public String toString() {
        return name + " (" + getQuantityDisplay() + ")";
    }
}
