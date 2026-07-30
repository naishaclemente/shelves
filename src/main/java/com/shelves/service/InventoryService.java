package com.shelves.service;

import com.shelves.db.Database;
import com.shelves.db.dao.ItemDao;
import com.shelves.db.dao.PriceHistoryDao;
import com.shelves.db.dao.ShelfDao;
import com.shelves.db.dao.TagDao;
import com.shelves.exception.DataAccessException;
import com.shelves.exception.ValidationException;
import com.shelves.model.Item;
import com.shelves.model.PricePoint;
import com.shelves.model.Shelf;
import com.shelves.model.Tag;
import com.shelves.util.Validator;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.TreeSet;

/**
 * The single entry point the user interface uses to read and change inventory.
 * <p>
 * Validation happens here, before anything is written, and every operation that
 * touches more than one table runs inside a transaction. Keeping that in one
 * layer means no screen can accidentally skip a rule, and no screen needs to
 * know that {@code java.sql} exists.
 */
public class InventoryService {

    private final Database database;
    private final ShelfDao shelfDao = new ShelfDao();
    private final ItemDao itemDao = new ItemDao();
    private final TagDao tagDao = new TagDao();
    private final PriceHistoryDao priceHistoryDao = new PriceHistoryDao();
    private final com.shelves.db.dao.DeletedItemDao deletedItemDao =
            new com.shelves.db.dao.DeletedItemDao();

    public InventoryService(Database database) {
        this.database = database;
    }

    public Database getDatabase() {
        return database;
    }

    // ==================== SHELVES ====================

    public List<Shelf> listShelves() {
        return database.query("load your shelves", shelfDao::findAll);
    }

    public Shelf getMasterShelf() {
        return database.query("find the Master Shelf", shelfDao::findDefault);
    }

    /**
     * Creates a shelf.
     *
     * @throws ValidationException if the name is empty, too long, or taken
     */
    public Shelf createShelf(String rawName) {
        String name = Validator.validateShelfName(rawName);
        return database.transaction("create the shelf", conn -> {
            if (shelfDao.nameExists(conn, name, 0)) {
                throw new ValidationException("A shelf named \"" + name + "\" already exists.");
            }
            return shelfDao.insert(conn, name);
        });
    }

    /** Renames a shelf. The Master Shelf cannot be renamed. */
    public void renameShelf(Shelf shelf, String rawName) {
        if (shelf.isDefaultShelf()) {
            throw new ValidationException("The Master Shelf cannot be renamed.");
        }
        String name = Validator.validateShelfName(rawName);
        database.transact("rename the shelf", conn -> {
            if (shelfDao.nameExists(conn, name, shelf.getId())) {
                throw new ValidationException("A shelf named \"" + name + "\" already exists.");
            }
            if (!shelfDao.rename(conn, shelf.getId(), name)) {
                throw new DataAccessException("That shelf no longer exists.");
            }
        });
        shelf.setName(name);
    }

    /**
     * Deletes a shelf. Items on it are kept and become unfiled, so they still
     * appear on the Master Shelf. Deleting a container should not destroy its
     * contents.
     */
    public void deleteShelf(Shelf shelf) {
        if (shelf.isDefaultShelf()) {
            throw new ValidationException("The Master Shelf cannot be deleted.");
        }
        database.transact("delete the shelf", conn -> {
            if (!shelfDao.delete(conn, shelf.getId())) {
                throw new DataAccessException("That shelf no longer exists.");
            }
        });
    }

    // ==================== ITEMS ====================

    /**
     * Everything on one shelf.
     * <p>
     * The Master Shelf is a view meaning "everything", not a container, so it
     * routes to a query with no shelf filter. Filtering by its id would return
     * an empty list, since nothing is ever assigned to it.
     */
    public List<Item> listItems(Shelf shelf) {
        if (shelf == null || shelf.isDefaultShelf()) {
            return listAllItems();
        }
        return database.query("load items for " + shelf.getName(),
                conn -> itemDao.findByShelfId(conn, shelf.getId()));
    }

    public List<Item> listAllItems() {
        return database.query("load your items", itemDao::findAll);
    }

    public List<Item> listUnfiledItems() {
        return database.query("load unfiled items", itemDao::findUnfiled);
    }

