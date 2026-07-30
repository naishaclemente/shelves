package com.shelves.ui;

import com.shelves.model.DeletedItem;
import com.shelves.service.InventoryService;
import com.shelves.util.Dates;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.time.LocalDate;
import java.util.List;

/**
 * The recycle bin: every deleted item, with a Restore button on each row.
 * <p>
 * Deleting an item archives a full snapshot of it, so this screen can do more
 * than list what is gone — it can bring an item back as a real, usable item on
 * its old shelf. Restore and permanent removal both run through the service, so
 * this dialog stays presentation only.
 */
public class DeletedItemsDialog extends Dialog<Void> {

    private final InventoryService inventory;
    private final Window owner;
    private final TableView<DeletedItem> table = new TableView<>();
    private final Label summary = new Label();

    /** Set true if anything was restored, so the caller can refresh. */
    private boolean changed = false;

    public DeletedItemsDialog(Window owner, InventoryService inventory) {
        this.owner = owner;
        this.inventory = inventory;

        initOwner(owner);
        setTitle("Shelves");
        setHeaderText("Deleted Items");
        setResizable(true);

        getDialogPane().getStylesheets().addAll(
                owner != null && owner.getScene() != null
                        ? owner.getScene().getStylesheets()
                        : List.of());
        getDialogPane().getStyleClass().add("shelves-dialog");
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        getDialogPane().setContent(buildContent());
        getDialogPane().setPrefWidth(860);
        reload();
    }

    /** Whether a restore happened, so the main view knows to refresh. */
    public boolean wasChanged() {
        return changed;
    }

    // See the note in MainView.buildItemTable: the constrained resize policy is
    // deprecated in JavaFX 20+ but its replacement is absent from earlier
    // versions, so the working original is kept and the notice suppressed.
    @SuppressWarnings("deprecation")
    private Region buildContent() {
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.setPrefHeight(360);
        Label empty = new Label("Nothing has been deleted. Items you delete land here, "
                + "so you can restore them if you change your mind.");
        empty.getStyleClass().add("muted-text");
        empty.setWrapText(true);
        table.setPlaceholder(empty);

        table.getColumns().add(textColumn("Name", 140, DeletedItem::getName));
        table.getColumns().add(textColumn("Last shelf", 110, DeletedItem::shelfDisplay));
        table.getColumns().add(textColumn("Quantity", 80, DeletedItem::getQuantityDisplay));
        table.getColumns().add(textColumn("Last price", 80, DeletedItem::priceDisplay));
        table.getColumns().add(textColumn("Last bought", 100, DeletedItem::lastBoughtDisplay));
        table.getColumns().add(textColumn("Reason", 110, DeletedItem::getReasonDisplay));
        table.getColumns().add(textColumn("Deleted", 100, this::deletedDateDisplay));
        table.getColumns().add(actionColumn());

        summary.getStyleClass().add("preview-note");

        Button emptyBin = new Button("Empty bin\u2026");
        emptyBin.getStyleClass().add("danger");
        emptyBin.setOnAction(event -> emptyBin());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox footer = new HBox(8, summary, spacer, emptyBin);
        footer.setAlignment(Pos.CENTER_LEFT);

        VBox content = new VBox(10, table, footer);
        content.setPadding(new Insets(10, 6, 6, 6));
        VBox.setVgrow(table, Priority.ALWAYS);
        return content;
    }

    private TableColumn<DeletedItem, String> textColumn(
            String heading, double width, java.util.function.Function<DeletedItem, String> reader) {
        TableColumn<DeletedItem, String> column = new TableColumn<>(heading);
        column.setPrefWidth(width);
        column.setCellValueFactory(data ->
                new SimpleStringProperty(reader.apply(data.getValue())));
        return column;
    }

    /** The column holding the per-row Restore and Delete buttons. */
    private TableColumn<DeletedItem, DeletedItem> actionColumn() {
        TableColumn<DeletedItem, DeletedItem> column = new TableColumn<>("");
        column.setPrefWidth(150);
        column.setMinWidth(150);
        column.setSortable(false);
        column.setCellValueFactory(data -> new SimpleObjectProperty<>(data.getValue()));
        column.setCellFactory(col -> new TableCell<>() {
            private final Button restore = new Button("Restore");
            private final Button remove = new Button("Delete");
            private final HBox buttons = new HBox(6, restore, remove);

            {
                restore.getStyleClass().add("primary");
                Tooltip.install(restore, new Tooltip(
                        "Bring this item back to its shelf as a usable item."));
                remove.getStyleClass().add("danger");
                Tooltip.install(remove, new Tooltip(
                        "Permanently remove just this entry. This cannot be undone."));
                buttons.setAlignment(Pos.CENTER_LEFT);
            }

            @Override
            protected void updateItem(DeletedItem item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    return;
                }
                restore.setOnAction(event -> restore(item));
                remove.setOnAction(event -> purge(item));
                setGraphic(buttons);
            }
        });
        return column;
    }

    private String deletedDateDisplay(DeletedItem item) {
        String raw = item.getDeletedDate();
        if (raw == null || raw.isBlank()) {
            return "\u2014";
        }
        // Stored as "yyyy-MM-dd HH:mm:ss"; the date part is enough here.
        LocalDate date = Dates.fromStorage(raw.length() >= 10 ? raw.substring(0, 10) : raw);
        return date == null ? raw : Dates.DISPLAY.format(date);
    }

    // ==================== ACTIONS ====================

    private void restore(DeletedItem item) {
        try {
            var restored = inventory.restoreDeletedItem(item);
            changed = true;
            reload();
            Dialogs.showInfo(owner, "Restored",
                    restored.getName() + " is back"
                            + (restored.getShelfId() == null
                                    ? " (unfiled, since its shelf no longer exists)."
                                    : ".") );
        } catch (com.shelves.exception.ShelvesException e) {
            Dialogs.showError(owner, e);
        }
    }

    private void purge(DeletedItem item) {
        boolean confirmed = Dialogs.confirm(owner, "Delete permanently?",
                "\"" + item.getName() + "\" will be removed from the bin for good. "
                        + "This cannot be undone, and it cannot be restored afterwards.",
                "Delete permanently");
        if (confirmed) {
            inventory.purgeDeletedItem(item);
            // Purging is a change to the bin, but it does not restore anything to
            // the shelves, so the main list does not need refreshing on its
            // account; leave `changed` untouched.
            reload();
        }
    }

    private void emptyBin() {
        int count = table.getItems().size();
        if (count == 0) {
            return;
        }
        boolean confirmed = Dialogs.confirm(owner, "Empty the bin?",
                "This will permanently remove all " + count
                        + " archived item(s). This cannot be undone.",
                "Empty bin");
        if (confirmed) {
            inventory.emptyDeletedItems();
            changed = true;
            reload();
        }
    }

    private void reload() {
        List<DeletedItem> items = inventory.listDeletedItems();
        table.getItems().setAll(items);
        summary.setText(items.isEmpty()
                ? ""
                : items.size() + (items.size() == 1 ? " deleted item" : " deleted items"));
    }
}
