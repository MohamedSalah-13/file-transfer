package com.hamza.filetransfer.client;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

/**
 * Which side panels are shown and how wide they are (pixels), kept from one start to the next in
 * {@code ~/.file-transfer/client.properties} next to {@link LastLogin} and {@link Appearance}.
 */
public record PanelLayout(boolean usersShown, double usersWidth, boolean transfersShown, double transfersWidth) {

    public static final double MIN_WIDTH = 150;
    public static final double MAX_WIDTH = 2000;
    public static final PanelLayout DEFAULT = new PanelLayout(true, 300, true, 420);

    public static Path defaultLocation() {
        return PrefsFile.defaultLocation();
    }

    /** The saved layout, with {@link #DEFAULT} values for anything missing or out of range. Never throws. */
    public static PanelLayout load(Path file) {
        Properties p = PrefsFile.read(file);
        return new PanelLayout(
                bool(p, "panel.users.shown", DEFAULT.usersShown),
                width(p, "panel.users.width", DEFAULT.usersWidth),
                bool(p, "panel.transfers.shown", DEFAULT.transfersShown),
                width(p, "panel.transfers.width", DEFAULT.transfersWidth));
    }

    /** Saves the layout, keeping the file's other settings. */
    public void save(Path file) throws IOException {
        PrefsFile.update(file, Map.of(
                "panel.users.shown", Boolean.toString(usersShown),
                "panel.users.width", Long.toString(Math.round(usersWidth)),
                "panel.transfers.shown", Boolean.toString(transfersShown),
                "panel.transfers.width", Long.toString(Math.round(transfersWidth))));
    }

    private static boolean bool(Properties p, String key, boolean fallback) {
        String v = p.getProperty(key, "").trim();
        return v.equals("true") || (!v.equals("false") && fallback);
    }

    private static double width(Properties p, String key, double fallback) {
        try {
            double w = Double.parseDouble(p.getProperty(key, "").trim());
            return w >= MIN_WIDTH && w <= MAX_WIDTH ? w : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
