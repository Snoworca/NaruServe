package com.snoworca.naruserve.proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.proxy.ProxyTlsTest.TlsBackend;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.TestApp;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.net.Socket;
import java.security.Key;
import java.security.KeyStore;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedKeyManager;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * {@code Proxy.trustCertificates} (FR-PROXY-022): an https backend with a self-signed or private-CA certificate is
 * reached by trusting exactly the given certificates, while the certificate chain (signature and validity period)
 * and the proxy's own host name check (SEC-PROXY-005) stay on. The certificates are made with the JDK's
 * {@code keytool} once per class, as in {@link ProxyTlsTest}: a self-signed certificate for {@code localhost} and
 * {@code 127.0.0.1}, a self-signed one named only by its common name, an expired self-signed one, a private CA with a
 * certificate for {@code localhost} and one for {@code other.example}, a second CA with a certificate for
 * {@code localhost}, an expired and a not-yet-valid private CA each with a currently valid certificate for
 * {@code localhost}, an expired CA certificate with the first CA's name but another key, a cross-signed
 * intermediate (one name and key, issued once by the expired CA and once by the first CA) with a leaf it issued, two
 * expired certificates with the first CA's name and key (an old cross-sign by the expired CA and the copy before the
 * CA was renewed), and an expired intermediate the first CA issued with a currently valid leaf it issued.
 */
public class ProxyTrustCertificatesTest {

    private static File dir;
    /** PEM of the self-signed certificate for localhost and 127.0.0.1. */
    private static String selfPem;
    /** PEM of a self-signed certificate whose only name is its subject CN=localhost. */
    private static String cnOnlyPem;
    /** PEM of an expired self-signed certificate for localhost and 127.0.0.1. */
    private static String expiredPem;
    /** PEM of the private CA. */
    private static String caPem;
    private static SSLContext selfServer;
    private static SSLContext cnOnlyServer;
    private static SSLContext expiredServer;
    /** Serves the private CA's certificate for localhost and 127.0.0.1. */
    private static SSLContext caGoodServer;
    /** Serves the private CA's certificate for other.example only. */
    private static SSLContext caOtherHostServer;
    /** Serves the second CA's certificate for localhost and 127.0.0.1. */
    private static SSLContext otherCaServer;
    /** A client factory that trusts the private CA only. */
    private static SSLSocketFactory caFactory;
    /** PEM of an expired private CA (valid 2000-01-01 for 2 days). */
    private static String expiredCaPem;
    /** PEM of a private CA whose validity starts 10 days from now. */
    private static String futureCaPem;
    /** PEM of an expired CA certificate with the subject of {@link #caPem} but another key. */
    private static String staleSameNameCaPem;
    /** Serves only its currently valid leaf for localhost and 127.0.0.1, which the expired CA issued. */
    private static SSLContext expiredCaLeafServer;
    /** Serves only its currently valid leaf for localhost and 127.0.0.1, which the not-yet-valid CA issued. */
    private static SSLContext futureCaLeafServer;
    /** Serves only the private CA's leaf for localhost and 127.0.0.1, without the CA certificate. */
    private static SSLContext caLeafOnlyServer;
    /** The private CA's certificate. */
    private static X509Certificate caCert;
    /** A currently valid intermediate CA certificate the expired CA issued. */
    private static X509Certificate crossByExpiredCa;
    /** The same intermediate (same name and key), issued by the private CA: a cross-signed intermediate. */
    private static X509Certificate crossByCa;
    /** A currently valid leaf for localhost and 127.0.0.1 that the cross-signed intermediate issued. */
    private static X509Certificate crossLeaf;
    private static PrivateKey crossLeafKey;
    /** The private CA's currently valid leaf for localhost and 127.0.0.1 ({@code good.p12}) and its key. */
    private static X509Certificate caLeaf;
    private static PrivateKey caLeafKey;
    /** The private CA's name and key, issued by the expired CA and itself expired: an old cross-sign of the CA. */
    private static X509Certificate expiredCrossOfCa;
    /** The private CA's name and key, self-issued and expired: the CA certificate before it was renewed. */
    private static X509Certificate preRenewalCa;
    /** An expired intermediate CA certificate the private CA issued. */
    private static X509Certificate expiredIntermediate;
    /** A currently valid leaf for localhost and 127.0.0.1 that the expired intermediate issued, and its key. */
    private static X509Certificate expiredIntermediateLeaf;
    private static PrivateKey expiredIntermediateLeafKey;

    private final InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
    private final TestApp t = TestApp.create(adapter);
    private final RestApp app = t.app();
    private final List<Closeable> closeables = new ArrayList<>();
    private final List<Throwable> errors = Collections.synchronizedList(new ArrayList<Throwable>());
    private boolean started;

