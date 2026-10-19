package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.Cookie;
import java.util.ArrayList;
import java.util.List;

/**
 * Lenient parser of the request {@code Cookie} header lines: never throws, skips a pair without '=' or whose name is
 * not a token, and keeps each value exactly as sent (no quote stripping, no percent-decoding), so
 * {@code res.addCookie(req.cookie("x"))} round-trips.
 */
// @req FR-COOKIE-002
final class CookieParser {

    private CookieParser() {
    }

    /** Every name=value pair of {@code headerValues} in header order, duplicates kept. */
    // @req FR-COOKIE-002
    static Cookie[] parse(List<String> headerValues) {
        List<Cookie> out = new ArrayList<>();
        for (String header : headerValues) {
            for (String raw : header.split(";", -1)) {
                String pair = raw.trim();
                int eq = pair.indexOf('=');
                if (eq < 0) {
                    continue;
                }
                // Like Express's cookie parser, whitespace around '=' is not part of the name or the value.
                String name = pair.substring(0, eq).trim();
                if (DefaultResponse.isToken(name)) {
                    out.add(CookieFactoryHook.create(name, pair.substring(eq + 1).trim()));
                }
            }
        }
        return out.toArray(new Cookie[0]);
    }
}
