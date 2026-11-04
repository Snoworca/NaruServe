package com.snoworca.naruserve.utils.auth;

import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.utils.auth.internal.MiniJson;
import com.snoworca.naruserve.utils.auth.password.Pbkdf2PasswordHasher;
import com.snoworca.naruserve.utils.auth.rbac.RolePolicy;
import com.snoworca.naruserve.utils.auth.user.AuthUser;
import com.snoworca.naruserve.utils.auth.user.InMemoryUserStore;
import com.snoworca.naruserve.utils.auth.user.UserStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;

/**
 * The wave 8 fixture: an app over the in-memory adapter (the peer address and the TLS flag are chosen per test) with
 * the auth MiniJson codec, auth over real HS256 tokens with cheap hashing, a test clock and a recording logger. The
 * stores are the shipped in-memory ones; nothing is a mock.
 */
final class W8 {

    static final long T0 = 1_761_700_900_000L;
    static final String PW = "correct horse battery";
    static final String HOST = "app.test";
    static final String SAME = "https://app.test";
    static final String EVIL = "https://evil.example";

    final AtomicLong clock = new AtomicLong(T0);
    final InMemoryUserStore store = new InMemoryUserStore();
    final RecordingLogger logger = new RecordingLogger();
    UserStore users = store;
    /** True: the app trusts one proxy hop, so {@code X-Forwarded-For} sets the client IP of the login limits. */
    boolean proxied;
    /** The app's JSON codec; the auth MiniJson codec when not replaced. */
    com.snoworca.naruserve.spi.JsonCodec codec = new AdminFixture.MiniJsonCodec();
    /** App routes added after the probe routes, before {@code app.onError(auth.errors())} and listen. */
    java.util.function.Consumer<W8> routes = w -> { };
    /** Wraps the error handler {@code app.onError} gets; {@code auth.errors()} itself when not replaced; null: none. */
    @Nullable UnaryOperator<com.snoworca.naruserve.ErrorHandler> errors = h -> h;
    /** How auth joins the app: {@code auth.install(app)} when not replaced (FR-AUTH-010: {@code app.use(auth)}). */
    java.util.function.BiConsumer<Auth, RestApp> attach = (a, app) -> a.install(app);
    InMemoryHttpAdapter adapter;
    /** Wraps the engine the app is built over (the in-memory adapter itself when not replaced). */
    UnaryOperator<com.snoworca.naruserve.spi.HttpAdapter> engine = a -> a;
    RestApp app;
    Auth auth;
    /** The key source auth is built with; the test secret when null. */
    com.snoworca.naruserve.utils.auth.spi.@Nullable KeySource keys;

    Auth.Builder builder() {
        com.snoworca.naruserve.utils.auth.spi.KeySource k = keys;
        Auth.Builder b = k != null ? Auth.builder(k) : Auth.builder(AuthTestSupport.SECRET);
        return b.hashIterations(1000).logger(logger).users(users)
                .timeSource(clock::get).roles(RolePolicy.builder().role("reader", "posts:read")
                        .role("writer", "posts:write").build());
    }

    W8 start(UnaryOperator<Auth.Builder> config) {
        return start("127.0.0.1", true, config);
    }

    /** Builds auth, installs it, adds the probe routes {@code GET /api/x}, {@code POST /api/x} and {@code GET /health}. */
    W8 start(String peer, boolean secure, UnaryOperator<Auth.Builder> config) {
        adapter = new InMemoryHttpAdapter(peer, secure);
        app = RestApp.builder().httpAdapter(engine.apply(adapter)).jsonCodec(codec).logger(logger).build();
        if (proxied) {
            app.trustProxyHops(1);
        }
        auth = config.apply(builder()).build();
        attach.accept(auth, app);
        app.get("/api/x", auth.require("posts:read"), (req, res) -> res.send("x:" + auth.user(req).getId()));
        app.post("/api/x", auth.require("posts:read"), (req, res) -> res.status(201).send("posted"));
        app.get("/health", (req, res) -> res.send(String.valueOf(auth.userOrNull(req) == null ? "anon" : "user")));
        routes.accept(this);
        UnaryOperator<com.snoworca.naruserve.ErrorHandler> e = errors;
        if (e != null) {
            app.onError(e.apply(auth.errors()));
        }
        app.listen(0);
        return this;
    }

