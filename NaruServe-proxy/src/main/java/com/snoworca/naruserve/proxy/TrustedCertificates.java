package com.snoworca.naruserve.proxy;

import java.io.ByteArrayInputStream;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * The socket factory of {@code Proxy.trustCertificates} (FR-PROXY-022): an {@link SSLContext} whose trust anchors are
 * exactly the given certificates that are within their validity period at connection time, built from
 * {@code CertificateFactory.getInstance("X.509")}, an empty {@code KeyStore} of {@code KeyStore.getDefaultType()}, a
 * {@code TrustManagerFactory} of {@code TrustManagerFactory.getDefaultAlgorithm()} and
 * {@code SSLContext.getInstance("TLS")}, all present on Android API 21. At each handshake the platform trust manager
 * built from the currently valid given certificates validates the backend's chain with its standard checks
 * (path building, signatures, CA constraints and the validity of each certificate on the path), so an expired or
 * not-yet-valid given certificate is never an anchor, which matters because PKIX does not check a trust anchor's
 * validity; presented certificates the path does not need are left to the platform, which ignores them. The host
 * name is checked afterwards by {@link TlsConnector} as for every https target. No check is turned off.
 */
// @req FR-PROXY-022
final class TrustedCertificates {

    private static final String BEGIN = "-----BEGIN CERTIFICATE-----";
    private static final String END = "-----END CERTIFICATE-----";

    private TrustedCertificates() {
    }

    /**
     * The factory trusting every {@code CERTIFICATE} block of {@code pem}; text outside the blocks is ignored.
     * {@code IllegalArgumentException} when there is no complete block or a block's base64 or DER does not parse.
     */
    // @req FR-PROXY-022
    static SSLSocketFactory fromPem(String pem) {
        if (pem == null) {
            throw new NullPointerException("trustCertificates pem");
        }
        CertificateFactory factory = x509Factory();
        List<X509Certificate> certificates = new ArrayList<>();
        int from = 0;
        while (true) {
            int begin = pem.indexOf(BEGIN, from);
            if (begin < 0) {
                break;
            }
            int end = pem.indexOf(END, begin + BEGIN.length());
            if (end < 0) {
                throw new IllegalArgumentException("trustCertificates: a CERTIFICATE block has no END line");
            }
            byte[] der = Base64.decode(pem.substring(begin + BEGIN.length(), end), certificates.size() + 1);
            X509Certificate certificate;
            try {
                certificate = (X509Certificate) factory.generateCertificate(new ByteArrayInputStream(der));
                // the factory reads one certificate and ignores what follows it; the block must be that one exactly
                if (!Arrays.equals(certificate.getEncoded(), der)) {
                    certificate = null;
                }
            } catch (CertificateException | RuntimeException e) {
                throw new IllegalArgumentException(
                        "trustCertificates: CERTIFICATE block " + (certificates.size() + 1) + " is not a certificate", e);
            }
            if (certificate == null) {
                throw new IllegalArgumentException("trustCertificates: CERTIFICATE block " + (certificates.size() + 1)
                        + " is not exactly one certificate");
            }
            certificates.add(certificate);
            from = end + END.length();
        }
        if (certificates.isEmpty()) {
            throw new IllegalArgumentException("trustCertificates: the PEM text holds no CERTIFICATE block");
        }
        return socketFactory(certificates.toArray(new X509Certificate[0]));
    }

    /**
     * The factory trusting {@code certificates}; {@code NullPointerException} for a {@code null} array or element and
     * {@code IllegalArgumentException} for an empty array.
     */
    // @req FR-PROXY-022
    static SSLSocketFactory fromCertificates(X509Certificate[] certificates) {
        if (certificates == null) {
            throw new NullPointerException("trustCertificates certificates");
        }
        X509Certificate[] copy = certificates.clone();
        for (int i = 0; i < copy.length; i++) {
            if (copy[i] == null) {
                throw new NullPointerException("trustCertificates certificates[" + i + "]");
            }
        }
        if (copy.length == 0) {
            throw new IllegalArgumentException("trustCertificates: no certificate given");
        }
        return socketFactory(copy);
    }

    private static CertificateFactory x509Factory() {
        try {
            return CertificateFactory.getInstance("X.509");
        } catch (CertificateException e) {
            throw new IllegalStateException("no X.509 CertificateFactory", e);
        }
    }

