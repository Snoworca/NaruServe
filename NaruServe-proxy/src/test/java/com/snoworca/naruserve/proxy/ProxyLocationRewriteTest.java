package com.snoworca.naruserve.proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.Router;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.TestApp;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Test;

/**
 * The backend {@code Location} rewrite of {@link Proxy} (FR-PROXY-013) through a real backend socket: a same-origin
 * absolute or network-path {@code Location} under the base path reaches the client as a path under the client-side
 * prefix, and every other value reaches it unchanged.
 */
public class ProxyLocationRewriteTest {

    /** Replaced in a Location template with the backend authority {@code 127.0.0.1:<port>}. */
    private static final String ORIGIN = "{origin}";

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

    /**
     * A backend answering every request with {@code 302} and one {@code Location} line per template, in order, each
     * with {@link #ORIGIN} replaced by its own {@code 127.0.0.1:<port>}.
     */
    private RawBackend redirecting(String... locationTemplates) throws IOException {
        AtomicInteger port = new AtomicInteger();
        RawBackend b = RawBackend.start((index, request, out) -> {
            StringBuilder head = new StringBuilder("HTTP/1.1 302 Found\r\n");
            for (String template : locationTemplates) {
                head.append("Location: ").append(template.replace(ORIGIN, "127.0.0.1:" + port.get())).append("\r\n");
            }
            head.append("Content-Length: 0\r\n\r\n");
            out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        });
        port.set(b.port());
        backends.add(b);
        return b;
    }

    private ClientResponse send(String target, String... headerPairs) {
        started = true;
        return t.send("GET", target, headerPairs);
    }

