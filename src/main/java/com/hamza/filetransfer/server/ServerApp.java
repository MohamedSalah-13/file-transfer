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
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.nio.file.Path;

public class ServerApp extends Application {

    private final ObservableList<TransferSession> sessions = FXCollections.observableArrayList();

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
        stage.setScene(scene);
        stage.show();

        // Creating the certificate on first start runs keytool (about a second): keep it off the FX thread.
        Thread starter = new Thread(() -> {
            try {
                AccountStore accounts = new AccountStore(Path.of("server-storage", "accounts.properties"));
                ServerTls tls = ServerTls.loadOrCreate(Path.of("server-storage", "tls"));

                new FileTransferServer(
                        5000,
                        Path.of("server-storage", "users"),
                        accounts,
                        tls.context(),
                        session -> {
                            // Called on the socket thread before any progress update.
                            session.deliverUpdatesOn(Platform::runLater);
                            Platform.runLater(() -> sessions.add(session));
                        }
                ).startAsync();

                Platform.runLater(() -> {
                    info.setText("Server running on port 5000 (TLS). Storage: server-storage/users");
                    fingerprint.setText(tls.fingerprint());
                });
            } catch (Exception e) {
                Platform.runLater(() -> info.setText("Server failed to start: " + e.getMessage()));
            }
        }, "server-start");
        starter.setDaemon(true);
        starter.start();
    }
}
