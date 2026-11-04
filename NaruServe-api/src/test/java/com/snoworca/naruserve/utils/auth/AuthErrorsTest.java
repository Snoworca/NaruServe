package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.TestApp;
import com.snoworca.naruserve.utils.auth.internal.ErrorBodies;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

/** AuthRejection, AuthUnavailableException and the AuthErrorCode catalog (IR-AUTH-003). */
public class AuthErrorsTest {

    private static void rejected(int status, String code) {
        try {
            new AuthRejection(status, code, null);
            fail("accepted " + status + " " + code);
        } catch (IllegalArgumentException expected) {
            // the constructor validates both values
        }
    }

    // IR-AUTH-003 AC-1: 4xx statuses and lower-case codes only; the two-argument form has no message and no retry
    @Test
    public void IR_AUTH_003_AC1_constructorValidatesStatusAndCode() {
        rejected(500, "x");
        rejected(422, "Bad-Code");
        rejected(399, "ok");
        rejected(422, "");
        rejected(422, "9lives");
        rejected(422, "a" + new String(new char[64]).replace('\0', 'b'));
        AuthRejection r = new AuthRejection(422, "domain_not_allowed");
        assertEquals(422, r.getStatus());
        assertEquals("domain_not_allowed", r.getCode());
        assertNull(r.getMessage());
        assertNull(r.getRetryAfterSeconds());
        AuthRejection longest = new AuthRejection(499, "a" + new String(new char[63]).replace('\0', '_'));
        assertEquals(64, longest.getCode().length());
        assertTrue(RuntimeException.class.isAssignableFrom(AuthRejection.class));
        AuthRejection retry = new AuthRejection(429, "too_many_attempts", "slow down", 30L);
        assertEquals(Long.valueOf(30), retry.getRetryAfterSeconds());
        assertEquals("slow down", retry.getMessage());
    }

    private static TestApp throwing(final RuntimeException error) {
        TestApp t = TestApp.create();
        t.app().get("/x", (req, res) -> {
            throw error;
        });
        t.app().onError((err, req, res) -> ErrorBodies.write(req, res, (AuthRejection) err));
        return t;
    }

    // IR-AUTH-003 AC-2: a null message is rendered with a non-empty default; retryAfterSeconds becomes Retry-After
    @Test
    public void IR_AUTH_003_AC2_nullMessageGetsADefaultAndRetryAfterIsAHeader() {
        ClientResponse plain = throwing(new AuthRejection(422, "domain_not_allowed")).send("GET", "/x");
        assertEquals(422, plain.status());
        String body = plain.text();
        assertTrue(body, body.startsWith("{\"error\":{\"code\":\"domain_not_allowed\",\"message\":\""));
        assertFalse(body, body.contains("\"message\":\"\""));
        assertFalse(body, body.contains("\"message\":null"));
        assertTrue(body, body.contains("\"requestId\":\""));
        assertTrue(plain.header("Content-Type"), plain.header("Content-Type").startsWith("application/json"));
        assertNull(plain.header("Retry-After"));

        ClientResponse retry = throwing(new AuthRejection(429, "too_many_attempts", null, 30L)).send("GET", "/x");
        assertEquals(429, retry.status());
        assertEquals("30", retry.header("Retry-After"));
        assertTrue(retry.text(), retry.text().contains("\"code\":\"too_many_attempts\""));

        ClientResponse quoted = throwing(new AuthRejection(400, "bad_name", "a \"quoted\"\nname\\")).send("GET", "/x");
        assertTrue(quoted.text(), quoted.text().contains("\"message\":\"a \\\"quoted\\\"\\nname\\\\\""));
    }

