package com.shelves;

import com.shelves.db.Database;
import com.shelves.exception.ShelvesException;
import com.shelves.service.InventoryService;
import com.shelves.service.ShelfLifeService;
import com.shelves.ui.MainView;
import com.shelves.ui.ThemeManager;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.stage.Stage;

/**
 * Starts Shelves.
 * <p>
 * Responsibilities are deliberately narrow: open the database, build the
 * services, hand them to the main window, and make sure a failure during
 * startup produces an explanation rather than a stack trace on a console the
 * user will never see.
 */
public class App extends Application {

    private Database database;

    @Override
    public void start(Stage stage) {
        try {
            database = new Database();
            database.initialise();

            InventoryService inventory = new InventoryService(database);
            ShelfLifeService shelfLife = new ShelfLifeService(database);

            // Loads the built-in reference on first run only.
            shelfLife.seedIfEmpty();

            MainView root = new MainView(stage, inventory, shelfLife);

            Scene scene = new Scene(root, 1120, 700);
            scene.getStylesheets().add(
                    App.class.getResource("/com/shelves/styles.css").toExternalForm());

            // Theme is applied to the scene root and remembered in the data
            // folder, so the app opens in whichever mode was last chosen.
            ThemeManager themeManager = new ThemeManager(root, database.getDataDirectory());
            root.setThemeManager(themeManager);

            stage.setTitle("Shelves");
            stage.setScene(scene);
            stage.setMinWidth(900);
            stage.setMinHeight(560);
            stage.show();

        } catch (ShelvesException e) {
            showStartupFailure(e.getMessage());
        } catch (RuntimeException e) {
            showStartupFailure("Shelves could not start: " + e);
        }
    }

    /**
     * Reports a failure that happened before there was a window to report it
     * in. Without this the application would simply fail to appear.
     */
    private void showStartupFailure(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Shelves");
        alert.setHeaderText("Shelves could not start");
        alert.setContentText(message
                + "\n\nCheck that you have permission to write to your home folder, "
                + "then try again.");
        alert.showAndWait();
        javafx.application.Platform.exit();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
