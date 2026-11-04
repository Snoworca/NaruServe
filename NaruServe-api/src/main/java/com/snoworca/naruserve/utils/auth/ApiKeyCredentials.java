package com.snoworca.naruserve.utils.auth;

import com.snoworca.naruserve.Request;
import com.snoworca.naruserve.utils.auth.apikey.ApiKeyStore;
import com.snoworca.naruserve.utils.auth.apikey.StoredApiKey;
import com.snoworca.naruserve.utils.auth.internal.ApiKeyFormat;
import com.snoworca.naruserve.utils.auth.internal.ApiKeyUsage;
import com.snoworca.naruserve.utils.auth.internal.ConstantTime;
import com.snoworca.naruserve.utils.auth.internal.Credential;
import com.snoworca.naruserve.utils.auth.internal.CredentialResolver;
import com.snoworca.naruserve.utils.auth.internal.PrincipalCache;
import com.snoworca.naruserve.utils.auth.internal.ThrottleKeys;
import com.snoworca.naruserve.utils.auth.internal.TransportCredentials;
import com.snoworca.naruserve.utils.auth.spi.LoginThrottle;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;

/**
 * The built-in API key authenticator (00 R47 (1), (2)), in front of the token reading when {@code .apiKeys(store)} is
 * set; it is internal and cannot be replaced. Keys and JWTs are told apart by the {@code Authorization} scheme alone:
 * a header whose scheme equals the configured key scheme (ignoring case) is a key, everything else goes to the token
 * reading, so {@code Bearer nk_...} is a bad JWT. A key is read only from that header, never from the query string or
 * a cookie, and must be exactly one RFC 9110 {@code token68} of the form {@code nk_<prefix8>_<secret32>}. With a
 * cookie access token next to it (COOKIE and BOTH) the request carries two credentials: 400. The procedure: parse,
 * {@code findByPrefix} (a dummy hash for a missing prefix and a byte-wise prefix re-compare), a constant-time compare of
 * the SHA-256 of the secret, the expiry without leeway, the owner read through the principal cache, the owner's
 * {@code enabled} flag and {@code ver} (a key issued for a newer {@code ver} than the cached one re-reads the owner once).
 * Every failure is 401 {@code invalid_api_key} and counts in the key-only per-IP counter, which answers 429 before any
 * parsing once it is over its limit; a store failure propagates (503). No hash slot and no failure floor is used. A
 * success records the key's {@code lastUsedAt} off the request path.
 */
// @req SEC-APIKEY-001
// @req SEC-APIKEY-002
// @req IR-TOKEN-001
final class ApiKeyCredentials implements CredentialResolver {

    private static final String DUMMY_HASH = ApiKeyFormat.secretHash("naru-dummy-api-key-secret");

    private final Auth auth;
    private final CredentialResolver tokens;
    private final AtomicLong invariantLoggedAt = new AtomicLong(Long.MIN_VALUE);
    private volatile @Nullable Runnable compareHook;

    ApiKeyCredentials(Auth auth, CredentialResolver tokens) {
        this.auth = auth;
        this.tokens = tokens;
    }

    /** True when the {@code Authorization} value's scheme (the text before the first space) is {@code scheme}, ignoring case. */
    static boolean isScheme(String header, String scheme) {
        String h = header.trim();
        int sp = h.indexOf(' ');
        return (sp < 0 ? h : h.substring(0, sp)).equalsIgnoreCase(scheme);
    }

    // @req SEC-APIKEY-001
    @Override
    public @Nullable Credential resolve(Request req) throws Exception {
        List<String> authorization = req.headers("Authorization");
        if (authorization.size() >= 2) {
            throw TransportCredentials.multiple();
        }
        if (authorization.size() == 1 && isScheme(authorization.get(0), auth.apiKeyScheme())) {
            req.attribute(Auth.KEY_REQUEST, Boolean.TRUE);
            if (auth.transport() != TokenTransport.HEADER && Auth.accessCookieCount(req) > 0) {
                throw TransportCredentials.multiple();
            }
            return verifyHeader(req, authorization.get(0));
        }
        return tokens.resolve(req);
    }

