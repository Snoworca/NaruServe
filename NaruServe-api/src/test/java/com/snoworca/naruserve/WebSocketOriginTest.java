package com.snoworca.naruserve;

import static com.snoworca.naruserve.WebSocketDispatchTest.accepted;
import static com.snoworca.naruserve.WebSocketDispatchTest.assertError;
import static org.junit.Assert.assertEquals;

import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.TestApp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/** Origin check of the upgrade. SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-7-3.sds.md. */
public class WebSocketOriginTest {

    private final InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
    private final TestApp t = TestApp.create(adapter);
    private final AtomicInteger handled = new AtomicInteger();

    private void start(String... allowed) {
        if (allowed.length > 0) {
            t.app().webSocket(o -> o.allowedOrigins(allowed));
        }
        t.app().ws("/ws", (req, ws) -> handled.incrementAndGet());
        t.start();
    }

    /** An upgrade with {@code headerPairs} that the core hands to webSocket: 101 and the handler ran once more. */
    private void admitted(String... headerPairs) throws Exception {
        int before = handled.get();
        assertEquals(101, accepted(adapter, ClientRequest.upgrade("/ws", headerPairs)).status());
        assertEquals(before + 1, handled.get());
    }

    /** An upgrade with {@code headerPairs} answered 403 with the standard body; the handler never ran. */
    private void refused(String... headerPairs) {
        int before = handled.get();
        assertError(403, "HTTP_403", "Forbidden", adapter.send(ClientRequest.upgrade("/ws", headerPairs)));
        assertEquals(before, handled.get());
    }

    // FR-WS-003 AC-1
    @Test(timeout = 20000)
    public void FR_WS_003_AC1_noOriginAndSameOriginAreAdmittedByDefault() throws Exception {
        start();
        admitted();
        admitted("Origin", "http://example.test:8080", "Host", "example.test:8080");
    }

    // SDS-AC-6; FR-WS-003 AC-2
    @Test(timeout = 20000)
    public void sdsAc6_FR_WS_003_AC2_crossOriginGets403WithTheRequestIdAndNoUpgrade() {
        start();
        refused("Origin", "http://evil.test", "Host", "example.test");
    }

    // FR-WS-003 AC-3
    @Test(timeout = 20000)
    public void FR_WS_003_AC3_schemeFromIsSecureDefaultPortAndCaseInsensitiveHost() throws Exception {
        start();
        admitted("Origin", "HTTP://Example.Test", "Host", "example.test:80");
        refused("Origin", "https://example.test", "Host", "example.test");
        refused("Origin", "http://example.test:8081", "Host", "example.test:8080");
    }

    // FR-WS-003 AC-4
    @Test(timeout = 20000)
    public void FR_WS_003_AC4_literalNullOriginIsRefusedWithoutAStar() {
        start();
        refused("Origin", "null", "Host", "example.test");
    }

    // FR-WS-003 AC-4
    @Test(timeout = 20000)
    public void FR_WS_003_AC4_literalNullOriginIsRefusedWithOnlyNamedOrigins() {
        start("https://app.example.com");
        refused("Origin", "null", "Host", "example.test");
    }

    // SDS-AC-9; FR-WS-003 AC-5
    @Test(timeout = 20000)
    public void sdsAc9_FR_WS_003_AC5_listedOriginIsAdmittedAfterNormalisation() throws Exception {
        start("https://app.example.com");
        admitted("Origin", "https://APP.example.com:443", "Host", "api.example.com");
        refused("Origin", "https://other.example.com", "Host", "api.example.com");
        admitted();
        admitted("Origin", "http://api.example.com", "Host", "api.example.com");
    }

    // SDS-AC-9; FR-WS-003 AC-6
    @Test(timeout = 20000)
    public void sdsAc9_FR_WS_003_AC6_starAdmitsEveryOrigin() throws Exception {
        start("https://app.example.com", "*");
        admitted("Origin", "http://evil.test", "Host", "example.test");
        admitted("Origin", "null", "Host", "example.test");
    }

    // SDS-AC-10; FR-WS-003 AC-7, FR-WS-001 AC-2
    @Test(timeout = 20000)
    public void sdsAc10_FR_WS_003_AC7_FR_WS_001_AC2_originCheckRunsOnlyAfterTheWholeChain() {
        List<String> calls = Collections.synchronizedList(new ArrayList<String>());
        t.app().use((req, res, next) -> {
            calls.add("recorded " + req.path());
            next.run();
        });
        t.app().use("/ws/private", (req, res, next) -> res.status(401).end());
        t.app().ws("/ws/private", (req, ws) -> handled.incrementAndGet());
        start();
        ClientResponse denied = adapter.send(ClientRequest.upgrade("/ws/private", "Origin", "http://evil.test",
                "Host", "example.test"));
        assertEquals(401, denied.status());
        assertEquals(0, handled.get());
        refused("Origin", "http://evil.test", "Host", "example.test");
        assertEquals(Arrays.asList("recorded /ws/private", "recorded /ws"), calls);
    }
}
