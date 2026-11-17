package com.snoworca.naruserve.proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.TestApp;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Assume;
import org.junit.Test;

/**
 * The target URL grammar of {@code Proxy.to} (FR-PROXY-001): the accepted http/https origin with an optional base
 * path (design D-048), the rejections at the call, the trailing slashes removed from the base path and the base path
 * checked like a request path (design D-072), and the base path prefixed to every forwarded path, observed at a
 * recording backend under the {@code /api/orders} mount.
 */
public class ProxyTargetUrlTest {

    private final InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
    private final TestApp t = TestApp.create(adapter);
    private final RestApp app = t.app();
    private final List<Closeable> closeables = new ArrayList<>();
    private final List<RestApp> started = new ArrayList<>();

    @After
    public void tearDown() throws IOException {
        for (RestApp a : started) {
            a.shutdown(2, TimeUnit.SECONDS);
        }
        for (Closeable c : closeables) {
            c.close();
        }
    }

    /** Asserts that {@code Proxy.to(url)} throws {@code IllegalArgumentException} for every url, at the call. */
    private static void assertRejected(String... urls) {
        for (String url : urls) {
            try {
                Proxy p = Proxy.to(url);
                fail("Proxy.to accepted " + url + " as " + p.describe());
            } catch (IllegalArgumentException expected) {
                // rejected at the call
            }
        }
    }

    /** The {@code target=...} part of {@code describe()} for {@code Proxy.to(url)}. */
    private static String target(String url) {
        String d = Proxy.to(url).describe();
        return d.substring(0, d.indexOf(' '));
    }

    /** The request line a recording backend receives for GET {@code /api/orders/x} through {@code Proxy.to(url)}. */
    private String forwardedRequestLine(String url) throws IOException {
        RawBackend backend = RawBackend.answering(RawBackend.response(200, "ok"));
        closeables.add(backend);
        app.use("/api/orders", Proxy.to(url.replace("<port>", Integer.toString(backend.port()))));
        t.start();
        started.add(app);

        ClientResponse r = adapter.send(ClientRequest.of("GET", "/api/orders/x"));

        assertEquals("status for " + url, 200, r.status());
        assertEquals("ok", r.text());
        return backend.onlyRequest().requestLine();
    }

    // ---- AC-1 ----

    // FR-PROXY-001 AC-1: a query, with or without a path before it, is rejected at the call
    @Test
    public void fr_PROXY_001_AC1_queryIsRejected() {
        assertRejected("http://h:8081/?a=1", "http://h:8081?a=1", "http://h/base?a=1", "http://h:8081/?");
    }

    // FR-PROXY-001 AC-1: a fragment, with or without a path before it, is rejected at the call
    @Test
    public void fr_PROXY_001_AC1_fragmentIsRejected() {
        assertRejected("http://h:8081/#f", "http://h:8081#f", "http://h/base#f", "http://h:8081/#");
    }

    // FR-PROXY-001 AC-1: user information in the authority is rejected at the call
    @Test
    public void fr_PROXY_001_AC1_userInformationIsRejected() {
        assertRejected("http://user@h:8081", "http://user:pw@h:8081/base", "http://@h:8081", "https://u@h");
    }

    // FR-PROXY-001 AC-1: a scheme other than http or https is rejected at the call
    @Test
    public void fr_PROXY_001_AC1_otherSchemesAreRejected() {
        assertRejected("ftp://h/x", "ws://h:8081", "httpx://h", "file:///x", "http+unix://h");
    }

    // FR-PROXY-001 AC-1: a URL without a host is rejected at the call
    @Test
    public void fr_PROXY_001_AC1_missingHostIsRejected() {
        assertRejected("http:///x", "http://:8081", "http://", "https://:443/base", "http://[]:8081");
    }

    // ---- AC-2 ----

    // FR-PROXY-001 AC-2: the four accepted forms construct a proxy; the scheme is matched case-insensitively
    @Test
    public void fr_PROXY_001_AC2_acceptedFormsConstruct() {
        assertEquals("target=http://h", target("http://h"));
        assertEquals("target=https://h:8443", target("HTTPS://h:8443"));
        assertEquals("target=http://127.0.0.1:8081", target("http://127.0.0.1:8081"));
        assertEquals("target=http://[::1]:8081", target("http://[::1]:8081"));
    }

    // FR-PROXY-001 AC-2: names with digits, hyphens, dots and underscores, full IPv6 forms and the port bounds pass
    @Test
    public void fr_PROXY_001_AC2_otherAcceptedHostsAndPortBounds() {
        assertEquals("target=http://api-1.internal.example:1", target("http://api-1.internal.example:1"));
        assertEquals("target=http://backend_svc:65535", target("http://backend_svc:65535"));
        assertEquals("target=http://H", target("Http://H"));
        assertEquals("target=http://[2001:db8::8:800:200c:417a]",
                target("http://[2001:db8::8:800:200c:417a]"));
        assertEquals("target=http://[::ffff:192.0.2.1]:80", target("http://[::ffff:192.0.2.1]:80"));
        assertEquals("target=http://[1:2:3:4:5:6:7:8]", target("http://[1:2:3:4:5:6:7:8]"));
    }