    @BeforeClass
    public static void makeCertificates() throws Exception {
        dir = java.nio.file.Files.createTempDirectory("naruserve-proxy-trust").toFile();
        selfSigned("self", "SAN=dns:localhost,ip:127.0.0.1", null);
        selfSigned("cnonly", null, null);
        selfSigned("expired", "SAN=dns:localhost,ip:127.0.0.1", "2000/01/01");
        ca("ca", "CN=NaruServe Trust Test CA");
        ca("ca2", "CN=NaruServe Other Test CA");
        issued("ca", "good", "CN=localhost", "SAN=dns:localhost,ip:127.0.0.1");
        issued("ca", "other", "CN=other.example", "SAN=dns:other.example");
        issued("ca2", "good2", "CN=localhost", "SAN=dns:localhost,ip:127.0.0.1");
        ca("expiredca", "CN=NaruServe Expired Test CA", "2000/01/01", "2");
        ca("futureca", "CN=NaruServe Future Test CA", "+10d", "3");
        ca("staleca", "CN=NaruServe Trust Test CA", "2000/01/01", "2");
        issued("expiredca", "expiredcaleaf", "CN=localhost", "SAN=dns:localhost,ip:127.0.0.1");
        issued("futureca", "futurecaleaf", "CN=localhost", "SAN=dns:localhost,ip:127.0.0.1");

        selfPem = TestCertificates.read(new File(dir, "self.pem"));
        cnOnlyPem = TestCertificates.read(new File(dir, "cnonly.pem"));
        expiredPem = TestCertificates.read(new File(dir, "expired.pem"));
        caPem = TestCertificates.read(new File(dir, "ca.pem"));
        selfServer = TestCertificates.serverContext(new File(dir, "self.p12"));
        cnOnlyServer = TestCertificates.serverContext(new File(dir, "cnonly.p12"));
        expiredServer = TestCertificates.serverContext(new File(dir, "expired.p12"));
        caGoodServer = TestCertificates.serverContext(new File(dir, "good.p12"));
        caOtherHostServer = TestCertificates.serverContext(new File(dir, "other.p12"));
        otherCaServer = TestCertificates.serverContext(new File(dir, "good2.p12"));
        expiredCaPem = TestCertificates.read(new File(dir, "expiredca.pem"));
        futureCaPem = TestCertificates.read(new File(dir, "futureca.pem"));
        staleSameNameCaPem = TestCertificates.read(new File(dir, "staleca.pem"));
        expiredCaLeafServer = leafOnlyServer(new File(dir, "expiredcaleaf.p12"));
        futureCaLeafServer = leafOnlyServer(new File(dir, "futurecaleaf.p12"));
        caLeafOnlyServer = leafOnlyServer(new File(dir, "good.p12"));
        crossSigned();
        expiredCopiesAndIntermediate();

        TestCertificates.keytool(dir, "-importcert", "-noprompt", "-alias", "ca", "-file", "ca.pem", "-keystore",
                "trust.p12");
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(TestCertificates.load(new File(dir, "trust.p12")));
        SSLContext client = SSLContext.getInstance("TLS");
        client.init(null, tmf.getTrustManagers(), null);
        caFactory = client.getSocketFactory();
    }

    @AfterClass
    public static void deleteCertificates() {
        TestCertificates.delete(dir);
    }

    @After
    public void tearDown() throws IOException {
        if (started) {
            app.shutdown(2, TimeUnit.SECONDS);
        }
        for (Closeable c : closeables) {
            c.close();
        }
    }

    // ---- certificates ----

    /** A self-signed key pair {@code name}.p12 for CN=localhost, with {@code san} if not null, and its {@code name}.pem. */
    private static void selfSigned(String name, String san, String startDate) throws Exception {
        List<String> args = new ArrayList<>(java.util.Arrays.asList("-genkeypair", "-alias", name, "-keyalg", "RSA",
                "-keysize", "2048", "-dname", "CN=localhost", "-keystore", name + ".p12"));
        if (san != null) {
            args.addAll(java.util.Arrays.asList("-ext", san));
        }
        if (startDate != null) {
            args.addAll(java.util.Arrays.asList("-startdate", startDate, "-validity", "2"));
        } else {
            args.addAll(java.util.Arrays.asList("-validity", "3"));
        }
        TestCertificates.keytool(dir, args.toArray(new String[0]));
        TestCertificates.keytool(dir, "-exportcert", "-rfc", "-alias", name, "-keystore", name + ".p12", "-file",
                name + ".pem");
    }

    private static void ca(String name, String dname) throws Exception {
        ca(name, dname, null, "3");
    }

    /** A CA key pair {@code name}.p12 valid from {@code startDate} (now if null) for {@code days}, and its PEM. */
    private static void ca(String name, String dname, String startDate, String days) throws Exception {
        List<String> args = new ArrayList<>(java.util.Arrays.asList("-genkeypair", "-alias", name, "-keyalg", "RSA",
                "-keysize", "2048", "-dname", dname, "-ext", "bc:c", "-validity", days, "-keystore", name + ".p12"));
        if (startDate != null) {
            args.addAll(java.util.Arrays.asList("-startdate", startDate));
        }
        TestCertificates.keytool(dir, args.toArray(new String[0]));
        TestCertificates.keytool(dir, "-exportcert", "-rfc", "-alias", name, "-keystore", name + ".p12", "-file",
                name + ".pem");
    }

    /**
     * A server context that serves the key of alias {@code server} in {@code keystore} with only its leaf certificate,
     * so the backend presents no CA certificate and the trust anchor comes from the given certificates alone.
     */
    private static SSLContext leafOnlyServer(File keystore) throws Exception {
        KeyStore source = TestCertificates.load(keystore);
        char[] password = TestCertificates.PASSWORD.toCharArray();
        Key key = source.getKey("server", password);
        Certificate leaf = source.getCertificateChain("server")[0];
        KeyStore leafOnly = KeyStore.getInstance("PKCS12");
        leafOnly.load(null, null);
        leafOnly.setKeyEntry("server", key, password, new Certificate[] {leaf});
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(leafOnly, password);
        SSLContext c = SSLContext.getInstance("TLS");
        c.init(kmf.getKeyManagers(), null, null);
        return c;
    }

