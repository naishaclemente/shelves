package com.shelves.ui;

import javafx.scene.Parent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Owns the light / dark theme: which one is active, switching between them, and
 * remembering the choice across launches.
 * <p>
 * The mechanism is deliberately simple. The stylesheet defines every colour
 * twice — once in {@code .root} (light) and once in {@code .root.dark-mode}
 * (dark) — so switching theme is nothing more than adding or removing the
 * {@code dark-mode} style class on the scene root. One class toggle recolours
 * the whole application at once.
 * <p>
 * Colours drawn on a canvas (the history charts) cannot read CSS, so this class
 * is also the single source of truth they consult to pick theme-appropriate
 * colours in code. Anything that needs to react to a theme change registers a
 * listener here.
 */
public class ThemeManager {

    /** The two themes. */
    public enum Theme { LIGHT, DARK }

    private static final String DARK_CLASS = "dark-mode";
    private static final String PREF_FILE = "theme.pref";

    /**
     * The darkness of the active theme, readable without a reference to the
     * manager. Canvas-drawn colours (the history charts) cannot read CSS and
     * cannot easily be handed the manager through several dialog layers, so they
     * consult this instead. There is one theme for the whole application at a
     * time, so a single shared value is accurate.
     */
    private static volatile boolean currentlyDark = false;

    /** Whether the active theme is dark, for code that draws its own colours. */
    public static boolean isCurrentThemeDark() {
        return currentlyDark;
    }

    private final Parent root;
    private final Path prefFile;
    private final java.util.List<Runnable> listeners = new java.util.ArrayList<>();
    private Theme theme = Theme.LIGHT;

    /**
     * @param root      the scene root the theme class is toggled on
     * @param dataDir   the folder to store the remembered choice in, or null to
     *                  not persist (e.g. in tests)
     */
    public ThemeManager(Parent root, Path dataDir) {
        this.root = root;
        this.prefFile = dataDir == null ? null : dataDir.resolve(PREF_FILE);
        this.theme = readSavedTheme();
        apply();
    }

    public Theme getTheme() {
        return theme;
    }

    public boolean isDark() {
        return theme == Theme.DARK;
    }

    /** Switches to the other theme, applies it, and remembers the choice. */
    public void toggle() {
        setTheme(theme == Theme.LIGHT ? Theme.DARK : Theme.LIGHT);
    }

    public void setTheme(Theme newTheme) {
        if (newTheme == null || newTheme == theme) {
            return;
        }
        this.theme = newTheme;
        apply();
        save();
        for (Runnable listener : listeners) {
            listener.run();
        }
    }

    /**
     * Registers something to run whenever the theme changes — used by the charts
     * to redraw themselves in the new theme's colours.
     */
    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    private void apply() {
        currentlyDark = theme == Theme.DARK;
        if (theme == Theme.DARK) {
            if (!root.getStyleClass().contains(DARK_CLASS)) {
                root.getStyleClass().add(DARK_CLASS);
            }
        } else {
            root.getStyleClass().remove(DARK_CLASS);
        }
    }

    private Theme readSavedTheme() {
        if (prefFile == null || !Files.exists(prefFile)) {
            return Theme.LIGHT;
        }
        try {
            String saved = Files.readString(prefFile).trim().toUpperCase(java.util.Locale.ROOT);
            return saved.equals("DARK") ? Theme.DARK : Theme.LIGHT;
        } catch (IOException e) {
            // A preference we cannot read is not worth failing over; default to
            // light and carry on.
            return Theme.LIGHT;
        }
    }

    private void save() {
        if (prefFile == null) {
            return;
        }
        try {
            Files.writeString(prefFile, theme.name());
        } catch (IOException e) {
            // Not remembering the choice is a small loss, not worth interrupting
            // the user over.
        }
    }
}
