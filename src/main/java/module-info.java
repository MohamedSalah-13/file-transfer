module com.hamza.filetransfer {
    requires javafx.controls;
    requires javafx.fxml;

    requires atlantafx.base;

    opens com.hamza.filetransfer to javafx.fxml;
    exports com.hamza.filetransfer;
    exports com.hamza.filetransfer.shared;
    exports com.hamza.filetransfer.server;
    exports com.hamza.filetransfer.client;

}