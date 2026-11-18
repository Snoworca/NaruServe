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
     * verified for {@code https}, whose handshake ends by {@code deadlineNanos}, the request's total deadline
     * (FR-PROXY-017). A TLS failure is a {@link ProxyException} (see {@link TlsConnector#layer}) that is never retried
     * (FR-PROXY-018). The caller owns the returned socket; on failure {@code plain} is closed here.
     */
    // @req SEC-PROXY-005
    // @req FR-PROXY-017
    // @req FR-PROXY-018
    static Socket secure(Socket plain, TargetUrl target, int connectTimeoutMillis, long deadlineNanos,
            @Nullable SSLSocketFactory sslSocketFactory) {
        if (!"https".equals(target.scheme)) {
            return plain;
        }
        boolean layered = false;
        try {
            Socket socket = TlsConnector.layer(plain, target.host, target.port, connectTimeoutMillis, deadlineNanos,
                    sslSocketFactory);
            layered = true;
            return socket;
        } finally {
            if (!layered) {
                closeQuietly(plain);
            }
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
