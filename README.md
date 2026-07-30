# Shelves

**Student:** Naisha Lopez Clemente
**Course:** COP 2800

A desktop inventory tool for personal and professional use. It tracks what you
have, what's expiring, and what things cost over time — a spreadsheet's job,
without needing spreadsheet skills.

Built with JavaFX and SQLite.

---

## Running it

**Requirements:** JDK 17 or newer, and Maven.

```bash
# Run the application
mvn javafx:run

# Run the automated checks
mvn verify

# Build a single runnable jar
mvn package
java -jar target/shelves-1.0.0.jar
```

To load sample inventory for a demo (20 items across three shelves, with a
realistic mix of fresh, expiring and expired):

```bash
mvn compile exec:java -Dexec.mainClass=com.shelves.DemoData
```

### Where your data lives

Everything is kept in `~/.shelves`:

```
~/.shelves/
├── shelves.db      the SQLite database
└── photos/         item photos, copied here when you choose them
```

Point the app somewhere else with `-Dshelves.home=/some/path`. The automated
checks use this to build a throwaway database rather than touching real data.

**Upgrading from an earlier build:** newer columns are added to an existing
database in place on launch, via `ALTER TABLE` guarded by a check for whether
the column is already there (see `Database.migrateColumns`), so your data
carries forward and there is nothing to delete. The schema version is tracked in
SQLite's `user_version`.

---

## What it does

- **Shelves.** Organise items into shelves you create — Kitchen, Stadium
  Concession, Medicine Cabinet. A permanent Master Shelf always shows
  everything, including items not filed anywhere.
- **Tags.** Label items across shelves (Meat, Dairy, Event Stock) and switch
  between a flat list and a grouped view. An item with several tags appears
  under each of them.
- **Notes.** Jot anything worth remembering on an item. A dot in the table marks
  any item that has a note, and a dedicated Notes view collects every note in
  one place, each labelled with its item and shelf.
- **Photos.** Attach a photo for reference. Thumbnails show in the table.
- **Shelf life reference.** Around 100 common products with typical keeping
  times, tracked separately for opened and unopened.
- **Alerts.** Each item has its own alert window. The badge in the top bar
  counts what needs attention, and the alerts view keeps already-expired items
  visibly apart from those merely approaching their date.
- **Cost and usage over time.** Every change to a tracked item is logged as a
  purchase or a depletion, each with quantity, price and date. A restock logs the
  amount actually bought — the increase over what was on hand, so raising a count
  from 1 to 8 records a purchase of 7, not 8 — while a decrease is never a
  purchase but a depletion. The log follows the product, not the item, so it
  survives a carton being used up and thrown away. Opening an item's editor shows
  an Item History tab with a price history table (date, quantity, unit price and
  line total, capped to a few visible rows with the rest scrolling) and a Usage
  Tracker donut that reconciles by quantity: bought equals used plus expired plus
  unused, where unused is derived live (what is left once use and waste are taken
  off what was bought) rather than tracked. Saving a change that touches the price
  or quantity asks what it was — new purchase, correction, used, or expired — so a
  typo fix never looks like a restock, and consumption is told apart from waste.
  Setting the quantity to zero is a valid "none left" that records the remainder
  as gone without deleting the item.
- **Total value at a glance.** The inventory table shows each item's total value
  — unit price times quantity — in its own column, using the same formula that
  produces the shelf total in the status bar, so the rows always add up to it.
- **Deleted Items.** Deleting an item sends it to a recycle bin rather than
  erasing it, along with an optional reason. The Deleted Items page lists
  everything removed and can restore any of it to a real item on its old shelf.
- **Export.** Export any shelf, with a preview of exactly what will be written,
  as a CSV spreadsheet or a printable PDF inventory sheet with tick boxes.
- **Barcodes (behind the scenes).** Barcode support lives in the codebase — a
  local table maps a barcode to product details the user has saved, with no
  external service — but is not surfaced in the item form in this build. See the
  design note below.

---

## How it's put together

