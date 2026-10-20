package com.snoworca.naruserve.internal;

import static org.junit.Assert.*;

import com.snoworca.naruserve.spi.JsonCodec;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.TestApp;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

public class JsonMediaTypeSyntaxTest {
    // FR-JSON-001 AC-1: invalid media tokens and malformed parameters are rejected before body/codec access.
    @Test public void invalidMediaSyntaxAnswers415WithoutConsumingBody() {
        String[] invalid = {"application/(+json", "application/\"+json", "application/a\\b+json",
                "application/:+json", "application/[+json", "application/json;charset", "application/json;charset=",
                "application/json; charset=\"utf-8", "application/json; charset=\"utf-8\"junk",
                "application/json; =utf-8", "application/json; x y=z", "application/json; profile=a b",
                "application/json; profile=\"a\\", "application/json; profile=\"a\r\nb\"",
                "application/json; charset=utf-8, text/plain", "application/json; profile=a=b",
                "application/json; charset=iso-8859-1", "application/problem+json; charset=\"utf-16\""};
        verify(invalid, 415);
    }

    // FR-JSON-001 AC-1: quoted delimiters and escaped quoted-pairs in unrelated parameters never become charset.
    @Test public void validQuotedParametersAndUtf8CharsetsDecodeNormally() {
        String[] valid = {"application/json", "APPLICATION/PROBLEM+JSON", "application/vnd.api+json",
                "application/json; profile=\"x;charset=iso-8859-1\"",
                "application/json; profile=\"x;y\"; charset=utf-8",
                "application/json; profile=\"x\\\";charset=iso-8859-1\"; charset=\"UTF-8\"",
                "application/json; profile=\"x\\;charset=iso-8859-1\"",
                "application/json ; charset = \"utf-8\" ; profile=example",
                "application/json; profile=\"\"", "application/json; profile=\"x\\\\y\""};
        verify(valid, 200);
    }

    private static void verify(String[] types, int expectedStatus) {
        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
        TestApp app = TestApp.create(adapter);
        AtomicInteger calls = new AtomicInteger();
        AtomicLong consumed = new AtomicLong(-1);
        app.app().jsonCodec(new JsonCodec() {
            @Override @SuppressWarnings("unchecked")
            public <T> T decode(Reader reader, Type type) throws IOException {
                calls.incrementAndGet();
                while (reader.read() >= 0) { }
                return (T) "decoded";
            }
            @Override public void encode(Object value, Writer writer) { throw new AssertionError("no JSON response needed"); }
        });
        app.app().post("/json", (req, res) -> {
            try { req.body(String.class); }
            finally { consumed.set(((DefaultRequest) req).body().bytesRead()); }
            res.end();
        });
        app.start();
        try {
            for (String type : types) {
                calls.set(0);
                consumed.set(-1);
                assertEquals(type, expectedStatus, adapter.send(ClientRequest.of("POST", "/json")
                        .header("Content-Type", type).body("{}".getBytes(StandardCharsets.UTF_8))).status());
                assertEquals(type, expectedStatus == 200 ? 1 : 0, calls.get());
                assertEquals(type, expectedStatus == 200 ? 2 : 0, consumed.get());
            }
        } finally { app.app().shutdown(5, TimeUnit.SECONDS); }
    }
}
