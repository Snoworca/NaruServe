package com.snoworca.naruserve.proxy;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import org.jspecify.annotations.Nullable;

/**
 * Layers TLS over a connected plain socket for an {@code https} target (SEC-PROXY-005): the factory's
 * {@code createSocket(plain, host, port, true)}, so SNI carries the target host; the handshake with {@code SO_TIMEOUT}
 * set to the connect timeout and under the total-timeout watchdog; the certificate chain checked by the factory's
 * trust managers and the host name checked through {@code SSLParameters.setEndpointIdentificationAlgorithm("HTTPS")},
 * called by reflection where the method exists (Android API 24 and the JDK), and through the held
 * {@link HostnameVerifier} otherwise. Nothing turns either check off.
 */
// @req SEC-PROXY-005
final class TlsConnector {

    /**
     * {@code SSLParameters.setEndpointIdentificationAlgorithm}, looked up by reflection: it is absent before Android
     * API 24, and a direct reference fails the API 21 signature check (design D-067). {@code null} where it is absent.
     */
    private static final @Nullable Method SET_ENDPOINT_IDENTIFICATION = endpointIdentificationMethod();

    /** The watchdog thread that closes the plain socket of a handshake past the total-timeout deadline. */
    private static final ScheduledThreadPoolExecutor WATCHDOG = watchdog();

    /**
     * Package-private test switch: {@code false} skips the reflective endpoint identification, so the
     * {@link HostnameVerifier} path that runs where the method is absent can be tested on the JDK (AC-6).
     */
    static volatile boolean endpointIdentification = true;

    /**
     * Package-private test seam: a verifier used in place of {@link HttpsURLConnection#getDefaultHostnameVerifier()}
     * on the fallback path; the JDK's default verifier rejects every host, so it cannot be the test oracle (AC-6).
     */
    static volatile @Nullable HostnameVerifier hostnameVerifier;

    private TlsConnector() {
    }

    /**
     * The TLS socket over {@code plain}, handshaken and verified for {@code host}; the plain socket stays the one the
     * watchdog closes. A failure is a {@link ProxyException}: {@code TOTAL_TIMEOUT} when the watchdog's deadline fired,
     * otherwise {@code CONNECT_TIMEOUT} for a handshake {@link SocketTimeoutException} and {@code CONNECT_FAILED} for
     * every other TLS or I/O failure, a rejected certificate or host name included. The caller closes {@code plain}
     * on failure.
     */
    static Socket layer(Socket plain, String host, int port, int connectTimeoutMillis, long deadlineNanos,
            @Nullable SSLSocketFactory factory) {
        SSLSocketFactory f = factory != null ? factory : (SSLSocketFactory) SSLSocketFactory.getDefault();
        Watchdog watchdog = Watchdog.arm(plain, deadlineNanos);
        try {
            plain.setSoTimeout(connectTimeoutMillis);
            SSLSocket ssl = (SSLSocket) f.createSocket(plain, host, port, true);
            boolean identified = endpointIdentification(ssl);
            ssl.startHandshake();
            if (!identified && !verifier().verify(host, ssl.getSession())) {
                throw new SSLPeerUnverifiedException("the backend certificate does not match the target host");
            }
            if (watchdog.disarm()) {
                throw new ProxyException(ProxyException.Kind.TOTAL_TIMEOUT, null);
            }
            return ssl;
        } catch (IOException e) {
            if (watchdog.disarm()) {
                throw new ProxyException(ProxyException.Kind.TOTAL_TIMEOUT, e);
            }
            if (e instanceof SocketTimeoutException) {
                throw new ProxyException(ProxyException.Kind.CONNECT_TIMEOUT, e);
            }
            throw new ProxyException(ProxyException.Kind.CONNECT_FAILED, e);
        } catch (RuntimeException e) {
            if (e instanceof ProxyException) {
                throw e;
            }
            // A provider failure outside IOException (for example a rejected argument) is still a failed TLS connect.
            throw new ProxyException(watchdog.disarm() ? ProxyException.Kind.TOTAL_TIMEOUT
                    : ProxyException.Kind.CONNECT_FAILED, e);
        } finally {
            watchdog.disarm();
        }
    }

    /** The verifier of the fallback path: the injected one, else {@link HttpsURLConnection}'s default. */
    static HostnameVerifier verifier() {
        HostnameVerifier v = hostnameVerifier;
        return v != null ? v : HttpsURLConnection.getDefaultHostnameVerifier();
    }

    /** Whether this runtime has {@code SSLParameters.setEndpointIdentificationAlgorithm}. */
    static boolean endpointIdentificationAvailable() {
        return SET_ENDPOINT_IDENTIFICATION != null;
    }

    /**
     * Turns on the "HTTPS" endpoint identification of {@code ssl}, so its handshake checks the certificate against
     * the host given to {@code createSocket}; {@code false} when the method is absent, switched off, or not
     * applicable, so the caller verifies the host itself.
     */
    private static boolean endpointIdentification(SSLSocket ssl) {
        Method m = SET_ENDPOINT_IDENTIFICATION;
        if (!endpointIdentification || m == null) {
            return false;
        }
        try {
            SSLParameters params = ssl.getSSLParameters();
            m.invoke(params, "HTTPS");
            ssl.setSSLParameters(params);
            return true;
        } catch (IllegalAccessException | InvocationTargetException | RuntimeException e) {
            return false;
        }
    }

    private static @Nullable Method endpointIdentificationMethod() {
        try {
            return SSLParameters.class.getMethod("setEndpointIdentificationAlgorithm", String.class);
        } catch (NoSuchMethodException | RuntimeException e) {
            return null;
        }
    }

    private static ScheduledThreadPoolExecutor watchdog() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "naruserve-proxy-tls-watchdog");
                t.setDaemon(true);
                return t;
            }
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    /**
     * Closes the plain socket when the total-timeout deadline passes before {@link #disarm()}; never the
     * {@link SSLSocket}, whose close sends {@code close_notify} and may block behind a writer.
     */
    private static final class Watchdog implements Runnable {
        private final Socket plain;
        private static final int ARMED = 0;
        private static final int FIRED = 1;
        private static final int DISARMED = 2;
        private final AtomicInteger state = new AtomicInteger(ARMED);
        private @Nullable ScheduledFuture<?> future;

        private Watchdog(Socket plain) {
            this.plain = plain;
        }

        static Watchdog arm(Socket plain, long deadlineNanos) {
            Watchdog w = new Watchdog(plain);
            long delay = Math.max(0, deadlineNanos - System.nanoTime());
            w.future = WATCHDOG.schedule(w, delay, TimeUnit.NANOSECONDS);
            return w;
        }

        @Override
        public void run() {
            if (state.compareAndSet(ARMED, FIRED)) {
                try {
                    plain.close();
                } catch (IOException ignored) {
                    // the handshake fails either way
                }
            }
        }

        /** Stops the watchdog; {@code true} when its deadline fired first. */
        boolean disarm() {
            if (state.compareAndSet(ARMED, DISARMED)) {
                ScheduledFuture<?> f = future;
                if (f != null) {
                    f.cancel(false);
                }
            }
            return state.get() == FIRED;
        }
    }
}
