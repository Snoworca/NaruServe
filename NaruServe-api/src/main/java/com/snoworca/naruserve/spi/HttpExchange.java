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
 * The response is started exactly once, by {@link #commit}, {@link #sendFile}, {@link #upgrade} or
 * {@link #eventStream}; a second start throws {@link IllegalStateException}.
 */
// @req IR-HTTP-003
// @req IR-HTTP-004
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

    // @req IR-HTTP-003
    InputStream requestBody();

    // @req IR-HTTP-003
    String remoteAddress();

    // @req IR-HTTP-003
    boolean isSecure();

    /** Sends status and headers and returns the body stream; {@code contentLength} -1 means unknown. */
    // @req IR-HTTP-003
    OutputStream commit(int status, List<Map.Entry<String, String>> headers, long contentLength) throws IOException;

    /** Ends the exchange normally after the body. */
    // @req IR-HTTP-003
    void finish() throws IOException;

    /** Closes the connection abnormally. Idempotent. */
    // @req IR-HTTP-003
    void abort();

    /** Sends {@code length} bytes of {@code file} from {@code offset} as the whole response body. */
    // @req IR-HTTP-004
    void sendFile(int status, List<Map.Entry<String, String>> headers, File file, long offset, long length)
            throws IOException;

    /** Writes a 101 response with {@code headers}, then calls {@code handler} with the exchange's raw streams. */
    // @req IR-HTTP-004
    void upgrade(List<Map.Entry<String, String>> headers, UpgradeHandler handler) throws IOException;

    /** Sends status and headers, then runs {@code drain} on a thread the adapter chooses; returns at once. */
    // @req IR-HTTP-004
    void eventStream(int status, List<Map.Entry<String, String>> headers, StreamDrain drain) throws IOException;
}
