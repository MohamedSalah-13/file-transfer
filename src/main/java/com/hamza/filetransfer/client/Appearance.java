package com.hamza.filetransfer.client;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * The language ({@code en} or {@code ar}) and theme name the user picked last.
 * Stored next to {@link LastLogin} in {@code ~/.file-transfer/client.properties}.
 */
public record Appearance(String language, String theme) {

    public static final Set<String> LANGUAGES = Set.of("en", "ar");
    public static final Appearance DEFAULT = new Appearance("en", "Primer Light");

    public static Path defaultLocation() {
        return PrefsFile.defaultLocation();
    }

    /**
     * The saved choice; an unknown language gives English. The theme name is returned as saved:
     * {@code ClientApp} falls back to the first theme if no theme has that name. Never throws.
     */
    public static Appearance load(Path file) {
        Properties p = PrefsFile.read(file);
        String language = p.getProperty("language", "").trim();
        String theme = p.getProperty("theme", "").trim();
        return new Appearance(LANGUAGES.contains(language) ? language : DEFAULT.language,
                theme.isEmpty() ? DEFAULT.theme : theme);
    }

    /** Saves the choice, keeping the file's other settings. */
    public void save(Path file) throws IOException {
        PrefsFile.update(file, Map.of("language", language, "theme", theme));
    }
}
