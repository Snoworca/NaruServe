package com.snoworca.naruserve.servlet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.spi.ExchangeHandler;
import com.snoworca.naruserve.spi.ServerSettings;
import com.snoworca.naruserve.testing.RecordingLogger;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;

/** The frozen settings the adapter keeps, and the embedded-engine options it ignores. */
public class ServletSettingsTest {

    @Rule
    public final ContainerRule container = new ContainerRule();

    private @Nullable RestApp app;

    @After
    public void shutdown() {
        RestApp a = app;
        if (a != null) {
            a.shutdown(5, TimeUnit.SECONDS);
        }
    }

    /** An app over a new adapter behind Jetty at {@code /*}, with {@code log} as its logger. */
    private ServletAdapter onJetty(RecordingLogger log, Consumer<RestApp> routes) throws Exception {
        ServletAdapter adapter = new ServletAdapter();
        RestApp a = RestApp.builder().httpAdapter(adapter).jsonCodec(SharedApp.CODEC).logger(log).build();
        app = a;
        routes.accept(a);
        a.listen(0);
        container.servlet(new NaruServeServlet(adapter, ""), "/*");
        return adapter;
    }

    private static void assertStandard413(SharedApp.Reply r) {
        assertEquals(r.text(), 413, r.status);
        assertTrue(r.text(), r.text().contains("\"code\":\"PAYLOAD_TOO_LARGE\""));
    }

    // SDS-AC-9; FR-SVL-008 AC-1
    @Test
    public void sdsAc9_FR_SVL_008_AC1_adapterDeclaresItsOwnSettingsBind() throws Exception {
        Method m = ServletAdapter.class.getDeclaredMethod("bind", ServerSettings.class, ExchangeHandler.class);
        assertSame(ServletAdapter.class, m.getDeclaringClass());
    }

    // SDS-AC-9; FR-SVL-008 AC-1
    @Test
    public void sdsAc9_FR_SVL_008_AC1_settingsKeepMaxSseStreams() throws Exception {
        ServletAdapter adapter = onJetty(new RecordingLogger(), a -> a.server(o -> o.maxSseStreams(3)));
        ServerSettings s = adapter.settings();
        assertNotNull(s);
        assertEquals(3, s.maxSseStreams());
    }

    // SDS-AC-9; FR-SVL-008 AC-1
    @Test
    public void sdsAc9_FR_SVL_008_AC1_settingsRendererWritesTheAdapters404() throws Exception {
        ServletAdapter adapter = new ServletAdapter();
        adapter.bind(ServerSettings.builder().logger(new RecordingLogger())
                .errorRenderer(status -> "{\"custom\":" + status + "}").build(), exchange -> {
                });
        container.servlet(new NaruServeServlet(adapter, "/api"), "/api/*");
        // Jetty maps the decoded /api/users, but the raw URI does not start with /api: the adapter answers 404.
        SharedApp.Reply r = SharedApp.http(container.port(), "GET", "/%61pi/users", null);
        assertEquals(404, r.status);
        assertEquals("{\"custom\":404}", r.text());
    }

    // SDS-AC-10; FR-SVL-008 AC-2
    @Test
    public void sdsAc10_FR_SVL_008_AC2_engineOptionsHaveNoEffect() throws Exception {
        onJetty(new RecordingLogger(), a -> {
            a.server(o -> o.allowedHosts("example.test").bindAddress("10.255.255.1").maxWorkers(1)
                    .maxHeaderSize(1024));
            a.get("/x", (req, res) -> res.send("served " + req.header("Host")));
        });
        char[] pad = new char[2000];
        Arrays.fill(pad, 'p');
        SharedApp.Reply r = SharedApp.http(container.port(), "GET", "/x", null, "X-Pad", new String(pad));
        assertEquals(r.text(), 200, r.status);
        assertEquals("served localhost", r.text());
    }

    // SDS-AC-11; FR-SVL-008 AC-3
    @Test
    public void sdsAc11_FR_SVL_008_AC3_coreBodyLimitsAnswer413() throws Exception {
        onJetty(new RecordingLogger(), a -> {
            a.jsonLimit(10).formLimit(10).multipart(o -> o.maxRequestSize(100));
            a.post("/json", (req, res) -> res.send("got " + req.body(String.class)));
            a.post("/form", (req, res) -> res.send("got " + req.form("a")));
        });
        int port = container.port();
        byte[] json = "\"0123456789012345678901234567890123456789\"".getBytes(StandardCharsets.UTF_8);
        assertStandard413(SharedApp.http(port, "POST", "/json", json, "Content-Type", "application/json"));
        byte[] form = "a=0123456789012345678901234567890123456789".getBytes(StandardCharsets.UTF_8);
        assertStandard413(SharedApp.http(port, "POST", "/form", form,
                "Content-Type", "application/x-www-form-urlencoded"));
        StringBuilder part = new StringBuilder("--b\r\nContent-Disposition: form-data; name=\"a\"\r\n\r\n");
        for (int i = 0; i < 300; i++) {
            part.append('v');
        }
        part.append("\r\n--b--\r\n");
        assertStandard413(SharedApp.http(port, "POST", "/form", part.toString().getBytes(StandardCharsets.UTF_8),
                "Content-Type", "multipart/form-data; boundary=b"));
    }

    // SDS-AC-13; FR-SVL-008 AC-5
    @Test
    public void sdsAc13_FR_SVL_008_AC5_defaultAppLogsNoLanWarning() throws Exception {
        RecordingLogger log = new RecordingLogger();
        onJetty(log, a -> {
        });
        assertEquals(log.lines.toString(), 0, log.count("WARN"));
    }
}
