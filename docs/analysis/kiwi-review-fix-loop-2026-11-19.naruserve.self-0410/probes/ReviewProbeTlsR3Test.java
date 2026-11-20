package com.snoworca.naruserve.proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.Response;
import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.testing.StubJsonCodec;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/** Review probe, round 3: the SSLSocket close added for R2-001. */
public class ReviewProbeTlsR3Test {

    private static final char[] PASSWORD = "changeit".toCharArray();
    private static File dir;
    private static SSLContext server;
    private static SSLContext client;

    @BeforeClass
    public static void makeCertificate() throws Exception {
        dir = java.nio.file.Files.createTempDirectory("probe-r3").toFile();
        keytool("-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=localhost",
                "-ext", "SAN=ip:127.0.0.1", "-validity", "2", "-keystore", "server.p12");
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = new FileInputStream(new File(dir, "server.p12"))) {
            ks.load(in, PASSWORD);
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, PASSWORD);
        server = SSLContext.getInstance("TLS");
        server.init(kmf.getKeyManagers(), null, null);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        client = SSLContext.getInstance("TLS");
        client.init(null, tmf.getTrustManagers(), null);
    }

    @AfterClass
    public static void removeCertificate() {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                f.delete();
            }
        }
        dir.delete();
    }

    /**
     * A backend that accepts TCP and never speaks TLS: the handshake times out (CONNECT_TIMEOUT). Is the SSLSocket the
     * proxy created closed afterwards? TlsConnector says the JDK's fatal alert closes it.
     */
    @Test(timeout = 60000)
    public void handshakeTimeoutLeavesTheSslSocketClosed() throws Exception {
        ServerSocket silent = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        List<Socket> accepted = new CopyOnWriteArrayList<>();
        Thread t = new Thread(() -> {
            try {
                while (true) {
                    accepted.add(silent.accept());
                }
            } catch (IOException e) {
                // closed
            }
        });
        t.setDaemon(true);
        t.start();
        Recording sockets = new Recording(client.getSocketFactory());
        RestApp app = null;
        try {
            InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
            app = RestApp.builder().httpAdapter(adapter).jsonCodec(new StubJsonCodec())
                    .logger(new RecordingLogger()).build();
            app.use("/p", Proxy.to("https://127.0.0.1:" + silent.getLocalPort()).sslSocketFactory(sockets)
                    .connectTimeout(500, TimeUnit.MILLISECONDS).maxConcurrent(1));
            app.listen(0);
            ClientResponse r = adapter.submit(ClientRequest.of("GET", "/p/x")).get(20, TimeUnit.SECONDS);
            System.out.println("PROBE handshake-timeout status=" + r.status());
            assertEquals(1, sockets.created.size());
            Thread.sleep(200);
            SSLSocket s = sockets.created.get(0);
            System.out.println("PROBE handshake-timeout sslClosed=" + s.isClosed() + " plainUnderClosed? n/a");
            assertTrue("SSLSocket after a handshake timeout is left unclosed: " + s, s.isClosed());
        } finally {
            if (app != null) {
                app.shutdown(2, TimeUnit.SECONDS);
            }
            silent.close();
            for (Socket s : accepted) {
                s.close();
            }
        }
    }

    /**
     * On the JDK, closing the plain socket under an SSLSocket that another thread is reading makes that reader's
     * failure close the SSLSocket itself (fatal -> shutdown -> tlsIsClosed), with no SSLSocket.close() call.
     * Shows that an isClosed() assertion after an abort cannot tell whether the release closed the SSLSocket.
     */
    @Test(timeout = 60000)
    public void aReaderFailingOnAClosedPlainSocketClosesTheSslSocketByItself() throws Exception {
        SSLServerSocket listener = (SSLServerSocket) server.getServerSocketFactory().createServerSocket(0, 50,
                InetAddress.getByName("127.0.0.1"));
        listener.setEnabledProtocols(new String[] {"TLSv1.3"});
        CountDownLatch hold = new CountDownLatch(1);
        Thread acc = new Thread(() -> {
            try (SSLSocket s = (SSLSocket) listener.accept()) {
                s.startHandshake();
                s.getOutputStream().write('a');
                s.getOutputStream().flush();
                hold.await(30, TimeUnit.SECONDS);
            } catch (Exception e) {
                // done
            }
        });
        acc.setDaemon(true);
        acc.start();
        Socket plain = new Socket();
        plain.connect(new InetSocketAddress("127.0.0.1", listener.getLocalPort()), 2000);
        SSLSocket ssl = (SSLSocket) client.getSocketFactory().createSocket(plain, "127.0.0.1",
                listener.getLocalPort(), true);
        ssl.startHandshake();
        InputStream in = ssl.getInputStream();
        assertEquals('a', in.read());
        AtomicReference<Throwable> readerFailure = new AtomicReference<>();
        CountDownLatch readerDone = new CountDownLatch(1);
        Thread reader = new Thread(() -> {
            try {
                in.read();
            } catch (Throwable e) {
                readerFailure.set(e);
            } finally {
                readerDone.countDown();
            }
        });
        reader.start();
        Thread.sleep(300);
        plain.close(); // only the plain socket, as the mutated release would
        assertTrue(readerDone.await(5, TimeUnit.SECONDS));
        System.out.println("PROBE reader failure=" + readerFailure.get() + " sslClosed=" + ssl.isClosed());
        hold.countDown();
        listener.close();
        // red when the JDK reader does NOT close the SSLSocket by itself
        assertTrue("sslClosed=" + ssl.isClosed(), !ssl.isClosed());
    }

    /**
     * Stress: res.abort on another thread at varied moments while the request thread reads a stalled https body
     * (readTimeout 10 s). Each abort must return at once, each response end abnormally, and the slot
     * (maxConcurrent 1) must be free for the next request, which must reach the backend.
     */
    @Test(timeout = 120000)
    public void abortsAtVariedMomentsNeverBlockAndAlwaysFreeTheSlot() throws Exception {
        final Semaphore written = new Semaphore(0);
        SSLServerSocket listener = (SSLServerSocket) server.getServerSocketFactory().createServerSocket(0, 50,
                InetAddress.getByName("127.0.0.1"));
        listener.setEnabledProtocols(new String[] {"TLSv1.3"});
        final CountDownLatch hold = new CountDownLatch(1);
        final List<Socket> open = new CopyOnWriteArrayList<>();
        Thread acceptor = new Thread(() -> {
            while (!listener.isClosed()) {
                final Socket s;
                try {
                    s = listener.accept();
                } catch (IOException e) {
                    return;
                }
                open.add(s);
                Thread h = new Thread(() -> {
                    try {
                        SSLSocket ssl = (SSLSocket) s;
                        ssl.startHandshake();
                        InputStream in = ssl.getInputStream();
                        byte[] end = {'\r', '\n', '\r', '\n'};
                        int matched = 0;
                        for (int b; matched < 4 && (b = in.read()) != -1; ) {
                            matched = b == end[matched] ? matched + 1 : (b == '\r' ? 1 : 0);
                        }
                        OutputStream out = ssl.getOutputStream();
                        out.write("HTTP/1.1 200 OK\r\nContent-Length: 100000\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                        out.write(new byte[8192]);
                        out.flush();
                        written.release();
                        hold.await(60, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        // gone
                    }
                });
                h.setDaemon(true);
                h.start();
            }
        });
        acceptor.setDaemon(true);
        acceptor.start();

        Recording sockets = new Recording(client.getSocketFactory());
        final AtomicReference<Response> captured = new AtomicReference<>();
        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
        RestApp app = RestApp.builder().httpAdapter(adapter).jsonCodec(new StubJsonCodec())
                .logger(new RecordingLogger()).build();
        app.use((req, res, next) -> {
            captured.set(res);
            next.run();
        });
        app.use("/p", Proxy.to("https://127.0.0.1:" + listener.getLocalPort()).sslSocketFactory(sockets)
                .readTimeout(10, TimeUnit.SECONDS).maxConcurrent(1));
        app.listen(0);
        try {
            long worstAbort = 0;
            for (int i = 0; i < 25; i++) {
                Future<ClientResponse> pending = adapter.submit(ClientRequest.of("GET", "/p/" + i));
                assertTrue("iteration " + i + ": the request reached the backend (slot free)",
                        written.tryAcquire(10, TimeUnit.SECONDS));
                Thread.sleep((i * 7) % 60);
                long begin = System.nanoTime();
                captured.get().abort(null);
                long abortMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);
                worstAbort = Math.max(worstAbort, abortMillis);
                ClientResponse r = pending.get(10, TimeUnit.SECONDS);
                long endMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);
                assertTrue("iteration " + i + " abort took " + abortMillis, abortMillis < 1000);
                assertTrue("iteration " + i + " end took " + endMillis, endMillis < 2500);
                assertTrue("iteration " + i + " abnormal", r.abnormallyClosed());
            }
            Thread.sleep(200);
            int unclosed = 0;
            for (SSLSocket s : sockets.created) {
                if (!s.isClosed()) {
                    unclosed++;
                }
            }
            System.out.println("PROBE stress worstAbortMs=" + worstAbort + " created=" + sockets.created.size()
                    + " unclosed=" + unclosed);
            assertEquals(0, unclosed);
        } finally {
            app.shutdown(2, TimeUnit.SECONDS);
            hold.countDown();
            listener.close();
            for (Socket s : open) {
                s.close();
            }
        }
    }

    private static final class Recording extends SSLSocketFactory {
        final List<SSLSocket> created = new CopyOnWriteArrayList<>();
        private final SSLSocketFactory factory;

        Recording(SSLSocketFactory factory) {
            this.factory = factory;
        }

        @Override
        public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
            SSLSocket ssl = (SSLSocket) factory.createSocket(s, host, port, autoClose);
            created.add(ssl);
            return ssl;
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return factory.getDefaultCipherSuites();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return factory.getSupportedCipherSuites();
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            throw new IOException("unexpected");
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
            throw new IOException("unexpected");
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            throw new IOException("unexpected");
        }

        @Override
        public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort)
                throws IOException {
            throw new IOException("unexpected");
        }
    }

    private static void keytool(String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(new File(System.getProperty("java.home"), "bin/keytool").getPath());
        command.addAll(Arrays.asList(args));
        command.addAll(Arrays.asList("-storetype", "PKCS12", "-storepass", "changeit", "-keypass", "changeit"));
        Process p = new ProcessBuilder(command).directory(dir).redirectErrorStream(true).start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        for (int n; (n = p.getInputStream().read(buf)) != -1; ) {
            out.write(buf, 0, n);
        }
        if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) {
            throw new AssertionError("keytool failed: " + new String(out.toByteArray(), StandardCharsets.UTF_8));
        }
    }
}
