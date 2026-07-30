package com.shelves.ui;

import com.shelves.exception.ShelvesException;
import com.shelves.model.ExpiryStatus;
import com.shelves.model.Item;
import com.shelves.model.Shelf;
import com.shelves.service.ChangeKind;
import com.shelves.service.EditMode;
import com.shelves.service.ExpiryService;
import com.shelves.service.InventoryService;
import com.shelves.service.ShelfLifeService;
import com.shelves.util.Animations;
import com.shelves.util.Dates;
import com.shelves.util.Money;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Separator;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TitledPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The main window: shelves down the left, items in the middle, actions along
 * the bottom.
 * <p>
 * This class handles presentation and user intent only. It never touches the
 * database directly and never sees a {@code SQLException}; everything goes
 * through {@link InventoryService}, and anything that fails arrives here as a
 * {@link ShelvesException} to be shown by {@link Dialogs}.
 */
public class MainView extends BorderPane {

    private final Stage stage;
    private final InventoryService inventory;
    private final ShelfLifeService shelfLife;

    private final ListView<Shelf> shelfList = new ListView<>();
    private final TableView<Item> itemTable = new TableView<>();
    private final ObservableList<Item> visibleItems = FXCollections.observableArrayList();
    private final TextField searchField = new TextField();
    private final Button alertBadge = new Button();
    private final Button themeToggle = new Button();
    private ThemeManager themeManager;
    private final Label statusMessage = new Label();
    private final Label totalValueLabel = new Label();
    private final Label emptyMessage = new Label();
    private final StackPane contentArea = new StackPane();
    private final VBox groupedView = new VBox(8);
    private final ScrollPane groupedScroller = new ScrollPane(groupedView);
    private final VBox notesView = new VBox(10);
    private final ScrollPane notesScroller = new ScrollPane(notesView);
    private final ToggleButton flatToggle = new ToggleButton("List");
    private final ToggleButton groupedToggle = new ToggleButton("By tag");
    private final ToggleButton notesToggle = new ToggleButton("Notes");

    private final Map<Integer, String> shelfNames = new HashMap<>();
    private final Map<String, Image> thumbnailCache = new HashMap<>();

    private Button editButton;
    private Button deleteButton;
    private Button openedButton;
    private Button deletedItemsButton;

    public MainView(Stage stage, InventoryService inventory,
                    ShelfLifeService shelfLife) {
        this.stage = stage;
        this.inventory = inventory;
        this.shelfLife = shelfLife;

        setLeft(buildSidebar());
        setTop(buildTopBar());
        setCenter(buildContentArea());
        setBottom(buildActionBar());

        refreshShelves();
        selectMasterShelf();
    }

    // ==================== SIDEBAR ====================

    private Node buildSidebar() {
        Label title = new Label("Shelves");
        title.getStyleClass().add("app-title");

        Label subtitle = new Label("Know what you have");
        subtitle.getStyleClass().add("app-subtitle");

        Label shelvesHeading = new Label("SHELVES");
        shelvesHeading.getStyleClass().add("section-label");

        shelfList.getStyleClass().add("shelf-list");
        shelfList.setCellFactory(list -> new ShelfCell());
        shelfList.getSelectionModel().selectedItemProperty()
                .addListener((observable, previous, selected) -> refreshItems());
        VBox.setVgrow(shelfList, Priority.ALWAYS);

        Button newShelf = sidebarButton("New shelf", this::createShelf);
        Button renameShelf = sidebarButton("Rename shelf", this::renameSelectedShelf);
        Button deleteShelf = sidebarButton("Delete shelf", this::deleteSelectedShelf);
        // Export lives with the shelf controls because it acts on a whole
        // shelf's worth of items, not on one selected item.
        Button exportShelf = sidebarButton("Export\u2026", this::openExport);

        // The Master Shelf is a view, not a container, so it cannot be edited.
        shelfList.getSelectionModel().selectedItemProperty()
                .addListener((observable, previous, selected) -> {
                    boolean editable = selected != null && !selected.isDefaultShelf();
                    renameShelf.setDisable(!editable);
                    deleteShelf.setDisable(!editable);
                });

        deletedItemsButton = sidebarButton("Deleted Items", this::showDeletedItems);
        deletedItemsButton.getStyleClass().add("deleted-items-button");

        VBox footer = new VBox(2, newShelf, renameShelf, deleteShelf, exportShelf,
                new Separator(), deletedItemsButton);
        footer.getStyleClass().add("sidebar-footer");

        VBox sidebar = new VBox(title, subtitle, shelvesHeading, shelfList,
                new Separator(), footer);
        sidebar.getStyleClass().add("sidebar");
        return sidebar;
    }

    private Button sidebarButton(String text, Runnable action) {
        Button button = new Button(text);
        button.getStyleClass().add("sidebar-button");
        button.setMaxWidth(Double.MAX_VALUE);
        button.setOnAction(event -> guard(action));
        return button;
    }

    /** Shows a shelf name with how many items sit on it. */
    private class ShelfCell extends ListCell<Shelf> {
        private final Label name = new Label();
        private final Label count = new Label();
        private final HBox layout;