```
com.shelves
├── App                  starts JavaFX, wires up services
├── Launcher             plain entry point for the runnable jar
│
├── model/               plain data objects, no framework, no SQL
│   Item, Shelf, Tag, PricePoint, ShelfLifeEntry, ExpiryStatus,
│   HistoryKind, DeletedItem
│
├── exception/           ShelvesException
│                        ├── DataAccessException
│                        └── ValidationException
│
├── util/                Money, Dates, Validator, Animations, SimplePdf
│
├── db/                  Database — connections, transactions, schema
│   └── dao/             BaseDao, ItemDao, ShelfDao, TagDao,
│                        PriceHistoryDao, ShelfLifeDao, ProductCacheDao,
│                        DeletedItemDao
│
├── service/             InventoryService, ExpiryService, ShelfLifeService,
│                        BarcodeService, ExportService, EditMode, ChangeKind
│
└── ui/                  MainView, ItemFormDialog, ItemHistoryPanel,
                         DeletedItemsDialog, ExportDialog, Dialogs, ThemeManager
```

Dependencies point one way only: `ui` → `service` → `db/dao` → `model`. The UI
has no `java.sql` imports anywhere, and the DAOs know nothing about JavaFX.

### Design decisions worth knowing

**Light and dark themes switch from one place.** Every colour is defined once in
the stylesheet as a named variable, in two blocks: `.root` holds the light
(warm cream / burnt-orange) values, and `.root.dark-mode` re-defines the same
names with dark values. Switching theme is nothing more than adding or removing
the `dark-mode` style class on the scene root — one class toggle recolours the
whole app, and no rule anywhere else hard-codes a colour. `ThemeManager` owns
this: it applies the class, remembers the choice in `theme.pref` in the data
folder so the app reopens in the last mode, and exposes the active theme for the
one thing CSS cannot reach — the Usage Tracker donut, which is drawn on a canvas
and so reads its colours from `ThemeManager` in code instead. Dark mode uses a
deliberate three-tier text hierarchy (primary for item names, a dimmer secondary
for supporting columns, tertiary for placeholders) so names stay readable at low
brightness rather than every cell competing at one weight; both modes clear WCAG
AA for body text.

**The Master Shelf is a view, not a container.** Nothing is ever assigned to it.
`InventoryService.listItems` routes it to an unfiltered query. Storing it as a
shelf that everything also belongs to would mean maintaining duplicate
membership on every write, and querying it by `shelf_id` would correctly return
nothing at all.

**Money is stored as whole cents.** A `double` cannot represent most decimal
amounts exactly, so totals across a shelf would slowly drift. Formatting to
dollars happens only at the moment of display.

**Dates are `LocalDate` in the model, ISO-8601 text in the database.** SQLite
has no date type, and ISO-8601 sorts chronologically as a string, so `ORDER BY`
and `BETWEEN` work in SQL with no conversion. Keeping them as real dates in
Java is what makes expiry arithmetic possible.

**Opening an item can only bring its expiry forward.** A jar printed for
December, opened in November with a seven day opened life, expires in November.
A jar opened the day before its printed date still expires on the printed date.
The effective expiry is the earlier of the two — see `ExpiryService`.

**The history log is keyed by product, not item id.** Each entry records a
quantity, price, date, and a kind — purchase or usage — keyed by product. An
item row is a specific thing on a shelf and disappears when it is used up, so
the log has to outlive it, otherwise buying milk in January and again in March
produces two unrelated records. The key is the barcode when there is one, and a
normalised name otherwise. Each entry is also stamped with the id of the item
that wrote it, which is how a later correction finds the right entry to amend.
Price averages and the price history table count purchases only; usage events
carry no meaningful price and are excluded.