    public Optional<Item> findItem(int id) {
        return database.query("load that item", conn -> itemDao.findById(conn, id));
    }

    public List<Item> searchItems(String term) {
        if (term == null || term.isBlank()) {
            return listAllItems();
        }
        return database.query("search your items", conn -> itemDao.search(conn, term));
    }

    /**
     * Saves a new item.
     *
     * @throws ValidationException if any field is unusable
     */
    public Item addItem(Item item) {
        Validator.validateItem(item);
        return database.transaction("save the item", conn -> {
            itemDao.insert(conn, item);
            if (item.isTrackPriceHistory() && item.getPriceCents() != null) {
                priceHistoryDao.record(conn, item.productKey(), item.getName(),
                        item.getQuantity(), item.getPriceCents(),
                        item.getPurchaseDate() == null ? LocalDate.now() : item.getPurchaseDate(),
                        item.getId());
            }
            return item;
        });
    }

    /**
     * Saves changes to an existing item without touching the purchase log.
     * Used for edits that are not about a purchase at all, such as moving an
     * item or adding a note.
     */
    public Item updateItem(Item item) {
        return updateItem(item, EditMode.CORRECTION);
    }

    /**
     * Saves changes to an existing item, updating the purchase log according to
     * the edit mode.
     * <p>
     * A {@link EditMode#NEW_PURCHASE} adds an entry: the user restocked. A
     * {@link EditMode#CORRECTION} amends the item's most recent entry in place,
     * so fixing a typo does not create a phantom purchase, and never deletes
     * anything. If a correction finds no existing entry to amend (the item was
     * not previously tracked), it records one so the current state is not lost.
     */
    public Item updateItem(Item item, EditMode mode) {
        Validator.validateItem(item);
        return database.transaction("save your changes", conn -> {
            Optional<Item> existing = itemDao.findById(conn, item.getId());
            if (existing.isEmpty()) {
                throw new DataAccessException(
                        "That item no longer exists. It may have been deleted in another window.");
            }

            double previousQuantity = existing.get().getQuantity();
            Long previousPrice = existing.get().getPriceCents();

            if (!itemDao.update(conn, item)) {
                throw new DataAccessException("That item no longer exists.");
            }

            recordHistoryChange(conn, item, mode, previousQuantity, previousPrice);
            return item;
        });
    }

    private void recordHistoryChange(java.sql.Connection conn, Item item, EditMode mode,
                                     double previousQuantity, Long previousPrice)
            throws java.sql.SQLException {
        boolean tracking = item.isTrackPriceHistory() && item.getPriceCents() != null;
        LocalDate when = item.getPurchaseDate() == null ? LocalDate.now() : item.getPurchaseDate();

        switch (mode) {
            case USED, EXPIRED -> {
                // Record how much left: the drop in quantity. Setting the count
                // to zero is a valid "I have none left" and records the whole
                // previous quantity as gone. If the number did not fall at all
                // (the user marked a depletion without changing it), fall back to
                // the current quantity so something meaningful is logged. Price
                // is carried only for reference; a depletion does not affect
                // price averages.
                double gone = previousQuantity - item.getQuantity();
                if (gone <= 0) {
                    gone = item.getQuantity() > 0 ? item.getQuantity() : previousQuantity;
                }
                long price = item.getPriceCents() == null ? 0 : item.getPriceCents();
                com.shelves.model.HistoryKind kind = mode == EditMode.EXPIRED
                        ? com.shelves.model.HistoryKind.EXPIRED
                        : com.shelves.model.HistoryKind.USED;
                priceHistoryDao.record(conn, item.productKey(), item.getName(),
                        gone, price, LocalDate.now(), item.getId(), kind);
            }
            case NEW_PURCHASE -> {
                if (tracking) {
                    // Log the amount actually bought: the increase over what was
                    // already on hand, not the new total. Editing 1 to 8 is a
                    // purchase of 7, not 8. The previous quantity is read from the
                    // stored item inside this same transaction (see updateItem),
                    // so a repeated save measures against the quantity that save
                    // already persisted, not a stale baseline.
                    //
                    // If the quantity did not actually rise, nothing was bought,
                    // so nothing is logged. This is the difference between a real
                    // restock and a phantom one: saving again without changing the
                    // quantity must be a true no-op, never a fresh full-quantity
                    // purchase that would inflate the history.
                    double bought = item.getQuantity() - previousQuantity;
                    if (bought > 0) {
                        priceHistoryDao.record(conn, item.productKey(), item.getName(),
                                bought, item.getPriceCents(), when, item.getId());
                    }
                }
            }
            case CORRECTION -> {
                // A correction fixes existing data — a mistyped price or
                // quantity. If neither actually changed from what is stored, there
                // is nothing to correct, so the log is left completely untouched;
                // this is what makes saving again with no edits a true no-op.
                boolean priceChanged =
                        !java.util.Objects.equals(previousPrice, item.getPriceCents());
                boolean quantityChanged = previousQuantity != item.getQuantity();
                if (!priceChanged && !quantityChanged) {
                    break;
                }
                if (tracking) {
                    // Amend the latest purchase in place: fix its price, and shift
                    // its recorded quantity by the same amount the item's quantity
                    // moved. Correcting a mistyped opening quantity of 10 down to 5
                    // brings that purchase to 5; the delta keeps this correct even
                    // when several purchases exist, and never inflates history the
                    // way overwriting with the on-hand total would. If there is no
                    // purchase to correct yet, record the current one so the price
                    // is on file.
                    double quantityDelta = item.getQuantity() - previousQuantity;
                    boolean corrected = priceHistoryDao.correctLatest(
                            conn, item.getId(), quantityDelta, item.getPriceCents());
                    if (!corrected) {
                        priceHistoryDao.record(conn, item.productKey(), item.getName(),
                                item.getQuantity(), item.getPriceCents(), when, item.getId());
                    }
                }
            }
        }
    }

