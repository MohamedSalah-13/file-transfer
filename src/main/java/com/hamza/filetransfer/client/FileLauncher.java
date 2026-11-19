package com.hamza.filetransfer.client;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * Opens a file with its usual program, or shows it selected in Explorer (Finder on macOS). Both can
 * block for a moment, so callers run them off the FX thread.
 */
final class FileLauncher {

    private FileLauncher() {}

    private static final String OS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    private static final boolean WINDOWS = OS.startsWith("windows");
    private static final boolean MAC = OS.startsWith("mac");

    /**
     * Types Windows, macOS or a Linux desktop runs (or that run code) when opened. A received file of
     * one of these types is never opened without the user confirming first.
     */
    private static final Set<String> RUNNABLE = Set.of(
            // Windows
            "exe", "com", "scr", "pif", "msi", "msp", "msix", "msixbundle", "appx", "appxbundle", "application",
            "bat", "cmd", "ps1", "psm1", "psd1", "vbs", "vbe", "js", "jse", "wsf", "wsh", "hta",
            "jar", "lnk", "url", "reg", "cpl", "msc", "inf", "scf", "gadget", "dll", "sys", "iso", "img", "vhd", "vhdx",
            // macOS: Terminal runs .command and .tool; the others install or run programs
            "command", "tool", "app", "pkg", "mpkg", "dmg", "scpt", "workflow", "terminal",
            // Linux
            "sh", "desktop", "appimage", "run", "deb", "rpm");

    static boolean canRunPrograms(String fileName) {
        // Windows ignores trailing dots and spaces: "setup.exe. " runs as setup.exe
        String name = fileName.strip().replaceAll("[.\\s]+$", "").toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot >= 0 && RUNNABLE.contains(name.substring(dot + 1));
    }

    static void open(Path file) throws IOException {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            Desktop.getDesktop().open(file.toFile());
        } else if (!WINDOWS && !MAC) {
            // AWT's Desktop needs GNOME's libraries; xdg-open works on any Linux desktop.
            start("xdg-open", file.toAbsolutePath().toString());
        } else {
            throw new IOException("Opening files is not supported here");
        }
    }

    /** Explorer or Finder with the file selected; elsewhere, the folder that holds it. */
    static void showInFolder(Path file) throws IOException {
        Path absolute = file.toAbsolutePath();
        if (WINDOWS) {
            // "/select," and the path as two arguments. As one ("/select,C:\a b\c.txt") ProcessBuilder
            // quotes it whole for a path with spaces, and Explorer then opens Documents instead.
            new ProcessBuilder("explorer.exe", "/select,", absolute.toString()).start();
        } else if (MAC) {
            start("open", "-R", absolute.toString());
        } else {
            open(absolute.getParent());
        }
    }

    private static void start(String... command) throws IOException {
        new ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
    }
}
