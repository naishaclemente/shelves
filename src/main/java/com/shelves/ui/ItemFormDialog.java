package com.shelves.ui;

import com.shelves.exception.ValidationException;
import com.shelves.model.Item;
import com.shelves.model.Shelf;
import com.shelves.model.ShelfLifeEntry;
import com.shelves.service.InventoryService;
import com.shelves.service.ShelfLifeService;
import com.shelves.util.Animations;
import com.shelves.util.Money;
import com.shelves.util.Validator;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * The form for adding and editing an item.
 * <p>
 * Everything the user types is read back through {@link Validator}, so the
 * dialog cannot be dismissed with unusable data and the same rules apply here
 * as anywhere else. Errors are shown in place rather than as a popup, and the
 * dialog stays open with the fields as the user left them.
 */
public class ItemFormDialog extends Dialog<Item> {

    private final InventoryService inventory;
    private final ShelfLifeService shelfLife;
    private final Item working;
    private final boolean creating;

    private final TextField nameField = new TextField();
    private final TextField quantityField = new TextField();
    private final ComboBox<String> unitField = new ComboBox<>();
    private final ComboBox<Shelf> shelfField = new ComboBox<>();
    private final TextField tagsField = new TextField();
    private final TextField priceField = new TextField();
    private final CheckBox trackPriceBox = new CheckBox("Track this price over time");
    private final DatePicker purchasePicker = new DatePicker();
    private final DatePicker expiryPicker = new DatePicker();
    private final CheckBox openedBox = new CheckBox("This has been opened");
    private final DatePicker openedPicker = new DatePicker();
    private final TextField unopenedDaysField = new TextField();
    private final TextField openedDaysField = new TextField();
    private final TextField alertDaysField = new TextField();
    private final TextArea notesArea = new TextArea();

    private final Label errorLabel = new Label();
    private final Label shelfLifeHint = new Label();
    private final ImageView photoView = new ImageView();
    private final StackPane photoFrame = new StackPane();
    private String photoPath;

    /**
     * @param existing the item to edit, or {@code null} to create a new one
     * @param preselectedShelf the shelf to file a new item on by default
     */
    public ItemFormDialog(Window owner,
                          InventoryService inventory,
                          ShelfLifeService shelfLife,
                          Item existing,
                          Shelf preselectedShelf) {

        this.inventory = inventory;
        this.shelfLife = shelfLife;
        this.creating = existing == null;
        // Work on a copy, so cancelling leaves the original untouched.
        this.working = creating ? new Item() : existing.copy();

        initOwner(owner);
        setTitle("Shelves");
        setHeaderText(creating ? "Add an item" : "Edit " + working.getName());
        setResizable(true);

        getDialogPane().getStylesheets().addAll(
                owner != null && owner.getScene() != null
                        ? owner.getScene().getStylesheets()
                        : List.of());
        getDialogPane().getStyleClass().add("shelves-dialog");

        ButtonType saveButton = new ButtonType(
                creating ? "Add item" : "Save changes", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().setAll(ButtonType.CANCEL, saveButton);

        buildForm(preselectedShelf);
        populateFrom(working);
        wireBehaviour();

        // Intercept the save button so a validation failure keeps the dialog
        // open rather than closing and losing what the user typed.
        Button save = (Button) getDialogPane().lookupButton(saveButton);
        save.getStyleClass().add("primary");
        save.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            if (!readInto(working)) {
                event.consume();
            }
        });

