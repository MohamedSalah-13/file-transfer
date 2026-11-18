package com.hamza.filetransfer.server;

import com.hamza.filetransfer.client.Appearance;
import com.hamza.filetransfer.client.I18n;
import com.hamza.filetransfer.client.ThemeManager;
import com.hamza.filetransfer.client.Updates;
import com.hamza.filetransfer.shared.Discovery;
import com.hamza.updater.fx.UpdatePrompt;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.StringBinding;
import javafx.collections.*;
import javafx.geometry.Insets;
import javafx.geometry.NodeOrientation;
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

import java.io.IOException;
import java.net.BindException;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.List;

/**
 * The server window: status, certificate fingerprint and a table of transfers. Its text goes
 * through the client's {@link I18n} bundles, and the language and theme it shows are its own
 * choice, kept in {@code ~/.file-transfer/server-ui.properties}.
 */
public class ServerApp extends Application {

    private static final String ARABIC = "العربية"; // language names are shown in their own language
    private static final String ENGLISH = "English";

    private final ObservableList<TransferSession> sessions = FXCollections.observableArrayList();
    private DiscoveryResponder discovery; // kept open for the life of the app

    /** Where the server window keeps its language and theme (separate from the client's). */
    static Path appearanceFile() {
        return Path.of(System.getProperty("user.home"), ".file-transfer", "server-ui.properties");
    }

    @Override
    public void start(Stage stage) {
        Appearance look = Appearance.load(appearanceFile());
        ThemeManager.ThemeOption startTheme = ThemeManager.getThemes().stream()
                .filter(t -> t.name().equals(look.theme())).findFirst()
                .orElse(ThemeManager.getThemes().getFirst());
        I18n.setLanguage(look.language());
        ThemeManager.applyTheme(startTheme);

        TableView<TransferSession> table = new TableView<>(sessions);

        TableColumn<TransferSession, String> fromCol = column("column_from");
        fromCol.setCellValueFactory(c -> c.getValue().fromUserProperty());

        TableColumn<TransferSession, String> toCol = column("column_to");
        toCol.setCellValueFactory(c -> c.getValue().toUserProperty());

        TableColumn<TransferSession, String> fileCol = column("column_file");
        fileCol.setCellValueFactory(c -> c.getValue().fileNameProperty());

        TableColumn<TransferSession, Double> progCol = column("column_progress");
        progCol.setCellValueFactory(c -> c.getValue().progressProperty().asObject());
        progCol.setCellFactory(ProgressBarTableCell.forTableColumn());

        TableColumn<TransferSession, String> statusCol = column("column_status");
        // Sessions keep language-neutral codes (RECEIVING, DONE...); translate for display only.
        statusCol.setCellValueFactory(c -> {
            TransferSession s = c.getValue();
            return Bindings.createStringBinding(() -> I18n.transferStatus(s.statusProperty().get()),
                    s.statusProperty(), I18n.bundleProperty());
        });

        table.getColumns().addAll(fromCol, toCol, fileCol, progCol, statusCol);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        table.setPlaceholder(new Label(""));

        Label info = new Label();
        info.textProperty().bind(I18n.bind("server_starting"));
        info.setWrapText(true);

        // Clients show this fingerprint the first time they connect; users compare the two
        // before trusting the server. Read-only text field so it can be selected and copied.
        TextField fingerprint = new TextField();
        fingerprint.setEditable(false);
        fingerprint.setPrefColumnCount(70);
        fingerprint.setNodeOrientation(NodeOrientation.LEFT_TO_RIGHT); // hex reads left to right, also in Arabic
        Label fingerprintLabel = new Label();
        fingerprintLabel.textProperty().bind(I18n.bind("server_fingerprint"));
        fingerprintLabel.setMinWidth(Region.USE_PREF_SIZE); // never cut to "Certifica..."
        HBox fingerprintRow = new HBox(8, fingerprintLabel, fingerprint);
        fingerprintRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(fingerprint, Priority.ALWAYS);

        // Language and theme, remembered for the next start
        ComboBox<String> langPicker = new ComboBox<>(FXCollections.observableArrayList(ENGLISH, ARABIC));
        langPicker.getSelectionModel().select("ar".equals(look.language()) ? ARABIC : ENGLISH);
        ComboBox<ThemeManager.ThemeOption> themePicker = new ComboBox<>(ThemeManager.getThemes());
        themePicker.getSelectionModel().select(startTheme);
        Label langLabel = new Label();
        langLabel.textProperty().bind(I18n.bind("language"));
        Label themeLabel = new Label();
        themeLabel.textProperty().bind(I18n.bind("theme"));
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox infoRow = new HBox(8, info, spacer, langLabel, langPicker, themeLabel, themePicker);
        infoRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(info, Priority.SOMETIMES);
        for (Region r : List.of(langLabel, langPicker, themeLabel, themePicker)) r.setMinWidth(Region.USE_PREF_SIZE);

        VBox top = new VBox(8, infoRow, fingerprintRow);
        top.getStyleClass().add("topbar");

        VBox card = new VBox(10, table);
        card.getStyleClass().add("card");
        VBox.setVgrow(table, Priority.ALWAYS);

        BorderPane root = new BorderPane();
        root.setTop(top);
        root.setCenter(card);
        BorderPane.setMargin(card, new Insets(12));
        applyOrientation(root);

        // Handlers are set after the start values are selected, so selecting them saves nothing.
        // The person who runs the server is told of a new release too: clients that update ahead of the
        // server cannot connect when the release changes the protocol.
        UpdatePrompt updates = UpdatePrompt.create(Updates.updater(), stage).language(look.language()).start();
        langPicker.setOnAction(e -> {
            I18n.setLanguage(ARABIC.equals(langPicker.getValue()) ? "ar" : "en");
            updates.language(ARABIC.equals(langPicker.getValue()) ? "ar" : "en");
            applyOrientation(root);
            rememberAppearance(langPicker, themePicker);
        });
        themePicker.setOnAction(e -> {
            ThemeManager.applyTheme(themePicker.getValue());
            rememberAppearance(langPicker, themePicker);
        });

        Scene scene = new Scene(root, 1000, 520);
        scene.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());

