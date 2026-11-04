package com.snoworca.naruserve.utils.auth.internal;

import com.snoworca.naruserve.Cookie;
import com.snoworca.naruserve.Request;
import com.snoworca.naruserve.utils.auth.jwt.JwtKeys;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The signed double-submit CSRF tokens of cookie mode (00 R29 (d)(e), 05 §7 item 1): a token is
 * {@code base64url(random16) + "." + base64url(HMAC(csrfKey, sub + "." + fam + "." + base64url(random16)))}, bound to the
 * user and refresh family of the session it was issued with, so a value planted from another family (cookie tossing)
 * never passes. A request passes when its {@code X-CSRF-Token} header equals, in constant time, one of its
 * {@code naru_csrf} cookie values whose HMAC matches the expected {@code sub} and {@code fam}; two or more
 * {@code naru_csrf} cookies pass under the same rule. The key of each kid is derived with the label
 * {@link TokenKeys#CSRF}; new tokens use the signing kid, and a token of any kid in the set still verifies.
 */
// @req SEC-FLOW-006
public final class CsrfTokens {

    /** The request header carrying the CSRF token. */
    public static final String HEADER = "X-CSRF-Token";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final HmacSigner signer;
    private final List<HmacSigner> verifiers = new ArrayList<>();

    /** The CSRF tokens of the raw key set {@code keys}. */
    // @req SEC-FLOW-006
    public CsrfTokens(JwtKeys keys) {
        HmacSigner s = null;
        for (String kid : keys.getKids()) {
            byte[] k = TokenKeys.keyFor(keys, kid, TokenKeys.CSRF);
            if (k == null) {
                continue;
            }
            HmacSigner h = new HmacSigner(k);
            verifiers.add(h);
            if (kid.equals(keys.getSigningKid())) {
                s = h;
            }
        }
        if (s == null) {
            throw new IllegalArgumentException("the key set has no signing key");
        }
        this.signer = s;
    }

    /** A new token for the session of {@code sub} in refresh family {@code fam}. */
    // @req SEC-FLOW-006
    public String issue(String sub, String fam) {
        byte[] r = new byte[16];
        RANDOM.nextBytes(r);
        String random = Base64Url.encode(r);
        return random + "." + Base64Url.encode(signer.sign(input(sub, fam, random)));
    }

    /** True when {@code token} was issued for {@code sub} and {@code fam} under a key of the set. */
    // @req SEC-FLOW-006
    public boolean matches(String token, String sub, String fam) {
        int dot = token.indexOf('.');
        if (dot <= 0 || dot != token.lastIndexOf('.') || dot == token.length() - 1) {
            return false;
        }
        String random = token.substring(0, dot);
        byte[] mac;
        try {
            if (Base64Url.decode(random).length != 16) {
                return false;
            }
            mac = Base64Url.decode(token.substring(dot + 1));
        } catch (IllegalArgumentException malformed) {
            return false;
        }
        byte[] data = input(sub, fam, random);
        boolean ok = false;
        for (HmacSigner v : verifiers) {
            ok |= v.verify(data, mac);
        }
        return ok;
    }

    /**
     * The double-submit check of {@code req} for the session {@code sub}/{@code fam}: exactly one {@code X-CSRF-Token}
     * header, equal in constant time to a {@code naru_csrf} cookie value that {@link #matches}. A null {@code fam}
     * never passes.
     */
    // @req SEC-FLOW-006
    // @req SEC-FLOW-008
    public boolean passes(Request req, String sub, @Nullable String fam) {
        List<String> headers = req.headers(HEADER);
        if (fam == null || headers.size() != 1) {
            return false;
        }
        byte[] header = headers.get(0).trim().getBytes(StandardCharsets.UTF_8);
        if (header.length == 0) {
            return false;
        }
        Cookie[] cookies;
        try {
            cookies = req.getCookies();
        } catch (UnsupportedOperationException none) {
            return false;
        }
        boolean pass = false;
        for (Cookie c : cookies) {
            if (!AuthCookies.CSRF.equals(c.getName())) {
                continue;
            }
            String value = c.getValue();
            if (ConstantTime.equals(header, value.getBytes(StandardCharsets.UTF_8)) && matches(value, sub, fam)) {
                pass = true;
            }
        }
        return pass;
    }

    private static byte[] input(String sub, String fam, String random) {
        return (sub + "." + fam + "." + random).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String toString() {
        return "CsrfTokens";
    }
}
