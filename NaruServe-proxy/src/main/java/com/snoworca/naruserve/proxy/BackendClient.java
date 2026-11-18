package com.snoworca.naruserve.proxy;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import javax.net.ssl.SSLSocketFactory;
import org.jspecify.annotations.Nullable;

/**
 * Opens the connection to the backend: a plain {@link Socket} connected within the connect timeout, layered with TLS
 * by {@link TlsConnector} for an {@code https} target (SEC-PROXY-005). The caller owns the returned socket; on any
 * failure the connection is closed here.
 */
// @req SEC-PROXY-005
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

    private BackendClient() {
    }

    /**
     * A socket connected to {@code target}: plain for {@code http}, TLS-layered and verified for {@code https}. The
     * total timeout counts from this call. A failure of the plain connect is the {@link IOException} itself; a TLS
     * failure is a {@link ProxyException} (see {@link TlsConnector#layer}).
     */
    static Socket open(TargetUrl target, int connectTimeoutMillis, long totalTimeoutNanos,
            @Nullable SSLSocketFactory sslSocketFactory) throws IOException {
        long deadlineNanos = System.nanoTime() + totalTimeoutNanos;
        Socket plain = new Socket();
        boolean opened = false;
        try {
            plain.connect(new InetSocketAddress(resolver.resolve(target.host), target.port), connectTimeoutMillis);
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
