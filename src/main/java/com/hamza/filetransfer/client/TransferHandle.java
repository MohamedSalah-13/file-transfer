package com.hamza.filetransfer.client;

import java.io.Closeable;
import java.io.IOException;

/**
 * Lets the UI stop one upload or download. {@link #cancel()} closes the transfer's own socket, so a
 * blocked read or write fails at once; the transfer then reports {@link TransferCancelledException}
 * instead of an error. The server treats it like a dropped connection: it deletes the half-received
 * file and records no message. The CONTROL connection and other transfers are not touched.
 */
public final class TransferHandle {

    private boolean cancelled;   // guarded by this
    private Closeable resource;  // guarded by this

    /** Stops the transfer. Safe from any thread, before, during or after it runs. */
    public void cancel() {
        Closeable toClose;
        synchronized (this) {
            cancelled = true;
            toClose = resource;
        }
        closeQuietly(toClose);
    }

    public synchronized boolean isCancelled() {
        return cancelled;
    }

    /** The socket {@link #cancel()} should close. If already cancelled, closes it now. */
    void attach(Closeable socket) {
        boolean closeNow;
        synchronized (this) {
            resource = socket;
            closeNow = cancelled;
        }
        if (closeNow) closeQuietly(socket);
    }

    /** Throws {@link TransferCancelledException} if {@link #cancel()} was called. */
    void throwIfCancelled() throws TransferCancelledException {
        if (isCancelled()) throw new TransferCancelledException();
    }

    private static void closeQuietly(Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException ignored) {
            // closing is the whole point; nothing else to do
        }
    }

    /** The transfer stopped because the user cancelled it (not an error). */
    public static final class TransferCancelledException extends IOException {
        public TransferCancelledException() {
            super("Transfer cancelled");
        }
    }
}
