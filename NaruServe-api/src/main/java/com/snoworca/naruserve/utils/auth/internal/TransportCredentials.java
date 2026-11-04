package com.snoworca.naruserve.utils.auth.internal;

import com.snoworca.naruserve.Cookie;
import com.snoworca.naruserve.Request;
import com.snoworca.naruserve.utils.auth.AuthErrorCode;
import com.snoworca.naruserve.utils.auth.AuthRejection;
import com.snoworca.naruserve.utils.auth.TokenTransport;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Reads the access credential of a request as the effective {@link TokenTransport} says (00 R29 (g)): HEADER reads only
 * {@code Authorization: Bearer}, COOKIE only the {@code naru_at} cookie, BOTH both. A credential the mode does not read
 * is absent. Two or more {@code Authorization} headers, two or more {@code naru_at} cookies, or (BOTH) a Bearer token
 * together with a {@code naru_at} cookie are multiple credentials: 400 {@code invalid_request}. The refresh cookie
 * {@code naru_rt} is never an access credential. Without API keys an {@code Authorization} header of another scheme is
 * no credential.
 */
// @req SEC-FLOW-004
public final class TransportCredentials implements CredentialResolver {

    /** The access-token cookie. */
    public static final String ACCESS_COOKIE = "naru_at";

    private final TokenTransport transport;
    private final TokenVerifier verifier;

    /** Reading as {@code transport}, verifying with {@code verifier}. */
    // @req SEC-FLOW-004
    public TransportCredentials(TokenTransport transport, TokenVerifier verifier) {
        this.transport = transport;
        this.verifier = verifier;
    }

    /** 400 {@code invalid_request} for a request carrying more than one credential. */
    // @req SEC-FLOW-004
    public static AuthRejection multiple() {
        return new AuthRejection(AuthErrorCode.INVALID_REQUEST.getStatus(), AuthErrorCode.INVALID_REQUEST.getCode(),
                "the request carries more than one credential");
    }

    /** True when {@code req} carries two or more {@code Authorization} headers (multiple in every mode). */
    // @req SEC-FLOW-004
    public static boolean duplicateAuthorization(Request req) {
        return req.headers("Authorization").size() >= 2;
    }

    /** True when {@code req} carries a {@code naru_at} cookie (the cookie-delivered access credential). */
    // @req SEC-FLOW-006
    public static boolean hasAccessCookie(Request req) {
        for (Cookie c : cookies(req)) {
            if (ACCESS_COOKIE.equals(c.getName())) {
                return true;
            }
        }
        return false;
    }

    // @req SEC-FLOW-004
    @Override
    public @Nullable Credential resolve(Request req) throws Exception {
        List<String> authorization = req.headers("Authorization");
        if (authorization.size() >= 2) {
            throw multiple();
        }
        String bearer = null;
        if (transport != TokenTransport.COOKIE && authorization.size() == 1) {
            bearer = bearerToken(authorization.get(0));
        }
        String cookie = null;
        if (transport != TokenTransport.HEADER) {
            int count = 0;
            for (Cookie c : cookies(req)) {
                if (ACCESS_COOKIE.equals(c.getName())) {
                    count++;
                    cookie = c.getValue();
                }
            }
            if (count >= 2) {
                throw multiple();
            }
        }
        if (bearer != null && cookie != null) {
            throw multiple();
        }
        String token = bearer != null ? bearer : cookie;
        return token == null ? null : verifier.verify(token);
    }

    private static Cookie[] cookies(Request req) {
        try {
            return req.getCookies();
        } catch (UnsupportedOperationException none) {
            return new Cookie[0];
        }
    }

    /** The token of a {@code Bearer} header (scheme case-insensitive), "" for a Bearer header without one, else null. */
    private static @Nullable String bearerToken(String header) {
        String h = header.trim();
        int sp = h.indexOf(' ');
        String scheme = sp < 0 ? h : h.substring(0, sp);
        if (!scheme.equalsIgnoreCase("Bearer")) {
            return null;
        }
        return sp < 0 ? "" : h.substring(sp + 1).trim();
    }
}
