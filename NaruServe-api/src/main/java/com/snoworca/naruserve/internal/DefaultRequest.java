package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.ApplicationContext;
import com.snoworca.naruserve.Cookie;
import com.snoworca.naruserve.HttpException;
import com.snoworca.naruserve.Method;
import com.snoworca.naruserve.Request;
import com.snoworca.naruserve.TypeRef;
import com.snoworca.naruserve.UploadFile;
import com.snoworca.naruserve.spi.HttpExchange;
import com.snoworca.naruserve.spi.JsonCodec;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/** {@link Request} over one exchange; built by the Dispatcher only for a matched HTTP route. */
// @req FR-HTTP-009
// @req FR-HTTP-025
// @req FR-HTTP-011
// @req FR-HTTP-012
// @req FR-JSON-001
// @req FR-COOKIE-002
// @req FR-MULTIPART-003
public final class DefaultRequest implements Request {

    private final HttpExchange ex;
    private volatile String requestId = "";
    private volatile long startNanos = System.nanoTime();
    private final Method method;
    private final Map<String, String> params;
    private final String path;
    private final ConcurrentHashMap<String, Object> attributes = new ConcurrentHashMap<>();
    private final BodyState body = new BodyState();
    private volatile @Nullable Map<String, List<String>> query;
    private volatile Cookie @Nullable [] cookies;
    private final @Nullable ApplicationContext context;
    private final @Nullable JsonCodec codec;
    private final long jsonLimit;
    private final boolean trustProxy;
    private final MultipartSettings multipart;
    private final long formLimit;
    private final SpoolRegistry spool;
    private final Object uploadLock = new Object();
    // Guarded by uploadLock.
    private @Nullable FormData parsed;
    private boolean uploadsReleased;

    /** {@code params} are the decoded variables of the RouteMatch; no application context. */
    // @req FR-HTTP-009
    public DefaultRequest(HttpExchange ex, Method method, Map<String, String> params) {
        this(ex, method, params, null);
    }

    /** As above, with the app's one context returned by {@link #application()}. */
    // @req FR-APP-007
    public DefaultRequest(HttpExchange ex, Method method, Map<String, String> params,
            @Nullable ApplicationContext context) {
        this(ex, method, params, context, null, JsonBodyReader.DEFAULT_LIMIT);
    }

    /** As above, with the app codec and JSON limit that {@link #body(Class)} uses; no codec refuses body reads. */
    // @req FR-JSON-001
    public DefaultRequest(HttpExchange ex, Method method, Map<String, String> params,
            @Nullable ApplicationContext context, @Nullable JsonCodec codec, long jsonLimit) {
        this(ex, method, params, context, codec, jsonLimit, false);
    }

    /** As above; {@code trustProxy} makes X-Forwarded-For and X-Forwarded-Proto count (app.trustProxy). */
    // @req FR-ENGINE-008
    public DefaultRequest(HttpExchange ex, Method method, Map<String, String> params,
            @Nullable ApplicationContext context, @Nullable JsonCodec codec, long jsonLimit, boolean trustProxy) {
        this(ex, method, params, context, codec, jsonLimit, trustProxy, new MultipartSettings(), 1048576L,
                new SpoolRegistry());
    }

