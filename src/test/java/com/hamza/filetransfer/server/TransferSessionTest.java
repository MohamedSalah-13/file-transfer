package com.hamza.filetransfer.server;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;

class TransferSessionTest {

    /** Stands in for the FX thread: tasks run only when the test drains the queue. */
    static final class ManualExecutor implements Executor {
        final Queue<Runnable> tasks = new ArrayDeque<>();
        @Override public synchronized void execute(Runnable r) { tasks.add(r); }
        synchronized int drain() {
            int n = 0;
            for (Runnable r; (r = tasks.poll()) != null; n++) r.run();
            return n;
        }
    }

    @Test
    void appliesDirectlyWithoutExecutor() {
        TransferSession s = new TransferSession("a", "b", "f.bin", 200);
        s.setDoneBytes(50);
        s.setStatus("RECEIVING");
        assertEquals(50, s.doneBytesProperty().get());
        assertEquals(0.25, s.progressProperty().get(), 1e-9);
        assertEquals("RECEIVING", s.statusProperty().get());
    }

    @Test
    void changesWaitForTheExecutorThread() {
        ManualExecutor ui = new ManualExecutor();
        TransferSession s = new TransferSession("a", "b", "f.bin", 100);
        s.deliverUpdatesOn(ui);

        s.setDoneBytes(40);
        s.setStatus("STORED");
        assertEquals(0, s.doneBytesProperty().get(), "updated off the executor thread");
        assertEquals("STARTING", s.statusProperty().get());

        ui.drain();
        assertEquals(40, s.doneBytesProperty().get());
        assertEquals(0.4, s.progressProperty().get(), 1e-9);
        assertEquals("STORED", s.statusProperty().get());
    }

    @Test
    void progressBurstQueuesOneTaskWithTheLatestValue() {
        ManualExecutor ui = new ManualExecutor();
        TransferSession s = new TransferSession("a", "b", "f.bin", 1000);
        s.deliverUpdatesOn(ui);

        for (int i = 1; i <= 1000; i++) s.setDoneBytes(i);
        assertEquals(1, ui.drain());
        assertEquals(1000, s.doneBytesProperty().get());
        assertEquals(1.0, s.progressProperty().get(), 1e-9);

        s.setDoneBytes(10); // a new burst queues a new task
        assertEquals(1, ui.drain());
        assertEquals(10, s.doneBytesProperty().get());
    }

    @Test
    void statusChangesKeepTheirOrder() {
        ManualExecutor ui = new ManualExecutor();
        TransferSession s = new TransferSession("a", "b", "f.bin", 10);
        List<String> seen = new ArrayList<>();
        s.statusProperty().addListener((o, was, now) -> seen.add(now));
        s.deliverUpdatesOn(ui);

        s.setStatus("RECEIVING");
        s.setDoneBytes(10);
        s.setStatus("STORED");
        s.setStatus("DONE");
        ui.drain();
        assertEquals(List.of("RECEIVING", "STORED", "DONE"), seen);
    }

    @Test
    void concurrentWritersEndOnTheLastValue() throws Exception {
        ManualExecutor ui = new ManualExecutor();
        TransferSession s = new TransferSession("a", "b", "f.bin", 1_000_000);
        s.deliverUpdatesOn(ui);

        CountDownLatch done = new CountDownLatch(1);
        Thread socket = new Thread(() -> {
            for (int i = 1; i <= 1_000_000; i++) s.setDoneBytes(i);
            done.countDown();
        });
        socket.start();
        while (done.getCount() > 0) ui.drain(); // the "FX thread" keeps draining meanwhile
        socket.join();
        ui.drain();
        assertEquals(1_000_000, s.doneBytesProperty().get());
    }
}
