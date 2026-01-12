package com.hamza.filetransfer.client;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.*;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.cell.ProgressBarTableCell;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ClientApp extends Application {

    private final ObservableList<UserItem> userItems = FXCollections.observableArrayList();
    private final Map<String, UserItem> userIndex = new HashMap<>();
    private final ObservableList<ChatMessage> chatMessages = FXCollections.observableArrayList();
    private final ObservableList<SimpleTransferRow> transfers = FXCollections.observableArrayList();
    private ClientConnection conn;
    private String activePeer;
    private ListView<ChatMessage> chatListRef;
    
    @Override
    public void start(Stage stage) {
        ThemeManager.applyLight();

        TextField tfHost = new TextField("127.0.0.1");
        TextField tfPort = new TextField("5000");
        TextField tfUser = new TextField("user1");
        Button btnConnect = new Button("Connect");

        Label lblStatus = new Label("Not connected");

        ToggleButton themeToggle = new ToggleButton("Dark");
        themeToggle.setOnAction(e -> {
            ThemeManager.toggle();
            themeToggle.setSelected(ThemeManager.isDark());
            themeToggle.setText(ThemeManager.isDark() ? "Light" : "Dark");
        });

        HBox top = new HBox(
                10,
                new Label("Host:"), tfHost,
                new Label("Port:"), tfPort,
                new Label("User:"), tfUser,
                btnConnect,
                new Region(),
                themeToggle,
                lblStatus
        );
        HBox.setHgrow(top.getChildren().get(7), Priority.ALWAYS); // Region spacer
        top.setAlignment(Pos.CENTER_LEFT);
        top.getStyleClass().add("topbar");

        // ===== Left sidebar =====
        TextField tfSearchUsers = new TextField();
        tfSearchUsers.setPromptText("Search users...");

        FilteredList<UserItem> filteredUsers = new FilteredList<>(userItems, u -> true);
        tfSearchUsers.textProperty().addListener((obs, old, q) -> {
            String query = q == null ? "" : q.trim().toLowerCase();
            filteredUsers.setPredicate(u -> query.isEmpty() || u.getUsername().toLowerCase().contains(query));
        });

        // NEW: sort = unread desc, lastActivity desc, username
        SortedList<UserItem> sortedUsers = new SortedList<>(filteredUsers);
        sortedUsers.setComparator((a, b) -> {
            int unread = Integer.compare(b.unreadCountProperty().get(), a.unreadCountProperty().get());
            if (unread != 0) return unread;

            int activity = Long.compare(b.lastActivityMillisProperty().get(), a.lastActivityMillisProperty().get());
            if (activity != 0) return activity;

            return a.getUsername().compareToIgnoreCase(b.getUsername());
        });

        ListView<UserItem> usersView = new ListView<>(sortedUsers);
        usersView.setPrefWidth(300);
        usersView.setCellFactory(lv -> new UserCell());

        Button btnRefreshUsers = new Button("Refresh");

        VBox left = new VBox(10, new Label("Online"), tfSearchUsers, usersView, btnRefreshUsers);
        left.getStyleClass().add("sidebar");

        // ===== Center chat surface =====
        Label chatTitle = new Label("Select a user");
        chatTitle.getStyleClass().add("chat-title");

        Label chatSub = new Label("Files are shown like chat messages.");
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
        tfMessage.setPromptText("Type a message...");

        Button btnSendText = new Button("Send Text");

        btnSendText.setOnAction(a -> {
            if (conn == null || activePeer == null) return;
            String text = tfMessage.getText();
            tfMessage.clear();

            Thread t = new Thread(() -> {
                try {
                    conn.sendText(activePeer, text);
                    Platform.runLater(() -> {
                        try {
                            conn.requestThread(activePeer);
                        } catch (Exception ignored) {
                        }
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> {/* show status */});
                }
            }, "send-text");
            t.setDaemon(true);
            t.start();
        });

        Button btnChoose = new Button("Attach");
        Label lblChosen = new Label("No file");
        Button btnSend = new Button("Send");

        final Path[] chosen = new Path[1];
        btnChoose.setOnAction(a -> {
            FileChooser fc = new FileChooser();
            var file = fc.showOpenDialog(stage);
            if (file != null) {
                chosen[0] = file.toPath();
                lblChosen.setText(chosen[0].getFileName().toString());
            }
        });

        btnSend.setOnAction(a -> {
            if (conn == null) return;
            if (activePeer == null) {
                // لازم تختار شخص
                return;
            }
            if (chosen[0] == null) return;

            SimpleTransferRow row = new SimpleTransferRow("SEND " + chosen[0].getFileName() + " -> " + activePeer);
            row.setStatus("SENDING");
            transfers.add(row);

            Thread t = new Thread(() -> {
                try {
                    conn.sendFile(activePeer, chosen[0], row::setProgress);
                    Platform.runLater(() -> {
                        row.setStatus("DONE");
                        // تحديث المحادثة (أبسط MVP: نعمل requestThread)
                        try {
                            conn.requestThread(activePeer);
                        } catch (Exception ignored) {
                        }
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> row.setStatus("FAILED: " + ex.getMessage()));
                }
            }, "send-task");
            t.setDaemon(true);
            t.start();
        });

        HBox composer = new HBox(10, btnChoose, lblChosen, tfMessage, btnSendText, btnSend);
        HBox.setHgrow(tfMessage, Priority.ALWAYS);
        composer.getStyleClass().add("composer");

        // Transfers table (كما هو عندك سابقاً) — أبقيه كما هو
        TableView<SimpleTransferRow> transfersTable = new TableView<>(transfers);
        transfersTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);

        TableColumn<SimpleTransferRow, String> tName = new TableColumn<>("Transfer");
        tName.setCellValueFactory(c -> c.getValue().nameProperty());

        TableColumn<SimpleTransferRow, Double> tProg = new TableColumn<>("Progress");
        tProg.setCellValueFactory(c -> c.getValue().progressProperty().asObject());
        tProg.setCellFactory(tc -> new javafx.scene.control.cell.ProgressBarTableCell<>());

        TableColumn<SimpleTransferRow, String> tStatus = new TableColumn<>("Status");
        tStatus.setCellValueFactory(c -> c.getValue().statusProperty());

        transfersTable.getColumns().addAll(tName, tProg, tStatus);

        VBox right = new VBox(10, new Label("Transfers"), transfersTable, new Separator(), new Label("Send"), composer);
        right.getStyleClass().add("card");
        right.setPrefWidth(420);

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
            if (selected == null || conn == null) return;

            activePeer = selected.getUsername();
            selected.clearUnread();

            // Force list refresh sorting after unread cleared
            userItems.setAll(userItems);

            try {
                conn.requestThread(activePeer);
            } catch (Exception ignored) {
            }
        });

        // connect logic: add thread callbacks
        btnConnect.setOnAction(a -> {
            try {
                conn = new ClientConnection(tfHost.getText().trim(), Integer.parseInt(tfPort.getText().trim()), tfUser.getText().trim());
                lblStatus.setText("Connected");

                conn.setOnError(msg -> lblStatus.setText("Error: " + msg));
                conn.setOnUsers(this::applyUsers);
                conn.setOnThreadMessages(msgs -> {
                    chatMessages.setAll(msgs);
                    updateLastPreviewFromThread(activePeer, msgs);
                    userItems.setAll(userItems); // re-sort after activity change
                });

                conn.setOnIncomingText(evt -> {
                    long ms = FileTypeUtil.parseIsoMillis(evt.timestamp());
                    bumpUnreadAndPreview(evt.fromUser(), "[MSG] Message", ms);
                    if (activePeer != null && activePeer.equals(evt.fromUser())) {
                        clearUnread(activePeer);
                        try {
                            conn.requestThread(activePeer);
                        } catch (Exception ignored) {
                        }
                    }
                    userItems.setAll(userItems);
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
                    userItems.setAll(userItems);
                });
                // ---------------------------------
                conn.requestUsers();

            } catch (Exception ex) {
                lblStatus.setText("Connect failed: " + ex.getMessage());
            }
        });

        btnRefreshUsers.setOnAction(a -> {
            if (conn == null) return;
            try {
                conn.requestUsers();
            } catch (Exception ex) {
                lblStatus.setText(ex.getMessage());
            }
        });

        Scene scene = new Scene(root, 1280, 720);
        scene.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());

        stage.setTitle("File Transfer Chat - Client");
        stage.setScene(scene);
        stage.show();
    }

    private void chooseAndDownload(Stage stage, String messageId, String displayName) {
        if (conn == null) return;

        DirectoryChooser dc = new DirectoryChooser();
        dc.setTitle("Choose download folder");
        var dir = dc.showDialog(stage);
        if (dir == null) return;

        SimpleTransferRow row = new SimpleTransferRow("DOWNLOAD " + displayName);
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
        for (String u : usernames) {
            userIndex.computeIfAbsent(u, key -> {
                UserItem item = new UserItem(key);
                userItems.add(item);
                return item;
            });
        }

        userItems.removeIf(item -> !usernames.contains(item.getUsername()));
        userIndex.keySet().retainAll(usernames);

        // trigger sort update
        userItems.setAll(userItems);
    }

    private void bumpUnreadAndPreview(String fromUser, String preview, long activityMillis) {
        UserItem item = userIndex.get(fromUser);
        if (item == null) return;

        item.setLastPreview(preview);

        String timeLabel = FileTypeUtil.formatTime(activityMillis);
        item.updateLastActivity(activityMillis, timeLabel);

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
        item.updateLastActivity(ms, FileTypeUtil.formatTime(ms));
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
        @Override
        protected void updateItem(ChatMessage item, boolean empty) {
            super.updateItem(item, empty);

            if (empty || item == null) {
                setGraphic(null);
                setText(null);
                return;
            }

            boolean out = item.outgoingProperty().get();

            Label title = new Label(out ? "You" : item.fromUserProperty().get());
            title.getStyleClass().add("bubble-title");

            Node bodyNode;
            Label meta;

            if (item.typeProperty().get() == ChatMessage.Type.TEXT) {
                Label body = new Label(item.textProperty().get());
                body.setWrapText(true);
                bodyNode = body;

                meta = new Label(item.timestampProperty().get());
                meta.getStyleClass().add("bubble-meta");

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

                String metaText = humanSize(item.fileSizeProperty().get())
                        + "  •  " + item.timestampProperty().get()
                        + (item.downloadableProperty().get() ? "  •  double-click to download" : "");
                meta = new Label(metaText);
                meta.getStyleClass().add("bubble-meta");
            }

            VBox box = new VBox(4, title, bodyNode, meta);

            StackPane bubble = new StackPane(box);
            bubble.getStyleClass().add("bubble");
            bubble.getStyleClass().add(out ? "bubble-out" : "bubble-in");

            HBox row = new HBox(bubble);
            row.setAlignment(out ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);

            setGraphic(row);
        }

        private static Node fileBadgeFor(String fileName) {
            Label b = new Label(switch (FileTypeUtil.kindOf(fileName)) {
                case IMAGE -> "IMG";
                case ARCHIVE -> "BOOK";
                case OTHER -> "FILE";
            });
            b.getStyleClass().add("file-badge");
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
    }
}
