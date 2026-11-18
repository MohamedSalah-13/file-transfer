package com.hamza.filetransfer.client;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * Opens a file with its usual program, or shows it selected in Explorer. Both can block for a
 * moment, so callers run them off the FX thread.
 */
final class FileLauncher {

    private FileLauncher() {}

    /**
     * Types Windows runs (or that run code) when opened. A received file of one of these types is
     * never opened without the user confirming first.
     */
    private static final Set<String> RUNNABLE = Set.of(
            "exe", "com", "scr", "pif", "msi", "msp", "msix", "msixbundle", "appx", "appxbundle", "application",
            "bat", "cmd", "ps1", "psm1", "psd1", "vbs", "vbe", "js", "jse", "wsf", "wsh", "hta",
            "jar", "lnk", "url", "reg", "cpl", "msc", "inf", "scf", "gadget", "dll", "sys", "iso", "img", "vhd", "vhdx");

    static boolean canRunPrograms(String fileName) {
        // Windows ignores trailing dots and spaces: "setup.exe. " runs as setup.exe
        String name = fileName.strip().replaceAll("[.\\s]+$", "").toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot >= 0 && RUNNABLE.contains(name.substring(dot + 1));
    }

    static void open(Path file) throws IOException {
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            throw new IOException("Opening files is not supported here");
        }
        Desktop.getDesktop().open(file.toFile());
    }

    /** Explorer with the file selected; elsewhere, the folder that holds it. */
    static void showInFolder(Path file) throws IOException {
        Path absolute = file.toAbsolutePath();
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
            // "/select," and the path as two arguments. As one ("/select,C:\a b\c.txt") ProcessBuilder
            // quotes it whole for a path with spaces, and Explorer then opens Documents instead.
            new ProcessBuilder("explorer.exe", "/select,", absolute.toString()).start();
            return;
        }
        open(absolute.getParent());
    }
}
