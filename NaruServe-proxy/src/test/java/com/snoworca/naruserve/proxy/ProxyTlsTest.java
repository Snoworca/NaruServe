package com.snoworca.naruserve.proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.testing.StubJsonCodec;
import com.snoworca.naruserve.testing.TestApp;
import com.snoworca.naruserve.tinyhttpd.TinyHttpdAdapter;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIMatcher;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.X509TrustManager;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * The https backends of the proxy (SEC-PROXY-005): a plain connect wrapped by
 * {@code createSocket(plain, host, port, true)}, SNI with the target host, certificate and host name verification
 * that stay on, the proxy's own certificate host name check after every handshake with the reflective endpoint
 * identification as a second layer, the handshake under the total-timeout watchdog, and every TLS failure as
 * {@code CONNECT_FAILED}. A private CA and two server
 * certificates are made with the JDK's {@code keytool} once per class: one for {@code localhost},
 * {@code backend.test} and {@code 127.0.0.1}, one for {@code other.example} only.
 */
public class ProxyTlsTest {

    private static final char[] PASSWORD = "changeit".toCharArray();

    private static File dir;
    /** Trusts the private CA and nothing else. */
    private static SSLContext trustingClient;
    /** Serves the certificate for localhost, backend.test and 127.0.0.1, signed by the private CA. */
    private static SSLContext goodServer;
    /** Serves the certificate for other.example only, signed by the private CA. */
    private static SSLContext otherHostServer;

    private final InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
    private final TestApp t = TestApp.create(adapter);
    private final RestApp app = t.app();
    private final List<Closeable> closeables = new ArrayList<>();
    private final List<Throwable> errors = Collections.synchronizedList(new ArrayList<Throwable>());
    private boolean started;

    @BeforeClass
    public static void makeCertificates() throws Exception {
        dir = java.nio.file.Files.createTempDirectory("naruserve-proxy-tls").toFile();
        keytool("-genkeypair", "-alias", "ca", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=NaruServe Test CA",
                "-ext", "bc:c", "-validity", "3", "-keystore", "ca.p12");
        keytool("-exportcert", "-rfc", "-alias", "ca", "-keystore", "ca.p12", "-file", "ca.pem");
        server("good", "CN=localhost", "SAN=dns:localhost,dns:backend.test,ip:127.0.0.1");
        server("other", "CN=other.example", "SAN=dns:other.example");
        keytool("-importcert", "-noprompt", "-alias", "ca", "-file", "ca.pem", "-keystore", "trust.p12");

        trustingClient = SSLContext.getInstance("TLS");
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(load("trust.p12"));
        trustingClient.init(null, tmf.getTrustManagers(), null);
        goodServer = serverContext("good.p12");
        otherHostServer = serverContext("other.p12");
    }

