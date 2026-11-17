package com.hamza.filetransfer.client;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
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
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.nio.file.Path;
import java.util.*;

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
    private String activePeer;
    private ListView<ChatMessage> chatListRef;

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
    public void start(Stage stage) {
        I18n.setLanguage("en"); // Default language
        ThemeManager.applyTheme(ThemeManager.getThemes().getFirst());

        TextField tfHost = new TextField("127.0.0.1");
        TextField tfPort = new TextField("5000");
        TextField tfUser = new TextField("user1");
        PasswordField pfPassword = new PasswordField();
        pfPassword.promptTextProperty().bind(I18n.bind("password_prompt"));
        Button btnConnect = new Button();
        btnConnect.textProperty().bind(I18n.bind("connect"));

        Label lblStatus = label("status_not_connected");

        // Language Picker (language names are shown in their own language on purpose)
        ComboBox<String> langPicker = new ComboBox<>(FXCollections.observableArrayList("English", "العربية"));
        langPicker.getSelectionModel().selectFirst();
        langPicker.setOnAction(e -> {
            String selected = langPicker.getValue();
            I18n.setLanguage(selected.equals("العربية") ? "ar" : "en");
            applyOrientation(stage.getScene().getRoot());
            if (chatListRef != null) chatListRef.refresh(); // bubbles read translations when rendered
        });

        // Theme Picker
        ComboBox<ThemeManager.ThemeOption> themePicker = new ComboBox<>(ThemeManager.getThemes());
        themePicker.getSelectionModel().selectFirst();
        themePicker.setOnAction(e -> {
            ThemeManager.applyTheme(themePicker.getValue());
            // Re-apply custom app styles to ensure they override theme defaults if necessary
            String css = getClass().getResource("/styles/app.css").toExternalForm();
            if (stage.getScene() != null && !stage.getScene().getStylesheets().contains(css)) {
                stage.getScene().getStylesheets().add(css);
            }
        });

        Button btnToggleRight = new Button();

        tfHost.setPrefColumnCount(9);
        tfPort.setPrefColumnCount(4);
        tfUser.setPrefColumnCount(8);
        pfPassword.setPrefColumnCount(14);

        // Two groups that wrap onto a second line when the window is too narrow,
        // instead of truncating labels to "..." (Arabic labels are longer).
        HBox connectionGroup = new HBox(10,
                label("host"), tfHost,
                label("port"), tfPort,
                label("user"), tfUser,
                pfPassword,
                btnConnect, lblStatus);
        HBox settingsGroup = new HBox(10,
                label("language"), langPicker,
                label("theme"), themePicker,
                btnToggleRight);
        for (HBox group : List.of(connectionGroup, settingsGroup)) {
            group.setAlignment(Pos.CENTER_LEFT);
            for (Node n : group.getChildren()) {
                if (!(n instanceof TextInputControl) && n instanceof Region r) r.setMinWidth(Region.USE_PREF_SIZE);
            }
        }
        FlowPane top = new FlowPane(24, 8, connectionGroup, settingsGroup);
        top.setAlignment(Pos.CENTER_LEFT);
        top.getStyleClass().add("topbar");

        // ===== Left sidebar =====
        TextField tfSearchUsers = new TextField();
        tfSearchUsers.promptTextProperty().bind(I18n.bind("search_prompt"));

        FilteredList<UserItem> filteredUsers = new FilteredList<>(userItems, u -> true);
        tfSearchUsers.textProperty().addListener((obs, old, q) -> {
            String query = q == null ? "" : q.trim().toLowerCase();
            filteredUsers.setPredicate(u -> query.isEmpty() || u.getUsername().toLowerCase().contains(query));
        });

        // IMPORTANT CHANGE:
        // Instead of SortedList + "setAll" hack, we keep a FilteredList and sort userItems in-place using resortUsers().
        ListView<UserItem> usersView = new ListView<>(filteredUsers);
        usersView.setPrefWidth(300);
        usersView.setCellFactory(lv -> new UserCell());

        Button btnRefreshUsers = new Button();
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
        chatList.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                ChatMessage m = chatList.getSelectionModel().getSelectedItem();
                if (m != null && m.typeProperty().get() == ChatMessage.Type.FILE && m.downloadableProperty().get()) {
                    chooseAndDownload(stage, m.messageIdProperty().get(), m.fileNameProperty().get());
                }
            }
        });

        HBox chatHeader = new HBox(10, chatTitle, new Region());
        HBox.setHgrow(chatHeader.getChildren().get(1), Priority.ALWAYS);

        VBox center = new VBox(10, chatHeader, chatSub, chatList);
        center.getStyleClass().add("chat-surface");

        TextField tfMessage = new TextField();
        tfMessage.promptTextProperty().bind(I18n.bind("type_message"));

        Button btnSendText = new Button();
        btnSendText.textProperty().bind(Bindings.concat("✅ ", I18n.bind("send_text")));

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
                    Platform.runLater(() -> setStatus(lblStatus, "status_error", ex.getMessage()));
                }
            }, "send-text");
            t.setDaemon(true);
            t.start();
        });

        Button btnChoose = new Button();
        btnChoose.textProperty().bind(Bindings.concat("📎 ", I18n.bind("attach")));
        ObjectProperty<Path> chosen = new SimpleObjectProperty<>();
        Label lblChosen = new Label();
        lblChosen.textProperty().bind(Bindings.createStringBinding(
                () -> chosen.get() == null ? I18n.get("no_file") : chosen.get().getFileName().toString(),
                chosen, I18n.bundleProperty()));
        Button btnSend = new Button();
        btnSend.textProperty().bind(Bindings.concat("✉ ", I18n.bind("send_file")));

        btnChoose.setOnAction(a -> {
            FileChooser fc = new FileChooser();
            fc.setTitle(I18n.get("choose_file"));
            var file = fc.showOpenDialog(stage);
            if (file != null) {
                chosen.set(file.toPath());
            }
        });

        btnSend.setOnAction(a -> {
            if (conn == null) return;
            if (activePeer == null) {
                // لازم تختار شخص
                return;
            }
            if (chosen.get() == null) return;

            // capture now: the selection may change before the thread runs
            String peer = activePeer;
            Path file = chosen.get();

            SimpleTransferRow row = new SimpleTransferRow("");
            row.nameProperty().bind(I18n.bind("transfer_send", file.getFileName().toString(), peer));
            row.setStatus("SENDING");
            transfers.add(row);

            Thread t = new Thread(() -> {
                try {
                    conn.sendFile(peer, file, row::setProgress);
                    Platform.runLater(() -> {
                        row.setStatus("DONE");
                        refreshThreadIfActive(peer);
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> row.setStatus("FAILED: " + ex.getMessage()));
                }
            }, "send-task");
            t.setDaemon(true);
            t.start();
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
        // Rows keep language-neutral status codes ("SENDING", "FAILED: ..."); translate for display only.
        tStatus.setCellValueFactory(c -> Bindings.createStringBinding(
                () -> I18n.transferStatus(c.getValue().statusProperty().get()),
                c.getValue().statusProperty(), I18n.bundleProperty()));

        transfersTable.getColumns().addAll(tName, tProg, tStatus);
        transfersTable.setPlaceholder(label("no_transfers"));

        VBox right = new VBox(10, label("transfers"), transfersTable);
        right.getStyleClass().add("card");
        right.setPrefWidth(420);

        btnToggleRight.textProperty().bind(Bindings.when(right.visibleProperty())
                .then(I18n.bind("hide_transfers"))
                .otherwise(I18n.bind("show_transfers")));
        btnToggleRight.setOnAction(e -> {
            boolean show = !right.isVisible();
            right.setVisible(show);
            right.setManaged(show);
        });

        center.getChildren().addAll(label("send"), composer);
        // ===== Layout =====
        BorderPane root = new BorderPane();
        root.setTop(top);

        // IMPORTANT: لا تمسح الـtopbar عندك، فقط اتركه كما هو.
        // ضع المحتوى هنا:
        BorderPane content = new BorderPane();
        content.setLeft(left);
        content.setCenter(center);
        content.setRight(right);
        BorderPane.setMargin(content, new Insets(12));
        root.setCenter(content);
        
        

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

            try {
                conn.requestThread(activePeer);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });

        // connect logic: add thread callbacks
        btnConnect.setOnAction(a -> {
            try {
                if (conn != null) { // reconnect: drop the old session first
                    try { conn.close(); } catch (Exception ignored) { }
                    conn = null;
                }
                char[] password = pfPassword.getText().toCharArray();
                pfPassword.clear();
                conn = new ClientConnection(tfHost.getText().trim(), Integer.parseInt(tfPort.getText().trim()),
                        tfUser.getText().trim(), password);
                tfUser.setText(conn.getUsername());
                setStatus(lblStatus, "status_connected", conn.getUsername());

                conn.setOnError(msg -> setStatus(lblStatus, "status_error", msg));
                conn.setOnUsers(this::applyUsers);
                conn.setOnThreadMessages(msgs -> {
                    chatMessages.setAll(msgs);
                    updateLastPreviewFromThread(activePeer, msgs);

                    // Activity changed -> resort (no clearing).
                    resortUsers();
                });

                conn.setOnIncomingText(evt -> {
                    long ms = FileTypeUtil.parseIsoMillis(evt.timestamp());
                    bumpUnreadAndPreview(evt.fromUser(), "[MSG] " + I18n.get("preview_new_message"), ms);

                    if (activePeer != null && activePeer.equals(evt.fromUser())) {
                        clearUnread(activePeer);
                        try {
                            conn.requestThread(activePeer);
                        } catch (Exception ignored) {
                        }
                    }

                    resortUsers();
                });

                conn.setOnIncoming(fileEvt -> {
                    String from = fileEvt.fromUserProperty().get();
                    String fileName = fileEvt.fileNameProperty().get();
                    long ms = FileTypeUtil.parseIsoMillis(fileEvt.timestampProperty().get());

                    bumpUnreadAndPreview(from, FileTypeUtil.previewPrefixForFile(fileName) + fileName, ms);

                    if (activePeer != null && activePeer.equals(from)) {
                        clearUnread(activePeer);
                        try {
                            conn.requestThread(activePeer);
                        } catch (Exception ignored) {
                        }
                    }

                    resortUsers();
                });
                // ---------------------------------
                conn.requestUsers();

            } catch (Exception ex) {
                setStatus(lblStatus, "status_connect_failed", ex.getMessage());
            }
        });

        btnRefreshUsers.setOnAction(a -> {
            if (conn == null) return;
            try {
                conn.requestUsers();
            } catch (Exception ex) {
                setStatus(lblStatus, "status_error", ex.getMessage());
            }
        });

        Scene scene = new Scene(root, 1280, 720);
        scene.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());
        applyOrientation(root);

        stage.titleProperty().bind(I18n.bind("app_title"));
        stage.setScene(scene);
        stage.show();
    }

    private void chooseAndDownload(Stage stage, String messageId, String displayName) {
        if (conn == null) return;

        DirectoryChooser dc = new DirectoryChooser();
        dc.setTitle(I18n.get("choose_download_folder"));
        var dir = dc.showDialog(stage);
        if (dir == null) return;

        SimpleTransferRow row = new SimpleTransferRow("");
        row.nameProperty().bind(I18n.bind("transfer_download", displayName));
        row.setStatus("QUEUED");
        transfers.add(row);

        conn.startParallelDownload(
                messageId,
                dir.toPath(),
                row::setProgress,
                row::setStatus,
                savedPath -> {
                    // attach local path to the message (enables thumbnail)
                    ChatMessage msg = findMessageById(messageId);
                    if (msg != null) {
                        msg.localPathProperty().set(savedPath);
                        // refresh list cell rendering
                        if (chatListRef != null) chatListRef.refresh();
                    }
                }
        );
    }

    private ChatMessage findMessageById(String messageId) {
        for (ChatMessage m : chatMessages) {
            if (m.messageIdProperty().get().equals(messageId)) return m;
        }
        return null;
    }

    private void applyUsers(List<String> usernames) {
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
        UserItem item = userIndex.get(fromUser);
        if (item == null) return;

        item.setLastPreview(preview);

        item.updateLastActivity(activityMillis);

        if (activePeer == null || !activePeer.equals(fromUser)) {
            item.bumpUnread();
        }
    }

    private void clearUnread(String user) {
        UserItem item = userIndex.get(user);
        if (item != null) item.clearUnread();
    }

    private void updateLastPreviewFromThread(String peer, List<ChatMessage> msgs) {
        if (peer == null) return;
        UserItem item = userIndex.get(peer);
        if (item == null || msgs == null || msgs.isEmpty()) return;

        ChatMessage last = msgs.get(msgs.size() - 1);

        String preview;
        if (last.typeProperty().get() == ChatMessage.Type.TEXT) {
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
        try {
            conn.requestThread(peer);
        } catch (Exception ignored) {
        }
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

    private static void applyOrientation(Parent root) {
        root.setNodeOrientation(I18n.isRightToLeft() ? NodeOrientation.RIGHT_TO_LEFT : NodeOrientation.LEFT_TO_RIGHT);
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

    private static final class ChatBubbleCell extends ListCell<ChatMessage> {
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
                return;
            }

            boolean out = item.outgoingProperty().get();

            Label title = new Label(out ? I18n.get("bubble_you") : item.fromUserProperty().get());
            title.getStyleClass().add("bubble-title");

            Node bodyNode;
            Node meta;

            if (item.typeProperty().get() == ChatMessage.Type.TEXT) {
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

                meta = item.downloadableProperty().get()
                        ? metaLine(humanSize(item.fileSizeProperty().get()), I18n.messageTime(item.timestampProperty().get()), I18n.get("bubble_double_click"))
                        : metaLine(humanSize(item.fileSizeProperty().get()), I18n.messageTime(item.timestampProperty().get()));
            }

            VBox box = new VBox(4, title, bodyNode, meta);

            StackPane bubble = new StackPane(box);
            bubble.getStyleClass().add("bubble");
            bubble.getStyleClass().add(out ? "bubble-out" : "bubble-in");
            // Full date on hover; the bubble itself shows the short form.
            Tooltip.install(bubble, new Tooltip(I18n.messageTimeFull(item.timestampProperty().get())));

            HBox row = new HBox(bubble);
            row.setAlignment(out ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);

            setGraphic(row);
        }
    }
}