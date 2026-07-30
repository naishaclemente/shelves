package com.shelves.service;

import java.util.EnumSet;
import java.util.Set;

/**
 * The ways a change to an item can be classified, and the rule for which ones
 * make sense given how the quantity moved.
 * <p>
 * This lives in the service layer, free of any UI, so the rule can be reasoned
 * about and tested on its own. The prompt that asks the user is just a view onto
 * it: it shows exactly the options {@link #optionsFor} returns for the direction
 * of the edit, and can never offer one the rule excludes.
 */
public final class ChangeKind {

    private ChangeKind() {
    }

    /** What a change was: a new purchase, a correction, stock used, or expired. */
    public enum Choice { NEW_PURCHASE, CORRECTION, USED, EXPIRED, CANCEL }

    /** Which way the quantity moved on this edit. */
    public enum Direction { INCREASED, DECREASED, UNCHANGED }

    /**
     * The classifications that fit a given quantity change. The two directions
     * are mirror images: a rise can only be a restock or a correction — never
     * stock used or expired — and a drop can only be used, expired, or a
     * correction — never a purchase. An unchanged count could be any of them.
     * Correction is always present, since any edit might just be fixing a
     * mistake. CANCEL is not a classification and is never included.
     */
    public static Set<Choice> optionsFor(Direction direction) {
        return switch (direction) {
            case INCREASED -> EnumSet.of(Choice.NEW_PURCHASE, Choice.CORRECTION);
            case DECREASED -> EnumSet.of(Choice.USED, Choice.EXPIRED, Choice.CORRECTION);
            case UNCHANGED -> EnumSet.of(
                    Choice.NEW_PURCHASE, Choice.CORRECTION, Choice.USED, Choice.EXPIRED);
        };
    }
}