**Editing asks: purchase, correction, used, or expired?** A change to an item
can mean four different things. Restocking should add a purchase entry; fixing a
mistyped price should not; using some stock and throwing some out are both
depletions but mean opposite things — stock doing its job versus stock wasted.
Rather than guess, an edit that touches the price or the quantity asks. A
purchase appends an entry for the amount actually bought — the increase over what
was on hand, not the new total, so raising a count from 1 to 8 logs 7 — a
correction amends the item's most recent purchase in place, and used or expired
each record the amount that went under their own kind. The options offered mirror
which way the quantity moved: only a rise offers "new purchase" (a drop can never
be a purchase), and only a drop offers "used" and "expired" (a rise can never be
stock leaving); an unchanged count offers all four, and "correction" is always
available. That rule lives in `ChangeKind.optionsFor` — free of any UI so it can
be reasoned about and tested directly — and the prompt is only a view onto it,
building its buttons from exactly what the rule returns. Used and expired are
split at this point on purpose: the Usage Tracker can only separate consumption
from waste if the two are separated here, where the fact is known. The amount
logged is always measured against the quantity read from the database inside the
save transaction, so the Details tab and the log never disagree about what
changed. A correction amends the latest purchase in place — it fixes the price,
and shifts that entry's recorded quantity by the same amount the item's quantity
moved, so correcting a mistyped opening count of 10 down to 5 brings the purchase
itself to 5. The shift is applied as a delta rather than by writing the item's
on-hand total onto the entry, because purchases store the amount bought at the
time; overwriting a single entry with the running total would inflate the history
whenever more than one purchase exists. A correction never touches a depletion
entry, so fixing a purchase cannot rewrite the record of what was used, and
setting the quantity to zero is a valid depletion (not a correction) that records
the remainder as gone. Nothing is ever deleted, so a wrong
answer is recoverable and the log cannot be silently corrupted. See `ChangeKind`,
`EditMode`, `HistoryKind`, and `InventoryService.updateItem`.

**Unused stock is derived, not tracked.** The Usage Tracker reconciles what was
bought against what became of it: bought equals used plus expired plus unused.
Only the first three are ever logged; unused is computed live as what is left
once use and waste are subtracted from purchases, which is roughly the quantity
on hand. Deriving it rather than storing it means it cannot fall out of step with
the events it is defined against. See `ItemHistoryPanel`.

**Item value has one formula.** An item's total value is its unit price times its
quantity. That single calculation lives in `InventoryService.itemValue`; the
status-bar shelf total sums exactly it over the visible items, and the table's
Total Value column shows exactly it per row, so the rows always add up to the
total by construction rather than by two formulas happening to agree.

**Deleting is recoverable, not destructive.** A deleted item is archived as a
full snapshot — every field, its tags, and its shelf — rather than erased, with
an optional reason. The Deleted Items page can restore it to a real item on its
original shelf, or to unfiled when that shelf is gone, so losing an item to a
misclick is never permanent. See `DeletedItem`, `DeletedItemDao`, and
`InventoryService.restoreDeletedItem`.

**Permanent delete is the one action that erases history.** History is kept by
product key precisely so it outlives an item — using something up and rebuying it
later keeps its past (see the note on the history log below). The single
exception is the explicit "delete permanently" action in the Deleted Items bin
(and emptying the bin): there the user is unambiguously saying "erase all of
this," so the product's price and usage history is purged too. The purge is
scoped carefully — it only runs when no other item, live or still archived,
shares that product key, so deleting one copy of a product two items stock never
wipes history the other still relies on. See `InventoryService.purgeDeletedItem`
and `PriceHistoryDao.deleteByProductKey`.

**Barcodes resolve against a local table, not an online service.** An online
product database covers groceries and almost nothing else, so it fails for
medicine, cosmetics and hardware — most of what a general inventory tool holds.
The design instead has the user build their own lookup: a barcode maps to
product details they save, with no network and full category coverage. This
build keeps that machinery (`BarcodeService`, `ProductCacheDao`) but does not
surface the barcode field in the item form; manual entry with a photo is the
primary path, and the local lookup is groundwork for a future point-of-sale use.

**PDF export is written by hand, not with a library.** Exporting a plain
inventory sheet does not justify pulling in a large PDF dependency. The PDF
format's text and layout operators are simple, so `SimplePdf` lays out a title
and paginated rows of text using the standard Helvetica font, which every reader
has built in. It supports only what the export needs and is not a general PDF
toolkit.

**Photos are files on disk, not database blobs.** Chosen images are copied into
`~/.shelves/photos` under a generated name, and only the path is stored. Keeping
the path to wherever the user picked the file would break the moment they tidied
their downloads folder.

**Deleting a shelf keeps its items.** The foreign key is `ON DELETE SET NULL`,
so items become unfiled and stay visible on the Master Shelf. Losing a container
should never mean losing inventory.

---

## Two things that will bite you if you touch the database layer

**`getGeneratedKeys()` does not work on a `PreparedStatement` in SQLite.** It
throws `SQLFeatureNotSupportedException`. Every insert goes through
`BaseDao.lastInsertId()`, which asks SQLite for `last_insert_rowid()` on the
same connection.