    /** True if this item has recorded at least one purchase. */
    public boolean hasPurchaseHistory(Item item) {
        return database.query("check purchase history",
                conn -> priceHistoryDao.hasEntryForItem(conn, item.getId()));
    }

    /**
     * Archives an item and then deletes it, both inside one transaction.
     * <p>
     * These two writes must not be able to come apart. If the delete fails
     * after the archive has been written, the whole thing rolls back rather
     * than leaving a record of a deletion that never happened.
     *
     * @param reason an optional note on why it was deleted; may be null
     */
    public void deleteItem(Item item, String reason) {
        database.transact("delete the item", conn -> {
            itemDao.archive(conn, item.getId(), reason);
            if (!itemDao.delete(conn, item.getId())) {
                throw new DataAccessException("That item no longer exists.");
            }
        });
    }

    /** Deletes an item with no stated reason. */
    public void deleteItem(Item item) {
        deleteItem(item, null);
    }

    /** Deletes several items as one all-or-nothing operation. */
    public int deleteItems(List<Item> items, String reason) {
        if (items.isEmpty()) {
            return 0;
        }
        return database.transaction("delete those items", conn -> {
            int deleted = 0;
            for (Item item : items) {
                itemDao.archive(conn, item.getId(), reason);
                if (itemDao.delete(conn, item.getId())) {
                    deleted++;
                }
            }
            return deleted;
        });
    }

    /** Deletes several items with no stated reason. */
    public int deleteItems(List<Item> items) {
        return deleteItems(items, null);
    }

    public void moveItem(Item item, Shelf target) {
        Integer shelfId = (target == null || target.isDefaultShelf()) ? null : target.getId();
        database.transact("move the item", conn -> {
            itemDao.moveToShelf(conn, item.getId(), shelfId);
        });
        item.setShelfId(shelfId);
    }

    /** Records that an item has been opened, which may shorten its expiry. */
    public void markOpened(Item item, LocalDate openedOn) {
        if (openedOn != null && openedOn.isAfter(LocalDate.now())) {
            throw new ValidationException("Opened date cannot be in the future.");
        }
        if (openedOn != null && item.getPurchaseDate() != null
                && openedOn.isBefore(item.getPurchaseDate())) {
            throw new ValidationException("Opened date cannot be before the purchase date.");
        }
        database.transact("mark the item opened", conn -> {
            itemDao.markOpened(conn, item.getId(), openedOn);
        });
        item.setOpenedDate(openedOn);
    }