    private static SSLSocketFactory socketFactory(X509Certificate[] certificates) {
        try {
            // one platform trust manager over every given certificate, built and dropped here, so a platform that
            // cannot hold them fails at the call rather than at the first connection
            platformTrustManager(certificates);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[] {new CurrentlyValidTrustManager(certificates)}, null);
            return context.getSocketFactory();
        } catch (java.io.IOException | GeneralSecurityException e) {
            throw new IllegalArgumentException("trustCertificates: the certificates cannot be trusted", e);
        }
    }

    /** The platform's X.509 trust manager whose only trust anchors are {@code anchors}. */
    private static X509TrustManager platformTrustManager(X509Certificate[] anchors)
            throws java.io.IOException, GeneralSecurityException {
        KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
        store.load(null, null);
        for (int i = 0; i < anchors.length; i++) {
            store.setCertificateEntry("trusted-" + i, anchors[i]);
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(store);
        for (TrustManager manager : tmf.getTrustManagers()) {
            if (manager instanceof X509TrustManager) {
                return (X509TrustManager) manager;
            }
        }
        throw new KeyStoreException("the default TrustManagerFactory has no X509TrustManager");
    }

    /**
     * Delegates every check to the platform trust manager built from the given certificates that are within their
     * validity period at the moment of the check. The delegate is cached for the set of currently valid given
     * certificates and rebuilt only when that set changes; when no given certificate is currently valid, the check
     * throws the validity exception of the first given certificate without building one. {@link #getAcceptedIssuers}
     * returns every given certificate.
     */
    // @req FR-PROXY-022
    private static final class CurrentlyValidTrustManager implements X509TrustManager {
        private final X509Certificate[] given;
        /** The delegate for the last set of currently valid given certificates; {@code null} before the first check. */
        private volatile Delegate cached;

        CurrentlyValidTrustManager(X509Certificate[] given) {
            this.given = given;
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            current().manager.checkClientTrusted(chain, authType);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            current().manager.checkServerTrusted(chain, authType);
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return given.clone();
        }

        /** The delegate over the given certificates that are valid now. */
        private Delegate current() throws CertificateException {
            boolean[] valid = new boolean[given.length];
            int count = 0;
            CertificateException first = null;
            for (int i = 0; i < given.length; i++) {
                try {
                    given[i].checkValidity();
                    valid[i] = true;
                    count++;
                } catch (CertificateException e) {
                    if (first == null) {
                        first = e;
                    }
                }
            }
            if (count == 0) {
                throw first;
            }
            Delegate d = cached;
            if (d != null && Arrays.equals(d.valid, valid)) {
                return d;
            }
            X509Certificate[] anchors = new X509Certificate[count];
            for (int i = 0, a = 0; i < given.length; i++) {
                if (valid[i]) {
                    anchors[a++] = given[i];
                }
            }
            try {
                d = new Delegate(valid, platformTrustManager(anchors));
            } catch (java.io.IOException | GeneralSecurityException e) {
                throw new CertificateException("trustCertificates: the currently valid certificates cannot be trusted",
                        e);
            }
            cached = d;
            return d;
        }
    }

    /** An immutable pair: which given certificates were valid, and the platform trust manager over exactly those. */
    private static final class Delegate {
        final boolean[] valid;
        final X509TrustManager manager;

        Delegate(boolean[] valid, X509TrustManager manager) {
            this.valid = valid;
            this.manager = manager;
        }
    }

    /** A strict base64 decoder for PEM bodies; {@code java.util.Base64} is absent before Android API 26. */
    // @req FR-PROXY-022
    static final class Base64 {
        private Base64() {
        }

        /**
         * The bytes of {@code text}, ignoring whitespace; {@code IllegalArgumentException} for any other character
         * outside the base64 alphabet, misplaced padding, a length that cannot be base64, or no data.
         */
        static byte[] decode(String text, int block) {
            StringBuilder chars = new StringBuilder(text.length());
            int padding = 0;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                    continue;
                }
                if (c == '=') {
                    padding++;
                } else if (padding > 0 || value(c) < 0) {
                    throw new IllegalArgumentException("trustCertificates: CERTIFICATE block " + block
                            + " is not base64");
                }
                chars.append(c);
            }
            int n = chars.length();
            if (n == 0 || padding > 2 || n % 4 == 1 || (padding > 0 && n % 4 != 0)) {
                throw new IllegalArgumentException("trustCertificates: CERTIFICATE block " + block + " is not base64");
            }
            int data = n - padding;
            int bytes = data / 4 * 3 + (data % 4 == 0 ? 0 : data % 4 - 1);
            byte[] out = new byte[bytes];
            int o = 0;
            int acc = 0;
            int bits = 0;
            for (int i = 0; i < data; i++) {
                acc = (acc << 6) | value(chars.charAt(i));
                bits += 6;
                if (bits >= 8) {
                    bits -= 8;
                    out[o++] = (byte) (acc >> bits);
                    acc &= (1 << bits) - 1;
                }
            }
            if (acc != 0) {
                throw new IllegalArgumentException("trustCertificates: CERTIFICATE block " + block + " is not base64");
            }
            return out;
        }

        private static int value(char c) {
            if (c >= 'A' && c <= 'Z') {
                return c - 'A';
            }
            if (c >= 'a' && c <= 'z') {
                return c - 'a' + 26;
            }
            if (c >= '0' && c <= '9') {
                return c - '0' + 52;
            }
            if (c == '+') {
                return 62;
            }
            if (c == '/') {
                return 63;
            }
            return -1;
        }
    }
}
