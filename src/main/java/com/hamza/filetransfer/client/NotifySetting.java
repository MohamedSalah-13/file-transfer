package com.hamza.filetransfer.client;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * Whether new messages show a Windows notification (on by default), kept in
 * {@code ~/.file-transfer/client.properties} with the client's other settings.
 */
public record NotifySetting(boolean enabled) {

    public static final NotifySetting DEFAULT = new NotifySetting(true);

    public static Path defaultLocation() {
        return PrefsFile.defaultLocation();
    }

    /** The saved choice; anything but {@code false} means on. Never throws. */
    public static NotifySetting load(Path file) {
        return new NotifySetting(!"false".equals(PrefsFile.read(file).getProperty("notifications", "").trim()));
    }

    /** Saves the choice, keeping the file's other settings. */
    public void save(Path file) throws IOException {
        PrefsFile.update(file, Map.of("notifications", Boolean.toString(enabled)));
    }
}
