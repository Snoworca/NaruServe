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
 * API 21. The platform trust manager validates the chain to those anchors; every certificate of the chain the backend
 * presents is also checked against its validity period, because the JDK accepts a trusted end-entity certificate
 * without one, and so is the given certificate the chain ends at, because PKIX does not check a trust anchor's
 * validity. The host name is checked afterwards by {@link TlsConnector} as for every https target. No check is
 * turned off.
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
     * Delegates to the platform trust manager and then checks the validity period of every certificate of the
     * presented chain, which the JDK skips for a chain whose end-entity certificate is itself a trust anchor, and of
     * the given certificate the chain ends at, which PKIX never checks because it takes a trust anchor as it is.
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
            for (X509Certificate c : chain) {
                c.checkValidity();
            }
            checkAnchorValidity(chain);
        }

        /**
         * Walks the presented chain from the leaf as the platform's PKIX validator does and stops at the first
         * certificate that is itself a given certificate (already checked above) or that a given certificate issued:
         * a given certificate whose subject is that certificate's issuer and whose public key verifies its signature.
         * Of those issuers at least one must be within its validity period. A given certificate that issued nothing
         * in the chain is never looked at, so an expired certificate elsewhere in a bundle does not matter.
         */
        private void checkAnchorValidity(X509Certificate[] chain) throws CertificateException {
            for (X509Certificate c : chain) {
                if (isAnchor(c)) {
                    return;
                }
                CertificateException invalid = null;
                boolean issued = false;
                for (X509Certificate anchor : anchors) {
                    if (!anchor.getSubjectX500Principal().equals(c.getIssuerX500Principal()) || !signed(c, anchor)) {
                        continue;
                    }
                    issued = true;
                    try {
                        anchor.checkValidity();
                        return;
                    } catch (CertificateException e) {
                        invalid = e;
                    }
                }
                if (issued) {
                    throw invalid;
                }
            }
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