    /** A server key pair {@code name}.p12 whose certificate the CA {@code ca} issued with {@code san}. */
    private static void issued(String ca, String name, String dname, String san) throws Exception {
        TestCertificates.keytool(dir, "-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048",
                "-dname", dname, "-validity", "3", "-keystore", name + ".p12");
        TestCertificates.keytool(dir, "-certreq", "-alias", "server", "-keystore", name + ".p12", "-file",
                name + ".csr");
        TestCertificates.keytool(dir, "-gencert", "-rfc", "-alias", ca, "-keystore", ca + ".p12", "-infile",
                name + ".csr", "-outfile", name + ".pem", "-ext", san, "-validity", "3");
        TestCertificates.keytool(dir, "-importcert", "-noprompt", "-alias", ca, "-file", ca + ".pem", "-keystore",
                name + ".p12");
        TestCertificates.keytool(dir, "-importcert", "-noprompt", "-alias", "server", "-file", name + ".pem",
                "-keystore", name + ".p12");
    }

    /**
     * The cross-signed fixture: an intermediate key pair {@code cross}.p12 whose certificate request both the expired
     * CA and the private CA sign (same subject, same key), and a leaf for localhost and 127.0.0.1 that the
     * intermediate's key signs.
     */
    private static void crossSigned() throws Exception {
        TestCertificates.keytool(dir, "-genkeypair", "-alias", "cross", "-keyalg", "RSA", "-keysize", "2048",
                "-dname", "CN=NaruServe Cross Intermediate", "-ext", "bc:c", "-validity", "3", "-keystore",
                "cross.p12");
        TestCertificates.keytool(dir, "-certreq", "-alias", "cross", "-keystore", "cross.p12", "-file", "cross.csr");
        TestCertificates.keytool(dir, "-gencert", "-rfc", "-alias", "expiredca", "-keystore", "expiredca.p12",
                "-infile", "cross.csr", "-outfile", "crossbyexpired.pem", "-ext", "bc:c", "-validity", "3");
        TestCertificates.keytool(dir, "-gencert", "-rfc", "-alias", "ca", "-keystore", "ca.p12", "-infile",
                "cross.csr", "-outfile", "crossbyca.pem", "-ext", "bc:c", "-validity", "3");
        TestCertificates.keytool(dir, "-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048",
                "-dname", "CN=localhost", "-validity", "3", "-keystore", "crossleaf.p12");
        TestCertificates.keytool(dir, "-certreq", "-alias", "server", "-keystore", "crossleaf.p12", "-file",
                "crossleaf.csr");
        TestCertificates.keytool(dir, "-gencert", "-rfc", "-alias", "cross", "-keystore", "cross.p12", "-infile",
                "crossleaf.csr", "-outfile", "crossleaf.pem", "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-validity",
                "3");
        caCert = TestCertificates.parse(caPem);
        crossByExpiredCa = TestCertificates.parse(TestCertificates.read(new File(dir, "crossbyexpired.pem")));
        crossByCa = TestCertificates.parse(TestCertificates.read(new File(dir, "crossbyca.pem")));
        crossLeaf = TestCertificates.parse(TestCertificates.read(new File(dir, "crossleaf.pem")));
        crossLeafKey = (PrivateKey) TestCertificates.load(new File(dir, "crossleaf.p12"))
                .getKey("server", TestCertificates.PASSWORD.toCharArray());
        // the fixture: the expired CA and the private CA both issued the intermediate, which issued the leaf
        X509Certificate expiredCa = TestCertificates.parse(expiredCaPem);
        try {
            expiredCa.checkValidity();
            throw new AssertionError("the expired CA must be expired");
        } catch (java.security.cert.CertificateExpiredException expected) {
            // fixture ok
        }
        crossByExpiredCa.verify(expiredCa.getPublicKey());
        crossByCa.verify(caCert.getPublicKey());
        assertEquals(crossByExpiredCa.getSubjectX500Principal(), crossByCa.getSubjectX500Principal());
        assertEquals(crossByExpiredCa.getPublicKey(), crossByCa.getPublicKey());
        crossLeaf.verify(crossByCa.getPublicKey());
        crossByExpiredCa.checkValidity();
        crossByCa.checkValidity();
        crossLeaf.checkValidity();
    }