    // FR-PROXY-001 AC-2: an IPv6 literal with a zone id is rejected, encoded or raw
    @Test
    public void fr_PROXY_001_AC2_ipv6ZoneIdIsRejected() {
        assertRejected("http://[fe80::1%25eth0]:8081", "http://[fe80::1%eth0]:8081", "http://[fe80::1%25eth0]");
    }

    // FR-PROXY-001 AC-2: a malformed IPv6 literal or an unbracketed one is rejected
    @Test
    public void fr_PROXY_001_AC2_malformedIpv6IsRejected() {
        assertRejected("http://[::1:8081", "http://[::1]x", "http://[::1]:", "http://[zz::1]:8081",
                "http://[1:2:3:4:5:6:7:8:9]", "http://[1::2::3]", "http://[12345::1]", "http://::1:8081",
                "http://[v1.fe80::a+en1]", "http://[::1]]:8081", "http://[:1]");
    }

    // FR-PROXY-001 AC-2: a host with a non-ASCII character is rejected
    @Test
    public void fr_PROXY_001_AC2_nonAsciiHostIsRejected() {
        assertRejected("http://bück.example:8081", "http://例え.jp", "http://hé", "http://１２７.0.0.1:8081");
    }

    // FR-PROXY-001 AC-2: a host holding characters a host name never holds is rejected
    @Test
    public void fr_PROXY_001_AC2_hostWithOtherCharactersIsRejected() {
        assertRejected("http://h%41:8081", "http://h x:8081", "http://h\\x", "http://h!:8081", "http://h,i",
                "http://h;x", "http://h=x", "http://h~x", "http://h\t", "http:// h");
    }

    // FR-PROXY-001 AC-2: an all-digit dotted host must be a dotted-quad IPv4 literal with octets 0..255
    @Test
    public void fr_PROXY_001_AC2_malformedIpv4IsRejected() {
        assertRejected("http://256.0.0.1:8081", "http://127.0.0:8081", "http://127.0.0.1.5", "http://1.2.3.04",
                "http://127..0.1", "http://4294967295");
    }

    // FR-PROXY-001 AC-2: a name whose last label is a number (decimal or 0x hexadecimal) is not a host name, so the
    // lenient numeric IPv4 forms resolvers read as addresses (0x7f.1, 127.0.0.0x1) are rejected; names still pass
    @Test
    public void fr_PROXY_001_AC2_numericLastLabelIsRejected() {
        assertRejected("http://0x7f.1:8081", "http://127.0.0.0x1", "http://0x7f000001", "http://h.123",
                "http://0X7F.0.0.1", "http://127.0.0.1.:8081", "http://0x");
        assertEquals("target=http://0x7f.example", target("http://0x7f.example"));
        assertEquals("target=http://h1.example.", target("http://h1.example."));
        assertEquals("target=http://123.example:8081", target("http://123.example:8081"));
    }

    // FR-PROXY-001 AC-2: a port of 0, 65536, abc and every other non-decimal or out-of-range port is rejected
    @Test
    public void fr_PROXY_001_AC2_badPortIsRejected() {
        assertRejected("http://h:0", "http://h:65536", "http://h:abc", "http://h:", "http://h:+80", "http://h:-1",
                "http://h:８０", "http://h:99999999999", "http://h:80:81", "http://h: 80", "http://h:0x50");
    }

    // FR-PROXY-001 AC-2: a string that is not a URL is rejected
    @Test
    public void fr_PROXY_001_AC2_notAUrlIsRejected() {
        assertRejected("", "not a url", "h:8081", "127.0.0.1:8081", "//h:8081", "http:/h", "http:h", "http//h",
                "://h", " http://h", "http://h ", "http:\\\\h", "1http://h");
    }

    // FR-PROXY-001 AC-2: http://[::1]:<port> connects to the IPv6 loopback, the brackets are not part of the host
    @Test
    public void fr_PROXY_001_AC2_ipv6LiteralConnectsWithoutBrackets() throws IOException {
        ServerSocket v6;
        try {
            v6 = new ServerSocket(0, 50, InetAddress.getByName("::1"));
        } catch (IOException e) {
            Assume.assumeNoException("no IPv6 loopback on this machine", e);
            return;
        }
        closeables.add(v6);
        Thread acceptor = new Thread(() -> answerOnce(v6), "v6-backend");
        acceptor.setDaemon(true);
        acceptor.start();
        app.use("/api/orders", Proxy.to("http://[::1]:" + v6.getLocalPort()));
        t.start();
        started.add(app);

        ClientResponse r = adapter.send(ClientRequest.of("GET", "/api/orders/x"));

        assertEquals(200, r.status());
        assertEquals("v6", r.text());
    }

