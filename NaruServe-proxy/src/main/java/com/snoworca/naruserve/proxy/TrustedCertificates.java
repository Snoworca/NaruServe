package com.snoworca.naruserve.proxy;

import java.io.ByteArrayInputStream;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
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
 * exactly the given certificates, built at the option call from {@code CertificateFactory.getInstance("X.509")}, an
 * empty {@code KeyStore} of {@code KeyStore.getDefaultType()}, a {@code TrustManagerFactory} of
 * {@code TrustManagerFactory.getDefaultAlgorithm()} and {@code SSLContext.getInstance("TLS")}, all present on Android
 * API 21. The platform trust manager validates the chain to those anchors; a path from the presented leaf to a given
 * certificate on which every certificate, the given certificate the path ends at included, is within its validity
 * period is also required when the presented chain reaches a given certificate at all, because the JDK accepts a
 * trusted end-entity certificate without checking its validity and PKIX does not check a trust anchor's validity;
 * presented certificates that path does not need are not checked. The host name is checked afterwards by
 * {@link TlsConnector} as for every https target. No check is turned off.
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
            KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
            store.load(null, null);
            for (int i = 0; i < certificates.length; i++) {
                store.setCertificateEntry("trusted-" + i, certificates[i]);
            }
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(store);
            TrustManager[] managers = tmf.getTrustManagers();
            for (int i = 0; i < managers.length; i++) {
                if (managers[i] instanceof X509TrustManager) {
                    managers[i] = new ValidityCheckingTrustManager((X509TrustManager) managers[i], certificates);
                }
            }
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, managers, null);
            return context.getSocketFactory();
        } catch (java.io.IOException | GeneralSecurityException e) {
            throw new IllegalArgumentException("trustCertificates: the certificates cannot be trusted", e);
        }
    }

    /**
     * Delegates to the platform trust manager and then requires a path from the presented leaf to a given certificate
     * on which every certificate is within its validity period, which the JDK skips for a chain whose end-entity
     * certificate is itself a trust anchor and PKIX never checks for the trust anchor a path ends at (see
     * {@link #checkPathValidity}). Presented certificates no such path needs are not looked at.
     */
    // @req FR-PROXY-022
    private static final class ValidityCheckingTrustManager implements X509TrustManager {
        private final X509TrustManager delegate;
        private final X509Certificate[] anchors;

        ValidityCheckingTrustManager(X509TrustManager delegate, X509Certificate[] anchors) {
            this.delegate = delegate;
            this.anchors = anchors;
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            delegate.checkClientTrusted(chain, authType);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            delegate.checkServerTrusted(chain, authType);
            checkPathValidity(chain);
        }

        /**
         * Searches the issuer paths of the presented chain from its leaf ({@code chain[0]}). A link from a presented
         * certificate X to a presented certificate Y needs Y's subject to equal X's issuer and Y's public key to
         * verify X's signature. A path succeeds when every certificate on it, the leaf included, is currently valid
         * and it ends at a presented certificate that is itself a given certificate, or at one that a currently valid
         * given certificate issued (the given certificate's subject equals its issuer and its public key verifies its
         * signature); one successful path is enough. Because validity belongs to a certificate and not to the path
         * that reaches it, a successful path exists exactly when the search through the currently valid presented
         * certificates, each visited once, reaches such an end: the answer does not depend on the order the backend
         * sends, the work is bounded by the square of the chain length, and cycles end. So an expired old cross-sign
         * or a pre-renewal copy of the CA that the backend sends besides a valid path changes nothing, and a
         * cross-signed intermediate reaching a valid given CA passes although an expired given CA issued it too.
         *
         * <p>When no path succeeds, an expired or not-yet-valid leaf throws its own exception. Otherwise, when a path
         * from the leaf reaches a given certificate, the shortest such path (the first found in presented order)
         * throws the exception of its first certificate outside its validity period, or of the given certificate
         * that issued its last certificate. When no path reaches any given certificate, only the leaf's validity is
         * checked and the platform trust manager's acceptance stands, since it found a path to a given certificate
         * that this search does not see; the given certificates are its only trust anchors, so that is no wider
         * trust. A given certificate no path reaches is never looked at, so an expired certificate elsewhere in a
         * bundle does not matter.
         */
        private void checkPathValidity(X509Certificate[] chain) throws CertificateException {
            int n = chain.length;
            if (n == 0) {
                return;
            }
            CertificateException[] invalid = new CertificateException[n];
            for (int i = 0; i < n; i++) {
                invalid[i] = validity(chain[i]);
            }
            if (invalid[0] != null) {
                throw invalid[0];
            }
            CertificateException[] invalidAnchor = new CertificateException[anchors.length];
            for (int a = 0; a < anchors.length; a++) {
                invalidAnchor[a] = validity(anchors[a]);
            }
            // links[x][y]: 0 not computed, 1 chain[y] issued chain[x], 2 it did not; each signature is checked once
            byte[][] links = new byte[n][n];
            if (reachesValidEnd(chain, invalid, invalidAnchor, links)) {
                return;
            }
            CertificateException blocker = firstBlocker(chain, invalid, invalidAnchor, links);
            if (blocker != null) {
                throw blocker;
            }
        }

        /** Whether the currently valid presented certificates hold a path from the leaf to a valid end. */
        private boolean reachesValidEnd(X509Certificate[] chain, CertificateException[] invalid,
                CertificateException[] invalidAnchor, byte[][] links) {
            int n = chain.length;
            boolean[] visited = new boolean[n];
            int[] queue = new int[n];
            int head = 0;
            int tail = 0;
            visited[0] = true;
            queue[tail++] = 0;
            while (head < tail) {
                int x = queue[head++];
                if (isAnchor(chain[x])) {
                    return true;
                }
                for (int a = 0; a < anchors.length; a++) {
                    if (invalidAnchor[a] == null && issuedBy(chain[x], anchors[a])) {
                        return true;
                    }
                }
                for (int y = 0; y < n; y++) {
                    if (!visited[y] && invalid[y] == null && linked(chain, links, x, y)) {
                        visited[y] = true;
                        queue[tail++] = y;
                    }
                }
            }
            return false;
        }

        /**
         * The exception that blocks the shortest path from the leaf to a given certificate over all presented
         * certificates, or {@code null} when no path reaches one. Called only when no valid path exists, so that path
         * holds a certificate outside its validity period or ends at given certificates that all are.
         */
        private CertificateException firstBlocker(X509Certificate[] chain, CertificateException[] invalid,
                CertificateException[] invalidAnchor, byte[][] links) {
            int n = chain.length;
            int[] parent = new int[n];
            boolean[] visited = new boolean[n];
            int[] queue = new int[n];
            int head = 0;
            int tail = 0;
            visited[0] = true;
            parent[0] = -1;
            queue[tail++] = 0;
            while (head < tail) {
                int x = queue[head++];
                CertificateException end = null;
                boolean reached = isAnchor(chain[x]);
                for (int a = 0; a < anchors.length && !reached; a++) {
                    if (issuedBy(chain[x], anchors[a])) {
                        reached = true;
                        end = invalidAnchor[a];
                    }
                }
                if (reached) {
                    CertificateException onPath = null;
                    for (int c = x; c >= 0; c = parent[c]) {
                        if (invalid[c] != null) {
                            onPath = invalid[c];
                        }
                    }
                    return onPath != null ? onPath : end;
                }
                for (int y = 0; y < n; y++) {
                    if (!visited[y] && linked(chain, links, x, y)) {
                        visited[y] = true;
                        parent[y] = x;
                        queue[tail++] = y;
                    }
                }
            }
            return null;
        }

        private static boolean linked(X509Certificate[] chain, byte[][] links, int x, int y) {
            if (links[x][y] == 0) {
                links[x][y] = x != y && issuedBy(chain[x], chain[y]) ? (byte) 1 : (byte) 2;
            }
            return links[x][y] == 1;
        }

        private static CertificateException validity(X509Certificate c) {
            try {
                c.checkValidity();
                return null;
            } catch (CertificateException e) {
                return e;
            }
        }

        private static boolean issuedBy(X509Certificate c, X509Certificate issuer) {
            return issuer.getSubjectX500Principal().equals(c.getIssuerX500Principal()) && signed(c, issuer);
        }

        private boolean isAnchor(X509Certificate c) {
            for (X509Certificate anchor : anchors) {
                if (anchor.equals(c)) {
                    return true;
                }
            }
            return false;
        }

        private static boolean signed(X509Certificate c, X509Certificate issuer) {
            try {
                c.verify(issuer.getPublicKey());
                return true;
            } catch (GeneralSecurityException | RuntimeException e) {
                return false;
            }
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return delegate.getAcceptedIssuers();
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
