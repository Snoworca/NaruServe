package com.snoworca.naruserve;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.JsonBodyAcceptanceTest.TextCodec;
import com.snoworca.naruserve.spi.JsonCodec;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.TestApp;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.Test;

public class JsonResponseTest {

    /** Writes part of a document, flushes it and then fails. */
    static final class PartialCodec implements JsonCodec {
        @Override
        public <T> @Nullable T decode(Reader reader, Type type) throws IOException {
            throw new IOException("PartialCodec does not decode");
        }

        @Override
        public void encode(@Nullable Object value, Writer writer) throws IOException {
            writer.write("{\"partial\":");
            writer.flush();
            throw new IllegalStateException("encode failed half way");
        }
    }

    // FR-JSON-002 AC-1, AC-3
    @Test
    public void FR_JSON_002_AC1_AC3_jsonSendsTheEncodedBytesWithJsonTypeAndLength() {
        TestApp t = TestApp.create();
        TextCodec codec = new TextCodec();
        t.app().jsonCodec(codec);
        t.app().get("/hello", (req, res) -> res.json("hello"));
        t.app().get("/null", (req, res) -> res.json(null));
        t.app().get("/created", (req, res) -> res.status(201).json(5));
        t.app().get("/utf8", (req, res) -> res.json("\u00e9"));
        ClientResponse r = t.send("GET", "/hello");
        assertEquals(200, r.status());
        assertEquals("application/json; charset=UTF-8", r.header("Content-Type"));
        assertEquals("7", r.header("Content-Length"));
        assertEquals("\"hello\"", r.text());
        assertEquals(1, codec.encodes.get());
        ClientResponse n = t.send("GET", "/null");
        assertEquals("null", n.text());
        assertEquals("4", n.header("Content-Length"));
        ClientResponse c = t.send("GET", "/created");
        assertEquals(201, c.status());
        assertEquals("5", c.text());
        ClientResponse u = t.send("GET", "/utf8");
        assertEquals("4", u.header("Content-Length"));
        assertEquals("\"\u00e9\"", u.text());
        assertEquals(4, codec.encodes.get());
    }

    // SDS-AC-12; FR-JSON-002 AC-4
    @Test
    public void sdsAc12_FR_JSON_002_AC4_partialEncodeFailureGivesTheStandard500AndNoPartialBytes() {
        TestApp t = TestApp.create();
        t.app().jsonCodec(new PartialCodec());
        List<Boolean> committedAtError = Collections.synchronizedList(new ArrayList<Boolean>());
        t.app().get("/j", (req, res) -> res.json(Collections.singletonMap("a", 1)));
        // CON-APP-010 AC-4 AC-5; FR-APP-024 AC-1 AC-3: error observers follow the failing layer.
        t.app().onError((err, req, res) -> committedAtError.add(res.isCommitted()));
        ClientResponse r = t.send("GET", "/j");
        assertEquals(500, r.status());
        assertFalse(r.abnormallyClosed());
        JsonBodyAcceptanceTest.assertDefaultError(500, IllegalStateException.class, r);
        String body = r.text();
        assertTrue(body, body.contains("IllegalStateException: encode failed half way"));
        // The Java stack names this partial-encode test; only the encoder's actual document bytes must be absent.
        assertFalse(body, body.contains("{\"partial\":"));
        assertFalse(body, body.contains("{&quot;partial&quot;:"));
        assertEquals(Collections.singletonList(false), committedAtError);
    }

    // SDS-AC-13; FR-JSON-002 AC-5
    @Test
    public void sdsAc13_FR_JSON_002_AC5_jsonOnACommittedResponseThrowsBeforeEncodeAndIsAborted() {
        TestApp t = TestApp.create();
        TextCodec codec = new TextCodec();
        t.app().jsonCodec(codec);
        List<Throwable> thrown = Collections.synchronizedList(new ArrayList<Throwable>());
        t.app().get("/committed", (req, res) -> {
            res.stream(new ByteArrayInputStream(new byte[10]), "text/plain");
            try {
                res.json(1);
            } catch (RuntimeException e) {
                thrown.add(e);
                throw e;
            }
        });
        ClientResponse r = t.send("GET", "/committed");
        assertTrue(r.abnormallyClosed());
        assertFalse(r.text(), r.text().contains("\"error\""));
        assertEquals(1, thrown.size());
        assertSame(IllegalStateException.class, thrown.get(0).getClass());
        assertEquals(0, codec.encodes.get());
    }

    // SDS-AC-13; FR-JSON-002 AC-5
    @Test
    public void sdsAc13_FR_JSON_002_AC5_jsonOnACompletedResponseThrowsAndTheResponseStaysCompleted() {
        TestApp t = TestApp.create();
        TextCodec codec = new TextCodec();
        t.app().jsonCodec(codec);
        List<Throwable> thrown = Collections.synchronizedList(new ArrayList<Throwable>());
        t.app().get("/done", (req, res) -> {
            res.send("x");
            try {
                res.json(1);
            } catch (RuntimeException e) {
                thrown.add(e);
                throw e;
            }
        });
        ClientResponse r = t.send("GET", "/done");
        assertFalse(r.abnormallyClosed());
        assertEquals(200, r.status());
        assertEquals("x", r.text());
        assertEquals(1, thrown.size());
        assertSame(IllegalStateException.class, thrown.get(0).getClass());
        assertEquals(0, codec.encodes.get());
    }
}
