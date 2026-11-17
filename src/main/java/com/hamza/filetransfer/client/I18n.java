package com.hamza.filetransfer.client;

import javafx.beans.binding.Bindings;
import javafx.beans.binding.StringBinding;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleObjectProperty;

import java.text.MessageFormat;
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
