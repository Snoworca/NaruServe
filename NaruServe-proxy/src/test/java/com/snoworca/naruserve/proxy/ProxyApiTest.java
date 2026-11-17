package com.snoworca.naruserve.proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.Installable;
import com.snoworca.naruserve.Middleware;
import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.TestApp;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLSocketFactory;
import org.jspecify.annotations.NullMarked;
import org.junit.After;
import org.junit.Test;

/** The public shape of {@link Proxy} and its immutable option methods (IR-PROXY-001). */
public class ProxyApiTest {

    private final InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
    private final TestApp t = TestApp.create(adapter);
    private final RestApp app = t.app();
    private final List<RawBackend> backends = new ArrayList<>();
    private boolean started;

    @After
    public void tearDown() throws IOException {
        if (started) {
            app.shutdown(2, TimeUnit.SECONDS);
        }
        for (RawBackend b : backends) {
            b.close();
        }
    }

    private RawBackend backend(String rawResponse) throws IOException {
        RawBackend b = RawBackend.answering(rawResponse);
        backends.add(b);
        return b;
    }

    private ClientResponse send(String method, String target) {
        started = true;
        return t.send(method, target);
    }

    /** Each option method of AC-3 called once with an accepted value, by its signature. */
    private static Map<String, Proxy> eachOption(Proxy p) {
        Map<String, Proxy> out = new LinkedHashMap<>();
        out.put("connectTimeout", p.connectTimeout(3, TimeUnit.SECONDS));
        out.put("writeTimeout", p.writeTimeout(3, TimeUnit.SECONDS));
        out.put("readTimeout", p.readTimeout(3, TimeUnit.SECONDS));
        out.put("totalTimeout", p.totalTimeout(3, TimeUnit.SECONDS));
        out.put("retries", p.retries(2));
        out.put("retryDelay", p.retryDelay(5, TimeUnit.MILLISECONDS));
        out.put("retryUnsafeMethods", p.retryUnsafeMethods(true));
        out.put("stripPrefix", p.stripPrefix(true));
        out.put("preserveHost", p.preserveHost(true));
        out.put("bodyLimit", p.bodyLimit(1024));
        out.put("maxConcurrent", p.maxConcurrent(4));
        out.put("requestHeader", p.requestHeader("X-A", "a"));
        out.put("removeRequestHeader", p.removeRequestHeader("X-B"));
        out.put("responseHeader", p.responseHeader("X-C", "c"));
        out.put("removeResponseHeader", p.removeResponseHeader("X-D"));
        out.put("rewriteLocation", p.rewriteLocation(false));
        out.put("sslSocketFactory", p.sslSocketFactory((SSLSocketFactory) SSLSocketFactory.getDefault()));
        return out;
    }

    // IR-PROXY-001 AC-1: a public final Middleware, not Installable, with no public constructor
    @Test
    public void ir_PROXY_001_AC1_proxyIsAPublicFinalMiddlewareWithoutPublicConstructor() {
        int mod = Proxy.class.getModifiers();
        assertTrue("public", Modifier.isPublic(mod));
        assertTrue("final", Modifier.isFinal(mod));
        assertEquals("com.snoworca.naruserve.proxy.Proxy", Proxy.class.getName());
        assertTrue("implements Middleware", Middleware.class.isAssignableFrom(Proxy.class));
        assertFalse("not Installable", Installable.class.isAssignableFrom(Proxy.class));
        assertEquals("no public constructor", 0, Proxy.class.getConstructors().length);
    }

    // IR-PROXY-001 AC-1: app.use("/api/orders", Proxy.to(url)) registers and forwards GET /api/orders/x with no option
    @Test
    public void ir_PROXY_001_AC1_mountWithoutOptionsForwardsGetToTheBackend() throws IOException {
        RawBackend backend = backend("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: 5\r\n\r\nhello");
        app.use("/api/orders", Proxy.to(backend.url()));

        ClientResponse r = send("GET", "/api/orders/x");

        assertEquals("GET /api/orders/x HTTP/1.1", backend.onlyRequest().requestLine());
        assertEquals(200, r.status());
        assertEquals("hello", r.text());
    }

    // IR-PROXY-001 AC-2: package com.snoworca.naruserve.proxy holds a package-info.java annotated @NullMarked
    @Test
    public void ir_PROXY_001_AC2_packageInfoIsNullMarked() throws IOException {
        Package pkg = Proxy.class.getPackage();
        assertEquals("com.snoworca.naruserve.proxy", pkg.getName());
        assertTrue("the compiled package carries @NullMarked", pkg.isAnnotationPresent(NullMarked.class));
        File source = new File(System.getProperty("user.dir"),
                "src/main/java/com/snoworca/naruserve/proxy/package-info.java");
        assertTrue(source + " exists", source.isFile());
        String text;
        try (Scanner s = new Scanner(source, StandardCharsets.UTF_8.name()).useDelimiter("\\A")) {
            text = s.hasNext() ? s.next() : "";
        }
        assertTrue("package-info.java is annotated @NullMarked",
                text.matches("(?s).*@(org\\.jspecify\\.annotations\\.)?NullMarked\\s+package\\s+"
                        + "com\\.snoworca\\.naruserve\\.proxy;.*"));
    }

