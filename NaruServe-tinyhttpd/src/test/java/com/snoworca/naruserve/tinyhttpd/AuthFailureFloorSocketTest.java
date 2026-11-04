package com.snoworca.naruserve.tinyhttpd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.spi.JsonCodec;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.utils.auth.Auth;
import com.snoworca.naruserve.utils.auth.internal.MiniJson;
import com.snoworca.naruserve.utils.auth.password.HashCost;
import com.snoworca.naruserve.utils.auth.password.Pbkdf2PasswordHasher;
import com.snoworca.naruserve.utils.auth.spi.LoginThrottle;
import com.snoworca.naruserve.utils.auth.user.AuthUser;
import com.snoworca.naruserve.utils.auth.user.InMemoryUserStore;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.After;
import org.junit.Test;

/**
 * The R38 failure floor of {@code POST /auth/login} on the TinyHttpd adapter over a local port: the delayed 401 is
 * written from the floor scheduler thread and arrives intact, and the waiting holds no worker thread (SEC-TOKEN-009).
 */
public class AuthFailureFloorSocketTest {

    static final String SECRET = "a-test-secret-of-at-least-32-bytes-for-hs256";
    /** A target cost whose start-time timing T makes every failed login wait visibly for 1.25T. */
    static final HashCost COST = HashCost.fixed(200_000);

    /** The app's JSON codec over the auth MiniJson reader, reporting malformed input as an IOException. */
    static final class MiniCodec implements JsonCodec {
        @Override
        @SuppressWarnings("unchecked")
        public <T> T decode(Reader reader, Type type) throws IOException {
            StringBuilder b = new StringBuilder();
            char[] buf = new char[1024];
            for (int n; (n = reader.read(buf)) >= 0;) {
                b.append(buf, 0, n);
            }
            try {
                return (T) MiniJson.parse(b.toString(), MiniJson.Limits.file());
            } catch (IllegalArgumentException malformed) {
                throw new IOException(malformed.getMessage(), malformed);
            }
        }

        @Override
        public void encode(@Nullable Object value, Writer writer) throws IOException {
            writer.write(MiniJson.write(value));
        }
    }

    /** A login limit that never blocks, so many failures of one name all reach the hash and the floor. */
    static class NoLimit implements LoginThrottle {
        @Override
        public long retryAfter(String key) {
            return 0;
        }

        @Override
        public long recordFailure(String key) {
            return 0;
        }

        @Override
        public void reset(String key) {
        }
    }

    /**
     * A {@link NoLimit} that counts the logins reaching the last limit check before the hash (the user-only key, one
     * call per login), so a test orders its requests by that event instead of a fixed sleep.
     */
    static final class ReachedLimit extends NoLimit {
        private volatile CountDownLatch reached = new CountDownLatch(0);

        /** Starts counting {@code logins} logins that reach the hash step from now on. */
        CountDownLatch expect(int logins) {
            CountDownLatch l = new CountDownLatch(logins);
            reached = l;
            return l;
        }

        @Override
        public long retryAfter(String key) {
            if (key.startsWith("login:u:")) {
                reached.countDown();
            }
            return 0;
        }
    }

    /** One raw HTTP/1.1 exchange: status, headers in wire order and the body. */
    static final class Reply {
        final int status;
        final Map<String, String> headers;
        final String body;
        final long millis;

        Reply(int status, Map<String, String> headers, String body, long millis) {
            this.status = status;
            this.headers = headers;
            this.body = body;
            this.millis = millis;
        }
    }

