package com.shelves.model;

import com.shelves.util.Money;

import java.time.LocalDate;
import java.util.Locale;

/**
 * An archived item, holding everything needed to show it in the Deleted Items
 * list and to rebuild it as a real item if the user restores it.
 * <p>
 * This is a snapshot taken at the moment of deletion, not a live reference. The
 * shelf it came from may since have been renamed or deleted, so both the shelf
 * id and the shelf name at the time are kept; restore uses the id when the shelf
 * still exists and falls back to unfiled when it does not.
 */
public class DeletedItem {

    private int id;
    private String name;
    private Integer originalShelfId;
    private String lastShelfName;
    private String barcode;
    private String photoPath;
    private double quantity = 1;
    private String unit;
    private LocalDate purchaseDate;
    private Long lastPriceCents;
    private boolean trackPriceHistory;
    private LocalDate expirationDate;
    private LocalDate openedDate;
    private Integer shelfLifeUnopenedDays;
    private Integer shelfLifeOpenedDays;
    private int alertWindowDays = Item.DEFAULT_ALERT_WINDOW_DAYS;
    private String tags;
    private String notes;
    private String reason;
    private String deletedDate;
    private LocalDate lastBoughtDate;

    /**
     * The product key this item logged its history under, derived the same way
     * as a live item so the archived item can still be matched to its purchase
     * history. Kept identical to {@code Item.productKey()} on purpose.
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

    /** Rebuilds a live item from this archived snapshot, for restoring. */
    public Item toItem() {
        Item item = new Item();
        item.setName(name);
        item.setShelfId(originalShelfId);
        item.setBarcode(barcode);
        item.setPhotoPath(photoPath);
        item.setQuantity(quantity <= 0 ? 1 : quantity);
        item.setUnit(unit);
        item.setPurchaseDate(purchaseDate);
        item.setPriceCents(lastPriceCents);
        item.setTrackPriceHistory(trackPriceHistory);
        item.setExpirationDate(expirationDate);
        item.setOpenedDate(openedDate);
        item.setShelfLifeUnopenedDays(shelfLifeUnopenedDays);
        item.setShelfLifeOpenedDays(shelfLifeOpenedDays);
        item.setAlertWindowDays(alertWindowDays);
        item.setNotes(notes);
        if (tags != null && !tags.isBlank()) {
            for (String tag : tags.split(",")) {
                item.addTag(tag.trim());
            }
        }
        return item;
    }

    /** Last known price, formatted, or an em dash if none. */
    public String priceDisplay() {
        return Money.formatOrDash(lastPriceCents);
    }

    /** The shelf name to show in the list, or "Unfiled" if it had none. */
    public String shelfDisplay() {
        return lastShelfName == null || lastShelfName.isBlank() ? "Unfiled" : lastShelfName;
    }

    // === Getters and setters ===

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Integer getOriginalShelfId() {
        return originalShelfId;
    }

    public void setOriginalShelfId(Integer originalShelfId) {
        this.originalShelfId = originalShelfId;
    }

    public String getLastShelfName() {
        return lastShelfName;
    }

    public void setLastShelfName(String lastShelfName) {
        this.lastShelfName = lastShelfName;
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

    public LocalDate getPurchaseDate() {
        return purchaseDate;
    }

    public void setPurchaseDate(LocalDate purchaseDate) {
        this.purchaseDate = purchaseDate;
    }

    public Long getLastPriceCents() {
        return lastPriceCents;
    }

    public void setLastPriceCents(Long lastPriceCents) {
        this.lastPriceCents = lastPriceCents;
    }

    public boolean isTrackPriceHistory() {
        return trackPriceHistory;
    }

    public void setTrackPriceHistory(boolean trackPriceHistory) {
        this.trackPriceHistory = trackPriceHistory;
    }

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

    public String getTags() {
        return tags;
    }

    public void setTags(String tags) {
        this.tags = tags;
    }

    public String getTagsDisplay() {
        return tags == null ? "" : tags;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getReasonDisplay() {
        return reason == null || reason.isBlank() ? "\u2014" : reason;
    }

    public String getDeletedDate() {
        return deletedDate;
    }

    public void setDeletedDate(String deletedDate) {
        this.deletedDate = deletedDate;
    }

    public String getQuantityDisplay() {
        String number = quantity == Math.rint(quantity)
                ? String.valueOf((long) quantity)
                : String.valueOf(quantity);
        return unit == null || unit.isBlank() ? number : number + " " + unit;
    }

    public LocalDate getLastBoughtDate() {
        return lastBoughtDate;
    }

    public void setLastBoughtDate(LocalDate lastBoughtDate) {
        this.lastBoughtDate = lastBoughtDate;
    }

    /** The last-bought date formatted for the table, or an em dash if unknown. */
    public String lastBoughtDisplay() {
        return lastBoughtDate == null
                ? "\u2014"
                : com.shelves.util.Dates.DISPLAY.format(lastBoughtDate);
    }
}
