package com.shelves;

import com.shelves.db.Database;
import com.shelves.exception.ValidationException;
import com.shelves.model.ExpiryStatus;
import com.shelves.model.Item;
import com.shelves.model.Shelf;
import com.shelves.service.ExpiryService;
import com.shelves.service.InventoryService;
import com.shelves.service.ShelfLifeService;
import com.shelves.util.Money;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * A self-checking harness for the parts of Shelves that have no user interface.
 * <p>
 * This replaces the old print-and-eyeball Main class. Every check either passes
 * silently or fails loudly with a message, so a regression is impossible to
 * miss by skimming console output. Run it with no arguments; it builds a
 * throwaway database in a temporary folder and deletes it afterwards.
 */
public class SmokeTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        Path tempDir = Files.createTempDirectory("shelves-test");
        System.setProperty("shelves.home", tempDir.toString());

        Database database = new Database(tempDir);
        database.initialise();

        InventoryService inventory = new InventoryService(database);
        ShelfLifeService shelfLife = new ShelfLifeService(database);
        shelfLife.seedIfEmpty();

        System.out.println("Running Shelves checks\n");

        checkMasterShelfExists(inventory);
        checkShelfCrud(inventory);
        checkItemCrud(inventory);
        checkMasterShelfShowsEverything(inventory);
        checkDeletingTaggedItemWorks(inventory);
        checkDuplicateTagIsNoOp(inventory);
        checkDeletingShelfKeepsItems(inventory);
        checkValidationRejectsBadInput(inventory);
        checkExpiryRules();
        checkOpeningOnlyShortens();
        checkShelfLifeLookup(shelfLife);
        checkPriceHistorySurvivesDeletion(inventory);
        checkPurchaseVsCorrection(inventory);
        checkPurchaseDeltaAndReconciliation(inventory);
        checkNoOpResaveDoesNotInflateHistory(inventory);
        checkCorrectionAmendsQuantityInPlace(inventory);
        checkUsageTracking(inventory);
        checkLegacyUsageMapping();
        checkItemValue(inventory);
        checkSearchMatchesNamesOnly(inventory);
        checkDeleteRestore(inventory);
        checkPermanentDeletePurgesHistory(inventory);
        checkChangeKindOptionsByDirection();
        checkMoneyIsExact();
        checkGroupByTag(inventory);
        checkExport(inventory);
        checkExportHandlesEdgeItems(inventory);
        checkPdfWriter();
        checkLocalBarcode(inventory);

        System.out.println("\n" + passed + " passed, " + failed + " failed");
        deleteRecursively(tempDir);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ==================== CHECKS ====================

    private static void checkMasterShelfExists(InventoryService inventory) {
        Shelf master = inventory.getMasterShelf();
        check("Master Shelf is created at startup",
                master != null && master.isDefaultShelf());
        check("Master Shelf is named correctly", "Master Shelf".equals(master.getName()));
    }

    private static void checkShelfCrud(InventoryService inventory) {
        Shelf kitchen = inventory.createShelf("Kitchen");
        check("Shelf can be created", kitchen.getId() > 0);

        inventory.renameShelf(kitchen, "Pantry");
        check("Shelf can be renamed",
                inventory.listShelves().stream().anyMatch(s -> s.getName().equals("Pantry")));

        boolean rejectedDuplicate = false;
        try {
            inventory.createShelf("pantry");
        } catch (ValidationException e) {
            rejectedDuplicate = true;
        }
        check("Duplicate shelf name is rejected, ignoring case", rejectedDuplicate);

        boolean rejectedMasterRename = false;
        try {
            inventory.renameShelf(inventory.getMasterShelf(), "Something Else");
        } catch (ValidationException e) {
            rejectedMasterRename = true;
        }
        check("Master Shelf cannot be renamed", rejectedMasterRename);

        inventory.renameShelf(kitchen, "Kitchen");
    }

    private static void checkItemCrud(InventoryService inventory) {
        Shelf kitchen = shelfNamed(inventory, "Kitchen");

        Item apple = new Item("Apples", 6, "count");
        apple.setShelfId(kitchen.getId());
        apple.setPriceCents(Money.parse("3.50"));
        apple.setPurchaseDate(LocalDate.now().minusDays(1));
        apple.setExpirationDate(LocalDate.now().plusDays(30));
        inventory.addItem(apple);
        check("Item can be created", apple.getId() > 0);

        apple.setQuantity(4);
        apple.setNotes("Two went into a pie.");
        inventory.updateItem(apple);

        Item reloaded = inventory.findItem(apple.getId()).orElseThrow();
        check("Item update persists quantity", reloaded.getQuantity() == 4);
        check("Item update persists notes", "Two went into a pie.".equals(reloaded.getNotes()));
        check("Price round-trips exactly", Long.valueOf(350L).equals(reloaded.getPriceCents()));
        check("Dates round-trip as dates",
                LocalDate.now().plusDays(30).equals(reloaded.getExpirationDate()));
    }

    private static void checkMasterShelfShowsEverything(InventoryService inventory) {
        Shelf kitchen = shelfNamed(inventory, "Kitchen");

        Item unfiled = new Item("Batteries", 8, "count");
        inventory.addItem(unfiled);

        List<Item> onMaster = inventory.listItems(inventory.getMasterShelf());
        List<Item> onKitchen = inventory.listItems(kitchen);

        check("Master Shelf shows filed items",
                onMaster.stream().anyMatch(i -> i.getName().equals("Apples")));
        check("Master Shelf shows unfiled items too",
                onMaster.stream().anyMatch(i -> i.getName().equals("Batteries")));
        check("A named shelf shows only its own items",
                onKitchen.stream().noneMatch(i -> i.getName().equals("Batteries")));
    }

    private static void checkDeletingTaggedItemWorks(InventoryService inventory) {
        Item cheese = new Item("Cheddar Cheese", 1, "block");
        cheese.addTag("Dairy");
        cheese.addTag("Fridge");
        inventory.addItem(cheese);

        Item saved = inventory.findItem(cheese.getId()).orElseThrow();
        check("Tags are saved with the item", saved.getTags().size() == 2);

        // The old code failed here: item_tags had no cascade, so the foreign key
        // blocked the delete after the archive row had already been committed.
        inventory.deleteItem(cheese);
        check("A tagged item can be deleted", inventory.findItem(cheese.getId()).isEmpty());
    }

    private static void checkDuplicateTagIsNoOp(InventoryService inventory) {
        Item milk = new Item("Milk", 1, "gallon");
        milk.addTag("Dairy");
        milk.addTag("Dairy");
        milk.addTag("dairy ");
        inventory.addItem(milk);

        Item saved = inventory.findItem(milk.getId()).orElseThrow();
        check("Tagging the same item twice does not duplicate", saved.getTags().size() <= 2);

        List<Item> dairy = inventory.listItemsByTag("Dairy");
        long milkCount = dairy.stream().filter(i -> i.getId() == milk.getId()).count();
        check("An item appears once under a tag, not twice", milkCount == 1);
    }

    private static void checkDeletingShelfKeepsItems(InventoryService inventory) {
        Shelf garage = inventory.createShelf("Garage");
        Item oil = new Item("Motor Oil", 2, "quart");
        oil.setShelfId(garage.getId());
        inventory.addItem(oil);

        inventory.deleteShelf(garage);

        Item survivor = inventory.findItem(oil.getId()).orElse(null);
        check("Deleting a shelf does not delete its items", survivor != null);
        check("Items from a deleted shelf become unfiled",
                survivor != null && survivor.getShelfId() == null);
        check("Unfiled items still appear on the Master Shelf",
                inventory.listItems(inventory.getMasterShelf()).stream()
                        .anyMatch(i -> i.getId() == oil.getId()));
    }

    private static void checkValidationRejectsBadInput(InventoryService inventory) {
        check("Blank name is rejected", rejects(inventory, item -> item.setName("   ")));
        check("Zero quantity is allowed (none left)",
                !rejects(inventory, item -> item.setQuantity(0)));
        check("Negative quantity is rejected", rejects(inventory, item -> item.setQuantity(-5)));
        check("Negative price is rejected", rejects(inventory, item -> item.setPriceCents(-1L)));
        check("Future purchase date is rejected",
                rejects(inventory, item -> item.setPurchaseDate(LocalDate.now().plusDays(1))));
        check("Non-numeric barcode is rejected",
                rejects(inventory, item -> item.setBarcode("not-a-barcode")));

        check("Expiry before purchase is rejected", rejects(inventory, item -> {
            item.setPurchaseDate(LocalDate.now());
            item.setExpirationDate(LocalDate.now().minusDays(5));
        }));

        // All problems should be reported at once, not one at a time.
        Item bad = new Item("", -1, "x");
        bad.setPriceCents(-100L);
        try {
            inventory.addItem(bad);
            check("Validation reports every problem at once", false);
        } catch (ValidationException e) {
            check("Validation reports every problem at once", e.getErrors().size() >= 3);
        }
    }

    private static void checkExpiryRules() {
        LocalDate today = LocalDate.of(2026, 3, 15);

        Item noDate = new Item("Salt", 1, "box");
        check("An item with no dates has no status",
                ExpiryService.status(noDate, today) == ExpiryStatus.NO_DATE);

        Item expired = new Item("Old Yogurt", 1, "cup");
        expired.setExpirationDate(today.minusDays(2));
        check("A past date reads as expired",
                ExpiryService.status(expired, today) == ExpiryStatus.EXPIRED);

        Item soon = new Item("Bread", 1, "loaf");
        soon.setExpirationDate(today.plusDays(2));
        soon.setAlertWindowDays(3);
        check("Inside the alert window reads as expiring soon",
                ExpiryService.status(soon, today) == ExpiryStatus.EXPIRING_SOON);

        Item fresh = new Item("Rice", 1, "bag");
        fresh.setExpirationDate(today.plusDays(200));
        check("A distant date reads as fresh",
                ExpiryService.status(fresh, today) == ExpiryStatus.FRESH);

        Item boundary = new Item("Milk", 1, "gallon");
        boundary.setExpirationDate(today.plusDays(3));
        boundary.setAlertWindowDays(3);
        check("The edge of the alert window counts as expiring soon",
                ExpiryService.status(boundary, today) == ExpiryStatus.EXPIRING_SOON);

        Item derived = new Item("Chips", 1, "bag");
        derived.setPurchaseDate(today.minusDays(10));
        derived.setShelfLifeUnopenedDays(60);
        check("Expiry is derived from purchase date plus shelf life",
                today.plusDays(50).equals(ExpiryService.effectiveExpiry(derived)));
    }

    private static void checkOpeningOnlyShortens() {
        LocalDate today = LocalDate.of(2026, 3, 15);

        // Printed date is far away, so opening should bring expiry forward.
        Item mayo = new Item("Mayonnaise", 1, "jar");
        mayo.setExpirationDate(today.plusDays(120));
        mayo.setOpenedDate(today.minusDays(2));
        mayo.setShelfLifeOpenedDays(60);
        check("Opening brings a distant expiry forward",
                today.plusDays(58).equals(ExpiryService.effectiveExpiry(mayo)));

        // Printed date is close, so opening must not push it back.
        Item nearlyOld = new Item("Sour Cream", 1, "tub");
        nearlyOld.setExpirationDate(today.plusDays(3));
        nearlyOld.setOpenedDate(today);
        nearlyOld.setShelfLifeOpenedDays(14);
        check("Opening never pushes an expiry date back",
                today.plusDays(3).equals(ExpiryService.effectiveExpiry(nearlyOld)));
    }

    private static void checkShelfLifeLookup(ShelfLifeService shelfLife) {
        check("Reference data loaded", shelfLife.findAll().size() > 50);
        check("Exact lookup works", shelfLife.lookup("milk").isPresent());
        check("Lookup ignores case", shelfLife.lookup("MILK").isPresent());
        check("Partial lookup finds the product inside a longer name",
                shelfLife.lookup("Kraft Shredded Cheddar Cheese")
                        .map(e -> e.getProductName().equals("cheddar cheese"))
                        .orElse(false));
        check("Unknown products return nothing",
                shelfLife.lookup("zzzz nonexistent product").isEmpty());

        Item milk = new Item("Whole Milk", 1, "gallon");
        milk.setPurchaseDate(LocalDate.now());
        boolean applied = shelfLife.applySuggestion(milk);
        check("Suggestions fill in shelf life",
                applied && milk.getShelfLifeUnopenedDays() != null);
        check("Suggestions estimate an expiry date", milk.getExpirationDate() != null);
    }

    private static void checkPriceHistorySurvivesDeletion(InventoryService inventory) {
        Item januaryEggs = new Item("Eggs", 1, "dozen");
        januaryEggs.setPriceCents(Money.parse("2.99"));
        januaryEggs.setTrackPriceHistory(true);
        januaryEggs.setPurchaseDate(LocalDate.now().minusDays(60));
        inventory.addItem(januaryEggs);

        inventory.deleteItem(januaryEggs);

        Item marchEggs = new Item("Eggs", 1, "dozen");
        marchEggs.setPriceCents(Money.parse("4.29"));
        marchEggs.setTrackPriceHistory(true);
        inventory.addItem(marchEggs);

        // The old design keyed history to item id, so the January price would
        // have been orphaned when that item was used up and deleted.
        check("Price history outlives the item it came from",
                inventory.priceHistoryFor(marchEggs).size() == 2);
        check("Average price spans separate purchases",
                Math.round(inventory.averagePriceFor(marchEggs).orElse(0)) == 364);
    }

    private static void checkPurchaseVsCorrection(InventoryService inventory) {
        // A fresh product, so its log starts clean.
        Item flour = new Item("Bread Flour", 1, "bag");
        flour.setPriceCents(Money.parse("3.00"));
        flour.setTrackPriceHistory(true);
        inventory.addItem(flour);
        check("Adding a tracked item records one purchase",
                inventory.priceHistoryFor(flour).size() == 1);

        // A correction fixes the price in place, no new row.
        flour.setPriceCents(Money.parse("3.20"));
        inventory.updateItem(flour, com.shelves.service.EditMode.CORRECTION);
        check("A correction does not add a log entry",
                inventory.priceHistoryFor(flour).size() == 1);
        check("A correction amends the existing entry",
                inventory.priceHistoryFor(flour).get(0).getPriceCents() == 320);

        // A restock adds a new row for the amount bought, which is the increase
        // over what was on hand — not the new total. Flour was at 1; raising it
        // to 8 is a purchase of 7.
        flour.setPriceCents(Money.parse("3.50"));
        flour.setQuantity(8);
        inventory.updateItem(flour, com.shelves.service.EditMode.NEW_PURCHASE);
        check("A new purchase adds a log entry",
                inventory.priceHistoryFor(flour).size() == 2);
        check("A new purchase logs the increase, not the new total",
                inventory.priceHistoryFor(flour).get(0).getQuantity() == 7);

        // The earlier corrected entry is still there and untouched.
        check("A new purchase leaves earlier entries alone",
                inventory.priceHistoryFor(flour).stream()
                        .anyMatch(p -> p.getPriceCents() == 320));

        check("Item reports having purchase history", inventory.hasPurchaseHistory(flour));
    }

    private static void checkUsageTracking(InventoryService inventory) {
        Item cans = new Item("Canned Beans", 12, "can");
        cans.setPriceCents(Money.parse("0.89"));
        cans.setTrackPriceHistory(true);
        inventory.addItem(cans);

        int purchasesBefore = (int) inventory.priceHistoryFor(cans).stream()
                .filter(com.shelves.model.PricePoint::isPurchase).count();

        // Use four cans: quantity drops from 12 to 8.
        cans.setQuantity(8);
        inventory.updateItem(cans, com.shelves.service.EditMode.USED);

        var history = inventory.priceHistoryFor(cans);
        long depletions = history.stream().filter(p -> !p.isPurchase()).count();
        check("Used adds a depletion entry", depletions == 1);

        var used = history.stream()
                .filter(p -> p.getKind() == com.shelves.model.HistoryKind.USED)
                .findFirst().orElseThrow();
        check("Used records the amount consumed, not the new total",
                used.getQuantity() == 4);
        check("A used entry is marked USED, not EXPIRED",
                used.getKind() == com.shelves.model.HistoryKind.USED);
        check("Used does not add a purchase entry",
                inventory.priceHistoryFor(cans).stream()
                        .filter(com.shelves.model.PricePoint::isPurchase).count()
                        == purchasesBefore);

        // Usage must not disturb the average purchase price.
        check("Used leaves the average price untouched",
                Math.round(inventory.averagePriceFor(cans).orElse(0)) == 89);

        // Now throw two out: this must record as EXPIRED, distinct from USED.
        cans.setQuantity(6);
        inventory.updateItem(cans, com.shelves.service.EditMode.EXPIRED);
        var expired = inventory.priceHistoryFor(cans).stream()
                .filter(p -> p.getKind() == com.shelves.model.HistoryKind.EXPIRED)
                .findFirst().orElseThrow();
        check("Expired records its own kind, separate from used",
                expired.getKind() == com.shelves.model.HistoryKind.EXPIRED
                        && expired.getQuantity() == 2);
        check("Used and expired are both on record and distinct",
                inventory.priceHistoryFor(cans).stream()
                        .filter(p -> p.getKind() == com.shelves.model.HistoryKind.USED).count() == 1
                        && inventory.priceHistoryFor(cans).stream()
                        .filter(p -> p.getKind() == com.shelves.model.HistoryKind.EXPIRED)
                        .count() == 1);

        // Set the count to zero: a valid "none left" that records the rest gone.
        cans.setQuantity(0);
        inventory.updateItem(cans, com.shelves.service.EditMode.USED);
        check("Quantity can be set to zero without deleting the item",
                inventory.findItem(cans.getId()).isPresent()
                        && inventory.findItem(cans.getId()).orElseThrow().getQuantity() == 0);
        var lastUsed = inventory.priceHistoryFor(cans).stream()
                .filter(p -> p.getKind() == com.shelves.model.HistoryKind.USED)
                .max(java.util.Comparator.comparingInt(com.shelves.model.PricePoint::getId))
                .orElseThrow();
        check("Emptying to zero logs the remaining quantity as gone",
                lastUsed.getQuantity() == 6);

        // A correction after depletion should amend the purchase, not a depletion.
        cans.setQuantity(6);
        cans.setPriceCents(Money.parse("0.95"));
        inventory.updateItem(cans, com.shelves.service.EditMode.CORRECTION);
        var afterCorrection = inventory.priceHistoryFor(cans);
        check("A correction does not overwrite a used entry",
                afterCorrection.stream()
                        .anyMatch(p -> p.getKind() == com.shelves.model.HistoryKind.USED
                                && p.getQuantity() == 4));
        check("A correction amends the purchase price after depletion",
                afterCorrection.stream().filter(com.shelves.model.PricePoint::isPurchase)
                        .anyMatch(p -> p.getPriceCents() == 95));
    }

    private static void checkPurchaseDeltaAndReconciliation(InventoryService inventory) {
        // Buy 2 at first: the opening purchase logs the whole amount.
        Item oats = new Item("Rolled Oats", 2, "bag");
        oats.setPriceCents(Money.parse("4.00"));
        oats.setTrackPriceHistory(true);
        inventory.addItem(oats);
        check("Opening stock logs the full quantity as bought",
                sumKind(inventory, oats, com.shelves.model.HistoryKind.PURCHASE) == 2);

        // Restock 2 -> 9: a purchase of 7, not 9.
        oats.setQuantity(9);
        inventory.updateItem(oats, com.shelves.service.EditMode.NEW_PURCHASE);
        check("Restock logs only the increase (7), not the total (9)",
                inventory.priceHistoryFor(oats).stream()
                        .filter(com.shelves.model.PricePoint::isPurchase)
                        .max(java.util.Comparator.comparingInt(
                                com.shelves.model.PricePoint::getId))
                        .orElseThrow().getQuantity() == 7);
        check("Total bought is now 9 across two purchases",
                sumKind(inventory, oats, com.shelves.model.HistoryKind.PURCHASE) == 9);

        // Use 3 (9 -> 6) and expire 1 (6 -> 5).
        oats.setQuantity(6);
        inventory.updateItem(oats, com.shelves.service.EditMode.USED);
        oats.setQuantity(5);
        inventory.updateItem(oats, com.shelves.service.EditMode.EXPIRED);

        double bought = sumKind(inventory, oats, com.shelves.model.HistoryKind.PURCHASE);
        double used = sumKind(inventory, oats, com.shelves.model.HistoryKind.USED);
        double expired = sumKind(inventory, oats, com.shelves.model.HistoryKind.EXPIRED);
        double unused = bought - used - expired;

        check("Reconciliation: bought 9 = used 3 + expired 1 + unused 5",
                bought == 9 && used == 3 && expired == 1 && unused == 5);
        check("Derived unused matches the quantity on hand",
                unused == inventory.findItem(oats.getId()).orElseThrow().getQuantity());
    }

    /**
     * Regression: saving with no real change must not touch the history log.
     * The old code re-logged the full on-hand quantity as a phantom purchase (or
     * rewrote the last entry's quantity to the on-hand total), inflating the
     * "bought" figure on every repeated save.
     */
    private static void checkNoOpResaveDoesNotInflateHistory(InventoryService inventory) {
        Item juice = new Item("Phantom Juice", 5, "bottle");
        juice.setPriceCents(Money.parse("5.99"));
        juice.setTrackPriceHistory(true);
        inventory.addItem(juice);

        // Restock 5 -> 9: bought becomes 9.
        juice.setQuantity(9);
        inventory.updateItem(juice, com.shelves.service.EditMode.NEW_PURCHASE);
        check("Bought is 9 after the real restock",
                sumKind(inventory, juice, com.shelves.model.HistoryKind.PURCHASE) == 9);

        // Save again with nothing changed, classified as a new purchase: no-op.
        inventory.updateItem(juice, com.shelves.service.EditMode.NEW_PURCHASE);
        check("A no-change new-purchase re-save leaves bought at 9",
                sumKind(inventory, juice, com.shelves.model.HistoryKind.PURCHASE) == 9);

        // Save again with nothing changed, classified as a correction: no-op.
        inventory.updateItem(juice, com.shelves.service.EditMode.CORRECTION);
        check("A no-change correction re-save leaves bought at 9",
                sumKind(inventory, juice, com.shelves.model.HistoryKind.PURCHASE) == 9);

        // A genuine price correction fixes the price without inflating quantity.
        juice.setPriceCents(Money.parse("6.50"));
        inventory.updateItem(juice, com.shelves.service.EditMode.CORRECTION);
        check("A real price correction leaves bought at 9",
                sumKind(inventory, juice, com.shelves.model.HistoryKind.PURCHASE) == 9);
        check("A real price correction updates the latest purchase price",
                inventory.priceHistoryFor(juice).stream()
                        .filter(com.shelves.model.PricePoint::isPurchase)
                        .max(java.util.Comparator.comparingInt(
                                com.shelves.model.PricePoint::getId))
                        .orElseThrow().getPriceCents() == 650);

        // A genuine further restock still logs its delta.
        juice.setQuantity(12);
        inventory.updateItem(juice, com.shelves.service.EditMode.NEW_PURCHASE);
        check("A later real restock still logs its delta (bought 12)",
                sumKind(inventory, juice, com.shelves.model.HistoryKind.PURCHASE) == 12);
    }

    /**
     * Regression: a correction amends the latest purchase's quantity in place
     * (fixing a mistyped count) rather than leaving history untouched, while a
     * price-only correction on a multi-purchase item still never inflates.
     */
    private static void checkCorrectionAmendsQuantityInPlace(InventoryService inventory) {
        // The reported case: a lone opening purchase, quantity mistyped.
        Item widget = new Item("Widget", 10, "count");
        widget.setPriceCents(Money.parse("5.00"));
        widget.setTrackPriceHistory(true);
        inventory.addItem(widget);
        check("Opening purchase records the typed quantity (10)",
                sumKind(inventory, widget, com.shelves.model.HistoryKind.PURCHASE) == 10);

        // Correct 10 -> 5: the purchase entry itself should become 5, and there
        // should still be exactly one entry (amended in place, not a new row).
        widget.setQuantity(5);
        inventory.updateItem(widget, com.shelves.service.EditMode.CORRECTION);
        check("A quantity correction amends the purchase in place (bought 5)",
                sumKind(inventory, widget, com.shelves.model.HistoryKind.PURCHASE) == 5);
        check("A quantity correction adds no new entry",
                inventory.priceHistoryFor(widget).size() == 1);

        // Multi-purchase item: a price-only correction must not inflate quantity.
        Item gadget = new Item("Gadget", 9, "count");
        gadget.setPriceCents(Money.parse("3.00"));
        gadget.setTrackPriceHistory(true);
        inventory.addItem(gadget);
        gadget.setQuantity(12);
        inventory.updateItem(gadget, com.shelves.service.EditMode.NEW_PURCHASE);
        gadget.setPriceCents(Money.parse("3.50"));
        inventory.updateItem(gadget, com.shelves.service.EditMode.CORRECTION);
        check("A price-only correction on a multi-purchase item keeps bought at 12",
                sumKind(inventory, gadget, com.shelves.model.HistoryKind.PURCHASE) == 12);
        check("A price-only correction updates the latest price",
                inventory.priceHistoryFor(gadget).stream()
                        .filter(com.shelves.model.PricePoint::isPurchase)
                        .max(java.util.Comparator.comparingInt(
                                com.shelves.model.PricePoint::getId))
                        .orElseThrow().getPriceCents() == 350);

        // A quantity correction on the multi-purchase item shifts the last entry
        // by the delta (12 -> 10 reduces the last purchase by 2, bought 10).
        gadget.setQuantity(10);
        inventory.updateItem(gadget, com.shelves.service.EditMode.CORRECTION);
        check("A quantity correction shifts the last entry by the delta (bought 10)",
                sumKind(inventory, gadget, com.shelves.model.HistoryKind.PURCHASE) == 10);
    }

    private static double sumKind(InventoryService inventory, Item item,
                                  com.shelves.model.HistoryKind kind) {
        return inventory.priceHistoryFor(item).stream()
                .filter(p -> p.getKind() == kind)
                .mapToDouble(com.shelves.model.PricePoint::getQuantity)
                .sum();
    }

    private static void checkItemValue(InventoryService inventory) {
        Item item = new Item("Value Test Widget", 3, "box");
        item.setPriceCents(Money.parse("2.50"));
        inventory.addItem(item);
        check("Item value is unit price times quantity",
                inventory.itemValue(item) != null && inventory.itemValue(item) == 750);

        Item noPrice = new Item("Priceless Widget", 4, "box");
        inventory.addItem(noPrice);
        check("An unpriced item has no value (null, shown as a dash)",
                inventory.itemValue(noPrice) == null);

        // The per-row values must sum to the aggregate the status bar shows.
        var items = java.util.List.of(item, noPrice);
        long summed = items.stream()
                .map(inventory::itemValue)
                .filter(java.util.Objects::nonNull)
                .mapToLong(Long::longValue).sum();
        check("Per-row values sum to the aggregate total",
                summed == inventory.totalValue(items));
    }

    private static void checkSearchMatchesNamesOnly(InventoryService inventory) {
        Item item = new Item("Zephyr Kombucha", 1, "bottle");
        item.setNotes("keep in the garage fridge");
        item.setBarcode("0090210");
        inventory.addItem(item);

        // The name matches.
        check("Search finds an item by its name",
                inventory.searchItems("Zephyr").stream()
                        .anyMatch(i -> i.getName().equals("Zephyr Kombucha")));

        // Notes and barcode do NOT match: search is name-only, matching the
        // "Search items" label so it does not surface items for hidden reasons.
        check("Search does not match on notes",
                inventory.searchItems("garage fridge").stream()
                        .noneMatch(i -> i.getName().equals("Zephyr Kombucha")));
        check("Search does not match on barcode",
                inventory.searchItems("0090210").stream()
                        .noneMatch(i -> i.getName().equals("Zephyr Kombucha")));
    }

    private static void checkLegacyUsageMapping() {
        // Rows written before the used/expired split stored kind = 'USAGE'.
        // They must read back as USED so old history is not lost or misfiled.
        check("Legacy USAGE maps to USED",
                com.shelves.model.HistoryKind.fromStorage("USAGE")
                        == com.shelves.model.HistoryKind.USED);
        check("Unknown kind falls back to PURCHASE",
                com.shelves.model.HistoryKind.fromStorage("wat")
                        == com.shelves.model.HistoryKind.PURCHASE);
        check("EXPIRED round-trips from storage",
                com.shelves.model.HistoryKind.fromStorage("EXPIRED")
                        == com.shelves.model.HistoryKind.EXPIRED);
    }

    private static void checkDeleteRestore(InventoryService inventory) {
        com.shelves.model.Shelf pantry = inventory.createShelf("Restore Test Shelf");

        Item item = new Item("Restorable Soup", 3, "can");
        item.setShelfId(pantry.getId());
        item.setPriceCents(Money.parse("2.49"));
        item.setNotes("Keep for winter.");
        item.addTag("Pantry");
        item.addTag("Canned");
        inventory.addItem(item);
        int originalId = item.getId();

        int binBefore = inventory.deletedItemCount();
        inventory.deleteItem(item, "Expired / spoiled");
        check("Deleting an item moves it to the bin",
                inventory.deletedItemCount() == binBefore + 1);
        check("A deleted item is gone from the live list",
                inventory.findItem(originalId).isEmpty());

        var deleted = inventory.listDeletedItems().stream()
                .filter(d -> d.getName().equals("Restorable Soup"))
                .findFirst().orElseThrow();
        check("The deletion reason is stored",
                "Expired / spoiled".equals(deleted.getReason()));
        check("The archived item keeps its tags",
                deleted.getTagsDisplay().contains("Pantry")
                        && deleted.getTagsDisplay().contains("Canned"));

        Item restored = inventory.restoreDeletedItem(deleted);
        check("Restore creates a live item again",
                inventory.findItem(restored.getId()).isPresent());
        check("Restore returns the item to its shelf",
                restored.getShelfId() != null && restored.getShelfId() == pantry.getId());
        check("Restore rebuilds the tags",
                restored.getTags().contains("Pantry") && restored.getTags().contains("Canned"));
        check("Restore keeps the notes",
                "Keep for winter.".equals(inventory.findItem(restored.getId())
                        .orElseThrow().getNotes()));
        check("Restore empties that row from the bin",
                inventory.listDeletedItems().stream()
                        .noneMatch(d -> d.getId() == deleted.getId()));

        // Restoring to a since-deleted shelf must fall back to unfiled, not fail.
        Item orphan = new Item("Orphan Item", 1, "box");
        com.shelves.model.Shelf temp = inventory.createShelf("Temp Shelf");
        orphan.setShelfId(temp.getId());
        inventory.addItem(orphan);
        inventory.deleteItem(orphan, "test");
        var orphanArchived = inventory.listDeletedItems().stream()
                .filter(d -> d.getName().equals("Orphan Item")).findFirst().orElseThrow();
        inventory.deleteShelf(temp);
        Item orphanRestored = inventory.restoreDeletedItem(orphanArchived);
        check("Restoring to a deleted shelf falls back to unfiled",
                orphanRestored.getShelfId() == null);

        // Last bought comes from the purchase history and survives deletion.
        Item tracked = new Item("Tracked Juice", 2, "bottle");
        tracked.setPriceCents(Money.parse("3.00"));
        tracked.setTrackPriceHistory(true);
        tracked.setPurchaseDate(LocalDate.now().minusDays(5));
        inventory.addItem(tracked);
        inventory.deleteItem(tracked, "test");
        var trackedArchived = inventory.listDeletedItems().stream()
                .filter(d -> d.getName().equals("Tracked Juice")).findFirst().orElseThrow();
        check("Deleted item shows a last-bought date from history",
                trackedArchived.getLastBoughtDate() != null
                        && trackedArchived.getLastBoughtDate()
                                .equals(LocalDate.now().minusDays(5)));

        // Per-row purge permanently removes just that entry.
        int binBeforePurge = inventory.deletedItemCount();
        inventory.purgeDeletedItem(trackedArchived);
        check("Purging removes exactly one archived entry",
                inventory.deletedItemCount() == binBeforePurge - 1);
        check("A purged item is gone from the bin",
                inventory.listDeletedItems().stream()
                        .noneMatch(d -> d.getId() == trackedArchived.getId()));
        check("Purging does not restore the item to the live list",
                inventory.listAllItems().stream()
                        .noneMatch(i -> i.getName().equals("Tracked Juice")));
    }

    /**
     * Regression: permanently deleting an item from the bin also erases its
     * product history, but normal deletion keeps it, and a product still used by
     * another live item keeps its history even when one copy is purged.
     */
    private static void checkPermanentDeletePurgesHistory(InventoryService inventory) {
        // Normal deletion keeps history: used up then rebought carries the past.
        Item spud = new Item("Potato", 3, "count");
        spud.setPriceCents(Money.parse("1.50"));
        spud.setTrackPriceHistory(true);
        inventory.addItem(spud);
        inventory.deleteItem(spud, "used up");
        Item spud2 = new Item("Potato", 1, "count");
        spud2.setPriceCents(Money.parse("1.50"));
        spud2.setTrackPriceHistory(true);
        inventory.addItem(spud2);
        check("Normal deletion keeps product history (used up, rebought)",
                sumKind(inventory, spud2, com.shelves.model.HistoryKind.PURCHASE) == 4);

        // Clean up that lingering Potato so the next part starts fresh.
        inventory.deleteItem(spud2, "cleanup");
        inventory.listDeletedItems().stream()
                .filter(d -> d.getName().equals("Potato"))
                .forEach(inventory::purgeDeletedItem);

        // Permanent delete erases history: a rebuy afterwards starts clean.
        Item onion = new Item("Onion", 5, "count");
        onion.setPriceCents(Money.parse("2.00"));
        onion.setTrackPriceHistory(true);
        inventory.addItem(onion);
        inventory.deleteItem(onion, "gone");
        var onionArchived = inventory.listDeletedItems().stream()
                .filter(d -> d.getName().equals("Onion")).findFirst().orElseThrow();
        inventory.purgeDeletedItem(onionArchived);
        Item onion2 = new Item("Onion", 1, "count");
        onion2.setPriceCents(Money.parse("2.00"));
        onion2.setTrackPriceHistory(true);
        inventory.addItem(onion2);
        check("Permanent delete purges product history (rebuy starts fresh)",
                sumKind(inventory, onion2, com.shelves.model.HistoryKind.PURCHASE) == 1);

        // Shared product key: purging one copy keeps history the other still uses.
        Item leekA = new Item("Leek", 5, "count");
        leekA.setPriceCents(Money.parse("1.00"));
        leekA.setTrackPriceHistory(true);
        inventory.addItem(leekA);
        Item leekB = new Item("Leek", 2, "count");
        leekB.setPriceCents(Money.parse("1.00"));
        leekB.setTrackPriceHistory(true);
        inventory.addItem(leekB);
        inventory.deleteItem(leekA, "duplicate");
        var leekArchived = inventory.listDeletedItems().stream()
                .filter(d -> d.getName().equals("Leek")).findFirst().orElseThrow();
        inventory.purgeDeletedItem(leekArchived);
        check("Permanent delete keeps history a live item still shares",
                sumKind(inventory, leekB, com.shelves.model.HistoryKind.PURCHASE) == 7);
    }

    /**
     * The change-kind prompt offers only the classifications that fit which way
     * the quantity moved: a rise is a restock or a correction (never used or
     * expired), a drop is used, expired, or a correction (never a purchase), and
     * an unchanged count could be any of them.
     */
    private static void checkChangeKindOptionsByDirection() {
        var increased = com.shelves.service.ChangeKind.optionsFor(
                com.shelves.service.ChangeKind.Direction.INCREASED);
        check("An increase offers new purchase",
                increased.contains(com.shelves.service.ChangeKind.Choice.NEW_PURCHASE));
        check("An increase offers correction",
                increased.contains(com.shelves.service.ChangeKind.Choice.CORRECTION));
        check("An increase hides used",
                !increased.contains(com.shelves.service.ChangeKind.Choice.USED));
        check("An increase hides expired",
                !increased.contains(com.shelves.service.ChangeKind.Choice.EXPIRED));

        var decreased = com.shelves.service.ChangeKind.optionsFor(
                com.shelves.service.ChangeKind.Direction.DECREASED);
        check("A decrease hides new purchase",
                !decreased.contains(com.shelves.service.ChangeKind.Choice.NEW_PURCHASE));
        check("A decrease offers used and expired",
                decreased.contains(com.shelves.service.ChangeKind.Choice.USED)
                        && decreased.contains(com.shelves.service.ChangeKind.Choice.EXPIRED));

        var unchanged = com.shelves.service.ChangeKind.optionsFor(
                com.shelves.service.ChangeKind.Direction.UNCHANGED);
        check("An unchanged count offers all four classifications",
                unchanged.contains(com.shelves.service.ChangeKind.Choice.NEW_PURCHASE)
                        && unchanged.contains(com.shelves.service.ChangeKind.Choice.CORRECTION)
                        && unchanged.contains(com.shelves.service.ChangeKind.Choice.USED)
                        && unchanged.contains(com.shelves.service.ChangeKind.Choice.EXPIRED));
    }

    private static void checkMoneyIsExact() {
        check("Parsing produces exact cents", Long.valueOf(350L).equals(Money.parse("3.50")));
        check("Dollar signs and commas are tolerated",
                Long.valueOf(123456L).equals(Money.parse("$1,234.56")));
        check("Blank price is null", Money.parse("  ") == null);
        check("Formatting round-trips", "$3.50".equals(Money.format(350)));

        // The reason cents are used at all: a double cannot do this.
        long total = 0;
        for (int i = 0; i < 10; i++) {
            total += Money.parse("0.10");
        }
        check("Ten ten-cent items total exactly one dollar", total == 100);
    }

    private static void checkGroupByTag(InventoryService inventory) {
        List<Item> all = inventory.listAllItems();
        Map<String, List<Item>> grouped = inventory.groupByTag(all);

        check("Grouping produces at least one group", !grouped.isEmpty());

        long itemsWithoutTags = all.stream().filter(i -> i.getTags().isEmpty()).count();
        if (itemsWithoutTags > 0) {
            check("Untagged items are not lost when grouping",
                    grouped.containsKey("Untagged")
                            && grouped.get("Untagged").size() == itemsWithoutTags);
        }
    }

    // ==================== HELPERS ====================

    private static void checkExport(InventoryService inventory) {
        com.shelves.service.ExportService export =
                new com.shelves.service.ExportService(inventory);

        com.shelves.model.Shelf master = inventory.getMasterShelf();
        List<String[]> rows = export.buildRows(master);
        check("Export builds a row per item",
                rows.size() == inventory.listAllItems().size());
        check("Export rows have all columns",
                rows.isEmpty() || rows.get(0).length == com.shelves.service.ExportService.COLUMNS.length);

        // A field containing a comma must survive the CSV round trip quoted.
        Item tricky = new Item("Salt, coarse", 1, "box");
        tricky.setNotes("Line one\nLine two");
        inventory.addItem(tricky);

        try {
            java.nio.file.Path csv = java.nio.file.Files.createTempFile("shelves-export", ".csv");
            export.exportCsv(master, csv);
            String content = java.nio.file.Files.readString(csv);
            check("CSV quotes fields containing commas",
                    content.contains("\"Salt, coarse\""));
            check("CSV escapes embedded newlines by quoting",
                    content.contains("\"Line one\nLine two\""));
            check("CSV has a header row",
                    content.startsWith("Name,Quantity"));
            java.nio.file.Files.deleteIfExists(csv);
        } catch (Exception e) {
            check("CSV export writes a file", false);
        }

        inventory.deleteItem(tricky);
    }

    /**
     * The export preview builds a row for every item without throwing, even for
     * the trickier states the history and correction features can produce — a
     * corrected item, a zero-quantity item, and an item with no price. The export
     * dialog builds this same preview during construction, so a throw here is
     * exactly what would make the dialog appear to do nothing.
     */
    private static void checkExportHandlesEdgeItems(InventoryService inventory) {
        var export = new com.shelves.service.ExportService(inventory);

        Item corrected = new Item("Export Corrected", 10, "count");
        corrected.setPriceCents(Money.parse("5.00"));
        corrected.setTrackPriceHistory(true);
        inventory.addItem(corrected);
        corrected.setQuantity(5);
        inventory.updateItem(corrected, com.shelves.service.EditMode.CORRECTION);

        Item zero = new Item("Export Zero", 4, "count");
        zero.setPriceCents(Money.parse("2.00"));
        zero.setTrackPriceHistory(true);
        inventory.addItem(zero);
        zero.setQuantity(0);
        inventory.updateItem(zero, com.shelves.service.EditMode.USED);

        Item noPrice = new Item("Export No Price", 2, "count");
        inventory.addItem(noPrice);

        boolean built;
        try {
            List<String[]> rows = export.buildRows(inventory.getMasterShelf());
            built = rows.size() == inventory.listAllItems().size();
        } catch (RuntimeException e) {
            built = false;
        }
        check("Export preview builds cleanly for corrected, zero and unpriced items",
                built);

        inventory.deleteItem(corrected);
        inventory.deleteItem(zero);
        inventory.deleteItem(noPrice);
    }

    private static void checkPdfWriter() {
        try {
            java.nio.file.Path pdf = java.nio.file.Files.createTempFile("shelves-test", ".pdf");
            com.shelves.util.SimplePdf.write(pdf, "Test Sheet", "A subtitle",
                    new String[]{"Name", "Qty"},
                    new float[]{0, 200},
                    List.of(new String[]{"Apples", "6"}, new String[]{"Milk", "1"}));

            byte[] bytes = java.nio.file.Files.readAllBytes(pdf);
            String head = new String(bytes, 0, Math.min(8, bytes.length),
                    java.nio.charset.StandardCharsets.ISO_8859_1);
            String tail = new String(bytes, Math.max(0, bytes.length - 6), 6,
                    java.nio.charset.StandardCharsets.ISO_8859_1);

            check("PDF starts with the correct signature", head.startsWith("%PDF-"));
            check("PDF ends with the end-of-file marker", tail.contains("%%EOF"));
            check("PDF is not empty", bytes.length > 400);
            java.nio.file.Files.deleteIfExists(pdf);
        } catch (Exception e) {
            check("PDF writer produces a file", false);
        }
    }

    private static void checkLocalBarcode(InventoryService inventory) {
        com.shelves.service.BarcodeService barcodes =
                new com.shelves.service.BarcodeService(inventory.getDatabase());

        check("A random barcode is unknown at first",
                barcodes.lookup("012345678905").isEmpty());

        barcodes.remember("012345678905", "Test Cereal", "TestBrand", "box");
        check("A remembered barcode is then found",
                barcodes.lookup("012345678905").isPresent());
        check("A remembered barcode returns its details",
                barcodes.lookup("012345678905")
                        .map(p -> p.name().equals("Test Cereal"))
                        .orElse(false));
        check("Barcode plausibility rejects non-digits",
                !com.shelves.service.BarcodeService.isPlausibleBarcode("abc123"));
        check("Barcode plausibility accepts a valid code",
                com.shelves.service.BarcodeService.isPlausibleBarcode("012345678905"));
    }

    private static Shelf shelfNamed(InventoryService inventory, String name) {
        return inventory.listShelves().stream()
                .filter(s -> s.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No shelf named " + name));
    }

    /** Builds a valid item, applies a breaking change, and expects a rejection. */
    private static boolean rejects(InventoryService inventory, java.util.function.Consumer<Item> breakIt) {
        Item item = new Item("Valid Name", 1, "count");
        breakIt.accept(item);
        try {
            inventory.addItem(item);
            inventory.deleteItem(item);
            return false;
        } catch (ValidationException e) {
            return true;
        }
    }

    private static void check(String description, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("  PASS  " + description);
        } else {
            failed++;
            System.out.println("  FAIL  " + description);
        }
    }

    private static void deleteRecursively(Path path) throws Exception {
        try (var stream = Files.walk(path)) {
            stream.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                    // Temporary files; nothing depends on cleanup succeeding.
                }
            });
        }
    }
}
