package com.snoworca.naruserve.servlet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;

/** Separate common raw routing, pinned container rejection and retained strict routing profiles. */
public class ServletRawTargetTest {

    @Rule
    public final ContainerRule container = new ContainerRule();

    /** Historical strict-rejection cases, retained for an explicit transport/profile comparison. */
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

    // CON-APP-010 AC-1 AC-2 AC-5; FR-HTTP-027 AC-2 AC-3; FR-SVL-009 AC-6.
    @Test public void commonRawTargetsRetainExplicitContainerRestrictionsAndLegacyStrictProfile() throws Exception {
        java.util.concurrent.atomic.AtomicInteger dispatched=new java.util.concurrent.atomic.AtomicInteger();
        SharedApp.onJetty(container, "/*", app -> {
            app.use((req,res,next)->{dispatched.incrementAndGet();next.run();});
            for (String p : new String[] {"/a", "/b", "/a/b", "/a/:x", "/:x/a"}) {
                app.get(p, (req, res) -> res.send("reached " + req.rawPath()));
            }
            app.get("/capture/:x", (req,res)->res.send(req.param("x")));
        });
        // This matrix is the actual pinned Jetty transport plus common HTTP stack, not the retained strict profile.
        int[] statuses={400,400,404,404,400,400,200,400,404,200,400,404};
        int i=0;
        for(Map.Entry<String,String> c:CASES.entrySet()) {
            int before=dispatched.get();
            SharedApp.Reply r=reply("GET",c.getValue());
            assertEquals(c.getKey()+": "+r.text(),statuses[i++],r.status);
            if(r.status==200) assertEquals("reached "+c.getValue(),r.text());
            else if(r.status==404) SharedApp.assertHtmlError(r,404,"Cannot GET "+c.getValue()+"</pre>");
            else {
                assertTrue(c.getKey()+": "+r.text(),r.text().contains("400"));
                org.junit.Assert.assertNull("container rejection was mistaken for common finalhandler",r.header("Content-Security-Policy"));
            }
            assertEquals("container rejection must not dispatch: "+c.getKey(),r.status==400?before:before+1,dispatched.get());
        }
        SharedApp.Reply slash=reply("GET","/capture/a%2Fb");
        assertEquals(200,slash.status);assertEquals("a/b",slash.text());
        SharedApp.assertHtmlError(reply("GET","/capture/a%C3%28"),400,"com.snoworca.naruserve.internal.PathDecodeException: Failed to decode param &#39;a%C3%28&#39;<br>");
        SharedApp.Reply query=reply("GET","/a?x=%2F&v=1");
        assertEquals(200,query.status);assertEquals("reached /a",query.text());
        // Asterisk form has no application pathname and is rejected by the existing Servlet mapping boundary.
        SharedApp.Reply star=reply("OPTIONS","*");
        assertEquals(404,star.status);assertTrue(star.text(),star.isStandardError("NOT_FOUND"));
        assertEquals("application/json;charset=utf-8",star.header("Content-Type").replace(" ","").toLowerCase(java.util.Locale.ROOT));
        assertEquals(Integer.toString(star.body.length),star.header("Content-Length"));
    }

    // CON-APP-010 AC-1 AC-2: historical strict rejection remains independently exercised, not silently discarded.
    @Test public void retainedStrictRouterRejectsEveryHistoricalUnsafeTarget() {
        com.snoworca.naruserve.routing.Router<String> strict=new com.snoworca.naruserve.routing.Router<>(
                com.snoworca.naruserve.routing.RouterPolicy.strict());
        strict.add(com.snoworca.naruserve.Method.GET,"/a","safe");strict.freeze();
        assertEquals("safe",strict.lookup("GET","/a",false).handler());
        assertEquals(com.snoworca.naruserve.routing.RouteMatch.Kind.METHOD_MISMATCH,
                strict.lookup("DELETE","/a",false).kind());
        for(String raw:CASES.values()) org.junit.Assert.assertThrows(raw,IllegalArgumentException.class,
                ()->strict.lookup("GET",raw,false));
    }
}
