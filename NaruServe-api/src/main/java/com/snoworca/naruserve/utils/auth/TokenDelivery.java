package com.snoworca.naruserve.utils.auth;

import com.snoworca.naruserve.Logger;
import com.snoworca.naruserve.Request;
import com.snoworca.naruserve.Response;
import com.snoworca.naruserve.utils.auth.internal.AuthCookies;
import com.snoworca.naruserve.utils.auth.internal.CsrfTokens;
import com.snoworca.naruserve.utils.auth.internal.MiniJson;
import com.snoworca.naruserve.utils.auth.internal.Origins;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;

/**
 * The one writer of token-issuing responses (00 R29 (e)): login, register, setup, refresh and password call it with the
 * delivery the rule chose. Body delivery puts {@code accessToken}, {@code refreshToken}, their lifetimes,
 * {@code tokenType} and, when issued, {@code deviceToken} in the JSON body and sets no cookie. Cookie delivery sets
 * {@code naru_at}, {@code naru_rt}, a new {@code naru_csrf} bound to the pair's user and family and, when issued,
 * {@code naru_device}, and the body carries only {@code csrfToken}. Every such response has
 * {@code Cache-Control: no-store}. Under {@code .allowInsecureTransport(reason)} a cookie delivery over plain text logs
 * once that the auth cookies lack Secure. The endpoints that call it arrive with them.
 */
// @req FR-FLOW-002
// @req SEC-FLOW-006
// @req SEC-FLOW-002
final class TokenDelivery {

    private final AuthCookies cookies;
    private final CsrfTokens csrf;
    private final boolean insecureOptIn;
    private final AtomicBoolean insecureWarned = new AtomicBoolean();

    TokenDelivery(AuthCookies cookies, CsrfTokens csrf, boolean insecureOptIn) {
        this.cookies = cookies;
        this.csrf = csrf;
        this.insecureOptIn = insecureOptIn;
    }

    /**
     * The delivery rule: HEADER always body, COOKIE always cookies; BOTH by how the presented credential arrived for
     * refresh and password ({@code credentialByCookie} non-null), else by Origin (or Referer without one) for login,
     * register and setup: the request's own origin or a trusted origin means cookies, anything else (no Origin, an
     * untrusted one, {@code null}) body.
     */
    // @req FR-FLOW-002
    static boolean byCookies(TokenTransport transport, Request req, Set<String> trustedOrigins,
            @Nullable Boolean credentialByCookie) {
        if (transport == TokenTransport.HEADER) {
            return false;
        }
        if (transport == TokenTransport.COOKIE) {
            return true;
        }
        if (credentialByCookie != null) {
            return credentialByCookie;
        }
        return Origins.passes(req, trustedOrigins);
    }

    /** Writes the token response {@code status} for {@code pair} and the optional device token. */
    // @req FR-FLOW-002
    void deliver(Request req, Response res, int status, TokenPair pair, @Nullable String deviceToken,
            boolean byCookies, Logger logger) {
        res.header("Cache-Control", "no-store");
        Map<String, Object> body = new LinkedHashMap<>();
        if (byCookies) {
            cookies.setAccess(req, res, pair.getAccessToken(), seconds(pair.getExpiresIn()));
            cookies.setRefresh(req, res, pair.getRefreshToken(), seconds(pair.getRefreshExpiresIn()));
            String c = csrf.issue(pair.userId(), pair.familyId());
            cookies.setCsrf(req, res, c, seconds(pair.getRefreshExpiresIn()));
            if (deviceToken != null) {
                cookies.setDevice(req, res, deviceToken);
            }
            body.put("csrfToken", c);
            if (insecureOptIn && !req.isSecure() && insecureWarned.compareAndSet(false, true)) {
                logger.warn("auth: the auth cookies (naru_at, naru_rt, naru_csrf) are sent without the Secure "
                        + "attribute over plain text (.allowInsecureTransport); browsers send them in clear text");
            }
        } else {
            body.put("accessToken", pair.getAccessToken());
            body.put("tokenType", pair.getTokenType());
            body.put("expiresIn", pair.getExpiresIn());
            body.put("refreshToken", pair.getRefreshToken());
            body.put("refreshExpiresIn", pair.getRefreshExpiresIn());
            if (deviceToken != null) {
                body.put("deviceToken", deviceToken);
            }
        }
        res.status(status).type("application/json; charset=utf-8").send(MiniJson.write(body));
    }

    private static int seconds(long s) {
        return (int) Math.min(Integer.MAX_VALUE, s);
    }
}