    /**
     * Expired certificates around the private CA: its own request signed by the expired CA (an old cross-sign) and by
     * the CA itself (the copy before a renewal with the same name and key), both valid 2000-01-01 for 2 days; and an
     * intermediate the CA issued with the same past validity, which issued a currently valid leaf for localhost and
     * 127.0.0.1.
     */
    private static void expiredCopiesAndIntermediate() throws Exception {
        TestCertificates.keytool(dir, "-certreq", "-alias", "ca", "-keystore", "ca.p12", "-file", "ca.csr");
        TestCertificates.keytool(dir, "-gencert", "-rfc", "-alias", "expiredca", "-keystore", "expiredca.p12",
                "-infile", "ca.csr", "-outfile", "expiredcrossofca.pem", "-ext", "bc:c", "-startdate", "2000/01/01",
                "-validity", "2");
        TestCertificates.keytool(dir, "-gencert", "-rfc", "-alias", "ca", "-keystore", "ca.p12", "-infile", "ca.csr",
                "-outfile", "prerenewalca.pem", "-ext", "bc:c", "-startdate", "2000/01/01", "-validity", "2");
        TestCertificates.keytool(dir, "-genkeypair", "-alias", "expint", "-keyalg", "RSA", "-keysize", "2048",
                "-dname", "CN=NaruServe Expired Intermediate", "-ext", "bc:c", "-validity", "3", "-keystore",
                "expint.p12");
        TestCertificates.keytool(dir, "-certreq", "-alias", "expint", "-keystore", "expint.p12", "-file",
                "expint.csr");
        TestCertificates.keytool(dir, "-gencert", "-rfc", "-alias", "ca", "-keystore", "ca.p12", "-infile",
                "expint.csr", "-outfile", "expint.pem", "-ext", "bc:c", "-startdate", "2000/01/01", "-validity", "2");
        TestCertificates.keytool(dir, "-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048",
                "-dname", "CN=localhost", "-validity", "3", "-keystore", "expintleaf.p12");
        TestCertificates.keytool(dir, "-certreq", "-alias", "server", "-keystore", "expintleaf.p12", "-file",
                "expintleaf.csr");
        TestCertificates.keytool(dir, "-gencert", "-rfc", "-alias", "expint", "-keystore", "expint.p12", "-infile",
                "expintleaf.csr", "-outfile", "expintleaf.pem", "-ext", "SAN=dns:localhost,ip:127.0.0.1",
                "-validity", "3");
        caLeaf = TestCertificates.parse(TestCertificates.read(new File(dir, "good.pem")));
        caLeafKey = (PrivateKey) TestCertificates.load(new File(dir, "good.p12"))
                .getKey("server", TestCertificates.PASSWORD.toCharArray());
        expiredCrossOfCa = TestCertificates.parse(TestCertificates.read(new File(dir, "expiredcrossofca.pem")));
        preRenewalCa = TestCertificates.parse(TestCertificates.read(new File(dir, "prerenewalca.pem")));
        expiredIntermediate = TestCertificates.parse(TestCertificates.read(new File(dir, "expint.pem")));
        expiredIntermediateLeaf = TestCertificates.parse(TestCertificates.read(new File(dir, "expintleaf.pem")));
        expiredIntermediateLeafKey = (PrivateKey) TestCertificates.load(new File(dir, "expintleaf.p12"))
                .getKey("server", TestCertificates.PASSWORD.toCharArray());
        // the fixture: two expired certificates with the CA's name and key, and an expired intermediate on a leaf
        caLeaf.verify(caCert.getPublicKey());
        caLeaf.checkValidity();
        for (X509Certificate copy : new X509Certificate[] {expiredCrossOfCa, preRenewalCa}) {
            assertEquals(caCert.getSubjectX500Principal(), copy.getSubjectX500Principal());
            assertEquals(caCert.getPublicKey(), copy.getPublicKey());
            assertExpired(copy);
        }
        expiredCrossOfCa.verify(TestCertificates.parse(expiredCaPem).getPublicKey());
        preRenewalCa.verify(caCert.getPublicKey());
        expiredIntermediate.verify(caCert.getPublicKey());
        assertExpired(expiredIntermediate);
        expiredIntermediateLeaf.verify(expiredIntermediate.getPublicKey());
        expiredIntermediateLeaf.checkValidity();
    }

    private static void assertExpired(X509Certificate c) {
        try {
            c.checkValidity();
            throw new AssertionError("must be expired: " + c.getSubjectX500Principal());
        } catch (java.security.cert.CertificateExpiredException expected) {
            // fixture ok
        } catch (java.security.cert.CertificateNotYetValidException e) {
            throw new AssertionError("must be expired, not yet valid: " + c.getSubjectX500Principal(), e);
        }
    }

    /**
     * A server context that presents exactly {@code chain}, in that order, with the cross-signed leaf's key; a
     * {@code KeyStore} entry cannot hold a chain whose issuer links are out of order.
     */
    private static SSLContext presenting(final X509Certificate... chain) throws Exception {
        return presentingWithKey(crossLeafKey, chain);
    }

    /** A server context that presents exactly {@code chain}, in that order, with {@code key}. */
    private static SSLContext presentingWithKey(final PrivateKey key, final X509Certificate... chain)
            throws Exception {
        X509ExtendedKeyManager km = new X509ExtendedKeyManager() {
            @Override
            public String[] getClientAliases(String keyType, Principal[] issuers) {
                return null;
            }

            @Override
            public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
                return null;
            }

            @Override
            public String[] getServerAliases(String keyType, Principal[] issuers) {
                return new String[] {"server"};
            }

            @Override
            public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
                return keyType.startsWith("RSA") ? "server" : null;
            }

            @Override
            public String chooseEngineServerAlias(String keyType, Principal[] issuers, SSLEngine engine) {
                return keyType.startsWith("RSA") ? "server" : null;
            }

            @Override
            public X509Certificate[] getCertificateChain(String alias) {
                return chain.clone();
            }

