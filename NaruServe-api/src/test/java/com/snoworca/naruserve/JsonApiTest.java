package com.snoworca.naruserve;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.JsonBodyAcceptanceTest.TextCodec;
import com.snoworca.naruserve.internal.DefaultRestApp;
import com.snoworca.naruserve.spi.JsonCodec;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.Test;

public class JsonApiTest {

    private static RestApp app(InMemoryHttpAdapter adapter, JsonCodec codec) {
        return RestApp.builder().httpAdapter(adapter).jsonCodec(codec).build();
    }

    private static ClientResponse post(InMemoryHttpAdapter adapter, String path, String body) {
        return adapter.send(ClientRequest.of("POST", path).header("Content-Type", "application/json")
                .header("Content-Length", String.valueOf(body.length()))
                .body(body.getBytes(StandardCharsets.UTF_8)));
    }

    // SDS-AC-3; IR-JSON-002 AC-1
    @Test
    public void sdsAc3_IR_JSON_002_AC1_jsonCodecReplacesTheBuilderCodecForBodyAndJson() {
        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
        TextCodec c1 = new TextCodec();
        TextCodec c2 = new TextCodec();
        RestApp app = app(adapter, c1);
        assertSame(app, app.jsonCodec(c2));
        app.post("/echo", (req, res) -> res.json(req.body(String.class)));
        app.listen(0);
        try {
            ClientResponse r = post(adapter, "/echo", "\"hi\"");
            assertEquals(200, r.status());
            assertEquals("\"\"hi\"\"", new String(r.body(), StandardCharsets.UTF_8));
            assertEquals(0, c1.decodes.get());
            assertEquals(0, c1.encodes.get());
            assertEquals(1, c2.decodes.get());
            assertEquals(1, c2.encodes.get());
            assertSame(c2, ((DefaultRestApp) app).jsonCodec());
        } finally {
            app.shutdown(5, TimeUnit.SECONDS);
        }
    }

    // SDS-AC-3; IR-JSON-002 AC-1
    @Test
    public void sdsAc3_IR_JSON_002_AC1_jsonCodecRefusesNullAndCallsOutsideNew() {
        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
        RestApp app = app(adapter, new TextCodec());
        try {
            app.jsonCodec(null);
            fail("jsonCodec(null) must throw");
        } catch (NullPointerException expected) {
            // refused
        }
        app.listen(0);
        try {
            app.jsonCodec(new TextCodec());
            fail("jsonCodec outside NEW must throw");
        } catch (IllegalStateException expected) {
            // refused
        }
        try {
            app.jsonLimit(5);
            fail("jsonLimit outside NEW must throw");
        } catch (IllegalStateException expected) {
            // refused
        } finally {
            app.shutdown(5, TimeUnit.SECONDS);
        }
    }

    // SDS-AC-4; IR-JSON-002 AC-2
    @Test
    public void sdsAc4_IR_JSON_002_AC2_jsonLimitBoundsAndDefault() {
        DefaultRestApp app = (DefaultRestApp) app(new InMemoryHttpAdapter(), new TextCodec());
        assertEquals(1048576L, app.jsonLimit());
        for (long bad : new long[] {0, -1, 1073741825L, Long.MIN_VALUE, Long.MAX_VALUE}) {
            try {
                app.jsonLimit(bad);
                fail("jsonLimit(" + bad + ") must throw");
            } catch (IllegalArgumentException e) {
                assertTrue(e.getMessage(), e.getMessage().contains("jsonLimit"));
                assertTrue(e.getMessage(), e.getMessage().contains("1..1073741824"));
            }
        }
        assertEquals(1048576L, app.jsonLimit());
        assertSame(app, app.jsonLimit(1));
        assertEquals(1L, app.jsonLimit());
        app.jsonLimit(1073741824L);
        assertEquals(1073741824L, app.jsonLimit());
    }

    // SDS-AC-5; IR-JSON-002 AC-4
    @Test
    public void sdsAc5_IR_JSON_002_AC4_decodeReturnIsNullableWithTheErasedSignatureKept() throws Exception {
        Method decode = JsonCodec.class.getMethod("decode", Reader.class, Type.class);
        assertSame(Object.class, decode.getReturnType());
        assertTrue(decode.getAnnotatedReturnType().isAnnotationPresent(Nullable.class));
        assertEquals(1, decode.getTypeParameters().length);
        assertEquals(1, decode.getExceptionTypes().length);
        assertSame(java.io.IOException.class, decode.getExceptionTypes()[0]);
        Method encode = JsonCodec.class.getMethod("encode", Object.class, Writer.class);
        assertTrue(encode.getAnnotatedParameterTypes()[0].isAnnotationPresent(Nullable.class));
        assertFalse(encode.getAnnotatedReturnType().isAnnotationPresent(Nullable.class));
    }

    // IR-JSON-002 AC-3
    @Test
    public void IR_JSON_002_AC3_bodyReturnsAndJsonParameterAreNullable() throws Exception {
        for (Class<?> p : new Class<?>[] {Class.class, TypeRef.class}) {
            Method body = Request.class.getMethod("body", p);
            assertTrue(p.getSimpleName(), body.getAnnotatedReturnType().isAnnotationPresent(Nullable.class));
            assertEquals(1, body.getTypeParameters().length);
            assertEquals(body.getTypeParameters()[0], body.getGenericReturnType());
        }
        Method json = Response.class.getMethod("json", Object.class);
        assertSame(void.class, json.getReturnType());
        assertTrue(json.getAnnotatedParameterTypes()[0].isAnnotationPresent(Nullable.class));
        assertSame(RestApp.class, RestApp.class.getMethod("jsonCodec", JsonCodec.class).getReturnType());
        assertSame(RestApp.class, RestApp.class.getMethod("jsonLimit", long.class).getReturnType());
    }

    // IR-JSON-002 AC-5
    @Test
    public void IR_JSON_002_AC5_noJsonTreeOrJsonMap() {
        for (Method m : Request.class.getMethods()) {
            assertFalse(m.getName(), m.getName().equals("jsonTree") || m.getName().equals("jsonMap"));
        }
    }

    // IR-JSON-002 AC-6
    @Test
    public void IR_JSON_002_AC6_secondBodyCallThrowsBodyAlreadyConsumed() {
        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
        RestApp app = app(adapter, new TextCodec());
        AtomicReference<Throwable> second = new AtomicReference<>();
        app.post("/twice", (req, res) -> {
            String first = req.body(String.class);
            try {
                req.body(new TypeRef<String>() {
                });
            } catch (RuntimeException e) {
                second.set(e);
            }
            res.send(String.valueOf(first));
        });
        app.listen(0);
        try {
            ClientResponse r = post(adapter, "/twice", "\"a\"");
            assertEquals(200, r.status());
            assertNotNull(second.get());
            assertSame(BodyAlreadyConsumedException.class, second.get().getClass());
        } finally {
            app.shutdown(5, TimeUnit.SECONDS);
        }
    }
}
