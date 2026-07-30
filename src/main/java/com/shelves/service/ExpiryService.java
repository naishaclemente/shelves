package com.shelves.service;

import com.shelves.model.ExpiryStatus;
import com.shelves.model.Item;
import com.shelves.util.Dates;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Works out when an item actually goes bad, and how urgent that is.
 * <p>
 * Every method takes "today" as an argument instead of calling
 * {@link LocalDate#now()} internally. That keeps the rules pure functions,
 * which means they can be tested against fixed dates rather than only ever
 * being correct on the day the test happens to run.
 */
public final class ExpiryService {

    private ExpiryService() {
    }

    /**
     * The date an item should be treated as expiring, taking into account
     * whether it has been opened.
     * <p>
     * The rule that matters: opening something can only ever bring the date
     * forward, never push it back. A jar with a printed date of December that
     * is opened in November with a seven day opened life expires in November.
     * A jar opened the day before its printed date still expires on the printed
     * date, not a week later. So the answer is the earlier of the two.
     *
     * @return the effective expiry date, or {@code null} if nothing is known
     */
    public static LocalDate effectiveExpiry(Item item) {
        if (item == null) {
            return null;
        }

        LocalDate printed = item.getExpirationDate();

        // With no printed date, fall back to purchase date plus unopened life.
        if (printed == null
                && item.getPurchaseDate() != null
                && item.getShelfLifeUnopenedDays() != null) {
            printed = item.getPurchaseDate().plusDays(item.getShelfLifeUnopenedDays());
        }

        if (item.isOpened() && item.getShelfLifeOpenedDays() != null) {
            LocalDate onceOpened = item.getOpenedDate().plusDays(item.getShelfLifeOpenedDays());
            return Dates.earliest(printed, onceOpened);
        }

        return printed;
    }

    /** How many days remain, negative once past. Null when no date is known. */
    public static Long daysRemaining(Item item, LocalDate today) {
        LocalDate expiry = effectiveExpiry(item);
        if (expiry == null) {
            return null;
        }
        return ChronoUnit.DAYS.between(today, expiry);
    }

    /** How urgent this item is right now. */
    public static ExpiryStatus status(Item item, LocalDate today) {
        Long remaining = daysRemaining(item, today);
        if (remaining == null) {
            return ExpiryStatus.NO_DATE;
        }
        if (remaining < 0) {
            return ExpiryStatus.EXPIRED;
        }
        if (remaining <= item.getAlertWindowDays()) {
            return ExpiryStatus.EXPIRING_SOON;
        }
        return ExpiryStatus.FRESH;
    }

    /** Convenience overload using the real current date. */
    public static ExpiryStatus status(Item item) {
        return status(item, LocalDate.now());
    }

    /** A short phrase for the table, such as "3 days left" or "2 days ago". */
    public static String describe(Item item, LocalDate today) {
        Long remaining = daysRemaining(item, today);
        if (remaining == null) {
            return "\u2014";
        }
        if (remaining == 0) {
            return "Today";
        }
        if (remaining == 1) {
            return "Tomorrow";
        }
        if (remaining == -1) {
            return "Yesterday";
        }
        if (remaining < 0) {
            return Math.abs(remaining) + " days ago";
        }
        return remaining + " days left";
    }

    /**
     * The items that should be shown in the alerts panel: anything already
     * expired or inside its own alert window, most urgent first.
     */
    public static List<Item> findNeedingAttention(List<Item> items, LocalDate today) {
        return items.stream()
                .filter(item -> status(item, today).needsAttention())
                .sorted(Comparator.comparing(
                        item -> daysRemaining(item, today),
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .collect(Collectors.toList());
    }

    /** Counts how many items are in each state, for the summary badges. */
    public static long countWithStatus(List<Item> items, ExpiryStatus status, LocalDate today) {
        return items.stream().filter(item -> status(item, today) == status).count();
    }
}
