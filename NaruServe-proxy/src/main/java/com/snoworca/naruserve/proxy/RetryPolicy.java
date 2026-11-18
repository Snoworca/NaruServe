package com.snoworca.naruserve.proxy;

import java.io.IOException;
import java.net.SocketTimeoutException;
import javax.net.ssl.SSLException;

/**
 * The retry decisions of the proxy (FR-PROXY-018): which failures of an attempt may be followed by another attempt on
 * a new connection, and whether the wait before it still ends inside the total timeout. Whether any retry is left is
 * the caller's count.
 */
// @req FR-PROXY-018
final class RetryPolicy {

    private RetryPolicy() {
    }

    /**
     * Whether {@code failure}, a failure to open the plain backend connection, is retried: a {@code CONNECT_FAILED}
     * caused by a TCP-level failure such as {@link java.net.ConnectException},
     * {@link java.net.UnknownHostException} or {@link java.net.NoRouteToHostException} (design D-064). A connect
     * timeout is not, since a retry would hold the worker for another full timeout, nor is a TLS failure, which
     * repeats identically; neither is any other kind.
     */
    // @req FR-PROXY-018
    static boolean retriesConnectFailure(ProxyException failure) {
        if (failure.kind() != ProxyException.Kind.CONNECT_FAILED) {
            return false;
        }
        Throwable cause = failure.getCause();
        return cause instanceof IOException && !(cause instanceof SocketTimeoutException)
                && !(cause instanceof SSLException);
    }

    /**
     * Whether a backend response with {@code status} is retried: a 502 or 503, only when {@code bodyless}, the request
     * having sent zero body bytes to the backend, and only for {@code GET}, {@code HEAD} and {@code OPTIONS} unless
     * {@code unsafeMethods} allows every method. The backend may have processed the request, so a request with a
     * body, whose streamed body cannot be sent again, is never retried.
     */
    // @req FR-PROXY-018
    static boolean retriesStatus(int status, String method, boolean bodyless, boolean unsafeMethods) {
        if ((status != 502 && status != 503) || !bodyless) {
            return false;
        }
        return unsafeMethods || "GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method);
    }

    /**
     * Whether a wait of {@code delayNanos} starting at {@code nowNanos} ends no later than the total deadline of a
     * request that started at {@code startNanos} with {@code totalTimeoutNanos}; computed from elapsed time so that a
     * large timeout or delay does not overflow.
     */
    // @req FR-PROXY-018
    static boolean waitFits(long startNanos, long nowNanos, long totalTimeoutNanos, long delayNanos) {
        long remaining = totalTimeoutNanos - (nowNanos - startNanos);
        return delayNanos <= remaining;
    }
}