        stage.titleProperty().bind(I18n.bind("server_title"));
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

                StringBinding discoveryLine = startDiscovery(config.port());
                ServerConfig running = config;
                Platform.runLater(() -> {
                    // Numbers go in as text, so the port is never shown as "5,000".
                    info.textProperty().bind(Bindings.concat(
                            I18n.bind("server_running", Integer.toString(running.port())), System.lineSeparator(),
                            I18n.bind("server_storage", leftToRight(running.storageDir().toString())), System.lineSeparator(),
                            discoveryLine));
                    fingerprint.setText(tls.fingerprint());
                });
            } catch (Exception e) {
                StringBinding why = startFailure(e, config);
                Platform.runLater(() -> info.textProperty().bind(Bindings.createStringBinding(
                        () -> I18n.get("server_failed", why.get()), why, I18n.bundleProperty())));
            }
        }, "server-start");
        starter.setDaemon(true);
        starter.start();
    }

    /** Answers "Find servers" from clients. Optional: the server works without it. Returns a status line. */
    private StringBinding startDiscovery(int tcpPort) {
        try {
            discovery = new DiscoveryResponder(Discovery.PORT, tcpPort, serverName());
            return I18n.bind("server_discovery_on", Integer.toString(Discovery.PORT));
        } catch (Exception e) {
            return I18n.bind("server_discovery_off", leftToRight(String.valueOf(e.getMessage())));
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
    static StringBinding startFailure(Exception e, ServerConfig config) {
        if (e instanceof BindException && config != null) {
            return I18n.bind("server_port_in_use", Integer.toString(config.port()), ServerConfig.CONFIG_FILE);
        }
        String detail = leftToRight(e.getMessage() == null ? e.toString() : e.getMessage());
        return Bindings.createStringBinding(() -> detail);
    }

    /**
     * Keeps a path or an English message in reading order inside Arabic text (a left-to-right
     * embedding, U+202A ... U+202C); without it a leading "--" or "\" can jump to the other end.
     * Invisible in English. The Arabic bundle uses the same characters around "--port <رقم>".
     */
    static String leftToRight(String text) {
        return "‪" + text + "‬";
    }

    private static <T> TableColumn<TransferSession, T> column(String key) {
        TableColumn<TransferSession, T> c = new TableColumn<>();
        c.textProperty().bind(I18n.bind(key));
        return c;
    }

    private static void applyOrientation(BorderPane root) {
        root.setNodeOrientation(I18n.isRightToLeft() ? NodeOrientation.RIGHT_TO_LEFT : NodeOrientation.LEFT_TO_RIGHT);
    }

    /** Saves the picked language and theme. A failure only loses the convenience. */
    private static void rememberAppearance(ComboBox<String> langPicker, ComboBox<ThemeManager.ThemeOption> themePicker) {
        if (langPicker.getValue() == null || themePicker.getValue() == null) return;
        try {
            new Appearance(ARABIC.equals(langPicker.getValue()) ? "ar" : "en", themePicker.getValue().name())
                    .save(appearanceFile());
        } catch (IOException | RuntimeException e) {
            System.err.println("Could not save the server window's language and theme: " + e);
        }
    }
}
