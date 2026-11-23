package com.snoworca.naruserve.proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.TestApp;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLSocketFactory;
import org.junit.After;
import org.junit.Test;

/** The call-time checks of the {@link Proxy} option values (FR-PROXY-002). */
public class ProxyOptionsTest {

    /** A call of one option method; it throws when the option rejects its argument. */
    private interface OptionCall {
        Proxy call(Proxy p);
    }

    /** The request header names the proxy manages, as AC-4 lists them. */
    private static final List<String> REQUEST_MANAGED = Arrays.asList("Connection", "Keep-Alive",
            "Proxy-Connection", "TE", "Transfer-Encoding", "Upgrade", "Proxy-Authenticate", "Proxy-Authorization",
            "Trailer", "Host", "Content-Length", "Expect", "Forwarded", "X-Forwarded-For", "X-Forwarded-Proto",
            "X-Forwarded-Host");

    /** The response header names the proxy manages: the hop-by-hop names of AC-4 and Content-Length (AC-6). */
    private static final List<String> RESPONSE_MANAGED = Arrays.asList("Connection", "Keep-Alive",
            "Proxy-Connection", "TE", "Transfer-Encoding", "Upgrade", "Proxy-Authenticate", "Proxy-Authorization",
            "Trailer", "Content-Length");

    /** Header names that are not RFC 9110 tokens. */
    private static final List<String> NOT_TOKENS = Arrays.asList("", "X A", "X:A", "X\r\nA", "X\u0000A", "X\"A",
            "X(A)", "X/A", "X@A", "X\u00e9A", "X\tA");

    /** Header values that HttpHeaderValues.isFieldValue rejects. */
    private static final List<String> BAD_VALUES = Arrays.asList("a\r\nb", "a\nb", "a\rb", "a\u0000b", "a\u007fb",
            "a\u0001b", "a\u0100b");

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

    private static Proxy proxy() {
        return Proxy.to("http://127.0.0.1:8081");
    }

    private static void assertRejects(Class<? extends RuntimeException> expected, String what, OptionCall call) {
        Proxy p = proxy();
        String before = p.describe();
        try {
            call.call(p);
            fail(what + " should throw " + expected.getSimpleName());
        } catch (RuntimeException e) {
            assertSame(what + " threw " + e, expected, e.getClass());
        }
        assertEquals(what + " left its receiver unchanged", before, p.describe());
    }

    private static void assertIllegal(String what, OptionCall call) {
        assertRejects(IllegalArgumentException.class, what, call);
    }

    private static void assertNpe(String what, OptionCall call) {
        assertRejects(NullPointerException.class, what, call);
    }

    private static Proxy assertAccepted(String what, OptionCall call) {
        Proxy p = proxy();
        Proxy q = call.call(p);
        assertNotNull(what, q);
        assertNotSame(what + " returns a new Proxy", p, q);
        return q;
    }

    /** {@code name} as written, in lower case and in upper case. */
    private static List<String> cases(String name) {
        return Arrays.asList(name, name.toLowerCase(Locale.ROOT), name.toUpperCase(Locale.ROOT));
    }

    // FR-PROXY-002 AC-1: each time option throws IllegalArgumentException for 0 and -1 in every unit
    @Test
    public void fr_PROXY_002_AC1_timeOptionsRejectZeroAndMinusOneInEveryUnit() {
        for (final TimeUnit unit : TimeUnit.values()) {
            for (final long amount : new long[] {0, -1, Long.MIN_VALUE}) {
                String at = "(" + amount + ", " + unit + ")";
                assertIllegal("connectTimeout" + at, p -> p.connectTimeout(amount, unit));
                assertIllegal("writeTimeout" + at, p -> p.writeTimeout(amount, unit));
                assertIllegal("readTimeout" + at, p -> p.readTimeout(amount, unit));
                assertIllegal("totalTimeout" + at, p -> p.totalTimeout(amount, unit));
            }
        }
    }

