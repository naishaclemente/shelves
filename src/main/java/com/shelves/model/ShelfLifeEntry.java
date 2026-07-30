package com.shelves.model;

/**
 * A row from the built-in shelf life reference: how long a commonly known
 * product keeps, unopened and once opened.
 */
public class ShelfLifeEntry {

    private final String productName;
    private final String category;
    private final Integer unopenedDays;
    private final Integer openedDays;

    public ShelfLifeEntry(String productName, String category,
                          Integer unopenedDays, Integer openedDays) {
        this.productName = productName;
        this.category = category;
        this.unopenedDays = unopenedDays;
        this.openedDays = openedDays;
    }

    public String getProductName() {
        return productName;
    }

    public String getCategory() {
        return category;
    }

    public Integer getUnopenedDays() {
        return unopenedDays;
    }

    public Integer getOpenedDays() {
        return openedDays;
    }

    @Override
    public String toString() {
        return productName;
    }
}