    /**
     * The credential of one key-scheme {@code Authorization} value of {@code req}, in the SEC-APIKEY-004 order: the
     * key-only per-IP limit (429 {@code too_many_attempts} before parsing or lookup), then the key itself; every 401
     * {@code invalid_api_key} counts once in that per-IP counter, on protected and public paths alike, and a success
     * never resets it. A store or throttle failure propagates (503 on a protected path, anonymous on a public one).
     */
    // @req SEC-APIKEY-004
    // @req SEC-APIKEY-005
    Credential verifyHeader(Request req, String header) throws Exception {
        LoginThrottle throttle = auth.loginThrottle();
        String ip;
        try {
            ip = req.remoteAddress();
        } catch (UnsupportedOperationException none) {
            ip = "unknown"; // a request view without an address shares one counter
        }
        String counter = ThrottleKeys.apiKeyIp(ip);
        int max = auth.apiKeyFailureMax();
        long window = auth.apiKeyFailureWindowMillis();
        long wait = throttle.retryAfter(counter, max, window);
        if (wait > 0) {
            throw DefaultAuthUsers.tooMany(wait);
        }
        try {
            return verify(header);
        } catch (AuthRejection r) {
            if (AuthErrorCode.INVALID_API_KEY.getCode().equals(r.getCode())) {
                throttle.recordFailure(counter, max, window);
            }
            throw r;
        }
    }

    /**
     * Steps 3 to 9 of SEC-APIKEY-004: parse, {@code findByPrefix} (a dummy hash for a missing prefix and a byte-wise
     * prefix re-compare), one constant-time compare, the expiry without leeway, the pending marker, the owner read
     * through the principal cache, its {@code enabled} flag and ver: equal authenticates, smaller is a stale key whose
     * deletion is scheduled off the request, greater re-reads the owner once and, still greater, is an invariant
     * violation (ERROR once a minute, deleted off the request under {@code SINGLE_INSTANCE}). The key row is read from
     * the store on every request; only the owner goes through the cache.
     */
    // @req SEC-APIKEY-004
    // @req SEC-APIKEY-006
    // @req REL-APIKEY-001
    // @req FR-APIKEY-002
    private Credential verify(String header) throws Exception {
        String h = header.trim();
        int sp = h.indexOf(' ');
        String value = sp < 0 ? "" : h.substring(sp + 1).trim();
        ApiKeyFormat.Key key = isToken68(value) ? ApiKeyFormat.parse(value) : null;
        if (key == null) { // OPS-AUTH-028 AC-4: the format is public, so naming it reveals nothing
            throw invalid("the API key is not valid: an API key has the form nk_<8-character prefix>_<32-character "
                    + "secret>, exactly as issued");
        }
        ApiKeyStore store = auth.apiKeyStore();
        if (store == null) {
            throw invalid();
        }
        StoredApiKey k = store.findByPrefix(key.prefix());
        String presented = ApiKeyFormat.secretHash(key.secret());
        String stored = k == null ? DUMMY_HASH : k.getSecretHash();
        Runnable hook = compareHook;
        if (hook != null) {
            hook.run();
        }
        boolean secretOk = ConstantTime.equals(presented.getBytes(StandardCharsets.US_ASCII),
                stored.getBytes(StandardCharsets.US_ASCII));
        if (k == null || !k.getPrefix().equals(key.prefix()) || !secretOk) {
            throw invalid();
        }
        Long exp = k.getExpiresAt();
        if (exp != null && auth.time().nowMillis() >= exp) {
            throw invalid("the API key has expired; issue a new key"); // OPS-AUTH-024 AC-9: the secret matched
        }
        String owner = k.getOwnerUserId();
        if (auth.apiKeyOwners().rejects(owner, k.getOwnerVer())) {
            throw stale();
        }
        PrincipalCache cache = auth.principalCache();
        PrincipalCache.Snapshot s = cache.get(owner);
        if (s != null && k.getOwnerVer() > s.getTokenVersion()) {
            cache.invalidate(owner); // a key issued for a newer ver than the cached one: read the owner once more
            s = cache.get(owner);
        }
        if (s == null || !s.isEnabled()) {
            throw invalid();
        }
        if (k.getOwnerVer() < s.getTokenVersion()) {
            auth.apiKeyOwners().scheduleStale(owner, s.getTokenVersion());
            throw stale();
        }
        if (k.getOwnerVer() > s.getTokenVersion()) {
            invariantViolation(k, s.getTokenVersion());
            throw invalid();
        }
        ApiKeyUsage usage = auth.apiKeyUsage();
        if (usage != null) {
            usage.used(k.getId());
        }
        Set<String> scopes = new LinkedHashSet<>();
        boolean ignored = false;
        for (String sc : k.getScopes()) {
            if (auth.allowAdminScopesOnApiKeys() || !sc.startsWith("auth-")) {
                scopes.add(sc);
            } else {
                ignored = true; // with the opt-in off, an admin scope left on a key is not judged
            }
        }
        if (ignored) {
            auth.ignoredAdminScope(k.getId());
        }
        return new Credential(owner, s.getTokenVersion(), k.getId(), scopes, exp == null ? Long.MAX_VALUE : exp);
    }

