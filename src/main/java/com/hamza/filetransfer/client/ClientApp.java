package com.hamza.filetransfer.client;

import com.hamza.updater.AppUpdater;
import com.hamza.updater.fx.UpdatePrompt;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.collections.ListChangeListener;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Orientation;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.NodeOrientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.control.skin.VirtualFlow;
import javafx.scene.input.DragEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.*;
import javafx.scene.text.TextAlignment;
import javafx.css.PseudoClass;
import atlantafx.base.controls.CustomTextField;
import atlantafx.base.theme.Styles;
import org.kordamp.ikonli.feather.Feather;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import com.hamza.filetransfer.shared.Discovery;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.BooleanSupplier;

public class ClientApp extends Application {

    private final ObservableList<UserItem> userItems = FXCollections.observableArrayList();
    private final Map<String, UserItem> userIndex = new HashMap<>();
    private final ObservableList<ChatMessage> chatMessages = FXCollections.observableArrayList();
    private final ObservableList<SimpleTransferRow> transfers = FXCollections.observableArrayList();
    // Keep one comparator definition so sorting is consistent everywhere.
    private final Comparator<UserItem> USER_COMPARATOR = (a, b) -> {
        int unread = Integer.compare(b.unreadCountProperty().get(), a.unreadCountProperty().get());
        if (unread != 0) return unread;

        int activity = Long.compare(b.lastActivityMillisProperty().get(), a.lastActivityMillisProperty().get());
        if (activity != 0) return activity;

        return a.getUsername().compareToIgnoreCase(b.getUsername());
    };
    private ClientConnection conn;
    private final BooleanProperty connected = new SimpleBooleanProperty(false); // drives Connect/Disconnect
    private SettingsWindow settings;
    private UpdatePrompt updates;
    private KnownServers knownServers; // servers whose TLS certificate this user trusted
    private String activePeer;
    private ListView<ChatMessage> chatListRef;
    private ListView<UserItem> usersViewRef;
    private Path lastAttachFolder; // the file dialog opens where the last attachment came from
    final ObservableList<Path> attachments = FXCollections.observableArrayList(); // picked with "Attach Files"; tests fill it directly
    private Path lastDownloadFolder; // the folder dialog opens where the last download went
    // Search in the open chat
    CustomTextField chatSearch;
    private final Label searchCount = new Label();
    private final BooleanProperty searchHasMatches = new SimpleBooleanProperty(false);
    /** Ids of the matching messages, oldest first; {@link #currentMatch} indexes into it (-1: none). */
    private List<String> searchMatches = List.of();
    private int currentMatch = -1;
    private LocalFiles localFiles;   // where chat files are on this computer (downloaded or sent)
    /** Opening and showing files; tests replace them so nothing really opens. */
    FileAction openFile = FileLauncher::open;
    FileAction showInFolder = FileLauncher::showInFolder;

    interface FileAction {
        void run(Path file) throws Exception;
    }
    private Stage mainStage;

    /** Shows a desktop notification; {@link DesktopNotifier} in the app, a recorder in tests. */
    interface Notifier extends AutoCloseable {
        void show(String sender, String title, String text);

        @Override
        void close();
    }

    private static final long NOTIFY_QUIET_MILLIS = 4000;
    Notifier notifier;               // set in start() unless a test set it first
    BooleanSupplier windowInFront;   // "is the user looking at the app?"; tests may replace it
    private final BooleanProperty notifyEnabled = new SimpleBooleanProperty(true);
    private final Map<String, Long> lastNotified = new HashMap<>();
    private final IntegerProperty totalUnread = new SimpleIntegerProperty(); // shown in the window title

    private boolean resorting;

    private void resortUsers() {
        // Sort in-place. This does NOT temporarily clear the list, so it won't break selection.
        // Sorting makes the ListView re-select the current item, which fires the selection
        // listener again (and that listener sorts) - the flag breaks that loop.
        if (resorting) return;
        resorting = true;
        try {
            FXCollections.sort(userItems, USER_COMPARATOR);
        } finally {
            resorting = false;
        }
    }