    // IR-PROXY-001 AC-3: besides Object and Middleware methods, exactly the pinned public methods, options returning Proxy
    @Test
    public void ir_PROXY_001_AC3_publicMethodsAreExactlyThePinnedSet() {
        TreeSet<String> expected = new TreeSet<>(Arrays.asList(
                "static to(java.lang.String)",
                "connectTimeout(long,java.util.concurrent.TimeUnit)",
                "writeTimeout(long,java.util.concurrent.TimeUnit)",
                "readTimeout(long,java.util.concurrent.TimeUnit)",
                "totalTimeout(long,java.util.concurrent.TimeUnit)",
                "retries(int)",
                "retryDelay(long,java.util.concurrent.TimeUnit)",
                "retryUnsafeMethods(boolean)",
                "stripPrefix(boolean)",
                "preserveHost(boolean)",
                "bodyLimit(long)",
                "maxConcurrent(int)",
                "requestHeader(java.lang.String,java.lang.String)",
                "removeRequestHeader(java.lang.String)",
                "responseHeader(java.lang.String,java.lang.String)",
                "removeResponseHeader(java.lang.String)",
                "rewriteLocation(boolean)",
                "sslSocketFactory(javax.net.ssl.SSLSocketFactory)"));
        TreeSet<String> actual = new TreeSet<>();
        for (Method m : Proxy.class.getMethods()) {
            if (m.isSynthetic() || isObjectOrMiddlewareMethod(m)) {
                continue;
            }
            assertSame(m + " returns Proxy", Proxy.class, m.getReturnType());
            actual.add(signature(m));
        }
        assertEquals(expected, actual);
        for (Method m : Proxy.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(m.getModifiers()) && !m.isSynthetic()) {
                assertFalse(m + " is not protected", Modifier.isProtected(m.getModifiers()));
            }
        }
    }

    private static boolean isObjectOrMiddlewareMethod(Method m) {
        for (Class<?> owner : new Class<?>[] {Object.class, Middleware.class}) {
            try {
                owner.getMethod(m.getName(), m.getParameterTypes());
                return true;
            } catch (NoSuchMethodException absent) {
                // not declared by this owner
            }
        }
        return false;
    }

    private static String signature(Method m) {
        StringBuilder s = new StringBuilder();
        if (Modifier.isStatic(m.getModifiers())) {
            s.append("static ");
        }
        s.append(m.getName()).append('(');
        Class<?>[] params = m.getParameterTypes();
        for (int i = 0; i < params.length; i++) {
            s.append(i == 0 ? "" : ",").append(params[i].getName());
        }
        return s.append(')').toString();
    }

    // IR-PROXY-001 AC-4: no public method takes or returns java.time.Duration or any other java.time type
    @Test
    public void ir_PROXY_001_AC4_noPublicMethodUsesJavaTime() {
        for (Method m : Proxy.class.getMethods()) {
            List<Class<?>> types = new ArrayList<>(Arrays.asList(m.getParameterTypes()));
            types.add(m.getReturnType());
            for (Class<?> c : types) {
                Class<?> base = c;
                while (base.isArray()) {
                    base = base.getComponentType();
                }
                assertFalse(m + " uses " + c.getName(), base.getName().startsWith("java.time."));
            }
            assertFalse(m + " generic signature uses java.time", m.toGenericString().contains("java.time."));
        }
    }

    // IR-PROXY-001 AC-5: retries(2) returns a new Proxy; the accessors report 0 for the receiver and 2 for the result
    @Test
    public void ir_PROXY_001_AC5_retriesReturnsANewProxyAndLeavesTheReceiverUnchanged() {
        Proxy p = Proxy.to("http://127.0.0.1:8081");
        Proxy q = p.retries(2);
        assertNotSame(p, q);
        assertEquals(0, p.retries());
        assertEquals(2, q.retries());
    }

    // IR-PROXY-001 AC-5: every option method returns a new Proxy distinct from its receiver, leaving the receiver as is
    @Test
    public void ir_PROXY_001_AC5_everyOptionMethodReturnsANewProxyDistinctFromItsReceiver() {
        Proxy p = Proxy.to("http://127.0.0.1:8081");
        String before = p.describe();
        Map<String, Proxy> results = eachOption(p);
        for (Map.Entry<String, Proxy> e : results.entrySet()) {
            assertNotSame(e.getKey() + " returns a new Proxy", p, e.getValue());
            assertFalse(e.getKey() + " changes the result's configuration", before.equals(e.getValue().describe()));
        }
        assertEquals("the receiver is unchanged by every option call", before, p.describe());
        int optionMethods = 0;
        for (Method m : Proxy.class.getDeclaredMethods()) {
            int mod = m.getModifiers();
            if (Modifier.isPublic(mod) && !Modifier.isStatic(mod) && m.getReturnType() == Proxy.class) {
                optionMethods++;
            }
        }
        assertEquals("every public option method is exercised once", optionMethods, results.size());
    }

    // IR-PROXY-001 AC-5: a GET through a mount of p (retries 0) reaches a backend that answers 503 exactly once
    @Test
    public void ir_PROXY_001_AC5_getThroughTheReceiverReachesA503BackendExactlyOnce() throws IOException {
        RawBackend backend = backend(RawBackend.response(503, "busy"));
        Proxy p = Proxy.to(backend.url());
        Proxy q = p.retries(2);
        assertEquals(2, q.retries());
        app.use("/api/orders", p);

        ClientResponse r = send("GET", "/api/orders/x");

        assertEquals(503, r.status());
        assertEquals("busy", r.text());
        assertEquals("the backend saw exactly one attempt", 1, backend.requestCount());
    }
    // IR-PROXY-001 AC-1: a bodyless backend answer to HEAD that names Transfer-Encoding is relayed, not refused
    @Test
    public void ir_PROXY_001_AC1_headAnswerWithTransferEncodingAndNoBodyIsRelayed() throws IOException {
        RawBackend backend = backend("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n");
        app.use("/api/orders", Proxy.to(backend.url()));

        ClientResponse r = send("HEAD", "/api/orders/x");

        assertEquals("HEAD /api/orders/x HTTP/1.1", backend.onlyRequest().requestLine());
        assertEquals(200, r.status());
        assertEquals(0, r.body().length);
    }

    // IR-PROXY-001 AC-1: a backend Content-Type the response cannot carry is an unreadable backend answer, so 502
    @Test
    public void ir_PROXY_001_AC1_backendContentTypeWithAControlCharacterGives502() throws IOException {
        RawBackend backend = backend("HTTP/1.1 200 OK\r\nContent-Type: text/\u0001plain\r\nContent-Length: 2\r\n\r\nok");
        app.use("/api/orders", Proxy.to(backend.url()));

        ClientResponse r = send("GET", "/api/orders/x");

        assertEquals(502, r.status());
    }

    // IR-PROXY-001 AC-1: a client body over the app's raw body limit is the client's failure (413), not the backend's
    @Test
    public void ir_PROXY_001_AC1_chunkedClientBodyOverTheRawLimitGives413WithoutReachingTheBackend() throws IOException {
        RawBackend backend = backend(RawBackend.response(200, "ok"));
        app.rawBodyLimit(8);
        app.use("/api/orders", Proxy.to(backend.url()));
        started = true;
        t.start();

        ClientResponse r = adapter.send(ClientRequest.of("POST", "/api/orders/x")
                .header("Transfer-Encoding", "chunked").body(new byte[64]));

        assertEquals(413, r.status());
        assertEquals("the backend saw no request", 0, backend.requestCount());
    }
    // IR-PROXY-001 AC-1: a declared client body over the raw limit gives 413 before the backend is contacted
    @Test
    public void ir_PROXY_001_AC1_declaredClientBodyOverTheRawLimitGives413BeforeAnyConnect() throws IOException {
        int closedPort;
        try (java.net.ServerSocket probe = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            closedPort = probe.getLocalPort();
        }
        app.rawBodyLimit(8);
        app.use("/api/orders", Proxy.to("http://127.0.0.1:" + closedPort));
        started = true;
        t.start();

        ClientResponse r = adapter.send(ClientRequest.of("POST", "/api/orders/x")
                .header("Content-Length", "64").body(new byte[64]));

        assertEquals(413, r.status());
    }

    // IR-PROXY-001 AC-1: a client body that ends before its declared length is the client's failure, not a 502
    @Test
    public void ir_PROXY_001_AC1_clientBodyEndingEarlyIsNotReportedAsABackendFailure() throws IOException {
        RawBackend backend = backend(RawBackend.response(200, "ok"));
        app.use("/api/orders", Proxy.to(backend.url()));
        started = true;
        t.start();

        ClientResponse r = adapter.send(ClientRequest.of("POST", "/api/orders/x")
                .header("Content-Length", "64").body(new byte[10]));

        assertEquals("the early end reaches the error flow as the client's EOFException", 500, r.status());
    }
}