    // FR-PROXY-002 AC-1: readTimeout(0, TimeUnit.SECONDS) throws IllegalArgumentException at the call
    @Test
    public void fr_PROXY_002_AC1_readTimeoutZeroSecondsThrows() {
        assertIllegal("readTimeout(0, SECONDS)", p -> p.readTimeout(0, TimeUnit.SECONDS));
    }

    // FR-PROXY-002 AC-1: each time option accepts 1 nanosecond
    @Test
    public void fr_PROXY_002_AC1_timeOptionsAcceptOneNanosecond() {
        assertEquals(1, assertAccepted("connectTimeout(1, NANOSECONDS)",
                p -> p.connectTimeout(1, TimeUnit.NANOSECONDS)).connectTimeoutNanos());
        assertEquals(1, assertAccepted("writeTimeout(1, NANOSECONDS)",
                p -> p.writeTimeout(1, TimeUnit.NANOSECONDS)).writeTimeoutNanos());
        assertEquals(1, assertAccepted("readTimeout(1, NANOSECONDS)",
                p -> p.readTimeout(1, TimeUnit.NANOSECONDS)).readTimeoutNanos());
        assertEquals(1, assertAccepted("totalTimeout(1, NANOSECONDS)",
                p -> p.totalTimeout(1, TimeUnit.NANOSECONDS)).totalTimeoutNanos());
    }

    // FR-PROXY-002 AC-2: retries(-1) and retryDelay(-1, MILLISECONDS) throw IllegalArgumentException
    @Test
    public void fr_PROXY_002_AC2_negativeRetriesAndRetryDelayThrow() {
        assertIllegal("retries(-1)", p -> p.retries(-1));
        assertIllegal("retries(Integer.MIN_VALUE)", p -> p.retries(Integer.MIN_VALUE));
        assertIllegal("retryDelay(-1, MILLISECONDS)", p -> p.retryDelay(-1, TimeUnit.MILLISECONDS));
        for (final TimeUnit unit : TimeUnit.values()) {
            assertIllegal("retryDelay(-1, " + unit + ")", p -> p.retryDelay(-1, unit));
        }
    }

    // FR-PROXY-002 AC-2: retries(0) and retryDelay(0, MILLISECONDS) are accepted
    @Test
    public void fr_PROXY_002_AC2_zeroRetriesAndZeroRetryDelayAreAccepted() {
        Proxy noRetries = proxy().retries(3).retries(0);
        assertEquals(0, noRetries.retries());
        Proxy noDelay = assertAccepted("retryDelay(0, MILLISECONDS)", p -> p.retryDelay(0, TimeUnit.MILLISECONDS));
        assertEquals(0, noDelay.retryDelayNanos());
    }

    // FR-PROXY-002 AC-3: bodyLimit(0) and bodyLimit(1073741825) throw, bodyLimit(1) and bodyLimit(1073741824) are accepted
    @Test
    public void fr_PROXY_002_AC3_bodyLimitRange() {
        assertIllegal("bodyLimit(0)", p -> p.bodyLimit(0));
        assertIllegal("bodyLimit(-1)", p -> p.bodyLimit(-1));
        assertIllegal("bodyLimit(1073741825)", p -> p.bodyLimit(1073741825L));
        assertIllegal("bodyLimit(Long.MAX_VALUE)", p -> p.bodyLimit(Long.MAX_VALUE));
        assertEquals(1, assertAccepted("bodyLimit(1)", p -> p.bodyLimit(1)).bodyLimit());
        assertEquals(1073741824L,
                assertAccepted("bodyLimit(1073741824)", p -> p.bodyLimit(1073741824L)).bodyLimit());
    }

