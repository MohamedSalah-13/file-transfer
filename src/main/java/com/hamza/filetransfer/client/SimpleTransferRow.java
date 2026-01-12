package com.hamza.filetransfer.client;

import javafx.beans.property.*;

public class SimpleTransferRow {
    private final StringProperty name = new SimpleStringProperty();
    private final DoubleProperty progress = new SimpleDoubleProperty(0);
    private final StringProperty status = new SimpleStringProperty("STARTING");

    public SimpleTransferRow(String name) {
        this.name.set(name);
    }

    public StringProperty nameProperty() { return name; }
    public DoubleProperty progressProperty() { return progress; }
    public StringProperty statusProperty() { return status; }

    public void setProgress(long done, long total) {
        progress.set(total <= 0 ? 0 : (double) done / total);
    }

    public void setStatus(String s) { status.set(s); }
}
