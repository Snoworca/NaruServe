package com.snoworca.naruserve.servlet;

import static org.junit.Assert.assertEquals;
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
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.junit.Rule;
import org.junit.Test;

/**
 * The R38 failure floor of {@code POST /auth/login} on the Servlet adapter behind Jetty at a local port: the 401 that
 * the floor scheduler thread writes after the delay reaches the client intact while other requests are served
 * (SEC-TOKEN-009 AC-8, the Servlet half; the TinyHttpd half is AuthFailureFloorSocketTest).
 */
public class ServletAuthFailureFloorTest {

    @Rule
    public final ContainerRule container = new ContainerRule();

    private static final String SECRET = "a-test-secret-of-at-least-32-bytes-for-hs256";

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

    /** A login limit that never blocks. */
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
     * call per login), so the test orders its requests by that event instead of a fixed sleep.
     */
    static final class ReachedLimit extends NoLimit {
        private volatile CountDownLatch reached = new CountDownLatch(0);

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

    private static SharedApp.Reply login(int port, String password) throws IOException {
        byte[] body = ("{\"username\":\"alice\",\"password\":\"" + password + "\"}").getBytes(StandardCharsets.UTF_8);
        return SharedApp.http(port, "POST", "/auth/login", body, "Content-Type", "application/json");
    }

    private static void assertDelayedFailureIntact(SharedApp.Reply r) {
        assertEquals(r.text(), 401, r.status);
        Map<?, ?> error = (Map<?, ?>) ((Map<?, ?>) MiniJson.parse(r.text(), MiniJson.Limits.file())).get("error");
        assertEquals("invalid_credentials", error.get("code"));
        assertNotNull(error.get("requestId"));
        assertTrue(r.header("Content-Type"), r.header("Content-Type").startsWith("application/json"));
        assertTrue(String.valueOf(r.header("WWW-Authenticate")), r.header("WWW-Authenticate").startsWith("Bearer"));
        String length = r.header("Content-Length");
        if (length != null) {
            assertEquals("the body is complete", Integer.parseInt(length), r.body.length);
        }
    }

    // SEC-TOKEN-009 AC-8 (Servlet half): with the Servlet adapter on a local port, a failed login answered after the
    // floor is received with status 401, its JSON body and its headers unchanged, and a concurrent non-auth request on
    // the same server is answered while the failure waits
    @Test(timeout = 60000)
    public void SEC_TOKEN_009_AC8_theDelayedFailureArrivesIntactOnTheServletAdapter() throws Exception {
        ReachedLimit limit = new ReachedLimit();
        InMemoryUserStore users = new InMemoryUserStore();
        users.create(AuthUser.builder().username("alice")
                .passwordHash(new Pbkdf2PasswordHasher(1000).hash("the right password".toCharArray())).build());
        ServletAdapter adapter = new ServletAdapter();
        RestApp app = RestApp.builder().httpAdapter(adapter).jsonCodec(new MiniCodec()).logger(new RecordingLogger())
                .build();
        Auth auth = Auth.builder(SECRET).users(users).hashCost(HashCost.fixed(200_000)).loginThrottle(limit)
                .permit("/health").build();
        auth.install(app);
        app.get("/health", (req, res) -> res.send("ok"));
        app.onError(auth.errors());
        app.listen(0);
        container.servlet(new NaruServeServlet(adapter, InitParams.prefix("/*")), "/*");
        final int port = container.port();
        try {
            assertDelayedFailureIntact(login(port, "a wrong password"));
            ExecutorService pool = Executors.newFixedThreadPool(1);
            try {
                CountDownLatch reached = limit.expect(1);
                final AtomicLong failedNanos = new AtomicLong(-1);
                Future<SharedApp.Reply> failing = pool.submit(() -> {
                    long own = System.nanoTime();
                    SharedApp.Reply r = login(port, "a wrong password");
                    failedNanos.set(System.nanoTime() - own);
                    return r;
                });
                // the failing login is on the server and at the hash step before /health is sent
                assertTrue("the failing login reached the server", reached.await(20, TimeUnit.SECONDS));
                long healthStart = System.nanoTime();
                SharedApp.Reply health = SharedApp.http(port, "GET", "/health", null);
                long healthAt = System.nanoTime() - healthStart;
                boolean stillWaiting = !failing.isDone();
                assertEquals(200, health.status);
                assertEquals("ok", health.text());
                assertTrue("the failing login was still waiting when /health was answered", stillWaiting);
                SharedApp.Reply failed = failing.get(20, TimeUnit.SECONDS);
                long failedAt = failedNanos.get();
                // each exchange timed from its own start
                assertTrue("the non-auth request was answered while the failure waited", healthAt < failedAt);
                assertDelayedFailureIntact(failed);
            } finally {
                pool.shutdownNow();
            }
            assertEquals(200, login(port, "the right password").status);
        } finally {
            app.shutdown(5, TimeUnit.SECONDS);
        }
    }
}
