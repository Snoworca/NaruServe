package com.snoworca.naruserve.servlet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import org.junit.Rule;
import org.junit.Test;

/** The shared case suite: one app definition on Jetty and on the in-memory adapter gives equal transcripts. */
public class ServletCasesTest {

    @Rule
    public final ContainerRule container = new ContainerRule();

    /** One request: method, target, body (or null) and header pairs. */
    private static final class Case {
        final String method;
        final String target;
        final byte @Nullable [] body;
        final String[] headers;

        Case(String method, String target, @Nullable String body, String... headers) {
            this.method = method;
            this.target = target;
            this.body = body == null ? null : body.getBytes(StandardCharsets.UTF_8);
            this.headers = headers;
        }
    }

    private static final List<Case> CASES = Arrays.asList(
            new Case("GET", "/users/42?q=a%20b", null),
            new Case("GET", "/users/%41b", null),
            new Case("POST", "/json", "{\"a\":1}", "Content-Type", "application/json"),
            new Case("GET", "/cookie", null, "Cookie", "s=v1"),
            new Case("GET", "/cookie", null),
            new Case("GET", "/nope", null),
            new Case("DELETE", "/users/1", null),
            new Case("HEAD", "/users/1", null),
            new Case("OPTIONS", "/users/1", null),
            new Case("GET", "/trail", null),
            new Case("GET", "/boom", null),
            new Case("GET", "/teapot", null),
            new Case("GET", "/file", null),
            new Case("GET", "/download", null));

    /**
     * Status, the order-independent headers that matter and the body with the request id masked; header order is
     * the container's, so no line depends on it.
     */
    private static String transcript(Case c, SharedApp.Reply r) {
        StringBuilder b = new StringBuilder(c.method + " " + c.target + " -> " + r.status);
        for (String name : new String[] {"Allow", "Set-Cookie", "Content-Disposition"}) {
            b.append(" ").append(name).append("=").append(new TreeSet<>(r.headers(name)));
        }
        // Jetty re-formats the parameter part of Content-Type (no space after ';'); compare it without spaces.
        String type = r.header("Content-Type");
        b.append(" Content-Type=").append(type == null ? null : type.replace(" ", "").toLowerCase());
        if (!c.method.equals("HEAD")) {
            b.append(" length=").append(r.body.length);
        } else {
            b.append(" Content-Length=").append(r.header("Content-Length"));
        }
        b.append(" body=").append(r.text().replaceAll("\"requestId\":\"[^\"]*\"", "\"requestId\":\"*\""));
        return b.toString();
    }

    // SDS-AC-4; FR-SVL-002 AC-4
    @Test
    public void sdsAc4_FR_SVL_002_AC4_sharedCasesGiveEqualStatusesAndBodiesOnBothAdapters() throws Exception {
        File file = SharedApp.tempFile();
        SharedApp.onJetty(container, "/*", app -> SharedApp.define(app, file));
        InMemoryHttpAdapter memory = new InMemoryHttpAdapter();
        SharedApp.listen(memory, app -> SharedApp.define(app, file));

        List<String> jetty = new ArrayList<>();
        List<String> inMemory = new ArrayList<>();
        for (Case c : CASES) {
            jetty.add(transcript(c, SharedApp.http(container.port(), c.method, c.target, c.body, c.headers)));
            inMemory.add(transcript(c, SharedApp.inMemory(memory, c.method, c.target, c.body, c.headers)));
        }
        assertEquals(String.join("\n", inMemory), String.join("\n", jetty));

        String all = String.join("\n", jetty);
        assertTrue(all, all.contains("GET /users/42?q=a%20b -> 200"));
        assertTrue(all, all.contains("body=user 42 q=a b"));
        assertTrue(all, all.contains("GET /nope -> 404"));
        assertTrue(all, all.contains("DELETE /users/1 -> 405 Allow=["));
        assertTrue(all, all.contains("body=m1,m2,h"));
        assertTrue(all, all.contains("GET /boom -> 500"));
        assertTrue(all, all.contains("body=handled short and stout"));
        assertTrue(all, all.contains("GET /file -> 200") && all.contains("length=" + SharedApp.FILE_BYTES.length));
    }
}
