package com.snoworca.naruserve.spi;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One request/response exchange, implemented by the adapter. Request values are undecoded; the core decodes once.
 * The response is started exactly once, by {@link #commit}, {@link #sendFile}, {@link #upgrade},
 * {@link #eventStream} or {@link #webSocket}; a second start throws {@link IllegalStateException}. The exchange
 * outlives {@link ExchangeHandler#handle} until the core calls {@link #finish} or {@link #abort} (or the adapter's
 * stop deadline force-closes it).
 */
// @req IR-HTTP-003
// @req IR-HTTP-007
// @req IR-WS-002
public interface HttpExchange {

    /** The request-line method token, as received. */
    // @req IR-HTTP-003
    String methodToken();

    /** The raw request-target path, undecoded. */
    // @req IR-HTTP-003
    String rawPath();

    /** The raw query after '?', undecoded; null when the target has no '?'. */
    // @req IR-HTTP-003
    @Nullable String rawQuery();

    /** The request-line HTTP version, such as {@code HTTP/1.1}. */
    // @req IR-HTTP-003
    String httpVersion();

    /** Request headers in received order, duplicates kept. */
    // @req IR-HTTP-003
    List<Map.Entry<String, String>> headers();

    /**
     * The request body, ending (-1) where the message body ends by its Content-Length or chunked framing; it never
     * reads into the next request on the connection.
     */
    // @req IR-HTTP-003
    InputStream requestBody();

    // @req IR-HTTP-003
    String remoteAddress();

    // @req IR-HTTP-003
    boolean isSecure();

    /**
     * Sends status and headers and returns the body stream. {@code headers} are sent as given: they already carry
     * Content-Length when the core knows it (a HEAD response carries the length the GET body would have had), and
     * the adapter never adds, removes or changes Content-Length. {@code contentLength} is the number of body bytes
     * the core will write to the returned stream: 0 for a bodyless response (204, 304, HEAD), or -1 when unknown,
     * which the adapter frames itself (chunked, or close on HTTP/1.0) and is the only case where it may add a
     * framing header. Header names and values are ISO-8859-1: the core guarantees every char is at most U+00FF
     * and never CR or LF, so the adapter writes each char as one byte.
     */
    // @req IR-HTTP-003
    OutputStream commit(int status, List<Map.Entry<String, String>> headers, long contentLength) throws IOException;

    /** Ends the exchange normally after the body. */
    // @req IR-HTTP-003
    void finish() throws IOException;

    /**
     * Closes the connection abnormally. Idempotent and thread-safe: it may run on any thread, also while another
     * thread is in {@link #commit}, a body write or {@link #finish}, which then fail with an {@link IOException}.
     */
    // @req IR-HTTP-003
    void abort();

    /** Sends {@code length} bytes of {@code file} from {@code offset} as the whole response body. */
    // @req IR-HTTP-007
    void sendFile(int status, List<Map.Entry<String, String>> headers, File file, long offset, long length)
            throws IOException;

    /** Writes a 101 response with {@code headers}, then calls {@code handler} with the exchange's raw streams. */
    // @req IR-HTTP-007
    void upgrade(List<Map.Entry<String, String>> headers, UpgradeHandler handler) throws IOException;

    /** Sends status and headers, then runs {@code drain} on a thread the adapter chooses; returns at once. */
    // @req IR-HTTP-007
    void eventStream(int status, List<Map.Entry<String, String>> headers, StreamDrain drain) throws IOException;

    /**
     * Retains ownership of the request input/context after response completion until the returned
     * action releases it. Each returned action must be thread-safe and idempotent; finishing the
     * wire response and releasing the underlying request are separate events.
     *
     * <p>The default is a no-op for source compatibility with existing custom exchanges. Built-in
     * adapters override it to keep unread input/container state available to retained asynchronous
     * work, and separately reject retention after terminal cancellation or request recycling.
     */
    // @req FR-APP-023 AC-8
    default Runnable retainRequest() {
        return () -> { };
    }

    /**
     * Registers notification of an abnormal transport termination the adapter detects. Built-in
     * adapters notify each registration once, including registration after an abort. Successful
     * {@link #finish()} does not notify this hook. Idle peer closure need not be detected before IO.
     *
     * <p>The default validates the listener and otherwise does nothing, preserving existing custom
     * exchange implementations. Callbacks must run outside adapter state locks; their failures must
     * not prevent transport cleanup or other listeners.
     */
    // @req IR-HTTP-009 AC-1 AC-2 AC-3 AC-5
    default void onAbort(Runnable listener) {
        java.util.Objects.requireNonNull(listener, "listener");
    }

    /**
     * Optional status of a response the adapter generated independently of the core; {@code 0} means unknown.
     * Publish a side-effect-free snapshot before terminal notifications. Core-owned responses can keep the
     * default: access logging falls back to the core's status. No container access after recycling is needed.
     */
    // @req IR-HTTP-009 AC-8; FR-APP-019 AC-3
    default int responseStatus() { return 0; }

    /**
     * Optional successfully written body-byte count for an adapter-owned response, excluding headers;
     * {@code -1} means unknown. When unknown, access logging retains its core counter. Implementations should
     * expose a side-effect-free snapshot before notifying termination, without reading a recycled container.
     */
    // @req IR-HTTP-009 AC-8; FR-APP-019 AC-5
    default long responseBytesWritten() { return -1; }

    /**
     * Completes a WebSocket handshake with {@code settings}, calls {@code endpoint} on the adapter's read thread and
     * returns only after the connection closed. A refusal before 101 throws a NaruServe {@code HttpException} and
     * leaves the exchange unstarted; the exchange is started once 101 is written. The default throws
     * {@link UnsupportedOperationException}: an adapter without WebSocket support does not override it.
     */
    // @req IR-WS-002
    default void webSocket(WebSocketSettings settings, WebSocketEndpoint endpoint) throws IOException {
        throw new UnsupportedOperationException("webSocket is not supported by this adapter");
    }
}
