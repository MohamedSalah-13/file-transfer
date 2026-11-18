module com.hamza.filetransfer {
    requires javafx.controls;

    requires atlantafx.base;

    // Button icons (the Feather pack registers itself as an IkonHandler service)
    requires org.kordamp.ikonli.javafx;
    requires org.kordamp.ikonli.feather;

    // Windows notifications for new messages (AWT TrayIcon, see client.DesktopNotifier)
    requires java.desktop;

    exports com.hamza.filetransfer.shared;
    exports com.hamza.filetransfer.server;
    exports com.hamza.filetransfer.client;

}