    public void adjustQuantity(Item item, double newQuantity) {
        if (newQuantity <= 0) {
            throw new ValidationException("Quantity must be greater than zero.");
        }
        database.transact("update the quantity", conn -> {
            itemDao.adjustQuantity(conn, item.getId(), newQuantity);
        });
        item.setQuantity(newQuantity);
    }

    // ==================== TAGS ====================

    public List<Tag> listTags() {
        return database.query("load your tags", tagDao::findAll);
    }

    public List<String> listTagNames() {
        return database.query("load your tags", tagDao::findAllNames);
    }

    public List<Item> listItemsByTag(String tagName) {
        return database.query("load items tagged " + tagName,
                conn -> itemDao.findByTag(conn, tagName));
    }

    public void renameTag(Tag tag, String rawName) {
        String name = Validator.validateTagName(rawName);
        database.transact("rename the tag", conn -> tagDao.rename(conn, tag.getId(), name));
        tag.setName(name);
    }

    public void deleteTag(Tag tag) {
        database.transact("delete the tag", conn -> tagDao.delete(conn, tag.getId()));
    }

    /** Removes tags that are no longer on any item. */
    public int cleanUpUnusedTags() {
        return database.transaction("tidy up unused tags", tagDao::deleteUnused);
    }

    /**
     * Groups a list of items by tag for the grouped view.
     * <p>
     * An item with several tags appears under each of them, which is the point
     * of tags: they cut across shelves and each other. Anything untagged is
     * collected at the end so it cannot silently disappear from the view.
     */
    public Map<String, List<Item>> groupByTag(List<Item> items) {
        Map<String, List<Item>> grouped = new LinkedHashMap<>();

        TreeSet<String> tagNames = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (Item item : items) {
            tagNames.addAll(item.getTags());
        }
        for (String tag : tagNames) {
            grouped.put(tag, new ArrayList<>());
        }

        List<Item> untagged = new ArrayList<>();
        for (Item item : items) {
            if (item.getTags().isEmpty()) {
                untagged.add(item);
            } else {
                for (String tag : item.getTags()) {
                    grouped.computeIfAbsent(tag, k -> new ArrayList<>()).add(item);
                }
            }
        }
        if (!untagged.isEmpty()) {
            grouped.put("Untagged", untagged);
        }
        return grouped;
    }

    // ==================== PRICE HISTORY ====================

    public List<PricePoint> priceHistoryFor(Item item) {
        return database.query("load price history",
                conn -> priceHistoryDao.findByProduct(conn, item.productKey()));
    }

    public OptionalDouble averagePriceFor(Item item) {
        return database.query("work out the average price",
                conn -> priceHistoryDao.findAveragePrice(conn, item.productKey()));
    }

    /** Lowest and highest unit price ever paid, or empty if none recorded. */
    public Optional<long[]> priceRangeFor(Item item) {
        return database.query("work out the price range",
                conn -> priceHistoryDao.findPriceRange(conn, item.productKey()));
    }

    /**
     * The value of a single item, in cents: unit price times quantity, rounded.
     * Returns null when the item has no price, so callers can show a dash rather
     * than a misleading zero.
     * <p>
     * This is the one place the value formula lives. The status-bar total sums
     * exactly this over the visible items, and the per-row Total Value column
     * shows exactly this for each one, so the row values always add up to the
     * total by construction.
     */
    public Long itemValue(Item item) {
        if (item.getPriceCents() == null) {
            return null;
        }
        return Math.round(item.getPriceCents() * item.getQuantity());
    }

    /** Total value of a list of items, in cents, ignoring unpriced ones. */
    public long totalValue(List<Item> items) {
        long total = 0;
        for (Item item : items) {
            Long value = itemValue(item);
            if (value != null) {
                total += value;
            }
        }
        return total;
    }

    // ==================== DELETED ITEMS ====================

