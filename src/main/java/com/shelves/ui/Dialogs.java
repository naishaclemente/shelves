package com.shelves.ui;

import com.shelves.exception.DataAccessException;
import com.shelves.exception.ValidationException;
import com.shelves.service.ChangeKind;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Optional;

/**
 * The one place exceptions become something a person can read.
 * <p>
 * Nothing below this class ever shows a message, and nothing above it ever sees
 * a stack trace. A validation problem is the user's to fix and is shown plainly;
 * a database problem is not their fault, so it says what failed and keeps the
 * technical detail tucked away for whoever has to debug it.
 */
public final class Dialogs {

    private Dialogs() {
    }

    /**
     * Reports any expected failure.
     * <p>
     * Validation problems and database problems need different wording, so this
     * decides which the caller has, rather than making every call site do it.
     */
    public static void showError(Window owner, Throwable error) {
        if (error instanceof ValidationException validation) {
            showValidationErrors(owner, validation);
        } else if (error instanceof DataAccessException) {
            showDatabaseError(owner, (DataAccessException) error);
        } else {
            showUnexpectedError(owner, error);
        }
    }

    /** Lists everything wrong with a form, in the user's own terms. */
    public static void showValidationErrors(Window owner, ValidationException error) {
        Alert alert = build(owner, Alert.AlertType.WARNING, "Check these fields");

        if (error.getErrors().size() == 1) {
            alert.setContentText(error.getErrors().get(0));
        } else {
            alert.setContentText("There are a few things to fix:");
            StringBuilder list = new StringBuilder();
            for (String problem : error.getErrors()) {
                list.append("\u2022  ").append(problem).append('\n');
            }
            Label detail = new Label(list.toString().stripTrailing());
            detail.setWrapText(true);
            alert.getDialogPane().setExpandableContent(detail);
            alert.getDialogPane().setExpanded(true);
        }
        alert.showAndWait();
    }

    /** Reports a failed database operation without exposing SQL to the user. */
    public static void showDatabaseError(Window owner, DataAccessException error) {
        Alert alert = build(owner, Alert.AlertType.ERROR, "Something went wrong");
        alert.setContentText(error.getMessage()
                + "\n\nYour other data is unaffected. If this keeps happening, "
                + "close Shelves and open it again.");
        attachTechnicalDetail(alert, error);
        alert.showAndWait();
    }

    /** The catch-all, for bugs rather than expected failures. */
    public static void showUnexpectedError(Window owner, Throwable error) {
        Alert alert = build(owner, Alert.AlertType.ERROR, "Unexpected problem");
        alert.setContentText("Shelves hit a problem it did not expect: "
                + describe(error)
                + "\n\nNothing has been saved. Please try that again.");
        attachTechnicalDetail(alert, error);
        alert.showAndWait();
    }

    /** A plain informational message. */
    public static void showInfo(Window owner, String header, String message) {
        Alert alert = build(owner, Alert.AlertType.INFORMATION, header);
        alert.setContentText(message);
        alert.showAndWait();
    }

    /**
     * Asks the user to confirm something destructive.
     *
     * @param confirmLabel the wording on the confirming button, which should
     *                     name the action rather than say "OK"
     * @return true if the user confirmed
     */
    public static boolean confirm(Window owner, String header, String message,
                                  String confirmLabel) {
        Alert alert = build(owner, Alert.AlertType.CONFIRMATION, header);
        alert.setContentText(message);

        ButtonType confirmButton = new ButtonType(confirmLabel, ButtonType.OK.getButtonData());
        alert.getButtonTypes().setAll(ButtonType.CANCEL, confirmButton);

        return alert.showAndWait().filter(choice -> choice == confirmButton).isPresent();
    }

    /**
     * Asks what a change to an item means and returns the user's choice. The
     * classifications offered follow {@link com.shelves.service.ChangeKind}: the
     * dialog is only a view onto that rule.
     */
    public static ChangeKind.Choice askChangeKind(Window owner, String itemName,
                                                   ChangeKind.Direction direction) {
        Alert alert = build(owner, Alert.AlertType.CONFIRMATION, "What kind of change is this?");
        alert.getDialogPane().getStyleClass().add("purchase-correction");

        ButtonType purchase = new ButtonType("New purchase", ButtonBar.ButtonData.OTHER);
        ButtonType correction = new ButtonType("Correction", ButtonBar.ButtonData.OTHER);
        ButtonType used = new ButtonType("Used", ButtonBar.ButtonData.OTHER);
        ButtonType expired = new ButtonType("Expired", ButtonBar.ButtonData.OTHER);

        java.util.Set<ChangeKind.Choice> options = ChangeKind.optionsFor(direction);

        // Prompt wording matched to the options actually offered.
        alert.setContentText(switch (direction) {
            case DECREASED -> "Did you use some, did it expire, or are you fixing a mistake?";
            case INCREASED -> "Did you buy more, or are you fixing a mistake?";
            case UNCHANGED -> "Did you buy more, fix a mistake, use some, or did it expire?";
        });

        // Build the button row from the rule, in a consistent order, so the
        // dialog can never show an option the rule excludes.
        java.util.List<ButtonType> buttons = new java.util.ArrayList<>();
        buttons.add(ButtonType.CANCEL);
        if (options.contains(ChangeKind.Choice.EXPIRED)) {
            buttons.add(expired);
        }
        if (options.contains(ChangeKind.Choice.USED)) {
            buttons.add(used);
        }
        if (options.contains(ChangeKind.Choice.CORRECTION)) {
            buttons.add(correction);
        }
        if (options.contains(ChangeKind.Choice.NEW_PURCHASE)) {
            buttons.add(purchase);
        }
        alert.getButtonTypes().setAll(buttons);

        Optional<ButtonType> choice = alert.showAndWait();
        if (choice.isEmpty() || choice.get() == ButtonType.CANCEL) {
            return ChangeKind.Choice.CANCEL;
        }
        if (choice.get() == purchase) {
            return ChangeKind.Choice.NEW_PURCHASE;
        }
        if (choice.get() == used) {
            return ChangeKind.Choice.USED;
        }
        if (choice.get() == expired) {
            return ChangeKind.Choice.EXPIRED;
        }
        return ChangeKind.Choice.CORRECTION;
    }