    /** Every {@code Location} value (case-insensitive name) the in-memory client received, in order. */
    private static List<String> locations(ClientResponse r) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String> h : r.headers()) {
            if (h.getKey().equalsIgnoreCase("Location")) {
                out.add(h.getValue());
            }
        }
        return out;
    }

    private static String origin(RawBackend b) {
        return "127.0.0.1:" + b.port();
    }

    // FR-PROXY-013 AC-1: a same-origin absolute Location reaches the client as the path, and the accessor reports
    // rewriteLocation true on Proxy.to(url)
    @Test
    public void fr_PROXY_013_AC1_sameOriginLocationReachesTheClientAsAPath() throws IOException {
        RawBackend backend = redirecting("http://" + ORIGIN + "/api/orders/x");
        Proxy proxy = Proxy.to(backend.url());
        assertTrue("Proxy.to(url) rewrites by default", proxy.rewriteLocation());
        app.use("/api/orders", proxy);

        ClientResponse r = send("/api/orders/start");

        assertEquals(302, r.status());
        assertEquals(Collections.singletonList("/api/orders/x"), locations(r));
    }

    // FR-PROXY-013 AC-1: with rewriteLocation(false) the client receives the backend value unchanged
    @Test
    public void fr_PROXY_013_AC1_rewriteLocationFalseKeepsTheBackendValue() throws IOException {
        RawBackend backend = redirecting("http://" + ORIGIN + "/api/orders/x");
        Proxy proxy = Proxy.to(backend.url()).rewriteLocation(false);
        assertFalse(proxy.rewriteLocation());
        app.use("/api/orders", proxy);

        ClientResponse r = send("/api/orders/start");

        assertEquals(Collections.singletonList("http://" + origin(backend) + "/api/orders/x"), locations(r));
    }

    // FR-PROXY-013 AC-2: relative references, another host and another port reach the client unchanged
    @Test
    public void fr_PROXY_013_AC2_relativeAndForeignLocationsAreUnchanged() throws IOException {
        RawBackend backend = redirecting("/x", "x/y", "http://other.example/x", "http://127.0.0.1:1/x");
        app.use("/api/orders", Proxy.to(backend.url()));

        ClientResponse r = send("/api/orders/start");

        assertEquals(Arrays.asList("/x", "x/y", "http://other.example/x", "http://127.0.0.1:1/x"), locations(r));
    }

    // FR-PROXY-013 AC-3: under stripPrefix(true) the mount prefix replaces the origin
    @Test
    public void fr_PROXY_013_AC3_stripPrefixPutsTheMountPrefixInFront() throws IOException {
        RawBackend backend = redirecting("http://" + ORIGIN + "/x");
        app.use("/api/orders", Proxy.to(backend.url()).stripPrefix(true));

        ClientResponse r = send("/api/orders/start");

        assertEquals("GET /start HTTP/1.1", backend.onlyRequest().requestLine());
        assertEquals(Collections.singletonList("/api/orders/x"), locations(r));
    }

    // FR-PROXY-013 AC-4: the base path is removed only when the path equals it or continues it with "/"
    @Test
    public void fr_PROXY_013_AC4_basePathIsReplacedOnlyOnASegmentBoundary() throws IOException {
        RawBackend backend = redirecting("http://" + ORIGIN + "/base/api/orders/y", "http://" + ORIGIN + "/basement/y",
                "http://" + ORIGIN + "/other/y");
        app.use("/api/orders", Proxy.to(backend.url() + "/base"));

        ClientResponse r = send("/api/orders/start");

        assertEquals("GET /base/api/orders/start HTTP/1.1", backend.onlyRequest().requestLine());
        assertEquals(Arrays.asList("/api/orders/y", "http://" + origin(backend) + "/basement/y",
                "http://" + origin(backend) + "/other/y"), locations(r));
    }

    // FR-PROXY-013 AC-5: the scheme and host compare case-insensitively, and another scheme is not the same origin
    @Test
    public void fr_PROXY_013_AC5_schemeAndHostCaseIsIgnoredButTheSchemeMustMatch() throws IOException {
        RawBackend backend = redirecting("HTTP://" + ORIGIN + "/x", "Http://" + ORIGIN + "/y",
                "https://" + ORIGIN + "/z");
        app.use("/p", Proxy.to(backend.url()));

        ClientResponse r = send("/p/start");

        assertEquals(Arrays.asList("/x", "/y", "https://" + origin(backend) + "/z"), locations(r));
    }

    // FR-PROXY-013 AC-6: a rewrite whose resulting path is empty yields "/"
    @Test
    public void fr_PROXY_013_AC6_emptyResultingPathIsSlash() throws IOException {
        RawBackend plain = redirecting("http://" + ORIGIN);
        RawBackend based = redirecting("http://" + ORIGIN + "/base");
        app.use("/plain", Proxy.to(plain.url()));
        app.use("/based", Proxy.to(based.url() + "/base"));

        assertEquals(Collections.singletonList("/"), locations(send("/plain/start")));
        assertEquals(Collections.singletonList("/"), locations(send("/based/start")));
    }

    // FR-PROXY-013 AC-7: under stripPrefix(true) the prefix is req.baseUrl(), the whole matched prefix of nested
    // router mounts, not the string given to use
    @Test
    public void fr_PROXY_013_AC7_nestedRouterMountsGiveTheWholeMatchedPrefix() throws IOException {
        RawBackend backend = redirecting("http://" + ORIGIN + "/x");
        Router router = Router.create();
        router.use("/orders", Proxy.to(backend.url()).stripPrefix(true));
        app.use("/api", router);

        ClientResponse r = send("/api/orders/start");

        assertEquals("GET /start HTTP/1.1", backend.onlyRequest().requestLine());
        assertEquals(Collections.singletonList("/api/orders/x"), locations(r));
    }

    // FR-PROXY-013 AC-8: a rewritten Location holds neither the received Host nor X-Forwarded-Host nor any origin
    @Test
    public void fr_PROXY_013_AC8_rewrittenLocationCarriesNoClientChosenHost() throws IOException {
        RawBackend backend = redirecting("http://" + ORIGIN + "/api/orders/x");
        app.use("/api/orders", Proxy.to(backend.url()));

        ClientResponse r = send("/api/orders/start", "Host", "gw.example", "X-Forwarded-Host", "evil.example");

        List<String> got = locations(r);
        assertEquals(Collections.singletonList("/api/orders/x"), got);
        String value = got.get(0);
        assertFalse(value, value.contains("gw.example"));
        assertFalse(value, value.contains("evil.example"));
        assertFalse(value, value.contains("127.0.0.1"));
        assertFalse(value, value.contains("://"));
        assertFalse(value, value.startsWith("//"));
    }

    // FR-PROXY-013 AC-8: the same holds under stripPrefix(true), where the prefix comes from the request
    @Test
    public void fr_PROXY_013_AC8_stripPrefixResultCarriesNoClientChosenHost() throws IOException {
        RawBackend backend = redirecting("http://" + ORIGIN + "/x");
        app.use("/api/orders", Proxy.to(backend.url()).stripPrefix(true));

        ClientResponse r = send("/api/orders/start", "Host", "gw.example", "X-Forwarded-Host", "evil.example");

        assertEquals(Collections.singletonList("/api/orders/x"), locations(r));
    }

    // FR-PROXY-013 AC-8: a same-origin Location whose path starts with "//" is not turned into a reference to
    // another origin: it reaches the client as "/." followed by the path, a path on the client's origin
    @Test
    public void fr_PROXY_013_AC8_doubleSlashPathDoesNotBecomeAnotherOrigin() throws IOException {
        RawBackend backend = redirecting("http://" + ORIGIN + "//evil.example/x");
        app.use("/api/orders", Proxy.to(backend.url()));

        ClientResponse r = send("/api/orders/start");

        assertEquals(Collections.singletonList("/.//evil.example/x"), locations(r));
    }

    // FR-PROXY-013 AC-9: a network-path Location of the target authority becomes a path; another authority stays
    @Test
    public void fr_PROXY_013_AC9_networkPathLocationIsResolvedAgainstTheTargetScheme() throws IOException {
        RawBackend backend = redirecting("//" + ORIGIN + "/api/orders/x", "//other.example/x");
        app.use("/api/orders", Proxy.to(backend.url()));

        ClientResponse r = send("/api/orders/start");

        assertEquals(Arrays.asList("/api/orders/x", "//other.example/x"), locations(r));
    }

    // FR-PROXY-013 AC-10: the part after the base path, query and fragment included, is kept unchanged
    @Test
    public void fr_PROXY_013_AC10_queryAndFragmentAreKept() throws IOException {
        RawBackend backend = redirecting("http://" + ORIGIN + "/api/orders/x?a=1#f");
        app.use("/api/orders", Proxy.to(backend.url()));

        ClientResponse r = send("/api/orders/start");

        assertEquals(Collections.singletonList("/api/orders/x?a=1#f"), locations(r));
    }
}
