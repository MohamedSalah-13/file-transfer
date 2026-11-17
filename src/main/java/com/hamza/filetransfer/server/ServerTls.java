package com.hamza.filetransfer.server;

import com.hamza.filetransfer.shared.Tls;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * The server's TLS identity: a self-signed certificate created on first start and kept in
 * {@code <dir>/server.p12} (password in {@code server.p12.password}). Keeping the same certificate
 * matters: clients remember its fingerprint and refuse to connect if it changes.
 *
 * @param context     server-side TLS context with the key loaded
 * @param fingerprint SHA-256 fingerprint that users compare when a client asks to trust the server
 */
public record ServerTls(SSLContext context, String fingerprint) {

    private static final String ALIAS = "server";
    private static final String KEYSTORE = "server.p12";
    private static final String PASSWORD_FILE = "server.p12.password";
    private static final String PASSWORD_ENV = "FT_KEYSTORE_PASSWORD";

    /** Loads the certificate from {@code dir}, creating it first if there is none. */
    public static ServerTls loadOrCreate(Path dir) throws Exception {
        Files.createDirectories(dir);
        Path keystore = dir.resolve(KEYSTORE);
        Path passwordFile = dir.resolve(PASSWORD_FILE);

        if (!Files.exists(keystore)) {
            byte[] random = new byte[24];
            new SecureRandom().nextBytes(random);
            String password = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
            // Password file first: if we stop half-way, the next start sees no keystore and simply
            // starts over, instead of finding a keystore whose password was never saved.
            Path tmp = Files.createTempFile(dir, PASSWORD_FILE, ".tmp");
            Files.writeString(tmp, password, StandardCharsets.UTF_8);
            Files.move(tmp, passwordFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            generate(keystore, password);
        }

        char[] password = Files.readString(passwordFile, StandardCharsets.UTF_8).trim().toCharArray();
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystore)) {
            ks.load(in, password);
        }

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, password);
        SSLContext context = SSLContext.getInstance(Tls.PROTOCOL);
        context.init(kmf.getKeyManagers(), null, null);

        String fingerprint = Tls.fingerprint((X509Certificate) ks.getCertificate(ALIAS));
        return new ServerTls(context, fingerprint);
    }

    /**
     * Creates a self-signed EC certificate with the JDK's keytool. The Java API has no public way to
     * build certificates; the password goes through an environment variable, not the command line.
     */
    private static void generate(Path keystore, String password) throws Exception {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        Path keytool = Path.of(System.getProperty("java.home"), "bin", windows ? "keytool.exe" : "keytool");
        if (!Files.isExecutable(keytool)) {
            throw new IOException("keytool not found at " + keytool + ": cannot create the server certificate");
        }

        ProcessBuilder pb = new ProcessBuilder(keytool.toString(),
                "-genkeypair", "-alias", ALIAS,
                "-keyalg", "EC", "-groupname", "secp256r1", "-sigalg", "SHA256withECDSA",
                "-validity", "3650", "-dname", "CN=file-transfer server",
                "-storetype", "PKCS12", "-keystore", keystore.toString(),
                "-storepass:env", PASSWORD_ENV, "-noprompt");
        pb.environment().put(PASSWORD_ENV, password);
        pb.redirectErrorStream(true);

        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) {
            p.destroyForcibly();
            Files.deleteIfExists(keystore);
            throw new IOException("keytool failed to create the server certificate: " + output.trim());
        }
    }
}
