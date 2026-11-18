package com.snoworca.naruserve.proxy;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import javax.net.ssl.SSLSocketFactory;
import org.jspecify.annotations.Nullable;

/**
 * Opens the connection to the backend: a plain {@link Socket} connected within the connect timeout, layered with TLS
 * by {@link TlsConnector} for an {@code https} target (SEC-PROXY-005). The caller owns the returned socket; on any
 * failure the connection is closed here.
 */
// @req SEC-PROXY-005
// @req FR-PROXY-017
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
     * A socket connected to {@code target}: plain for {@code http}, TLS-layered and verified for {@code https}. The
     * plain socket is given to {@code exchange} before its connect, so a firing write or total deadline closes it
     * (FR-PROXY-017). The TLS handshake's own total deadline counts from this call. A connect that outlasts
     * {@code connectTimeoutMillis} is a {@link ProxyException} {@code CONNECT_TIMEOUT}; any other failure of the plain
     * connect is the {@link IOException} itself; a TLS failure is a {@link ProxyException} (see
     * {@link TlsConnector#layer}).
     */
    // @req SEC-PROXY-005
    // @req FR-PROXY-017
    static Socket open(TargetUrl target, int connectTimeoutMillis, long totalTimeoutNanos,
            @Nullable SSLSocketFactory sslSocketFactory, ProxyExchange exchange) throws IOException {
        long deadlineNanos = System.nanoTime() + totalTimeoutNanos;
        Socket plain = plainSockets.create();
        boolean opened = false;
        try {
            exchange.plainSocket(plain);
            InetSocketAddress address = new InetSocketAddress(resolver.resolve(target.host), target.port);
            try {
                plain.connect(address, connectTimeoutMillis);
            } catch (SocketTimeoutException e) {
                throw new ProxyException(ProxyException.Kind.CONNECT_TIMEOUT, e);
            }
            Socket socket = "https".equals(target.scheme)
                    ? TlsConnector.layer(plain, target.host, target.port, connectTimeoutMillis, deadlineNanos,
                            sslSocketFactory)
                    : plain;
            opened = true;
            return socket;
        } finally {
            if (!opened) {
                try {
                    plain.close();
                } catch (IOException ignored) {
                    // the connection is dropped either way
                }
            }
        }
    }
}
