package com.hamza.filetransfer.server;

import javafx.beans.property.*;

public class TransferSession {
    private final StringProperty fromUser = new SimpleStringProperty();
    private final StringProperty toUser = new SimpleStringProperty();
    private final StringProperty fileName = new SimpleStringProperty();
    private final LongProperty totalBytes = new SimpleLongProperty();
    private final LongProperty doneBytes = new SimpleLongProperty();
    private final DoubleProperty progress = new SimpleDoubleProperty(0);
    private final StringProperty status = new SimpleStringProperty("STARTING");

    public TransferSession(String from, String to, String fileName, long totalBytes) {
        this.fromUser.set(from);
        this.toUser.set(to);
        this.fileName.set(fileName);
        this.totalBytes.set(totalBytes);
        setDoneBytes(0);
    }

    public void setDoneBytes(long v) {
        doneBytes.set(v);
        long total = totalBytes.get();
        progress.set(total <= 0 ? 0 : (double) v / total);
    }

    public StringProperty fromUserProperty() { return fromUser; }
    public StringProperty toUserProperty() { return toUser; }
    public StringProperty fileNameProperty() { return fileName; }
    public LongProperty totalBytesProperty() { return totalBytes; }
    public LongProperty doneBytesProperty() { return doneBytes; }
    public DoubleProperty progressProperty() { return progress; }
    public StringProperty statusProperty() { return status; }

    public void setStatus(String s) { status.set(s); }
}
