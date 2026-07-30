package com.shelves.ui;

import com.shelves.model.Shelf;
import com.shelves.service.ExportService;
import com.shelves.service.InventoryService;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

/**
 * The export flow: choose a shelf, see exactly what will be exported, then save
 * it as CSV or PDF.
 * <p>
 * The preview is the point of this dialog. The old flow sent the current view
 * straight to a printer with no chance to check it and no way to pick a
 * different shelf. Here the shelf is chosen up front, the preview updates to
 * show the real rows, and only then does the user commit to a format and a
 * file. What they see in the table is what the file will contain.
 */
public class ExportDialog extends Dialog<Void> {

    private final InventoryService inventory;
    private final ExportService export;
    private final Window owner;

    private final ComboBox<Shelf> shelfChoice = new ComboBox<>();
    private final TableView<String[]> preview = new TableView<>();
    private final Label summary = new Label();
    private Button csvButton;
    private Button pdfButton;

    public ExportDialog(Window owner, InventoryService inventory) {
        this.owner = owner;
        this.inventory = inventory;
        this.export = new ExportService(inventory);

        initOwner(owner);
        setTitle("Shelves");
        setHeaderText("Export inventory");
        setResizable(true);

        getDialogPane().getStylesheets().addAll(
                owner != null && owner.getScene() != null
                        ? owner.getScene().getStylesheets()
                        : List.of());
        getDialogPane().getStyleClass().add("shelves-dialog");
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        buildContent();
        loadShelves();
    }

    // See the note in MainView.buildItemTable: constrained resize policy kept
    // for JavaFX-version portability, deprecation notice suppressed.
    @SuppressWarnings("deprecation")
    private void buildContent() {
        Label chooseLabel = new Label("Shelf to export");
        chooseLabel.getStyleClass().add("field-label");

        shelfChoice.setPrefWidth(240);
        shelfChoice.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(Shelf shelf) {
                if (shelf == null) {
                    return "";
                }
                return shelf.isDefaultShelf()
                        ? "All items (Master Shelf)"
                        : shelf.getName();
            }

            @Override
            public Shelf fromString(String string) {
                return null;
            }
        });
        shelfChoice.valueProperty().addListener((observable, was, now) -> refreshPreview());

        VBox chooseBlock = new VBox(4, chooseLabel, shelfChoice);

        // Preview table.
        buildPreviewColumns();
        preview.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        preview.setPrefHeight(300);
        preview.setPrefWidth(640);
        Label emptyPreview = new Label("This shelf has no items to export.");
        emptyPreview.getStyleClass().add("muted-text");
        preview.setPlaceholder(emptyPreview);

        summary.getStyleClass().add("preview-note");

        Label previewLabel = new Label("Preview \u2014 this is exactly what will be exported");
        previewLabel.getStyleClass().add("field-label");

        // Save buttons.
        csvButton = new Button("Save as CSV\u2026");
        csvButton.getStyleClass().add("primary");
        csvButton.setOnAction(event -> saveAs(Format.CSV));
        Tooltip.install(csvButton, new Tooltip(
                "A spreadsheet file. Best for sorting, filtering or totalling further."));

        pdfButton = new Button("Save as PDF\u2026");
        pdfButton.setOnAction(event -> saveAs(Format.PDF));
        Tooltip.install(pdfButton, new Tooltip(
                "A printable inventory sheet with a tick box on each row."));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox actions = new HBox(8, summary, spacer, pdfButton, csvButton);
        actions.setAlignment(Pos.CENTER_LEFT);

        VBox content = new VBox(12, chooseBlock, previewLabel, preview, actions);
        content.setPadding(new Insets(10, 6, 6, 6));
        VBox.setVgrow(preview, Priority.ALWAYS);

        getDialogPane().setContent(content);
        getDialogPane().setPrefWidth(700);
    }

    private void buildPreviewColumns() {
        String[] headers = ExportService.COLUMNS;
        for (int i = 0; i < headers.length; i++) {
            final int index = i;
            TableColumn<String[], String> column = new TableColumn<>(headers[i]);
            column.setCellValueFactory(data -> {
                String[] row = data.getValue();
                return new SimpleStringProperty(index < row.length ? row[index] : "");
            });
            preview.getColumns().add(column);
        }
    }

    private void loadShelves() {
        shelfChoice.getItems().setAll(inventory.listShelves());
        // Default to the Master Shelf, so the whole inventory is the starting
        // point and the user narrows down from there.
        shelfChoice.getItems().stream()
                .filter(Shelf::isDefaultShelf)
                .findFirst()
                .ifPresent(shelfChoice::setValue);
    }

    private void refreshPreview() {
        Shelf shelf = shelfChoice.getValue();
        if (shelf == null) {
            preview.getItems().clear();
            summary.setText("");
            return;
        }
        List<String[]> rows = export.buildRows(shelf);
        preview.getItems().setAll(rows);
        summary.setText(rows.size() + (rows.size() == 1 ? " item" : " items") + " to export");

        boolean hasRows = !rows.isEmpty();
        csvButton.setDisable(!hasRows);
        pdfButton.setDisable(!hasRows);
    }

    private enum Format { CSV, PDF }

    private void saveAs(Format format) {
        Shelf shelf = shelfChoice.getValue();
        if (shelf == null) {
            return;
        }

        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save export");
        String base = suggestedFileName(shelf);

        if (format == Format.CSV) {
            chooser.setInitialFileName(base + ".csv");
            chooser.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter("CSV spreadsheet", "*.csv"));
        } else {
            chooser.setInitialFileName(base + ".pdf");
            chooser.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter("PDF document", "*.pdf"));
        }

        File chosen = chooser.showSaveDialog(owner);
        if (chosen == null) {
            return;
        }

        Path path = chosen.toPath();
        // guard is not available here, so the dialog reports failures itself;
        // a failed export must not take the window down with it.
        try {
            if (format == Format.CSV) {
                export.exportCsv(shelf, path);
            } else {
                export.exportPdf(shelf, path);
            }
            Dialogs.showInfo(owner, "Exported",
                    "Saved " + preview.getItems().size()
                            + " items to\n" + path.toAbsolutePath() + ".");
            close();
        } catch (com.shelves.exception.ShelvesException e) {
            Dialogs.showError(owner, e);
        } catch (RuntimeException e) {
            Dialogs.showUnexpectedError(owner, e);
        }
    }

    private String suggestedFileName(Shelf shelf) {
        String name = shelf.isDefaultShelf() ? "All items" : shelf.getName();
        String safe = name.replaceAll("[^A-Za-z0-9 _-]", "").trim().replace(' ', '_');
        return "Shelves_" + (safe.isEmpty() ? "export" : safe) + "_" + LocalDate.now();
    }
}