    // FR-PROXY-002 AC-3, IR-HTTP-013: the bodyLimit range Proxy accepts is the range req.bodyStream(n) accepts, where
    // the proxy passes the value at request time; the core keeps its bound package-private, so this pins the two
    // together and a drift of either fails here instead of turning an accepted bodyLimit into a 500 per request
    @Test
    public void fr_PROXY_002_AC3_bodyLimitRangeIsTheRangeOfReqBodyStream() {
        app.post("/bound/:n", (req, res) -> {
            String outcome;
            try {
                req.bodyStream(Long.parseLong(req.param("n"))).close();
                outcome = "accepted";
            } catch (IllegalArgumentException e) {
                outcome = "rejected";
            }
            res.send(outcome);
        });
        started = true;

        for (long n : new long[] {0, 1, Proxy.MAX_BODY_LIMIT, Proxy.MAX_BODY_LIMIT + 1}) {
            ClientResponse r = t.send("POST", "/bound/" + n, "Content-Length", "0");
            boolean proxyAccepts;
            try {
                proxy().bodyLimit(n);
                proxyAccepts = true;
            } catch (IllegalArgumentException e) {
                proxyAccepts = false;
            }
            assertEquals("req.bodyStream(" + n + ")", proxyAccepts ? "accepted" : "rejected", r.text());
        }
    }

    // FR-PROXY-002 AC-3: maxConcurrent(0) throws IllegalArgumentException while maxConcurrent(1) is accepted
    @Test
    public void fr_PROXY_002_AC3_maxConcurrentRange() {
        assertIllegal("maxConcurrent(0)", p -> p.maxConcurrent(0));
        assertIllegal("maxConcurrent(-1)", p -> p.maxConcurrent(-1));
        assertEquals(1, assertAccepted("maxConcurrent(1)", p -> p.maxConcurrent(1)).maxConcurrent());
    }

    // FR-PROXY-002 AC-4: requestHeader and removeRequestHeader throw for every managed name in any letter case
    @Test
    public void fr_PROXY_002_AC4_requestHeaderRejectsEveryManagedNameInAnyCase() {
        for (String managed : REQUEST_MANAGED) {
            for (final String name : cases(managed)) {
                assertIllegal("requestHeader(" + name + ")", p -> p.requestHeader(name, "v"));
                assertIllegal("removeRequestHeader(" + name + ")", p -> p.removeRequestHeader(name));
            }
        }
    }

