package com.shelves.util;

import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.PauseTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.TranslateTransition;
import javafx.scene.Node;
import javafx.util.Duration;

/**
 * Small, reusable motion helpers.
 * <p>
 * Every animation in Shelves is routed through this class for two reasons.
 * First, call sites stay one line, so the interface code is not buried in
 * timeline setup. Second, if the schedule tightens, motion can be toned down or
 * switched off in one place instead of being unpicked from a dozen screens.
 * <p>
 * Durations are deliberately short. Anything past roughly 250ms on a desktop
 * tool stops reading as polish and starts reading as lag.
 */
public final class Animations {

    private static final Duration QUICK = Duration.millis(160);
    private static final Duration GENTLE = Duration.millis(220);

    private Animations() {
    }

    /** Fades a node in from transparent. Used when swapping the main view. */
    public static void fadeIn(Node node) {
        FadeTransition fade = new FadeTransition(GENTLE, node);
        fade.setFromValue(0);
        fade.setToValue(1);
        fade.setInterpolator(Interpolator.EASE_OUT);
        fade.play();
    }

    /** Fades a node in while it rises slightly, for content appearing. */
    public static void riseIn(Node node) {
        FadeTransition fade = new FadeTransition(GENTLE, node);
        fade.setFromValue(0);
        fade.setToValue(1);

        TranslateTransition rise = new TranslateTransition(GENTLE, node);
        rise.setFromY(8);
        rise.setToY(0);
        rise.setInterpolator(Interpolator.EASE_OUT);

        fade.play();
        rise.play();
    }

    /**
     * A brief swell, used on the alert badge when the count goes up so the
     * change is noticed without anything flashing or moving position.
     */
    public static void pulse(Node node) {
        ScaleTransition pulse = new ScaleTransition(QUICK, node);
        pulse.setFromX(1);
        pulse.setFromY(1);
        pulse.setToX(1.12);
        pulse.setToY(1.12);
        pulse.setCycleCount(2);
        pulse.setAutoReverse(true);
        pulse.setInterpolator(Interpolator.EASE_BOTH);
        pulse.play();
    }

    /** A short horizontal shake, used when a form is submitted with errors. */
    public static void shake(Node node) {
        TranslateTransition shake = new TranslateTransition(Duration.millis(50), node);
        shake.setFromX(0);
        shake.setByX(6);
        shake.setCycleCount(6);
        shake.setAutoReverse(true);
        shake.setOnFinished(event -> node.setTranslateX(0));
        shake.play();
    }

    /** Fades a node out, then runs an action once it has gone. */
    public static void fadeOut(Node node, Runnable afterwards) {
        FadeTransition fade = new FadeTransition(QUICK, node);
        fade.setFromValue(node.getOpacity());
        fade.setToValue(0);
        fade.setOnFinished(event -> afterwards.run());
        fade.play();
    }

    /** Shows a node, waits, then fades it away. Used for the status message. */
    public static void flashThenFade(Node node, Duration hold) {
        node.setOpacity(1);
        PauseTransition wait = new PauseTransition(hold);
        wait.setOnFinished(event -> {
            FadeTransition fade = new FadeTransition(Duration.millis(400), node);
            fade.setFromValue(1);
            fade.setToValue(0);
            fade.play();
        });
        wait.play();
    }
}
