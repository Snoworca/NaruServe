package com.snoworca.naruserve.servlet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.UploadFile;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.TestSupport;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.function.Consumer;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.webapp.WebAppContext;
import org.junit.Rule;
import org.junit.Test;

/**
 * Multipart, urlencoded and JSON bodies on Jetty go through the core parser over getInputStream.
 * SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-8-7.sds.md.
 */
public class ServletMultipartTest {

    @Rule
    public final ContainerRule container = new ContainerRule();

    private static final String B = "svl";
    private static final String MP = "multipart/form-data; boundary=" + B;
    private static final String FORM = "application/x-www-form-urlencoded";

    /** The Servlet API methods that read or parse the body or the parameters behind the core's back. */
    static final List<String> FORBIDDEN = Arrays.asList("getParts", "getPart", "getParameter", "getParameterMap",
            "getParameterNames", "getParameterValues");

    static String part(String name, String filename, String content) {
        return "--" + B + "\r\nContent-Disposition: form-data; name=\"" + name + "\""
                + (filename == null ? "" : "; filename=\"" + filename + "\"") + "\r\n\r\n" + content + "\r\n";
    }

    static String close() {
        return "--" + B + "--\r\n";
    }

    /** Every accessor the requirement names: req.parts, req.form, req.file and req.files. */
    private static void routes(RestApp app, File dir) {
        app.multipart(o -> o.tempDirectory(dir).memoryThreshold(64).maxFileSize(1024).maxParts(5));
        app.formLimit(256);
        app.jsonLimit(256);
        app.post("/up", (req, res) -> {
            StringBuilder sb = new StringBuilder();
            for (UploadFile p : req.parts()) {
                sb.append(p.name()).append('=').append(p.size()).append(';');
            }
            sb.append("form t=").append(req.form("t")).append(';');
            UploadFile f = req.file("doc");
            sb.append("file doc=").append(f == null ? null : f.filename() + "/" + f.size()).append(';');
            for (UploadFile p : req.files("photos")) {
                sb.append("photos ").append(p.filename()).append('/').append(p.size()).append(';');
            }
            res.send(sb.toString());
        });
        app.post("/json", (req, res) -> res.send("json " + req.body(String.class)));
    }

    private int jetty(Consumer<RestApp> routes) throws Exception {
        SharedApp.onJetty(container, "/*", routes);
        return container.port();
    }

    private static String masked(SharedApp.Reply r) {
        return r.status + " " + TestSupport.maskRequestId(r.text());
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static String repeat(char c, int n) {
        char[] a = new char[n];
        Arrays.fill(a, c);
        return new String(a);
    }

    private String multipartBody() {
        return part("t", null, "Hello") + part("photos", "a.png", repeat('a', 100)) + part("doc", "d.txt", "D")
                + part("photos", "b.png", "B") + close();
    }

    // SDS-AC-1; FR-SVL-004 AC-1
    @Test
    public void sdsAc1_FR_SVL_004_AC1_multipartAndFormBodiesGiveTheSameAccessorsOnJettyAndInMemory() throws Exception {
        File dir = container.uploads(); // checked again by the rule after the test (FR-SVL-006 AC-5)
        int port = jetty(app -> routes(app, dir));
        InMemoryHttpAdapter memory = new InMemoryHttpAdapter();
        SharedApp.listen(memory, app -> routes(app, dir));

        String[][] cases = {{MP, multipartBody()}, {FORM, "t=a%20b&x=1"}};
        for (String[] c : cases) {
            String jetty = masked(SharedApp.http(port, "POST", "/up", bytes(c[1]), "Content-Type", c[0]));
            String inMemory = masked(SharedApp.inMemory(memory, "POST", "/up", bytes(c[1]), "Content-Type", c[0]));
            assertEquals(inMemory, jetty);
        }
        String mp = masked(SharedApp.http(port, "POST", "/up", bytes(multipartBody()), "Content-Type", MP));
        assertEquals("200 t=5;photos=100;doc=1;photos=1;form t=Hello;file doc=d.txt/1;"
                + "photos a.png/100;photos b.png/1;", mp);
        String form = masked(SharedApp.http(port, "POST", "/up", bytes("t=a%20b&x=1"), "Content-Type", FORM));
        assertEquals("200 form t=a b;file doc=null;", form);
        assertEquals(Collections.emptyList(), Arrays.asList(dir.list()));
    }

    // SDS-AC-2; FR-SVL-004 AC-2
    @Test
    public void sdsAc2_FR_SVL_004_AC2_theAdapterReadsGetInputStreamOnlyAndHoldsNoMultipartConfig() throws Exception {
        File dir = container.uploads(); // checked again by the rule after the test (FR-SVL-006 AC-5)
        List<String> calls = Collections.synchronizedList(new ArrayList<String>());
        container.filter(RecordingRequest.filter(calls));
        int port = jetty(app -> routes(app, dir));
        assertEquals(200, SharedApp.http(port, "POST", "/up", bytes(multipartBody()), "Content-Type", MP).status);
        assertEquals(200, SharedApp.http(port, "POST", "/up", bytes("t=v"), "Content-Type", FORM).status);
        assertEquals(200, SharedApp.http(port, "POST", "/json", bytes("{}"), "Content-Type", "application/json")
                .status);
        List<String> seen = new ArrayList<>(calls);
        assertTrue(seen.toString(), seen.contains("getInputStream"));
        for (String name : FORBIDDEN) {
            assertFalse(name + " called: " + seen, seen.contains(name));
        }
        assertEquals("no upload file left", Collections.emptyList(), Arrays.asList(dir.list()));

        WebAppContext ctx = container.webApp(new HashMap<>(
                ServletConfigurerTest.configurer(ServletConfigurerTest.Good.class)));
        ServletHolder holder = ctx.getServletHandler().getServlet(NaruServeServlet.NAME);
        assertNull(((ServletHolder.Registration) holder.getRegistration()).getMultipartConfig());
    }

    // SDS-AC-3; FR-SVL-004 AC-3
    @Test
    public void sdsAc3_FR_SVL_004_AC3_bodiesAboveTheCoreLimitsAnswer413AndLeaveNoUploadFile() throws Exception {
        File dir = container.uploads(); // checked again by the rule after the test (FR-SVL-006 AC-5)
        int port = jetty(app -> routes(app, dir));
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            many.append(part("p" + i, null, "v"));
        }
        String[][] cases = {
            {MP, part("doc", "big.bin", repeat('x', 1025)) + close()},
            {MP, many + close()},
            {FORM, "t=" + repeat('f', 300)},
            {"application/json", "\"" + repeat('j', 300) + "\""},
        };
        for (String[] c : cases) {
            String r = masked(SharedApp.http(port, "POST", c[0].equals("application/json") ? "/json" : "/up",
                    bytes(c[1]), "Content-Type", c[0]));
            assertTrue(c[0] + ": " + r, r.startsWith(
                    "413 {\"error\":{\"code\":\"PAYLOAD_TOO_LARGE\",\"message\":\""));
            assertTrue(r, r.endsWith("\"requestId\":\"*\"}}"));
            assertEquals(c[0], Collections.emptyList(), Arrays.asList(dir.list()));
        }
    }
}