    // IR-AUTH-003 AC-3: the auth-owned catalog through the AuthErrorCode constants, nothing beyond it
    @Test
    public void IR_AUTH_003_AC3_catalogIsExactlyTheNamedPairs() {
        Map<AuthErrorCode, Object[]> named = new LinkedHashMap<>();
        named.put(AuthErrorCode.UNAUTHENTICATED, new Object[] {"unauthenticated", 401});
        named.put(AuthErrorCode.INVALID_TOKEN, new Object[] {"invalid_token", 401});
        named.put(AuthErrorCode.INVALID_API_KEY, new Object[] {"invalid_api_key", 401});
        named.put(AuthErrorCode.INVALID_REQUEST, new Object[] {"invalid_request", 400});
        named.put(AuthErrorCode.INSUFFICIENT_SCOPE, new Object[] {"insufficient_scope", 403});
        named.put(AuthErrorCode.API_KEY_NOT_ALLOWED, new Object[] {"api_key_not_allowed", 403});
        named.put(AuthErrorCode.INSECURE_TRANSPORT, new Object[] {"insecure_transport", 403});
        named.put(AuthErrorCode.CSRF_FAILED, new Object[] {"csrf_failed", 403});
        named.put(AuthErrorCode.API_KEY_LIMIT, new Object[] {"api_key_limit", 409});
        named.put(AuthErrorCode.TOO_MANY_ATTEMPTS, new Object[] {"too_many_attempts", 429});
        named.put(AuthErrorCode.SERVICE_ACCOUNT_NO_PASSWORD, new Object[] {"service_account_no_password", 400});
        named.put(AuthErrorCode.USERNAME_MISMATCH, new Object[] {"username_mismatch", 400});
        named.put(AuthErrorCode.SECURITY_WRITE_CONFLICT, new Object[] {"security_write_conflict", 503});
        named.put(AuthErrorCode.INVALID_CREDENTIALS, new Object[] {"invalid_credentials", 401});
        named.put(AuthErrorCode.INVALID_REFRESH_TOKEN, new Object[] {"invalid_refresh_token", 401});
        // the six answers decided on 2026-11-02
        named.put(AuthErrorCode.DUPLICATE_USER, new Object[] {"duplicate_user", 409});
        named.put(AuthErrorCode.WRITE_CONFLICT, new Object[] {"write_conflict", 409});
        named.put(AuthErrorCode.GROUP_CYCLE, new Object[] {"group_cycle", 409});
        named.put(AuthErrorCode.GROUP_TOO_LARGE, new Object[] {"group_too_large", 409});
        named.put(AuthErrorCode.INVALID_SETUP_TOKEN, new Object[] {"invalid_setup_token", 401});
        named.put(AuthErrorCode.SETUP_NOT_LOCAL, new Object[] {"setup_not_local", 403});
        for (Map.Entry<AuthErrorCode, Object[]> e : named.entrySet()) {
            assertEquals(e.getKey().name(), e.getValue()[0], e.getKey().getCode());
            assertEquals(e.getKey().name(), e.getValue()[1], e.getKey().getStatus());
        }
        // The wrong current password reuses the wrong-password answer (decided at implementation, docs/api/auth.md).
        assertSame(AuthErrorCode.INVALID_CREDENTIALS, AuthErrorCode.WRONG_CURRENT_PASSWORD);
        assertEquals(401, AuthErrorCode.WRONG_CURRENT_PASSWORD.getStatus());
        // The open answers (requireRole, a false auth.check, the R31 self rule) reuse insufficient_scope: no extra
        // constant, so the enum holds exactly the named pairs.
        assertEquals(EnumSet.copyOf(named.keySet()), EnumSet.allOf(AuthErrorCode.class));
        for (AuthErrorCode c : AuthErrorCode.values()) {
            assertEquals(c.name(), c.name().toLowerCase(java.util.Locale.ROOT), c.getCode());
        }
    }

    // AuthUnavailableException: the 503 carrier; its default code is the Core spelling (IR-AUTH-003 statement)
    @Test
    public void authUnavailableExceptionCarriesItsCode() {
        AuthUnavailableException plain = new AuthUnavailableException("store down");
        assertEquals(503, plain.getStatus());
        assertEquals("SERVICE_UNAVAILABLE", plain.getCode());
        IllegalStateException cause = new IllegalStateException("x");
        AuthUnavailableException wrapped = new AuthUnavailableException("store down", cause);
        assertSame(cause, wrapped.getCause());
        AuthUnavailableException conflict = new AuthUnavailableException(
                AuthErrorCode.SECURITY_WRITE_CONFLICT.getCode(), "not confirmed", null);
        assertEquals("security_write_conflict", conflict.getCode());
        for (String core : new String[] {"SERVICE_UNAVAILABLE", "HTTP_503"}) {
            assertEquals(core, new AuthUnavailableException(core, "x", null).getCode());
        }
        try {
            new AuthUnavailableException("Http_x", "x", null);
            fail("accepted a mixed-case code");
        } catch (IllegalArgumentException expected) {
            // upper-case Core spellings are all upper case
        }
        try {
            new AuthUnavailableException("Bad-Code", "x", null);
            fail("accepted a malformed code");
        } catch (IllegalArgumentException expected) {
            // same code rule as AuthRejection, upper-case Core spellings allowed
        }
    }
}
