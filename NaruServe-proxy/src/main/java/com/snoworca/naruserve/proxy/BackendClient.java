package com.snoworca.naruserve.proxy;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import javax.net.ssl.SSLSocketFactory;
import org.jspecify.annotations.Nullable;

/**
 * Opens one connection attempt to the backend: a plain {@link Socket} connected within the connect timeout
 * ({@link #connect}), then layered with TLS by {@link TlsConnector} for an {@code https} target ({@link #secure},
 * SEC-PROXY-005). The two steps are apart so that only a failure of the first may be retried (FR-PROXY-018). The
 * caller owns the returned socket; on any failure the connection is closed here.
 */
// @req SEC-PROXY-005
// @req FR-PROXY-018
// @req FR-PROXY-017
// @req FR-PROXY-015
final class BackendClient {

    /** Resolves the target host to the address the plain socket connects to. */
    interface Resolver {
        InetAddress resolve(String host) throws IOException;
    }

    /** The system resolver, {@link InetAddress#getByName}. */
    static final Resolver SYSTEM_RESOLVER = new Resolver() {
        @Override
        public InetAddress resolve(String host) throws IOException {
            return InetAddress.getByName(host);
        }
    };

    /**
     * Package-private test seam: the resolver of every backend connection, so a test can reach a loopback backend
     * through a host name with a dot, which the JDK sends as SNI (SEC-PROXY-005 AC-3). Tests reset it.
     */
    static volatile Resolver resolver = SYSTEM_RESOLVER;

    /** Creates the unconnected plain socket of a backend connection. */
    interface PlainSockets {
        Socket create();
    }

    /** {@code new Socket()}. */
    static final PlainSockets SYSTEM_SOCKETS = new PlainSockets() {
        @Override
        public Socket create() {
            return new Socket();
        }
    };

    /**
     * Package-private test seam: the factory of every plain backend socket, so a test can record the
     * {@code Socket.connect} call and make it throw (FR-PROXY-017 AC-2). Tests reset it.
     */
    static volatile PlainSockets plainSockets = SYSTEM_SOCKETS;

    private BackendClient() {
    }

    /**
     * The plain socket of one connection attempt, connected to {@code target} within {@code connectTimeoutMillis}. The
     * socket is given to {@code exchange} before its connect, so a firing write or total deadline closes it
     * (FR-PROXY-017). A connect that outlasts the timeout is a {@link ProxyException} {@code CONNECT_TIMEOUT}; any other
     * failure of the name resolution or the plain connect, such as a {@link java.net.ConnectException} or an
     * {@link java.net.UnknownHostException}, is a {@link ProxyException} {@code CONNECT_FAILED} with that failure as its
     * cause, the TCP-level connect failure a retry may follow (FR-PROXY-018). On failure the socket is closed here.
     */
    // @req FR-PROXY-017
    // @req FR-PROXY-015
    // @req FR-PROXY-018
    static Socket connect(TargetUrl target, int connectTimeoutMillis, ProxyExchange exchange) {
        Socket plain = plainSockets.create();
        boolean connected = false;
        try {
            exchange.plainSocket(plain);
            InetSocketAddress address = new InetSocketAddress(resolver.resolve(target.host), target.port);
            plain.connect(address, connectTimeoutMillis);
            connected = true;
            return plain;
        } catch (SocketTimeoutException e) {
            throw new ProxyException(ProxyException.Kind.CONNECT_TIMEOUT, e);
        } catch (IOException e) {
            // A refused connection, an unresolvable host or an unreachable network (FR-PROXY-015).
            throw new ProxyException(ProxyException.Kind.CONNECT_FAILED, e);
        } finally {
            if (!connected) {
                closeQuietly(plain);
            }
        }
    }

    /**
     * The connection over the connected {@code plain} socket: {@code plain} itself for {@code http}, TLS-layered and
     * verified for {@code https}, whose handshake runs under the total-timeout reservation of {@code exchange}, the
     * request's one total deadline (FR-PROXY-017). A TLS failure is a {@link ProxyException} (see
     * {@link TlsConnector#layer}) that is never retried (FR-PROXY-018). The caller owns the returned socket; on
     * failure {@code plain} is closed here.
     */
    // @req SEC-PROXY-005
    // @req FR-PROXY-017
    // @req FR-PROXY-018
    static Socket secure(Socket plain, TargetUrl target, int connectTimeoutMillis, ProxyExchange exchange,
            @Nullable SSLSocketFactory sslSocketFactory) {
        if (!"https".equals(target.scheme)) {
            return plain;
        }
        boolean layered = false;
        try {
            Socket socket = TlsConnector.layer(plain, target.host, target.port, connectTimeoutMillis, exchange,
                    sslSocketFactory);
            layered = true;
            return socket;
        } finally {
            if (!layered) {
                closeQuietly(plain);
            }
        }
    }

    /**
     * Ends a backend connection, the one rule of every proxy-side close of it (SEC-PROXY-005, FR-PROXY-019): closes
     * {@code plain}, the plain socket, first and then {@code connection}, the {@code SSLSocket} layered over it for
     * https, both quietly; for http, where {@code connection} is {@code plain}, and for a null {@code connection}, the
     * plain socket is closed once. Closing the plain socket first ends the connection without a TLS duplex close, which
     * under TLS 1.3 sends {@code close_notify} and then reads until the backend answers or the read timeout passes
     * (FR-PROXY-020, FR-PROXY-017), and makes every thread blocked in I/O on it fail at once, so it releases what it
     * holds. The {@code SSLSocket} is still closed so that no TLS socket is left for its finalizer: on Android it owns
     * native SSL state that only its close frees at once, and StrictMode reports one left to the finalizer as a leak.
     * With its transport closed that close can neither send nor read: on the JDK it fails at its first transport call,
     * {@code getSoLinger}. It runs only while no read of the connection is under way: on the request thread before
     * {@code res.relay}, on the copy thread once its read returned, or on the thread that aborts the response while no
     * copy is running, where the response closes the relayed input itself. The {@code res.onAbort} listener, which may
     * run while a read is blocked, closes only the plain socket ({@link ProxyExchange#releaseOnAbortThread}).
     */
    // @req SEC-PROXY-005
    // @req FR-PROXY-019
    // @req FR-PROXY-020
    static void closeConnection(Socket plain, @Nullable Socket connection) {
        closeQuietly(plain);
        if (connection != null && connection != plain) {
            closeQuietly(connection);
        }
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
            // the connection is dropped either way
        }
    }
}
