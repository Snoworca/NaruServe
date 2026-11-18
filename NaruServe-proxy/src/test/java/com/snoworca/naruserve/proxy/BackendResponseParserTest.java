package com.snoworca.naruserve.proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.TestApp;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.After;
import org.junit.Test;

/**
 * The backend response parser of {@link Proxy} (SEC-PROXY-004): every malformed backend response is a 502 whose
 * {@code onError} error is a {@link ProxyException} of kind {@code INVALID_RESPONSE}, and every malformed chunked body
 * is an {@code INVALID_RESPONSE} failure after the response started. Each malformed input has its own test whose
 * backend is a raw {@link ServerSocket} writing the exact bytes ({@link RawBackend}, or {@link ResetBackend} for a
 * connection reset).
 *
 * <p>SEC-PROXY-004 AC-11: every malformed input of AC-1 to AC-5, the 101 of AC-6, AC-8 and AC-10 below has its own
 * test method, and each backend is a raw {@code java.net.ServerSocket} in test scope writing the exact bytes.
 */
public class BackendResponseParserTest {

    private static final String OK_HEAD = "HTTP/1.1 200 OK\r\n";

    private final InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
    private final TestApp t = TestApp.create(adapter);
    private final RestApp app = t.app();
    private final List<Closeable> closeables = new ArrayList<>();
    private final List<Throwable> errors = Collections.synchronizedList(new ArrayList<Throwable>());
    private boolean started;

    @After
    public void tearDown() throws IOException {
        if (started) {
            app.shutdown(2, TimeUnit.SECONDS);
        }
        for (Closeable c : closeables) {
            c.close();
        }
    }

    // ---- helpers ----

    private RawBackend backend(String rawResponse) throws IOException {
        RawBackend b = RawBackend.answering(rawResponse);
        closeables.add(b);
        return b;
    }

    /** Mounts a proxy to {@code url} at {@code /api}, records what reaches onError, and starts the app. */
    private void mount(String url) {
        app.use("/api", Proxy.to(url));
        app.onError((error, req, res) -> errors.add(error));
        t.start();
        started = true;
    }

    private ClientResponse send(String method) {
        return adapter.send(ClientRequest.of(method, "/api/x"));
    }

    /** {@code GET /api/x} through a proxy to a backend answering {@code rawResponse}. */
    private ClientResponse get(String rawResponse) throws IOException {
        mount(backend(rawResponse).url());
        return send("GET");
    }

    /** {@code method /api/x} through a proxy to a backend answering {@code rawResponse}. */
    private ClientResponse request(String method, String rawResponse) throws IOException {
        mount(backend(rawResponse).url());
        return send(method);
    }

    /** The only error that reached onError, as a ProxyException. */
    private ProxyException onlyProxyError() {
        synchronized (errors) {
            assertEquals("errors that reached onError: " + errors, 1, errors.size());
            assertTrue("a ProxyException: " + errors.get(0), errors.get(0) instanceof ProxyException);
            return (ProxyException) errors.get(0);
        }
    }

    private void assertInvalidResponse(ClientResponse r) {
        assertEquals(502, r.status());
        assertSame(ProxyException.Kind.INVALID_RESPONSE, onlyProxyError().kind());
    }

    /** A GET through the proxy to a backend answering {@code rawResponse} gives 502 {@code INVALID_RESPONSE}. */
    private void assertGetIsInvalidResponse(String rawResponse) throws IOException {
        assertInvalidResponse(get(rawResponse));
    }

    /** A response with {@code headerLines} (each ending in CRLF) and the body "ok" framed by Content-Length. */
    private static String okWith(String headerLines) {
        return OK_HEAD + headerLines + "Content-Length: 2\r\n\r\nok";
    }

    private static String repeat(char c, int n) {
        char[] chars = new char[n];
        Arrays.fill(chars, c);
        return new String(chars);
    }

