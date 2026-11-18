package com.snoworca.naruserve.proxy;

import static org.junit.Assert.assertEquals;

import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.TestApp;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap.SimpleImmutableEntry;
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
 * The fixed response header options of {@link Proxy} (FR-PROXY-012) through a real backend socket:
 * {@code removeResponseHeader} hides the backend lines of a name and keeps an app value, {@code responseHeader}
 * replaces the backend and app values after the copy and the {@code Location} rewrite, names compare
 * case-insensitively and the later call for a name wins.
 */
public class ProxyFixedResponseHeadersTest {

    /** Replaced in a backend header template with the backend authority {@code 127.0.0.1:<port>}. */
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
     * A backend answering every request with {@code status}, the given header lines ({@code name, value} pairs, in
     * order, {@link #ORIGIN} replaced by its own authority) and a two-byte body.
     */
    private RawBackend backend(int status, String... nameValuePairs) throws IOException {
        AtomicInteger port = new AtomicInteger();
        RawBackend b = RawBackend.start((index, request, out) -> {
            StringBuilder head = new StringBuilder("HTTP/1.1 ").append(status).append(" X\r\n");
            for (int i = 0; i < nameValuePairs.length; i += 2) {
                head.append(nameValuePairs[i]).append(": ")
                        .append(nameValuePairs[i + 1].replace(ORIGIN, "127.0.0.1:" + port.get())).append("\r\n");
            }
            head.append("Content-Length: 2\r\n\r\nok");
            out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        });
        port.set(b.port());
        backends.add(b);
        return b;
    }

    private RawBackend backend(String... nameValuePairs) throws IOException {
        return backend(200, nameValuePairs);
    }

    /** An app middleware before the proxy that sets {@code name: value} with {@code res.header}. */
    private void appSets(String name, String value) {
        app.use((req, res, next) -> {
            res.header(name, value);
            next.run();
        });
    }

    private ClientResponse get(String target) {
        started = true;
        return t.send("GET", target);
    }

    /** Every value of {@code name} (case-insensitive) the in-memory client received, in order. */
    private static List<String> values(ClientResponse r, String name) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String> h : r.headers()) {
            if (h.getKey().equalsIgnoreCase(name)) {
                out.add(h.getValue());
            }
        }
        return out;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // AC-1 removeResponseHeader

    // FR-PROXY-012 AC-1: with removeResponseHeader("Server") the backend's Server line does not reach the client
    @Test
    public void fr_PROXY_012_AC1_removedBackendServerLineDoesNotReachTheClient() throws IOException {
        RawBackend b = backend("Server", "backend/1.0", "X-Other", "kept");
        app.use("/p", Proxy.to(b.url()).removeResponseHeader("Server"));

        ClientResponse r = get("/p/x");

        assertEquals(200, r.status());
        assertEquals(Collections.emptyList(), values(r, "Server"));
        assertEquals(Collections.singletonList("kept"), values(r, "X-Other"));
        assertEquals("ok", r.text());
    }

    // FR-PROXY-012 AC-1: an app Server: gw set before the proxy stays as exactly one Server: gw
    @Test
    public void fr_PROXY_012_AC1_appServerValueSetBeforeTheProxyStays() throws IOException {
        RawBackend b = backend("Server", "backend/1.0", "Server", "backend/2.0");
        appSets("Server", "gw");
        app.use("/p", Proxy.to(b.url()).removeResponseHeader("Server"));

        ClientResponse r = get("/p/x");

        assertEquals(Collections.singletonList("gw"), values(r, "Server"));
    }

    // FR-PROXY-012 AC-1: a removed Location is not put back by the Location rewrite that follows the copy
    @Test
    public void fr_PROXY_012_AC1_removedLocationIsNotPutBackByTheRewrite() throws IOException {
        RawBackend b = backend(302, "Location", "http://" + ORIGIN + "/x");
        app.use("/p", Proxy.to(b.url()).removeResponseHeader("Location"));

        ClientResponse r = get("/p/start");

        assertEquals(302, r.status());
        assertEquals(Collections.emptyList(), values(r, "Location"));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // AC-2 responseHeader

    // FR-PROXY-012 AC-2: responseHeader replaces both the backend value and the app value of the name
    @Test
    public void fr_PROXY_012_AC2_fixedValueReplacesTheBackendAndAppValues() throws IOException {
        RawBackend b = backend("Strict-Transport-Security", "max-age=0");
        appSets("Strict-Transport-Security", "max-age=60");
        app.use("/p", Proxy.to(b.url()).responseHeader("Strict-Transport-Security", "max-age=31536000"));

        ClientResponse r = get("/p/x");

        assertEquals(Collections.singletonList("max-age=31536000"), values(r, "Strict-Transport-Security"));
    }

    // FR-PROXY-012 AC-2: the fixed value reaches the client when the backend does not send the name
    @Test
    public void fr_PROXY_012_AC2_fixedValueIsSetWhenTheBackendSendsNoLine() throws IOException {
        RawBackend b = backend("X-Other", "kept");
        app.use("/p", Proxy.to(b.url()).responseHeader("Strict-Transport-Security", "max-age=31536000"));

        ClientResponse r = get("/p/x");

        assertEquals(Collections.singletonList("max-age=31536000"), values(r, "Strict-Transport-Security"));
        assertEquals(Collections.singletonList("kept"), values(r, "X-Other"));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // AC-3, AC-4 the later call for a name wins

    // FR-PROXY-012 AC-3: responseHeader("X-A", "1").removeResponseHeader("X-A") lets neither the backend X-A nor 1 out
    @Test
    public void fr_PROXY_012_AC3_laterRemovalDropsTheEarlierFixedValueAndTheBackendLine() throws IOException {
        RawBackend b = backend("X-A", "9");
        app.use("/p", Proxy.to(b.url()).responseHeader("X-A", "1").removeResponseHeader("X-A"));

        ClientResponse r = get("/p/x");

        assertEquals(Collections.emptyList(), values(r, "X-A"));
    }

    // FR-PROXY-012 AC-4: removeResponseHeader("X-A").responseHeader("X-A", "2") gives exactly one X-A: 2
    @Test
    public void fr_PROXY_012_AC4_laterFixedValueWinsOverAnEarlierRemoval() throws IOException {
        RawBackend b = backend("X-A", "9");
        app.use("/p", Proxy.to(b.url()).removeResponseHeader("X-A").responseHeader("X-A", "2"));

        ClientResponse r = get("/p/x");

        assertEquals(Collections.singletonList("2"), values(r, "X-A"));
    }

    // FR-PROXY-012 AC-4: responseHeader("X-A", "1").responseHeader("X-A", "2") gives exactly one X-A: 2
    @Test
    public void fr_PROXY_012_AC4_laterFixedValueWinsOverAnEarlierFixedValue() throws IOException {
        RawBackend b = backend("X-A", "9");
        app.use("/p", Proxy.to(b.url()).responseHeader("X-A", "1").responseHeader("X-A", "2"));

        ClientResponse r = get("/p/x");

        assertEquals(Collections.singletonList("2"), values(r, "X-A"));
    }

    // FR-PROXY-012 AC-4: the accessor keeps every call in call order; only the forward resolves the later call
    @Test
    public void fr_PROXY_012_AC4_accessorKeepsTheCallsInOrder() {
        Proxy proxy = Proxy.to("http://127.0.0.1:1").responseHeader("X-A", "1").removeResponseHeader("x-a")
                .responseHeader("X-A", "2");

        assertEquals(Arrays.asList(new SimpleImmutableEntry<>("X-A", "1"), new SimpleImmutableEntry<>("x-a", null),
                new SimpleImmutableEntry<>("X-A", "2")), proxy.responseHeaderRules());
    }

    // ---------------------------------------------------------------------------------------------------------------
    // AC-5 after the copy and the Location rewrite

    // FR-PROXY-012 AC-5: responseHeader("Location", "/fixed") applies after the rewrite of a same-origin Location
    @Test
    public void fr_PROXY_012_AC5_fixedLocationAppliesAfterTheRewrite() throws IOException {
        RawBackend b = backend(302, "Location", "http://" + ORIGIN + "/x");
        app.use("/p", Proxy.to(b.url()).responseHeader("Location", "/fixed"));

        ClientResponse r = get("/p/start");

        assertEquals(302, r.status());
        assertEquals(Collections.singletonList("/fixed"), values(r, "Location"));
    }

    // FR-PROXY-012 AC-5: two backend X-A lines reach the client as exactly one X-A: 1
    @Test
    public void fr_PROXY_012_AC5_fixedValueReplacesTwoBackendLines() throws IOException {
        RawBackend b = backend("X-A", "8", "X-A", "9");
        app.use("/p", Proxy.to(b.url()).responseHeader("X-A", "1"));

        ClientResponse r = get("/p/x");

        assertEquals(Collections.singletonList("1"), values(r, "X-A"));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // AC-6 case-insensitive names

    // FR-PROXY-012 AC-6: removeResponseHeader("server") hides a backend Server line
    @Test
    public void fr_PROXY_012_AC6_lowerCaseRemovalHidesTheBackendServerLine() throws IOException {
        RawBackend b = backend("Server", "backend/1.0");
        app.use("/p", Proxy.to(b.url()).removeResponseHeader("server"));

        ClientResponse r = get("/p/x");

        assertEquals(Collections.emptyList(), values(r, "Server"));
    }

    // FR-PROXY-012 AC-6: responseHeader("x-a", "1") replaces a backend X-A line
    @Test
    public void fr_PROXY_012_AC6_lowerCaseFixedValueReplacesTheBackendLine() throws IOException {
        RawBackend b = backend("X-A", "9");
        app.use("/p", Proxy.to(b.url()).responseHeader("x-a", "1"));

        ClientResponse r = get("/p/x");

        assertEquals(Collections.singletonList("1"), values(r, "X-A"));
    }

    // FR-PROXY-012 AC-6: calls naming X-A in different cases resolve to the later call
    @Test
    public void fr_PROXY_012_AC6_laterCallInAnotherCaseWins() throws IOException {
        RawBackend b = backend("X-A", "9");
        app.use("/p", Proxy.to(b.url()).responseHeader("X-A", "1").removeResponseHeader("x-A"));

        ClientResponse r = get("/p/x");

        assertEquals(Collections.emptyList(), values(r, "X-A"));
    }
}