    /** A key whose ownerVer is above the owner's ver after the re-read (a store whose ver went back). */
    // @req SEC-APIKEY-006
    private void invariantViolation(StoredApiKey k, long ver) {
        long now = auth.time().nowMillis();
        long last = invariantLoggedAt.get();
        if ((last == Long.MIN_VALUE || now - last >= 60_000L || now < last) && invariantLoggedAt.compareAndSet(last, now)) {
            auth.log().error("auth: API key " + k.getId() + " carries ownerVer " + k.getOwnerVer() + " above its "
                    + "owner's ver " + ver + " (the user store's ver went back, or a replica lags); the key is refused"
                    + (auth.deployment() == Deployment.SINGLE_INSTANCE ? " and deleted" : "")
                    + " (logged at most once a minute)");
        }
        auth.apiKeyOwners().scheduleDelete(k.getId(), k.getOwnerUserId(), k.getOwnerVer());
    }

    /** Runs before every secret comparison; a test seam counting them. */
    void compareHook(@Nullable Runnable hook) {
        this.compareHook = hook;
    }

    /** RFC 9110 {@code token68}: {@code 1*( ALPHA / DIGIT / "-" / "." / "_" / "~" / "+" / "/" ) *"="}. */
    static boolean isToken68(String v) {
        int i = 0;
        int n = v.length();
        while (i < n) {
            char c = v.charAt(i);
            if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || "-._~+/".indexOf(c) >= 0) {
                i++;
            } else {
                break;
            }
        }
        if (i == 0) {
            return false;
        }
        while (i < n && v.charAt(i) == '=') {
            i++;
        }
        return i == n;
    }

    private static AuthRejection invalid() {
        return invalid("the API key is not valid");
    }

    private static AuthRejection invalid(String why) {
        return new AuthRejection(AuthErrorCode.INVALID_API_KEY.getStatus(), AuthErrorCode.INVALID_API_KEY.getCode(),
                why);
    }

    /** OPS-AUTH-024 AC-9: a key whose owner's tokens were revoked after it was issued. */
    private static AuthRejection stale() {
        return invalid("the API key is stale: its owner's tokens were revoked after it was issued (logout ?all=true, a "
                + "password change or a disabled account); issue a new key");
    }

    @Override
    public String toString() {
        return "ApiKeyCredentials";
    }
}