    /**
     * Every archived item, each with its last-bought date filled in from the
     * purchase log.
     * <p>
     * The date is read from the history rather than the archived item's own
     * purchase-date field, so it reflects the most recent purchase of that
     * product across every item of it, which is what "last bought" should mean.
     * The history outlives individual items, so it is still there to read even
     * though the item itself is gone.
     */
    public List<com.shelves.model.DeletedItem> listDeletedItems() {
        return database.query("load deleted items", conn -> {
            List<com.shelves.model.DeletedItem> items = deletedItemDao.findAll(conn);
            for (com.shelves.model.DeletedItem item : items) {
                LocalDate lastBought =
                        priceHistoryDao.findLatestPurchaseDate(conn, item.productKey());
                // Fall back to the item's own recorded purchase date if the log
                // has nothing, so the column is not needlessly blank.
                item.setLastBoughtDate(
                        lastBought != null ? lastBought : item.getPurchaseDate());
            }
            return items;
        });
    }

    public int deletedItemCount() {
        return database.query("count deleted items", deletedItemDao::count);
    }

    /**
     * Restores an archived item back to a real, usable item and removes it from
     * the archive.
     * <p>
     * The item returns to its original shelf when that shelf still exists, and
     * becomes unfiled (still visible on the Master Shelf) when it does not, so a
     * restore never fails just because the shelf was deleted in the meantime.
     * Both writes run in one transaction, so a restore is all or nothing.
     *
     * @return the freshly created item
     */
    public Item restoreDeletedItem(com.shelves.model.DeletedItem archived) {
        return database.transaction("restore the item", conn -> {
            Item item = archived.toItem();

            // Drop the shelf link if that shelf is gone, rather than pointing at
            // a shelf id that no longer exists.
            if (item.getShelfId() != null
                    && shelfDao.findById(conn, item.getShelfId()).isEmpty()) {
                item.setShelfId(null);
            }

            itemDao.insert(conn, item);
            deletedItemDao.remove(conn, archived.getId());
            return item;
        });
    }

    /**
     * Permanently removes one archived item, and — because this is the explicit,
     * unrecoverable "delete for good" action — also erases the price and usage
     * history for that product, but only if nothing else still uses it.
     * <p>
     * History is deliberately kept by product key in the normal case: deleting an
     * item because it is used up and later rebuying the same product should keep
     * its past. Permanent delete is the one moment the user is unambiguously
     * saying "erase all of this," so here the history goes too. The guard matters:
     * if another item — still live, or another archived snapshot — shares this
     * product key, its history must survive, so the purge only happens when this
     * was the last thing referencing the product.
     */
    public void purgeDeletedItem(com.shelves.model.DeletedItem archived) {
        database.transact("remove the archived item", conn -> {
            deletedItemDao.remove(conn, archived.getId());
            if (!productKeyStillInUse(conn, archived.productKey(), archived.getId())) {
                priceHistoryDao.deleteByProductKey(conn, archived.productKey());
            }
        });
    }

    /**
     * Whether any item still references a product key, so its history must be
     * kept. Checks both live items and other archived snapshots; the archive row
     * currently being purged is excluded by id.
     */
    private boolean productKeyStillInUse(java.sql.Connection conn, String productKey,
                                         int excludingArchivedId) throws java.sql.SQLException {
        for (Item live : itemDao.findAll(conn)) {
            if (live.productKey().equals(productKey)) {
                return true;
            }
        }
        for (com.shelves.model.DeletedItem archived : deletedItemDao.findAll(conn)) {
            if (archived.getId() != excludingArchivedId
                    && archived.productKey().equals(productKey)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Empties the archive entirely, erasing the history of every product that no
     * live item still uses. Same reasoning as {@link #purgeDeletedItem}: emptying
     * the bin is an explicit permanent delete of everything in it, so the history
     * of those products goes too — except where a live item still stocks the
     * product, whose history is kept.
     */
    public int emptyDeletedItems() {
        return database.transaction("empty the archive", conn -> {
            List<com.shelves.model.DeletedItem> archived = deletedItemDao.findAll(conn);
            int cleared = deletedItemDao.clear(conn);

            // With the archive now empty, purge history for any archived product
            // that no live item still uses. Distinct keys so a product archived
            // twice is only checked once.
            java.util.Set<String> keys = new java.util.HashSet<>();
            for (com.shelves.model.DeletedItem item : archived) {
                keys.add(item.productKey());
            }
            for (String key : keys) {
                if (!productKeyStillInUse(conn, key, -1)) {
                    priceHistoryDao.deleteByProductKey(conn, key);
                }
            }
            return cleared;
        });
    }
}