    /** Reads one request head on {@code server} and answers 200 with body {@code v6}. */
    private static void answerOnce(ServerSocket server) {
        try (Socket s = server.accept()) {
            s.setSoTimeout(10_000);
            InputStream in = s.getInputStream();
            int matched = 0;
            byte[] end = {'\r', '\n', '\r', '\n'};
            while (matched < 4) {
                int b = in.read();
                if (b < 0) {
                    return;
                }
                matched = b == end[matched] ? matched + 1 : (b == '\r' ? 1 : 0);
            }
            OutputStream out = s.getOutputStream();
            out.write("HTTP/1.1 200 X\r\nContent-Length: 2\r\n\r\nv6".getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
        } catch (IOException e) {
            // the test fails on the status instead
        }
    }

    // ---- AC-3 ----

    // FR-PROXY-001 AC-3: a target whose base path is only "/" forwards /api/orders/x unchanged
    @Test
    public void fr_PROXY_001_AC3_rootBasePathForwardsThePathUnchanged() throws IOException {
        assertEquals("GET /api/orders/x HTTP/1.1", forwardedRequestLine("http://127.0.0.1:<port>/"));
    }

    // FR-PROXY-001 AC-3: the base path /base/ loses its trailing slash before it prefixes the path
    @Test
    public void fr_PROXY_001_AC3_basePathWithTrailingSlashPrefixesWithoutDoubleSlash() throws IOException {
        assertEquals("GET /base/api/orders/x HTTP/1.1", forwardedRequestLine("http://127.0.0.1:<port>/base/"));
    }

    // FR-PROXY-001 AC-3: every trailing slash of /base// is removed, so no // is built from the base path
    @Test
    public void fr_PROXY_001_AC3_basePathWithTrailingSlashesPrefixesWithoutDoubleSlash() throws IOException {
        assertEquals("GET /base/api/orders/x HTTP/1.1", forwardedRequestLine("http://127.0.0.1:<port>/base//"));
    }

    // FR-PROXY-001 AC-3: the trailing slashes are removed in the configuration too, "/" and "//" leave no base path
    @Test
    public void fr_PROXY_001_AC3_trailingSlashesAreRemovedFromTheConfiguredBasePath() {
        assertEquals("target=http://h:8081", target("http://h:8081/"));
        assertEquals("target=http://h:8081", target("http://h:8081//"));
        assertEquals("target=http://h:8081/base", target("http://h:8081/base/"));
        assertEquals("target=http://h:8081/base", target("http://h:8081/base//"));
        assertEquals("target=http://h:8081/a/b", target("http://h:8081/a/b///"));
    }

    // ---- AC-4 ----

    // FR-PROXY-001 AC-4: a base path equal to the mount path is still prefixed, giving /api/orders/api/orders/x
    @Test
    public void fr_PROXY_001_AC4_basePathEqualToTheMountIsPrefixed() throws IOException {
        assertEquals("GET /api/orders/api/orders/x HTTP/1.1",
                forwardedRequestLine("http://127.0.0.1:<port>/api/orders"));
    }

    // ---- AC-5 ----

    // FR-PROXY-001 AC-5: the base paths of the requirement's examples fail the SEC-PROXY-001 checks and are rejected
    @Test
    public void fr_PROXY_001_AC5_basePathsFailingThePathChecksAreRejected() {
        assertRejected("http://h/a/../b", "http://h/a;x", "http://h/a//b", "http://h/%2e", "http://h/a%zz");
    }

    // FR-PROXY-001 AC-5: the other segment and character rules of the path check apply to the base path too
    @Test
    public void fr_PROXY_001_AC5_otherPathCheckFailuresAreRejected() {
        assertRejected("http://h/a/..", "http://h/a/../", "http://h/./a", "http://h/%2E%2E/a", "http://h/a%2fb",
                "http://h/a%5Cb", "http://h/a%00b", "http://h/%70rivate", "http://h/%252e%252e", "http://h/a\\b",
                "http://h/a b", "http://h/a\u007Fb", "http://h/aéb", "http://h/a%", "http://h//x", "http://h:8081//x/");
    }

    // FR-PROXY-001 AC-5: a base path the checks accept is kept as written (reserved characters, percent-encodings)
    @Test
    public void fr_PROXY_001_AC5_basePathsPassingThePathChecksAreKept() {
        for (String path : Arrays.asList("/a@b", "/v1/items", "/a%20b", "/a:b", "/a-b_c.d~e", "/%E4%BE%8B",
                "/a%2Cb")) {
            assertEquals("target=http://h" + path, target("http://h" + path));
        }
    }

    // ---- AC-6 ----

    // FR-PROXY-001 AC-6: Proxy.to(null) throws NullPointerException
    @Test
    public void fr_PROXY_001_AC6_nullUrlThrowsNullPointerException() {
        try {
            Proxy.to((String) null);
            fail("Proxy.to(null) returned");
        } catch (NullPointerException expected) {
            // the null answer of D-072
        }
    }
}