    /** The outcome of the delete prompt: whether to proceed, and why. */
    public record DeleteChoice(boolean confirmed, String reason) { }

    /**
     * Confirms a deletion and captures an optional reason.
     * <p>
     * The reason is a plain text field the user types into — simpler than a pick
     * list, and there is no fixed set of reasons worth constraining people to. It
     * is optional: a user in a hurry can just confirm. The reason is stored with
     * the archived item so the Deleted Items list can show why each went.
     */
    public static DeleteChoice promptDelete(Window owner, String header, String message,
                                            String confirmLabel) {
        Dialog<DeleteChoice> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("Shelves");
        dialog.setHeaderText(header);
        dialog.getDialogPane().getStylesheets().addAll(stylesheetsOf(owner));
        dialog.getDialogPane().getStyleClass().add("shelves-dialog");

        Label prompt = new Label(message);
        prompt.setWrapText(true);
        prompt.setMaxWidth(380);

        Label reasonLabel = new Label("Reason (optional)");
        reasonLabel.getStyleClass().add("field-label");

        TextField reason = new TextField();
        reason.setPromptText("e.g. no longer stocked, changed brand, stopped using");
        reason.setMaxWidth(Double.MAX_VALUE);

        VBox box = new VBox(10, prompt, new VBox(4, reasonLabel, reason));
        box.setPadding(new Insets(6, 4, 4, 4));
        dialog.getDialogPane().setContent(box);

        ButtonType confirmButton = new ButtonType(confirmLabel, ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.CANCEL, confirmButton);
        ((Button) dialog.getDialogPane().lookupButton(confirmButton))
                .getStyleClass().add("danger");

        dialog.setResultConverter(button -> {
            if (button == confirmButton) {
                String value = reason.getText();
                return new DeleteChoice(true,
                        value == null || value.isBlank() ? null : value.trim());
            }
            return new DeleteChoice(false, null);
        });

        return dialog.showAndWait().orElse(new DeleteChoice(false, null));
    }

    /** Asks for a single line of text, such as a shelf name. */
    public static Optional<String> prompt(Window owner, String header, String message,
                                          String initialValue, String confirmLabel) {
        TextInputDialog dialog = new TextInputDialog(initialValue == null ? "" : initialValue);
        dialog.initOwner(owner);
        dialog.setTitle("Shelves");
        dialog.setHeaderText(header);
        dialog.setContentText(message);
        dialog.getDialogPane().getStylesheets().addAll(stylesheetsOf(owner));
        dialog.getDialogPane().getStyleClass().add("shelves-dialog");

        ButtonType confirmButton = new ButtonType(confirmLabel, ButtonType.OK.getButtonData());
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.CANCEL, confirmButton);

        return dialog.showAndWait().map(String::trim).filter(value -> !value.isEmpty());
    }

    // ==================== INTERNAL ====================

    private static Alert build(Window owner, Alert.AlertType type, String header) {
        Alert alert = new Alert(type);
        alert.initOwner(owner);
        alert.setTitle("Shelves");
        alert.setHeaderText(header);
        alert.getDialogPane().setMinWidth(420);
        alert.getDialogPane().setMinHeight(Region.USE_PREF_SIZE);
        alert.getDialogPane().getStylesheets().addAll(stylesheetsOf(owner));
        alert.getDialogPane().getStyleClass().add("shelves-dialog");
        return alert;
    }

    /** Reuses the main window's stylesheet so dialogs match the app. */
    private static java.util.List<String> stylesheetsOf(Window owner) {
        if (owner != null && owner.getScene() != null) {
            return owner.getScene().getStylesheets();
        }
        return java.util.List.of();
    }

    /**
     * Puts the stack trace behind a disclosure arrow. Users never need it, but
     * it is the first thing wanted when a bug report comes in.
     */
    private static void attachTechnicalDetail(Alert alert, Throwable error) {
        StringWriter writer = new StringWriter();
        error.printStackTrace(new PrintWriter(writer));

        TextArea detail = new TextArea(writer.toString());
        detail.setEditable(false);
        detail.setWrapText(false);
        detail.setPrefRowCount(12);
        detail.getStyleClass().add("technical-detail");

        alert.getDialogPane().setExpandableContent(detail);
    }

    /** The most specific message available, walking down to the root cause. */
    private static String describe(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getMessage() == null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null ? current.getClass().getSimpleName() : message;
    }
}
