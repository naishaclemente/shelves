package com.shelves;

import com.shelves.db.Database;
import com.shelves.model.Item;
import com.shelves.model.Shelf;
import com.shelves.service.InventoryService;
import com.shelves.service.ShelfLifeService;
import com.shelves.util.Money;

import java.time.LocalDate;

/**
 * Fills a database with believable inventory, for demonstrating the app.
 * <p>
 * Useful when showing the project to someone: an empty window makes it hard to
 * see what expiry colouring, tag grouping or price history actually do. Dates
 * are set relative to today, so the mix of expired, expiring and fresh items
 * looks right whenever this is run.
 * <p>
 * Safe to run against a throwaway database. Point it somewhere else with
 * {@code -Dshelves.home=/some/path} if you do not want it touching your own
 * data, which is what the run configuration in the README does.
 */
public class DemoData {

    public static void main(String[] args) {
        Database database = new Database();
        database.initialise();

        InventoryService inventory = new InventoryService(database);
        ShelfLifeService shelfLife = new ShelfLifeService(database);
        shelfLife.seedIfEmpty();

        if (!inventory.listAllItems().isEmpty()) {
            System.out.println("Database already has items; nothing added.");
            return;
        }

        Shelf kitchen = inventory.createShelf("Kitchen");
        Shelf concession = inventory.createShelf("Stadium Concession");
        Shelf medicine = inventory.createShelf("Medicine Cabinet");

        LocalDate today = LocalDate.now();

        // --- Kitchen: a spread of expiry states ---
        add(inventory, "Whole Milk", 1, "gallon", kitchen, "4.29",
                today.minusDays(5), today.minusDays(1), "Dairy, Fridge");

        add(inventory, "Greek Yogurt", 4, "cup", kitchen, "1.19",
                today.minusDays(3), today.plusDays(2), "Dairy, Fridge");

        add(inventory, "Cheddar Cheese", 1, "block", kitchen, "6.49",
                today.minusDays(10), today.plusDays(40), "Dairy, Fridge");

        Item beef = add(inventory, "Ground Beef", 2, "lb", kitchen, "7.99",
                today.minusDays(1), today.plusDays(1), "Meat, Fridge");
        beef.setNotes("For Friday's chili. Freeze half if not used by Thursday.");
        inventory.updateItem(beef);

        add(inventory, "Chicken Breast", 3, "lb", kitchen, "11.50",
                today.minusDays(2), today.plusDays(3), "Meat, Fridge");

        Item rice = add(inventory, "White Rice", 1, "bag", kitchen, "8.99",
                today.minusDays(30), today.plusDays(600), "Pantry, Dry Goods");
        rice.setNotes("Bulk bag from the warehouse store. Keep sealed against pantry moths.");
        inventory.updateItem(rice);

        add(inventory, "Olive Oil", 1, "bottle", kitchen, "12.99",
                today.minusDays(60), today.plusDays(400), "Pantry");

        Item bread = add(inventory, "Sourdough Bread", 1, "loaf", kitchen, "5.50",
                today.minusDays(4), today.plusDays(1), "Bakery");
        bread.setNotes("From the Saturday market stall. Best toasted once a day old.");
        inventory.updateItem(bread);

        // An opened jar, to show that opening brings the expiry forward. The
        // printed date is months away, but it was opened 55 days ago and keeps
        // 60 days once open, so it is actually due in five days.
        Item mayo = add(inventory, "Mayonnaise", 1, "jar", kitchen, "4.79",
                today.minusDays(70), today.plusDays(200), "Condiment, Fridge");
        mayo.setShelfLifeOpenedDays(60);
        mayo.setOpenedDate(today.minusDays(55));
        inventory.updateItem(mayo);

        // --- Concession: bulk stock ---
        add(inventory, "Hot Dog Buns", 24, "pack", concession, "3.25",
                today.minusDays(2), today.plusDays(4), "Bakery, Event Stock");

        add(inventory, "Beef Hot Dogs", 10, "pack", concession, "6.75",
                today.minusDays(2), today.plusDays(10), "Meat, Event Stock");

        add(inventory, "Nacho Cheese", 6, "can", concession, "9.20",
                today.minusDays(45), today.plusDays(300), "Event Stock");

        add(inventory, "Soda Syrup", 4, "box", concession, "42.00",
                today.minusDays(20), today.plusDays(180), "Beverage, Event Stock");

        add(inventory, "Popcorn Kernels", 2, "case", concession, "28.50",
                today.minusDays(90), today.plusDays(500), "Snack, Event Stock");

        add(inventory, "Paper Napkins", 12, "pack", concession, "2.10",
                today.minusDays(120), null, "Supplies, Event Stock");

        // --- Medicine cabinet: shows the app is not only for food ---
        add(inventory, "Ibuprofen 200mg", 1, "bottle", medicine, "7.49",
                today.minusDays(400), today.plusDays(300), "Medicine");

        add(inventory, "Cough Syrup", 1, "bottle", medicine, "9.99",
                today.minusDays(500), today.minusDays(20), "Medicine");

        add(inventory, "Adhesive Bandages", 2, "box", medicine, "3.99",
                today.minusDays(200), today.plusDays(900), "Medicine, Supplies");

        // --- Unfiled, to prove these still show on the Master Shelf ---
        add(inventory, "AA Batteries", 8, "count", null, "9.99",
                today.minusDays(15), null, "Supplies");

        // --- Price history: the same product bought three times ---
        recordPriceHistory(inventory, today);

        System.out.println("Added " + inventory.listAllItems().size()
                + " items across " + (inventory.listShelves().size() - 1) + " shelves.");
        System.out.println("Data folder: " + database.getDataDirectory());
    }

    /**
     * Buys and uses up the same product several times, so the price history
     * has something in it to compare. Each purchase is deleted before the next,
     * which is exactly the case that would lose the history if it were keyed to
     * the item rather than the product.
     */
    private static void recordPriceHistory(InventoryService inventory, LocalDate today) {
        String[][] purchases = {
                {"2.99", "120"},
                {"3.49", "80"},
                {"3.89", "40"}
        };

        for (String[] purchase : purchases) {
            Item eggs = new Item("Large Eggs", 1, "dozen");
            eggs.setPriceCents(Money.parse(purchase[0]));
            eggs.setTrackPriceHistory(true);
            eggs.setPurchaseDate(today.minusDays(Long.parseLong(purchase[1])));
            eggs.addTag("Dairy");
            inventory.addItem(eggs);
            inventory.deleteItem(eggs);
        }

        // The one currently in stock.
        Item current = new Item("Large Eggs", 1, "dozen");
        current.setPriceCents(Money.parse("4.29"));
        current.setTrackPriceHistory(true);
        current.setPurchaseDate(today.minusDays(3));
        current.setExpirationDate(today.plusDays(25));
        current.addTag("Dairy");
        current.addTag("Fridge");
        inventory.addItem(current);
    }

    private static Item add(InventoryService inventory, String name, double quantity,
                            String unit, Shelf shelf, String price,
                            LocalDate purchased, LocalDate expires, String tags) {

        Item item = new Item(name, quantity, unit);
        item.setShelfId(shelf == null ? null : shelf.getId());
        item.setPriceCents(Money.parse(price));
        item.setTrackPriceHistory(true);
        item.setPurchaseDate(purchased);
        item.setExpirationDate(expires);
        for (String tag : tags.split(",")) {
            item.addTag(tag.trim());
        }
        return inventory.addItem(item);
    }
}
