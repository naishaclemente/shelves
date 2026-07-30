package com.shelves;

/**
 * Plain entry point.
 * <p>
 * Exists because of a quirk of JavaFX: when the main class extends
 * {@link javafx.application.Application}, the runtime insists on being started
 * from the module path and refuses with "JavaFX runtime components are missing"
 * if it is not. Launching from a class that does not extend Application avoids
 * that check, which is what makes a plain runnable jar work.
 * <p>
 * Running from an IDE or with the Maven JavaFX plugin can use either this or
 * {@link App} directly.
 */
public class Launcher {

    public static void main(String[] args) {
        App.main(args);
    }
}