    // FR-PROXY-002 AC-4: managed names are matched in any letter case whatever the default locale (Turkish dotless i)
    @Test
    public void fr_PROXY_002_AC4_managedNamesMatchUnderATurkishDefaultLocale() {
        Locale previous = Locale.getDefault();
        Locale.setDefault(new Locale("tr", "TR"));
        try {
            for (final String name : Arrays.asList("CONNECTION", "PROXY-AUTHORIZATION", "PROXY-AUTHENTICATE",
                    "TRAILER", "KEEP-ALIVE", "PROXY-CONNECTION")) {
                assertIllegal("requestHeader(" + name + ") under tr", p -> p.requestHeader(name, "x"));
                assertIllegal("removeRequestHeader(" + name + ") under tr", p -> p.removeRequestHeader(name));
                assertIllegal("responseHeader(" + name + ") under tr", p -> p.responseHeader(name, "x"));
                assertIllegal("removeResponseHeader(" + name + ") under tr", p -> p.removeResponseHeader(name));
            }
        } finally {
            Locale.setDefault(previous);
        }
        // U+212A KELVIN SIGN lower-cases to "k": the name is rejected as a non-token, never matched as a managed name
        String kelvinKeepAlive = "\u212Aeep-Alive";
        assertEquals("keep-alive", kelvinKeepAlive.toLowerCase(Locale.ROOT));
        try {
            proxy().requestHeader(kelvinKeepAlive, "x");
            fail("a Kelvin-sign name should throw IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertEquals("requestHeader: the header name is not an RFC 9110 token", e.getMessage());
        }
    }

    // FR-PROXY-002 AC-4: the examples of the criterion throw IllegalArgumentException
    @Test
    public void fr_PROXY_002_AC4_requestHeaderExamplesThrow() {
        assertIllegal("requestHeader(Host, h)", p -> p.requestHeader("Host", "h"));
        assertIllegal("requestHeader(connection, x)", p -> p.requestHeader("connection", "x"));
        assertIllegal("requestHeader(X-Forwarded-For, 1.2.3.4)", p -> p.requestHeader("X-Forwarded-For", "1.2.3.4"));
        assertIllegal("requestHeader(Forwarded, for=x)", p -> p.requestHeader("Forwarded", "for=x"));
        assertIllegal("removeRequestHeader(Host)", p -> p.removeRequestHeader("Host"));
        assertIllegal("requestHeader(tRaNsFeR-eNcOdInG, x)", p -> p.requestHeader("tRaNsFeR-eNcOdInG", "x"));
    }

    // FR-PROXY-002 AC-4: a name the proxy does not manage is accepted and recorded
    @Test
    public void fr_PROXY_002_AC4_unmanagedRequestHeaderNamesAreAccepted() {
        Proxy q = assertAccepted("requestHeader(X-Api-Key, k)", p -> p.requestHeader("X-Api-Key", "k"));
        assertEquals(1, q.requestHeaderRules().size());
        assertEquals("X-Api-Key", q.requestHeaderRules().get(0).getKey());
        assertEquals("k", q.requestHeaderRules().get(0).getValue());
        Proxy r = assertAccepted("removeRequestHeader(Cookie)", p -> p.removeRequestHeader("Cookie"));
        assertEquals("Cookie", r.requestHeaderRules().get(0).getKey());
        assertAccepted("requestHeader(X-Forwarded-Port, 80)", p -> p.requestHeader("X-Forwarded-Port", "80"));
    }

    // FR-PROXY-002 AC-5: requestHeader and removeRequestHeader throw for a name that is not an RFC 9110 token
    @Test
    public void fr_PROXY_002_AC5_requestHeaderRejectsNonTokenNames() {
        for (final String name : NOT_TOKENS) {
            assertIllegal("requestHeader(" + name + ")", p -> p.requestHeader(name, "v"));
            assertIllegal("removeRequestHeader(" + name + ")", p -> p.removeRequestHeader(name));
        }
    }

    // FR-PROXY-002 AC-5: requestHeader throws for a value isFieldValue rejects, e.g. "a\r\nb" and "a\u0000b"
    @Test
    public void fr_PROXY_002_AC5_requestHeaderRejectsValuesThatAreNotFieldValues() {
        assertIllegal("requestHeader(X-A, a CR LF b)", p -> p.requestHeader("X-A", "a\r\nb"));
        assertIllegal("requestHeader(X-A, a NUL b)", p -> p.requestHeader("X-A", "a\u0000b"));
        for (final String value : BAD_VALUES) {
            assertIllegal("requestHeader(X-A, " + escape(value) + ")", p -> p.requestHeader("X-A", value));
        }
        assertAccepted("requestHeader(X-A, a TAB b)", p -> p.requestHeader("X-A", "a\tb"));
        assertAccepted("requestHeader(X-A, empty)", p -> p.requestHeader("X-A", ""));
        assertAccepted("requestHeader(X-A, latin-1)", p -> p.requestHeader("X-A", "caf\u00e9"));
    }

    // FR-PROXY-002 AC-6: responseHeader and removeResponseHeader throw for the hop-by-hop names and Content-Length
    @Test
    public void fr_PROXY_002_AC6_responseHeaderRejectsEveryManagedNameInAnyCase() {
        for (String managed : RESPONSE_MANAGED) {
            for (final String name : cases(managed)) {
                assertIllegal("responseHeader(" + name + ")", p -> p.responseHeader(name, "v"));
                assertIllegal("removeResponseHeader(" + name + ")", p -> p.removeResponseHeader(name));
            }
        }
    }

    // FR-PROXY-002 AC-6: responseHeader and removeResponseHeader throw for a non-token name, responseHeader for a bad value
    @Test
    public void fr_PROXY_002_AC6_responseHeaderRejectsNonTokenNamesAndBadValues() {
        assertIllegal("responseHeader(Content-Length, 1)", p -> p.responseHeader("Content-Length", "1"));
        assertIllegal("removeResponseHeader(Transfer-Encoding)", p -> p.removeResponseHeader("Transfer-Encoding"));
        assertIllegal("responseHeader(X-A, a LF b)", p -> p.responseHeader("X-A", "a\nb"));
        for (final String name : NOT_TOKENS) {
            assertIllegal("responseHeader(" + name + ")", p -> p.responseHeader(name, "v"));
            assertIllegal("removeResponseHeader(" + name + ")", p -> p.removeResponseHeader(name));
        }
        for (final String value : BAD_VALUES) {
            assertIllegal("responseHeader(X-A, " + escape(value) + ")", p -> p.responseHeader("X-A", value));
        }
    }

    // FR-PROXY-002 AC-6: a response header name the proxy does not manage is accepted and recorded
    @Test
    public void fr_PROXY_002_AC6_unmanagedResponseHeaderNamesAreAccepted() {
        Proxy q = assertAccepted("responseHeader(Cache-Control, no-store)",
                p -> p.responseHeader("Cache-Control", "no-store"));
        assertEquals("Cache-Control", q.responseHeaderRules().get(0).getKey());
        assertEquals("no-store", q.responseHeaderRules().get(0).getValue());
        Proxy r = assertAccepted("removeResponseHeader(Server)", p -> p.removeResponseHeader("Server"));
        assertEquals("Server", r.responseHeaderRules().get(0).getKey());
    }

    // FR-PROXY-002 AC-6: the names managed only on the request side are not managed on the response side
    @Test
    public void fr_PROXY_002_AC6_requestOnlyManagedNamesAreAcceptedOnTheResponseSide() {
        List<String> requestOnly = new ArrayList<>(REQUEST_MANAGED);
        requestOnly.removeAll(RESPONSE_MANAGED);
        assertEquals("Host, Expect, Forwarded and the X-Forwarded-* names",
                Arrays.asList("Host", "Expect", "Forwarded", "X-Forwarded-For", "X-Forwarded-Proto", "X-Forwarded-Host"),
                requestOnly);
        for (final String name : requestOnly) {
            assertEquals(name, assertAccepted("responseHeader(" + name + ")",
                    p -> p.responseHeader(name, "v")).responseHeaderRules().get(0).getKey());
            assertAccepted("removeResponseHeader(" + name + ")", p -> p.removeResponseHeader(name));
        }
    }

    // FR-PROXY-002 AC-7: a null argument throws NullPointerException at the call
    @Test
    public void fr_PROXY_002_AC7_nullArgumentsThrowNullPointerException() {
        String nullString = null;
        TimeUnit nullUnit = null;
        SSLSocketFactory nullFactory = null;
        assertNpe("requestHeader(null, x)", p -> p.requestHeader(nullString, "x"));
        assertNpe("requestHeader(X-A, null)", p -> p.requestHeader("X-A", nullString));
        assertNpe("removeRequestHeader(null)", p -> p.removeRequestHeader(nullString));
        assertNpe("responseHeader(null, x)", p -> p.responseHeader(nullString, "x"));
        assertNpe("responseHeader(X-A, null)", p -> p.responseHeader("X-A", nullString));
        assertNpe("removeResponseHeader(null)", p -> p.removeResponseHeader(nullString));
        assertNpe("sslSocketFactory(null)", p -> p.sslSocketFactory(nullFactory));
        assertNpe("readTimeout(1, null)", p -> p.readTimeout(1, nullUnit));
        assertNpe("connectTimeout(1, null)", p -> p.connectTimeout(1, nullUnit));
        assertNpe("writeTimeout(1, null)", p -> p.writeTimeout(1, nullUnit));
        assertNpe("totalTimeout(1, null)", p -> p.totalTimeout(1, nullUnit));
        assertNpe("retryDelay(1, null)", p -> p.retryDelay(1, nullUnit));
    }

    // FR-PROXY-002 AC-7: a null argument is reported as NullPointerException even beside a value out of range
    @Test
    public void fr_PROXY_002_AC7_nullUnitWinsOverAnOutOfRangeAmount() {
        TimeUnit nullUnit = null;
        assertNpe("readTimeout(0, null)", p -> p.readTimeout(0, nullUnit));
        assertNpe("retryDelay(-1, null)", p -> p.retryDelay(-1, nullUnit));
    }

    // FR-PROXY-002 AC-7: a null header value is reported as NullPointerException even beside a rejected name
    @Test
    public void fr_PROXY_002_AC7_nullValueWinsOverARejectedName() {
        String nullString = null;
        assertNpe("requestHeader(Host, null)", p -> p.requestHeader("Host", nullString));
        assertNpe("requestHeader(X A, null)", p -> p.requestHeader("X A", nullString));
        assertNpe("responseHeader(Content-Length, null)", p -> p.responseHeader("Content-Length", nullString));
        assertNpe("responseHeader(X A, null)", p -> p.responseHeader("X A", nullString));
        assertNpe("requestHeader(null, bad value)", p -> p.requestHeader(nullString, "a\r\nb"));
        assertNpe("responseHeader(null, bad value)", p -> p.responseHeader(nullString, "a\r\nb"));
    }

    // FR-PROXY-002 AC-8: readTimeout(1, NANOSECONDS) reports 1 ms; the other socket time options round the same way
    @Test
    public void fr_PROXY_002_AC8_oneNanosecondRoundsUpToOneMillisecond() {
        Proxy p = proxy().readTimeout(1, TimeUnit.NANOSECONDS);
        assertEquals(1, p.readTimeoutMillis());
        assertEquals(1, p.readTimeoutNanos());
        assertEquals(1, proxy().connectTimeout(1, TimeUnit.NANOSECONDS).connectTimeoutMillis());
        assertEquals(1, proxy().writeTimeout(1, TimeUnit.NANOSECONDS).writeTimeoutMillis());
    }

    // FR-PROXY-002 AC-8: readTimeout(1500, MICROSECONDS) rounds up to 2 ms, a whole millisecond stays as it is
    @Test
    public void fr_PROXY_002_AC8_partialMillisecondsRoundUp() {
        Proxy p = proxy().readTimeout(1500, TimeUnit.MICROSECONDS);
        assertEquals(2, p.readTimeoutMillis());
        assertEquals(1_500_000L, p.readTimeoutNanos());
        assertEquals(2, proxy().connectTimeout(1500, TimeUnit.MICROSECONDS).connectTimeoutMillis());
        assertEquals(2, proxy().writeTimeout(1500, TimeUnit.MICROSECONDS).writeTimeoutMillis());
        assertEquals(1, proxy().readTimeout(1_000_000, TimeUnit.NANOSECONDS).readTimeoutMillis());
        assertEquals(2, proxy().readTimeout(1_000_001, TimeUnit.NANOSECONDS).readTimeoutMillis());
        assertEquals(1000, proxy().readTimeout(1, TimeUnit.SECONDS).readTimeoutMillis());
    }

    // FR-PROXY-002 AC-8: readTimeout(Long.MAX_VALUE, DAYS) clamps to Integer.MAX_VALUE ms and saturates at Long.MAX_VALUE ns
    @Test
    public void fr_PROXY_002_AC8_hugeTimeoutsClampToIntegerMaxMillisAndSaturateNanos() {
        Proxy p = proxy().readTimeout(Long.MAX_VALUE, TimeUnit.DAYS);
        assertEquals(Integer.MAX_VALUE, p.readTimeoutMillis());
        assertEquals(Long.MAX_VALUE, p.readTimeoutNanos());
        Proxy c = proxy().connectTimeout(Long.MAX_VALUE, TimeUnit.DAYS);
        assertEquals(Integer.MAX_VALUE, c.connectTimeoutMillis());
        assertEquals(Long.MAX_VALUE, c.connectTimeoutNanos());
        Proxy w = proxy().writeTimeout(Long.MAX_VALUE, TimeUnit.DAYS);
        assertEquals(Integer.MAX_VALUE, w.writeTimeoutMillis());
        assertEquals(Long.MAX_VALUE, w.writeTimeoutNanos());
        assertEquals("the watchdog value saturates", Long.MAX_VALUE,
                proxy().totalTimeout(Long.MAX_VALUE, TimeUnit.DAYS).totalTimeoutNanos());
        assertEquals(Integer.MAX_VALUE, proxy().readTimeout(Long.MAX_VALUE, TimeUnit.NANOSECONDS).readTimeoutMillis());
        assertEquals(Integer.MAX_VALUE,
                proxy().readTimeout((long) Integer.MAX_VALUE + 1, TimeUnit.MILLISECONDS).readTimeoutMillis());
        assertEquals(Integer.MAX_VALUE,
                proxy().readTimeout(Integer.MAX_VALUE, TimeUnit.MILLISECONDS).readTimeoutMillis());
    }

    // FR-PROXY-002 AC-8: the defaults convert to their socket milliseconds
    @Test
    public void fr_PROXY_002_AC8_defaultsConvertToSocketMilliseconds() {
        Proxy p = proxy();
        assertEquals(10_000, p.connectTimeoutMillis());
        assertEquals(60_000, p.writeTimeoutMillis());
        assertEquals(60_000, p.readTimeoutMillis());
    }

    // FR-PROXY-002 AC-9: Proxy.to("http://h").sslSocketFactory(factory) returns a new Proxy without an exception
    @Test
    public void fr_PROXY_002_AC9_httpTargetAcceptsASocketFactory() {
        RecordingSslSocketFactory factory = new RecordingSslSocketFactory();
        Proxy p = Proxy.to("http://h");
        Proxy q = p.sslSocketFactory(factory);
        assertNotNull(q);
        assertNotSame(p, q);
        assertSame(factory, q.sslSocketFactory());
        assertEquals("the factory is not touched by the option call", 0, factory.calls());
    }

    // FR-PROXY-002 AC-9: an http proxy with a socket factory forwards plain HTTP without calling the factory
    @Test
    public void fr_PROXY_002_AC9_httpProxyForwardsPlainHttpWithoutCallingTheFactory() throws IOException {
        RawBackend backend = RawBackend.answering(RawBackend.response(200, "plain"));
        backends.add(backend);
        RecordingSslSocketFactory factory = new RecordingSslSocketFactory();
        app.use("/api", Proxy.to(backend.url()).sslSocketFactory(factory));
        started = true;

        ClientResponse r = t.send("GET", "/api/x");

        assertEquals(200, r.status());
        assertEquals("plain", r.text());
        assertEquals("the backend received a plain HTTP request head", "GET /api/x HTTP/1.1",
                backend.onlyRequest().requestLine());
        assertEquals("the factory was never called", 0, factory.calls());
    }

    private static String escape(String s) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c >= 0x7F) {
                out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** An SSLSocketFactory that counts every call made on it and never makes a socket. */
    private static final class RecordingSslSocketFactory extends SSLSocketFactory {
        private final AtomicInteger calls = new AtomicInteger();

        int calls() {
            return calls.get();
        }

        private IOException used() {
            calls.incrementAndGet();
            return new IOException("the socket factory was used");
        }

        @Override
        public String[] getDefaultCipherSuites() {
            calls.incrementAndGet();
            return new String[0];
        }

        @Override
        public String[] getSupportedCipherSuites() {
            calls.incrementAndGet();
            return new String[0];
        }

        @Override
        public Socket createSocket() throws IOException {
            throw used();
        }

        @Override
        public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
            throw used();
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            throw used();
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
            throw used();
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            throw used();
        }

        @Override
        public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort)
                throws IOException {
            throw used();
        }
    }
}
