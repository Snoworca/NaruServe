package com.snoworca.naruserve.proxy;

import com.snoworca.naruserve.HttpException;
import org.jspecify.annotations.Nullable;

/**
 * A proxy failure reaching the error flow, as the {@code err} of {@code http-proxy-middleware}'s error event; compare
 * {@link #kind()} like its {@code err.code}. The status follows the kind: 502 for {@link Kind#CONNECT_FAILED} and
 * {@link Kind#INVALID_RESPONSE}, 504 for the timeouts, 503 for {@link Kind#CONCURRENCY_LIMIT}. The message is always
 * {@code null}, so the default error response never echoes the backend address; the underlying failure, if any, is
 * {@link #getCause()}. The default error page names the causes by class only (FR-PROXY-015 AC-4).
 */
// @req IR-PROXY-002
public final class ProxyException extends HttpException {

    private static final long serialVersionUID = 1L;

    /** What failed while forwarding to the backend. */
    public enum Kind {
        /** The backend connection could not be opened. */
        CONNECT_FAILED,
        /** Opening the backend connection took longer than the connect timeout. */
        CONNECT_TIMEOUT,
        /** Writing the request to the backend stalled longer than the write timeout. */
        WRITE_TIMEOUT,
        /** Reading the response from the backend stalled longer than the read timeout. */
        READ_TIMEOUT,
        /** The whole exchange with the backend took longer than the total timeout. */
        TOTAL_TIMEOUT,
        /** The backend answered with something that is not a valid HTTP response. */
        INVALID_RESPONSE,
        /** The proxy already holds its maximum number of concurrent backend exchanges. */
        CONCURRENCY_LIMIT
    }

    private final Kind kind;

    // @req IR-PROXY-002
    public ProxyException(Kind kind, @Nullable Throwable cause) {
        super(statusOf(kind), null, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    // @req IR-PROXY-002
    private static int statusOf(Kind kind) {
        switch (kind) {
            case CONNECT_FAILED:
            case INVALID_RESPONSE:
                return 502;
            case CONCURRENCY_LIMIT:
                return 503;
            case CONNECT_TIMEOUT:
            case WRITE_TIMEOUT:
            case READ_TIMEOUT:
            case TOTAL_TIMEOUT:
                return 504;
            default:
                throw new AssertionError(kind);
        }
    }
}
