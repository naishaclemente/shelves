package com.shelves.model;

/**
 * What a history log entry records: stock coming in, or stock going out, and if
 * it went out, why.
 * <p>
 * Used and expired are kept as distinct kinds on purpose. Consuming an item and
 * throwing it away are both depletions, but they mean opposite things about how
 * well the stock is working: one is the item doing its job, the other is waste.
 * Collapsing them into a single "gone" bucket would make the usage chart lie, so
 * the distinction is captured at the point it is known and carried all the way
 * through.
 * <p>
 * Finer detail within expiry — unopened versus partially used, say — is not
 * modelled yet. When it is, it belongs on a separate field so this primary kind
 * stays a short, clean list; EXPIRED is the umbrella that such a field would
 * refine.
 */
public enum HistoryKind {

    /** A purchase: the user bought this quantity at this price. */
    PURCHASE("Purchase"),

    /** Stock used up in the normal way: consumed, served, put to use. */
    USED("Used"),

    /** Stock thrown out: expired, spoiled, or otherwise wasted. */
    EXPIRED("Expired");

    private final String label;

    HistoryKind(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /** True for any kind that removes stock, as opposed to a purchase. */
    public boolean isDepletion() {
        return this == USED || this == EXPIRED;
    }

    /**
     * Parses the stored text, defaulting to PURCHASE for anything unexpected.
     * The legacy value "USAGE", written before used and expired were split,
     * is read as USED so old records still count as consumption rather than
     * being dropped.
     */
    public static HistoryKind fromStorage(String value) {
        if (value == null) {
            return PURCHASE;
        }
        String normalised = value.trim().toUpperCase(java.util.Locale.ROOT);
        if (normalised.equals("USAGE")) {
            return USED;
        }
        try {
            return valueOf(normalised);
        } catch (IllegalArgumentException e) {
            return PURCHASE;
        }
    }
}
