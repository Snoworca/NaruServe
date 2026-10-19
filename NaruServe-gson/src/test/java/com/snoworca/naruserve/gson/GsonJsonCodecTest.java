package com.snoworca.naruserve.gson;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonIOException;
import com.google.gson.JsonParseException;
import com.google.gson.stream.MalformedJsonException;
import com.snoworca.naruserve.TypeRef;
import com.snoworca.naruserve.spi.JsonCodec;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.TestApp;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class GsonJsonCodecTest {

    public static final class User {
        String name = "";
        int age;

        User() {
        }

        User(String name, int age) {
            this.name = name;
            this.age = age;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof User && ((User) o).name.equals(name) && ((User) o).age == age;
        }

        @Override
        public int hashCode() {
            return name.hashCode() * 31 + age;
        }
    }

    /**
     * Only constructor takes arguments; counts constructor runs. Unsafe allocation would skip the constructor, so
     * the JsonIOException, not the counter, is what shows no instance was made.
     */
    public static final class ArgsOnly {
        static final AtomicInteger CREATED = new AtomicInteger();
        final int x;

        public ArgsOnly(int x) {
            this.x = x;
            CREATED.incrementAndGet();
        }
    }

    static final AtomicInteger CANARY_LOADS = new AtomicInteger();

    /** Static initializer records any loading. */
    public static final class Canary {
        static {
            CANARY_LOADS.incrementAndGet();
        }
    }

    private static Object decode(JsonCodec codec, String json, Type type) throws IOException {
        return codec.decode(new StringReader(json), type);
    }

    private static String nested(int depth) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            sb.append('[');
        }
        for (int i = 0; i < depth; i++) {
            sb.append(']');
        }
        return sb.toString();
    }

    private static IOException decodeFails(JsonCodec codec, String json, Type type) {
        try {
            decode(codec, json, type);
        } catch (IOException e) {
            return e;
        }
        fail("expected IOException for " + json);
        throw new AssertionError();
    }

    private static String encode(JsonCodec codec, Object value) throws IOException {
        StringWriter w = new StringWriter();
        codec.encode(value, w);
        return w.toString();
    }

    private static ClientResponse post(InMemoryHttpAdapter adapter, String path, String body) {
        return adapter.send(ClientRequest.of("POST", path).header("Content-Type", "application/json")
                .body(body.getBytes(StandardCharsets.UTF_8)));
    }

    private static String text(ClientResponse r) {
        return new String(r.body(), StandardCharsets.UTF_8);
    }

    /** An app over the in-memory adapter using {@code codec}, echoing req.body(type) through res.json. */
    private static InMemoryHttpAdapter echoApp(JsonCodec codec) {
        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
        TestApp t = TestApp.create(adapter);
        t.app().jsonCodec(codec);
        t.app().post("/user", (req, res) -> res.json(req.body(User.class)));
        t.app().post("/users", (req, res) -> res.json(req.body(new TypeRef<List<User>>() { })));
        t.app().post("/string", (req, res) -> res.json(req.body(String.class)));
        t.app().post("/int", (req, res) -> res.json(req.body(Integer.class)));
        t.app().post("/double", (req, res) -> res.json(req.body(Double.class)));
        t.app().post("/bool", (req, res) -> res.json(req.body(Boolean.class)));
        t.app().post("/map", (req, res) -> res.json(req.body(Map.class)));
        t.app().post("/object", (req, res) -> res.json(req.body(Object.class)));
        t.app().post("/argsonly", (req, res) -> res.json(req.body(ArgsOnly.class)));
        t.start();
        return adapter;
    }

    // ---- SDS-AC-1 ----

    // SDS-AC-1; FR-JSON-003 AC-1
    @Test
    public void sdsAc1_FR_JSON_003_AC1_classShapeAndConstructors() throws Exception {
        assertTrue(Modifier.isPublic(GsonJsonCodec.class.getModifiers()));
        assertTrue(Modifier.isFinal(GsonJsonCodec.class.getModifiers()));
        assertTrue(JsonCodec.class.isAssignableFrom(GsonJsonCodec.class));
        assertTrue(Modifier.isPublic(GsonJsonCodec.class.getConstructor().getModifiers()));
        assertTrue(Modifier.isPublic(GsonJsonCodec.class.getConstructor(Gson.class).getModifiers()));
        assertTrue(Modifier.isPublic(GsonJsonCodec.class.getConstructor(Gson.class, int.class).getModifiers()));
        Method defaultGson = GsonJsonCodec.class.getMethod("defaultGson");
        assertTrue(Modifier.isStatic(defaultGson.getModifiers()));
        assertEquals(Gson.class, defaultGson.getReturnType());
        assertNotNull(GsonJsonCodec.defaultGson());
    }

    // SDS-AC-1; FR-JSON-003 AC-1
    @Test
    public void sdsAc1_FR_JSON_003_AC1_nullGsonThrowsNullPointerException() {
        try {
            new GsonJsonCodec(nullGson());
            fail();
        } catch (NullPointerException expected) {
            // ok
        }
        try {
            new GsonJsonCodec(nullGson(), 10);
            fail();
        } catch (NullPointerException expected) {
            // ok
        }
    }

    @SuppressWarnings("NullAway")
    private static Gson nullGson() {
        return null;
    }

    // SDS-AC-1; FR-JSON-003 AC-1
    @Test
    public void sdsAc1_FR_JSON_003_AC1_depthOutOfRangeThrowsIllegalArgumentNamingMaxDepth() {
        for (int bad : new int[] {0, 256, -1}) {
            try {
                new GsonJsonCodec(new Gson(), bad);
                fail("maxDepth " + bad);
            } catch (IllegalArgumentException e) {
                assertTrue(e.getMessage(), e.getMessage().contains("maxDepth"));
            }
        }
    }

    // SDS-AC-1; FR-JSON-003 AC-1
    @Test
    public void sdsAc1_FR_JSON_003_AC1_depthBoundsOneAnd255Accepted() throws IOException {
        GsonJsonCodec one = new GsonJsonCodec(GsonJsonCodec.defaultGson(), 1);
        assertEquals(Collections.emptyList(), decode(one, "[]", List.class));
        decodeFails(one, "[[]]", List.class);
        new GsonJsonCodec(GsonJsonCodec.defaultGson(), 255);
    }

    // ---- SDS-AC-2 ----

    // SDS-AC-2; FR-JSON-003 AC-2
    @Test
    public void sdsAc2_FR_JSON_003_AC2_defaultRefusesUnquotedNamesSingleQuotesAndComments() {
        GsonJsonCodec codec = new GsonJsonCodec();
        for (String json : new String[] {"{a:1}", "{'a':1}", "{\"a\":1 /* c */}"}) {
            IOException e = decodeFails(codec, json, Map.class);
            assertTrue(json + " cause " + e.getCause(), e.getCause() instanceof MalformedJsonException
                    || e.getCause() instanceof JsonParseException);
        }
    }

    // SDS-AC-2; FR-JSON-003 AC-2
    @Test
    public void sdsAc2_FR_JSON_003_AC2_defaultGsonIsStrictPerCallAndUnsafeFree() {
        assertTrue(GsonJsonCodec.defaultGson() != GsonJsonCodec.defaultGson());
        // fromJson reads leniently unless the Gson itself is STRICT, so this checks defaultGson, not the codec.
        try {
            GsonJsonCodec.defaultGson().fromJson("{a:1}", Map.class);
            fail();
        } catch (com.google.gson.JsonSyntaxException expected) {
            // ok
        }
        // disableJdkUnsafe: a class without a no-arg constructor cannot be created.
        try {
            GsonJsonCodec.defaultGson().fromJson("{}", ArgsOnly.class);
            fail();
        } catch (JsonIOException expected) {
            // ok
        }
    }

    // SDS-AC-2; FR-JSON-003 AC-2
    @Test
    public void sdsAc2_FR_JSON_003_AC2_lenientConstructsThroughReqBodyAre400() {
        InMemoryHttpAdapter adapter = echoApp(new GsonJsonCodec());
        for (String json : new String[] {"{a:1}", "{'a':1}", "{\"name\":\"a\" /* c */}"}) {
            ClientResponse r = post(adapter, "/user", json);
            assertEquals(json, 400, r.status());
            assertTrue(text(r), text(r).contains("BAD_REQUEST"));
        }
    }

    // ---- SDS-AC-3 ----

    // SDS-AC-3; FR-JSON-003 AC-3
    @Test
    public void sdsAc3_FR_JSON_003_AC3_argsOnlyClassFailsUncheckedAndCreatesNoInstance() throws IOException {
        ArgsOnly.CREATED.set(0);
        try {
            decode(new GsonJsonCodec(), "{}", ArgsOnly.class);
            fail();
        } catch (JsonIOException expected) {
            // ok: unchecked, not IOException
        }
        assertEquals(0, ArgsOnly.CREATED.get());
    }

    // SDS-AC-3; FR-JSON-003 AC-3
    @Test
    public void sdsAc3_FR_JSON_003_AC3_argsOnlyClassThroughReqBodyIs500() {
        ArgsOnly.CREATED.set(0);
        ClientResponse r = post(echoApp(new GsonJsonCodec()), "/argsonly", "{}");
        assertEquals(500, r.status());
        assertEquals(0, ArgsOnly.CREATED.get());
    }

    // ---- SDS-AC-4 ----

    // SDS-AC-4; FR-JSON-003 AC-4
    @Test
    public void sdsAc4_FR_JSON_003_AC4_default64DecodesAnd65Fails() throws IOException {
        GsonJsonCodec codec = new GsonJsonCodec();
        assertNotNull(decode(codec, nested(64), Object.class));
        decodeFails(codec, nested(65), Object.class);
    }

    // SDS-AC-4; FR-JSON-003 AC-4
    @Test
    public void sdsAc4_FR_JSON_003_AC4_customGsonKeeps64() throws IOException {
        Gson custom = new GsonBuilder().create();
        GsonJsonCodec codec = new GsonJsonCodec(custom);
        assertNotNull(decode(codec, nested(64), Object.class));
        decodeFails(codec, nested(65), Object.class);
    }

    // SDS-AC-4; FR-JSON-003 AC-4
    @Test
    public void sdsAc4_FR_JSON_003_AC4_depth10Decodes10AndRejects11() throws IOException {
        GsonJsonCodec codec = new GsonJsonCodec(new Gson(), 10);
        assertNotNull(decode(codec, nested(10), Object.class));
        decodeFails(codec, nested(11), Object.class);
        // the limit is set on every decode, so repeated calls keep it
        decodeFails(codec, nested(11), Object.class);
    }

    // FR-JSON-001 AC-5
    @Test
    public void FR_JSON_001_AC5_defaultCodecThroughReqBodyRejects65AndDecodes64() {
        InMemoryHttpAdapter adapter = echoApp(new GsonJsonCodec());
        ClientResponse deep = post(adapter, "/object", nested(65));
        assertEquals(400, deep.status());
        assertTrue(text(deep), text(deep).contains("BAD_REQUEST"));
        ClientResponse ok = post(adapter, "/object", nested(64));
        assertEquals(200, ok.status());
        assertEquals(nested(64), text(ok));
    }

    // ---- SDS-AC-5 ----

    // SDS-AC-5; FR-JSON-003 AC-5
    @Test
    public void sdsAc5_FR_JSON_003_AC5_trailingDataThrowsIOException() {
        GsonJsonCodec codec = new GsonJsonCodec();
        decodeFails(codec, "{\"a\":1} x", Map.class);
        decodeFails(codec, "{} {}", Map.class);
    }

    // SDS-AC-5; FR-JSON-003 AC-5
    @Test
    public void sdsAc5_FR_JSON_003_AC5_trailingDataWithLenientCallerGsonThrowsIOException() {
        Gson lenient = new GsonBuilder().setStrictness(com.google.gson.Strictness.LENIENT).create();
        GsonJsonCodec codec = new GsonJsonCodec(lenient);
        for (String json : new String[] {"{\"a\":1} x", "{} {}"}) {
            IOException e = decodeFails(codec, json, Map.class);
            assertTrue(json + " " + e.getCause(), e.getCause() instanceof JsonParseException
                    || e.getCause() instanceof MalformedJsonException);
        }
    }

    // SDS-AC-5; FR-JSON-003 AC-5
    @Test
    public void sdsAc5_FR_JSON_003_AC5_nullDocumentReturnsNull() throws IOException {
        GsonJsonCodec codec = new GsonJsonCodec();
        assertNull(decode(codec, "null", User.class));
        assertNull(decode(codec, " null ", User.class));
        assertNull(decode(codec, " null ", new TypeRef<List<User>>() { }.getType()));
    }

    // FR-JSON-001 AC-3
    @Test
    public void FR_JSON_001_AC3_defaultCodecThroughReqBodyRejectsTrailingData() {
        InMemoryHttpAdapter adapter = echoApp(new GsonJsonCodec());
        for (String json : new String[] {"{\"a\":1} x", "{} {}"}) {
            ClientResponse r = post(adapter, "/map", json);
            assertEquals(json, 400, r.status());
            assertTrue(text(r), text(r).contains("BAD_REQUEST"));
        }
    }

    // FR-JSON-001: Gson's reader skips one leading UTF-8 BOM, so the default codec accepts it, as docs/api/request.md states
    @Test
    public void FR_JSON_001_bomPrefixedBodyIsDecodedByTheDefaultCodec() {
        InMemoryHttpAdapter adapter = echoApp(new GsonJsonCodec());
        ClientResponse r = post(adapter, "/map", "\uFEFF{\"a\":1}");
        assertEquals(200, r.status());
        assertEquals(com.google.gson.JsonParser.parseString("{\"a\":1.0}"), com.google.gson.JsonParser.parseString(text(r)));
        assertEquals(400, post(adapter, "/map", "\uFEFF\uFEFF{}").status());
    }

    // ---- SDS-AC-6 ----

    // SDS-AC-6; FR-JSON-003 AC-6
    @Test
    public void sdsAc6_FR_JSON_003_AC6_wrongJsonTypeGivesIOExceptionWithGsonCause() {
        GsonJsonCodec codec = new GsonJsonCodec();
        IOException e = decodeFails(codec, "\"x\"", User.class);
        assertTrue(String.valueOf(e.getCause()), e.getCause() instanceof JsonParseException);
        IOException list = decodeFails(codec, "{}", new TypeRef<List<User>>() { }.getType());
        assertTrue(String.valueOf(list.getCause()), list.getCause() instanceof JsonParseException);
        IOException number = decodeFails(codec, "\"abc\"", Integer.class);
        assertTrue(String.valueOf(number.getCause()), number.getCause() instanceof JsonParseException);
        for (Type t : new Type[] {Double.class, Float.class}) {
            IOException floating = decodeFails(codec, "\"abc\"", t);
            assertTrue(t + " " + floating.getCause(), floating.getCause() instanceof JsonParseException);
        }
        IOException field = decodeFails(codec, "{\"x\":\"abc\"}", DoubleField.class);
        assertTrue(String.valueOf(field.getCause()), field.getCause() instanceof JsonParseException);
    }

    public static final class DoubleField {
        double x;
    }

    // SDS-AC-6; FR-JSON-003 AC-6
    @Test
    public void sdsAc6_FR_JSON_003_AC6_wrongNumberTypeThroughReqBodyIs400() {
        InMemoryHttpAdapter adapter = echoApp(new GsonJsonCodec());
        for (String path : new String[] {"/double", "/int"}) {
            ClientResponse r = post(adapter, path, "\"abc\"");
            assertEquals(path, 400, r.status());
            assertTrue(text(r), text(r).contains("BAD_REQUEST"));
        }
    }

    // SDS-AC-6; FR-JSON-003 AC-6
    @Test
    public void sdsAc6_FR_JSON_003_AC6_syntaxNestingAndTrailingFailuresCarryGsonCause() {
        GsonJsonCodec codec = new GsonJsonCodec();
        for (String json : new String[] {"{\"a\":", "[1,]", "{a:1}", nested(65), "{} {}"}) {
            IOException e = decodeFails(codec, json, Object.class);
            assertNotNull(json, e.getCause());
            assertTrue(json + " " + e.getCause(), e.getCause().getClass().getName().startsWith("com.google.gson"));
        }
    }

    // SDS-AC-6; FR-JSON-003 AC-6
    @Test
    public void sdsAc6_FR_JSON_003_AC6_noGsonTypeInMethodSignatures() {
        for (Method m : GsonJsonCodec.class.getDeclaredMethods()) {
            if (m.getName().equals("defaultGson") || m.isSynthetic() || !Modifier.isPublic(m.getModifiers())) {
                continue;
            }
            List<Class<?>> types = new ArrayList<>(Arrays.asList(m.getParameterTypes()));
            types.add(m.getReturnType());
            types.addAll(Arrays.asList(m.getExceptionTypes()));
            for (Class<?> c : types) {
                assertFalse(m + " exposes " + c, c.getName().startsWith("com.google.gson"));
            }
        }
        for (Constructor<?> c : GsonJsonCodec.class.getConstructors()) {
            for (Class<?> e : c.getExceptionTypes()) {
                assertFalse(c + " throws " + e, e.getName().startsWith("com.google.gson"));
            }
        }
    }

    // ---- SDS-AC-7 ----

    // SDS-AC-7; FR-JSON-003 AC-7
    @Test
    public void sdsAc7_FR_JSON_003_AC7_encodeNullStringAndList() throws IOException {
        GsonJsonCodec codec = new GsonJsonCodec();
        try {
            codec.encode("x", new java.io.Writer() {
                @Override
                public void write(char[] b, int off, int len) throws IOException {
                    throw new IOException("boom");
                }

                @Override
                public void flush() {
                }

                @Override
                public void close() {
                }
            });
            fail();
        } catch (IOException e) {
            assertEquals("boom", e.getMessage());
        }
        assertEquals("null", encode(codec, null));
        assertEquals("\"hello\"", encode(codec, "hello"));
        assertEquals("[{\"name\":\"a\",\"age\":1},{\"name\":\"b\",\"age\":2}]",
                encode(codec, Arrays.asList(new User("a", 1), new User("b", 2))));
    }

    // SDS-AC-7; FR-JSON-003 AC-7
    @Test
    public void sdsAc7_FR_JSON_003_AC7_encodeUsesConfiguredGson() throws IOException {
        Gson nulls = new GsonBuilder().serializeNulls().create();
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("a", null);
        assertEquals("{\"a\":null}", encode(new GsonJsonCodec(nulls), m));
        assertEquals("{}", encode(new GsonJsonCodec(), m));
    }

    // ---- FR-JSON-001 AC-7 / FR-JSON-002 AC-2 (default codec end to end) ----

    @Test
    public void FR_JSON_001_AC7_polymorphicTypeKeysNeverLoadTheNamedClass() throws IOException {
        String canary = Canary.class.getName();
        String json = "{\"@class\":\"" + canary + "\",\"class\":\"" + canary + "\",\"@type\":\"" + canary + "\"}";
        int before = CANARY_LOADS.get();
        Object decoded = decode(new GsonJsonCodec(), json, Object.class);
        assertTrue(String.valueOf(decoded), decoded instanceof Map);
        ClientResponse r = post(echoApp(new GsonJsonCodec()), "/object", json);
        assertEquals(200, r.status());
        assertEquals(before, CANARY_LOADS.get());
    }

    @Test
    public void FR_JSON_002_AC2_echoRoundTripsValuesThroughTheDefaultCodec() throws IOException {
        InMemoryHttpAdapter adapter = echoApp(new GsonJsonCodec());
        Gson gson = new Gson();
        String user = "{\"name\":\"kim\",\"age\":30}";
        assertEquals(gson.fromJson(user, User.class), gson.fromJson(text(post(adapter, "/user", user)), User.class));
        String users = "[{\"name\":\"a\",\"age\":1},{\"name\":\"b\",\"age\":2}]";
        assertEquals(users, text(post(adapter, "/users", users)));
        assertEquals("\"x\"", text(post(adapter, "/string", "\"x\"")));
        assertEquals("42", text(post(adapter, "/int", "42")));
        assertEquals("1.5", text(post(adapter, "/double", "1.5")));
        assertEquals("true", text(post(adapter, "/bool", "true")));
        ClientResponse nul = post(adapter, "/user", "null");
        assertEquals(200, nul.status());
        assertEquals("null", text(nul));
        // untyped Map decodes numbers as Double: compare as JSON values
        assertEquals(com.google.gson.JsonParser.parseString("{\"n\":42.0}"),
                com.google.gson.JsonParser.parseString(text(post(adapter, "/map", "{\"n\":42}"))));
    }

    // FR-JSON-003: a user JsonDeserializer rejecting input with JsonParseException is bad input, so IOException
    @Test
    public void FR_JSON_003_deserializerJsonParseExceptionBecomesIOException() {
        Gson gson = new GsonBuilder().registerTypeAdapter(User.class,
                (com.google.gson.JsonDeserializer<User>) (json, t, ctx) -> {
                    throw new JsonParseException("bad");
                }).create();
        try {
            new GsonJsonCodec(gson).decode(new StringReader("{}"), User.class);
            fail("expected IOException");
        } catch (IOException e) {
            assertTrue(String.valueOf(e.getCause()), e.getCause() instanceof JsonParseException);
        }
    }
}
