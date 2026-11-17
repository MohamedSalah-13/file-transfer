package com.hamza.filetransfer.shared;

import javax.net.ssl.SSLSocket;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.HexFormat;

/**
 * TLS settings shared by the server and the client. Every connection (CONTROL and TRANSFER) is TLS 1.3.
 * The server uses a self-signed certificate; clients trust it by its SHA-256 fingerprint
 * (trust on first use), so the fingerprint format must be the same on both sides.
 */
public final class Tls {

    public static final String PROTOCOL = "TLSv1.3";

    private Tls() {}

    /** Restricts a socket to TLS 1.3 (call before the handshake). */
    public static void restrict(SSLSocket socket) {
        socket.setEnabledProtocols(new String[]{PROTOCOL});
    }

    /** SHA-256 of the certificate as upper-case hex pairs: "3F:A9:12:...". */
    public static String fingerprint(X509Certificate certificate) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
            return HexFormat.ofDelimiter(":").withUpperCase().formatHex(digest);
        } catch (NoSuchAlgorithmException | CertificateEncodingException e) {
            throw new IllegalStateException("Cannot fingerprint certificate", e);
        }
    }
}
