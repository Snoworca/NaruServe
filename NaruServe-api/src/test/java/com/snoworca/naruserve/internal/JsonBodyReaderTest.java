package com.snoworca.naruserve.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.HttpException;
import com.snoworca.naruserve.JsonBodyAcceptanceTest.TextCodec;
import com.snoworca.naruserve.spi.JsonCodec;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.TestApp;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.Test;

public class JsonBodyReaderTest {

    /** Throws {@code failure} from decode after reading nothing. */
    static final class FailingCodec implements JsonCodec {
        final Exception failure;

        FailingCodec(Exception failure) {
            this.failure = failure;
        }

        @Override
        public <T> @Nullable T decode(Reader reader, Type type) throws IOException {
            if (failure instanceof IOException) {
                throw (IOException) failure;
            }
            throw (RuntimeException) failure;
        }

        @Override
        public void encode(@Nullable Object value, Writer writer) throws IOException {
            writer.write("null");
        }
    }

    private static final class Outcome {
        final AtomicReference<Throwable> thrown = new AtomicReference<>();
        final AtomicLong bytesRead = new AtomicLong(-1);
    }

    private static ClientResponse send(JsonCodec codec, long limit, @Nullable String contentType, byte[] body,
            boolean contentLength, Outcome out) {
        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
        TestApp t = TestApp.create(adapter);
        t.app().jsonCodec(codec).jsonLimit(limit);
        t.app().post("/b", (req, res) -> {
            try {
                req.body(String.class);
            } catch (RuntimeException e) {
                out.thrown.set(e);
                throw e;
            } finally {
                out.bytesRead.set(((DefaultRequest) req).body().bytesRead());
            }
            res.end();
        });
        t.start();
        ClientRequest r = ClientRequest.of("POST", "/b");
        if (contentType != null) {
            r.header("Content-Type", contentType);
        }
        if (contentLength) {
            r.header("Content-Length", String.valueOf(body.length));
        }
        return adapter.send(r.body(body));
    }

    // SDS-AC-11; FR-JSON-001 AC-9
    @Test
    public void sdsAc11_FR_JSON_001_AC9_httpExceptionInTheCauseChainIsRethrownUnchanged() {
        HttpException timeout = new HttpException(408);
        for (Exception failure : new Exception[] {new IOException("read failed", timeout),
                new IllegalStateException("wrapped", new IOException("read failed", timeout)), timeout}) {
            Outcome out = new Outcome();
            ClientResponse r = send(new FailingCodec(failure), 1048576, "application/json",
                    "{}".getBytes(StandardCharsets.UTF_8), true, out);
            assertSame(failure.toString(), timeout, out.thrown.get());
            assertEquals(408, r.status());
        }
    }

