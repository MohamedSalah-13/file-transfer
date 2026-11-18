package com.hamza.filetransfer.client;

import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.*;

import java.util.Set;

public class SimpleTransferRow {
    /** Statuses of a transfer that is still running. */
    private static final Set<String> RUNNING = Set.of("STARTING", "QUEUED", "CONNECTING", "SENDING", "DOWNLOADING");

    private final StringProperty name = new SimpleStringProperty();
    private final DoubleProperty progress = new SimpleDoubleProperty(0);
    private final StringProperty status = new SimpleStringProperty("STARTING");
    private final ObjectProperty<AppError> failure = new SimpleObjectProperty<>();
    private final TransferHandle handle = new TransferHandle();
    /**
     * Running and not all bytes moved yet. Once an upload has sent everything the server may
     * already have delivered it, so cancelling then would only lie about it.
     */
    private final BooleanBinding cancellable = Bindings.createBooleanBinding(
            () -> RUNNING.contains(status.get()) && progress.get() < 1, status, progress);

    public SimpleTransferRow(String name) {
        this.name.set(name);
    }

    public StringProperty nameProperty() { return name; }
    public DoubleProperty progressProperty() { return progress; }
    public StringProperty statusProperty() { return status; }
    /** Why the transfer failed (translated for display), or null. */
    public ObjectProperty<AppError> failureProperty() { return failure; }
    /** Passed to the upload or download so the ✕ button can stop it. */
    public TransferHandle handle() { return handle; }
    public BooleanBinding cancellableProperty() { return cancellable; }

    public void setProgress(long done, long total) {
        progress.set(total <= 0 ? 0 : (double) done / total);
    }

    public void setStatus(String s) { status.set(s); }

    public void setFailed(AppError error) {
        failure.set(error);
        status.set("FAILED");
    }

    /** The ✕ button: stop it now; the transfer's thread reports CANCELLED when it has stopped. */
    public void cancel() {
        if (!cancellable.get()) return;
        status.set("CANCELLING");
        handle.cancel();
    }
}
