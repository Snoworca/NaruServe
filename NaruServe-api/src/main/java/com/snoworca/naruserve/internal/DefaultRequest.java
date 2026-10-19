package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.Method;
import com.snoworca.naruserve.Request;
import com.snoworca.naruserve.spi.HttpExchange;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/** {@link Request} over one exchange; built by the Dispatcher only for a matched HTTP route. */
// @req FR-HTTP-009
// @req FR-HTTP-010
// @req FR-HTTP-011
// @req FR-HTTP-012
public final class DefaultRequest implements Request {

    private final HttpExchange ex;
    private final Method method;
    private final Map<String, String> params;
    private final String path;
    private final ConcurrentHashMap<String, Object> attributes = new ConcurrentHashMap<>();
    private final BodyState body = new BodyState();
    private volatile @Nullable Map<String, List<String>> query;

    /** {@code params} are the decoded variables of the RouteMatch. */
    // @req FR-HTTP-009
    public DefaultRequest(HttpExchange ex, Method method, Map<String, String> params) {
        this.ex = ex;
        this.method = method;
        this.params = params;
        this.path = decodePath(ex.rawPath());
    }

    /** One UTF-8 percent-decoding pass; '+' stays '+' and no slash, trailing-slash or case policy applies. */
    // @req FR-HTTP-009
    static String decodePath(String rawPath) {
        return decode(rawPath, false);
    }

    /** Parses the raw query once; IllegalArgumentException for bad %XX or invalid UTF-8 (the Dispatcher answers 400). */
    // @req FR-HTTP-010
    void decodeQuery() {
        if (query == null) {
            query = parseQuery(ex.rawQuery());
        }
    }

    /**
     * {@code application/x-www-form-urlencoded} pairs split on '&', name and value split on the first '='; a pair
     * without '=' has an empty value, empty names are kept and empty pairs skipped. '+' is a space.
     */
    // @req FR-HTTP-010
    static Map<String, List<String>> parseQuery(@Nullable String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, List<String>> map = new LinkedHashMap<>();
        for (String pair : rawQuery.split("&", -1)) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String name = decode(eq < 0 ? pair : pair.substring(0, eq), true);
            String value = eq < 0 ? "" : decode(pair.substring(eq + 1), true);
            List<String> values = map.get(name);
            if (values == null) {
                values = new ArrayList<>(1);
                map.put(name, values);
            }
            values.add(value);
        }
        for (Map.Entry<String, List<String>> e : map.entrySet()) {
            e.setValue(Collections.unmodifiableList(e.getValue()));
        }
        return Collections.unmodifiableMap(map);
    }

    /** Package-visible owner of the body for the readers later waves add. */
    // @req FR-HTTP-019
    BodyState body() {
        return body;
    }

    @Override
    public Method method() {
        return method;
    }

    @Override
    public String path() {
        return path;
    }

    @Override
    public String rawPath() {
        return ex.rawPath();
    }

    // @req FR-HTTP-009
    @Override
    public String param(String name) {
        String value = params.get(name);
        if (value == null) {
            throw new IllegalArgumentException("no path parameter '" + name + "' in the matched pattern");
        }
        return value;
    }

    // @req FR-HTTP-010
    @Override
    public @Nullable String query(String name) {
        List<String> values = queries(name);
        return values.isEmpty() ? null : values.get(0);
    }

    // @req FR-HTTP-010
    @Override
    public List<String> queries(String name) {
        decodeQuery();
        Map<String, List<String>> q = query;
        List<String> values = q == null ? null : q.get(name);
        return values == null ? Collections.<String>emptyList() : values;
    }

    // @req FR-HTTP-011
    @Override
    public @Nullable String header(String name) {
        for (Map.Entry<String, String> h : ex.headers()) {
            if (h.getKey().equalsIgnoreCase(name)) {
                return h.getValue();
            }
        }
        return null;
    }

    // @req FR-HTTP-011
    @Override
    public List<String> headers(String name) {
        List<String> values = null;
        for (Map.Entry<String, String> h : ex.headers()) {
            if (h.getKey().equalsIgnoreCase(name)) {
                if (values == null) {
                    values = new ArrayList<>(2);
                }
                values.add(h.getValue());
            }
        }
        return values == null ? Collections.<String>emptyList() : Collections.unmodifiableList(values);
    }

    /** The adapter's value; X-Forwarded-* and Forwarded are not consulted. */
    @Override
    public String remoteAddress() {
        return ex.remoteAddress();
    }

    @Override
    public boolean isSecure() {
        return ex.isSecure();
    }

    @Override
    public @Nullable String lastEventId() {
        return header("Last-Event-ID");
    }

    // @req FR-HTTP-012
    @Override
    public void attribute(String key, @Nullable Object value) {
        if (value == null) {
            attributes.remove(key);
        } else {
            attributes.put(key, value);
        }
    }

    // @req FR-HTTP-012
    @Override
    public <T> @Nullable T attribute(String key, Class<T> type) {
        return type.cast(attributes.get(key));
    }

    /** One strict UTF-8 percent-decoding pass; '+' becomes a space when {@code plusAsSpace}. */
    private static String decode(String s, boolean plusAsSpace) {
        if (s.indexOf('%') < 0) {
            return plusAsSpace ? s.replace('+', ' ') : s;
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '%') {
                int hi = i + 2 < s.length() ? hex(s.charAt(i + 1)) : -1;
                int lo = hi < 0 ? -1 : hex(s.charAt(i + 2));
                if (lo < 0) {
                    throw new IllegalArgumentException("malformed percent-encoding at offset " + i);
                }
                bytes.write(hi * 16 + lo);
                i += 3;
            } else {
                int next = s.indexOf('%', i);
                int end = next < 0 ? s.length() : next;
                String run = s.substring(i, end);
                byte[] b = (plusAsSpace ? run.replace('+', ' ') : run).getBytes(StandardCharsets.UTF_8);
                bytes.write(b, 0, b.length);
                i = end;
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray()))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("percent-encoded bytes are not valid UTF-8", e);
        }
    }

    /** ASCII hex digit value, or -1; Character.digit would also take non-ASCII digits. */
    private static int hex(char c) {
        return c < 0x80 ? Character.digit(c, 16) : -1;
    }
}