    // SDS-AC-9; FR-JSON-001 AC-9: a codec that swallows the overrun still gives 413
    @Test
    public void sdsAc9_FR_JSON_001_AC9_swallowedOverrunGives413() {
        JsonCodec swallowing = new JsonCodec() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> @Nullable T decode(Reader reader, Type type) throws IOException {
                try {
                    TextCodec.readAll(reader);
                } catch (IOException ignored) {
                    // a careless codec
                }
                return (T) "truncated";
            }

            @Override
            public void encode(@Nullable Object value, Writer writer) throws IOException {
                writer.write("null");
            }
        };
        byte[] body = new byte[100];
        Arrays.fill(body, (byte) '1');
        Outcome out = new Outcome();
        assertEquals(413, send(swallowing, 10, "application/json", body, false, out).status());
    }

    // SDS-AC-10; FR-JSON-001 AC-6: invalid UTF-8 wrapped unchecked by the codec (as Gson does) is still 400
    @Test
    public void sdsAc10_FR_JSON_001_AC6_uncheckedWrappedMalformedInputGives400() {
        JsonCodec wrapping = new JsonCodec() {
            @Override
            public <T> @Nullable T decode(Reader reader, Type type) throws IOException {
                try {
                    TextCodec.readAll(reader);
                } catch (IOException e) {
                    throw new IllegalStateException("syntax", e);
                }
                throw new IOException("unexpected");
            }

            @Override
            public void encode(@Nullable Object value, Writer writer) throws IOException {
                writer.write("null");
            }
        };
        byte[] bad = {'"', 'a', (byte) 0xC3, 0x28, '"'};
        Outcome out = new Outcome();
        assertEquals(400, send(wrapping, 1048576, "application/json", bad, true, out).status());
    }

    // FR-JSON-001 AC-2: an unchecked codec failure without an HttpException cause stays itself (500)
    @Test
    public void FR_JSON_001_AC2_uncheckedCodecFailureReachesTheErrorPathUnchanged() {
        IllegalArgumentException bug = new IllegalArgumentException("no such constructor");
        Outcome out = new Outcome();
        ClientResponse r = send(new FailingCodec(bug), 1048576, "application/json",
                "{}".getBytes(StandardCharsets.UTF_8), true, out);
        assertSame(bug, out.thrown.get());
        assertEquals(500, r.status());
    }

    // FR-JSON-001 AC-2: a codec IOException becomes 400 with it as the cause
    @Test
    public void FR_JSON_001_AC2_codecIOExceptionBecomes400WithItAsCause() {
        IOException malformed = new IOException("malformed");
        Outcome out = new Outcome();
        ClientResponse r = send(new FailingCodec(malformed), 1048576, "application/json",
                "{}".getBytes(StandardCharsets.UTF_8), true, out);
        assertEquals(400, r.status());
        assertEquals(400, ((HttpException) out.thrown.get()).status());
        assertSame(malformed, out.thrown.get().getCause());
    }

    // SDS-AC-9; FR-JSON-001 AC-4: at most limit + 1 bytes are taken from the exchange
    @Test
    public void sdsAc9_FR_JSON_001_AC4_chunkedOverrunTakesAtMostLimitPlusOneBytes() {
        byte[] body = new byte[1000];
        Arrays.fill(body, (byte) '1');
        Outcome out = new Outcome();
        ClientResponse r = send(new TextCodec(), 10, "application/json", body, false, out);
        assertEquals(413, r.status());
        assertEquals(413, ((HttpException) out.thrown.get()).status());
        assertTrue(String.valueOf(out.bytesRead.get()), out.bytesRead.get() <= 11);
    }

    // SDS-AC-7, SDS-AC-9; FR-JSON-001 AC-1, AC-4: refused before the stream is touched
    @Test
    public void sdsAc7_sdsAc9_FR_JSON_001_AC1_AC4_415AndDeclared413ReadNoByte() {
        Outcome media = new Outcome();
        assertEquals(415, send(new TextCodec(), 10, "text/plain", "\"x\"".getBytes(StandardCharsets.UTF_8), true,
                media).status());
        assertEquals(0, media.bytesRead.get());
        Outcome large = new Outcome();
        assertEquals(413, send(new TextCodec(), 10, "application/json", new byte[11], true, large).status());
        assertEquals(0, large.bytesRead.get());
    }

    // SDS-AC-7; FR-JSON-001 AC-1
    @Test
    public void sdsAc7_FR_JSON_001_AC1_isJsonType() {
        for (String ok : new String[] {"application/json", "application/json; charset=utf-8", "APPLICATION/JSON",
                "application/problem+json", "Application/Vnd.Api+JSON", "application/json ; charset=UTF-8",
                "application/json; charset=\"utf-8\"", "application/json; foo=bar"}) {
            assertTrue(ok, JsonBodyReader.isJsonType(ok));
        }
        for (String bad : new String[] {null, "", "text/plain", "application/x-www-form-urlencoded",
                "application/jsonx", "application/json; charset=iso-8859-1", "application/+json", "json",
                "text/json", "application/json+xml", "application/a/b+json", "application/ x+json"}) {
            assertFalse(String.valueOf(bad), JsonBodyReader.isJsonType(bad));
        }
    }

    // SDS-AC-9; FR-JSON-001 AC-9: the overrun stays readable after a codec wrapped the failure
    @Test
    public void sdsAc9_FR_JSON_001_AC9_limitedStreamRecordsTheOverrun() throws IOException {
        LimitedInputStream in = new LimitedInputStream(new ByteArrayInputStream(new byte[20]), 10, -1);
        assertFalse(in.overrun());
        byte[] buf = new byte[64];
        try {
            while (in.read(buf, 0, buf.length) != -1) {
                // drain
            }
            fail("expected BodyTooLargeException");
        } catch (BodyTooLargeException expected) {
            assertTrue(in.overrun());
        }
        LimitedInputStream declared = new LimitedInputStream(new ByteArrayInputStream(new byte[20]), 10, 20);
        try {
            declared.read();
            fail("expected BodyTooLargeException");
        } catch (BodyTooLargeException expected) {
            assertTrue(declared.overrun());
        }
        LimitedInputStream within = new LimitedInputStream(new ByteArrayInputStream(new byte[10]), 10, -1);
        while (within.read(buf, 0, buf.length) != -1) {
            // drain
        }
        assertFalse(within.overrun());
    }
}
