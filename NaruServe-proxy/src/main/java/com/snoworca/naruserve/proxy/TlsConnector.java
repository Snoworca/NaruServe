package com.snoworca.naruserve.proxy;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import org.jspecify.annotations.Nullable;

/**
 * Layers TLS over a connected plain socket for an {@code https} target (SEC-PROXY-005): the factory's
 * {@code createSocket(plain, host, port, true)}, so SNI carries the target host; the handshake with {@code SO_TIMEOUT}
 * set to the connect timeout and under the total-timeout watchdog; the certificate chain checked by the factory's
 * trust managers; and the host name checked after every handshake, on every runtime, by the held
 * {@link HostnameVerifier} (the built-in {@link CertificateHostnames} matcher on the session's peer leaf
 * certificate), whatever the factory or its socket did with {@code SSLParameters}, with
 * {@code SSLParameters.setEndpointIdentificationAlgorithm("HTTPS")} also set by reflection where the method exists
 * (Android API 24 and the JDK) as a second layer. {@code HttpsURLConnection.getDefaultHostnameVerifier()}, which
 * rejects every host on a JVM, is never used. Nothing turns either check off.
 */
// @req SEC-PROXY-005
final class TlsConnector {

    /**
     * {@code SSLParameters.setEndpointIdentificationAlgorithm}, looked up by reflection: it is absent before Android
     * API 24, and a direct reference fails the API 21 signature check (design D-067). {@code null} where it is absent.
     */
    private static final @Nullable Method SET_ENDPOINT_IDENTIFICATION = endpointIdentificationMethod(
            "setEndpointIdentificationAlgorithm", String.class);

    /** The built-in certificate host name matcher, the held verifier unless a test injects another. */
    private static final HostnameVerifier BUILT_IN_VERIFIER = new CertificateHostnames();

    /**
     * Package-private test switch: {@code false} skips the reflective endpoint identification, as on Android before
     * API 24, so the held verifier is the only host name check (AC-6).
     */
    static volatile boolean endpointIdentification = true;

    /**
     * Package-private test seam: a verifier used in place of the built-in {@link CertificateHostnames} matcher, so a
     * test can observe the call and force either answer (AC-6, AC-9).
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
            endpointIdentification(ssl);
            ssl.startHandshake();
            // The proxy's own check runs whatever the identification did: a factory may handshake inside createSocket,
            // its trust manager may skip the endpoint check, or its socket may keep the parameters without applying
            // them (AC-9).
            if (!verifier().verify(host, ssl.getSession())) {
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

    /** The held host name check: the injected verifier, else the built-in {@link CertificateHostnames} matcher. */
    static HostnameVerifier verifier() {
        HostnameVerifier v = hostnameVerifier;
        return v != null ? v : BUILT_IN_VERIFIER;
    }

    /** Whether this runtime has {@code SSLParameters.setEndpointIdentificationAlgorithm}. */
    static boolean endpointIdentificationAvailable() {
        return SET_ENDPOINT_IDENTIFICATION != null;
    }

    /**
     * Turns on the "HTTPS" endpoint identification of {@code ssl} where the method exists and the test switch is on,
     * so the handshake also checks the certificate against the host given to {@code createSocket}. This is only the
     * second layer: whether the socket kept or applied it, {@link #layer} runs the held verifier after the handshake.
     */
    private static void endpointIdentification(SSLSocket ssl) {
        Method set = SET_ENDPOINT_IDENTIFICATION;
        if (!endpointIdentification || set == null) {
            return;
        }
        try {
            SSLParameters params = ssl.getSSLParameters();
            set.invoke(params, "HTTPS");
            ssl.setSSLParameters(params);
        } catch (IllegalAccessException | InvocationTargetException | RuntimeException e) {
            // the held verifier still checks the host
        }
    }

    private static @Nullable Method endpointIdentificationMethod(String name, Class<?>... parameterTypes) {
        try {
            return SSLParameters.class.getMethod(name, parameterTypes);
        } catch (NoSuchMethodException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Closes the plain socket when the total-timeout deadline passes before {@link #disarm()}; never the
     * {@link SSLSocket}, whose close sends {@code close_notify} and may block behind a writer. The deadline runs on
     * the proxy's one JVM-wide scheduler, {@link ProxyExchange.Scheduler}, like the write and total deadlines.
     */
    // @req REL-PROXY-002
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

        // @req REL-PROXY-002
        static Watchdog arm(Socket plain, long deadlineNanos) {
            Watchdog w = new Watchdog(plain);
            long delay = Math.max(0, deadlineNanos - System.nanoTime());
            w.future = ProxyExchange.Scheduler.INSTANCE.schedule(w, delay);
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
