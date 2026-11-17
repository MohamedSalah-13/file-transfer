package com.hamza.filetransfer.server;

import javafx.beans.property.*;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One upload as shown in the server window.
 *
 * <p>The server updates it from socket threads, but its JavaFX properties may be bound to
 * the UI, so every change is handed to {@link #deliverUpdatesOn(Executor) the update
 * executor} (the FX thread in {@code ServerApp}). Until one is set, changes apply on the
 * calling thread, which is what tests and a headless server want.
 * Read the properties only on that executor's thread.
 */
public class TransferSession {
    private final StringProperty fromUser = new SimpleStringProperty();
    private final StringProperty toUser = new SimpleStringProperty();
    private final StringProperty fileName = new SimpleStringProperty();
    private final LongProperty totalBytes = new SimpleLongProperty();
    private final LongProperty doneBytes = new SimpleLongProperty();
    private final DoubleProperty progress = new SimpleDoubleProperty(0);
    private final StringProperty status = new SimpleStringProperty("STARTING");

    private volatile Executor updates = Runnable::run;
    /** Latest byte count from the socket thread; progress updates are coalesced into one pending task. */
    private final AtomicLong latestDone = new AtomicLong();
    private final AtomicBoolean doneUpdateQueued = new AtomicBoolean();

    public TransferSession(String from, String to, String fileName, long totalBytes) {
        this.fromUser.set(from);
        this.toUser.set(to);
        this.fileName.set(fileName);
        this.totalBytes.set(totalBytes);
    }

    /**
     * Runs all later property changes on {@code executor}, e.g. {@code Platform::runLater}.
     * Call it before the session is shown, from the thread that is updating it.
     */
    public void deliverUpdatesOn(Executor executor) {
        updates = Objects.requireNonNull(executor);
    }

    /** Safe from any thread. A fast upload queues at most one pending progress update. */
    public void setDoneBytes(long v) {
        latestDone.set(v);
        if (doneUpdateQueued.compareAndSet(false, true)) {
            updates.execute(() -> {
                doneUpdateQueued.set(false);
                applyDoneBytes(latestDone.get());
            });
        }
    }

    /** Safe from any thread; applied in order with progress updates. */
    public void setStatus(String s) {
        updates.execute(() -> status.set(s));
    }

    private void applyDoneBytes(long v) {
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
}
