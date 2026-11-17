package com.hamza.filetransfer.client;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.util.Duration;

import java.time.Clock;
import java.time.LocalDate;

/**
 * The current local date as an observable value, so relative time labels
 * ("09:30" → "Yesterday, 09:30") update by themselves after midnight.
 *
 * <p>It checks the date once a minute instead of scheduling a single timer for midnight:
 * a one-shot timer can fire late after the computer sleeps, while polling also catches
 * sleep/resume and manual clock changes. The check is a cheap date comparison.
 */
public final class DayClock {

    private static Clock clock = Clock.systemDefaultZone();
    private static final ReadOnlyObjectWrapper<LocalDate> TODAY = new ReadOnlyObjectWrapper<>(LocalDate.now(clock));
    private static Timeline ticker;

    private DayClock() {}

    /** Starts the once-a-minute check. Call on the JavaFX thread; repeated calls are ignored. */
    public static void start() {
        if (ticker != null) return;
        ticker = new Timeline(new KeyFrame(Duration.minutes(1), e -> check()));
        ticker.setCycleCount(Timeline.INDEFINITE);
        ticker.play();
    }

    /** Today's date; changes (on the JavaFX thread) when the day rolls over. */
    public static ReadOnlyObjectProperty<LocalDate> todayProperty() {
        return TODAY.getReadOnlyProperty();
    }

    /** Clock used for "now" in time labels. */
    static Clock clock() {
        return clock;
    }

    /** Re-reads the date and notifies listeners if it changed. */
    static void check() {
        LocalDate now = LocalDate.now(clock);
        if (!now.equals(TODAY.get())) TODAY.set(now);
    }

    /** Test hook: replace the clock (e.g. a fixed time just after midnight), then call {@link #check()}. */
    static void setClock(Clock c) {
        clock = c;
    }
}
