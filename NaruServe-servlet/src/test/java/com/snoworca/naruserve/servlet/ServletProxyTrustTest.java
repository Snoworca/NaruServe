package com.snoworca.naruserve.servlet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.TestApp;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import javax.servlet.http.HttpServletRequest;
import org.junit.Rule;
import org.junit.Test;

/** SEC-SVL-001: socketAddress(), hop and CIDR trust and isSecure() on the Servlet adapter, against the in-memory one. */
public class ServletProxyTrustTest {

    @Rule
    public final ContainerRule container = new ContainerRule();

    /** A request whose getRemoteAddr and isSecure are fixed; every call is written to {@code calls}. */
    private static HttpServletRequest peer(HttpServletRequest target, String remote, boolean secure,
            List<String> calls) {
        return (HttpServletRequest) Proxy.newProxyInstance(ServletProxyTrustTest.class.getClassLoader(),
                new Class<?>[] {HttpServletRequest.class}, (proxy, method, args) -> {
                    String name = method.getName();
                    synchronized (calls) {
                        calls.add(args != null && args.length == 1 && args[0] instanceof String
                                ? name + "(" + args[0] + ")" : name);
                    }
                    if (name.equals("getRemoteAddr")) {
                        return remote;
                    }
                    if (name.equals("isSecure")) {
                        return secure;
                    }
                    try {
                        return method.invoke(target, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private static final String[] SETTINGS = {"default", "hops=1", "cidrs=10.0.0.0/8"};
    private static final String[] PEERS = {"10.0.0.1", "198.51.100.20"};
    private static final String[][] HEADERS = {
        {},
        {"X-Forwarded-For", "203.0.113.7"},
        {"X-Forwarded-For", "203.0.113.7", "X-Forwarded-Proto", "https"},
        {"X-Forwarded-For", "203.0.113.7", "X-Forwarded-Proto", "https, https"},
        {"X-Forwarded-For", "1.2.3.4, 203.0.113.7", "X-Forwarded-Proto", "http"},
        {"X-Forwarded-Proto", "https"},
        {"Forwarded", "for=198.51.100.1;proto=https"},
        {"X-Forwarded-For", "203.0.113.7", "Forwarded", "for=198.51.100.1;proto=https", "X-Forwarded-Proto", "https"},
    };

    private static void configure(RestApp app, String setting) {
        if (setting.startsWith("hops=")) {
            app.trustProxyHops(Integer.parseInt(setting.substring(5)));
        } else if (setting.startsWith("cidrs=")) {
            app.trustProxyCidrs(setting.substring(6));
        }
        app.get("/who", (req, res) -> res.send(req.socketAddress() + " " + req.remoteAddress() + " " + req.isSecure()));
    }

    private static String label(String setting, String peer, String[] headers) {
        return setting + " " + peer + " " + String.join(",", headers);
    }

    /** One Jetty context per setting and peer; returns the bodies in case-table order. */
    private List<String> onServlet(String setting, String peer, List<String> calls) throws Exception {
        container.filter((req, res, chain) -> chain.doFilter(peer((HttpServletRequest) req, peer, false, calls), res));
        SharedApp.onJetty(container, "/*", (Consumer<RestApp>) app -> configure(app, setting));
        List<String> out = new ArrayList<>();
        for (String[] h : HEADERS) {
            SharedApp.Reply r = SharedApp.http(container.port(), "GET", "/who", null, h);
            assertEquals(label(setting, peer, h), 200, r.status);
            out.add(r.text());
        }
        return out;
    }

    private static List<String> inMemory(String setting, String peer) {
        TestApp t = TestApp.create(new InMemoryHttpAdapter(peer, false));
        try {
            configure(t.app(), setting);
            List<String> out = new ArrayList<>();
            for (String[] h : HEADERS) {
                out.add(t.send("GET", "/who", h).text());
            }
            return out;
        } finally {
            t.app().shutdown(5, java.util.concurrent.TimeUnit.SECONDS); // its adapter pool ends with it
        }
    }

    // SEC-SVL-001 AC-1, AC-2, AC-3, AC-4: the shared case table gives the in-memory results on Jetty
    @Test(timeout = 60000)
    public void SEC_SVL_001_AC1_AC2_AC3_AC4_caseTableMatchesTheInMemoryAdapter() throws Exception {
        for (String setting : SETTINGS) {
            for (String peer : PEERS) {
                List<String> calls = Collections.synchronizedList(new ArrayList<String>());
                List<String> servlet = onServlet(setting, peer, calls);
                assertEquals(setting + " " + peer, inMemory(setting, peer), servlet);
            }
        }
    }

    // SEC-SVL-001 AC-1, AC-2, AC-3: the named values of the requirement
    @Test(timeout = 60000)
    public void SEC_SVL_001_AC1_AC2_AC3_namedValues() throws Exception {
        List<String> calls = Collections.synchronizedList(new ArrayList<String>());
        List<String> hops = onServlet("hops=1", "10.0.0.1", calls);
        assertEquals("10.0.0.1 203.0.113.7 false", hops.get(1));
        assertEquals("10.0.0.1 203.0.113.7 true", hops.get(2));
        assertEquals("10.0.0.1 203.0.113.7 false", hops.get(3));
        List<String> cidrs = onServlet("cidrs=10.0.0.0/8", "10.0.0.1", calls);
        assertEquals("10.0.0.1 203.0.113.7 false", cidrs.get(1));
        List<String> untrusted = onServlet("cidrs=10.0.0.0/8", "198.51.100.20", calls);
        assertEquals("198.51.100.20 198.51.100.20 false", untrusted.get(1));
        List<String> plain = onServlet("default", "10.0.0.1", calls);
        assertEquals("10.0.0.1 10.0.0.1 false", plain.get(1));
    }

    // SEC-SVL-001 AC-5: for every setting and header combination the exchange takes getRemoteAddr and isSecure as
    // captured; getHeaders runs only inside the getHeaderNames snapshot loop (each name once), getHeader is never
    // called for a forwarding header and getRemoteHost never at all. This is the Servlet adapter's half of
    // SEC-HTTP-001 AC-6, SEC-ENGINE-001 AC-6 and SEC-ENGINE-002 AC-6: CrossAdapterTest scans the TinyHttpd and in-memory classes
    @Test(timeout = 60000)
    public void SEC_SVL_001_AC5_exchangeReadsNoForwardingHeaderAndNoRemoteHost() throws Exception {
        for (String setting : SETTINGS) {
            List<String> calls = Collections.synchronizedList(new ArrayList<String>());
            List<String> bodies = onServlet(setting, "198.51.100.20", calls);
            if (!setting.startsWith("hops=")) {
                for (String body : bodies) {
                    assertEquals(setting + " " + body, "198.51.100.20 198.51.100.20 false", body);
                }
            } else {
                // under hops the client address and scheme come from the forwarding headers, but socketAddress()
                // is still the captured getRemoteAddr, and every value equals the in-memory adapter's over the same
                // captured peer and TLS flag
                for (String body : bodies) {
                    assertTrue(setting + " " + body, body.startsWith("198.51.100.20 "));
                }
                assertEquals(setting, inMemory(setting, "198.51.100.20"), bodies);
            }
            List<String> snapshot;
            synchronized (calls) {
                snapshot = new ArrayList<>(calls);
            }
            assertTrue(snapshot.toString(), snapshot.contains("getHeaderNames"));
            assertTrue(snapshot.toString(), snapshot.contains("getRemoteAddr"));
            assertTrue(snapshot.toString(), snapshot.contains("isSecure"));
            java.util.Set<String> loop = null;
            String previous = "";
            for (String call : snapshot) {
                String lower = call.toLowerCase(Locale.ROOT);
                assertFalse(call, call.equals("getRemoteHost"));
                if (lower.startsWith("getheader(")) {
                    assertFalse(setting + " " + call, lower.contains("forwarded"));
                }
                if (call.equals("getHeaderNames")) {
                    loop = new java.util.TreeSet<>();
                } else if (call.startsWith("getHeaders(")) {
                    assertTrue(setting + " " + call + " outside the snapshot loop after " + previous,
                            loop != null && (previous.equals("getHeaderNames") || previous.startsWith("getHeaders(")));
                    assertTrue(setting + " " + call + " twice in one loop", loop.add(lower));
                } else {
                    loop = null;
                }
                previous = call;
            }
        }
    }
}
