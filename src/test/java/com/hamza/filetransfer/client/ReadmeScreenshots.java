package com.hamza.filetransfer.client;

import com.hamza.filetransfer.server.ServerApp;
import com.hamza.filetransfer.server.ServerTls;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Button;
import javafx.scene.control.Labeled;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.transform.Transform;
import javafx.stage.Stage;
import javafx.stage.Window;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Takes the README screenshots ({@code docs/images/*.png}) from the real app: starts ServerApp and
 * ClientApp with demo users and messages and saves JavaFX snapshots of their windows.
 * Not a test (no @Test); it lives here so it compiles with the code it drives.
 * Run {@code docs/images/screenshots.ps1}: it gives each language an empty {@code user.home} and working
 * directory, so the real settings, trusted servers and server-storage are never touched.
 * Args: {@code <ar|en> <output dir> <port>}.
 */
public class ReadmeScreenshots {

    private record Demo(String me, String sara, String omar, String mona,
                        String hi, String notYet, String report, String thanks, String meeting, String lunch,
                        String photo) {}

    private static final Demo ARABIC = new Demo("أحمد", "سارة", "عمر", "منى",
            "صباح الخير يا أحمد، هل وصلك تقرير المبيعات؟", "لسه، ابعتيه من فضلك",
            "تقرير-المبيعات-نوفمبر.pdf", "وصل، شكرًا يا سارة 👍", "الاجتماع الساعة 3 في غرفة الاجتماعات",
            "هنطلب غدا، تحب حاجة؟", "صورة-الفريق.jpg");

    private static final Demo ENGLISH = new Demo("Ahmed", "Sara", "Omar", "Mona",
            "Good morning Ahmed, did you get the sales report?", "Not yet, please send it",
            "Sales-report-November.pdf", "Got it, thanks Sara 👍", "Meeting at 3 in the conference room",
            "We are ordering lunch, want anything?", "Team-photo.jpg");

    private static final String PASSWORD = "demo-password";

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Throwable t) {
            // The JavaFX thread would keep the JVM alive after a failure: exit, so the script sees it.
            t.printStackTrace();
            System.exit(1);
        }
    }

    private static void run(String[] args) throws Exception {
        String lang = args[0];
        Path out = Path.of(args[1]).toAbsolutePath();
        int port = Integer.parseInt(args[2]);
        Demo d = "ar".equals(lang) ? ARABIC : ENGLISH;

        Path home = Path.of(System.getProperty("user.home"));
        Path work = Path.of("").toAbsolutePath();
        Files.createDirectories(out);
        Files.createDirectories(home.resolve(".file-transfer"));
        Files.writeString(work.resolve("server.properties"), "port=" + port + "\n");
        String look = "language=" + lang + "\ntheme=Primer Light\n";
        Files.writeString(home.resolve(".file-transfer/server-ui.properties"), look);
        Files.writeString(home.resolve(".file-transfer/client.properties"), look
                + "host=127.0.0.1\nport=" + port + "\nnotifications=false\n", StandardCharsets.UTF_8);
        // LastLogin keeps the user name; write it with Properties escaping (it may be Arabic).
        new LastLogin("127.0.0.1", port, d.me()).save(LastLogin.defaultLocation());

        Platform.startup(() -> {});
        Platform.setImplicitExit(false);

        // ===== Server =====
        Stage serverStage = onFx(() -> {
            Stage s = new Stage();
            new ServerApp().start(s);
            return s;
        });
        waitUntil(() -> canConnect(port), 30, "server did not start");
        String fingerprint = ServerTls.loadOrCreate(work.resolve("server-storage/tls")).fingerprint();
        // The demo users trust the server through their own file; the GUI user's stays empty,
        // so the app shows its "New server" trust dialog.
        KnownServers known = new KnownServers(work.resolve("demo-known-servers.properties"));
        known.trust("127.0.0.1", port, fingerprint);

        // ===== Demo users and messages (the GUI user logs in last) =====
        login(d.me(), port, known).close(); // registers the account so others can write to it
        ClientConnection sara = login(d.sara(), port, known);
        ClientConnection omar = login(d.omar(), port, known);
        ClientConnection mona = login(d.mona(), port, known);
        ClientConnection me = login(d.me(), port, known);

        Path files = Files.createDirectories(work.resolve("demo-files"));
        Path report = files.resolve(d.report());
        Files.write(report, new byte[1_340_000]);
        Path photo = files.resolve(d.photo());
        Files.write(photo, new byte[860_000]);

        sara.sendText(d.me(), d.hi());
        pause();
        me.sendText(d.sara(), d.notYet());
        pause();
        sara.sendFile(d.me(), report, (done, total) -> {});
        pause();
        me.sendText(d.sara(), d.thanks());
        pause();
        me.sendFile(d.sara(), photo, (done, total) -> {});
        me.close();
        Thread.sleep(500);

        // ===== Client window =====
        ClientApp app = new ClientApp();
        app.notifier = new ClientApp.Notifier() {
            public void show(String sender, String title, String text) { }
            public void close() { }
        };
        BooleanSupplier inFront = () -> true;
        app.windowInFront = inFront;
        Stage clientStage = onFx(() -> {
            Stage s = new Stage();
            app.start(s);
            return s;
        });

        Stage settings = waitFor(() -> onFx(() -> Window.getWindows().stream()
                .filter(w -> w instanceof Stage st && st.getOwner() == clientStage && w.isShowing())
                .map(w -> (Stage) w).findFirst().orElse(null)), "settings window did not open");
        onFx(() -> {
            PasswordField pw = (PasswordField) settings.getScene().getRoot().lookupAll(".password-field")
                    .iterator().next();
            pw.setText(PASSWORD);
            return null;
        });
        Thread.sleep(300);
        save(settings, out.resolve("settings-" + lang + ".png"));
        onFx(() -> {
            clickButton(settings, I18n.get("connect"));
            return null;
        });

        // First connection: the app asks whether to trust the server's certificate.
        Stage trust = waitFor(() -> onFx(() -> Window.getWindows().stream()
                .filter(w -> w instanceof Stage st && st.getOwner() == settings && w.isShowing())
                .map(w -> (Stage) w).findFirst().orElse(null)), "trust dialog did not open");
        Thread.sleep(400);
        save(trust, out.resolve("trust-" + lang + ".png"));
        onFx(() -> {
            clickButton(trust, I18n.get("trust_button"));
            return null;
        });

        ListView<UserItem> users = waitFor(() -> onFx(() -> usersList(clientStage)), "user list did not fill");
        onFx(() -> {
            UserItem s = users.getItems().stream().filter(u -> u.getUsername().equals(d.sara())).findFirst().orElseThrow();
            users.getSelectionModel().select(s);
            return null;
        });
        ListView<ChatMessage> chat = waitFor(() -> onFx(() -> {
            ListView<ChatMessage> c = chatList(clientStage);
            return c != null && c.getItems().size() >= 5 ? c : null;
        }), "chat did not load");

        // Download the report through the app, so the transfers panel shows a finished row
        // and the bubble shows Open / Show in folder.
        Path downloads = Files.createDirectories(work.resolve("downloads"));
        onFx(() -> {
            ChatMessage file = chat.getItems().stream()
                    .filter(m -> m.typeProperty().get() == ChatMessage.Type.FILE && d.report().equals(m.fileNameProperty().get()))
                    .findFirst().orElseThrow();
            app.downloadInto(downloads, file.messageIdProperty().get(), d.report());
            return null;
        });
        waitUntil(() -> Files.exists(downloads.resolve(d.report())), 20, "download did not finish");

        // Unread messages from people whose chat is not open.
        omar.sendText(d.me(), d.meeting());
        pause();
        mona.sendText(d.me(), d.lunch());
        Thread.sleep(1500);
        onFx(() -> {
            chat.scrollTo(chat.getItems().size() - 1);
            return null;
        });
        Thread.sleep(500);

        // The server window shows its storage folder, a temp path with the OS user name in it:
        // show a neutral path in the picture instead.
        String storage = work.resolve("server-storage").toString();
        onFx(() -> {
            for (Node n : serverStage.getScene().getRoot().lookupAll(".label")) {
                if (n instanceof Labeled l && l.getText() != null && l.getText().contains(storage)) {
                    String text = l.getText().replace(storage, "C:\\FileTransfer\\server-storage");
                    l.textProperty().unbind();
                    l.setText(text);
                }
            }
            return null;
        });

        save(clientStage, out.resolve("client-" + lang + ".png"));
        save(serverStage, out.resolve("server-" + lang + ".png"));

        sara.close();
        omar.close();
        mona.close();
        Platform.exit();
        System.exit(0);
    }

    private static ClientConnection login(String user, int port, KnownServers known) throws Exception {
        ClientConnection c = new ClientConnection("127.0.0.1", port, user, PASSWORD.toCharArray(), known);
        c.start();
        return c;
    }

    private static void clickButton(Stage stage, String text) {
        stage.getScene().getRoot().lookupAll(".button").stream()
                .filter(n -> n instanceof Button b && text.equals(b.getText()) && !b.isDisabled())
                .map(n -> (Button) n).findFirst()
                .orElseThrow(() -> new IllegalStateException("no button " + text))
                .fire();
    }

    @SuppressWarnings("unchecked")
    private static ListView<UserItem> usersList(Stage stage) {
        for (Node n : stage.getScene().getRoot().lookupAll(".list-view")) {
            if (n instanceof ListView<?> lv && lv.getItems().size() >= 3 && lv.getItems().getFirst() instanceof UserItem) {
                return (ListView<UserItem>) lv;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static ListView<ChatMessage> chatList(Stage stage) {
        for (Node n : stage.getScene().getRoot().lookupAll(".list-view")) {
            if (n instanceof ListView<?> lv && !lv.getItems().isEmpty() && lv.getItems().getFirst() instanceof ChatMessage) {
                return (ListView<ChatMessage>) lv;
            }
        }
        return null;
    }

    /** Snapshot of the window's content (no OS title bar) at 1.5x, for sharp text on high-DPI screens. */
    private static void save(Stage stage, Path file) throws Exception {
        WritableImage img = onFx(() -> {
            Parent root = stage.getScene().getRoot();
            root.applyCss();
            root.layout();
            SnapshotParameters p = new SnapshotParameters();
            p.setTransform(Transform.scale(1.5, 1.5));
            return root.snapshot(p, null);
        });
        int w = (int) img.getWidth();
        int h = (int) img.getHeight();
        int[] argb = new int[w * h];
        img.getPixelReader().getPixels(0, 0, w, h, PixelFormat.getIntArgbInstance(), argb, 0, w);
        BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        bi.setRGB(0, 0, w, h, argb, 0, w);
        ImageIO.write(bi, "png", file.toFile());
        System.out.println("saved " + file + " (" + w + "x" + h + ")");
    }

    private static <T> T onFx(Callable<T> work) throws Exception {
        CompletableFuture<T> f = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                f.complete(work.call());
            } catch (Throwable t) {
                f.completeExceptionally(t);
            }
        });
        return f.get(30, TimeUnit.SECONDS);
    }

    private static <T> T waitFor(Callable<T> probe, String what) throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            T v = probe.call();
            if (v != null) return v;
            Thread.sleep(200);
        }
        throw new IllegalStateException(what);
    }

    private static void waitUntil(Callable<Boolean> probe, int seconds, String what) throws Exception {
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (probe.call()) return;
            Thread.sleep(200);
        }
        throw new IllegalStateException(what);
    }

    private static boolean canConnect(int port) {
        try (Socket s = new Socket("127.0.0.1", port)) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Server timestamps are millisecond-precise; keep the demo messages in order. */
    private static void pause() throws InterruptedException {
        Thread.sleep(150);
    }
}
