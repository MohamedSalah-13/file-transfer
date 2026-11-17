package com.hamza.filetransfer.client;

import javafx.beans.binding.Bindings;
import javafx.beans.binding.StringBinding;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleObjectProperty;

import java.text.MessageFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * Client UI translations backed by {@code messages*.properties}.
 * Bind UI text with {@link #bind}: it re-evaluates automatically when {@link #setLanguage} is called.
 * Values may use {@link MessageFormat} placeholders ({0}, {1}...).
 */
public final class I18n {

    private static final SimpleObjectProperty<ResourceBundle> BUNDLE =
            new SimpleObjectProperty<>(ResourceBundle.getBundle("messages", Locale.ENGLISH));

    private I18n() {}

    public static void setLanguage(String lang) {
        BUNDLE.set(ResourceBundle.getBundle("messages", Locale.of(lang)));
    }

    public static ReadOnlyObjectProperty<ResourceBundle> bundleProperty() {
        return BUNDLE;
    }

    public static boolean isRightToLeft() {
        return "ar".equals(BUNDLE.get().getLocale().getLanguage());
    }

    /** Current translation of {@code key}, formatted with {@code args}. Missing keys show as "!key!". */
    public static String get(String key, Object... args) {
        String pattern;
        try {
            pattern = BUNDLE.get().getString(key);
        } catch (MissingResourceException e) {
            return "!" + key + "!";
        }
        if (args.length == 0) return pattern;
        MessageFormat mf = new MessageFormat(pattern, BUNDLE.get().getLocale());
        return mf.format(args);
    }

    /** Live translation that follows language changes. */
    public static StringBinding bind(String key, Object... args) {
        return Bindings.createStringBinding(() -> get(key, args), BUNDLE);
    }

    /**
     * Short, readable message time in the user's time zone, relative to today:
     * "09:30", "Yesterday, 09:30", "17 Nov, 09:30", or "17 Nov 2025, 09:30" for other years.
     * Falls back to the raw text if it is not an ISO-8601 instant.
     */
    public static String messageTime(String isoInstant) {
        return messageTime(isoInstant, ZonedDateTime.now(DayClock.clock()));
    }

    static String messageTime(String isoInstant, ZonedDateTime now) {
        ZonedDateTime t = parseInstant(isoInstant, now.getZone());
        if (t == null) return isoInstant == null ? "" : isoInstant;
        return relative(t, now);
    }

    /** Same as {@link #messageTime(String)} for epoch millis; 0 or less means "no time" (empty). */
    public static String messageTime(long epochMillis) {
        if (epochMillis <= 0) return "";
        ZonedDateTime now = ZonedDateTime.now(DayClock.clock());
        return relative(Instant.ofEpochMilli(epochMillis).atZone(now.getZone()), now);
    }

    private static String relative(ZonedDateTime t, ZonedDateTime now) {
        LocalDate day = t.toLocalDate();
        LocalDate today = now.toLocalDate();
        String time = format(t, "time_pattern_today");

        if (day.equals(today)) return time;
        if (day.equals(today.minusDays(1))) return get("time_yesterday", time);
        if (day.getYear() == today.getYear()) return format(t, "time_pattern_this_year");
        return format(t, "time_pattern_older");
    }

    /** Full date and time (with weekday) for tooltips. */
    public static String messageTimeFull(String isoInstant) {
        ZonedDateTime t = parseInstant(isoInstant, DayClock.clock().getZone());
        return t == null ? (isoInstant == null ? "" : isoInstant) : format(t, "time_pattern_full");
    }

    private static ZonedDateTime parseInstant(String iso, ZoneId zone) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return Instant.parse(iso.trim()).atZone(zone);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** Patterns live in the bundle so each language controls word order and month names. */
    private static String format(ZonedDateTime t, String patternKey) {
        return DateTimeFormatter.ofPattern(get(patternKey), BUNDLE.get().getLocale()).format(t);
    }

    /**
     * Translates a transfer status code such as "SENDING" or "FAILED: reason".
     * Unknown codes are shown as-is.
     */
    public static String transferStatus(String raw) {
        if (raw == null) return "";
        String code = raw;
        String detail = "";
        int colon = raw.indexOf(':');
        if (colon > 0) {
            code = raw.substring(0, colon).trim();
            detail = raw.substring(colon + 1).trim();
        }
        String key = "transfer_status." + code;
        if (!BUNDLE.get().containsKey(key)) return raw;
        return detail.isEmpty() ? get(key) : get(key) + ": " + detail;
    }
}
