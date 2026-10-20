package com.snoworca.naruserve.servlet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.HttpException;
import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.spi.UpgradeHandler;
import com.snoworca.naruserve.spi.WebSocketEndpoint;
import com.snoworca.naruserve.spi.WebSocketSettings;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import java.io.OutputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.servlet.http.HttpServletRequest;
import org.junit.Rule;
import org.junit.Test;

/**
 * WebSocket is not supported on the Servlet adapter: an upgrade answers 501, the core's own answers stay.
 * SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-8-7.sds.md.
 */
public class ServletWebSocketTest {

    @Rule
    public final ContainerRule container = new ContainerRule();

    private final AtomicInteger sessions = new AtomicInteger();

    private static final String[] UPGRADE = {"Upgrade", "websocket", "Connection", "Upgrade",
        "Sec-WebSocket-Version", "13", "Sec-WebSocket-Key", "dGhlIHNhbXBsZSBub25jZQ=="};

    private void routes(RestApp app) {
        app.webSocket(o -> o.allowedOrigins("http://good.example"));
        app.ws("/chat", (req, ws) -> sessions.incrementAndGet());
    }

    private static String[] with(String[] a, String... more) {
        String[] out = new String[a.length + more.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(more, 0, out, a.length, more.length);
        return out;
    }

    // SDS-AC-11; FR-SVL-007 AC-1
    @Test
    public void sdsAc11_FR_SVL_007_AC1_aValidUpgradeToAnAppWsRouteAnswers501WithTheStandardBody() throws Exception {
        SharedApp.onJetty(container, "/*", this::routes);
        SharedApp.Reply r = SharedApp.http(container.port(), "GET", "/chat", null, UPGRADE);
        assertEquals(r.text(), 501, r.status);
        assertTrue(r.text(), r.text().matches("\\{\"error\":\\{\"code\":\"[A-Z_]+\",\"message\":\"[^\"]*\","
                + "\"requestId\":\"[0-9a-f]{16}\"}}"));
        assertEquals(0, sessions.get());
        // with an admitted Origin as well
        SharedApp.Reply admitted = SharedApp.http(container.port(), "GET", "/chat", null,
                with(UPGRADE, "Origin", "http://good.example"));
        assertEquals(501, admitted.status);
        assertNotEquals(101, admitted.status);
        assertEquals(0, sessions.get());
    }

    // SDS-AC-12; FR-SVL-007 AC-2
    @Test
    public void sdsAc12_FR_SVL_007_AC2_noUpgradeGives426ARejectedOriginGives403AndPostKeepsTheCoreAnswer()
            throws Exception {
        SharedApp.onJetty(container, "/*", this::routes);
        int port = container.port();
        SharedApp.Reply plain = SharedApp.http(port, "GET", "/chat", null);
        assertEquals(426, plain.status);
        assertEquals("websocket", plain.header("Upgrade"));

        SharedApp.Reply origin = SharedApp.http(port, "GET", "/chat", null,
                with(UPGRADE, "Origin", "http://evil.example"));
        assertEquals(origin.text(), 403, origin.status);

        InMemoryHttpAdapter memory = new InMemoryHttpAdapter();
        SharedApp.listen(memory, this::routes);
        SharedApp.Reply core = SharedApp.inMemory(memory, "POST", "/chat", new byte[0], UPGRADE);
        SharedApp.Reply post = SharedApp.http(port, "POST", "/chat", new byte[0], UPGRADE);
        assertEquals(core.status, post.status);
        assertNotEquals(501, post.status);
        assertEquals(0, sessions.get());
    }

    private static ServletExchange exchange(ServletAbortTest.FakeResponse res) {
        HttpServletRequest req = (HttpServletRequest) Proxy.newProxyInstance(
                ServletWebSocketTest.class.getClassLoader(), new Class<?>[] {HttpServletRequest.class},
                (p, m, args) -> null);
        return new ServletExchange(req, res.proxy, "/chat");
    }

    // SDS-AC-13; FR-SVL-007 AC-3
    @Test
    public void sdsAc13_FR_SVL_007_AC3_webSocketAndUpgradeThrow501AndLeaveTheExchangeUnstarted() throws Exception {
        ServletAbortTest.FakeResponse res = new ServletAbortTest.FakeResponse();
        ServletExchange ex = exchange(res);
        WebSocketEndpoint endpoint = (WebSocketEndpoint) Proxy.newProxyInstance(
                ServletWebSocketTest.class.getClassLoader(), new Class<?>[] {WebSocketEndpoint.class},
                (p, m, args) -> null);
        try {
            ex.webSocket(new WebSocketSettings(1024, 0), endpoint);
            fail("webSocket returned");
        } catch (HttpException e) {
            assertEquals(501, e.status());
        }
        UpgradeHandler handler = (in, out) -> fail("upgraded");
        List<Map.Entry<String, String>> none = Collections.emptyList();
        try {
            ex.upgrade(none, handler);
            fail("upgrade returned");
        } catch (HttpException e) {
            assertEquals(501, e.status());
        }
        assertFalse(res.calls.toString(), res.calls.toString().contains("setStatus"));
        OutputStream out = ex.commit(200, Collections.<Map.Entry<String, String>>singletonList(
                new SimpleImmutableEntry<>("Content-Length", "2")), 2);
        out.write("ok".getBytes(StandardCharsets.UTF_8));
        assertTrue(res.calls.toString(), res.calls.toString().contains("setStatus"));
        assertEquals("ok", new String(res.body.toByteArray(), StandardCharsets.UTF_8));
    }
}