    private void assertNoError() {
        synchronized (errors) {
            assertTrue("errors that reached onError: " + errors, errors.isEmpty());
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // AC-1: the status line

    // SEC-PROXY-004 AC-1: a four-digit status (HTTP/1.1 2000 OK) is 502 INVALID_RESPONSE
    @Test(timeout = 30000)
    public void sec_PROXY_004_AC1_fourDigitStatusIsInvalidResponse() throws IOException {
        assertGetIsInvalidResponse("HTTP/1.1 2000 OK\r\nContent-Length: 0\r\n\r\n");
    }

    // SEC-PROXY-004 AC-1: a two-digit status (HTTP/1.1 20 OK) is 502 INVALID_RESPONSE
    @Test(timeout = 30000)
    public void sec_PROXY_004_AC1_twoDigitStatusIsInvalidResponse() throws IOException {
        assertGetIsInvalidResponse("HTTP/1.1 20 OK\r\nContent-Length: 0\r\n\r\n");
    }

    // SEC-PROXY-004 AC-1: a status that is not digits (HTTP/1.1 abc OK) is 502 INVALID_RESPONSE
    @Test(timeout = 30000)
    public void sec_PROXY_004_AC1_nonNumericStatusIsInvalidResponse() throws IOException {
        assertGetIsInvalidResponse("HTTP/1.1 abc OK\r\nContent-Length: 0\r\n\r\n");
    }

    // SEC-PROXY-004 AC-1: a status line without an HTTP version (FOO 200 OK) is 502 INVALID_RESPONSE
    @Test(timeout = 30000)
    public void sec_PROXY_004_AC1_statusLineWithoutHttpVersionIsInvalidResponse() throws IOException {
        assertGetIsInvalidResponse("FOO 200 OK\r\nContent-Length: 0\r\n\r\n");
    }

    // SEC-PROXY-004 AC-1: the status 600, above 599, is 502 INVALID_RESPONSE
    @Test(timeout = 30000)
    public void sec_PROXY_004_AC1_status600IsInvalidResponse() throws IOException {
        assertGetIsInvalidResponse("HTTP/1.1 600 X\r\nContent-Length: 0\r\n\r\n");
    }

    // SEC-PROXY-004 AC-1: the status 099, below 100, is 502 INVALID_RESPONSE
    @Test(timeout = 30000)
    public void sec_PROXY_004_AC1_status099IsInvalidResponse() throws IOException {
        assertGetIsInvalidResponse("HTTP/1.1 099 X\r\nContent-Length: 0\r\n\r\n");
    }

    // SEC-PROXY-004 AC-1: a version that is not HTTP/1.<digit> (HTTP/1.x) is 502 INVALID_RESPONSE
    @Test(timeout = 30000)
    public void sec_PROXY_004_AC1_versionWithoutMinorDigitIsInvalidResponse() throws IOException {
        assertGetIsInvalidResponse("HTTP/1.x 200 OK\r\nContent-Length: 0\r\n\r\n");
    }

    // SEC-PROXY-004 AC-1: the edges 599 and HTTP/1.0 are inside the rule and relayed
    @Test(timeout = 30000)
    public void sec_PROXY_004_AC1_status599OverHttp10IsRelayed() throws IOException {
        ClientResponse r = get("HTTP/1.0 599 X\r\nContent-Length: 2\r\n\r\nok");

        assertEquals(599, r.status());
        assertEquals("ok", r.text());
        assertNoError();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // AC-2: the 64 KiB bound of the status line and headers together

    /**
     * A 200 whose status line and header lines, CRLFs included and the blank line excluded, are {@code size} bytes:
     * the status line (17 bytes), {@code Content-Length: 2} (19 bytes) and one {@code X-Pad} line filling the rest.
     */
    private static String headOfSize(int size) {
        String fixed = OK_HEAD + "Content-Length: 2\r\n";
        int pad = size - fixed.length() - "X-Pad: \r\n".length();
        String head = fixed + "X-Pad: " + repeat('a', pad) + "\r\n";
        assertEquals(size, head.length());
        return head + "\r\nok";
    }

    // SEC-PROXY-004 AC-2: a status line and headers of 65537 bytes, one past 64 KiB, are 502 INVALID_RESPONSE
    @Test(timeout = 30000)
    public void sec_PROXY_004_AC2_headOneBytePast64KiBIsInvalidResponse() throws IOException {
        assertGetIsInvalidResponse(headOfSize(65537));
    }

    // SEC-PROXY-004 AC-2: many header lines, each short, that together pass 64 KiB are 502 INVALID_RESPONSE
    @Test(timeout = 30000)
    public void sec_PROXY_004_AC2_manyShortHeaderLinesTogetherPast64KiBAreInvalidResponse() throws IOException {
        StringBuilder lines = new StringBuilder();
        for (int i = 0; i < 70; i++) {
            lines.append("X-Pad-").append(i).append(": ").append(repeat('b', 1000)).append("\r\n");
        }
        assertGetIsInvalidResponse(okWith(lines.toString()));
    }

    // SEC-PROXY-004 AC-2: a status line and headers of exactly 65536 bytes are within the bound and relayed
    @Test(timeout = 30000)
    public void sec_PROXY_004_AC2_headOfExactly64KiBIsRelayed() throws IOException {
        ClientResponse r = get(headOfSize(65536));

        assertEquals(200, r.status());
        assertEquals("ok", r.text());
        assertNoError();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // AC-3: obs-fold, value bytes and the header name token

    // SEC-PROXY-004 AC-3: an obs-fold line starting with a space is 502 INVALID_RESPONSE
    @Test(timeout = 30000)
    public void sec_PROXY_004_AC3_obsFoldStartingWithSpaceIsInvalidResponse() throws IOException {
        assertGetIsInvalidResponse(okWith("X-A: 1\r\n  folded\r\n"));
    }

    // SEC-PROXY-004 AC-3: an obs-fold line starting with a horizontal tab is 502 INVALID_RESPONSE
    @Test(timeout = 30000)
    public void sec_PROXY_004_AC3_obsFoldStartingWithTabIsInvalidResponse() throws IOException {
        assertGetIsInvalidResponse(okWith("X-A: 1\r\n\tfolded\r\n"));
    }

    // SEC-PROXY-004 AC-3: an obs-fold line that looks like a header line (" X-B: 2") is 502 INVALID_RESPONSE
    @Test(timeout = 30000)
    public void sec_PROXY_004_AC3_obsFoldThatLooksLikeAHeaderIsInvalidResponse() throws IOException {
        assertGetIsInvalidResponse(okWith("X-A: 1\r\n X-B: 2\r\n"));
    }

    // SEC-PROXY-004 AC-3: a header value holding the byte 0x00 is 502 INVALID_RESPONSE
    @Test(timeout = 30000)
    public void sec_PROXY_004_AC3_valueWithNulIsInvalidResponse() throws IOException {
        assertGetIsInvalidResponse(okWith("X-A: a\u0000b\r\n"));
    }

    // SEC-PROXY-004 AC-3: a header value holding the byte 0x7F is 502 INVALID_RESPONSE
    @Test(timeout = 30000)
    public void sec_PROXY_004_AC3_valueWithDelIsInvalidResponse() throws IOException {
        assertGetIsInvalidResponse(okWith("X-A: a\u007Fb\r\n"));
    }

    // SEC-PROXY-004 AC-3: a header name that is not a token (X A: 1) is 502 INVALID_RESPONSE
    @Test(timeout = 30000)
    public void sec_PROXY_004_AC3_nameWithSpaceIsInvalidResponse() throws IOException {
        assertGetIsInvalidResponse(okWith("X A: 1\r\n"));
    }

    // SEC-PROXY-004 AC-3: whitespace between the header name and the colon (X-A : 1) is 502 INVALID_RESPONSE
    @Test(timeout = 30000)
    public void sec_PROXY_004_AC3_whitespaceBeforeTheColonIsInvalidResponse() throws IOException {
        assertGetIsInvalidResponse(okWith("X-A : 1\r\n"));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // AC-4: Content-Length on a final response that can carry a body

    // SEC-PROXY-004 AC-4: Content-Length together with Transfer-Encoding is 502 INVALID_RESPONSE
    @Test(timeout = 30000)
    public void sec_PROXY_004_AC4_contentLengthWithTransferEncodingIsInvalidResponse() throws IOException {
        assertGetIsInvalidResponse(OK_HEAD + "Content-Length: 5\r\nTransfer-Encoding: chunked\r\n\r\n"
                + "5\r\nhello\r\n0\r\n\r\n");
    }

    // SEC-PROXY-004 AC-4: the two lines Content-Length: 5 and Content-Length: 6 are 502 INVALID_RESPONSE
    @Test(timeout = 30000)
    public void sec_PROXY_004_AC4_twoDifferingContentLengthLinesAreInvalidResponse() throws IOException {
        assertGetIsInvalidResponse(OK_HEAD + "Content-Length: 5\r\nContent-Length: 6\r\n\r\nhello!");
    }

    // SEC-PROXY-004 AC-4: Content-Length: -1 is 502 INVALID_RESPONSE
    @Test(timeout = 30000)
    public void sec_PROXY_004_AC4_negativeContentLengthIsInvalidResponse() throws IOException {
        assertGetIsInvalidResponse(OK_HEAD + "Content-Length: -1\r\n\r\nhello");
    }

    // SEC-PROXY-004 AC-4: Content-Length: abc is 502 INVALID_RESPONSE
    @Test(timeout = 30000)
    public void sec_PROXY_004_AC4_nonNumericContentLengthIsInvalidResponse() throws IOException {
        assertGetIsInvalidResponse(OK_HEAD + "Content-Length: abc\r\n\r\nhello");
    }

    // SEC-PROXY-004 AC-4: a HEAD response is bodyless first, so Content-Length with Transfer-Encoding is no error
    @Test(timeout = 30000)
    public void sec_PROXY_004_AC4_headResponseIsBodylessBeforeTheFramingCheck() throws IOException {
        ClientResponse r = request("HEAD", OK_HEAD + "Content-Length: 10\r\nTransfer-Encoding: chunked\r\n\r\n");

        assertEquals(200, r.status());
        assertEquals(0, r.body().length);
        assertNoError();
    }

    // SEC-PROXY-004 AC-4: a 204 is bodyless first, so Content-Length: abc is no error
    @Test(timeout = 30000)
    public void sec_PROXY_004_AC4_noContentIsBodylessBeforeTheFramingCheck() throws IOException {
        ClientResponse r = get("HTTP/1.1 204 No Content\r\nContent-Length: abc\r\n\r\n");

        assertEquals(204, r.status());
        assertEquals(0, r.body().length);
        assertNoError();
    }

    // SEC-PROXY-004 AC-4: a 304 is bodyless first, so the two lines Content-Length: 5 and 6 are no error
    @Test(timeout = 30000)
    public void sec_PROXY_004_AC4_notModifiedIsBodylessBeforeTheFramingCheck() throws IOException {
        ClientResponse r = get("HTTP/1.1 304 Not Modified\r\nContent-Length: 5\r\nContent-Length: 6\r\n\r\n");

        assertEquals(304, r.status());
        assertEquals(0, r.body().length);
        assertNoError();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // AC-5: only a single chunked coding

    // SEC-PROXY-004 AC-5: Transfer-Encoding: gzip, chunked is 502 INVALID_RESPONSE
    @Test(timeout = 30000)
    public void sec_PROXY_004_AC5_gzipThenChunkedIsInvalidResponse() throws IOException {
        assertGetIsInvalidResponse(OK_HEAD + "Transfer-Encoding: gzip, chunked\r\n\r\n5\r\nhello\r\n0\r\n\r\n");
    }

    // SEC-PROXY-004 AC-5: Transfer-Encoding: identity is 502 INVALID_RESPONSE
    @Test(timeout = 30000)
    public void sec_PROXY_004_AC5_identityIsInvalidResponse() throws IOException {
        assertGetIsInvalidResponse(OK_HEAD + "Transfer-Encoding: identity\r\n\r\nhello");
    }

    // SEC-PROXY-004 AC-5: Transfer-Encoding: chunked;x=1 is 502 INVALID_RESPONSE
    @Test(timeout = 30000)
    public void sec_PROXY_004_AC5_chunkedWithParameterIsInvalidResponse() throws IOException {
        assertGetIsInvalidResponse(OK_HEAD + "Transfer-Encoding: chunked;x=1\r\n\r\n5\r\nhello\r\n0\r\n\r\n");
    }

    // SEC-PROXY-004 AC-5: an empty Transfer-Encoding value is 502 INVALID_RESPONSE
    @Test(timeout = 30000)
    public void sec_PROXY_004_AC5_emptyTransferEncodingIsInvalidResponse() throws IOException {
        assertGetIsInvalidResponse(OK_HEAD + "Transfer-Encoding: \r\n\r\nhello");
    }

    // SEC-PROXY-004 AC-5: the two lines Transfer-Encoding: chunked twice are 502 INVALID_RESPONSE
    @Test(timeout = 30000)
    public void sec_PROXY_004_AC5_chunkedTwiceIsInvalidResponse() throws IOException {
        assertGetIsInvalidResponse(OK_HEAD + "Transfer-Encoding: chunked\r\nTransfer-Encoding: chunked\r\n\r\n"
                + "5\r\nhello\r\n0\r\n\r\n");
    }

    // SEC-PROXY-004 AC-5: a HEAD response is bodyless first, so Transfer-Encoding: gzip is no error
    @Test(timeout = 30000)
    public void sec_PROXY_004_AC5_headResponseWithGzipCodingIsNoError() throws IOException {
        ClientResponse r = request("HEAD", OK_HEAD + "Transfer-Encoding: gzip\r\n\r\n");

        assertEquals(200, r.status());
        assertNoError();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // AC-6: interim responses

    // SEC-PROXY-004 AC-6: a 101 Switching Protocols is 502 INVALID_RESPONSE
    @Test(timeout = 30000)
    public void sec_PROXY_004_AC6_switchingProtocolsIsInvalidResponse() throws IOException {
        assertGetIsInvalidResponse("HTTP/1.1 101 Switching Protocols\r\nConnection: Upgrade\r\nUpgrade: websocket"
                + "\r\n\r\n");
    }

    // SEC-PROXY-004 AC-6: a 100 Continue before the final 200 is skipped, and none of its headers reach the client
    @Test(timeout = 30000)
    public void sec_PROXY_004_AC6_continueIsSkippedAndOnlyTheFinalResponseArrives() throws IOException {
        ClientResponse r = get("HTTP/1.1 100 Continue\r\nX-Interim: a\r\n\r\n" + okWith("X-Final: b\r\n"));

        assertEquals(200, r.status());
        assertEquals("ok", r.text());
        assertEquals("b", r.header("X-Final"));
        assertNull(r.header("X-Interim"));
        assertNoError();
    }

    // SEC-PROXY-004 AC-6: a 103 Early Hints before the final 200 is skipped, and its Link header does not leak
    @Test(timeout = 30000)
    public void sec_PROXY_004_AC6_earlyHintsIsSkippedAndOnlyTheFinalResponseArrives() throws IOException {
        ClientResponse r = get("HTTP/1.1 103 Early Hints\r\nLink: </s.css>; rel=preload\r\n\r\n"
                + "HTTP/1.1 100 Continue\r\n\r\n" + okWith(""));

        assertEquals(200, r.status());
        assertEquals("ok", r.text());
        assertNull(r.header("Link"));
        assertNoError();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // AC-7: list forms of the framing headers that are valid

    // SEC-PROXY-004 AC-7: Content-Length: 5, 5 is a 5-byte body relayed with Content-Length 5
    @Test(timeout = 30000)
    public void sec_PROXY_004_AC7_repeatedContentLengthListIsOneLength() throws IOException {
        ClientResponse r = get(OK_HEAD + "Content-Length: 5, 5\r\n\r\nhello");

        assertEquals(200, r.status());
        assertEquals("hello", r.text());
        assertEquals(5, r.contentLength());
        assertNoError();
    }

    // SEC-PROXY-004 AC-7: the lines Content-Length: 5 and Content-Length: 05 are a 5-byte body with Content-Length 5
    @Test(timeout = 30000)
    public void sec_PROXY_004_AC7_contentLengthLinesEqualInValueAreOneLength() throws IOException {
        ClientResponse r = get(OK_HEAD + "Content-Length: 5\r\nContent-Length: 05\r\n\r\nhello");

        assertEquals(200, r.status());
        assertEquals("hello", r.text());
        assertEquals(5, r.contentLength());
        assertNoError();
    }

    // SEC-PROXY-004 AC-7: Transfer-Encoding: CHUNKED is decoded as chunked
    @Test(timeout = 30000)
    public void sec_PROXY_004_AC7_upperCaseChunkedIsDecoded() throws IOException {
        ClientResponse r = get(OK_HEAD + "Transfer-Encoding: CHUNKED\r\n\r\n5\r\nhello\r\n0\r\n\r\n");

        assertEquals(200, r.status());
        assertFalse(r.abnormallyClosed());
        assertEquals("hello", r.text());
        assertNoError();
    }

    // SEC-PROXY-004 AC-7: Transfer-Encoding: chunked, (an empty last element) is decoded as chunked
    @Test(timeout = 30000)
    public void sec_PROXY_004_AC7_chunkedWithTrailingCommaIsDecoded() throws IOException {
        ClientResponse r = get(OK_HEAD + "Transfer-Encoding: chunked,\r\n\r\n5\r\nhello\r\n0\r\n\r\n");

        assertEquals(200, r.status());
        assertFalse(r.abnormallyClosed());
        assertEquals("hello", r.text());
        assertNoError();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // AC-8: a connection that ends before the header block is complete

    /** A backend that reads one request head, writes {@code prefix}, then closes the connection normally. */
    private RawBackend closingAfter(String prefix) throws IOException {
        byte[] bytes = prefix.getBytes(StandardCharsets.ISO_8859_1);
        RawBackend b = RawBackend.start((index, request, out) -> out.write(bytes));
        closeables.add(b);
        return b;
    }

    // SEC-PROXY-004 AC-8: a backend that closes before sending a status line gives 502 INVALID_RESPONSE
    @Test(timeout = 30000)
    public void sec_PROXY_004_AC8_closeBeforeTheStatusLineIsInvalidResponse() throws IOException {
        mount(closingAfter("").url());

        assertInvalidResponse(send("GET"));
    }

    // SEC-PROXY-004 AC-8: a backend that closes inside the status line gives 502 INVALID_RESPONSE
    @Test(timeout = 30000)
    public void sec_PROXY_004_AC8_closeInsideTheStatusLineIsInvalidResponse() throws IOException {
        mount(closingAfter("HTTP/1.1 20").url());

        assertInvalidResponse(send("GET"));
    }

    // SEC-PROXY-004 AC-8: a backend that closes after part of the header block gives 502 INVALID_RESPONSE
    @Test(timeout = 30000)
    public void sec_PROXY_004_AC8_closeInsideTheHeaderBlockIsInvalidResponse() throws IOException {
        mount(closingAfter(OK_HEAD + "X-A: 1\r\nContent-Len").url());

        assertInvalidResponse(send("GET"));
    }

    // SEC-PROXY-004 AC-8: a backend that resets the connection before a status line gives 502 INVALID_RESPONSE
    @Test(timeout = 30000)
    public void sec_PROXY_004_AC8_resetBeforeTheStatusLineIsInvalidResponse() throws IOException {
        ResetBackend b = new ResetBackend("");
        closeables.add(b);
        mount(b.url());

        assertInvalidResponse(send("GET"));
    }

    // SEC-PROXY-004 AC-8: a backend that resets the connection after part of the header block gives 502
    // INVALID_RESPONSE
    @Test(timeout = 30000)
    public void sec_PROXY_004_AC8_resetInsideTheHeaderBlockIsInvalidResponse() throws IOException {
        ResetBackend b = new ResetBackend(OK_HEAD + "X-A: 1\r\n");
        closeables.add(b);
        mount(b.url());

        assertInvalidResponse(send("GET"));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // AC-9: ISO-8859-1 header bytes

    /** {@code text} as ISO-8859-1 bytes, with each {@code ~} replaced by the byte 0xE9. */
    private static String latin1(String text) {
        return text.replace('~', 'é');
    }

    // SEC-PROXY-004 AC-9: a backend header value byte 0xE9 reaches the client as U+00E9, written back as one 0xE9
    @Test(timeout = 30000)
    public void sec_PROXY_004_AC9_latin1HeaderBytesReachTheClientAsTheSameSingleBytes() throws IOException {
        ClientResponse r = get(latin1(okWith("X-Name: caf~\r\nSet-Cookie: n=caf~\r\n"
                + "Content-Type: text/plain; name=caf~\r\n")));

        assertEquals(200, r.status());
        for (String name : new String[] {"X-Name", "Set-Cookie", "Content-Type"}) {
            String value = r.header(name);
            assertNotNull(name, value);
            assertTrue(name + ": " + value, value.endsWith("café"));
            byte[] written = value.getBytes(StandardCharsets.ISO_8859_1);
            assertEquals(name, (byte) 0xE9, written[written.length - 1]);
            assertEquals(name + " is one byte per char", value.length(), written.length);
        }
        assertNoError();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // AC-10: malformed chunked bodies fail after the response started

    private static final String CHUNKED_HEAD = OK_HEAD + "Transfer-Encoding: chunked\r\n\r\n";

    /** What {@link #relayedRead} saw: the bytes read before the failure and the failure, or null for none. */
    static final class RelayedRead {
        final byte[] bytes;
        final @Nullable IOException failure;

        RelayedRead(byte[] bytes, @Nullable IOException failure) {
            this.bytes = bytes;
            this.failure = failure;
        }
    }

    /**
     * Mounts the proxy behind a {@code Response} wrapper that takes the stream the proxy hands to {@code res.relay},
     * reads it to its end or its failure on the handler thread, and relays what it read; returns what it saw.
     */
    private RelayedRead relayedRead(String rawResponse) throws IOException {
        final Proxy proxy = Proxy.to(backend(rawResponse).url());
        final RelayedRead[] seen = new RelayedRead[1];
        app.use("/api", (req, res, next) -> {
            InvocationHandler h = (p, method, args) -> {
                if (method.getName().equals("relay")) {
                    InputStream in = (InputStream) args[0];
                    ByteArrayOutputStream read = new ByteArrayOutputStream();
                    IOException failure = null;
                    try {
                        byte[] buf = new byte[1024];
                        for (int n; (n = in.read(buf)) >= 0; ) {
                            read.write(buf, 0, n);
                        }
                    } catch (IOException e) {
                        failure = e;
                    } finally {
                        in.close();
                    }
                    seen[0] = new RelayedRead(read.toByteArray(), failure);
                    res.relay(new ByteArrayInputStream(read.toByteArray()), -1);
                    return null;
                }
                try {
                    return method.invoke(res, args);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            };
            com.snoworca.naruserve.Response wrapped = (com.snoworca.naruserve.Response) java.lang.reflect.Proxy
                    .newProxyInstance(getClass().getClassLoader(),
                            new Class<?>[] {com.snoworca.naruserve.Response.class}, h);
            proxy.handle(req, wrapped, next);
        });
        app.onError((error, req, res) -> errors.add(error));
        t.start();
        started = true;
        ClientResponse r = send("GET");
        assertEquals(200, r.status());
        assertNotNull("the proxy handed a body to res.relay", seen[0]);
        return seen[0];
    }

    /** The failure of {@code read} is an IOException whose cause is a ProxyException of kind INVALID_RESPONSE. */
    private static void assertInvalidResponseFailure(RelayedRead read) {
        assertNotNull("the relayed stream failed", read.failure);
        Throwable cause = read.failure.getCause();
        assertTrue("caused by a ProxyException: " + read.failure, cause instanceof ProxyException);
        assertSame(ProxyException.Kind.INVALID_RESPONSE, ((ProxyException) cause).kind());
    }

    /** The client got the 200 and the data before the break, and its connection was aborted. */
    private void assertAbortedAfter(String rawResponse, String bodyBeforeTheBreak) throws IOException {
        ClientResponse r = get(rawResponse);

        assertEquals(200, r.status());
        assertTrue("the client connection was aborted", r.abnormallyClosed());
        assertEquals(bodyBeforeTheBreak, r.text());
    }

    private static String chunkSizeLineOfLength(int length) {
        String size = "5;e=";
        return size + repeat('x', length - size.length());
    }

    private static final String LONG_CHUNK_SIZE_LINE = CHUNKED_HEAD + "5\r\nhello\r\n"
            + chunkSizeLineOfLength(4097) + "\r\nworld\r\n0\r\n\r\n";

    // SEC-PROXY-004 AC-10: a chunk-size line of 4097 bytes with its extensions aborts the client after the response
    // started, with the data before it
    @Test(timeout = 30000)
    public void sec_PROXY_004_AC10_chunkSizeLineLongerThan4KiBAbortsTheClient() throws IOException {
        assertAbortedAfter(LONG_CHUNK_SIZE_LINE, "hello");
    }

    // SEC-PROXY-004 AC-10: a chunk-size line longer than 4 KiB is an INVALID_RESPONSE failure of the relayed stream
    @Test(timeout = 30000)
    public void sec_PROXY_004_AC10_chunkSizeLineLongerThan4KiBIsAnInvalidResponseFailure() throws IOException {
        RelayedRead read = relayedRead(LONG_CHUNK_SIZE_LINE);

        assertInvalidResponseFailure(read);
        assertEquals("hello", new String(read.bytes, StandardCharsets.ISO_8859_1));
    }

    // SEC-PROXY-004 AC-10: a chunk-size line of exactly 4096 bytes with its extensions is decoded
    @Test(timeout = 30000)
    public void sec_PROXY_004_AC10_chunkSizeLineOfExactly4KiBIsDecoded() throws IOException {
        ClientResponse r = get(CHUNKED_HEAD + chunkSizeLineOfLength(4096) + "\r\nhello\r\n0\r\n\r\n");

        assertEquals(200, r.status());
        assertFalse(r.abnormallyClosed());
        assertEquals("hello", r.text());
    }

    private static final String NON_HEX_CHUNK_SIZE = CHUNKED_HEAD + "5\r\nhello\r\nzz\r\nworld\r\n0\r\n\r\n";

    // SEC-PROXY-004 AC-10: a chunk size that is not hexadecimal aborts the client after the response started
    @Test(timeout = 30000)
    public void sec_PROXY_004_AC10_nonHexChunkSizeAbortsTheClient() throws IOException {
        assertAbortedAfter(NON_HEX_CHUNK_SIZE, "hello");
    }

    // SEC-PROXY-004 AC-10: a chunk size that is not hexadecimal is an INVALID_RESPONSE failure of the relayed stream
    @Test(timeout = 30000)
    public void sec_PROXY_004_AC10_nonHexChunkSizeIsAnInvalidResponseFailure() throws IOException {
        RelayedRead read = relayedRead(NON_HEX_CHUNK_SIZE);

        assertInvalidResponseFailure(read);
        assertEquals("hello", new String(read.bytes, StandardCharsets.ISO_8859_1));
    }

    // SEC-PROXY-004 AC-10: a chunk size with a leading space is not hexadecimal and is an INVALID_RESPONSE failure
    @Test(timeout = 30000)
    public void sec_PROXY_004_AC10_chunkSizeWithLeadingSpaceIsAnInvalidResponseFailure() throws IOException {
        RelayedRead read = relayedRead(CHUNKED_HEAD + "5\r\nhello\r\n 5\r\nworld\r\n0\r\n\r\n");

        assertInvalidResponseFailure(read);
        assertEquals("hello", new String(read.bytes, StandardCharsets.ISO_8859_1));
    }

    /**
     * A chunked response with the body "hello" whose trailer section, its CRLFs included and the closing blank line
     * excluded, is {@code size} bytes of {@code X-Tn: ...} lines.
     */
    private static String withTrailerOfSize(int size) {
        StringBuilder trailer = new StringBuilder();
        int n = 0;
        while (size - trailer.length() > 1100) {
            trailer.append("X-T").append(n++).append(": ").append(repeat('t', 1000)).append("\r\n");
        }
        String last = "X-T" + n + ": ";
        int pad = size - trailer.length() - last.length() - 2;
        trailer.append(last).append(repeat('t', pad)).append("\r\n");
        assertEquals(size, trailer.length());
        return CHUNKED_HEAD + "5\r\nhello\r\n0\r\n" + trailer + "\r\n";
    }

    // SEC-PROXY-004 AC-10: a trailer section of 65537 bytes, past 64 KiB, aborts the client after the response
    // started
    @Test(timeout = 30000)
    public void sec_PROXY_004_AC10_trailerPast64KiBAbortsTheClient() throws IOException {
        ClientResponse r = get(withTrailerOfSize(65537));

        assertEquals(200, r.status());
        assertTrue("the client connection was aborted", r.abnormallyClosed());
    }

    // SEC-PROXY-004 AC-10: a trailer section past 64 KiB is an INVALID_RESPONSE failure of the relayed stream
    @Test(timeout = 30000)
    public void sec_PROXY_004_AC10_trailerPast64KiBIsAnInvalidResponseFailure() throws IOException {
        RelayedRead read = relayedRead(withTrailerOfSize(65537));

        assertInvalidResponseFailure(read);
    }

    // SEC-PROXY-004 AC-10: a trailer section of exactly 64 KiB is read and dropped and the decoded body arrives
    @Test(timeout = 30000)
    public void sec_PROXY_004_AC10_trailerOfExactly64KiBIsDroppedAndTheBodyArrives() throws IOException {
        ClientResponse r = get(withTrailerOfSize(65536));

        assertEquals(200, r.status());
        assertFalse(r.abnormallyClosed());
        assertEquals("hello", r.text());
        assertNull(r.header("X-T0"));
    }

    // SEC-PROXY-004 AC-10: a trailer section of many short lines within 64 KiB is read and dropped
    @Test(timeout = 30000)
    public void sec_PROXY_004_AC10_trailerOfManyShortLinesWithin64KiBIsDropped() throws IOException {
        StringBuilder trailer = new StringBuilder();
        for (int i = 0; i < 2000; i++) {
            trailer.append("T").append(i).append(": v\r\n");
        }
        ClientResponse r = get(CHUNKED_HEAD + "5\r\nhello\r\n0\r\n" + trailer + "\r\n");

        assertEquals(200, r.status());
        assertFalse(r.abnormallyClosed());
        assertEquals("hello", r.text());
    }

    // ---------------------------------------------------------------------------------------------------------------

    /**
     * A raw {@link ServerSocket} backend that accepts one connection, reads the request head, writes {@code prefix}
     * and resets the connection (SO_LINGER 0, then close), so the proxy sees an RST.
     */
    static final class ResetBackend implements Closeable {
        private final ServerSocket server;
        private final Thread acceptor;

        ResetBackend(String prefix) throws IOException {
            byte[] bytes = prefix.getBytes(StandardCharsets.ISO_8859_1);
            server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
            acceptor = new Thread(() -> {
                try (Socket s = server.accept()) {
                    s.setSoTimeout(10_000);
                    readHead(s.getInputStream());
                    OutputStream out = s.getOutputStream();
                    out.write(bytes);
                    out.flush();
                    s.setSoLinger(true, 0);
                } catch (IOException ignored) {
                    // the test sees what the proxy made of it
                }
            }, "reset-backend");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getLocalPort();
        }

        private static void readHead(InputStream in) throws IOException {
            int matched = 0;
            byte[] end = {'\r', '\n', '\r', '\n'};
            while (matched < end.length) {
                int b = in.read();
                if (b < 0) {
                    return;
                }
                matched = b == end[matched] ? matched + 1 : (b == '\r' ? 1 : 0);
            }
        }

        @Override
        public void close() throws IOException {
            server.close();
            try {
                acceptor.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