            @Override
            public PrivateKey getPrivateKey(String alias) {
                return key;
            }
        };
        SSLContext c = SSLContext.getInstance("TLS");
        c.init(new KeyManager[] {km}, null, null);
        return c;
    }

    private static X509Certificate caCertificate() throws Exception {
        return TestCertificates.parse(caPem);
    }

    // ---- the app ----

    /** Mounts {@code proxy} at {@code /api}, records what reaches onError, and starts the app. */
    private void mount(Proxy proxy) {
        app.use("/api", proxy);
        start();
    }

    private void start() {
        app.onError((error, req, res) -> errors.add(error));
        t.start();
        started = true;
    }

    private ClientResponse get() {
        return get("/api/x");
    }

    private ClientResponse get(String path) {
        return adapter.send(ClientRequest.of("GET", path));
    }

    private TlsBackend tlsBackend(SSLContext context) throws IOException {
        TlsBackend b = new TlsBackend(context);
        closeables.add(b);
        return b;
    }

    private void assertReached(ClientResponse r, TlsBackend backend) {
        assertEquals("status (errors: " + errors + ")", 200, r.status());
        assertEquals("ok", r.text());
        assertTrue(backend.requestHead(), backend.requestHead().startsWith("GET /api/x HTTP/1.1\r\n"));
        assertTrue("errors: " + errors, errors.isEmpty());
    }

    private void assertConnectFailed(ClientResponse r, TlsBackend backend) {
        assertEquals(502, r.status());
        synchronized (errors) {
            assertEquals("errors that reached onError: " + errors, 1, errors.size());
            assertTrue("a ProxyException: " + errors.get(0), errors.get(0) instanceof ProxyException);
            assertSame(ProxyException.Kind.CONNECT_FAILED, ((ProxyException) errors.get(0)).kind());
        }
        assertEquals("request bytes the backend read", 0, backend.requestBytes());
    }

    // ---- AC-1 ----

    // FR-PROXY-022 AC-1: with .trustCertificates(pem) holding the backend's self-signed certificate whose subject
    // alternative name matches localhost, the request reaches the backend
    @Test(timeout = 30000)
    public void fr_PROXY_022_AC1_aSelfSignedBackendIsReachedWithItsPem() throws IOException {
        TlsBackend backend = tlsBackend(selfServer);
        mount(Proxy.to("https://localhost:" + backend.port()).trustCertificates(selfPem));

        assertReached(get(), backend);
    }

    // FR-PROXY-022 AC-1: the same holds for the IP literal target 127.0.0.1, matched by the iPAddress name
    @Test(timeout = 30000)
    public void fr_PROXY_022_AC1_aSelfSignedBackendIsReachedAtItsIpLiteral() throws IOException {
        TlsBackend backend = tlsBackend(selfServer);
        mount(Proxy.to("https://127.0.0.1:" + backend.port()).trustCertificates(selfPem));

        assertReached(get(), backend);
    }

    // FR-PROXY-022 AC-1: the same mount without the option gives 502 CONNECT_FAILED (the JDK default trust)
    @Test(timeout = 30000)
    public void fr_PROXY_022_AC1_withoutTheOptionTheSelfSignedBackendIsConnectFailed() throws IOException {
        TlsBackend backend = tlsBackend(selfServer);
        mount(Proxy.to("https://localhost:" + backend.port()));

        assertConnectFailed(get(), backend);
    }

    // ---- AC-2 ----

    // FR-PROXY-022 AC-2: with the X509Certificate overload holding a private CA, a backend whose certificate that CA
    // issued for the target host is reached
    @Test(timeout = 30000)
    public void fr_PROXY_022_AC2_aPrivateCaCertificateTrustsWhatItIssuedForTheTargetHost() throws Exception {
        TlsBackend backend = tlsBackend(caGoodServer);
        mount(Proxy.to("https://localhost:" + backend.port()).trustCertificates(caCertificate()));

        assertReached(get(), backend);
    }

    // FR-PROXY-022 AC-2: a certificate the same CA issued for another host gives 502 CONNECT_FAILED: the host name
    // check stays on
    @Test(timeout = 30000)
    public void fr_PROXY_022_AC2_theHostNameCheckStaysOnWithTheTrustedCa() throws Exception {
        TlsBackend backend = tlsBackend(caOtherHostServer);
        mount(Proxy.to("https://localhost:" + backend.port()).trustCertificates(caCertificate()));

        assertConnectFailed(get(), backend);
    }

    // FR-PROXY-022 AC-2: a certificate another CA issued for the target host gives 502 CONNECT_FAILED: only the given
    // certificates are trusted
    @Test(timeout = 30000)
    public void fr_PROXY_022_AC2_aCertificateOfAnotherCaIsConnectFailed() throws Exception {
        TlsBackend backend = tlsBackend(otherCaServer);
        mount(Proxy.to("https://localhost:" + backend.port()).trustCertificates(caCertificate()));

        assertConnectFailed(get(), backend);
    }

    // ---- AC-3 ----

    // FR-PROXY-022 AC-3: a trusted self-signed certificate whose only name is its subject common name gives 502
    // CONNECT_FAILED: the proxy's host name check does not fall back to the CN (SEC-PROXY-005 AC-10)
    @Test(timeout = 30000)
    public void fr_PROXY_022_AC3_aCommonNameOnlyCertificateIsConnectFailed() throws IOException {
        TlsBackend backend = tlsBackend(cnOnlyServer);
        mount(Proxy.to("https://localhost:" + backend.port()).trustCertificates(cnOnlyPem));

        assertConnectFailed(get(), backend);
    }

    // FR-PROXY-022 AC-3: an expired trusted self-signed certificate gives 502 CONNECT_FAILED: the validity period is
    // still checked
    @Test(timeout = 30000)
    public void fr_PROXY_022_AC3_anExpiredTrustedSelfSignedCertificateIsConnectFailed() throws IOException {
        TlsBackend backend = tlsBackend(expiredServer);
        mount(Proxy.to("https://localhost:" + backend.port()).trustCertificates(expiredPem));

        assertConnectFailed(get(), backend);
    }

    // FR-PROXY-022 AC-3: an expired trusted private CA gives 502 CONNECT_FAILED even when the backend presents only a
    // currently valid leaf it issued: the validity period of the given certificate the chain ends at is checked
    @Test(timeout = 30000)
    public void fr_PROXY_022_AC3_anExpiredTrustedPrivateCaIsConnectFailed() throws IOException {
        TlsBackend backend = tlsBackend(expiredCaLeafServer);
        mount(Proxy.to("https://localhost:" + backend.port()).trustCertificates(expiredCaPem));

        assertConnectFailed(get(), backend);
    }

    // FR-PROXY-022 AC-3: the same holds for the X509Certificate overload
    @Test(timeout = 30000)
    public void fr_PROXY_022_AC3_anExpiredTrustedPrivateCaCertificateObjectIsConnectFailed() throws Exception {
        TlsBackend backend = tlsBackend(expiredCaLeafServer);
        mount(Proxy.to("https://localhost:" + backend.port()).trustCertificates(TestCertificates.parse(expiredCaPem)));

        assertConnectFailed(get(), backend);
    }

    // FR-PROXY-022 AC-3: a trusted private CA that is not yet valid gives 502 CONNECT_FAILED as well
    @Test(timeout = 30000)
    public void fr_PROXY_022_AC3_aNotYetValidTrustedPrivateCaIsConnectFailed() throws IOException {
        TlsBackend backend = tlsBackend(futureCaLeafServer);
        mount(Proxy.to("https://localhost:" + backend.port()).trustCertificates(futureCaPem));

        assertConnectFailed(get(), backend);
    }

    // FR-PROXY-022 AC-3: an expired certificate in a PEM bundle that the presented chain does not end at does not
    // break a valid path: an expired self-signed certificate, an expired CA, and an expired CA with the valid CA's
    // name but another key sit next to the valid CA, and the backend presenting only the valid CA's leaf is reached
    @Test(timeout = 30000)
    public void fr_PROXY_022_AC3_anUnrelatedExpiredCertificateInTheBundleDoesNotBreakAValidPath() throws IOException {
        TlsBackend backend = tlsBackend(caLeafOnlyServer);
        String bundle = expiredPem + staleSameNameCaPem + expiredCaPem + caPem + futureCaPem;
        mount(Proxy.to("https://localhost:" + backend.port()).trustCertificates(bundle));

        assertReached(get(), backend);
    }

    // FR-PROXY-022 AC-2: a cross-signed intermediate. With the expired CA and the valid private CA given, a backend
    // presenting [leaf, intermediate by the expired CA, the same intermediate by the private CA] is reached: the leaf
    // chains through the second intermediate to the valid private CA, which trusts what it issued, and the expired CA
    // is a given certificate that path does not need
    @Test(timeout = 30000)
    public void fr_PROXY_022_AC2_aCrossSignedChainReachingTheValidGivenCaIsReachedDespiteAnExpiredGivenRoot()
            throws Exception {
        TlsBackend backend = tlsBackend(presenting(crossLeaf, crossByExpiredCa, crossByCa));
        mount(Proxy.to("https://localhost:" + backend.port()).trustCertificates(expiredCaPem + caPem));

        assertReached(get(), backend);
    }

    // FR-PROXY-022 AC-2: the same cross-signed chain presented in the other order [leaf, intermediate by the private
    // CA, intermediate by the expired CA] is reached as well; the order the backend sends does not decide
    @Test(timeout = 30000)
    public void fr_PROXY_022_AC2_aCrossSignedChainInTheOtherOrderIsReachedDespiteAnExpiredGivenRoot()
            throws Exception {
        TlsBackend backend = tlsBackend(presenting(crossLeaf, crossByCa, crossByExpiredCa));
        mount(Proxy.to("https://localhost:" + backend.port()).trustCertificates(expiredCaPem + caPem));

        assertReached(get(), backend);
    }

    // FR-PROXY-022 AC-3: with only the expired CA given, the same cross-signed chain gives 502 CONNECT_FAILED: every
    // path from the leaf to a given certificate ends at the expired CA
    @Test(timeout = 30000)
    public void fr_PROXY_022_AC3_aCrossSignedChainWhoseOnlyGivenRootIsExpiredIsConnectFailed() throws Exception {
        TlsBackend backend = tlsBackend(presenting(crossLeaf, crossByExpiredCa, crossByCa));
        mount(Proxy.to("https://localhost:" + backend.port()).trustCertificates(expiredCaPem));

        assertConnectFailed(get(), backend);
    }

    // FR-PROXY-022 AC-3: a valid given certificate the backend sends but the leaf does not chain through does not hide
    // the expired given CA the leaf does chain to: [leaf, the private CA's certificate, intermediate by the expired
    // CA] with both CAs given gives 502 CONNECT_FAILED
    @Test(timeout = 30000)
    public void fr_PROXY_022_AC3_aValidGivenCertificateOffThePathDoesNotHideTheExpiredGivenCa() throws Exception {
        TlsBackend backend = tlsBackend(presenting(crossLeaf, caCert, crossByExpiredCa));
        mount(Proxy.to("https://localhost:" + backend.port()).trustCertificates(expiredCaPem + caPem));

        assertConnectFailed(get(), backend);
    }

    // FR-PROXY-022 AC-2: with .trustCertificates(caPem), a backend whose certificate that CA issued for the target
    // host is reached although it also sends, after the valid CA certificate, an old expired cross-sign of that CA (a
    // common leftover after a root rollover): the path [leaf, CA] is valid and the extra certificate is not on it, as
    // the JDK trust over the same CA accepts
    @Test(timeout = 30000)
    public void fr_PROXY_022_AC2_anExpiredCrossSignAfterThePresentedGivenCaDoesNotRejectAValidChain()
            throws Exception {
        TlsBackend backend = tlsBackend(presentingWithKey(caLeafKey, caLeaf, caCert, expiredCrossOfCa));
        mount(Proxy.to("https://localhost:" + backend.port()).trustCertificates(caPem));

        assertReached(get(), backend);
    }

    // FR-PROXY-022 AC-2: the same chain with the expired cross-sign sent before the valid CA certificate
    // [leaf, expired cross-sign, CA] is reached as well; the order the backend sends does not decide
    @Test(timeout = 30000)
    public void fr_PROXY_022_AC2_anExpiredCrossSignBeforeThePresentedGivenCaDoesNotRejectAValidChain()
            throws Exception {
        TlsBackend backend = tlsBackend(presentingWithKey(caLeafKey, caLeaf, expiredCrossOfCa, caCert));
        mount(Proxy.to("https://localhost:" + backend.port()).trustCertificates(caPem));

        assertReached(get(), backend);
    }

    // FR-PROXY-022 AC-2: the CA was renewed with the same name and key and the renewed, valid certificate is given;
    // the backend still sends the expired pre-renewal copy after its leaf. The leaf that CA issued for the target host
    // is valid and so is the given CA, so the backend is reached, as the JDK trust over the renewed CA accepts
    @Test(timeout = 30000)
    public void fr_PROXY_022_AC2_anExpiredPreRenewalCopyOfTheGivenCaSentByTheBackendDoesNotRejectAValidChain()
            throws Exception {
        TlsBackend backend = tlsBackend(presentingWithKey(caLeafKey, caLeaf, preRenewalCa));
        mount(Proxy.to("https://localhost:" + backend.port()).trustCertificates(caPem));

        assertReached(get(), backend);
    }

    // FR-PROXY-022 AC-3: an expired intermediate on the only path from the leaf to the valid given CA gives 502
    // CONNECT_FAILED: [leaf, expired intermediate the CA issued, CA] with the CA given
    @Test(timeout = 30000)
    public void fr_PROXY_022_AC3_anExpiredIntermediateOnTheOnlyPathIsConnectFailed() throws Exception {
        TlsBackend backend = tlsBackend(presentingWithKey(expiredIntermediateLeafKey, expiredIntermediateLeaf,
                expiredIntermediate, caCert));
        mount(Proxy.to("https://localhost:" + backend.port()).trustCertificates(caPem));

        assertConnectFailed(get(), backend);
    }

    // ---- AC-4 ----

    // FR-PROXY-022 AC-4: a PEM text with several CERTIFICATE blocks and other text around them trusts every one
    @Test(timeout = 30000)
    public void fr_PROXY_022_AC4_aPemWithSeveralBlocksAndOtherTextTrustsEveryBlock() throws IOException {
        TlsBackend self = tlsBackend(selfServer);
        TlsBackend issued = tlsBackend(caGoodServer);
        String pem = "Bag Attributes\n    friendlyName: self\nsubject=CN=localhost\n" + selfPem
                + "\nsome text between the blocks\r\n" + caPem.replace("\n", "\r\n") + "trailing text";
        Proxy p = Proxy.to("https://localhost:" + self.port()).trustCertificates(pem);
        app.use("/a", p.stripPrefix(true));
        app.use("/b", Proxy.to("https://localhost:" + issued.port()).trustCertificates(pem).stripPrefix(true));
        start();

        ClientResponse a = get("/a/api/x");
        ClientResponse b = get("/b/api/x");

        assertReached(a, self);
        assertReached(b, issued);
    }

    // FR-PROXY-022 AC-4: null arguments and null elements throw NullPointerException at the call
    @Test
    public void fr_PROXY_022_AC4_nullsThrowNullPointerException() throws Exception {
        Proxy p = Proxy.to("https://localhost:8443");
        X509Certificate ca = caCertificate();
        assertNpe("trustCertificates((String) null)", () -> p.trustCertificates((String) null));
        assertNpe("trustCertificates((X509Certificate[]) null)", () -> p.trustCertificates((X509Certificate[]) null));
        assertNpe("an array holding null", () -> p.trustCertificates(ca, null));
        assertNpe("an array of one null", () -> p.trustCertificates(new X509Certificate[] {null}));
    }

    // FR-PROXY-022 AC-4: a PEM text with no CERTIFICATE block, a block whose base64 or DER does not parse, and an empty
    // array throw IllegalArgumentException at the call
    @Test
    public void fr_PROXY_022_AC4_unparsableInputThrowsIllegalArgumentExceptionAtTheCall() throws Exception {
        Proxy p = Proxy.to("https://localhost:8443");
        String body = selfPem.substring(selfPem.indexOf('\n') + 1, selfPem.indexOf("-----END"));
        assertIae("empty text", () -> p.trustCertificates(""));
        assertIae("text with no block", () -> p.trustCertificates("no certificate here"));
        assertIae("a private key block only",
                () -> p.trustCertificates("-----BEGIN PRIVATE KEY-----\nAAAA\n-----END PRIVATE KEY-----\n"));
        assertIae("a block without its END line", () -> p.trustCertificates("-----BEGIN CERTIFICATE-----\n" + body));
        assertIae("an empty block",
                () -> p.trustCertificates("-----BEGIN CERTIFICATE-----\n-----END CERTIFICATE-----\n"));
        assertIae("a block whose base64 does not parse", () -> p.trustCertificates(
                "-----BEGIN CERTIFICATE-----\n" + body.replace(body.substring(10, 14), "!@#$")
                        + "-----END CERTIFICATE-----\n"));
        assertIae("a block whose base64 has a bad length", () -> p.trustCertificates(
                "-----BEGIN CERTIFICATE-----\nMIIBAAA\n-----END CERTIFICATE-----\n"));
        assertIae("a block whose DER does not parse", () -> p.trustCertificates(
                "-----BEGIN CERTIFICATE-----\naGVsbG8gd29ybGQ=\n-----END CERTIFICATE-----\n"));
        assertIae("a good block followed by a bad one", () -> p.trustCertificates(
                selfPem + "-----BEGIN CERTIFICATE-----\naGVsbG8gd29ybGQ=\n-----END CERTIFICATE-----\n"));
        assertIae("an empty array", () -> p.trustCertificates(new X509Certificate[0]));
        assertIae("no vararg argument", () -> p.trustCertificates());
    }

    // FR-PROXY-022 AC-4: a CERTIFICATE block whose DER is a whole certificate followed by extra bytes does not parse as
    // one certificate and throws IllegalArgumentException at the call
    @Test
    public void fr_PROXY_022_AC4_aBlockWhoseDerHasTrailingBytesThrowsIllegalArgumentException() throws Exception {
        Proxy p = Proxy.to("https://localhost:8443");
        byte[] der = TestCertificates.parse(selfPem).getEncoded();
        byte[] junk = {0x04, 0x04, 'j', 'u', 'n', 'k'};
        byte[] longer = java.util.Arrays.copyOf(der, der.length + junk.length);
        System.arraycopy(junk, 0, longer, der.length, junk.length);
        String block = "-----BEGIN CERTIFICATE-----\n"
                + java.util.Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(longer)
                + "\n-----END CERTIFICATE-----\n";

        assertIae("a block whose DER is a certificate plus 6 trailing bytes", () -> p.trustCertificates(block));
        assertIae("the same block after a good one", () -> p.trustCertificates(selfPem + block));
    }

    private interface Call {
        void run() throws Exception;
    }

    private static void assertNpe(String what, Call call) throws Exception {
        try {
            call.run();
            fail(what + ": no NullPointerException");
        } catch (NullPointerException expected) {
            // thrown at the call
        }
    }

    private static void assertIae(String what, Call call) throws Exception {
        try {
            call.run();
            fail(what + ": no IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // thrown at the call
        }
    }

    // ---- AC-5 ----

    // FR-PROXY-022 AC-5: p.trustCertificates(pem).sslSocketFactory(f) uses f: the CA-issued backend that only f trusts
    // is reached
    @Test(timeout = 30000)
    public void fr_PROXY_022_AC5_aLaterSslSocketFactoryWins() throws IOException {
        TlsBackend backend = tlsBackend(caGoodServer);
        Proxy p = Proxy.to("https://localhost:" + backend.port());
        Proxy q = p.trustCertificates(selfPem).sslSocketFactory(caFactory);
        assertSame(caFactory, q.sslSocketFactory());
        mount(q);

        assertReached(get(), backend);
    }

    // FR-PROXY-022 AC-5: p.sslSocketFactory(f).trustCertificates(pem) trusts pem: the self-signed backend that f does
    // not trust is reached
    @Test(timeout = 30000)
    public void fr_PROXY_022_AC5_aLaterTrustCertificatesWins() throws IOException {
        TlsBackend backend = tlsBackend(selfServer);
        Proxy p = Proxy.to("https://localhost:" + backend.port());
        Proxy withFactory = p.sslSocketFactory(caFactory);
        Proxy q = withFactory.trustCertificates(selfPem);
        assertNotSame(caFactory, q.sslSocketFactory());
        mount(q);

        assertReached(get(), backend);
    }

    // FR-PROXY-022 AC-5: the Proxy objects the calls start from stay unchanged
    @Test
    public void fr_PROXY_022_AC5_theReceiversStayUnchanged() throws Exception {
        Proxy p = Proxy.to("https://localhost:8443");
        Proxy withFactory = p.sslSocketFactory(caFactory);
        String before = p.describe();
        String withFactoryBefore = withFactory.describe();

        Proxy q = p.trustCertificates(selfPem);
        Proxy r = p.trustCertificates(caCertificate());
        Proxy s = withFactory.trustCertificates(selfPem);

        assertNotSame(p, q);
        assertNotSame(p, r);
        assertNotSame(withFactory, s);
        assertEquals(before, p.describe());
        assertNull(p.sslSocketFactory());
        assertEquals(withFactoryBefore, withFactory.describe());
        assertSame(caFactory, withFactory.sslSocketFactory());
        assertTrue(q.sslSocketFactory() != null && r.sslSocketFactory() != null && s.sslSocketFactory() != null);
    }

    // FR-PROXY-022 AC-5: a Proxy with trustCertificates forwards a plain HTTP target without the certificates
    @Test(timeout = 30000)
    public void fr_PROXY_022_AC5_aPlainHttpTargetIsForwarded() throws IOException {
        RawBackend backend = RawBackend.answering(RawBackend.response(200, "plain"));
        closeables.add(backend);
        mount(Proxy.to(backend.url()).trustCertificates(selfPem));

        ClientResponse r = get();

        assertEquals("status (errors: " + errors + ")", 200, r.status());
        assertEquals("plain", r.text());
        assertEquals("GET /api/x HTTP/1.1", backend.onlyRequest().requestLine());
    }
}
