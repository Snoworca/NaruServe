package com.snoworca.naruserve.servlet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.junit.Rule;
import org.junit.Test;

/** The D-052 path-rejection targets as raw request lines on Jetty; each observed status is the one servlet.md records. */
public class ServletRawTargetTest {

    @Rule
    public final ContainerRule container = new ContainerRule();

    /** Doc label (the first cell of the servlet.md row) to the raw request target sent. */
    static final Map<String, String> CASES = new LinkedHashMap<>();

    static {
        CASES.put("GET /a%zz", "/a%zz");
        CASES.put("GET /a%2", "/a%2");
        CASES.put("GET /a%C3%28", "/a%C3%28");
        CASES.put("GET /a%2Fb", "/a%2Fb");
        CASES.put("GET /a%00b", "/a%00b");
        CASES.put("GET /a<NUL>b", "/a\u0000b");
        CASES.put("GET /./a", "/./a");
        CASES.put("GET /../a", "/../a");
        CASES.put("GET /a/../b", "/a/../b");
        CASES.put("GET /%2e/a", "/%2e/a");
        CASES.put("GET /%2e%2e/a", "/%2e%2e/a");
        CASES.put("GET /a/%2e%2e/b", "/a/%2e%2e/b");
    }

    static final String OPTIONS_STAR = "OPTIONS *";

    private static @Nullable File doc() {
        for (File d = new File("").getAbsoluteFile(); d != null; d = d.getParentFile()) {
            File f = new File(d, "docs/api/servlet.md");
            if (f.isFile()) {
                return f;
            }
        }
        return null;
    }

    /** The status servlet.md records for {@code label}: the row whose first cell is the label in backticks. */
    private static int documented(String doc, String label, CharSequence observed) {
        Matcher m = Pattern.compile("(?m)^\\| `" + Pattern.quote(label) + "` \\|.*?\\| (\\d{3}) \\|").matcher(doc);
        assertTrue("servlet.md has no row for " + label + "; observed:\n" + observed, m.find());
        return Integer.parseInt(m.group(1));
    }

    private SharedApp.Reply reply(String method, String target) throws IOException {
        byte[] line = (method + " " + target + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.ISO_8859_1);
        try {
            return SharedApp.raw(container.port(), line);
        } catch (IOException e) {
            // shortcut: one retry for a connection Jetty closed before its 400 was read; drop it if it never fires.
            return SharedApp.raw(container.port(), line);
        }
    }

    /** The row's last cell names who answered: 컨테이너 for the container page, else NaruServe 표준 본문. */
    private static void assertResponder(String doc, String label, SharedApp.Reply r) {
        Matcher m = Pattern.compile("(?m)^\\| `" + Pattern.quote(label) + "` \\|.*\\| ([^|]+) \\|$").matcher(doc);
        assertTrue(label, m.find());
        String expected = responder(r).equals("container page") ? "컨테이너" : "NaruServe 표준 본문";
        assertTrue(label + ": " + m.group(1), m.group(1).startsWith(expected));
    }

    /** Who answered: NaruServe's standard JSON body (core or adapter), or the container's own page. */
    private static String responder(SharedApp.Reply r) {
        return r.text().startsWith("{\"error\"") ? "standard body" : "container page";
    }

    // SDS-AC-5; FR-SVL-002 AC-5
    @Test
    public void sdsAc5_FR_SVL_002_AC5_rejectedTargetsAre400Or404AndMatchServletMd() throws Exception {
        SharedApp.onJetty(container, "/*", app -> {
            for (String p : new String[] {"/a", "/b", "/a/b", "/a/:x", "/:x/a"}) {
                app.get(p, (req, res) -> res.send("reached " + req.rawPath()));
            }
        });
        File f = doc();
        assertNotNull("docs/api/servlet.md", f);
        String doc = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        StringBuilder observed = new StringBuilder();
        for (Map.Entry<String, String> c : CASES.entrySet()) {
            SharedApp.Reply r = reply("GET", c.getValue());
            int status = r.status;
            observed.append(c.getKey()).append(" -> ").append(status).append(' ').append(responder(r)).append('\n');
            assertTrue(c.getKey() + " -> " + status, status == 400 || status == 404);
        }
        SharedApp.Reply o = reply("OPTIONS", "*");
        int options = o.status;
        observed.append(OPTIONS_STAR).append(" -> ").append(options).append(' ').append(responder(o)).append('\n');
        for (Map.Entry<String, String> c : CASES.entrySet()) {
            SharedApp.Reply r = reply("GET", c.getValue());
            assertEquals(observed.toString(), documented(doc, c.getKey(), observed), r.status);
            assertResponder(doc, c.getKey(), r);
        }
        assertEquals(observed.toString(), documented(doc, OPTIONS_STAR, observed), options);
        assertResponder(doc, OPTIONS_STAR, o);
    }
}