        ShelfCell() {
            count.getStyleClass().add("shelf-count");
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            layout = new HBox(name, spacer, count);
            layout.setAlignment(Pos.CENTER_LEFT);
        }

        @Override
        protected void updateItem(Shelf shelf, boolean empty) {
            super.updateItem(shelf, empty);
            if (empty || shelf == null) {
                setGraphic(null);
                return;
            }
            name.setText(shelf.getName());
            count.setText(String.valueOf(shelf.getItemCount()));
            setGraphic(layout);
        }
    }

    // ==================== TOP BAR ====================

    private Node buildTopBar() {
        searchField.setPromptText("Search items");
        searchField.getStyleClass().add("search-field");
        searchField.textProperty().addListener((observable, was, now) -> refreshItems());

        ToggleGroup viewGroup = new ToggleGroup();
        flatToggle.setToggleGroup(viewGroup);
        groupedToggle.setToggleGroup(viewGroup);
        notesToggle.setToggleGroup(viewGroup);
        flatToggle.setSelected(true);
        Tooltip.install(groupedToggle, new Tooltip(
                "Group items under their tags. An item with several tags appears under each."));
        Tooltip.install(notesToggle, new Tooltip(
                "Show every item's notes together, labelled by item and shelf."));

        // Clicking the selected toggle again must not leave neither selected.
        viewGroup.selectedToggleProperty().addListener((observable, was, now) -> {
            if (now == null) {
                viewGroup.selectToggle(was);
            } else {
                refreshItems();
            }
        });

        // List and By tag are two ways of looking at the same item list, so
        // they read as a joined segmented control. Notes is a different kind of
        // view — it shows notes, not items — so it is set apart with its own
        // styling and a gap, rather than sitting as a third equal segment.
        flatToggle.getStyleClass().add("segment-left");
        groupedToggle.getStyleClass().add("segment-right");
        HBox itemViews = new HBox(flatToggle, groupedToggle);
        itemViews.getStyleClass().add("view-segment");

        notesToggle.getStyleClass().add("notes-toggle");

        HBox toggles = new HBox(10, itemViews, notesToggle);
        toggles.setAlignment(Pos.CENTER_LEFT);

        alertBadge.getStyleClass().add("alert-badge");
        alertBadge.setOnAction(event -> guard(this::showAlerts));

        themeToggle.getStyleClass().add("theme-toggle");
        themeToggle.setOnAction(event -> toggleTheme());
        Tooltip.install(themeToggle, new Tooltip("Switch between light and dark mode"));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox topBar = new HBox(12, searchField, spacer, toggles, themeToggle, alertBadge);
        topBar.getStyleClass().add("top-bar");
        topBar.setAlignment(Pos.CENTER_LEFT);
        return topBar;
    }

    /**
     * Wires up the theme manager once it exists (it needs the built scene root),
     * and sets the toggle button to show the mode it will switch to.
     */
    public void setThemeManager(ThemeManager themeManager) {
        this.themeManager = themeManager;
        updateThemeToggleLabel();
    }

    private void toggleTheme() {
        if (themeManager == null) {
            return;
        }
        themeManager.toggle();
        updateThemeToggleLabel();
    }

    private void updateThemeToggleLabel() {
        if (themeManager == null) {
            return;
        }
        // The button offers the mode you are not in, so it reads as an action.
        themeToggle.setText(themeManager.isDark() ? "\u2600 Light" : "\u263D Dark");
    }

    // ==================== CONTENT ====================

    private Node buildContentArea() {
        buildItemTable();

        emptyMessage.getStyleClass().add("muted-text");
        emptyMessage.setWrapText(true);
        emptyMessage.setMaxWidth(360);
        emptyMessage.setAlignment(Pos.CENTER);

        groupedView.setPadding(new Insets(12));
        groupedScroller.setFitToWidth(true);
        groupedScroller.setVisible(false);

        notesView.setPadding(new Insets(12));
        notesView.getStyleClass().add("notes-panel");
        notesScroller.setFitToWidth(true);
        notesScroller.getStyleClass().add("notes-panel");
        notesScroller.setVisible(false);

        contentArea.getChildren().addAll(itemTable, groupedScroller, notesScroller);
        return contentArea;
    }

