package com.hamza.filetransfer.client;

import com.hamza.filetransfer.shared.Tls;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedTrustManager;
import java.io.IOException;
import java.net.Socket;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

/**
 * Trust on first use: the server's certificate is accepted only if its SHA-256 fingerprint matches the
 * one this user trusted for {@code host:port} (see {@link KnownServers}). There is no CA on a LAN;
 * the TLS handshake itself proves the server holds the private key of the pinned certificate.
 */
public final class TofuTrustManager extends X509ExtendedTrustManager {

    /** The server's certificate is not (or no longer) the trusted one. */
    public static final class UntrustedServerException extends CertificateException {
        private final String fingerprint;
        private final String trustedFingerprint;

        UntrustedServerException(String fingerprint, String trustedFingerprint) {
            super(trustedFingerprint == null ? "Server not trusted yet" : "Server certificate changed");
            this.fingerprint = fingerprint;
            this.trustedFingerprint = trustedFingerprint;
        }

        /** Fingerprint the server presented. */
        public String fingerprint() { return fingerprint; }

        /** Fingerprint trusted before, or null if this server was never trusted. */
        public String trustedFingerprint() { return trustedFingerprint; }
    }

    private final KnownServers knownServers;
    private final String host;
    private final int port;

    public TofuTrustManager(KnownServers knownServers, String host, int port) {
        this.knownServers = knownServers;
        this.host = host;
        this.port = port;
    }

    private void verify(X509Certificate[] chain) throws CertificateException {
        if (chain == null || chain.length == 0) throw new CertificateException("Server sent no certificate");
        String presented = Tls.fingerprint(chain[0]);
        String trusted;
        try {
            trusted = knownServers.fingerprint(host, port);
        } catch (IOException e) {
            throw new CertificateException("Cannot read " + knownServers.location(), e);
        }
        if (!presented.equals(trusted)) throw new UntrustedServerException(presented, trusted);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        verify(chain);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
        verify(chain);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
        verify(chain);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        throw new CertificateException("Client certificates are not used");
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
        throw new CertificateException("Client certificates are not used");
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
        throw new CertificateException("Client certificates are not used");
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return new X509Certificate[0];
    }
}
