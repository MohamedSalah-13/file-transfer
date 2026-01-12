package com.hamza.filetransfer.client;

import javafx.beans.property.*;

public class InboxFile {
    private final StringProperty fileId = new SimpleStringProperty();
    private final StringProperty fileName = new SimpleStringProperty();
    private final LongProperty size = new SimpleLongProperty();
    private final StringProperty fromUser = new SimpleStringProperty();
    private final StringProperty timestamp = new SimpleStringProperty();

    public InboxFile(String fileId, String fileName, long size, String fromUser, String timestamp) {
        this.fileId.set(fileId);
        this.fileName.set(fileName);
        this.size.set(size);
        this.fromUser.set(fromUser);
        this.timestamp.set(timestamp);
    }

    public StringProperty fileIdProperty() { return fileId; }
    public StringProperty fileNameProperty() { return fileName; }
    public LongProperty sizeProperty() { return size; }
    public StringProperty fromUserProperty() { return fromUser; }
    public StringProperty timestampProperty() { return timestamp; }
}
