package com.hamza.filetransfer.client;

import javax.imageio.ImageIO;
import java.awt.AWTException;
import java.awt.GraphicsEnvironment;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Windows notifications ("toasts") for new messages. JavaFX has none, so this uses an AWT
 * {@link TrayIcon}: it is added on the first notification and stays in the notification area
 * while the app runs; clicking it, or the notification, calls {@code onClick} with the sender.
 * All AWT work runs on one background thread, never on the FX thread. {@link #close()} removes the
 * icon, so the JVM can exit when the window closes.
 */
final class DesktopNotifier implements ClientApp.Notifier {

    private final ExecutorService awt = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "notifier");
        t.setDaemon(true);
        return t;
    });
    private final String appName;
    private final Consumer<String> onClick;
    private TrayIcon tray;          // only touched on the `awt` thread
    private volatile String lastSender;

    DesktopNotifier(String appName, Consumer<String> onClick) {
        this.appName = appName;
        this.onClick = onClick;
    }

    /** False on systems without a notification area (or headless): then nothing is shown. */
    static boolean supported() {
        return !GraphicsEnvironment.isHeadless() && SystemTray.isSupported();
    }

    @Override
    public void show(String sender, String title, String text) {
        lastSender = sender;
        awt.execute(() -> {
            try {
                if (tray == null) tray = addTrayIcon();
                tray.displayMessage(title, text, TrayIcon.MessageType.NONE);
            } catch (IOException | AWTException | RuntimeException e) {
                System.err.println("Could not show a notification: " + e);
            }
        });
    }

    private TrayIcon addTrayIcon() throws IOException, AWTException {
        java.awt.Image image;
        try (InputStream in = DesktopNotifier.class.getResourceAsStream("/icons/client-32.png")) {
            image = ImageIO.read(in);
        }
        TrayIcon icon = new TrayIcon(image, appName);
        icon.setImageAutoSize(true);
        // Fires for a click on the notification and a double-click on the icon (on the AWT thread).
        icon.addActionListener(e -> onClick.accept(lastSender));
        SystemTray.getSystemTray().add(icon);
        return icon;
    }

    @Override
    public void close() {
        awt.execute(() -> {
            if (tray != null) SystemTray.getSystemTray().remove(tray);
            tray = null;
        });
        awt.shutdown();
    }
}
