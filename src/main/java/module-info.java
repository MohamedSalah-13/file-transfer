module com.hamza.filetransfer {
    requires javafx.controls;

    requires atlantafx.base;

    exports com.hamza.filetransfer.shared;
    exports com.hamza.filetransfer.server;
    exports com.hamza.filetransfer.client;

}