    static Reply http(int port, String method, String target, @Nullable String json) throws IOException {
        long start = System.nanoTime();
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.setSoTimeout(20_000);
            byte[] body = json == null ? new byte[0] : json.getBytes(StandardCharsets.UTF_8);
            StringBuilder head = new StringBuilder(method + " " + target + " HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                    + "Connection: close\r\n");
            if (json != null) {
                head.append("Content-Type: application/json\r\nContent-Length: ").append(body.length).append("\r\n");
            }
            head.append("\r\n");
            OutputStream out = s.getOutputStream();
            out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
            out.write(body);
            out.flush();
            InputStream in = s.getInputStream();
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            for (int n; (n = in.read(buf)) >= 0;) {
                all.write(buf, 0, n);
            }
            String raw = new String(all.toByteArray(), StandardCharsets.UTF_8);
            int split = raw.indexOf("\r\n\r\n");
            String[] lines = raw.substring(0, split).split("\r\n");
            Map<String, String> headers = new LinkedHashMap<>();
            for (int i = 1; i < lines.length; i++) {
                int c = lines[i].indexOf(':');
                headers.put(lines[i].substring(0, c).trim().toLowerCase(Locale.ROOT), lines[i].substring(c + 1).trim());
            }
            String text = raw.substring(split + 4);
            if ("chunked".equalsIgnoreCase(headers.get("transfer-encoding"))) {
                text = dechunk(text);
            }
            return new Reply(Integer.parseInt(lines[0].split(" ")[1]), headers, text,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
        }
    }

    private static String dechunk(String chunked) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < chunked.length()) {
            int eol = chunked.indexOf("\r\n", i);
            int size = Integer.parseInt(chunked.substring(i, eol).trim(), 16);
            if (size == 0) {
                break;
            }
            out.append(chunked, eol + 2, eol + 2 + size);
            i = eol + 2 + size + 2;
        }
        return out.toString();
    }

    private RestApp app;
    private final ReachedLimit limit = new ReachedLimit();

    @After
    public void stop() {
        if (app != null) {
            app.shutdown(5, TimeUnit.SECONDS);
        }
    }

    /** The app on TinyHttpd at a local port with at most {@code workers} worker threads; its port. */
    int start(int workers) throws Exception {
        InMemoryUserStore users = new InMemoryUserStore();
        users.create(AuthUser.builder().username("alice")
                .passwordHash(new Pbkdf2PasswordHasher(1000).hash("the right password".toCharArray())).build());
        app = RestApp.builder().httpAdapter(new TinyHttpdAdapter()).jsonCodec(new MiniCodec())
                .logger(new RecordingLogger()).build();
        app.server(o -> o.maxWorkers(workers).maxConnectionsPerAddress(200).maxConnections(400));
        Auth auth = Auth.builder(SECRET).users(users).hashCost(COST).loginThrottle(limit).permit("/health")
                .build();
        auth.install(app);
        app.get("/health", (req, res) -> res.send("ok"));
        app.onError(auth.errors());
        return app.listen(0).port();
    }

    static void assertDelayedFailureIntact(Reply r) {
        assertEquals(r.body, 401, r.status);
        Map<?, ?> error = (Map<?, ?>) ((Map<?, ?>) MiniJson.parse(r.body, MiniJson.Limits.file())).get("error");
        assertEquals("invalid_credentials", error.get("code"));
        assertNotNull(error.get("requestId"));
        assertTrue(r.headers.toString(), r.headers.get("content-type").startsWith("application/json"));
        assertTrue(r.headers.toString(), r.headers.get("www-authenticate").startsWith("Bearer"));
        String length = r.headers.get("content-length");
        if (length != null) {
            assertEquals("the body is complete", Integer.parseInt(length),
                    r.body.getBytes(StandardCharsets.UTF_8).length);
        }
    }

    static String login(String password) {
        return "{\"username\":\"alice\",\"password\":\"" + password + "\"}";
    }

    // SEC-TOKEN-009 AC-8 (TinyHttpd half): on the TinyHttpd adapter at a local port a failed login answered after the
    // floor reaches the client with status 401, its JSON body and its headers unchanged, and a concurrent non-auth
    // request on the same server is answered while the failure waits
    @Test(timeout = 60000)
    public void SEC_TOKEN_009_AC8_theDelayedFailureArrivesIntactOnTinyHttpd() throws Exception {
        final int port = start(4);
        Reply first = http(port, "POST", "/auth/login", login("a wrong password"));
        assertDelayedFailureIntact(first);
        System.out.println("SEC-TOKEN-009 AC-8 TinyHttpd: a failed login took " + first.millis + " ms");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch reached = limit.expect(1);
            Future<Reply> failing = pool.submit(() -> http(port, "POST", "/auth/login", login("a wrong password")));
            // the failing login is on the server and at the hash step before /health is sent
            assertTrue("the failing login reached the server", reached.await(20, TimeUnit.SECONDS));
            Reply health = http(port, "GET", "/health", null);
            boolean stillWaiting = !failing.isDone();
            assertEquals(200, health.status);
            assertTrue("the failing login was still waiting when /health was answered (" + health.millis + " ms)",
                    stillWaiting);
            assertEquals("ok", health.body);
            Reply failed = failing.get(20, TimeUnit.SECONDS);
            assertTrue("the non-auth request (" + health.millis + " ms) was answered while the failure ("
                    + failed.millis + " ms) waited", health.millis + 10 < failed.millis);
            assertDelayedFailureIntact(failed);
            Reply ok = http(port, "POST", "/auth/login", login("the right password"));
            assertEquals(ok.body, 200, ok.status);
        } finally {
            pool.shutdownNow();
        }
    }

    // SEC-TOKEN-009 AC-5: while failure responses wait for the floor the waiting holds no worker thread: 50 delayed
    // failures with 2 worker threads do not block a concurrent non-auth request
    @Test(timeout = 120000)
    public void SEC_TOKEN_009_AC5_waitingFailuresHoldNoWorker() throws Exception {
        final int port = start(2);
        Reply probe = http(port, "POST", "/auth/login", login("a wrong password"));
        assertDelayedFailureIntact(probe);
        long floorMillis = probe.millis;
        ExecutorService pool = Executors.newFixedThreadPool(50);
        try {
            // as many failures as there are workers reached the hash step: a floor that held its worker would now
            // hold every worker, and /health would queue behind the other failures
            CountDownLatch reached = limit.expect(2);
            List<Future<Reply>> failures = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                failures.add(pool.submit(() -> http(port, "POST", "/auth/login", login("a wrong password"))));
            }
            assertTrue("the failures reached the server", reached.await(20, TimeUnit.SECONDS));
            Reply health = http(port, "GET", "/health", null);
            assertEquals(200, health.status);
            int pending = 0;
            for (Future<Reply> f : failures) {
                pending += f.isDone() ? 0 : 1;
            }
            assertTrue("failures were still waiting when the non-auth request was answered (" + pending + ")",
                    pending > 0);
            System.out.println("SEC-TOKEN-009 AC-5: floor " + floorMillis + " ms, non-auth " + health.millis + " ms, "
                    + pending + " of 50 failures pending");
            assertTrue("the non-auth request did not wait for the floor: " + health.millis + " ms",
                    health.millis < floorMillis);
            for (Future<Reply> f : failures) {
                Reply r = f.get(60, TimeUnit.SECONDS);
                if (r.status == 503) {
                    // a full hash-slot queue under load: also a failure sent after the floor (SEC-TOKEN-009 AC-3)
                    assertEquals("SERVICE_UNAVAILABLE", ((Map<?, ?>) ((Map<?, ?>) MiniJson.parse(r.body,
                            MiniJson.Limits.file())).get("error")).get("code"));
                    assertTrue(r.headers.get("content-type").startsWith("application/json"));
                } else {
                    assertDelayedFailureIntact(r);
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
