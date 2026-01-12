package com.hamza.filetransfer.client;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public final class FileTypeUtil {

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());

    private FileTypeUtil() {}

    public enum Kind { IMAGE, ARCHIVE, OTHER }

    public static Kind kindOf(String fileName) {
        String ext = extension(fileName);
        return switch (ext) {
            case "png", "jpg", "jpeg", "gif", "bmp", "webp" -> Kind.IMAGE;
            case "rar", "zip", "7z" -> Kind.ARCHIVE;
            default -> Kind.OTHER;
        };
    }

    public static String previewPrefixForFile(String fileName) {
        return switch (kindOf(fileName)) {
            case IMAGE -> "[IMG] ";
            case ARCHIVE -> "[BOOK] ";
            case OTHER -> "[FILE] ";
        };
    }

    public static String extension(String name) {
        if (name == null) return "";
        int i = name.lastIndexOf('.');
        if (i < 0 || i == name.length() - 1) return "";
        return name.substring(i + 1).toLowerCase(Locale.ROOT);
    }

    public static long parseIsoMillis(String isoInstant) {
        try {
            return Instant.parse(isoInstant).toEpochMilli();
        } catch (Exception e) {
            return 0L;
        }
    }

    public static String formatTime(long millis) {
        if (millis <= 0) return "";
        return TIME_FMT.format(Instant.ofEpochMilli(millis));
    }
}
