package com.shelves.model;

import com.shelves.util.Money;

import java.time.LocalDate;

/**
 * One purchase of a product: a quantity bought at a price on a date.
 * <p>
 * The log is keyed by {@code productKey} rather than by item id on purpose. An
 * item row represents a specific thing currently on a shelf, and it goes away
 * when that thing is used up. Tracking cost and restocking over time has to
 * outlive individual items, otherwise buying milk in January and again in March
 * would produce two unrelated records with no way to compare them.
 * <p>
 * {@code sourceItemId} records which item wrote the entry, so a later correction
 * to that item can find and update its most recent entry instead of adding a
 * duplicate. It is not a foreign key: the item it points to may be long gone,
 * and the entry must survive that.
 */
public class PricePoint {

    private int id;
    private String productKey;
    private String itemName;
    private double quantity = 1;
    private long priceCents;
    private LocalDate recordedOn;
    private Integer sourceItemId;
    private HistoryKind kind = HistoryKind.PURCHASE;

    public PricePoint() {
    }

    public PricePoint(String productKey, String itemName, double quantity,
                      long priceCents, LocalDate recordedOn, Integer sourceItemId) {
        this.productKey = productKey;
        this.itemName = itemName;
        this.quantity = quantity;
        this.priceCents = priceCents;
        this.recordedOn = recordedOn;
        this.sourceItemId = sourceItemId;
    }

    public HistoryKind getKind() {
        return kind;
    }

    public void setKind(HistoryKind kind) {
        this.kind = kind == null ? HistoryKind.PURCHASE : kind;
    }

    /** True if this entry is a purchase rather than a usage event. */
    public boolean isPurchase() {
        return kind == HistoryKind.PURCHASE;
    }

    /** Total spent on this purchase: unit price times quantity, in cents. */
    public long totalCents() {
        return Math.round(priceCents * quantity);
    }

    /** Unit price formatted for display, e.g. $3.50. */
    public String unitPriceDisplay() {
        return Money.format(priceCents);
    }

    /** Total for the purchase formatted for display. */
    public String totalDisplay() {
        return Money.format(totalCents());
    }

    /** Quantity without a trailing ".0" when it is whole. */
    public String quantityDisplay() {
        return quantity == Math.rint(quantity)
                ? String.valueOf((long) quantity)
                : String.valueOf(quantity);
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getProductKey() {
        return productKey;
    }

    public void setProductKey(String productKey) {
        this.productKey = productKey;
    }

    public String getItemName() {
        return itemName;
    }

    public void setItemName(String itemName) {
        this.itemName = itemName;
    }

    public double getQuantity() {
        return quantity;
    }

    public void setQuantity(double quantity) {
        this.quantity = quantity;
    }

    public long getPriceCents() {
        return priceCents;
    }

    public void setPriceCents(long priceCents) {
        this.priceCents = priceCents;
    }

    public LocalDate getRecordedOn() {
        return recordedOn;
    }

    public void setRecordedOn(LocalDate recordedOn) {
        this.recordedOn = recordedOn;
    }

    public Integer getSourceItemId() {
        return sourceItemId;
    }

    public void setSourceItemId(Integer sourceItemId) {
        this.sourceItemId = sourceItemId;
    }
}
