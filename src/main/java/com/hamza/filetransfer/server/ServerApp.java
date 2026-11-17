package com.hamza.filetransfer.server;

import atlantafx.base.theme.PrimerLight;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.*;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.cell.ProgressBarTableCell;
import javafx.scene.image.Image;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import com.hamza.filetransfer.shared.Discovery;

import java.net.BindException;
import java.net.InetAddress;
import java.util.List;

public class ServerApp extends Application {

    private final ObservableList<TransferSession> sessions = FXCollections.observableArrayList();
    private DiscoveryResponder discovery; // kept open for the life of the app

    @Override
    public void start(Stage stage) {

        Application.setUserAgentStylesheet(new PrimerLight().getUserAgentStylesheet());

        TableView<TransferSession> table = new TableView<>(sessions);

        TableColumn<TransferSession, String> fromCol = new TableColumn<>("From");
        fromCol.setCellValueFactory(c -> c.getValue().fromUserProperty());

        TableColumn<TransferSession, String> toCol = new TableColumn<>("To");
        toCol.setCellValueFactory(c -> c.getValue().toUserProperty());

        TableColumn<TransferSession, String> fileCol = new TableColumn<>("File");
        fileCol.setCellValueFactory(c -> c.getValue().fileNameProperty());

        TableColumn<TransferSession, Double> progCol = new TableColumn<>("Progress");
        progCol.setCellValueFactory(c -> c.getValue().progressProperty().asObject());
        progCol.setCellFactory(ProgressBarTableCell.forTableColumn());

        TableColumn<TransferSession, String> statusCol = new TableColumn<>("Status");
        statusCol.setCellValueFactory(c -> c.getValue().statusProperty());

        table.getColumns().addAll(fromCol, toCol, fileCol, progCol, statusCol);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);

        Label info = new Label("Starting server...");

        // Clients show this fingerprint the first time they connect; users compare the two
        // before trusting the server. Read-only text field so it can be selected and copied.
        TextField fingerprint = new TextField();
        fingerprint.setEditable(false);
        fingerprint.setPrefColumnCount(70);
        Label fingerprintLabel = new Label("Certificate fingerprint (SHA-256):");
        fingerprintLabel.setMinWidth(Region.USE_PREF_SIZE); // never cut to "Certifica..."
        HBox fingerprintRow = new HBox(8, fingerprintLabel, fingerprint);
        fingerprintRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(fingerprint, Priority.ALWAYS);

        VBox top = new VBox(6, info, fingerprintRow);
        top.getStyleClass().add("topbar");

        VBox card = new VBox(10, table);
        card.getStyleClass().add("card");

        BorderPane root = new BorderPane();
        root.setTop(top);
        root.setCenter(card);
        BorderPane.setMargin(card, new Insets(12));

        Scene scene = new Scene(root, 900, 500);
        scene.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());

        stage.setTitle("File Transfer - Server");
        // The client icon with a server badge (drawn by packaging/IconMaker.java).
        for (int size : new int[]{16, 24, 32, 48, 64, 128, 256}) {
            stage.getIcons().add(new Image(getClass().getResource("/icons/server-" + size + ".png").toExternalForm()));
        }
        stage.setScene(scene);
        stage.show();

        // Creating the certificate on first start runs keytool (about a second): keep it off the FX thread.
        List<String> args = getParameters() == null ? List.of() : getParameters().getRaw(); // null if not started by launch()
        Thread starter = new Thread(() -> {
            ServerConfig config = null;
            try {
                config = ServerConfig.load(args, ServerConfig.workDir());
                AccountStore accounts = new AccountStore(config.accountsFile());
                ServerTls tls = ServerTls.loadOrCreate(config.tlsDir());

                new FileTransferServer(
                        config.port(),
                        config.usersDir(),
                        accounts,
                        tls.context(),
                        session -> {
                            // Called on the socket thread before any progress update.
                            session.deliverUpdatesOn(Platform::runLater);
                            Platform.runLater(() -> sessions.add(session));
                        }
                ).startAsync();

                String discovery = startDiscovery(config.port());
                ServerConfig running = config;
                Platform.runLater(() -> {
                    info.setText("Server running on port " + running.port() + " (TLS). Storage: " + running.storageDir()
                            + System.lineSeparator() + discovery);
                    fingerprint.setText(tls.fingerprint());
                });
            } catch (Exception e) {
                String why = startFailure(e, config);
                Platform.runLater(() -> info.setText("Server failed to start: " + why));
            }
        }, "server-start");
        starter.setDaemon(true);
        starter.start();
    }

    /** Answers "Find servers" from clients. Optional: the server works without it. Returns a status line. */
    private String startDiscovery(int tcpPort) {
        try {
            discovery = new DiscoveryResponder(Discovery.PORT, tcpPort, serverName());
            return "LAN discovery: on (UDP " + Discovery.PORT + "). Clients can use \"Find servers\".";
        } catch (Exception e) {
            return "LAN discovery: off (" + e.getMessage() + "). Clients must type this computer's address.";
        }
    }

    private static String serverName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "File Transfer server";
        }
    }

    /** A message that says what to change, for the errors people actually hit. */
    static String startFailure(Exception e, ServerConfig config) {
        if (e instanceof BindException && config != null) {
            return "port " + config.port() + " is already in use (is another server running?). "
                    + "Start with --port <number> or set port=<number> in " + ServerConfig.CONFIG_FILE + ".";
        }
        if (e instanceof IllegalArgumentException) return e.getMessage();
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }
}
