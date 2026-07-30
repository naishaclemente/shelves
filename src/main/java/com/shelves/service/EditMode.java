package com.shelves.service;

/**
 * How a save to an existing item should affect its history log.
 * <p>
 * The distinction exists to keep the log honest. Editing an item to fix a typo
 * should not look like buying it again; restocking it should not overwrite the
 * record of what it cost last time; and stock leaving needs to say whether it
 * was used or wasted, since those mean opposite things about the item.
 */
public enum EditMode {

    /**
     * The edit is a restock: the user bought more of this item. A new purchase
     * entry is added to the log with the current quantity, price and date.
     */
    NEW_PURCHASE,

    /**
     * The edit is a correction to existing data, such as fixing a mistyped
     * price. The most recent purchase for this item is amended in place rather
     * than a new one being added, so a typo fix cannot masquerade as a purchase.
     */
    CORRECTION,

    /**
     * Stock was used in the normal way. A usage entry is added for the quantity
     * that went, recorded as consumption.
     */
    USED,

    /**
     * Stock was thrown out — expired, spoiled, or otherwise wasted. A usage
     * entry is added for the quantity that went, recorded as waste, so it can be
     * told apart from stock that was actually used.
     */
    EXPIRED;

    /** True for the two modes that record stock leaving. */
    public boolean isDepletion() {
        return this == USED || this == EXPIRED;
    }
}
