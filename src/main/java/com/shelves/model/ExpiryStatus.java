package com.shelves.model;

/**
 * How urgent an item is, derived from its effective expiry date.
 * Each constant carries the CSS class the table uses to colour the row.
 */
public enum ExpiryStatus {

    EXPIRED("Expired", "status-expired"),
    EXPIRING_SOON("Expiring soon", "status-soon"),
    FRESH("Fresh", "status-fresh"),
    NO_DATE("No date", "status-none");

    private final String label;
    private final String styleClass;

    ExpiryStatus(String label, String styleClass) {
        this.label = label;
        this.styleClass = styleClass;
    }

    public String getLabel() {
        return label;
    }

    public String getStyleClass() {
        return styleClass;
    }

    /** True for the two states that should raise an alert. */
    public boolean needsAttention() {
        return this == EXPIRED || this == EXPIRING_SOON;
    }
}