**`sqlite-jdbc` 3.42 and later require `slf4j-api` at runtime.** Without it the
driver class fails to load with a `NoClassDefFoundError` that looks nothing like
the real problem. Both `slf4j-api` and the no-op binding are declared in
`pom.xml`; do not remove them.

---

## Validation and error handling

Input is checked in `Validator` before anything is written, and every problem is
collected rather than only the first, so a form can show all its errors at once.
Rules cover required fields, lengths, numeric ranges, and date ordering —
purchase dates cannot be in the future, expiry cannot precede purchase, opened
cannot precede purchase.

The database enforces its own constraints as a second line of defence
(`CHECK (quantity >= 0)`, `UNIQUE COLLATE NOCASE` on names, foreign keys), but
relying on those alone would mean the only feedback a user gets is a constraint
violation written for a programmer.

Every `SQLException` is wrapped in a `DataAccessException` before it leaves the
persistence layer, with the original kept as the cause. `MainView.guard` runs
every button action, and `Dialogs` decides whether the user is looking at
something they can fix or something that is not their fault. No screen contains
a try block of its own.

Multi-statement operations run in transactions. Archiving an item and then
deleting it is the clearest case: if the delete fails, the archive row must not
survive, or the app records a deletion that never happened.

---

## Automated checks

`SmokeTest` runs 135 checks against a temporary database covering CRUD on shelves
and items, the Master Shelf behaviour, tag handling, validation rules (including
that a quantity of zero is allowed but a negative one is not), expiry arithmetic
including the opened-item case, the shelf life reference, the history log
surviving deletion, the purchase / correction / used / expired distinctions
(a restock logs the increase rather than the new total, saving again with no
change does not touch the log — no phantom purchase, no rewritten quantity — a
depletion records the
amount that went, used and expired stay distinct, a correction never overwrites
either, and emptying to zero logs the remainder as gone), the usage
reconciliation identity (bought equals used plus expired plus unused, with unused
derived), per-item value against the aggregate total, name-only search (matching
item names, not notes or barcodes), the delete-and-restore
round trip including restore to a since-deleted shelf, per-row purge, the
last-bought date drawn from history, exact money arithmetic, CSV quoting and
escaping, PDF file structure, and the local barcode round trip.

```bash
mvn verify
```

They are written as a plain `main` method rather than JUnit tests so the project
builds and checks itself with no test framework to install. Converting them to
JUnit is mechanical if that is wanted later.

---

## Known limits

- **Barcodes are not surfaced in the item form.** The local barcode lookup
  exists in the codebase but is hidden in this build, so all product entry is
  manual (with a photo). Re-exposing the field is a small change when wanted.
- **Webcam scanning is not supported.** The intended input is a USB scanner,
  which presents as a keyboard and types the digits, so no camera-based image
  processing is involved.
- **PDF export uses one font and Latin-1 text.** `SimplePdf` embeds no fonts and
  relies on the reader's built-in Helvetica, so characters outside Latin-1 (for
  example non-European scripts) are replaced with a hyphen. CSV export is full
  Unicode and is the right choice when that matters.
- **One user, one machine.** SQLite handles concurrent readers fine, but this is
  not built for two people editing the same file over a network share.

## Future work

- **Usage and popularity analysis.** The log now records usage events, so the
  raw data is there; what is not built is analysis on top — flagging items that
  are repeatedly bought and then wasted, or surfacing what moves fastest.
- **Export column customisation.** Export is currently a fixed set of columns;
  letting the user choose which to include is a natural extension.
- **A user-built barcode catalogue in the UI.** The local barcode table and its
  DAO already map a barcode to saved product details; re-surfacing the field and
  auto-filling from previous entries would complete the loop with no external
  service.
- **Point-of-sale groundwork.** That same local barcode table is the core of a
  small-shop lookup; a checkout flow on top of it is a natural next step.
- **Mobile app and cloud sync.** Push notifications for expiry and a shared
  database would take this beyond a single desktop.
- **Per-batch expiration tracking.** Editing an item's expiration date after a
  restock currently applies the new date across the item as a whole, rather
  than tracking which purchase batch it belongs to. For items bought at
  different times with different shelf lives, this is a simplification worth
  revisiting.