    @Override
    public void start(Stage stage) throws Exception {
        knownServers = new KnownServers(KnownServers.defaultLocation());
        localFiles = new LocalFiles(LocalFiles.defaultLocation());
        mainStage = stage;
        if (windowInFront == null) windowInFront = stage::isFocused;
        notifyEnabled.set(NotifySetting.load(NotifySetting.defaultLocation()).enabled());
        if (notifier == null) {
            notifier = DesktopNotifier.supported()
                    ? new DesktopNotifier("File Transfer", this::openChatFromNotification)
                    : new Notifier() {
                        public void show(String sender, String title, String text) { }
                        public void close() { }
                    };
        }
        // Total unread across all chats, for the window title.
        InvalidationListener recount = o -> totalUnread.set(
                userItems.stream().mapToInt(u -> u.unreadCountProperty().get()).sum());
        userItems.addListener((ListChangeListener<UserItem>) ch -> {
            while (ch.next()) {
                for (UserItem u : ch.getAddedSubList()) u.unreadCountProperty().addListener(recount);
            }
            recount.invalidated(null);
        });
        // Coming back to the window: the open chat's new messages are now seen.
        stage.focusedProperty().addListener((obs, was, focused) -> {
            if (focused && activePeer != null) clearUnread(activePeer);
        });
        Appearance look = Appearance.load(Appearance.defaultLocation());
        ThemeManager.ThemeOption startTheme = ThemeManager.getThemes().stream()
                .filter(t -> t.name().equals(look.theme())).findFirst()
                .orElse(ThemeManager.getThemes().getFirst());
        I18n.setLanguage(look.language());
        ThemeManager.applyTheme(startTheme);
        // A newer release is announced in this window; closing for the installer goes the way closing the
        // window does, so the panel layout is saved and stop() removes the tray icon.
        AppUpdater updater = Updates.updater();
        updates = UpdatePrompt.create(updater, stage).language(look.language()).onExit(() -> {
            stage.hide();
            Platform.exit();
        }).start();

        LastLogin last = LastLogin.load(LastLogin.defaultLocation());
        TextField tfHost = new TextField(last.host());
        TextField tfPort = new TextField(Integer.toString(last.port()));
        TextField tfUser = new TextField(last.user());
        PasswordField pfPassword = new PasswordField();
        pfPassword.promptTextProperty().bind(I18n.bind("password_prompt"));
        Button btnConnect = Icons.on(new Button(), Feather.LOG_IN);
        btnConnect.textProperty().bind(I18n.bind("connect"));
        Button btnFind = Icons.on(new Button(), Feather.WIFI);
        btnFind.textProperty().bind(I18n.bind("find_servers"));
        btnFind.setOnAction(e -> findServers(settings.stage(), btnFind, tfHost, tfPort, pfPassword));

        Label lblStatus = label("status_not_connected");

        // Language Picker (language names are shown in their own language on purpose)
        ComboBox<String> langPicker = new ComboBox<>(FXCollections.observableArrayList("English", "العربية"));
        langPicker.getSelectionModel().select("ar".equals(look.language()) ? "العربية" : "English");

        // Theme Picker
        ComboBox<ThemeManager.ThemeOption> themePicker = new ComboBox<>(ThemeManager.getThemes());
        themePicker.getSelectionModel().select(startTheme);

        // Handlers are set after the start values are selected, so selecting them saves nothing.
        langPicker.setOnAction(e -> {
            String selected = langPicker.getValue();
            I18n.setLanguage(selected.equals("العربية") ? "ar" : "en");
            updates.language(selected.equals("العربية") ? "ar" : "en");
            applyOrientation(stage.getScene().getRoot());
            settings.applyOrientation();
            if (chatListRef != null) chatListRef.refresh(); // bubbles read translations when rendered
            updateSearchCount();
            rememberAppearance(langPicker, themePicker);
        });
        themePicker.setOnAction(e -> {
            ThemeManager.applyTheme(themePicker.getValue());
            rememberAppearance(langPicker, themePicker);
            // Re-apply custom app styles to ensure they override theme defaults if necessary
            String css = getClass().getResource("/styles/app.css").toExternalForm();
            if (stage.getScene() != null && !stage.getScene().getStylesheets().contains(css)) {
                stage.getScene().getStylesheets().add(css);
            }
        });

        // ===== Settings window: Connection and Appearance tabs =====
        tfHost.setPrefColumnCount(14);
        tfPort.setPrefColumnCount(5);
        tfUser.setPrefColumnCount(14);
        pfPassword.setPrefColumnCount(20);
        btnConnect.setDefaultButton(true); // Enter in the password field connects
        Label lblSettingsStatus = new Label();
        lblSettingsStatus.textProperty().bind(lblStatus.textProperty());
        lblSettingsStatus.setWrapText(true);

        GridPane connectionForm = form();
        connectionForm.addRow(0, label("host"), tfHost);
        connectionForm.addRow(1, label("port"), new HBox(10, tfPort, btnFind));
        connectionForm.addRow(2, label("user"), tfUser);
        connectionForm.addRow(3, label("password"), pfPassword);
        connectionForm.add(new HBox(12, btnConnect, lblSettingsStatus), 1, 4);
        ((HBox) connectionForm.getChildren().getLast()).setAlignment(Pos.CENTER_LEFT);

        GridPane appearanceForm = form();
        appearanceForm.addRow(0, label("language"), langPicker);
        appearanceForm.addRow(1, label("theme"), themePicker);
        CheckBox cbNotify = new CheckBox();
        cbNotify.textProperty().bind(I18n.bind("notify_checkbox"));
        cbNotify.setWrapText(true);
        cbNotify.setPrefWidth(330); // wrap the long text instead of widening the whole settings window
        cbNotify.setSelected(notifyEnabled.get());
        notifyEnabled.bind(cbNotify.selectedProperty());
        cbNotify.selectedProperty().addListener((obs, was, on) -> rememberNotify(on));
        appearanceForm.addRow(2, label("notifications"), cbNotify);
        Button btnCheckUpdates = Icons.on(new Button(), Feather.DOWNLOAD_CLOUD);
        btnCheckUpdates.textProperty().bind(I18n.bind("check_updates"));
        btnCheckUpdates.setOnAction(e -> updates.checkNow());
        Label lblVersion = updater.currentVersion()
                .map(version -> {
                    Label l = new Label();
                    l.textProperty().bind(I18n.bind("updates_version", version.toString()));
                    return l;
                })
                .orElseGet(() -> label("updates_dev"));
        HBox updatesRow = new HBox(10, btnCheckUpdates, lblVersion);
        updatesRow.setAlignment(Pos.CENTER_LEFT);
        appearanceForm.addRow(3, label("updates"), updatesRow);

        // ===== Top bar: status, connect/disconnect, panel toggles, settings =====
        lblStatus.getStyleClass().add("connection-status");
        lblStatus.setMinWidth(0);
        Button btnConnection = new Button();
        btnConnection.textProperty().bind(Bindings.when(connected)
                .then(I18n.bind("disconnect")).otherwise(I18n.bind("connect")));
        btnConnection.graphicProperty().bind(Bindings.createObjectBinding(
                () -> Icons.of(connected.get() ? Feather.LOG_OUT : Feather.LOG_IN), connected));
        ToggleButton tgUsers = Icons.on(new ToggleButton(), Feather.USERS);
        tgUsers.textProperty().bind(I18n.bind("panel_users"));
        ToggleButton tgTransfers = Icons.on(new ToggleButton(), Feather.REPEAT);
        tgTransfers.textProperty().bind(I18n.bind("transfers"));
        Button btnSettings = Icons.on(new Button(), Feather.SETTINGS);
        btnSettings.textProperty().bind(I18n.bind("settings"));
        Region topSpacer = new Region();
        HBox.setHgrow(topSpacer, Priority.ALWAYS);
        HBox top = new HBox(8, lblStatus, topSpacer, btnConnection, new Separator(Orientation.VERTICAL),
                tgUsers, tgTransfers, new Separator(Orientation.VERTICAL), btnSettings);
        top.setAlignment(Pos.CENTER_LEFT);
        top.getStyleClass().add("topbar");
        for (Node n : top.getChildren()) {
            if (n instanceof ButtonBase b) b.setMinWidth(Region.USE_PREF_SIZE);
        }

        // ===== Left sidebar =====
        CustomTextField tfSearchUsers = new CustomTextField(); // AtlantaFX: a text field with an icon inside
        tfSearchUsers.setLeft(Icons.of(Feather.SEARCH));
        tfSearchUsers.promptTextProperty().bind(I18n.bind("search_prompt"));

        FilteredList<UserItem> filteredUsers = new FilteredList<>(userItems, u -> true);
        tfSearchUsers.textProperty().addListener((obs, old, q) -> {
            String query = q == null ? "" : q.trim().toLowerCase();
            filteredUsers.setPredicate(u -> query.isEmpty() || u.getUsername().toLowerCase().contains(query));
        });

        // IMPORTANT CHANGE:
        // Instead of SortedList + "setAll" hack, we keep a FilteredList and sort userItems in-place using resortUsers().
        ListView<UserItem> usersView = new ListView<>(filteredUsers);
        usersViewRef = usersView;
        usersView.setPrefWidth(300);
        usersView.setCellFactory(lv -> new UserCell());

        Button btnRefreshUsers = Icons.on(new Button(), Feather.REFRESH_CW);
        btnRefreshUsers.textProperty().bind(I18n.bind("refresh"));

        VBox left = new VBox(10, label("online_header"), tfSearchUsers, usersView, btnRefreshUsers);
        left.getStyleClass().add("sidebar");

        // ===== Center chat surface =====
        Label chatTitle = label("select_user");
        chatTitle.getStyleClass().add("chat-title");

        Label chatSub = label("chat_subtitle");
        chatSub.getStyleClass().add("chat-subtitle");

        ListView<ChatMessage> chatList = new ListView<>(chatMessages);
        this.chatListRef = chatList;
        chatList.setCellFactory(lv -> new ChatBubbleCell());

        // double-click download (only if downloadable)
        chatList.setOnKeyPressed(e -> { // Delete on a selected message: same as right-click > Delete...
            ChatMessage m = chatList.getSelectionModel().getSelectedItem();
            if (e.getCode() == KeyCode.DELETE && m != null) {
                askToDelete(m);
                e.consume();
            }
        });
        chatList.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                ChatMessage m = chatList.getSelectionModel().getSelectedItem();
                if (m != null && m.typeProperty().get() == ChatMessage.Type.FILE && m.downloadableProperty().get()) {
                    chooseAndDownload(stage, m.messageIdProperty().get(), m.fileNameProperty().get());
                }
            }
        });

        // Search in the open chat: Ctrl+F, Enter for older matches, Shift+Enter for newer, Esc to clear.
        chatSearch = new CustomTextField();
        chatSearch.setLeft(Icons.of(Feather.SEARCH));
        chatSearch.promptTextProperty().bind(I18n.bind("search_chat_prompt"));
        chatSearch.setPrefColumnCount(14);
        Button searchOlder = Icons.on(new Button(), Feather.CHEVRON_UP);
        Button searchNewer = Icons.on(new Button(), Feather.CHEVRON_DOWN);
        for (Button b : List.of(searchOlder, searchNewer)) {
            b.getStyleClass().addAll(Styles.BUTTON_ICON, Styles.FLAT);
            b.setFocusTraversable(false);
            b.disableProperty().bind(searchHasMatches.not());
        }
        searchOlder.setTooltip(tooltip("search_older"));
        searchNewer.setTooltip(tooltip("search_newer"));
        searchOlder.accessibleTextProperty().bind(I18n.bind("search_older"));
        searchNewer.accessibleTextProperty().bind(I18n.bind("search_newer"));
        searchCount.getStyleClass().add("search-count");
        searchCount.setMinWidth(Region.USE_PREF_SIZE);
        chatSearch.textProperty().addListener((obs, old, q) -> runChatSearch(false));
        chatSearch.setOnKeyPressed(e -> {
            switch (e.getCode()) {
                case ENTER -> stepChatSearch(e.isShiftDown() ? 1 : -1);
                case ESCAPE -> chatSearch.clear();
                default -> { return; }
            }
            e.consume();
        });
        searchOlder.setOnAction(e -> stepChatSearch(-1));
        searchNewer.setOnAction(e -> stepChatSearch(1));
        // A reloaded or switched chat: search it again, staying on the same message when it is still there.
        chatMessages.addListener((ListChangeListener<ChatMessage>) ch -> runChatSearch(true));
        HBox searchBox = new HBox(4, chatSearch, searchCount, searchOlder, searchNewer);
        searchBox.setAlignment(Pos.CENTER_RIGHT);

        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        HBox chatHeader = new HBox(10, chatTitle, headerSpacer, searchBox);
        chatHeader.setAlignment(Pos.CENTER_LEFT);

        VBox center = new VBox(10, chatHeader, chatSub, chatList);
        center.getStyleClass().add("chat-surface");

        TextField tfMessage = new TextField();
        tfMessage.promptTextProperty().bind(I18n.bind("type_message"));

        Button btnSendText = Icons.on(new Button(), Feather.SEND);
        btnSendText.textProperty().bind(I18n.bind("send_text"));

        btnSendText.setOnAction(a -> {
            if (conn == null || activePeer == null) return;
            String text = tfMessage.getText();
            String peer = activePeer; // capture now: the selection may change before the thread runs
            tfMessage.clear();

            Thread t = new Thread(() -> {
                try {
                    conn.sendText(peer, text);
                    Platform.runLater(() -> refreshThreadIfActive(peer));
                } catch (Exception ex) {
                    Platform.runLater(() -> setStatus(lblStatus, "status_error", AppError.from(ex)));
                }
            }, "send-text");
            t.setDaemon(true);
            t.start();
        });

        Button btnChoose = Icons.on(new Button(), Feather.PAPERCLIP);
        btnChoose.textProperty().bind(I18n.bind("attach"));
        // The attached files: one or several (Ctrl/Shift-click in the file dialog).
        ObservableList<Path> chosen = attachments;
        Label lblChosen = new Label();
        lblChosen.textProperty().bind(Bindings.createStringBinding(() -> switch (chosen.size()) {
            case 0 -> I18n.get("no_file");
            case 1 -> chosen.getFirst().getFileName().toString();
            default -> I18n.get("files_chosen", Integer.toString(chosen.size()));
        }, chosen, I18n.bundleProperty()));
        // Several files: hovering the label lists them.
        Tooltip chosenNames = new Tooltip();
        chosenNames.textProperty().bind(Bindings.createStringBinding(() -> String.join(System.lineSeparator(),
                chosen.stream().map(p -> p.getFileName().toString()).toList()), chosen));
        lblChosen.tooltipProperty().bind(Bindings.when(Bindings.size(chosen).greaterThan(1))
                .then(chosenNames).otherwise((Tooltip) null));
        Button btnSend = Icons.on(new Button(), Feather.UPLOAD);
        btnSend.textProperty().bind(Bindings.createStringBinding(() -> chosen.size() > 1
                ? I18n.get("send_files", Integer.toString(chosen.size())) : I18n.get("send_file"),
                chosen, I18n.bundleProperty()));

        btnChoose.setOnAction(a -> {
            FileChooser fc = new FileChooser();
            fc.setTitle(I18n.get("choose_file"));
            if (lastAttachFolder != null && Files.isDirectory(lastAttachFolder)) fc.setInitialDirectory(lastAttachFolder.toFile());
            List<File> files = fc.showOpenMultipleDialog(stage);
            if (files != null && !files.isEmpty()) { // cancelled: keep what was attached
                chosen.setAll(files.stream().map(File::toPath).toList());
                lastAttachFolder = chosen.getFirst().getParent();
            }
        });

        btnSend.setOnAction(a -> {
            if (conn == null || activePeer == null || chosen.isEmpty()) return;
            String peer = activePeer;
            chosen.forEach(f -> sendFile(peer, f)); // each file is its own transfer and row
            chosen.clear(); // sent: the next Send needs a new attachment, so nothing goes twice by mistake
        });

        FlowPane composer = new FlowPane(tfMessage, btnSendText, btnChoose, lblChosen, btnSend);
        HBox.setHgrow(tfMessage, Priority.ALWAYS);
        composer.setAlignment(Pos.CENTER_LEFT);
        composer.setHgap(10);
        composer.setVgap(5);
        composer.setPadding(new Insets(5, 10, 5, 10));
        composer.getStyleClass().add("composer");

        // Transfers table (كما هو عندك سابقاً) — أبقيه كما هو
        TableView<SimpleTransferRow> transfersTable = new TableView<>(transfers);
        transfersTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);

        TableColumn<SimpleTransferRow, String> tName = new TableColumn<>();
        tName.textProperty().bind(I18n.bind("column_transfer"));
        tName.setCellValueFactory(c -> c.getValue().nameProperty());

        TableColumn<SimpleTransferRow, Double> tProg = new TableColumn<>();
        tProg.textProperty().bind(I18n.bind("column_progress"));
        tProg.setCellValueFactory(c -> c.getValue().progressProperty().asObject());
        tProg.setCellFactory(tc -> new javafx.scene.control.cell.ProgressBarTableCell<>());

        TableColumn<SimpleTransferRow, String> tStatus = new TableColumn<>();
        tStatus.textProperty().bind(I18n.bind("column_status"));
        // Rows keep language-neutral status codes ("SENDING", "FAILED") and an AppError for failures;
        // both are translated for display only, so they follow language changes.
        tStatus.setCellValueFactory(c -> {
            SimpleTransferRow r = c.getValue();
            return Bindings.createStringBinding(() -> {
                String status = I18n.transferStatus(r.statusProperty().get());
                AppError failure = r.failureProperty().get();
                return failure == null ? status : status + ": " + I18n.error(failure);
            }, r.statusProperty(), r.failureProperty(), I18n.bundleProperty());
        });

        // ✕ stops a running upload or download (only that transfer; chat and the others go on).
        TableColumn<SimpleTransferRow, SimpleTransferRow> tCancel = new TableColumn<>();
        tCancel.setCellValueFactory(c -> new javafx.beans.property.ReadOnlyObjectWrapper<>(c.getValue()));
        tCancel.setCellFactory(tc -> new CancelCell());
        tCancel.setSortable(false);
        tCancel.setReorderable(false);
        tCancel.setResizable(false);
        tCancel.setPrefWidth(44);
        tCancel.setMinWidth(44);
        tCancel.setMaxWidth(44);

        transfersTable.getColumns().addAll(tName, tProg, tStatus, tCancel);
        transfersTable.setPlaceholder(label("no_transfers"));

        VBox right = new VBox(10, label("transfers"), transfersTable);
        right.getStyleClass().add("card");
        VBox.setVgrow(transfersTable, Priority.ALWAYS);
        VBox.setVgrow(usersView, Priority.ALWAYS);
        VBox.setVgrow(chatList, Priority.ALWAYS);

        center.getChildren().addAll(label("send"), composer);
        // Files dragged from Explorer onto the chat are sent to the open chat's user.
        StackPane chatDropTarget = new StackPane(center);
        acceptDroppedFiles(chatDropTarget);
        // ===== Layout: drag the dividers to resize the side panels; the top bar toggles hide them =====
        SidePanels panels = new SidePanels(left, chatDropTarget, right, PanelLayout.load(PanelLayout.defaultLocation()));
        tgUsers.selectedProperty().bindBidirectional(panels.usersShownProperty());
        tgTransfers.selectedProperty().bindBidirectional(panels.transfersShownProperty());
        stage.setOnHidden(e -> rememberLayout(panels.current()));

        BorderPane root = new BorderPane();
        root.setTop(top);
        BorderPane.setMargin(panels.node(), new Insets(12));
        root.setCenter(panels.node());

        // when selecting user => load thread
        usersView.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            if (selected == null || conn == null || resorting) return;

            activePeer = selected.getUsername();
            chatTitle.textProperty().bind(I18n.bind("chat_with", activePeer));
            selected.clearUnread();

            // IMPORTANT CHANGE:
            // Don't do userItems.setAll(userItems); it temporarily clears the list and breaks selection.
            // Just resort safely (in-place).
            resortUsers();

            conn.requestThread(activePeer); // queued off the FX thread
        });

        btnConnect.setOnAction(a -> {
            int port;
            try {
                port = Integer.parseInt(tfPort.getText().trim());
            } catch (NumberFormatException e) {
                setStatus(lblStatus, "status_connect_failed", new AppError(AppError.INVALID_PORT));
                return;
            }
            String host = tfHost.getText().trim();
            String user = tfUser.getText().trim();
            char[] password = pfPassword.getText().toCharArray();
            pfPassword.clear();

            endSession(chatTitle); // reconnect: drop the old session and its chats first
            // Trust and error dialogs open over the settings window, where the user is.
            connectInBackground(settings.stage(), host, port, user, password, btnConnect, tfUser, lblStatus);
        });

        // Not connected: the button opens the Connection tab (the password is needed there).
        btnConnection.setOnAction(a -> {
            if (connected.get()) {
                endSession(chatTitle);
                setStatus(lblStatus, "status_not_connected");
            } else {
                openConnectionSettings(pfPassword);
            }
        });
        btnSettings.setOnAction(a -> {
            if (connected.get()) settings.showAppearance();
            else openConnectionSettings(pfPassword);
        });

        btnRefreshUsers.setOnAction(a -> {
            if (conn != null) conn.requestUsers();
        });

        Scene scene = new Scene(root, 1280, 720);
        scene.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.F, KeyCombination.SHORTCUT_DOWN), () -> {
            chatSearch.requestFocus();
            chatSearch.selectAll();
        });
        applyOrientation(root);

        // After midnight, "09:30" must become "Yesterday, 09:30". The sidebar binds to the date;
        // chat bubbles format their time when rendered, so re-render them.
        DayClock.todayProperty().addListener((obs, oldDay, newDay) -> chatList.refresh());
        DayClock.start();

        // "(3) File Transfer Chat": unread messages show in the taskbar too.
        stage.titleProperty().bind(Bindings.createStringBinding(
                () -> totalUnread.get() > 0 ? "(" + totalUnread.get() + ") " + I18n.get("app_title") : I18n.get("app_title"),
                totalUnread, I18n.bundleProperty()));
        // Title bar and taskbar icon instead of Java's (drawn by packaging/IconMaker.java).
        for (int size : new int[]{16, 24, 32, 48, 64, 128, 256}) {
            stage.getIcons().add(new Image(getClass().getResource("/icons/client-" + size + ".png").toExternalForm()));
        }
        stage.setScene(scene);
        settings = new SettingsWindow(stage, connectionForm, appearanceForm,
                getClass().getResource("/styles/app.css").toExternalForm());
        stage.show();
        // Nobody is connected at start: show the Connection tab right away.
        settings.showConnection();
        // A returning user only needs the password. On a first start keep the field unfocused,
        // so its prompt (which explains how new accounts work) stays visible.
        if (!last.equals(LastLogin.DEFAULT)) pfPassword.requestFocus();
    }

    private void openConnectionSettings(PasswordField pfPassword) {
        settings.showConnection();
        pfPassword.requestFocus();
    }

    /** Closes the current connection (if any) and clears what it showed. */
    private void endSession(Label chatTitle) {
        if (conn != null) {
            try { conn.close(); } catch (Exception ignored) { }
            conn = null;
        }
        connected.set(false);
        activePeer = null;
        chatMessages.clear();
        userItems.clear();
        userIndex.clear();
        chatTitle.textProperty().bind(I18n.bind("select_user"));
    }

    private static GridPane form() {
        GridPane g = new GridPane();
        g.setHgap(12);
        g.setVgap(12);
        ColumnConstraints labels = new ColumnConstraints();
        labels.setMinWidth(Region.USE_PREF_SIZE); // never "Passw..." (Arabic labels are longer)
        ColumnConstraints fields = new ColumnConstraints();
        fields.setHgrow(Priority.ALWAYS);
        g.getColumnConstraints().addAll(labels, fields);
        return g;
    }

    private static void rememberNotify(boolean on) {
        try {
            new NotifySetting(on).save(NotifySetting.defaultLocation());
        } catch (IOException | RuntimeException e) {
            System.err.println("Could not save the notification setting: " + e);
        }
    }

    /** Saves the panel widths and which panels are shown. A failure only loses the convenience. */
    private static void rememberLayout(PanelLayout layout) {
        try {
            layout.save(PanelLayout.defaultLocation());
        } catch (IOException | RuntimeException e) {
            System.err.println("Could not save the panel layout: " + e);
        }
    }

    /** Uploads one file to {@code peer} on its own TRANSFER socket, with a row in the transfers panel. */
    private void sendFile(String peer, Path file) {
        SimpleTransferRow row = new SimpleTransferRow("");
        row.nameProperty().bind(I18n.bind("transfer_send", file.getFileName().toString(), peer));
        row.setStatus("SENDING");
        transfers.add(row);

        ClientConnection c = conn; // the upload belongs to this session even if the user reconnects
        Thread t = new Thread(() -> {
            try {
                // Server errors (e.g. unknown recipient, file too large) fail this row only.
                String messageId = c.sendFile(peer, file, row::setProgress, row.handle());
                Platform.runLater(() -> {
                    localFiles.remember(messageId, file); // the sender can open their original too
                    row.setStatus("DONE");
                    refreshThreadIfActive(peer);
                });
            } catch (TransferHandle.TransferCancelledException ex) {
                Platform.runLater(() -> row.setStatus("CANCELLED")); // the ✕ button, not an error
            } catch (Exception ex) {
                Platform.runLater(() -> row.setFailed(AppError.from(ex)));
            }
        }, "send-task");
        t.setDaemon(true);
        t.start();
    }

    private static final PseudoClass DROP_BLOCKED = PseudoClass.getPseudoClass("blocked");

    /**
     * Lets files dragged from Explorer be dropped on {@code target} (the chat): while they hover, an
     * overlay says what a drop would do, and dropping sends each file to the open chat's user.
     * Folders are skipped. File drags are handled in event filters, so the message field never
     * pastes their paths as text.
     */
    private void acceptDroppedFiles(StackPane target) {
        Label overlay = new Label();
        overlay.getStyleClass().add("drop-overlay");
        overlay.setWrapText(true);
        overlay.setTextAlignment(TextAlignment.CENTER);
        overlay.setAlignment(Pos.CENTER);
        overlay.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        overlay.setMouseTransparent(true);
        overlay.setGraphic(Icons.of(Feather.UPLOAD_CLOUD));
        overlay.setContentDisplay(ContentDisplay.TOP);
        overlay.setGraphicTextGap(12);
        overlay.setVisible(false);
        target.getChildren().add(overlay);

        target.addEventFilter(DragEvent.DRAG_OVER, e -> {
            if (!e.getDragboard().hasFiles()) return;
            List<Path> files = regularFiles(e.getDragboard());
            boolean canSend = conn != null && activePeer != null && !files.isEmpty();
            if (canSend) e.acceptTransferModes(TransferMode.COPY);
            overlay.setText(dropHint(files, files.size() < e.getDragboard().getFiles().size()));
            overlay.pseudoClassStateChanged(DROP_BLOCKED, !canSend);
            overlay.setVisible(true);
            e.consume();
        });
        target.addEventFilter(DragEvent.DRAG_DROPPED, e -> {
            if (!e.getDragboard().hasFiles()) return;
            overlay.setVisible(false);
            String peer = activePeer; // capture now: the selection may change while files upload
            List<Path> files = regularFiles(e.getDragboard());
            boolean send = conn != null && peer != null && !files.isEmpty();
            if (send) files.forEach(f -> sendFile(peer, f));
            e.setDropCompleted(send);
            e.consume();
        });
        target.addEventHandler(DragEvent.DRAG_EXITED, e -> overlay.setVisible(false));
    }

    private static List<Path> regularFiles(Dragboard board) {
        return board.getFiles().stream().map(File::toPath).filter(Files::isRegularFile).toList();
    }

    /** What dropping these files would do now, or why it can't. */
    private String dropHint(List<Path> files, boolean foldersSkipped) {
        if (conn == null) return I18n.get("drop_connect_first");
        if (activePeer == null) return I18n.get("drop_pick_user");
        if (files.isEmpty()) return I18n.get("drop_only_files");
        String hint = files.size() == 1
                ? I18n.get("drop_send_one", files.getFirst().getFileName().toString(), activePeer)
                : I18n.get("drop_send_many", Integer.toString(files.size()), activePeer);
        return foldersSkipped ? hint + System.lineSeparator() + I18n.get("drop_folders_skipped") : hint;
    }

    private void chooseAndDownload(Stage stage, String messageId, String displayName) {
        if (conn == null) return;

        DirectoryChooser dc = new DirectoryChooser();
        dc.setTitle(I18n.get("choose_download_folder"));
        if (lastDownloadFolder != null && Files.isDirectory(lastDownloadFolder)) dc.setInitialDirectory(lastDownloadFolder.toFile());
        var dir = dc.showDialog(stage);
        if (dir == null) return;
        lastDownloadFolder = dir.toPath();
        downloadInto(dir.toPath(), messageId, displayName);
    }

    /**
     * Asks how to delete a message: "for everyone" only on a message I sent (and not already
     * deleted), "for me" always; Cancel is the default. Then asks the server and reloads the chat.
     */
    void askToDelete(ChatMessage m) {
        if (conn == null || activePeer == null) return;
        String peer = activePeer;
        boolean mineToRemove = m.outgoingProperty().get() && m.typeProperty().get() != ChatMessage.Type.DELETED;

        ButtonType forEveryone = new ButtonType(I18n.get("delete_for_everyone"), ButtonBar.ButtonData.OTHER);
        ButtonType forMe = new ButtonType(I18n.get("delete_for_me"), ButtonBar.ButtonData.OTHER);
        ButtonType cancel = new ButtonType(I18n.get("cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert alert = mineToRemove
                ? new Alert(Alert.AlertType.CONFIRMATION, "", forEveryone, forMe, cancel)
                : new Alert(Alert.AlertType.CONFIRMATION, "", forMe, cancel);
        alert.setTitle(I18n.get("delete_title"));
        alert.setHeaderText(I18n.get("delete_title"));
        String what = switch (m.typeProperty().get()) {
            case TEXT -> shorten(m.textProperty().get());
            case FILE -> m.fileNameProperty().get();
            case DELETED -> I18n.get(m.outgoingProperty().get() ? "deleted_by_you" : "deleted_by_them");
        };
        Label quote = new Label(what);
        quote.getStyleClass().add("delete-quote");
        quote.setWrapText(true);
        Label note = new Label(I18n.get(mineToRemove ? "delete_note_everyone" : "delete_note_me", peer));
        note.setWrapText(true);
        alert.getDialogPane().setContent(new VBox(10, quote, note));
        alert.getDialogPane().setPrefWidth(560);
        if (mineToRemove) Icons.on((Button) alert.getDialogPane().lookupButton(forEveryone), Feather.TRASH_2);
        Icons.on((Button) alert.getDialogPane().lookupButton(forMe), Feather.TRASH);
        for (ButtonType t : alert.getButtonTypes()) {
            Button b = (Button) alert.getDialogPane().lookupButton(t);
            b.setDefaultButton(t == cancel); // Enter never deletes
            b.setMinWidth(Region.USE_PREF_SIZE); // never "Delete for ..."
            ButtonBar.setButtonUniformSize(b, false); // the uniform width ignores the icons
        }

        ButtonType choice = showLocalized(mainStage, alert).orElse(cancel);
        if (choice == cancel) return;
        ClientConnection c = conn;
        c.deleteMessage(peer, m.messageIdProperty().get(), choice == forEveryone);
        c.requestThread(peer); // queued after the delete, so the reply shows the result
    }

    /** The open chat's messages, oldest first (for tests). */
    ObservableList<ChatMessage> chatMessagesForTest() {
        return chatMessages;
    }

    private static String shorten(String text) {
        String t = text == null ? "" : text.strip();
        return t.length() > 120 ? t.substring(0, 120) + "..." : t;
    }

    /**
     * Finds the messages of the open chat whose text or file name contains the search (see
     * {@link SearchText}). A new search starts at the newest match; {@code keepCurrent} (the chat
     * reloaded) stays on the same message if it still matches.
     */
    private void runChatSearch(boolean keepCurrent) {
        if (chatSearch == null) return;
        String query = SearchText.normalize(chatSearch.getText());
        String stayOn = keepCurrent && currentMatch >= 0 ? searchMatches.get(currentMatch) : null;
        searchMatches = query.isEmpty() ? List.of() : chatMessages.stream()
                .filter(m -> SearchText.contains(m.typeProperty().get() == ChatMessage.Type.TEXT
                        ? m.textProperty().get() : m.fileNameProperty().get(), query))
                .map(m -> m.messageIdProperty().get())
                .toList();
        currentMatch = searchMatches.isEmpty() ? -1
                : stayOn != null && searchMatches.contains(stayOn) ? searchMatches.indexOf(stayOn)
                : searchMatches.size() - 1;
        showCurrentMatch(!keepCurrent);
    }

    /** -1: the older match, +1: the newer one; wraps around at either end. */
    private void stepChatSearch(int direction) {
        if (searchMatches.isEmpty()) return;
        currentMatch = Math.floorMod(currentMatch + direction, searchMatches.size());
        showCurrentMatch(true);
    }

    private void showCurrentMatch(boolean scroll) {
        searchHasMatches.set(!searchMatches.isEmpty());
        updateSearchCount();
        if (chatListRef == null) return;
        chatListRef.refresh(); // re-style the bubbles
        if (scroll && currentMatch >= 0) {
            String id = searchMatches.get(currentMatch);
            for (int i = 0; i < chatMessages.size(); i++) {
                if (chatMessages.get(i).messageIdProperty().get().equals(id)) {
                    int index = i;
                    chatListRef.scrollTo(Math.max(0, index - 1)); // one message of context above it
                    // Bubbles have different heights, so that first jump is an estimate and can stop
                    // a bubble short near the end of the chat. After the layout, make sure it shows.
                    Platform.runLater(() -> {
                        if (chatListRef.lookup(".virtual-flow") instanceof VirtualFlow<?> flow) flow.scrollTo(index);
                    });
                    break;
                }
            }
        }
    }

    private void updateSearchCount() {
        boolean searching = chatSearch != null && !SearchText.normalize(chatSearch.getText()).isEmpty();
        searchCount.setText(!searching ? "" : searchMatches.isEmpty() ? I18n.get("search_none")
                : I18n.get("search_count", Integer.toString(currentMatch + 1), Integer.toString(searchMatches.size())));
        searchCount.pseudoClassStateChanged(DROP_BLOCKED, searching && searchMatches.isEmpty());
    }

    /** "match" for every matching message, "current" for the one the arrows are on. */
    private String searchState(ChatMessage m) {
        int i = searchMatches.indexOf(m.messageIdProperty().get());
        return i < 0 ? null : i == currentMatch ? "current" : "match";
    }

    private static Tooltip tooltip(String key) {
        Tooltip t = new Tooltip();
        t.textProperty().bind(I18n.bind(key));
        return t;
    }

    /**
     * Opens a chat file with its usual program. A received file that can run programs (an .exe, a
     * script...) asks first, with Cancel as the default: it came from someone else.
     */
    private void openChatFile(ChatMessage m) {
        Path file = stillThere(m);
        if (file == null) return;
        if (!m.outgoingProperty().get() && FileLauncher.canRunPrograms(file.getFileName().toString())
                && !confirmOpenRunnable(file.getFileName().toString(), m.fromUserProperty().get())) {
            return;
        }
        runFileAction(openFile, file);
    }

    private void showChatFile(ChatMessage m) {
        Path file = stillThere(m);
        if (file != null) runFileAction(showInFolder, file);
    }

    /** The message's file, or null (and its buttons go back to Download) if it was moved or deleted. */
    private Path stillThere(ChatMessage m) {
        Path file = m.localPathProperty().get();
        if (file != null && Files.isRegularFile(file)) return file;
        m.localPathProperty().set(null);
        if (chatListRef != null) chatListRef.refresh();
        return null;
    }

    /** Opening can block for a moment (Explorer, a big program): never on the FX thread. */
    private void runFileAction(FileAction action, Path file) {
        Thread t = new Thread(() -> {
            try {
                action.run(file);
            } catch (Exception e) {
                String why = e.getMessage() == null ? e.toString() : e.getMessage();
                Platform.runLater(() -> {
                    Alert alert = new Alert(Alert.AlertType.ERROR, I18n.get("open_failed", file.getFileName().toString(), why), ButtonType.OK);
                    alert.setTitle(I18n.get("app_title"));
                    alert.setHeaderText(null);
                    showLocalized(mainStage, alert);
                });
            }
        }, "open-file");
        t.setDaemon(true);
        t.start();
    }

    private boolean confirmOpenRunnable(String fileName, String sender) {
        ButtonType openAnyway = new ButtonType(I18n.get("open_anyway"), ButtonBar.ButtonData.OTHER);
        ButtonType cancel = new ButtonType(I18n.get("cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        Label explain = new Label(I18n.get("open_risky_content", sender));
        explain.setWrapText(true);
        Alert alert = new Alert(Alert.AlertType.WARNING, "", openAnyway, cancel);
        alert.setTitle(I18n.get("open_risky_title"));
        alert.setHeaderText(I18n.get("open_risky_header", fileName));
        alert.getDialogPane().setContent(explain);
        alert.getDialogPane().setPrefWidth(520);
        Button anyway = Icons.on((Button) alert.getDialogPane().lookupButton(openAnyway), Feather.ALERT_TRIANGLE);
        anyway.setDefaultButton(false);
        ((Button) alert.getDialogPane().lookupButton(cancel)).setDefaultButton(true); // Enter must not run it
        return showLocalized(mainStage, alert).orElse(cancel) == openAnyway;
    }

    /** Downloads a chat file into {@code dir} (the folder dialog is only in front of this; tests call it directly). */
    void downloadInto(Path dir, String messageId, String displayName) {
        if (conn == null) return;
        SimpleTransferRow row = new SimpleTransferRow("");
        row.nameProperty().bind(I18n.bind("transfer_download", displayName));
        row.setStatus("QUEUED");
        transfers.add(row);

        conn.startParallelDownload(
                messageId,
                dir,
                row::setProgress,
                row::setStatus,
                row::setFailed,
                savedPath -> {
                    localFiles.remember(messageId, savedPath); // Open / Show in folder, also after a restart
                    // attach local path to the message (enables thumbnail and the buttons)
                    ChatMessage msg = findMessageById(messageId);
                    if (msg != null) {
                        msg.localPathProperty().set(savedPath);
                        // refresh list cell rendering
                        if (chatListRef != null) chatListRef.refresh();
                    }
                },
                row.handle()
        );
    }

    /**
     * Connects and logs in off the FX thread (network, TLS handshake and the server's password hashing
     * all wait). On the first connection to a server, asks the user to trust its certificate and retries;
     * if the certificate changed, offers to trust the new one only after an explicit confirmation.
     * {@code password} is wiped once the attempt is finished.
     */
    private void connectInBackground(Stage stage, String host, int port, String user, char[] password,
                                     Button btnConnect, TextField tfUser, Label lblStatus) {
        btnConnect.setDisable(true);
        setStatus(lblStatus, "status_connecting");
        Thread t = new Thread(() -> {
            try {
                // The constructor wipes the array it gets; keep ours for a retry after trusting the server.
                ClientConnection c = new ClientConnection(host, port, user, password.clone(), knownServers);
                Arrays.fill(password, '\0');
                rememberLogin(new LastLogin(host, port, c.getUsername()));
                Platform.runLater(() -> {
                    btnConnect.setDisable(false);
                    attachConnection(c, tfUser, lblStatus);
                });
            } catch (Exception ex) {
                AppError err = AppError.from(ex);
                Platform.runLater(() -> {
                    btnConnect.setDisable(false);
                    boolean retry = switch (err.code()) {
                        case AppError.UNTRUSTED_SERVER -> askToTrust(stage, host, port, err.args().getFirst());
                        case AppError.SERVER_CERT_CHANGED ->
                                askToTrustChanged(stage, host, port, err.args().get(0), err.args().get(1));
                        default -> false;
                    };
                    if (retry) {
                        // The retry checks the certificate again: it must be exactly the one just trusted.
                        connectInBackground(stage, host, port, user, password, btnConnect, tfUser, lblStatus);
                        return;
                    }
                    Arrays.fill(password, '\0');
                    setStatus(lblStatus, "status_connect_failed", err);
                });
            }
        }, "connect");
        t.setDaemon(true);
        t.start();
    }

    /** Saves the login for the next start. Runs on the connect thread; a failure only loses the convenience. */
    private static void rememberLogin(LastLogin login) {
        try {
            login.save(LastLogin.defaultLocation());
        } catch (IOException | RuntimeException e) {
            System.err.println("Could not save the last login: " + e);
        }
    }

    /** Saves the picked language and theme for the next start. A failure only loses the convenience. */
    private static void rememberAppearance(ComboBox<String> langPicker, ComboBox<ThemeManager.ThemeOption> themePicker) {
        if (langPicker.getValue() == null || themePicker.getValue() == null) return;
        String language = langPicker.getValue().equals("العربية") ? "ar" : "en";
        try {
            new Appearance(language, themePicker.getValue().name()).save(Appearance.defaultLocation());
        } catch (IOException | RuntimeException e) {
            System.err.println("Could not save the language and theme: " + e);
        }
    }

    /** Shows the server's fingerprint and asks the user to trust it. Saves it if they do. */
    private boolean askToTrust(Stage stage, String host, int port, String fingerprint) {
        ButtonType trust = new ButtonType(I18n.get("trust_button"), ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType(I18n.get("cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);

        Label explain = new Label(I18n.get("trust_content"));
        explain.setWrapText(true);

        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, "", trust, cancel);
        alert.setTitle(I18n.get("trust_title"));
        alert.setHeaderText(I18n.get("trust_header", host + ":" + port));
        alert.getDialogPane().setContent(new VBox(10, explain, fingerprintBox(fingerprint)));
        alert.getDialogPane().setPrefWidth(620);
        Icons.on((Button) alert.getDialogPane().lookupButton(trust), Feather.SHIELD);

        if (showLocalized(stage, alert).orElse(cancel) != trust) return false;
        return saveTrust(host, port, fingerprint);
    }

    /**
     * The server presents a different certificate than the trusted one. Shows both fingerprints;
     * "Close" is the default, and the trust button stays disabled until the user confirms they
     * compared the new fingerprint with the server window. Saves the new one if they trust it.
     */
    private boolean askToTrustChanged(Stage stage, String host, int port, String newFingerprint, String oldFingerprint) {
        ButtonType trustNew = new ButtonType(I18n.get("cert_changed_trust"), ButtonBar.ButtonData.OTHER);
        ButtonType close = new ButtonType(I18n.get("close"), ButtonBar.ButtonData.CANCEL_CLOSE);

        Label explain = new Label(I18n.get("cert_changed_content"));
        explain.setWrapText(true);
        CheckBox compared = new CheckBox(I18n.get("cert_changed_confirm"));
        compared.setWrapText(true);

        Alert alert = new Alert(Alert.AlertType.WARNING, "", trustNew, close);
        alert.setTitle(I18n.get("cert_changed_title"));
        alert.setHeaderText(I18n.get("cert_changed_header", host + ":" + port));
        alert.getDialogPane().setContent(new VBox(8,
                explain,
                new Label(I18n.get("cert_changed_old")), fingerprintBox(oldFingerprint),
                new Label(I18n.get("cert_changed_new")), fingerprintBox(newFingerprint),
                compared));
        alert.getDialogPane().setPrefWidth(640);

        Button trustButton = Icons.on((Button) alert.getDialogPane().lookupButton(trustNew), Feather.SHIELD);
        trustButton.disableProperty().bind(compared.selectedProperty().not());
        trustButton.setDefaultButton(false);
        ((Button) alert.getDialogPane().lookupButton(close)).setDefaultButton(true); // Enter must not trust

        if (showLocalized(stage, alert).orElse(close) != trustNew || !compared.isSelected()) return false;
        return saveTrust(host, port, newFingerprint);
    }

    /** Read-only but selectable, so it can be copied; two monospace lines so all 32 bytes are visible. */
    private static TextArea fingerprintBox(String fingerprint) {
        TextArea fp = new TextArea(fingerprint);
        fp.setEditable(false);
        fp.setWrapText(true);
        fp.setPrefRowCount(2);
        fp.setStyle("-fx-font-family: monospace;");
        fp.setNodeOrientation(NodeOrientation.LEFT_TO_RIGHT);
        return fp;
    }

    private boolean saveTrust(String host, int port, String fingerprint) {
        try {
            knownServers.trust(host, port, fingerprint);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static <T> Optional<T> showLocalized(Stage owner, Dialog<T> dialog) {
        dialog.initOwner(owner);
        dialog.getDialogPane().setNodeOrientation(
                I18n.isRightToLeft() ? NodeOrientation.RIGHT_TO_LEFT : NodeOrientation.LEFT_TO_RIGHT);
        return dialog.showAndWait();
    }

    /** How long "Find servers" waits for answers. */
    private static final java.time.Duration FIND_WAIT = java.time.Duration.ofMillis(1500);

    /** Asks the LAN for servers off the FX thread, then lets the user pick one to fill host and port. */
    private void findServers(Stage stage, Button btnFind, TextField tfHost, TextField tfPort, PasswordField pfPassword) {
        btnFind.setDisable(true);
        btnFind.textProperty().bind(I18n.bind("find_searching"));
        Thread t = new Thread(() -> {
            List<Discovery.Found> found = List.of();
            AppError failure = null;
            try {
                found = ServerFinder.find(Discovery.PORT, FIND_WAIT);
            } catch (Exception e) {
                failure = AppError.from(e);
            }
            List<Discovery.Found> result = found;
            AppError error = failure;
            Platform.runLater(() -> {
                btnFind.textProperty().bind(I18n.bind("find_servers"));
                btnFind.setDisable(false);
                pickServer(stage, result, error).ifPresent(f -> {
                    tfHost.setText(f.host());
                    tfPort.setText(String.valueOf(f.port()));
                    pfPassword.requestFocus();
                });
            });
        }, "find-servers");
        t.setDaemon(true);
        t.start();
    }

    private Optional<Discovery.Found> pickServer(Stage stage, List<Discovery.Found> found, AppError error) {
        Dialog<Discovery.Found> dialog = new Dialog<>();
        dialog.setTitle(I18n.get("find_title"));
        ButtonType use = new ButtonType(I18n.get("find_use"), ButtonBar.ButtonData.OK_DONE);
        ButtonType close = new ButtonType(I18n.get("close"), ButtonBar.ButtonData.CANCEL_CLOSE);

        if (found.isEmpty()) {
            dialog.setHeaderText(I18n.get("find_none_header"));
            Label why = new Label(error != null
                    ? I18n.get("find_failed", I18n.error(error))
                    : I18n.get("find_none", String.valueOf(Discovery.PORT)));
            why.setWrapText(true);
            dialog.getDialogPane().setContent(why);
            dialog.getDialogPane().getButtonTypes().add(close);
            dialog.getDialogPane().setPrefWidth(480);
            showLocalized(stage, dialog);
            return Optional.empty();
        }

        dialog.setHeaderText(I18n.get("find_header", String.valueOf(found.size())));
        ListView<Discovery.Found> list = new ListView<>(FXCollections.observableArrayList(found));
        list.setPrefHeight(Math.min(6, found.size()) * 58 + 4);
        list.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(Discovery.Found f, boolean empty) {
                super.updateItem(f, empty);
                if (empty || f == null) {
                    setGraphic(null);
                    return;
                }
                // Separate labels so the address stays left-to-right inside an Arabic layout.
                Label name = new Label(f.name());
                name.setStyle("-fx-font-weight: bold;");
                Label address = new Label(f.host() + ":" + f.port());
                Label where = ServerFinder.isLoopback(f.host()) ? new Label(I18n.get("find_this_computer")) : null;
                address.setNodeOrientation(NodeOrientation.LEFT_TO_RIGHT);
                VBox box = new VBox(2, name, where == null ? address : new HBox(8, address, where));
                if (!f.sameVersion()) {
                    Label warn = new Label(I18n.get("find_other_version"));
                    warn.getStyleClass().add("danger");
                    warn.setStyle("-fx-text-fill: -color-danger-fg;");
                    box.getChildren().add(warn);
                }
                setGraphic(box);
            }
        });
        list.getSelectionModel().selectFirst();
        dialog.getDialogPane().setContent(list);
        dialog.getDialogPane().getButtonTypes().addAll(use, close);
        Icons.on((Button) dialog.getDialogPane().lookupButton(use), Feather.CHECK);
        dialog.getDialogPane().setPrefWidth(480);
        dialog.getDialogPane().lookupButton(use).disableProperty()
                .bind(list.getSelectionModel().selectedItemProperty().isNull());
        list.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && list.getSelectionModel().getSelectedItem() != null) {
                ((Button) dialog.getDialogPane().lookupButton(use)).fire();
            }
        });
        dialog.setResultConverter(b -> b == use ? list.getSelectionModel().getSelectedItem() : null);
        return showLocalized(stage, dialog);
    }

    /** Wires a freshly logged-in connection to the UI, then starts it. Runs on the FX thread. */
    private void attachConnection(ClientConnection c, TextField tfUser, Label lblStatus) {
        conn = c;
        connected.set(true);
        tfUser.setText(c.getUsername());
        setStatus(lblStatus, "status_connected", c.getUsername());
        settings.hide(); // back to the chats

        // `conn != c` guards ignore late callbacks from a connection that was replaced by a reconnect.
        c.setOnError(err -> {
            if (conn == c) setStatus(lblStatus, "status_error", err);
        });
        c.setOnUsers(users -> {
            if (conn == c) applyUsers(users);
        });
        c.setOnThreadMessages((peer, msgs) -> {
            if (conn != c) return;
            // A reply can arrive after the user switched to another chat: only show it if it is
            // still the open one, but its last message is still the right preview for `peer`.
            for (ChatMessage m : msgs) { // files already on this computer: buttons and previews
                if (m.typeProperty().get() == ChatMessage.Type.FILE && m.localPathProperty().get() == null) {
                    m.localPathProperty().set(localFiles.find(m.messageIdProperty().get()));
                }
            }
            if (peer.equals(activePeer)) chatMessages.setAll(msgs);
            updateLastPreviewFromThread(peer, msgs);
            resortUsers();
        });
        c.setOnMessageDeleted((peer, messageId) -> {
            if (conn != c) return;
            // Reload that chat: it shows the placeholder if open, and fixes its preview either way.
            c.requestThread(peer);
        });
        c.setOnIncomingText(evt -> {
            if (conn != c) return;
            long ms = FileTypeUtil.parseIsoMillis(evt.timestamp());
            bumpUnreadAndPreview(evt.fromUser(), "[MSG] " + I18n.get("preview_new_message"), ms);
            if (evt.fromUser().equals(activePeer)) c.requestThread(activePeer);
            // The push carries no text (only the server has it), so the notification can't leak it either.
            notifyNewMessage(evt.fromUser(), I18n.get("preview_new_message"));
            resortUsers();
        });
        c.setOnIncoming(fileEvt -> {
            if (conn != c) return;
            String from = fileEvt.fromUserProperty().get();
            String fileName = fileEvt.fileNameProperty().get();
            long ms = FileTypeUtil.parseIsoMillis(fileEvt.timestampProperty().get());
            bumpUnreadAndPreview(from, FileTypeUtil.previewPrefixForFile(fileName) + fileName, ms);
            if (from.equals(activePeer)) c.requestThread(activePeer);
            notifyNewMessage(from, I18n.get("notify_file", fileName));
            resortUsers();
        });

        c.start(); // only now, so no push arrives before the callbacks above are in place
        c.requestUsers();
    }

    private ChatMessage findMessageById(String messageId) {
        for (ChatMessage m : chatMessages) {
            if (m.messageIdProperty().get().equals(messageId)) return m;
        }
        return null;
    }

    private void applyUsers(List<String> onlineUsers) {
        // The server lists everyone online, including us; you can't chat with yourself.
        String me = conn == null ? null : conn.getUsername();
        List<String> usernames = onlineUsers.stream().filter(u -> !u.equals(me)).toList();
        Set<String> usernameSet = new HashSet<>(usernames);

        // Add new users that don't exist yet
        for (String username : usernames) {
            userIndex.computeIfAbsent(username, name -> {
                UserItem item = new UserItem(name);
                userItems.add(item);
                return item;
            });
        }

        // Remove users no longer present in the source list
        userItems.removeIf(item -> !usernameSet.contains(item.getUsername()));
        userIndex.keySet().retainAll(usernameSet);

        // After add/remove, sort once.
        resortUsers();
    }

    private void bumpUnreadAndPreview(String fromUser, String preview, long activityMillis) {
        // The sender may have logged in after our last user-list refresh: add them instead of
        // dropping the notification.
        UserItem item = userIndex.computeIfAbsent(fromUser, name -> {
            UserItem added = new UserItem(name);
            userItems.add(added);
            return added;
        });

        item.setLastPreview(preview);

        item.updateLastActivity(activityMillis);

        // Read only if it lands in the open chat while the window is in front, where the user sees it.
        if (!fromUser.equals(activePeer) || !windowInFront.getAsBoolean()) {
            item.bumpUnread();
        }
    }

    /**
     * A Windows notification for something new from {@code from}, unless the user is looking at
     * the app or turned notifications off. A burst from one sender gives one notification.
     */
    private void notifyNewMessage(String from, String text) {
        if (!notifyEnabled.get() || windowInFront.getAsBoolean()) return;
        long now = System.currentTimeMillis();
        Long last = lastNotified.get(from);
        if (last != null && now - last < NOTIFY_QUIET_MILLIS) return;
        lastNotified.put(from, now);
        notifier.show(from, from, text);
    }

    /** Clicking a notification: bring the window back and open that sender's chat. */
    private void openChatFromNotification(String peer) {
        Platform.runLater(() -> {
            mainStage.setIconified(false);
            mainStage.show();
            mainStage.toFront();
            mainStage.requestFocus();
            UserItem item = peer == null ? null : userIndex.get(peer);
            if (item != null) usersViewRef.getSelectionModel().select(item);
        });
    }

    @Override
    public void stop() {
        if (updates != null) updates.stop();
        if (notifier != null) notifier.close(); // removes the tray icon, so the JVM can exit
    }

    private void clearUnread(String user) {
        UserItem item = userIndex.get(user);
        if (item != null) item.clearUnread();
    }

    private void updateLastPreviewFromThread(String peer, List<ChatMessage> msgs) {
        if (peer == null) return;
        UserItem item = userIndex.get(peer);
        if (item == null || msgs == null) return;
        if (msgs.isEmpty()) { // every message deleted for me
            item.setLastPreview("");
            return;
        }

        ChatMessage last = msgs.get(msgs.size() - 1);

        String preview;
        if (last.typeProperty().get() == ChatMessage.Type.DELETED) {
            preview = I18n.get(last.outgoingProperty().get() ? "deleted_by_you" : "deleted_by_them");
        } else if (last.typeProperty().get() == ChatMessage.Type.TEXT) {
            String t = last.textProperty().get();
            preview = (t == null) ? "" : t.trim();
            if (preview.length() > 40) preview = preview.substring(0, 40) + "...";
            preview = "[MSG] " + preview;
        } else {
            String name = last.fileNameProperty().get();
            preview = FileTypeUtil.previewPrefixForFile(name) + name;
        }

        long ms = FileTypeUtil.parseIsoMillis(last.timestampProperty().get());
        item.setLastPreview(preview);
        item.updateLastActivity(ms);
    }

    private void refreshThreadIfActive(String peer) {
        if (conn == null || !peer.equals(activePeer)) return;
        conn.requestThread(peer);
    }

    /** A label whose text follows the current language. */
    private static Label label(String key) {
        Label l = new Label();
        l.textProperty().bind(I18n.bind(key));
        return l;
    }

    /** Shows a translated status; re-binding replaces the previous status and keeps it live on language change. */
    private static void setStatus(Label status, String key, Object... args) {
        status.textProperty().bind(I18n.bind(key, args));
    }

    /** Shows "Error: <translated reason>" (or another wrapper key); stays live on language change. */
    private static void setStatus(Label status, String key, AppError error) {
        status.textProperty().bind(Bindings.createStringBinding(
                () -> I18n.get(key, I18n.error(error)), I18n.bundleProperty()));
    }

    private static void applyOrientation(Parent root) {
        root.setNodeOrientation(I18n.isRightToLeft() ? NodeOrientation.RIGHT_TO_LEFT : NodeOrientation.LEFT_TO_RIGHT);
    }

    /** The ✕ button of a transfer row, shown only while that transfer can still be cancelled. */
    private static final class CancelCell extends TableCell<SimpleTransferRow, SimpleTransferRow> {
        private final Button button = Icons.on(new Button(), Feather.X);

        CancelCell() {
            button.getStyleClass().addAll("cancel-transfer", Styles.BUTTON_ICON, Styles.FLAT, Styles.DANGER);
            Tooltip tip = new Tooltip();
            tip.textProperty().bind(I18n.bind("cancel_transfer"));
            button.setTooltip(tip);
            button.accessibleTextProperty().bind(I18n.bind("cancel_transfer"));
            setAlignment(Pos.CENTER);
        }

        @Override
        protected void updateItem(SimpleTransferRow row, boolean empty) {
            super.updateItem(row, empty);
            button.visibleProperty().unbind();
            if (empty || row == null) {
                setGraphic(null);
                return;
            }
            button.visibleProperty().bind(row.cancellableProperty());
            button.setOnAction(e -> row.cancel());
            setGraphic(button);
        }
    }

    private static final class UserCell extends ListCell<UserItem> {
        @Override
        protected void updateItem(UserItem item, boolean empty) {
            super.updateItem(item, empty);

            if (empty || item == null) {
                setGraphic(null);
                setText(null);
                return;
            }

            Label name = new Label(item.getUsername());
            name.getStyleClass().add("user-name");

            Label time = new Label();
            time.textProperty().bind(item.lastTimeProperty());
            time.getStyleClass().add("user-time");

            HBox topLine = new HBox(8, name, new Region(), time);
            topLine.getStyleClass().add("user-topline");
            HBox.setHgrow(topLine.getChildren().get(1), Priority.ALWAYS);

            Label preview = new Label();
            preview.textProperty().bind(item.lastPreviewProperty());
            preview.getStyleClass().add("user-preview");

            VBox textBox = new VBox(2, topLine, preview);

            Label badge = new Label();
            badge.getStyleClass().add("badge");
            badge.textProperty().bind(item.unreadCountProperty().asString());
            badge.visibleProperty().bind(item.unreadCountProperty().greaterThan(0));
            badge.managedProperty().bind(badge.visibleProperty());

            HBox row = new HBox(10, textBox, badge);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("user-row");
            HBox.setHgrow(textBox, Priority.ALWAYS);

            setGraphic(row);
        }
    }

    private final class ChatBubbleCell extends ListCell<ChatMessage> {
        private static Node fileBadgeFor(String fileName) {
            Label b = new Label(switch (FileTypeUtil.kindOf(fileName)) {
                case IMAGE -> "IMG";
                case ARCHIVE -> "BOOK";
                case OTHER -> "FILE";
            });
            b.getStyleClass().add("file-badge");
            return b;
        }

        /**
         * One label per part instead of one joined string: mixing Latin/number parts with Arabic
         * in a single label lets the bidi algorithm reorder them ("6.0 B" became "B • ... 6.0").
         * The HBox itself flips with the node orientation, so the order stays readable in RTL.
         */
        private static Node metaLine(String... parts) {
            HBox line = new HBox(6);
            line.setAlignment(Pos.CENTER_LEFT);
            for (int i = 0; i < parts.length; i++) {
                if (i > 0) {
                    Label dot = new Label("•");
                    dot.getStyleClass().add("bubble-meta");
                    line.getChildren().add(dot);
                }
                Label part = new Label(parts[i]);
                part.getStyleClass().add("bubble-meta");
                line.getChildren().add(part);
            }
            return line;
        }

        /**
         * Open and Show in folder for a file that is on this computer (downloaded, or sent from
         * here); Download for one that can be downloaded; nothing otherwise.
         */
        private Node fileActions(ChatMessage item) {
            if (item.typeProperty().get() != ChatMessage.Type.FILE) return null;
            Path local = item.localPathProperty().get();
            HBox row = new HBox(6);
            row.getStyleClass().add("bubble-actions");
            if (local != null && Files.isRegularFile(local)) {
                Button open = bubbleButton("file_open", Feather.EXTERNAL_LINK);
                open.setOnAction(e -> openChatFile(item));
                Button show = bubbleButton("file_show_in_folder", Feather.FOLDER);
                show.setOnAction(e -> showChatFile(item));
                row.getChildren().addAll(open, show);
            } else if (item.downloadableProperty().get()) {
                Button download = bubbleButton("file_download", Feather.DOWNLOAD);
                download.setOnAction(e -> chooseAndDownload(mainStage, item.messageIdProperty().get(), item.fileNameProperty().get()));
                row.getChildren().add(download); // double-clicking the bubble still works too
            } else {
                return null;
            }
            return row;
        }

        private static Button bubbleButton(String key, Feather icon) {
            Button b = Icons.on(new Button(I18n.get(key)), icon); // cells re-render on a language switch
            b.getStyleClass().add(Styles.SMALL);
            b.setFocusTraversable(false);
            return b;
        }

        private static String humanSize(long bytes) {
            double b = bytes;
            String[] units = {"B", "KB", "MB", "GB"};
            int i = 0;
            while (b >= 1024 && i < units.length - 1) {
                b /= 1024;
                i++;
            }
            return String.format("%.1f %s", b, units[i]);
        }

        @Override
        protected void updateItem(ChatMessage item, boolean empty) {
            super.updateItem(item, empty);

            if (empty || item == null) {
                setGraphic(null);
                setText(null);
                setContextMenu(null);
                return;
            }

            boolean out = item.outgoingProperty().get();

            Label title = new Label(out ? I18n.get("bubble_you") : item.fromUserProperty().get());
            title.getStyleClass().add("bubble-title");

            Node bodyNode;
            Node meta;

            if (item.typeProperty().get() == ChatMessage.Type.DELETED) {
                // A placeholder where the message was, so the conversation still reads right.
                Label body = new Label(I18n.get(out ? "deleted_by_you" : "deleted_by_them"), Icons.of(Feather.SLASH));
                body.getStyleClass().add("deleted-text");
                bodyNode = body;
                meta = metaLine(I18n.messageTime(item.timestampProperty().get()));

            } else if (item.typeProperty().get() == ChatMessage.Type.TEXT) {
                Label body = new Label(item.textProperty().get());
                body.setWrapText(true);
                bodyNode = body;

                meta = metaLine(I18n.messageTime(item.timestampProperty().get()));

            } else {
                String fileName = item.fileNameProperty().get();

                // If it's an image and downloaded -> show thumbnail
                boolean isImage = FileTypeUtil.kindOf(fileName) == FileTypeUtil.Kind.IMAGE;
                Path local = item.localPathProperty().get();

                if (isImage && local != null && local.toFile().exists()) {
                    ImageView iv = new ImageView(new Image(local.toUri().toString(), 260, 260, true, true, true));
                    iv.setPreserveRatio(true);
                    iv.setSmooth(true);
                    iv.setFitWidth(260);
                    iv.setFitHeight(260);

                    Label caption = new Label(fileName);
                    caption.setWrapText(true);

                    VBox imgBox = new VBox(8, iv, caption);
                    bodyNode = imgBox;
                } else {
                    Label body = new Label(fileName);
                    body.setWrapText(true);

                    Node badge = fileBadgeFor(fileName);
                    HBox fileLine = new HBox(8, badge, body);
                    fileLine.setAlignment(Pos.CENTER_LEFT);
                    bodyNode = fileLine;
                }

                meta = metaLine(humanSize(item.fileSizeProperty().get()), I18n.messageTime(item.timestampProperty().get()));
            }

            Node actions = fileActions(item);
            VBox box = actions == null ? new VBox(4, title, bodyNode, meta) : new VBox(4, title, bodyNode, meta, actions);

            StackPane bubble = new StackPane(box);
            bubble.getStyleClass().add("bubble");
            bubble.getStyleClass().add(out ? "bubble-out" : "bubble-in");
            String found = searchState(item);
            if (found != null) bubble.getStyleClass().add(found.equals("current") ? "bubble-search-current" : "bubble-search-match");
            // Full date on hover; the bubble itself shows the short form.
            Tooltip.install(bubble, new Tooltip(I18n.messageTimeFull(item.timestampProperty().get())));

            if (item.typeProperty().get() == ChatMessage.Type.DELETED) bubble.getStyleClass().add("bubble-deleted");

            HBox row = new HBox(bubble);
            row.setAlignment(out ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);

            setGraphic(row);
            // Right-click: Delete... (the Delete key on a selected message does the same)
            MenuItem delete = new MenuItem(I18n.get("delete_menu"), Icons.of(Feather.TRASH_2));
            delete.setOnAction(e -> askToDelete(item));
            setContextMenu(new ContextMenu(delete));
        }
    }
}