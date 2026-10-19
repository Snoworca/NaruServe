package com.snoworca.naruserve;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.spi.JsonCodec;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.TestApp;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.Before;
import org.junit.Test;

public class JsonBodyAcceptanceTest {

    /**
     * Test codec without Gson: reads the whole document; {@code null} decodes to null, the texts in {@link #rejected}
     * are malformed ({@link IOException}), any other text decodes as itself into {@code String}. Encodes null,
     * numbers and booleans as literals and a String as a quoted string without escaping.
     */
    public static final class TextCodec implements JsonCodec {
        public final AtomicInteger decodes = new AtomicInteger();
        public final AtomicInteger encodes = new AtomicInteger();
        public final Set<String> rejected = new HashSet<>(Arrays.asList("{\"a\":", "[1,]"));

        public static String readAll(Reader reader) throws IOException {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[1024];
            int n;
            while ((n = reader.read(buf)) != -1) {
                sb.append(buf, 0, n);
            }
            return sb.toString();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> @Nullable T decode(Reader reader, Type type) throws IOException {
            decodes.incrementAndGet();
            String text = readAll(reader);
            if (text.trim().equals("null")) {
                return null;
            }
            if (rejected.contains(text)) {
                throw new IOException("malformed JSON: " + text);
            }
            if (type != String.class) {
                throw new IOException("TextCodec decodes only into String: " + type);
            }
            return (T) text;
        }

        @Override
        public void encode(@Nullable Object value, Writer writer) throws IOException {
            encodes.incrementAndGet();
            if (value == null || value instanceof Number || value instanceof Boolean) {
                writer.write(String.valueOf(value));
            } else if (value instanceof String) {
                writer.write("\"" + value + "\"");
            } else {
                throw new IOException("TextCodec cannot encode " + value.getClass());
            }
        }
    }

    /** Wraps every IOException of the stream as Gson does: in another IOException, or unchecked. */
    static final class WrappingCodec implements JsonCodec {
        final boolean unchecked;

        WrappingCodec(boolean unchecked) {
            this.unchecked = unchecked;
        }

        @Override
        public <T> @Nullable T decode(Reader reader, Type type) throws IOException {
            try {
                TextCodec.readAll(reader);
            } catch (IOException e) {
                if (unchecked) {
                    throw new IllegalStateException("syntax error", e);
                }
                throw new IOException("syntax error", e);
            }
            throw new IOException("WrappingCodec only reads");
        }

        @Override
        public void encode(@Nullable Object value, Writer writer) throws IOException {
            writer.write("null");
        }
    }

    static final class User {
    }

    private InMemoryHttpAdapter adapter;
    private TestApp t;
    private TextCodec codec;

    @Before
    public void setUp() {
        adapter = new InMemoryHttpAdapter();
        t = TestApp.create(adapter);
        codec = new TextCodec();
        t.app().jsonCodec(codec);
        t.app().post("/b", (req, res) -> {
            String v = req.body(String.class);
            res.send(v == null ? "<null>" : "text:" + v);
        });
        t.app().post("/user", (req, res) -> {
            User u = req.body(User.class);
            res.send(u == null ? "<null>" : "user");
        });
        t.app().post("/list", (req, res) -> {
            List<User> l = req.body(new TypeRef<List<User>>() {
            });
            res.send(l == null ? "<null>" : "list");
        });
    }

    static ClientResponse post(TestApp t, InMemoryHttpAdapter adapter, String path, @Nullable String contentType,
            byte[] body, boolean contentLength) {
        t.start();
        ClientRequest r = ClientRequest.of("POST", path);
        if (contentType != null) {
            r.header("Content-Type", contentType);
        }
        if (contentLength) {
            r.header("Content-Length", String.valueOf(body.length));
        }
        return adapter.send(r.body(body));
    }

    private ClientResponse post(@Nullable String contentType, String body) {
        return post(t, adapter, "/b", contentType, body.getBytes(StandardCharsets.UTF_8), true);
    }

    static String text(ClientResponse r) {
        return new String(r.body(), StandardCharsets.UTF_8);
    }

    static void assertError(int status, String code, ClientResponse r) {
        assertEquals(text(r), status, r.status());
        assertEquals("application/json; charset=UTF-8", r.header("Content-Type"));
        String body = text(r);
        assertTrue(body, body.matches("\\{\"error\":\\{\"code\":\"" + code
                + "\",\"message\":\"[^\"]*\",\"requestId\":\"[0-9a-f]{16}\"}}"));
    }

    private static byte[] repeat(char c, int n) {
        byte[] b = new byte[n];
        Arrays.fill(b, (byte) c);
        return b;
    }

    // SDS-AC-7; FR-JSON-001 AC-1
    @Test
    public void sdsAc7_FR_JSON_001_AC1_jsonMediaTypesAreDecoded() {
        for (String ct : new String[] {"application/json", "application/json; charset=utf-8",
                "application/problem+json", "APPLICATION/JSON", "application/json;charset=\"UTF-8\""}) {
            ClientResponse r = post(ct, "\"x\"");
            assertEquals(ct, 200, r.status());
            assertEquals(ct, "text:\"x\"", text(r));
        }
    }

