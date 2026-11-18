package com.hamza.filetransfer.client;

import javafx.beans.property.*;

import java.nio.file.Path;

public class ChatMessage {

    /** DELETED: deleted for everyone by its sender; only who and when are left. */
    public enum Type { TEXT, FILE, DELETED }

    private final StringProperty messageId = new SimpleStringProperty();
    private final ObjectProperty<Type> type = new SimpleObjectProperty<>(Type.FILE);

    private final BooleanProperty outgoing = new SimpleBooleanProperty(false);
    private final StringProperty fromUser = new SimpleStringProperty();
    private final StringProperty toUser = new SimpleStringProperty();
    private final StringProperty timestamp = new SimpleStringProperty();

    // TEXT
    private final StringProperty text = new SimpleStringProperty("");

    // FILE
    private final StringProperty fileName = new SimpleStringProperty("");
    private final LongProperty fileSize = new SimpleLongProperty(0);
    private final BooleanProperty downloadable = new SimpleBooleanProperty(false);

    // NEW: local downloaded file path (for thumbnails)
    private final ObjectProperty<Path> localPath = new SimpleObjectProperty<>(null);

    private ChatMessage() {}

    public static ChatMessage text(String messageId, boolean outgoing, String fromUser, String toUser,
                                   String timestamp, String text) {
        ChatMessage m = new ChatMessage();
        m.messageId.set(messageId);
        m.type.set(Type.TEXT);
        m.outgoing.set(outgoing);
        m.fromUser.set(fromUser);
        m.toUser.set(toUser);
        m.timestamp.set(timestamp);
        m.text.set(text);
        return m;
    }

    public static ChatMessage file(String messageId, boolean outgoing, String fromUser, String toUser,
                                   String timestamp, String fileName, long fileSize, boolean downloadable) {
        ChatMessage m = new ChatMessage();
        m.messageId.set(messageId);
        m.type.set(Type.FILE);
        m.outgoing.set(outgoing);
        m.fromUser.set(fromUser);
        m.toUser.set(toUser);
        m.timestamp.set(timestamp);
        m.fileName.set(fileName);
        m.fileSize.set(fileSize);
        m.downloadable.set(downloadable);
        return m;
    }

    public static ChatMessage deleted(String messageId, boolean outgoing, String fromUser, String toUser, String timestamp) {
        ChatMessage m = new ChatMessage();
        m.messageId.set(messageId);
        m.type.set(Type.DELETED);
        m.outgoing.set(outgoing);
        m.fromUser.set(fromUser);
        m.toUser.set(toUser);
        m.timestamp.set(timestamp);
        return m;
    }

    public StringProperty messageIdProperty() { return messageId; }
    public ObjectProperty<Type> typeProperty() { return type; }
    public BooleanProperty outgoingProperty() { return outgoing; }
    public StringProperty fromUserProperty() { return fromUser; }
    public StringProperty toUserProperty() { return toUser; }
    public StringProperty timestampProperty() { return timestamp; }

    public StringProperty textProperty() { return text; }

    public StringProperty fileNameProperty() { return fileName; }
    public LongProperty fileSizeProperty() { return fileSize; }
    public BooleanProperty downloadableProperty() { return downloadable; }

    public ObjectProperty<Path> localPathProperty() { return localPath; }
}
