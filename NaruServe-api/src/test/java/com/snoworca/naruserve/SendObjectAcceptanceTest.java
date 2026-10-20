package com.snoworca.naruserve;

import static org.junit.Assert.*;

import com.snoworca.naruserve.spi.JsonCodec;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/** Public API and dispatcher coverage for FR-DX-002. */
public class SendObjectAcceptanceTest {
    // FR-DX-002 AC-1 AC-2 AC-3 AC-4: use Object references deliberately; dispatch must use runtime types.
    @Test
    public void runtimeValuesDispatchToTextBytesJsonAndEmptyBody() {
        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
        AtomicInteger encodes = new AtomicInteger();
        AtomicReference<Object> encoded = new AtomicReference<>();
        RestApp app = RestApp.builder().httpAdapter(adapter).jsonCodec(new JsonCodec() {
            public <T> T decode(Reader in, Type type) { throw new UnsupportedOperationException(); }
            public void encode(Object value, Writer out) throws IOException {
                encodes.incrementAndGet();
                encoded.set(value);
                out.write("{\"encoded\":true}");
            }
        }).build();
        Object text = "hello";
        Object bytes = new byte[] {0, 1, -1};
        Object dto = new Object();
        Object list = Arrays.asList("a", "b");
        Object map = Collections.singletonMap("id", 7);
        Object[] values = {text, bytes, dto, list, map, 42, true};
        for (int i = 0; i < values.length; i++) {
            Object value = values[i];
            app.get("/v" + i, (req, res) -> res.status(201).send(value));
        }
        app.get("/null", (req, res) -> res.send(null));
        app.get("/custom", (req, res) -> res.header("Content-Type", "custom/type").send(text));
        app.get("/empty", (req, res) -> res.status(204).send(dto));
        app.get("/unchanged", (req, res) -> res.status(304).send(list));
        app.listen(0);
        try {
            ClientResponse t = adapter.send(ClientRequest.of("GET", "/v0"));
            assertEquals(201, t.status());
            assertEquals("hello", t.text());
            assertEquals("text/plain; charset=UTF-8", t.header("Content-Type"));
            ClientResponse b = adapter.send(ClientRequest.of("GET", "/v1"));
            assertArrayEquals((byte[]) bytes, b.body());
            assertEquals("application/octet-stream", b.header("Content-Type"));
            assertEquals(0, encodes.get());
            for (int i = 2; i < values.length; i++) {
                ClientResponse r = adapter.send(ClientRequest.of("GET", "/v" + i));
                assertEquals(201, r.status());
                assertEquals("{\"encoded\":true}", r.text());
                assertEquals("application/json; charset=UTF-8", r.header("Content-Type"));
                assertSame(values[i], encoded.get());
            }
            int beforeNull = encodes.get();
            ClientResponse n = adapter.send(ClientRequest.of("GET", "/null"));
            assertEquals(200, n.status());
            assertEquals(0, n.body().length);
            assertEquals(beforeNull, encodes.get());
            assertEquals("custom/type", adapter.send(ClientRequest.of("GET", "/custom")).header("Content-Type"));
            for (String path : new String[] {"/empty", "/unchanged"}) {
                assertEquals(0, adapter.send(ClientRequest.of("GET", path)).body().length);
            }
            assertEquals(0, adapter.send(ClientRequest.of("HEAD", "/v2")).body().length);
        } finally {
            app.shutdown(5, TimeUnit.SECONDS);
        }
    }

    // FR-DX-002 AC-5: even a codec which writes before failing must leave the response uncommitted.
    @Test
    public void failedEncodingCanBeReplacedWithoutPartialOutput() {
        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
        RestApp app = RestApp.builder().httpAdapter(adapter).jsonCodec(new JsonCodec() {
            public <T> T decode(Reader in, Type type) { throw new UnsupportedOperationException(); }
            public void encode(Object value, Writer out) throws IOException {
                out.write("partial");
                throw new IOException("codec failure");
            }
        }).build();
        app.get("/failure", (req, res) -> {
            try {
                res.send(new Object());
                fail("encoding should fail");
            } catch (HttpException expected) {
                assertFalse(res.isCommitted());
                res.status(503).send("retry");
            }
        });
        app.listen(0);
        try {
            ClientResponse r = adapter.send(ClientRequest.of("GET", "/failure"));
            assertEquals(503, r.status());
            assertEquals("retry", r.text());
        } finally {
            app.shutdown(5, TimeUnit.SECONDS);
        }
    }
}
