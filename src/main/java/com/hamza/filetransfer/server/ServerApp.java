package com.hamza.filetransfer.server;

import atlantafx.base.theme.PrimerLight;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.*;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.cell.ProgressBarTableCell;
import javafx.scene.layout.BorderPane;
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

        Label info = new Label("Server running on port 5000. Storage: server-storage/users");
        info.getStyleClass().add("topbar");

        VBox card = new VBox(10, table);
        card.getStyleClass().add("card");

        BorderPane root = new BorderPane();
        root.setTop(info);
        root.setCenter(card);
        BorderPane.setMargin(card, new Insets(12));

        Scene scene = new Scene(root, 900, 500);
        scene.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());

        stage.setTitle("File Transfer - Server");
        stage.setScene(scene);
        stage.show();

        AccountStore accounts;
        try {
            accounts = new AccountStore(Path.of("server-storage", "accounts.properties"));
        } catch (Exception e) {
            info.setText("Failed to load accounts: " + e.getMessage());
            return;
        }

        FileTransferServer server = new FileTransferServer(
                5000,
                Path.of("server-storage", "users"),
                accounts,
                session -> Platform.runLater(() -> sessions.add(session))
        );
        server.startAsync();
    }
}