        setResultConverter(button -> button == saveButton ? working : null);
        Platform.runLater(nameField::requestFocus);
    }

    // ==================== LAYOUT ====================

    /**
     * Builds the form as a single column of stacked field blocks, each one a
     * label sitting directly above its control.
     * <p>
     * The earlier version put labels in their own grid column, which is what
     * caused the resize bug: as the dialog narrowed, the label column was
     * squeezed until "Quantity" became an unreadable "...". Stacking each label
     * on top of its control means nothing competes for horizontal space, so the
     * form stays legible at any width and simply grows taller as it narrows.
     * The photo panel wraps below the fields on a narrow dialog instead of
     * being pinned to the right.
     */
    private void buildForm(Shelf preselectedShelf) {
        nameField.setPromptText("What is it?");
        nameField.setMaxWidth(Double.MAX_VALUE);

        quantityField.setPromptText("1");
        quantityField.setPrefWidth(90);

        unitField.setEditable(true);
        unitField.getItems().setAll("count", "lb", "oz", "kg", "g", "gallon", "quart",
                "liter", "ml", "box", "bag", "can", "jar", "bottle", "case", "pack");
        unitField.setPrefWidth(130);
        unitField.setPromptText("unit");

        HBox quantityRow = new HBox(8, quantityField, unitField);
        quantityRow.setAlignment(Pos.CENTER_LEFT);

        shelfField.setMaxWidth(Double.MAX_VALUE);
        shelfField.getItems().setAll(inventory.listShelves());
        // The Master Shelf means "everything", so it is not a filing target.
        shelfField.getItems().removeIf(Shelf::isDefaultShelf);
        shelfField.setPromptText("Not on a shelf");

        if (preselectedShelf != null && !preselectedShelf.isDefaultShelf()) {
            shelfField.getItems().stream()
                    .filter(s -> s.getId() == preselectedShelf.getId())
                    .findFirst()
                    .ifPresent(shelfField::setValue);
        }

        Button clearShelf = new Button("Clear");
        clearShelf.getStyleClass().add("ghost");
        clearShelf.setOnAction(event -> shelfField.setValue(null));
        HBox shelfRow = new HBox(8, shelfField, clearShelf);
        shelfRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(shelfField, Priority.ALWAYS);

        tagsField.setPromptText("Dairy, Fridge");
        tagsField.setMaxWidth(Double.MAX_VALUE);
        Tooltip.install(tagsField, new Tooltip("Separate tags with commas."));

        priceField.setPromptText("0.00");
        priceField.setPrefWidth(120);
        HBox priceRow = new HBox(12, priceField, trackPriceBox);
        priceRow.setAlignment(Pos.CENTER_LEFT);

        purchasePicker.setMaxWidth(Double.MAX_VALUE);
        expiryPicker.setMaxWidth(Double.MAX_VALUE);
        openedPicker.setPrefWidth(170);
        openedPicker.setDisable(true);

        HBox openedRow = new HBox(12, openedBox, openedPicker);
        openedRow.setAlignment(Pos.CENTER_LEFT);

        unopenedDaysField.setPromptText("days");
        unopenedDaysField.setPrefWidth(80);
        openedDaysField.setPromptText("days");
        openedDaysField.setPrefWidth(80);

        Button suggestButton = new Button("Suggest");
        suggestButton.setOnAction(event -> applyShelfLifeSuggestion());
        Tooltip.install(suggestButton, new Tooltip(
                "Fill these in from the built-in shelf life reference."));

        Label unopenedLabel = new Label("Unopened");
        Label openedLabel = new Label("Opened");
        unopenedLabel.getStyleClass().add("field-label");
        openedLabel.getStyleClass().add("field-label");

        HBox shelfLifeRow = new HBox(8,
                unopenedLabel, unopenedDaysField,
                openedLabel, openedDaysField,
                suggestButton);
        shelfLifeRow.setAlignment(Pos.CENTER_LEFT);

        shelfLifeHint.getStyleClass().add("hint-text");
        shelfLifeHint.setWrapText(true);

        alertDaysField.setPrefWidth(80);
        HBox alertRow = new HBox(8, alertDaysField, hint("days before expiry"));
        alertRow.setAlignment(Pos.CENTER_LEFT);

        notesArea.setPromptText("Anything worth remembering about this item");
        notesArea.setPrefRowCount(3);
        notesArea.setWrapText(true);
        notesArea.setMaxWidth(Double.MAX_VALUE);

        // Tighter than before: the label-above-field layout fixed truncation but
        // left the form feeling spread out, so the gaps between blocks are pulled
        // in. The barcode field is gone from the form entirely; barcode support
        // stays in the codebase but is not shown here.
        VBox fields = new VBox(1);
        fields.getChildren().addAll(
                sectionLabel("ITEM"),
                fieldBlock("Name", nameField),
                fieldBlock("Quantity", quantityRow),
                fieldBlock("Shelf", shelfRow),
                fieldBlock("Tags", tagsField),

                sectionLabel("COST"),
                fieldBlock("Price", priceRow),
                fieldBlock("Purchased", purchasePicker),

                sectionLabel("SHELF LIFE"),
                fieldBlock("Expires", expiryPicker),
                fieldBlock("Opened", openedRow),
                fieldBlock("Keeps for", shelfLifeRow),
                indented(shelfLifeHint),
                fieldBlock("Warn me", alertRow),

                sectionLabel("NOTES"),
                fieldBlock("Notes", notesArea));

        errorLabel.getStyleClass().add("form-error");
        errorLabel.setWrapText(true);
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
        errorLabel.setMaxWidth(Double.MAX_VALUE);

        VBox fieldColumn = new VBox(6, fields, errorLabel);
        fieldColumn.setMinWidth(280);
        fieldColumn.setPrefWidth(380);
        HBox.setHgrow(fieldColumn, Priority.ALWAYS);

        HBox detailsContent = new HBox(18, fieldColumn, buildPhotoPanel());
        detailsContent.setPadding(new Insets(10, 6, 6, 6));

        ScrollPane detailsScroller = new ScrollPane(detailsContent);
        detailsScroller.setFitToWidth(true);
        detailsScroller.setPrefViewportHeight(540);
        detailsScroller.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

        if (creating) {
            // A new item has no history yet, so it is a plain form.
            getDialogPane().setContent(detailsScroller);
        } else {
            // An existing item gets a tab for its history alongside the details,
            // which is where the old standalone Price History button now lives.
            TabPane tabs = new TabPane();
            tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

            Tab detailsTab = new Tab("Details", detailsScroller);
            Tab historyTab = new Tab("Item History",
                    new ItemHistoryPanel(inventory, working));
            tabs.getTabs().addAll(detailsTab, historyTab);

            getDialogPane().setContent(tabs);
        }

        getDialogPane().setPrefWidth(640);
        // A hard floor so the dialog can never be dragged narrow enough to
        // clip its own controls.
        getDialogPane().setMinWidth(360);
    }

    /** A label stacked directly above its control, growing to fill the width. */
    private VBox fieldBlock(String labelText, javafx.scene.Node control) {
        Label label = new Label(labelText);
        label.getStyleClass().add("field-label");
        VBox block = new VBox(2, label, control);
        block.setPadding(new Insets(2, 0, 2, 0));
        if (control instanceof Region region) {
            region.setMaxWidth(Double.MAX_VALUE);
        }
        return block;
    }

    /** Slightly inset supporting content that belongs to the field above it. */
    private VBox indented(javafx.scene.Node node) {
        VBox box = new VBox(node);
        box.setPadding(new Insets(0, 0, 3, 2));
        return box;
    }

    private VBox buildPhotoPanel() {
        photoView.setFitWidth(128);
        photoView.setFitHeight(128);
        photoView.setPreserveRatio(true);
        photoView.setSmooth(true);

        Label empty = new Label("No photo");
        empty.getStyleClass().add("hint-text");

        photoFrame.getStyleClass().add("photo-frame");
        photoFrame.getChildren().setAll(empty);

        Button choose = new Button("Choose photo\u2026");
        choose.setMaxWidth(Double.MAX_VALUE);
        choose.setOnAction(event -> choosePhoto());

        Button remove = new Button("Remove");
        remove.getStyleClass().add("ghost");
        remove.setMaxWidth(Double.MAX_VALUE);
        remove.setOnAction(event -> {
            photoPath = null;
            showPhoto(null);
        });

        VBox panel = new VBox(8, sectionLabel("PHOTO"), photoFrame, choose, remove);
        panel.setAlignment(Pos.TOP_CENTER);
        panel.setMinWidth(150);
        return panel;
    }

    private Label sectionLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("form-section");
        return label;
    }

    private Label hint(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("hint-text");
        return label;
    }

    // ==================== BEHAVIOUR ====================

    private void wireBehaviour() {
        // The opened date only means something once something is opened.
        openedBox.selectedProperty().addListener((observable, was, isOpened) -> {
            openedPicker.setDisable(!isOpened);
            if (isOpened && openedPicker.getValue() == null) {
                openedPicker.setValue(LocalDate.now());
            } else if (!isOpened) {
                openedPicker.setValue(null);
            }
        });

        // Offer a shelf life suggestion once the user leaves the name field.
        nameField.focusedProperty().addListener((observable, had, hasFocus) -> {
            if (!hasFocus) {
                showShelfLifeHint();
            }
        });
    }

    private void populateFrom(Item item) {
        nameField.setText(nullToEmpty(item.getName()));
        quantityField.setText(creating ? "1" : trimNumber(item.getQuantity()));
        unitField.setValue(nullToEmpty(item.getUnit()));
        tagsField.setText(item.getTagsDisplay());
        priceField.setText(Money.toEditableString(item.getPriceCents()));
        trackPriceBox.setSelected(creating || item.isTrackPriceHistory());
        purchasePicker.setValue(creating ? LocalDate.now() : item.getPurchaseDate());
        expiryPicker.setValue(item.getExpirationDate());
        openedBox.setSelected(item.isOpened());
        openedPicker.setValue(item.getOpenedDate());
        openedPicker.setDisable(!item.isOpened());
        unopenedDaysField.setText(item.getShelfLifeUnopenedDays() == null
                ? "" : String.valueOf(item.getShelfLifeUnopenedDays()));
        openedDaysField.setText(item.getShelfLifeOpenedDays() == null
                ? "" : String.valueOf(item.getShelfLifeOpenedDays()));
        alertDaysField.setText(String.valueOf(item.getAlertWindowDays()));
        notesArea.setText(nullToEmpty(item.getNotes()));

        if (item.getShelfId() != null) {
            shelfField.getItems().stream()
                    .filter(shelf -> shelf.getId() == item.getShelfId())
                    .findFirst()
                    .ifPresent(shelfField::setValue);
        }

        photoPath = item.getPhotoPath();
        showPhoto(photoPath);
    }

    /**
     * Reads every field into the item, validating as it goes.
     *
     * @return true if everything was usable and the item is now populated
     */
    private boolean readInto(Item item) {
        try {
            hideError();

            item.setName(nameField.getText() == null ? null : nameField.getText().trim());
            // Barcode is no longer edited in the form, so whatever the item
            // already carried is left untouched.
            item.setQuantity(Validator.parseQuantity(quantityField.getText()));
            item.setUnit(emptyToNull(unitField.getValue()));
            item.setShelfId(shelfField.getValue() == null ? null : shelfField.getValue().getId());
            item.setPriceCents(Validator.parsePrice(priceField.getText()));
            item.setTrackPriceHistory(trackPriceBox.isSelected());
            item.setPurchaseDate(purchasePicker.getValue());
            item.setExpirationDate(expiryPicker.getValue());
            item.setOpenedDate(openedBox.isSelected() ? openedPicker.getValue() : null);
            item.setShelfLifeUnopenedDays(
                    Validator.parseDays(unopenedDaysField.getText(), "Unopened shelf life"));
            item.setShelfLifeOpenedDays(
                    Validator.parseDays(openedDaysField.getText(), "Opened shelf life"));

            Integer alertDays = Validator.parseDays(alertDaysField.getText(), "Alert window");
            item.setAlertWindowDays(alertDays == null ? Item.DEFAULT_ALERT_WINDOW_DAYS : alertDays);

            item.setNotes(emptyToNull(notesArea.getText()));
            item.setPhotoPath(photoPath);
            item.setTags(parseTags(tagsField.getText()));

            // The same rules that guard the database guard the form.
            Validator.validateItem(item);
            return true;

        } catch (ValidationException e) {
            showError(e);
            return false;
        }
    }

    private List<String> parseTags(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(text.split(","))
                .map(String::trim)
                .filter(tag -> !tag.isEmpty())
                .map(Validator::validateTagName)
                .toList();
    }

    private void showError(ValidationException error) {
        StringBuilder message = new StringBuilder();
        for (String problem : error.getErrors()) {
            message.append(message.length() == 0 ? "" : "\n").append("\u2022  ").append(problem);
        }
        errorLabel.setText(message.toString());
        errorLabel.setVisible(true);
        errorLabel.setManaged(true);
        Animations.shake(getDialogPane());
    }

    private void hideError() {
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
    }

    // ==================== SHELF LIFE ====================

    private void showShelfLifeHint() {
        String name = nameField.getText();
        if (name == null || name.isBlank()) {
            shelfLifeHint.setText("");
            return;
        }
        Optional<ShelfLifeEntry> match = shelfLife.lookup(name);
        if (match.isEmpty()) {
            shelfLifeHint.setText("");
            return;
        }
        ShelfLifeEntry entry = match.get();
        shelfLifeHint.setText("Reference has \"" + entry.getProductName() + "\": "
                + describeDays(entry.getUnopenedDays()) + " unopened, "
                + describeDays(entry.getOpenedDays()) + " once opened. Press Suggest to use it.");
    }

    private void applyShelfLifeSuggestion() {
        Item preview = new Item();
        preview.setName(nameField.getText());
        preview.setPurchaseDate(purchasePicker.getValue());
        preview.setShelfLifeUnopenedDays(
                parseDaysQuietly(unopenedDaysField.getText()));
        preview.setShelfLifeOpenedDays(parseDaysQuietly(openedDaysField.getText()));
        preview.setExpirationDate(expiryPicker.getValue());

        if (!shelfLife.applySuggestion(preview)) {
            shelfLifeHint.setText(
                    "No reference figure for that. Fill the days in yourself if you know them.");
            return;
        }

        if (preview.getShelfLifeUnopenedDays() != null) {
            unopenedDaysField.setText(String.valueOf(preview.getShelfLifeUnopenedDays()));
        }
        if (preview.getShelfLifeOpenedDays() != null) {
            openedDaysField.setText(String.valueOf(preview.getShelfLifeOpenedDays()));
        }
        if (expiryPicker.getValue() == null && preview.getExpirationDate() != null) {
            expiryPicker.setValue(preview.getExpirationDate());
        }
        shelfLifeHint.setText("Filled in from the reference. Adjust if the packaging differs.");
        Animations.pulse(shelfLifeHint);
    }

    private String describeDays(Integer days) {
        if (days == null) {
            return "no figure";
        }
        if (days >= 365) {
            long years = Math.round(days / 365.0);
            return years + (years == 1 ? " year" : " years");
        }
        if (days >= 30) {
            long months = Math.round(days / 30.0);
            return months + (months == 1 ? " month" : " months");
        }
        return days + (days == 1 ? " day" : " days");
    }

    private Integer parseDaysQuietly(String text) {
        try {
            return Validator.parseDays(text, "days");
        } catch (ValidationException e) {
            return null;
        }
    }

    // ==================== PHOTO ====================

    /**
     * Copies the chosen image into the app's own photo folder.
     * <p>
     * Storing a path to wherever the user picked the file from would break the
     * moment they tidied their downloads folder. Copying it means the photo
     * belongs to Shelves. The file itself stays on disk rather than going into
     * the database, which keeps the database small and queries quick.
     */
    private void choosePhoto() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Choose a photo");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                "Images", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.bmp"));

        java.io.File chosen = chooser.showOpenDialog(getOwner());
        if (chosen == null) {
            return;
        }

        try {
            Path photos = inventory.getDatabase().getPhotoDirectory();
            Files.createDirectories(photos);

            String extension = extensionOf(chosen.getName());
            Path destination = photos.resolve(UUID.randomUUID() + extension);
            Files.copy(chosen.toPath(), destination, StandardCopyOption.REPLACE_EXISTING);

            photoPath = destination.toAbsolutePath().toString();
            showPhoto(photoPath);

        } catch (IOException e) {
            Dialogs.showInfo(getOwner(), "Could not use that photo",
                    "Shelves could not copy that file into its photo folder. "
                            + "Check the file still exists and try again.");
        }
    }

    private void showPhoto(String path) {
        if (path == null || path.isBlank() || !Files.exists(Path.of(path))) {
            Label empty = new Label("No photo");
            empty.getStyleClass().add("hint-text");
            photoFrame.getChildren().setAll(empty);
            return;
        }
        try {
            Image image = new Image(Path.of(path).toUri().toString(),
                    256, 256, true, true, true);
            photoView.setImage(image);
            photoFrame.getChildren().setAll(photoView);
            Animations.fadeIn(photoView);
        } catch (Exception e) {
            Label broken = new Label("Photo missing");
            broken.getStyleClass().add("hint-text");
            photoFrame.getChildren().setAll(broken);
        }
    }

    private String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return ".png";
        }
        return fileName.substring(dot).toLowerCase(Locale.ROOT);
    }

    // ==================== SMALL HELPERS ====================

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String trimNumber(double value) {
        return value == Math.rint(value)
                ? String.valueOf((long) value)
                : String.valueOf(value);
    }

    /** Stops the dialog stretching oddly on very small screens. */
    static void constrain(Region region) {
        region.setMinHeight(Region.USE_PREF_SIZE);
    }
}
