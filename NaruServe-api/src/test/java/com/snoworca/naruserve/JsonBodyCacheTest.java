package com.snoworca.naruserve;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

import com.snoworca.naruserve.spi.JsonCodec;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.Test;

public class JsonBodyCacheTest {
    private static final class CountingCodec implements JsonCodec {
        int calls;
        int characters;

        @Override
        @SuppressWarnings("unchecked")
        public <T> @Nullable T decode(Reader reader, Type type) throws IOException {
            calls++;
            StringBuilder input = new StringBuilder();
            int next;
            while ((next = reader.read()) >= 0) {
                characters++;
                input.append((char) next);
            }
            String text = input.toString();
            if ("broken".equals(text)) {
                throw new IOException("malformed JSON");
            }
            if ("null".equals(text)) {
                return null;
            }
            return (T) (type instanceof ParameterizedType ? Collections.singletonList(text) : text);
        }

        @Override
        public void encode(@Nullable Object value, Writer writer) throws IOException {
            writer.write(String.valueOf(value));
        }
    }

    @Test
    public void FR_DX_007_AC1_middlewareAndHandlerShareClassAndEquivalentTypeRefWithoutReread() { // FR-DX-007 AC-1
        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
        CountingCodec codec = new CountingCodec();
        RestApp app = RestApp.builder().httpAdapter(adapter).jsonCodec(codec).build();
        Object[] first = new Object[1];
        app.use((req, res, next) -> {
            first[0] = req.body(String.class);
            next.run();
        });
        app.post("/cached", (req, res) -> {
            assertSame(first[0], req.body(String.class));
            assertSame(first[0], req.body(new TypeRef<String>() {}));
            res.send("ok");
        });
        app.listen(0);
        try {
            assertEquals(200, adapter.send(InMemoryHttpAdapter.ClientRequest.json("POST", "/cached", "\"hello\"")).status());
            assertEquals(1, codec.calls);
            assertEquals(7, codec.characters);
        } finally {
            app.shutdown(0, TimeUnit.SECONDS);
        }
    }

    @Test
    public void FR_DX_007_AC1_equivalentGenericTypesShareTheSameDecodedInstance() { // FR-DX-007 AC-1
        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
        CountingCodec codec = new CountingCodec();
        RestApp app = RestApp.builder().httpAdapter(adapter).jsonCodec(codec).build();
        Object[] first = new Object[1];
        app.use((req, res, next) -> {
            first[0] = req.body(new TypeRef<List<String>>() {});
            next.run();
        });
        app.post("/cached", (req, res) -> {
            assertSame(first[0], req.body(new TypeRef<List<String>>() {}));
            assertThrows(BodyAlreadyConsumedException.class, () -> req.body(new TypeRef<List<Integer>>() {}));
            res.send("ok");
        });
        app.listen(0);
        try {
            assertEquals(200, adapter.send(InMemoryHttpAdapter.ClientRequest.json("POST", "/cached", "[]")).status());
            assertEquals(1, codec.calls);
            assertEquals(2, codec.characters);
        } finally {
            app.shutdown(0, TimeUnit.SECONDS);
        }
    }

    @Test
    public void FR_DX_007_AC1_nullIsCachedWithoutDecodingAgain() { // FR-DX-007 AC-1
        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
        CountingCodec codec = new CountingCodec();
        RestApp app = RestApp.builder().httpAdapter(adapter).jsonCodec(codec).build();
        app.use((req, res, next) -> {
            assertNull(req.body(String.class));
            next.run();
        });
        app.post("/cached", (req, res) -> {
            assertNull(req.body(new TypeRef<String>() {}));
            res.send("ok");
        });
        app.listen(0);
        try {
            assertEquals(200, adapter.send(InMemoryHttpAdapter.ClientRequest.json("POST", "/cached", "null")).status());
            assertEquals(1, codec.calls);
            assertEquals(4, codec.characters);
        } finally {
            app.shutdown(0, TimeUnit.SECONDS);
        }
    }

    @Test
    public void FR_DX_007_AC2_incompatibleTypeAndFormAfterJsonAreRejected() { // FR-DX-007 AC-2
        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
        CountingCodec codec = new CountingCodec();
        RestApp app = RestApp.builder().httpAdapter(adapter).jsonCodec(codec).build();
        app.post("/cached", (req, res) -> {
            req.body(String.class);
            assertThrows(BodyAlreadyConsumedException.class, () -> req.body(Integer.class));
            assertThrows(BodyAlreadyConsumedException.class, () -> req.form("name"));
            res.send("ok");
        });
        app.listen(0);
        try {
            assertEquals(200, adapter.send(InMemoryHttpAdapter.ClientRequest.json("POST", "/cached", "\"hello\"")).status());
            assertEquals(1, codec.calls);
        } finally {
            app.shutdown(0, TimeUnit.SECONDS);
        }
    }

    @Test
    public void FR_DX_007_AC2_AC3_failedDecodeIsNeverCachedOrRetried() { // FR-DX-007 AC-2
        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
        CountingCodec codec = new CountingCodec();
        RestApp app = RestApp.builder().httpAdapter(adapter).jsonCodec(codec).build();
        app.post("/cached", (req, res) -> {
            HttpException failure = assertThrows(HttpException.class, () -> req.body(String.class));
            assertEquals(400, failure.status());
            assertThrows(BodyAlreadyConsumedException.class, () -> req.body(String.class));
            res.send("ok");
        });
        app.listen(0);
        try {
            assertEquals(200, adapter.send(InMemoryHttpAdapter.ClientRequest.json("POST", "/cached", "broken")).status());
            assertEquals(1, codec.calls);
        } finally {
            app.shutdown(0, TimeUnit.SECONDS);
        }
    }

    @Test
    public void FR_DX_007_AC3_overLimitBodyRemains413AndIsNotCached() { // FR-DX-007 AC-3
        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
        CountingCodec codec = new CountingCodec();
        RestApp app = RestApp.builder().httpAdapter(adapter).jsonCodec(codec).build();
        app.jsonLimit(3);
        app.post("/cached", (req, res) -> {
            HttpException failure = assertThrows(HttpException.class, () -> req.body(String.class));
            assertEquals(413, failure.status());
            assertThrows(BodyAlreadyConsumedException.class, () -> req.body(String.class));
            res.send("ok");
        });
        app.listen(0);
        try {
            assertEquals(200, adapter.send(InMemoryHttpAdapter.ClientRequest.json("POST", "/cached", "\"hello\"")).status());
        } finally {
            app.shutdown(0, TimeUnit.SECONDS);
        }
    }
}
