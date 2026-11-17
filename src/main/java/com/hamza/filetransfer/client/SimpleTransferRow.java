package com.hamza.filetransfer.client;

import javafx.beans.property.*;

public class SimpleTransferRow {
    private final StringProperty name = new SimpleStringProperty();
    private final DoubleProperty progress = new SimpleDoubleProperty(0);
    private final StringProperty status = new SimpleStringProperty("STARTING");
    private final ObjectProperty<AppError> failure = new SimpleObjectProperty<>();

    public SimpleTransferRow(String name) {
        this.name.set(name);
    }

    public StringProperty nameProperty() { return name; }
    public DoubleProperty progressProperty() { return progress; }
    public StringProperty statusProperty() { return status; }
    /** Why the transfer failed (translated for display), or null. */
    public ObjectProperty<AppError> failureProperty() { return failure; }

    public void setProgress(long done, long total) {
        progress.set(total <= 0 ? 0 : (double) done / total);
    }

    public void setStatus(String s) { status.set(s); }

    public void setFailed(AppError error) {
        failure.set(error);
        status.set("FAILED");
    }
}