    // CONSTRAINED_RESIZE_POLICY is deprecated in JavaFX 20+ in favour of
    // CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS, but that constant does not exist in
    // earlier JavaFX. The old one still works and behaves identically here, so it
    // is kept and the deprecation notice suppressed rather than tying the code to
    // one JavaFX generation.
    @SuppressWarnings("deprecation")
    private void buildItemTable() {
        itemTable.setItems(visibleItems);
        itemTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        itemTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        itemTable.setPlaceholder(emptyMessage);

        // The status colour lives on the row, as a bar down its leading edge.
        itemTable.setRowFactory(table -> new TableRow<>() {
            @Override
            protected void updateItem(Item item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().removeAll("status-fresh", "status-soon",
                        "status-expired", "status-none");
                if (!empty && item != null) {
                    getStyleClass().add(ExpiryService.status(item).getStyleClass());
                }
            }
        });

        itemTable.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2 && selectedItem() != null) {
                guard(this::editSelectedItem);
            }
        });

        itemTable.getColumns().setAll(List.of(
                photoColumn(),
                notesColumn(),
                nameColumn(),
                textColumn("Quantity", 90, Item::getQuantityDisplay),
                textColumn("Shelf", 120, item -> shelfNameFor(item)),
                tagColumn(),
                textColumn("Price", 80, item -> Money.formatOrDash(item.getPriceCents())),
                // Total Value = unit price times quantity, using the same
                // per-item formula the status-bar total sums, so the column and
                // the total can never disagree.
                textColumn("Total Value", 90,
                        item -> Money.formatOrDash(inventory.itemValue(item))),
                expiryColumn(),
                statusColumn()));
    }

    /**
     * A narrow column showing a small dot on any item that has notes, so the
     * presence of a note is visible at a glance without opening the item. The
     * note itself is shown in a tooltip on hover.
     */
    private TableColumn<Item, Item> notesColumn() {
        TableColumn<Item, Item> column = new TableColumn<>("");
        column.setPrefWidth(26);
        column.setMaxWidth(26);
        column.setMinWidth(26);
        column.setSortable(false);
        column.getStyleClass().add("notes-icon-cell");
        column.setCellValueFactory(data -> new SimpleObjectProperty<>(data.getValue()));
        column.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Item item, boolean empty) {
                super.updateItem(item, empty);
                boolean hasNote = !empty && item != null
                        && item.getNotes() != null && !item.getNotes().isBlank();
                if (!hasNote) {
                    setGraphic(null);
                    setTooltip(null);
                    return;
                }
                javafx.scene.shape.Circle dot = new javafx.scene.shape.Circle(3.5);
                dot.getStyleClass().add("notes-dot");
                setGraphic(dot);
                Tooltip tip = new Tooltip(item.getNotes());
                tip.setWrapText(true);
                tip.setMaxWidth(280);
                setTooltip(tip);
            }
        });
        return column;
    }

    private TableColumn<Item, Item> photoColumn() {
        TableColumn<Item, Item> column = new TableColumn<>("");
        column.setPrefWidth(44);
        column.setMaxWidth(44);
        column.setMinWidth(44);
        column.setSortable(false);
        column.setCellValueFactory(data -> new SimpleObjectProperty<>(data.getValue()));
        column.setCellFactory(col -> new TableCell<>() {
            private final ImageView view = new ImageView();

            {
                view.setFitWidth(28);
                view.setFitHeight(28);
                view.setPreserveRatio(true);
            }

            @Override
            protected void updateItem(Item item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null || item.getPhotoPath() == null) {
                    setGraphic(null);
                    return;
                }
                Image image = thumbnailFor(item.getPhotoPath());
                if (image == null) {
                    setGraphic(null);
                } else {
                    view.setImage(image);
                    setGraphic(view);
                }
            }
        });
        return column;
    }

    /**
     * The name column, which during a search also shows which field matched.
     * <p>
     * A plain filtered list leaves the user guessing why a row is present when
     * the search term is nowhere in the visible name, because it matched a note
     * or a barcode instead. A small chip beside the name naming the matched
     * field answers that at a glance.
     */
    private TableColumn<Item, Item> nameColumn() {
        TableColumn<Item, Item> column = new TableColumn<>("Name");
        column.setPrefWidth(200);
        column.setCellValueFactory(data -> new SimpleObjectProperty<>(data.getValue()));
        column.setComparator((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(
                a == null ? "" : a.getName(), b == null ? "" : b.getName()));
        column.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Item item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    setText(null);
                    return;
                }
                // Search matches item names only, so a visible row always matches
                // on its name; there is nothing else to flag.
                setGraphic(new Label(item.getName()));
            }
        });
        return column;
    }

    /**
     * Which field a search term matched for an item, for the result chip.
     * <p>
     * Returns null when there is no search, or when the match is the name
     * itself, since a chip that says "name" next to the matching name would
     * only add noise. Names are checked first so a note chip never appears on a
     * row whose name already contains the term.
     */
    private TableColumn<Item, String> textColumn(String heading, double width,
                                                 java.util.function.Function<Item, String> reader) {
        TableColumn<Item, String> column = new TableColumn<>(heading);
        column.setPrefWidth(width);
        column.setCellValueFactory(data ->
                new SimpleStringProperty(reader.apply(data.getValue())));
        // Supporting columns (shelf, quantity, price, dates) sit at the secondary
        // text tier, a step down from item names. This is the dark-mode contrast
        // fix: names read at full-strength primary, everything supporting is
        // clearly dimmer, so the eye knows what to scan first instead of every
        // cell competing at the same weight.
        column.setCellFactory(col -> {
            TableCell<Item, String> cell = new TableCell<>() {
                @Override
                protected void updateItem(String value, boolean empty) {
                    super.updateItem(value, empty);
                    setText(empty ? null : value);
                }
            };
            cell.getStyleClass().add("supporting-cell");
            return cell;
        });
        return column;
    }

    private TableColumn<Item, Item> tagColumn() {
        TableColumn<Item, Item> column = new TableColumn<>("Tags");
        column.setPrefWidth(160);
        column.setCellValueFactory(data -> new SimpleObjectProperty<>(data.getValue()));
        column.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Item item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null || item.getTags().isEmpty()) {
                    setGraphic(null);
                    return;
                }
                HBox chips = new HBox(4);
                chips.setAlignment(Pos.CENTER_LEFT);
                for (String tag : item.getTags()) {
                    Label chip = new Label(tag);
                    chip.getStyleClass().add("tag-chip");
                    chips.getChildren().add(chip);
                }
                setGraphic(chips);
            }
        });
        return column;
    }

    private TableColumn<Item, String> expiryColumn() {
        TableColumn<Item, String> column = new TableColumn<>("Expires");
        column.setPrefWidth(110);
        column.setCellValueFactory(data -> new SimpleStringProperty(
                Dates.formatOrDash(ExpiryService.effectiveExpiry(data.getValue()))));
        // Dates are supporting information, so they sit at the secondary tier
        // alongside shelf and price.
        column.setCellFactory(col -> {
            TableCell<Item, String> cell = new TableCell<>() {
                @Override
                protected void updateItem(String value, boolean empty) {
                    super.updateItem(value, empty);
                    setText(empty ? null : value);
                }
            };
            cell.getStyleClass().add("supporting-cell");
            return cell;
        });
        return column;
    }

    private TableColumn<Item, Item> statusColumn() {
        TableColumn<Item, Item> column = new TableColumn<>("Status");
        column.setPrefWidth(110);
        column.setCellValueFactory(data -> new SimpleObjectProperty<>(data.getValue()));
        column.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Item item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().removeAll("expired-text", "soon-text", "muted-text");
                if (empty || item == null) {
                    setText(null);
                    return;
                }
                LocalDate today = LocalDate.now();
                ExpiryStatus status = ExpiryService.status(item, today);
                setText(ExpiryService.describe(item, today));
                switch (status) {
                    case EXPIRED -> getStyleClass().add("expired-text");
                    case EXPIRING_SOON -> getStyleClass().add("soon-text");
                    case NO_DATE -> getStyleClass().add("muted-text");
                    default -> { }
                }
                if (item.isOpened()) {
                    setTooltip(new Tooltip("Opened "
                            + Dates.formatOrDash(item.getOpenedDate())));
                } else {
                    setTooltip(null);
                }
            }
        });
        return column;
    }

    // ==================== ACTION BAR ====================

    private Node buildActionBar() {
        Button addButton = new Button("Add item");
        addButton.getStyleClass().add("primary");
        addButton.setOnAction(event -> guard(this::addItem));

        editButton = new Button("Edit");
        editButton.setOnAction(event -> guard(this::editSelectedItem));

        deleteButton = new Button("Delete");
        deleteButton.getStyleClass().add("danger");
        deleteButton.setOnAction(event -> guard(this::deleteSelectedItems));

        openedButton = new Button("Mark opened");
        openedButton.setOnAction(event -> guard(this::markSelectedOpened));

        // Actions that need a selection stay disabled until there is one.
        itemTable.getSelectionModel().getSelectedItems()
                .addListener((javafx.collections.ListChangeListener<Item>) change ->
                        updateActionAvailability());
        updateActionAvailability();

        statusMessage.getStyleClass().add("status-message");
        statusMessage.setOpacity(0);

        totalValueLabel.getStyleClass().add("total-value");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // Export and item history are gone from here: history now lives inside
        // the edit dialog, and export sits with the shelf controls in the
        // sidebar, since it acts on a whole shelf rather than a selected item.
        HBox bar = new HBox(8, addButton, editButton, openedButton, deleteButton,
                statusMessage, spacer, totalValueLabel);
        bar.getStyleClass().add("action-bar");
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    private void updateActionAvailability() {
        int selected = itemTable.getSelectionModel().getSelectedItems().size();
        editButton.setDisable(selected != 1);
        openedButton.setDisable(selected != 1);
        deleteButton.setDisable(selected == 0);
    }

    // ==================== DATA REFRESH ====================

    /** Reloads the shelf list, keeping whatever was selected selected. */
    private void refreshShelves() {
        Shelf previous = shelfList.getSelectionModel().getSelectedItem();
        List<Shelf> shelves = inventory.listShelves();

        shelfNames.clear();
        for (Shelf shelf : shelves) {
            shelfNames.put(shelf.getId(), shelf.getName());
        }

        shelfList.getItems().setAll(shelves);

        if (previous != null) {
            shelves.stream()
                    .filter(shelf -> shelf.getId() == previous.getId())
                    .findFirst()
                    .ifPresentOrElse(
                            shelf -> shelfList.getSelectionModel().select(shelf),
                            this::selectMasterShelf);
        }
    }

    private void selectMasterShelf() {
        shelfList.getItems().stream()
                .filter(Shelf::isDefaultShelf)
                .findFirst()
                .ifPresent(shelf -> shelfList.getSelectionModel().select(shelf));
    }

    /** Reloads the items for the current shelf, search text and view mode. */
    private void refreshItems() {
        Shelf shelf = shelfList.getSelectionModel().getSelectedItem();
        String search = searchField.getText();

        List<Item> items;
        if (search != null && !search.isBlank()) {
            items = inventory.searchItems(search);
            // A search inside a named shelf stays inside that shelf.
            if (shelf != null && !shelf.isDefaultShelf()) {
                items = items.stream()
                        .filter(item -> item.getShelfId() != null
                                && item.getShelfId() == shelf.getId())
                        .toList();
            }
        } else {
            items = inventory.listItems(shelf);
        }

        visibleItems.setAll(items);
        updateEmptyMessage(shelf, search);
        updateTotals(items);
        updateAlertBadge();

        if (notesToggle.isSelected()) {
            showNotesView(items);
        } else if (groupedToggle.isSelected()) {
            showGroupedView(items);
        } else {
            showFlatView();
        }
    }

    private void updateEmptyMessage(Shelf shelf, String search) {
        if (search != null && !search.isBlank()) {
            emptyMessage.setText("Nothing matches \"" + search.trim() + "\".");
        } else if (shelf != null && shelf.isDefaultShelf()) {
            emptyMessage.setText("Nothing here yet. Add your first item to get started.");
        } else if (shelf != null) {
            emptyMessage.setText("The " + shelf.getName()
                    + " shelf is empty. Add an item, or move one here from another shelf.");
        } else {
            emptyMessage.setText("Pick a shelf on the left.");
        }
    }

    private void updateTotals(List<Item> items) {
        long total = inventory.totalValue(items);
        String count = items.size() == 1 ? "1 item" : items.size() + " items";
        totalValueLabel.setText(total > 0
                ? count + "  \u00b7  " + Money.format(total)
                : count);
    }

    private void updateAlertBadge() {
        List<Item> needsAttention =
                ExpiryService.findNeedingAttention(inventory.listAllItems(), LocalDate.now());
        int count = needsAttention.size();

        alertBadge.setText(count == 0
                ? "No alerts"
                : count + (count == 1 ? " alert" : " alerts"));
        alertBadge.getStyleClass().removeAll("quiet");
        if (count == 0) {
            alertBadge.getStyleClass().add("quiet");
        } else {
            Animations.pulse(alertBadge);
        }
    }

    // ==================== VIEW MODES ====================

    private void showFlatView() {
        groupedScroller.setVisible(false);
        notesScroller.setVisible(false);
        itemTable.setVisible(true);
        Animations.fadeIn(itemTable);
    }

    /**
     * Shows items grouped under their tags, one collapsible panel per tag.
     * <p>
     * An item with several tags appears under each of them, which is the point
     * of tags cutting across shelves. Untagged items get their own panel at the
     * bottom so nothing quietly vanishes when the view is switched.
     */
    private void showGroupedView(List<Item> items) {
        Map<String, List<Item>> grouped = inventory.groupByTag(items);
        groupedView.getChildren().clear();

        if (grouped.isEmpty()) {
            Label none = new Label("Nothing to group yet. Add tags to your items first.");
            none.getStyleClass().add("muted-text");
            groupedView.getChildren().add(none);
        } else {
            for (Map.Entry<String, List<Item>> group : grouped.entrySet()) {
                groupedView.getChildren().add(buildGroupPane(group.getKey(), group.getValue()));
            }
        }

        itemTable.setVisible(false);
        notesScroller.setVisible(false);
        groupedScroller.setVisible(true);
        Animations.riseIn(groupedView);
    }

    /**
     * Shows every note in one place, each on a card labelled with the item it
     * belongs to and the shelf that item sits on.
     * <p>
     * This is the counterpart to the per-row dot: the dot tells you a note
     * exists, this view lets you read all of them at once without opening items
     * one by one, which is the point when you are trying to remember what you
     * flagged across a whole shelf.
     */
    private void showNotesView(List<Item> items) {
        notesView.getChildren().clear();

        List<Item> withNotes = items.stream()
                .filter(item -> item.getNotes() != null && !item.getNotes().isBlank())
                .sorted(java.util.Comparator.comparing(Item::getName,
                        String.CASE_INSENSITIVE_ORDER))
                .toList();

        if (withNotes.isEmpty()) {
            Label none = new Label(
                    "No notes here yet. Notes you add to an item show up together on this tab.");
            none.getStyleClass().add("muted-text");
            none.setWrapText(true);
            notesView.getChildren().add(none);
        } else {
            Label heading = new Label(withNotes.size()
                    + (withNotes.size() == 1 ? " item has a note" : " items have notes"));
            heading.getStyleClass().add("section-label");
            notesView.getChildren().add(heading);

            for (Item item : withNotes) {
                notesView.getChildren().add(buildNoteCard(item));
            }
        }

        itemTable.setVisible(false);
        groupedScroller.setVisible(false);
        notesScroller.setVisible(true);
        Animations.riseIn(notesView);
    }

    /** One note on a card, labelled with its item and shelf. */
    private Node buildNoteCard(Item item) {
        Label name = new Label(item.getName());
        name.getStyleClass().add("note-item-name");

        Label shelf = new Label(shelfNameFor(item));
        shelf.getStyleClass().add("note-shelf");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox header = new HBox(8, name, spacer, shelf);
        header.setAlignment(Pos.CENTER_LEFT);

        Label body = new Label(item.getNotes());
        body.getStyleClass().add("note-body");
        body.setWrapText(true);

        VBox card = new VBox(6, header, body);
        card.getStyleClass().add("note-card");
        card.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                guard(() -> openEditDialog(item));
            }
        });
        return card;
    }

    private TitledPane buildGroupPane(String tagName, List<Item> items) {
        VBox rows = new VBox(2);
        rows.setPadding(new Insets(8));

        for (Item item : items) {
            rows.getChildren().add(buildGroupRow(item));
        }

        long value = inventory.totalValue(items);
        String heading = tagName + "   " + items.size()
                + (items.size() == 1 ? " item" : " items")
                + (value > 0 ? "   " + Money.format(value) : "");

        TitledPane pane = new TitledPane(heading, rows);
        pane.setExpanded(true);
        // TitledPane animates its own expand and collapse, which is exactly the
        // motion this view wants, so nothing extra is added here.
        return pane;
    }

    private Node buildGroupRow(Item item) {
        Label name = new Label(item.getName());
        Label quantity = new Label(item.getQuantityDisplay());
        quantity.getStyleClass().add("muted-text");

        Label status = new Label(ExpiryService.describe(item, LocalDate.now()));
        ExpiryStatus expiryStatus = ExpiryService.status(item);
        if (expiryStatus == ExpiryStatus.EXPIRED) {
            status.getStyleClass().add("expired-text");
        } else if (expiryStatus == ExpiryStatus.EXPIRING_SOON) {
            status.getStyleClass().add("soon-text");
        } else {
            status.getStyleClass().add("muted-text");
        }

        Label shelf = new Label(shelfNameFor(item));
        shelf.getStyleClass().add("muted-text");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox row = new HBox(12, name, quantity, spacer, shelf, status);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(4, 6, 4, 6));
        row.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                guard(() -> openEditDialog(item));
            }
        });
        return row;
    }

    // ==================== ITEM ACTIONS ====================

    private void addItem() {
        Shelf current = shelfList.getSelectionModel().getSelectedItem();
        ItemFormDialog dialog = new ItemFormDialog(
                stage, inventory, shelfLife, null, current);

        dialog.showAndWait().ifPresent(item -> {
            inventory.addItem(item);
            refreshShelves();
            refreshItems();
            selectItemById(item.getId());
            flash("Added " + item.getName());
        });
    }

    private void editSelectedItem() {
        Item selected = selectedItem();
        if (selected != null) {
            openEditDialog(selected);
        }
    }

    private void openEditDialog(Item item) {
        Long priceBefore = item.getPriceCents();
        double quantityBefore = item.getQuantity();
        boolean wasTracking = item.isTrackPriceHistory();

        ItemFormDialog dialog = new ItemFormDialog(
                stage, inventory, shelfLife, item, null);

        dialog.showAndWait().ifPresent(edited -> {
            EditMode mode = decideEditMode(edited, priceBefore, quantityBefore, wasTracking);
            if (mode == null) {
                // The user cancelled at the change-kind prompt.
                return;
            }
            inventory.updateItem(edited, mode);
            refreshShelves();
            refreshItems();
            selectItemById(edited.getId());
            flash(switch (mode) {
                case NEW_PURCHASE -> "Saved " + edited.getName() + " and logged a new purchase";
                case USED -> "Saved " + edited.getName() + " and logged usage";
                case EXPIRED -> "Saved " + edited.getName() + " and logged waste";
                case CORRECTION -> "Saved " + edited.getName();
            });
        });
    }

    /**
     * Works out how a save should affect the history log.
     * <p>
     * Any change to the quantity or the price raises the question, since a
     * quantity going up can be a restock, a quantity going down can be stock
     * used or thrown out, and a price change can be a purchase or a correction.
     * The usage cases matter even for an item that is not price-tracked, so a
     * quantity change alone is enough to ask. When nothing relevant changed, the
     * save is a plain correction that adds no log entry. Returns null if the user
     * backs out at the prompt, so the caller abandons the save.
     */
    private EditMode decideEditMode(Item edited, Long priceBefore, double quantityBefore,
                                    boolean wasTracking) {
        boolean priceChanged = !java.util.Objects.equals(priceBefore, edited.getPriceCents());
        boolean quantityChanged = edited.getQuantity() != quantityBefore;

        if (!priceChanged && !quantityChanged) {
            return EditMode.CORRECTION;
        }

        ChangeKind.Direction direction;
        if (edited.getQuantity() > quantityBefore) {
            direction = ChangeKind.Direction.INCREASED;
        } else if (edited.getQuantity() < quantityBefore) {
            direction = ChangeKind.Direction.DECREASED;
        } else {
            direction = ChangeKind.Direction.UNCHANGED;
        }

        ChangeKind.Choice choice =
                Dialogs.askChangeKind(stage, edited.getName(), direction);
        return switch (choice) {
            case NEW_PURCHASE -> EditMode.NEW_PURCHASE;
            case CORRECTION -> EditMode.CORRECTION;
            case USED -> EditMode.USED;
            case EXPIRED -> EditMode.EXPIRED;
            case CANCEL -> null;
        };
    }

    private void deleteSelectedItems() {
        List<Item> selected = new ArrayList<>(itemTable.getSelectionModel().getSelectedItems());
        if (selected.isEmpty()) {
            return;
        }

        String what = selected.size() == 1
                ? "\"" + selected.get(0).getName() + "\""
                : selected.size() + " items";

        Dialogs.DeleteChoice choice = Dialogs.promptDelete(stage, "Delete " + what + "?",
                "Deleted items go to the Deleted Items bin, where you can restore them. "
                        + "Price and usage history is kept either way.",
                selected.size() == 1 ? "Delete item" : "Delete items");

        if (choice.confirmed()) {
            int deleted = inventory.deleteItems(selected, choice.reason());
            refreshShelves();
            refreshItems();
            flash("Deleted " + (deleted == 1 ? what : deleted + " items"));
        }
    }

    private void showDeletedItems() {
        DeletedItemsDialog dialog = new DeletedItemsDialog(stage, inventory);
        dialog.showAndWait();
        if (dialog.wasChanged()) {
            // A restore puts items back, so the shelves and list need refreshing.
            refreshShelves();
            refreshItems();
        }
    }

    private void markSelectedOpened() {
        Item selected = selectedItem();
        if (selected == null) {
            return;
        }
        if (selected.isOpened()) {
            Dialogs.showInfo(stage, "Already open",
                    selected.getName() + " was marked opened on "
                            + Dates.formatOrDash(selected.getOpenedDate()) + ".");
            return;
        }

        inventory.markOpened(selected, LocalDate.now());
        refreshItems();
        selectItemById(selected.getId());

        LocalDate newExpiry = ExpiryService.effectiveExpiry(selected);
        flash(newExpiry == null
                ? "Marked " + selected.getName() + " opened"
                : "Marked opened. Now expires " + Dates.formatOrDash(newExpiry));
    }

    // ==================== SHELF ACTIONS ====================

    private void createShelf() {
        Optional<String> name = Dialogs.prompt(stage, "New shelf",
                "What do you want to call it?", "", "Create shelf");

        name.ifPresent(value -> {
            Shelf created = inventory.createShelf(value);
            refreshShelves();
            shelfList.getItems().stream()
                    .filter(shelf -> shelf.getId() == created.getId())
                    .findFirst()
                    .ifPresent(shelf -> shelfList.getSelectionModel().select(shelf));
            flash("Created " + created.getName());
        });
    }

    private void renameSelectedShelf() {
        Shelf selected = shelfList.getSelectionModel().getSelectedItem();
        if (selected == null || selected.isDefaultShelf()) {
            return;
        }

        Dialogs.prompt(stage, "Rename shelf", "New name for this shelf",
                selected.getName(), "Rename").ifPresent(value -> {
            inventory.renameShelf(selected, value);
            refreshShelves();
            flash("Renamed to " + selected.getName());
        });
    }

    private void deleteSelectedShelf() {
        Shelf selected = shelfList.getSelectionModel().getSelectedItem();
        if (selected == null || selected.isDefaultShelf()) {
            return;
        }

        boolean confirmed = Dialogs.confirm(stage,
                "Delete the " + selected.getName() + " shelf?",
                "Its " + selected.getItemCount() + " item(s) will be kept and become unfiled, "
                        + "so you can still find them on the Master Shelf.",
                "Delete shelf");

        if (confirmed) {
            inventory.deleteShelf(selected);
            refreshShelves();
            selectMasterShelf();
            refreshItems();
            flash("Deleted the " + selected.getName() + " shelf");
        }
    }

    // ==================== ALERTS ====================

    /**
     * Shows what needs attention, with expired items and expiring items kept
     * visibly apart.
     * <p>
     * The old version put both in one flat list, which buried the distinction
     * that matters most: something already spoiled needs throwing out, while
     * something approaching its date needs using up. They get separate,
     * colour-coded sections and different wording so the difference reads at a
     * glance.
     */
    private void showAlerts() {
        LocalDate today = LocalDate.now();
        List<Item> all = inventory.listAllItems();

        List<Item> expired = all.stream()
                .filter(item -> ExpiryService.status(item, today) == ExpiryStatus.EXPIRED)
                .sorted(java.util.Comparator.comparing(
                        item -> ExpiryService.daysRemaining(item, today),
                        java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())))
                .toList();

        List<Item> soon = all.stream()
                .filter(item -> ExpiryService.status(item, today) == ExpiryStatus.EXPIRING_SOON)
                .sorted(java.util.Comparator.comparing(
                        item -> ExpiryService.daysRemaining(item, today),
                        java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())))
                .toList();

        if (expired.isEmpty() && soon.isEmpty()) {
            Dialogs.showInfo(stage, "Nothing needs attention",
                    "Nothing is expired or close to it. Each item warns you based on its own "
                            + "alert window, which you can set when you edit it.");
            return;
        }

        Dialog<Void> dialog = new Dialog<>();
        dialog.initOwner(stage);
        dialog.setTitle("Shelves");
        dialog.setHeaderText(alertsHeader(expired.size(), soon.size()));
        dialog.getDialogPane().getStylesheets().addAll(getScene().getStylesheets());
        dialog.getDialogPane().getStyleClass().add("shelves-dialog");
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        VBox body = new VBox(14);
        body.setPadding(new Insets(6, 4, 4, 4));

        if (!expired.isEmpty()) {
            body.getChildren().add(buildAlertSection(
                    "Already expired", "alert-group-expired", "alert-row-expired", expired, today));
        }
        if (!soon.isEmpty()) {
            body.getChildren().add(buildAlertSection(
                    "Expiring soon", "alert-group-soon", "alert-row-soon", soon, today));
        }

        ScrollPane scroller = new ScrollPane(body);
        scroller.setFitToWidth(true);
        scroller.getStyleClass().add("alert-scroll");
        dialog.getDialogPane().setContent(scroller);

        dialog.showAndWait();
    }

    private String alertsHeader(int expired, int soon) {
        if (expired > 0 && soon > 0) {
            return expired + " expired, " + soon + " expiring soon";
        }
        if (expired > 0) {
            return expired == 1 ? "1 item has expired" : expired + " items have expired";
        }
        return soon == 1 ? "1 item is expiring soon" : soon + " items are expiring soon";
    }

    private Node buildAlertSection(String title, String titleClass, String rowClass,
                                   List<Item> items, LocalDate today) {
        Label heading = new Label(title + "  (" + items.size() + ")");
        heading.getStyleClass().add(titleClass);

        VBox rows = new VBox(6);
        rows.setPadding(new Insets(4, 0, 0, 4));

        for (Item item : items) {
            Label when = new Label(ExpiryService.describe(item, today));
            when.getStyleClass().add(rowClass);
            when.setMinWidth(110);

            Label name = new Label(item.getName());
            name.getStyleClass().add(rowClass);

            Label shelf = new Label(shelfNameFor(item));
            shelf.getStyleClass().add("alert-row-shelf");

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);

            HBox row = new HBox(10, when, name, spacer, shelf);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2) {
                    guard(() -> openEditDialog(item));
                }
            });
            rows.getChildren().add(row);
        }

        return new VBox(6, heading, rows);
    }

    // ==================== EXPORT ====================

    /** Opens the export flow: choose a shelf, preview, then save as CSV or PDF. */
    private void openExport() {
        new ExportDialog(stage, inventory).showAndWait();
    }

    // ==================== HELPERS ====================

    /**
     * Runs an action, turning any expected failure into a readable dialog.
     * <p>
     * Every button goes through here, which is why no handler in this class
     * contains a try block of its own.
     */
    private void guard(Runnable action) {
        try {
            action.run();
        } catch (ShelvesException e) {
            Dialogs.showError(stage, e);
        } catch (RuntimeException e) {
            Dialogs.showUnexpectedError(stage, e);
        }
    }

    private Item selectedItem() {
        return itemTable.getSelectionModel().getSelectedItem();
    }

    private void selectItemById(int id) {
        visibleItems.stream()
                .filter(item -> item.getId() == id)
                .findFirst()
                .ifPresent(item -> {
                    itemTable.getSelectionModel().select(item);
                    itemTable.scrollTo(item);
                });
    }

    private String shelfNameFor(Item item) {
        if (item.getShelfId() == null) {
            return "Unfiled";
        }
        return shelfNames.getOrDefault(item.getShelfId(), "Unfiled");
    }

    /** Loads a thumbnail once and remembers it, so scrolling stays smooth. */
    private Image thumbnailFor(String path) {
        if (path == null) {
            return null;
        }
        if (thumbnailCache.containsKey(path)) {
            return thumbnailCache.get(path);
        }
        Image image = null;
        try {
            if (Files.exists(Path.of(path))) {
                image = new Image(Path.of(path).toUri().toString(), 56, 56, true, true, true);
            }
        } catch (Exception e) {
            // A missing or unreadable photo is not worth interrupting anyone
            // over; the row simply shows without a thumbnail.
            image = null;
        }
        thumbnailCache.put(path, image);
        return image;
    }

    /** Shows a short confirmation that fades away on its own. */
    private void flash(String message) {
        statusMessage.setText(message);
        Animations.flashThenFade(statusMessage, Duration.seconds(2.5));
    }
}