    // SDS-AC-7; FR-JSON-001 AC-1
    @Test
    public void sdsAc7_FR_JSON_001_AC1_otherMediaTypesGive415WithoutCallingTheCodec() {
        for (String ct : new String[] {"text/plain", "application/x-www-form-urlencoded", "application/jsonx",
                "application/json; charset=iso-8859-1", "application/+json", null}) {
            assertError(415, "UNSUPPORTED_MEDIA_TYPE", post(ct, "\"x\""));
        }
        assertEquals(0, codec.decodes.get());
    }

    // SDS-AC-8; FR-JSON-001 AC-2
    @Test
    public void sdsAc8_FR_JSON_001_AC2_emptyAndWhitespaceBodiesGive400WithoutCallingTheCodec() {
        assertError(400, "BAD_REQUEST", post("application/json", ""));
        assertError(400, "BAD_REQUEST", post("application/json", "  \n\r\n\t "));
        assertError(400, "BAD_REQUEST",
                post(t, adapter, "/b", "application/json", "   ".getBytes(StandardCharsets.UTF_8), false));
        assertEquals(0, codec.decodes.get());
    }

    // SDS-AC-8; FR-JSON-001 AC-2
    @Test
    public void sdsAc8_FR_JSON_001_AC2_codecIOExceptionGives400() {
        assertError(400, "BAD_REQUEST", post("application/json", "{\"a\":"));
        assertError(400, "BAD_REQUEST", post("application/json", "[1,]"));
        assertEquals(2, codec.decodes.get());
    }

    // SDS-AC-8; FR-JSON-001 AC-2: the peeked first char still reaches the codec
    @Test
    public void sdsAc8_FR_JSON_001_AC2_leadingWhitespaceAndFirstCharReachTheCodec() {
        ClientResponse r = post("application/json", "  \"abc\"");
        assertEquals(200, r.status());
        assertEquals("text:\"abc\"", text(r));
    }

    // SDS-AC-9; FR-JSON-001 AC-4
    @Test
    public void sdsAc9_FR_JSON_001_AC4_contentLengthOverTheDefaultLimitGives413BeforeTheCodec() {
        ClientResponse r = post(t, adapter, "/b", "application/json", repeat(' ', 1048577), true);
        assertError(413, "PAYLOAD_TOO_LARGE", r);
        assertEquals(0, codec.decodes.get());
    }

    // SDS-AC-9; FR-JSON-001 AC-4
    @Test
    public void sdsAc9_FR_JSON_001_AC4_chunkedOverrunWrappedByTheCodecGives413() {
        for (boolean unchecked : new boolean[] {false, true}) {
            InMemoryHttpAdapter a = new InMemoryHttpAdapter();
            TestApp app = TestApp.create(a);
            app.app().jsonCodec(new WrappingCodec(unchecked));
            app.app().post("/b", (req, res) -> res.send(String.valueOf(req.body(String.class))));
            byte[] body = repeat('1', 1048578);
            assertError(413, "PAYLOAD_TOO_LARGE", post(app, a, "/b", "application/json", body, false));
        }
    }

    // SDS-AC-9; FR-JSON-001 AC-4
    @Test
    public void sdsAc9_FR_JSON_001_AC4_jsonLimit10Gives413For11BytesAndDecodes10() {
        InMemoryHttpAdapter a = new InMemoryHttpAdapter();
        TestApp app = TestApp.create(a);
        app.app().jsonCodec(new TextCodec()).jsonLimit(10);
        app.app().post("/b", (req, res) -> res.send(String.valueOf(req.body(String.class))));
        byte[] ten = "\"12345678\"".getBytes(StandardCharsets.UTF_8);
        byte[] eleven = "\"123456789\"".getBytes(StandardCharsets.UTF_8);
        assertEquals(10, ten.length);
        assertError(413, "PAYLOAD_TOO_LARGE", post(app, a, "/b", "application/json", eleven, true));
        assertError(413, "PAYLOAD_TOO_LARGE", post(app, a, "/b", "application/json", eleven, false));
        for (boolean length : new boolean[] {true, false}) {
            ClientResponse r = post(app, a, "/b", "application/json", ten, length);
            assertEquals(200, r.status());
            assertEquals("\"12345678\"", text(r));
        }
    }

    // SDS-AC-10; FR-JSON-001 AC-6
    @Test
    public void sdsAc10_FR_JSON_001_AC6_invalidUtf8Gives400() throws Exception {
        ByteArrayOutputStream bad = new ByteArrayOutputStream();
        bad.write("{\"a\":\"".getBytes(StandardCharsets.UTF_8));
        bad.write(new byte[] {(byte) 0xC3, 0x28});
        bad.write("\"}".getBytes(StandardCharsets.UTF_8));
        assertError(400, "BAD_REQUEST", post(t, adapter, "/b", "application/json", bad.toByteArray(), true));
        byte[] truncated = {'"', 'a', (byte) 0xE2, (byte) 0x82};
        assertError(400, "BAD_REQUEST", post(t, adapter, "/b", "application/json", truncated, true));
    }

    // FR-JSON-001 AC-8
    @Test
    public void FR_JSON_001_AC8_jsonNullBodyReturnsNullForClassAndTypeRef() {
        for (String path : new String[] {"/b", "/user", "/list"}) {
            for (String body : new String[] {"null", " null "}) {
                ClientResponse r = post(t, adapter, path, "application/json", body.getBytes(StandardCharsets.UTF_8),
                        true);
                assertEquals(path + " " + body, 200, r.status());
                assertEquals(path + " " + body, "<null>", text(r));
            }
        }
    }
}