    /** As above, with the app's multipart settings, urlencoded form limit and spool for the form accessors. */
    // @req FR-MULTIPART-003
    public DefaultRequest(HttpExchange ex, Method method, Map<String, String> params,
            @Nullable ApplicationContext context, @Nullable JsonCodec codec, long jsonLimit, boolean trustProxy,
            MultipartSettings multipart, long formLimit, SpoolRegistry spool) {
        this.multipart = Objects.requireNonNull(multipart, "multipart");
        this.formLimit = formLimit;
        this.spool = Objects.requireNonNull(spool, "spool");
        this.trustProxy = trustProxy;
        this.codec = codec;
        this.jsonLimit = jsonLimit;
        this.context = context;
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

    /**
     * Parses the raw query once; a new {@code HttpException(400)} without message on every call for bad %XX or
     * invalid UTF-8, which the error path answers.
     */
    // @req FR-HTTP-025
    void decodeQuery() {
        if (query == null) {
            try {
                query = parseQuery(ex.rawQuery());
            } catch (IllegalArgumentException e) {
                throw new HttpException(400);
            }
        }
    }

    /**
     * {@code application/x-www-form-urlencoded} pairs split on '&', name and value split on the first '='; a pair
     * without '=' has an empty value, empty names are kept and empty pairs skipped. '+' is a space.
     */
    // @req FR-HTTP-025
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

    /** The exchange body counted by {@link #body()}; only a reader that took the body calls this. */
    // @req FR-JSON-001
    InputStream bodyStream() {
        return body.stream(ex.requestBody());
    }

    // @req FR-JSON-001
    @Override
    public <T> @Nullable T body(Class<T> type) {
        return readJson(Objects.requireNonNull(type, "type"));
    }

    // @req FR-JSON-001
    @Override
    public <T> @Nullable T body(TypeRef<T> type) {
        return readJson(Objects.requireNonNull(type, "type").getType());
    }

    /** BodyState.begin(JSON), then {@link JsonBodyReader#read}; the body ends CONSUMED whatever the outcome. */
    private <T> @Nullable T readJson(Type type) {
        JsonCodec c = codec;
        if (c == null) {
            throw new IllegalStateException("this request has no JsonCodec; build the app with RestApp.builder()");
        }
        body.begin(BodyState.Kind.JSON);
        try {
            return JsonBodyReader.read(this, type, c, jsonLimit);
        } finally {
            body.complete();
        }
    }

    /**
     * The one shared parse of a form body. A body whose media type (before ';', trimmed, case-insensitive) is neither
     * urlencoded nor multipart/form-data gives EMPTY while UNREAD, without reading it; once taken, sharedParse throws
     * BodyAlreadyConsumedException. A failed parse leaves later calls with that exception too.
     */
    // @req FR-MULTIPART-003
    FormData formData() {
        String type = header("Content-Type");
        String media = type == null ? "" : MultipartParser.mediaType(type);
        boolean urlencoded = media.equals("application/x-www-form-urlencoded");
        boolean multipartBody = media.equals("multipart/form-data");
        if (!urlencoded && !multipartBody && body.state() == BodyState.State.UNREAD) {
            return FormData.EMPTY;
        }
        try {
            FormData d = body.sharedParse(() -> {
                if (urlencoded) {
                    return FormData.urlencoded(FormBodyReader.read(bodyStream(), contentLength(), formLimit));
                }
                if (!multipartBody) {
                    return FormData.EMPTY; // unreachable: sharedParse refuses a body that is not UNREAD
                }
                String boundary = MultipartParser.boundary(type);
                if (boundary == null) {
                    throw new HttpException(400, "multipart/form-data without a valid boundary");
                }
                return FormData.multipart(MultipartParser.parse(bodyStream(), contentLength(), boundary, multipart,
                        formLimit, spool));
            });
            boolean late;
            synchronized (uploadLock) {
                parsed = d;
                late = uploadsReleased;
            }
            if (late) {
                releaseUploads(); // a parse that finished after the request ended keeps no file
            }
            return d;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new HttpException(400, null, e); // an IOException reading the body, as for a JSON body
        }
    }

    /** The request ended: release() on every part of the cached parse, and on one that finishes later; idempotent. */
    // @req FR-MULTIPART-006
    void releaseUploads() {
        FormData d;
        synchronized (uploadLock) {
            uploadsReleased = true;
            d = parsed;
        }
        if (d != null) {
            for (UploadFile p : d.parts()) {
                ((DefaultUploadFile) p).release();
            }
        }
    }

    /** The Content-Length header, or -1 when absent or not a number (the adapter frames the body). */
    private long contentLength() {
        String v = header("Content-Length");
        if (v == null) {
            return -1;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // @req FR-MULTIPART-003
    @Override
    public @Nullable String form(String name) {
        Objects.requireNonNull(name, "name");
        return formData().firstText(name);
    }

    // @req FR-MULTIPART-003
    @Override
    public @Nullable UploadFile file(String name) {
        Objects.requireNonNull(name, "name");
        return formData().firstFile(name);
    }

    // @req FR-MULTIPART-003
    @Override
    public List<UploadFile> files(String name) {
        Objects.requireNonNull(name, "name");
        return formData().files(name);
    }

    // @req FR-MULTIPART-003
    @Override
    public List<UploadFile> parts() {
        return formData().parts();
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

    /** The raw query after '?', or null; the static 301 Location keeps it as sent. */
    @Nullable String rawQuery() {
        return ex.rawQuery();
    }

    /** The id the dispatcher gave this request, for automatic error bodies; empty until set. */
    String requestId() {
        return requestId;
    }

    void requestId(String id) {
        this.requestId = id;
    }

    /** The System.nanoTime() the Dispatcher took when the request arrived; the WebSocket handshake line reads it. */
    // @req FR-APP-019
    void startNanos(long nanos) {
        this.startNanos = nanos;
    }

    long startNanos() {
        return startNanos;
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

    // @req FR-HTTP-025
    @Override
    public @Nullable String query(String name) {
        List<String> values = queries(name);
        return values.isEmpty() ? null : values.get(0);
    }

    // @req FR-HTTP-025
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

    /** The adapter's value; X-Forwarded-For only under app.trustProxy(true); Forwarded never. */
    // @req FR-ENGINE-008
    @Override
    public String remoteAddress() {
        return ProxyTrust.remoteAddress(trustProxy ? header("X-Forwarded-For") : null, ex.remoteAddress());
    }

    // @req FR-ENGINE-008
    @Override
    public boolean isSecure() {
        return ProxyTrust.isSecure(trustProxy ? header("X-Forwarded-Proto") : null, ex.isSecure());
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
    @SuppressWarnings("unchecked") // a wrapper class stands for its primitive T, which is the boxed type at runtime
    public <T> @Nullable T attribute(String key, Class<T> type) {
        // Kotlin's Int::class.java is int.class, and Class.cast always fails for a primitive class.
        return (T) wrapper(type).cast(attributes.get(key));
    }

    static Class<?> wrapper(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == int.class) {
            return Integer.class;
        } else if (type == long.class) {
            return Long.class;
        } else if (type == boolean.class) {
            return Boolean.class;
        } else if (type == double.class) {
            return Double.class;
        } else if (type == float.class) {
            return Float.class;
        } else if (type == short.class) {
            return Short.class;
        } else if (type == byte.class) {
            return Byte.class;
        } else if (type == char.class) {
            return Character.class;
        }
        return Void.class;
    }

    /** {@link PercentDecoder#decode}; IllegalArgumentException for bad %XX or invalid UTF-8. */
    private static String decode(String s, boolean plusAsSpace) {
        String decoded = PercentDecoder.decode(s, plusAsSpace);
        if (decoded == null) {
            throw new IllegalArgumentException("malformed percent-encoding or invalid UTF-8");
        }
        return decoded;
    }

    /** The Cookie header lines parsed once; a race parses twice to equal results, which is harmless. */
    // @req FR-COOKIE-002
    private Cookie[] parsedCookies() {
        Cookie[] c = cookies;
        if (c == null) {
            c = CookieParser.parse(headers("Cookie"));
            cookies = c;
        }
        return c;
    }

    // @req FR-COOKIE-002
    @Override
    public @Nullable Cookie cookie(String name) {
        Objects.requireNonNull(name, "name");
        for (Cookie c : parsedCookies()) {
            if (c.getName().equals(name)) {
                return copy(c);
            }
        }
        return null;
    }

    // @req FR-COOKIE-002
    @Override
    public Cookie[] getCookies() {
        Cookie[] parsed = parsedCookies();
        Cookie[] out = new Cookie[parsed.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = copy(parsed[i]);
        }
        return out;
    }

    /** Cookie is mutable, so a reader never hands out the parsed instance: changing one leaves later reads intact. */
    private static Cookie copy(Cookie c) {
        return CookieFactoryHook.create(c.getName(), c.getValue());
    }

    /** The app's one context, identical for every request of the app. */
    // @req FR-APP-007
    @Override
    public ApplicationContext application() {
        ApplicationContext c = context;
        if (c == null) {
            throw new UnsupportedOperationException("this request was built without an application context");
        }
        return c;
    }

    // @req FR-APP-007
    @Override
    public <T> T service(Class<T> type) {
        return application().get(type);
    }
}