    void stop() {
        if (app != null) {
            app.shutdown(5, TimeUnit.SECONDS);
        }
    }

    /** A stored user with a real PBKDF2 hash of {@code password} (null: none) and {@code roles}; its id. */
    String user(String name, @Nullable String password, String... roles) throws Exception {
        return store.create(AuthUser.builder().username(name)
                .passwordHash(password == null ? null : new Pbkdf2PasswordHasher(1000).hash(password.toCharArray()))
                .roles(roles).build()).getId();
    }

    AuthUser stored(String id) throws Exception {
        AuthUser u = store.findById(id);
        if (u == null) {
            throw new AssertionError("no user " + id);
        }
        return u;
    }

    ClientResponse post(String path, @Nullable String json, String... headers) {
        ClientRequest r = json == null ? ClientRequest.of("POST", path) : ClientRequest.json("POST", path, json);
        for (int i = 0; i < headers.length; i += 2) {
            r.header(headers[i], headers[i + 1]);
        }
        return adapter.send(r);
    }

    ClientResponse get(String path, String... headers) {
        ClientRequest r = ClientRequest.of("GET", path);
        for (int i = 0; i < headers.length; i += 2) {
            r.header(headers[i], headers[i + 1]);
        }
        return adapter.send(r);
    }

    /** {@code {"username":name,"password":pw}}. */
    static String login(String name, String pw) {
        return "{\"username\":\"" + name + "\",\"password\":\"" + pw + "\"}";
    }

    /** A successful HEADER login's body as a map. */
    Map<String, Object> loginBody(String name, String pw, String... headers) {
        ClientResponse r = post("/auth/login", login(name, pw), headers);
        if (r.status() != 200) {
            throw new AssertionError("login " + name + ": " + r.status() + " " + r.text());
        }
        return object(r);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(ClientResponse r) {
        Object o = MiniJson.parse(r.text(), MiniJson.Limits.file());
        return o instanceof Map ? (Map<String, Object>) o : Collections.<String, Object>emptyMap();
    }

    static String code(ClientResponse r) {
        return AuthTestSupport.code(r);
    }

    /** Every Set-Cookie header of {@code r}. */
    static List<String> setCookies(ClientResponse r) {
        List<String> out = new ArrayList<>();
        for (Entry<String, String> e : r.headers()) {
            if (e.getKey().equalsIgnoreCase("Set-Cookie")) {
                out.add(e.getValue());
            }
        }
        return out;
    }

    /** The Set-Cookie header of {@code name}, or null. */
    static @Nullable String setCookie(ClientResponse r, String name) {
        for (String c : setCookies(r)) {
            if (c.startsWith(name + "=")) {
                return c;
            }
        }
        return null;
    }

    /** The value a Set-Cookie header gives {@code name}, or null. */
    static @Nullable String cookieValue(ClientResponse r, String name) {
        String c = setCookie(r, name);
        if (c == null) {
            return null;
        }
        int semi = c.indexOf(';');
        return c.substring(name.length() + 1, semi < 0 ? c.length() : semi);
    }

    /** True when a Set-Cookie gives a value (not a Max-Age=0 clear) to one of the four auth cookies. */
    static boolean plantsAuthCookie(ClientResponse r) {
        for (String c : setCookies(r)) {
            for (String n : new String[] {"naru_at", "naru_rt", "naru_csrf", "naru_device"}) {
                if (c.startsWith(n + "=") && !c.contains("Max-Age=0")) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The {@code fam} claim of an access token. */
    String fam(String accessToken) {
        return auth.tokens().verifyAccess(accessToken).getString("fam");
    }

    /** An access token of {@code userId} at its current ver that expired a minute ago. */
    String expiredAccess(String userId) throws Exception {
        long now = clock.get();
        clock.set(now - 3_600_000L);
        try {
            return auth.accessToken(userId, stored(userId).getTokenVersion(), 60_000L);
        } finally {
            clock.set(now);
        }
    }
}