    @AfterClass
    public static void deleteCertificates() {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                assertTrue(f.delete());
            }
        }
        assertTrue(dir.delete());
    }

    @After
    public void tearDown() throws IOException {
        TlsConnector.endpointIdentification = true;
        TlsConnector.hostnameVerifier = null;
        BackendClient.resolver = BackendClient.SYSTEM_RESOLVER;
        if (started) {
            app.shutdown(2, TimeUnit.SECONDS);
        }
        for (Closeable c : closeables) {
            c.close();
        }
    }

    // ---- certificates ----

    private static void server(String name, String dname, String san) throws Exception {
        keytool("-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048", "-dname", dname,
                "-validity", "3", "-keystore", name + ".p12");
        keytool("-certreq", "-alias", "server", "-keystore", name + ".p12", "-file", name + ".csr");
        keytool("-gencert", "-rfc", "-alias", "ca", "-keystore", "ca.p12", "-infile", name + ".csr", "-outfile",
                name + ".pem", "-ext", san, "-validity", "3");
        keytool("-importcert", "-noprompt", "-alias", "ca", "-file", "ca.pem", "-keystore", name + ".p12");
        keytool("-importcert", "-noprompt", "-alias", "server", "-file", name + ".pem", "-keystore", name + ".p12");
    }

    private static void keytool(String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(new File(System.getProperty("java.home"), "bin/keytool").getPath());
        command.addAll(Arrays.asList(args));
        command.addAll(Arrays.asList("-storetype", "PKCS12", "-storepass", "changeit", "-keypass", "changeit"));
        Process p = new ProcessBuilder(command).directory(dir).redirectErrorStream(true).start();
        String out = new String(readAll(p.getInputStream()), StandardCharsets.UTF_8);
        if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) {
            throw new AssertionError("keytool " + Arrays.asList(args) + " failed:\n" + out);
        }
    }

    private static KeyStore load(String name) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = new FileInputStream(new File(dir, name))) {
            ks.load(in, PASSWORD);
        }
        return ks;
    }

    private static SSLContext serverContext(String keystore) throws Exception {
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(load(keystore), PASSWORD);
        SSLContext c = SSLContext.getInstance("TLS");
        c.init(kmf.getKeyManagers(), null, null);
        return c;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        for (int n; (n = in.read(buf)) != -1; ) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    // ---- the app ----

    /** Mounts {@code proxy} at {@code /api}, records what reaches onError, and starts the app. */
    private void mount(Proxy proxy) {
        app.use("/api", proxy);
        app.onError((error, req, res) -> errors.add(error));
        t.start();
        started = true;
    }

    private ClientResponse get() {
        return adapter.send(ClientRequest.of("GET", "/api/x"));
    }

    /** The only error that reached onError, as a ProxyException. */
    private ProxyException onlyProxyError() {
        synchronized (errors) {
            assertEquals("errors that reached onError: " + errors, 1, errors.size());
            assertTrue("a ProxyException: " + errors.get(0), errors.get(0) instanceof ProxyException);
            return (ProxyException) errors.get(0);
        }
    }

    private void assertConnectFailed(ClientResponse r) {
        assertEquals(502, r.status());
        assertSame(ProxyException.Kind.CONNECT_FAILED, onlyProxyError().kind());
    }

    /** Sends {@code raw} to the TinyHttpd app at {@code port} and returns everything it answers until it closes. */
    private static String exchange(int port, String raw) throws IOException {
        try (Socket s = new Socket()) {
            s.connect(new java.net.InetSocketAddress("127.0.0.1", port), 5000);
            s.setSoTimeout(10_000);
            s.getOutputStream().write(raw.getBytes(StandardCharsets.ISO_8859_1));
            s.getOutputStream().flush();
            ByteArrayOutputStream got = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            try {
                for (int n; (n = s.getInputStream().read(buf)) >= 0; ) {
                    got.write(buf, 0, n);
                }
            } catch (java.net.SocketException reset) {
                // a reset after the close counts as the end
            }
            String text = new String(got.toByteArray(), StandardCharsets.ISO_8859_1);
            if (text.indexOf("\r\n") < 0) {
                throw new java.io.EOFException("no status line: " + text);
            }
            return text;
        }
    }

    private TlsBackend tlsBackend(SSLContext context) throws IOException {
        TlsBackend b = new TlsBackend(context);
        closeables.add(b);
        return b;
    }

    private PlainBackend plainBackend(boolean closeAtOnce) throws IOException {
        PlainBackend b = new PlainBackend(closeAtOnce);
        closeables.add(b);
        return b;
    }

    // ---- AC-1 ----

    // SEC-PROXY-005 AC-1: an https backend with a certificate from an untrusted CA gives 502 CONNECT_FAILED, and
    // with .retries(2) it sees exactly one TCP connection
    @Test(timeout = 30000)
    public void sec_PROXY_005_AC1_untrustedCaIsConnectFailedOnOneConnection() throws IOException {
        TlsBackend backend = tlsBackend(goodServer);
        mount(Proxy.to("https://localhost:" + backend.port()).retries(2));

        assertConnectFailed(get());
        assertEquals("TCP connections", 1, backend.connectionsBeforeSentinel());
        assertEquals("request bytes the backend read", 0, backend.requestBytes());
    }

    // SEC-PROXY-005 AC-1: an https backend whose certificate names another host gives 502 CONNECT_FAILED on one TCP
    // connection with .retries(2)
    @Test(timeout = 30000)
    public void sec_PROXY_005_AC1_certificateForAnotherHostIsConnectFailedOnOneConnection() throws IOException {
        TlsBackend backend = tlsBackend(otherHostServer);
        mount(Proxy.to("https://localhost:" + backend.port()).retries(2));

        assertConnectFailed(get());
        assertEquals("TCP connections", 1, backend.connectionsBeforeSentinel());
    }

    // FR-PROXY-015 AC-4 (PRD-99): on an app without onError over TinyHttpd, a TLS host name failure (the JDK's endpoint
    // identification names the target host in its message) gives the default 502 response whose body names neither the
    // target host nor the target port
    @Test(timeout = 30000)
    public void fr_PROXY_015_AC4_theDefaultBodyOfATlsHostNameFailureNamesNeitherHostNorPort() throws Exception {
        TlsBackend backend = tlsBackend(otherHostServer);
        BackendClient.resolver = new BackendClient.Resolver() {
            @Override
            public InetAddress resolve(String host) throws IOException {
                return InetAddress.getByName("127.0.0.1");
            }
        };
        RestApp g = RestApp.builder().httpAdapter(new TinyHttpdAdapter()).jsonCodec(new StubJsonCodec())
                .logger(new RecordingLogger()).build();
        String response;
        try {
            g.use("/api", Proxy.to("https://lp-tls.test:" + backend.port())
                    .sslSocketFactory(trustingClient.getSocketFactory()));
            int port = g.listen(0).port();
            response = exchange(port, "GET /api/x HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n");
        } finally {
            g.shutdown(2, TimeUnit.SECONDS);
        }

        assertEquals("HTTP/1.1 502 Bad Gateway", response.substring(0, response.indexOf("\r\n")));
        String body = response.substring(response.indexOf("\r\n\r\n") + 4);
        assertFalse("the body names the target host: " + body, body.contains("lp-tls"));
        assertFalse("the body names the target port: " + body, body.contains(Integer.toString(backend.port())));
        assertTrue("the body names a TLS cause: " + body, body.contains("Caused by: javax.net.ssl."));
    }

    // SEC-PROXY-005 AC-1: an https backend that closes the connection during the TLS handshake gives 502
    // CONNECT_FAILED on one TCP connection with .retries(2)
    @Test(timeout = 30000)
    public void sec_PROXY_005_AC1_closeDuringTheHandshakeIsConnectFailedOnOneConnection() throws IOException {
        PlainBackend backend = plainBackend(true);
        mount(Proxy.to("https://localhost:" + backend.port()).retries(2)
                .sslSocketFactory(trustingClient.getSocketFactory()));

        assertConnectFailed(get());
        assertEquals("TCP connections", 1, backend.connectionsBeforeSentinel());
    }

    // ---- AC-2 ----

    // SEC-PROXY-005 AC-2: with .sslSocketFactory trusting the private CA the request reaches the first backend of
    // AC-1
    @Test(timeout = 30000)
    public void sec_PROXY_005_AC2_aFactoryTrustingThePrivateCaReachesTheBackend() throws IOException {
        TlsBackend backend = tlsBackend(goodServer);
        mount(Proxy.to("https://localhost:" + backend.port()).sslSocketFactory(trustingClient.getSocketFactory()));

        ClientResponse r = get();

        assertEquals(200, r.status());
        assertEquals("ok", r.text());
        assertTrue(backend.requestHead(), backend.requestHead().startsWith("GET /api/x HTTP/1.1\r\n"));
        assertTrue(errors.isEmpty());
    }

    // SEC-PROXY-005 AC-2: with the same factory the backend whose certificate names another host still gives 502
    // CONNECT_FAILED: the host name check stays on
    @Test(timeout = 30000)
    public void sec_PROXY_005_AC2_theHostNameCheckStaysOnWithTheFactory() throws IOException {
        TlsBackend backend = tlsBackend(otherHostServer);
        mount(Proxy.to("https://localhost:" + backend.port()).sslSocketFactory(trustingClient.getSocketFactory()));

        assertConnectFailed(get());
        assertEquals("request bytes the backend read", 0, backend.requestBytes());
    }

    // SEC-PROXY-005 AC-2: an IP-literal target is verified against the certificate's IP address entry
    @Test(timeout = 30000)
    public void sec_PROXY_005_AC2_anIpLiteralTargetIsVerifiedAgainstTheCertificate() throws IOException {
        TlsBackend backend = tlsBackend(goodServer);
        mount(Proxy.to("https://127.0.0.1:" + backend.port()).sslSocketFactory(trustingClient.getSocketFactory()));

        assertEquals(200, get().status());
    }

    // ---- AC-3 ----

    // SEC-PROXY-005 AC-3: the factory sees createSocket(plain, "localhost", port, true) for https://localhost:<port>
    @Test(timeout = 30000)
    public void sec_PROXY_005_AC3_theTargetHostIsHandedToCreateSocket() throws IOException {
        TlsBackend backend = tlsBackend(goodServer);
        RecordingFactory factory = new RecordingFactory(trustingClient.getSocketFactory());
        mount(Proxy.to("https://localhost:" + backend.port()).sslSocketFactory(factory));

        assertEquals(200, get().status());
        assertEquals(Collections.singletonList("layered connected=true host=localhost port=" + backend.port()
                + " autoClose=true"), factory.calls());
    }

    // SEC-PROXY-005 AC-3: for https://backend.test:<port>, resolved to loopback through the test resolver seam, the
    // SNI server name of the ClientHello is backend.test
    @Test(timeout = 30000)
    public void sec_PROXY_005_AC3_theSniServerNameIsTheDottedTargetHost() throws IOException {
        TlsBackend backend = tlsBackend(goodServer);
        final List<String> resolved = Collections.synchronizedList(new ArrayList<String>());
        BackendClient.resolver = new BackendClient.Resolver() {
            @Override
            public InetAddress resolve(String host) throws IOException {
                resolved.add(host);
                return InetAddress.getByName("127.0.0.1");
            }
        };
        RecordingFactory factory = new RecordingFactory(trustingClient.getSocketFactory());
        mount(Proxy.to("https://backend.test:" + backend.port()).sslSocketFactory(factory));

        ClientResponse r = get();

        assertEquals(200, r.status());
        assertEquals(Collections.singletonList("backend.test"), resolved);
        assertEquals(Collections.singletonList("backend.test"), backend.sniNames());
        assertEquals(Collections.singletonList("layered connected=true host=backend.test port=" + backend.port()
                + " autoClose=true"), factory.calls());
    }

    // ---- AC-4 ----

    // SEC-PROXY-005 AC-4: the recording factory gets createSocket(Socket, String, int, boolean) with an already
    // connected plain socket, the host, the port and true, and none of the self-connecting createSocket methods
    @Test(timeout = 30000)
    public void sec_PROXY_005_AC4_onlyTheLayeringCreateSocketIsCalled() throws IOException {
        TlsBackend backend = tlsBackend(goodServer);
        RecordingFactory factory = new RecordingFactory(trustingClient.getSocketFactory());
        mount(Proxy.to("https://localhost:" + backend.port()).sslSocketFactory(factory));

        assertEquals(200, get().status());
        assertEquals(200, get().status());

        String layered = "layered connected=true host=localhost port=" + backend.port() + " autoClose=true";
        assertEquals(Arrays.asList(layered, layered), factory.calls());
    }

    // ---- AC-5 ----

    // SEC-PROXY-005 AC-5: a backend that accepts TCP and never answers the handshake gives 504 TOTAL_TIMEOUT after
    // about the 1 second total timeout, well before the 10 second connect timeout
    @Test(timeout = 30000)
    public void sec_PROXY_005_AC5_theHandshakeRunsUnderTheTotalTimeoutWatchdog() throws IOException {
        PlainBackend backend = plainBackend(false);
        mount(Proxy.to("https://localhost:" + backend.port()).sslSocketFactory(trustingClient.getSocketFactory())
                .connectTimeout(10, TimeUnit.SECONDS).totalTimeout(1, TimeUnit.SECONDS));

        long start = System.nanoTime();
        ClientResponse r = get();
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertEquals(504, r.status());
        assertSame(ProxyException.Kind.TOTAL_TIMEOUT, onlyProxyError().kind());
        assertTrue("answered after " + millis + " ms", millis >= 900 && millis < 5000);
    }

    // SEC-PROXY-005: a handshake that outlasts the connect timeout (its SO_TIMEOUT) is CONNECT_TIMEOUT, 504
    @Test(timeout = 30000)
    public void sec_PROXY_005_aHandshakeTimeoutIsConnectTimeout() throws IOException {
        PlainBackend backend = plainBackend(false);
        mount(Proxy.to("https://localhost:" + backend.port()).sslSocketFactory(trustingClient.getSocketFactory())
                .connectTimeout(300, TimeUnit.MILLISECONDS));

        ClientResponse r = get();

        assertEquals(504, r.status());
        assertSame(ProxyException.Kind.CONNECT_TIMEOUT, onlyProxyError().kind());
    }

    // ---- AC-6 ----

    // SEC-PROXY-005 AC-6: without an injected verifier the proxy holds its built-in certificate host name matcher,
    // never HttpsURLConnection's default verifier, which rejects every host on a JVM
    @Test
    public void sec_PROXY_005_AC6_theHeldVerifierIsTheBuiltInMatcher() {
        HostnameVerifier held = TlsConnector.verifier();
        assertEquals("CertificateHostnames", held.getClass().getSimpleName());
        assertSame(TlsConnector.class.getPackage(), held.getClass().getPackage());
        assertFalse(held == HttpsURLConnection.getDefaultHostnameVerifier());
        assertTrue("this JVM has SSLParameters.setEndpointIdentificationAlgorithm",
                TlsConnector.endpointIdentificationAvailable());
    }

    // SEC-PROXY-005 AC-6: with the reflective path off and no injected verifier, the built-in matcher checks the
    // host: the certificate for localhost reaches the backend, the certificate for other.example gives 502
    // CONNECT_FAILED with no request bytes sent
    @Test(timeout = 30000)
    public void sec_PROXY_005_AC6_theBuiltInMatcherChecksTheHostWithTheReflectionOff() throws IOException {
        TlsConnector.endpointIdentification = false;
        TlsBackend good = tlsBackend(goodServer);
        TlsBackend other = tlsBackend(otherHostServer);
        app.use("/good", Proxy.to("https://localhost:" + good.port())
                .sslSocketFactory(trustingClient.getSocketFactory()));
        mount(Proxy.to("https://localhost:" + other.port()).sslSocketFactory(trustingClient.getSocketFactory()));

        ClientResponse ok = adapter.send(ClientRequest.of("GET", "/good/x"));
        assertEquals("status (errors: " + errors + ")", 200, ok.status());
        assertEquals("ok", ok.text());

        assertConnectFailed(get());
        assertEquals("request bytes the backend read", 0, other.requestBytes());
    }

    // SEC-PROXY-005 AC-6: with the reflective path off, an injected verifier answering false is called once with the
    // target host and the handshake session, and gives 502 CONNECT_FAILED with no request bytes sent
    @Test(timeout = 30000)
    public void sec_PROXY_005_AC6_aRejectingVerifierIsConnectFailedWithNoRequestBytes() throws IOException {
        TlsBackend backend = tlsBackend(goodServer);
        RecordingVerifier verifier = new RecordingVerifier(false);
        TlsConnector.endpointIdentification = false;
        TlsConnector.hostnameVerifier = verifier;
        mount(Proxy.to("https://localhost:" + backend.port()).sslSocketFactory(trustingClient.getSocketFactory()));

        assertConnectFailed(get());
        assertEquals(Collections.singletonList("localhost"), verifier.hosts());
        assertEquals("request bytes the backend read", 0, backend.requestBytes());
    }

    // SEC-PROXY-005 AC-6: with the reflective path off, an injected verifier answering true lets the request reach
    // the backend after exactly one call with the target host
    @Test(timeout = 30000)
    public void sec_PROXY_005_AC6_anAcceptingVerifierLetsTheRequestThrough() throws IOException {
        TlsBackend backend = tlsBackend(otherHostServer);
        RecordingVerifier verifier = new RecordingVerifier(true);
        TlsConnector.endpointIdentification = false;
        TlsConnector.hostnameVerifier = verifier;
        mount(Proxy.to("https://localhost:" + backend.port()).sslSocketFactory(trustingClient.getSocketFactory()));

        assertEquals(200, get().status());
        assertEquals(Collections.singletonList("localhost"), verifier.hosts());
        assertTrue(backend.requestHead(), backend.requestHead().startsWith("GET /api/x HTTP/1.1\r\n"));
    }

    // ---- AC-9 ----

    // SEC-PROXY-005 AC-9: with the reflective identification on and reading back (this JVM), the held verifier still
    // runs after the handshake, once with the target host: the proxy's own check runs on every runtime
    @Test(timeout = 30000)
    public void sec_PROXY_005_AC9_theHeldVerifierRunsAfterAnIdentifiedHandshake() throws IOException {
        TlsBackend backend = tlsBackend(goodServer);
        RecordingVerifier verifier = new RecordingVerifier(true);
        TlsConnector.hostnameVerifier = verifier;
        mount(Proxy.to("https://localhost:" + backend.port()).sslSocketFactory(trustingClient.getSocketFactory()));

        assertEquals(200, get().status());
        assertEquals(Collections.singletonList("localhost"), verifier.hosts());
    }

    // SEC-PROXY-005 AC-9: a rejecting verifier after an identified handshake gives 502 CONNECT_FAILED, called once
    // with the target host, and no request bytes are sent
    @Test(timeout = 30000)
    public void sec_PROXY_005_AC9_aRejectingVerifierAfterIdentificationIsConnectFailed() throws IOException {
        TlsBackend backend = tlsBackend(goodServer);
        RecordingVerifier verifier = new RecordingVerifier(false);
        TlsConnector.hostnameVerifier = verifier;
        mount(Proxy.to("https://localhost:" + backend.port()).sslSocketFactory(trustingClient.getSocketFactory()));

        assertConnectFailed(get());
        assertEquals(Collections.singletonList("localhost"), verifier.hosts());
        assertEquals("request bytes the backend read", 0, backend.requestBytes());
    }

    // SEC-PROXY-005 AC-9 (loop P wave-14 r2 LP-1): a private-CA factory whose trust manager is an
    // X509ExtendedTrustManager that checks only the chain: the JDK does not wrap it, so the endpoint identification
    // reads back but checks nothing; the backend whose certificate names another host still gives 502
    // CONNECT_FAILED with no request bytes sent
    @Test(timeout = 30000)
    public void sec_PROXY_005_AC9_hostNameCheckStaysOnWithAChainOnlyExtendedTrustManager() throws Exception {
        TlsBackend backend = tlsBackend(otherHostServer);
        mount(Proxy.to("https://localhost:" + backend.port()).sslSocketFactory(chainOnlyClient().getSocketFactory()));

        ClientResponse r = get();

        assertEquals("status (request bytes the backend read: " + backend.requestBytes() + ")", 502, r.status());
        assertSame(ProxyException.Kind.CONNECT_FAILED, onlyProxyError().kind());
        assertEquals("request bytes the backend read", 0, backend.requestBytes());
    }

    // SEC-PROXY-005 AC-9: the same chain-only trust manager still reaches a backend whose certificate names the
    // target host
    @Test(timeout = 30000)
    public void sec_PROXY_005_AC9_aChainOnlyExtendedTrustManagerReachesTheGoodBackend() throws Exception {
        TlsBackend backend = tlsBackend(goodServer);
        mount(Proxy.to("https://localhost:" + backend.port()).sslSocketFactory(chainOnlyClient().getSocketFactory()));

        assertEquals("status (errors: " + errors + ")", 200, get().status());
    }

    // SEC-PROXY-005 AC-9 (loop P wave-14 r2 LP-2): with a delegating private-CA factory, whose socket drops the
    // endpoint identification, a backend whose certificate names the target host is reached
    @Test(timeout = 30000)
    public void sec_PROXY_005_AC9_aDelegatingPrivateCaFactoryReachesTheGoodBackend() throws IOException {
        TlsBackend backend = tlsBackend(goodServer);
        mount(Proxy.to("https://localhost:" + backend.port())
                .sslSocketFactory(new DelegatingFactory(trustingClient.getSocketFactory(), false)));

        ClientResponse r = get();

        assertEquals("status (errors: " + errors + ")", 200, r.status());
        assertEquals("ok", r.text());
        assertTrue(errors.isEmpty());
    }

    // SEC-PROXY-005 AC-9 (loop P wave-14 r2 LP-3): a private-CA factory whose createSocket completes the handshake
    // before returning, so the identification set afterwards checks nothing: the backend whose certificate names
    // another host still gives 502 CONNECT_FAILED with no request bytes sent
    @Test(timeout = 30000)
    public void sec_PROXY_005_AC9_hostNameCheckStaysOnWithAnEagerHandshakeFactory() throws IOException {
        TlsBackend backend = tlsBackend(otherHostServer);
        mount(Proxy.to("https://localhost:" + backend.port())
                .sslSocketFactory(new EagerHandshakeFactory(trustingClient.getSocketFactory())));

        ClientResponse r = get();

        assertEquals("status (request bytes the backend read: " + backend.requestBytes() + ")", 502, r.status());
        assertSame(ProxyException.Kind.CONNECT_FAILED, onlyProxyError().kind());
        assertEquals("request bytes the backend read", 0, backend.requestBytes());
    }

    // SEC-PROXY-005 AC-9 (loop P wave-14 r2 LP-4): a delegating SSLSocket that keeps the SSLParameters it was given,
    // so the identification reads back "HTTPS", without applying it to the real socket: the backend whose
    // certificate names another host still gives 502 CONNECT_FAILED with no request bytes sent
    @Test(timeout = 30000)
    public void sec_PROXY_005_AC9_hostNameCheckStaysOnWithAParameterCachingDelegate() throws IOException {
        TlsBackend backend = tlsBackend(otherHostServer);
        mount(Proxy.to("https://localhost:" + backend.port())
                .sslSocketFactory(new DelegatingFactory(trustingClient.getSocketFactory(), true)));

        ClientResponse r = get();

        assertEquals("status (request bytes the backend read: " + backend.requestBytes() + ")", 502, r.status());
        assertSame(ProxyException.Kind.CONNECT_FAILED, onlyProxyError().kind());
        assertEquals("request bytes the backend read", 0, backend.requestBytes());
    }

    /**
     * A context trusting the private CA through an {@link X509ExtendedTrustManager} that checks the chain with the
     * PKIX manager's two-argument {@code checkServerTrusted} and never the endpoint.
     */
    private static SSLContext chainOnlyClient() throws Exception {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(load("trust.p12"));
        final X509TrustManager pkix = (X509TrustManager) tmf.getTrustManagers()[0];
        X509ExtendedTrustManager chainOnly = new X509ExtendedTrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
                    throws CertificateException {
                pkix.checkClientTrusted(chain, authType);
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
                    throws CertificateException {
                pkix.checkServerTrusted(chain, authType);
            }

            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                    throws CertificateException {
                pkix.checkClientTrusted(chain, authType);
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                    throws CertificateException {
                pkix.checkServerTrusted(chain, authType);
            }

            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                pkix.checkClientTrusted(chain, authType);
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                pkix.checkServerTrusted(chain, authType);
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return pkix.getAcceptedIssuers();
            }
        };
        SSLContext c = SSLContext.getInstance("TLS");
        c.init(null, new TrustManager[] {chainOnly}, null);
        return c;
    }

    // ---- AC-7 ----

    // SEC-PROXY-005 AC-7: no main class file of the module holds a method reference to
    // SSLParameters.setEndpointIdentificationAlgorithm, and no main source carries an animal-sniffer exemption
    @Test
    public void sec_PROXY_005_AC7_noDirectReferenceToSetEndpointIdentificationAlgorithm()
            throws IOException, URISyntaxException {
        File classes = new File(Proxy.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        assertTrue(classes + " is the main classes directory", classes.isDirectory());
        List<File> files = new ArrayList<>();
        collect(classes, ".class", files);
        assertTrue(files.toString(), files.size() > 5);
        for (File f : files) {
            for (String ref : methodRefs(f)) {
                assertFalse(f + " references " + ref,
                        ref.equals("javax/net/ssl/SSLParameters.setEndpointIdentificationAlgorithm"));
            }
        }
        List<File> sources = new ArrayList<>();
        collect(new File("src/main/java"), ".java", sources);
        assertTrue("main sources found from " + new File(".").getAbsolutePath(), sources.size() > 5);
        for (File f : sources) {
            String text = new String(readAll(new FileInputStream(f)), StandardCharsets.UTF_8);
            assertFalse(f.getPath(), text.contains("IgnoreJRERequirement"));
            assertFalse(f.getPath(), text.contains("animal_sniffer"));
        }
    }

    private static void collect(File dir, String suffix, List<File> into) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File c : children) {
            if (c.isDirectory()) {
                collect(c, suffix, into);
            } else if (c.getName().endsWith(suffix)) {
                into.add(c);
            }
        }
    }

    /** The {@code owner.name} of every CONSTANT_Methodref and CONSTANT_InterfaceMethodref of a class file. */
    private static List<String> methodRefs(File classFile) throws IOException {
        try (DataInputStream in = new DataInputStream(new FileInputStream(classFile))) {
            in.readInt();
            in.readUnsignedShort();
            in.readUnsignedShort();
            int count = in.readUnsignedShort();
            Map<Integer, String> utf8 = new HashMap<>();
            Map<Integer, Integer> classes = new HashMap<>();
            Map<Integer, int[]> pairs = new HashMap<>();
            List<int[]> methods = new ArrayList<>();
            for (int i = 1; i < count; i++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1:
                        utf8.put(i, in.readUTF());
                        break;
                    case 7:
                        classes.put(i, in.readUnsignedShort());
                        break;
                    case 10:
                    case 11:
                        methods.add(new int[] {in.readUnsignedShort(), in.readUnsignedShort()});
                        break;
                    case 12:
                        pairs.put(i, new int[] {in.readUnsignedShort(), in.readUnsignedShort()});
                        break;
                    case 8:
                    case 16:
                    case 19:
                    case 20:
                        in.skipBytes(2);
                        break;
                    case 15:
                        in.skipBytes(3);
                        break;
                    case 3:
                    case 4:
                    case 9:
                    case 17:
                    case 18:
                        in.skipBytes(4);
                        break;
                    case 5:
                    case 6:
                        in.skipBytes(8);
                        i++;
                        break;
                    default:
                        throw new AssertionError("constant pool tag " + tag + " in " + classFile);
                }
            }
            List<String> refs = new ArrayList<>();
            for (int[] m : methods) {
                refs.add(utf8.get(classes.get(m[0])) + "." + utf8.get(pairs.get(m[1])[0]));
            }
            return refs;
        }
    }

    // ---- AC-8 ----

    // SEC-PROXY-005 AC-8: no public method of Proxy turns verification off; its only TLS options are
    // sslSocketFactory(SSLSocketFactory) and the two trustCertificates overloads of FR-PROXY-022 (IR-PROXY-001 AC-3)
    @Test
    public void sec_PROXY_005_AC8_theOnlyTlsOptionsAreTheSocketFactoryAndTrustCertificates() {
        List<String> tls = new ArrayList<>();
        for (Method m : Proxy.class.getMethods()) {
            if (!Modifier.isPublic(m.getModifiers()) || m.getDeclaringClass() != Proxy.class) {
                continue;
            }
            String name = m.getName().toLowerCase(java.util.Locale.ROOT);
            boolean sslTyped = false;
            for (Class<?> p : m.getParameterTypes()) {
                Class<?> base = p.isArray() ? p.getComponentType() : p;
                sslTyped |= base.getName().startsWith("javax.net.") || base.getName().startsWith("java.security.");
            }
            if (sslTyped || name.contains("ssl") || name.contains("tls") || name.contains("verif")
                    || name.contains("trust") || name.contains("secure") || name.contains("certificate")
                    || name.contains("hostname")) {
                tls.add(m.getName() + Arrays.toString(m.getParameterTypes()));
            }
        }
        Collections.sort(tls);
        assertEquals(Arrays.asList("sslSocketFactory[class javax.net.ssl.SSLSocketFactory]",
                "trustCertificates[class [Ljava.security.cert.X509Certificate;]",
                "trustCertificates[class java.lang.String]"), tls);
    }

    // SEC-PROXY-005 AC-8: host name verification stays on with an .sslSocketFactory whose SSLSocket is a delegate
    // that keeps SSLSocket's base getSSLParameters/setSSLParameters, which drop the endpoint identification
    // algorithm: the backend whose certificate names another host still gives 502 CONNECT_FAILED with no request
    // bytes sent
    @Test(timeout = 30000)
    public void sec_PROXY_005_AC8_hostNameCheckStaysOnWithADelegatingSocket() throws IOException {
        TlsBackend backend = tlsBackend(otherHostServer);
        mount(Proxy.to("https://localhost:" + backend.port())
                .sslSocketFactory(new DelegatingFactory(trustingClient.getSocketFactory(), false)));

        ClientResponse r = get();

        assertEquals("status (request bytes the backend read: " + backend.requestBytes() + ")", 502, r.status());
        assertSame(ProxyException.Kind.CONNECT_FAILED, onlyProxyError().kind());
        assertEquals("request bytes the backend read", 0, backend.requestBytes());
    }

    // ---- backends ----

    /** Counts accepted connections; a sentinel connection makes the count deterministic (FIFO accept queue). */
    abstract static class CountingBackend implements Closeable {
        final ServerSocket server;
        private final List<Integer> acceptedPorts = new ArrayList<>();
        private final List<Socket> open = Collections.synchronizedList(new ArrayList<Socket>());

        CountingBackend(ServerSocket server) {
            this.server = server;
            Thread acceptor = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (!CountingBackend.this.server.isClosed()) {
                        try {
                            final Socket s = CountingBackend.this.server.accept();
                            open.add(s);
                            synchronized (acceptedPorts) {
                                acceptedPorts.add(s.getPort());
                            }
                            Thread handler = new Thread(new Runnable() {
                                @Override
                                public void run() {
                                    try {
                                        handle(s);
                                    } catch (IOException e) {
                                        // the client went away
                                    }
                                }
                            });
                            handler.setDaemon(true);
                            handler.start();
                        } catch (IOException e) {
                            // the listener was closed
                        }
                    }
                }
            }, "tls-backend-" + server.getLocalPort());
            acceptor.setDaemon(true);
            acceptor.start();
        }

        abstract void handle(Socket s) throws IOException;

        int port() {
            return server.getLocalPort();
        }

        int connectionsBeforeSentinel() throws IOException {
            try (Socket sentinel = new Socket(InetAddress.getByName("127.0.0.1"), port())) {
                Integer local = sentinel.getLocalPort();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (true) {
                    synchronized (acceptedPorts) {
                        int at = acceptedPorts.indexOf(local);
                        if (at >= 0) {
                            return at;
                        }
                    }
                    if (System.nanoTime() > deadline) {
                        throw new AssertionError("the sentinel connection was not accepted within 5 seconds");
                    }
                    Thread.yield();
                }
            }
        }

        @Override
        public void close() throws IOException {
            server.close();
            synchronized (open) {
                for (Socket s : open) {
                    s.close();
                }
            }
        }
    }

    /** A plain TCP listener that closes each connection at once, or holds it open and never writes. */
    private static final class PlainBackend extends CountingBackend {
        private final boolean closeAtOnce;

        PlainBackend(boolean closeAtOnce) throws IOException {
            super(new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")));
            this.closeAtOnce = closeAtOnce;
        }

        @Override
        void handle(Socket s) throws IOException {
            if (closeAtOnce) {
                s.close();
            }
        }
    }

    /**
     * A TLS listener with the given server context: records the SNI host names of each ClientHello and the request
     * head it reads after the handshake, then answers 200 "ok" and closes.
     */
    static final class TlsBackend extends CountingBackend {
        private final List<String> sniNames = Collections.synchronizedList(new ArrayList<String>());
        private final ByteArrayOutputStream request = new ByteArrayOutputStream();

        TlsBackend(SSLContext context) throws IOException {
            super(context.getServerSocketFactory().createServerSocket(0, 50, InetAddress.getByName("127.0.0.1")));
        }

        @Override
        void handle(Socket s) throws IOException {
            SSLSocket ssl = (SSLSocket) s;
            SSLParameters params = ssl.getSSLParameters();
            final List<String> names = sniNames;
            params.setSNIMatchers(Collections.<SNIMatcher>singletonList(new SNIMatcher(0) {
                @Override
                public boolean matches(SNIServerName serverName) {
                    names.add(new SNIHostName(serverName.getEncoded()).getAsciiName());
                    return true;
                }
            }));
            ssl.setSSLParameters(params);
            try {
                ssl.startHandshake();
            } catch (IOException e) {
                s.close();
                return;
            }
            InputStream in = ssl.getInputStream();
            int matched = 0;
            byte[] end = {'\r', '\n', '\r', '\n'};
            for (int b; matched < 4 && (b = in.read()) != -1; ) {
                synchronized (request) {
                    request.write(b);
                }
                matched = b == end[matched] ? matched + 1 : (b == '\r' ? 1 : 0);
            }
            if (matched == 4) {
                OutputStream out = ssl.getOutputStream();
                out.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok"
                        .getBytes(StandardCharsets.ISO_8859_1));
                out.flush();
            }
            s.close();
        }

        List<String> sniNames() {
            return new ArrayList<>(sniNames);
        }

        int requestBytes() {
            synchronized (request) {
                return request.size();
            }
        }

        String requestHead() {
            synchronized (request) {
                return new String(request.toByteArray(), StandardCharsets.ISO_8859_1);
            }
        }
    }

    // ---- recording seams ----

    /** Delegates to a real factory and records each createSocket call. */
    private static final class RecordingFactory extends SSLSocketFactory {
        private final SSLSocketFactory delegate;
        private final List<String> calls = Collections.synchronizedList(new ArrayList<String>());

        RecordingFactory(SSLSocketFactory delegate) {
            this.delegate = delegate;
        }

        List<String> calls() {
            return new ArrayList<>(calls);
        }

        @Override
        public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
            calls.add("layered connected=" + s.isConnected() + " host=" + host + " port=" + port + " autoClose="
                    + autoClose);
            return delegate.createSocket(s, host, port, autoClose);
        }

        @Override
        public Socket createSocket(Socket s, InputStream consumed, boolean autoClose) throws IOException {
            calls.add("createSocket(Socket, InputStream, boolean)");
            return delegate.createSocket(s, consumed, autoClose);
        }

        @Override
        public Socket createSocket() throws IOException {
            calls.add("createSocket()");
            return delegate.createSocket();
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            calls.add("createSocket(String, int)");
            return delegate.createSocket(host, port);
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
            calls.add("createSocket(String, int, InetAddress, int)");
            return delegate.createSocket(host, port, localHost, localPort);
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            calls.add("createSocket(InetAddress, int)");
            return delegate.createSocket(host, port);
        }

        @Override
        public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort)
                throws IOException {
            calls.add("createSocket(InetAddress, int, InetAddress, int)");
            return delegate.createSocket(address, port, localAddress, localPort);
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return delegate.getDefaultCipherSuites();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return delegate.getSupportedCipherSuites();
        }
    }

    /**
     * Wraps each layered socket of a real factory in a {@link DelegatingSocket}, which keeps {@link SSLSocket}'s
     * base {@code getSSLParameters}/{@code setSSLParameters}, or, with {@code cacheParameters}, in a
     * {@link ParameterCachingSocket}.
     */
    private static final class DelegatingFactory extends SSLSocketFactory {
        private final SSLSocketFactory delegate;
        private final boolean cacheParameters;

        DelegatingFactory(SSLSocketFactory delegate, boolean cacheParameters) {
            this.delegate = delegate;
            this.cacheParameters = cacheParameters;
        }

        @Override
        public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
            SSLSocket real = (SSLSocket) delegate.createSocket(s, host, port, autoClose);
            return cacheParameters ? new ParameterCachingSocket(real) : new DelegatingSocket(real);
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            throw new IOException("not a layering createSocket");
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
            throw new IOException("not a layering createSocket");
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            throw new IOException("not a layering createSocket");
        }

        @Override
        public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort)
                throws IOException {
            throw new IOException("not a layering createSocket");
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return delegate.getDefaultCipherSuites();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return delegate.getSupportedCipherSuites();
        }
    }

    /**
     * Delegates the abstract {@link SSLSocket} methods and the stream, timeout and close methods to a real socket,
     * and does not override {@code getSSLParameters}/{@code setSSLParameters}: the base versions carry only the
     * cipher suites, protocols and client-auth flags, so an endpoint identification algorithm set through them is
     * dropped silently.
     */
    private static class DelegatingSocket extends SSLSocket {
        private final SSLSocket d;

        DelegatingSocket(SSLSocket d) {
            this.d = d;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return d.getInputStream();
        }

        @Override
        public OutputStream getOutputStream() throws IOException {
            return d.getOutputStream();
        }

        @Override
        public synchronized void close() throws IOException {
            d.close();
        }

        @Override
        public boolean isConnected() {
            return d.isConnected();
        }

        @Override
        public boolean isClosed() {
            return d.isClosed();
        }

        @Override
        public synchronized void setSoTimeout(int timeout) throws java.net.SocketException {
            d.setSoTimeout(timeout);
        }

        @Override
        public synchronized int getSoTimeout() throws java.net.SocketException {
            return d.getSoTimeout();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return d.getSupportedCipherSuites();
        }

        @Override
        public String[] getEnabledCipherSuites() {
            return d.getEnabledCipherSuites();
        }

        @Override
        public void setEnabledCipherSuites(String[] suites) {
            d.setEnabledCipherSuites(suites);
        }

        @Override
        public String[] getSupportedProtocols() {
            return d.getSupportedProtocols();
        }

        @Override
        public String[] getEnabledProtocols() {
            return d.getEnabledProtocols();
        }

        @Override
        public void setEnabledProtocols(String[] protocols) {
            d.setEnabledProtocols(protocols);
        }

        @Override
        public SSLSession getSession() {
            return d.getSession();
        }

        @Override
        public void addHandshakeCompletedListener(javax.net.ssl.HandshakeCompletedListener listener) {
            d.addHandshakeCompletedListener(listener);
        }

        @Override
        public void removeHandshakeCompletedListener(javax.net.ssl.HandshakeCompletedListener listener) {
            d.removeHandshakeCompletedListener(listener);
        }

        @Override
        public void startHandshake() throws IOException {
            d.startHandshake();
        }

        @Override
        public void setUseClientMode(boolean mode) {
            d.setUseClientMode(mode);
        }

        @Override
        public boolean getUseClientMode() {
            return d.getUseClientMode();
        }

        @Override
        public void setNeedClientAuth(boolean need) {
            d.setNeedClientAuth(need);
        }

        @Override
        public boolean getNeedClientAuth() {
            return d.getNeedClientAuth();
        }

        @Override
        public void setWantClientAuth(boolean want) {
            d.setWantClientAuth(want);
        }

        @Override
        public boolean getWantClientAuth() {
            return d.getWantClientAuth();
        }

        @Override
        public void setEnableSessionCreation(boolean flag) {
            d.setEnableSessionCreation(flag);
        }

        @Override
        public boolean getEnableSessionCreation() {
            return d.getEnableSessionCreation();
        }
    }

    /**
     * A {@link DelegatingSocket} that keeps the {@link SSLParameters} it was given and answers them back, so an
     * endpoint identification algorithm reads back while the base {@code setSSLParameters} applies only the cipher
     * suites, protocols and client-auth flags to the real socket.
     */
    private static final class ParameterCachingSocket extends DelegatingSocket {
        private SSLParameters kept;

        ParameterCachingSocket(SSLSocket d) {
            super(d);
        }

        @Override
        public void setSSLParameters(SSLParameters params) {
            super.setSSLParameters(params);
            kept = params;
        }

        @Override
        public SSLParameters getSSLParameters() {
            return kept != null ? kept : super.getSSLParameters();
        }
    }

    /** Returns the real factory's layered socket after completing its handshake inside {@code createSocket}. */
    private static final class EagerHandshakeFactory extends SSLSocketFactory {
        private final SSLSocketFactory delegate;

        EagerHandshakeFactory(SSLSocketFactory delegate) {
            this.delegate = delegate;
        }

        @Override
        public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
            SSLSocket ssl = (SSLSocket) delegate.createSocket(s, host, port, autoClose);
            ssl.startHandshake();
            return ssl;
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            throw new IOException("not a layering createSocket");
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
            throw new IOException("not a layering createSocket");
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            throw new IOException("not a layering createSocket");
        }

        @Override
        public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort)
                throws IOException {
            throw new IOException("not a layering createSocket");
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return delegate.getDefaultCipherSuites();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return delegate.getSupportedCipherSuites();
        }
    }

    /** Answers a fixed value and records the host of each call, with a non-null handshake session. */
    private static final class RecordingVerifier implements HostnameVerifier {
        private final boolean answer;
        private final List<String> hosts = Collections.synchronizedList(new ArrayList<String>());

        RecordingVerifier(boolean answer) {
            this.answer = answer;
        }

        @Override
        public boolean verify(String host, SSLSession session) {
            hosts.add(session == null ? "no session: " + host : host);
            return answer;
        }

        List<String> hosts() {
            return new ArrayList<>(hosts);
        }
    }
}
