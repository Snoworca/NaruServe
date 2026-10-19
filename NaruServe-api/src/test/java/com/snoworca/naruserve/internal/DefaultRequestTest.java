package com.snoworca.naruserve.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.HttpException;
import com.snoworca.naruserve.Method;
import com.snoworca.naruserve.RouteHandler;
import com.snoworca.naruserve.routing.Router;
import com.snoworca.naruserve.routing.RouterPolicy;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class DefaultRequestTest {

    /** A DefaultRequest over a real in-memory exchange; {@code headerPairs} alternate name and value. */
    private static DefaultRequest request(InMemoryHttpAdapter adapter, String target, Map<String, String> params,
            String... headerPairs) {
        DefaultRequest[] captured = new DefaultRequest[1];
        adapter.bind("127.0.0.1", 0, ex -> {
            captured[0] = new DefaultRequest(ex, Method.GET, params);
            ex.abort(); // the adapter keeps an unended exchange open after handle() returns (HttpExchange contract)
        });
        ClientRequest r = ClientRequest.of("GET", target);
        for (int i = 0; i < headerPairs.length; i += 2) {
            r.header(headerPairs[i], headerPairs[i + 1]);
        }
        adapter.send(r);
        return captured[0];
    }

    private static DefaultRequest request(String target, String... headerPairs) {
        return request(new InMemoryHttpAdapter(), target, Collections.<String, String>emptyMap(), headerPairs);
    }

    // SDS-AC-1; FR-HTTP-009 AC-1, FR-HTTP-009 AC-2, FR-HTTP-009 AC-3
    @Test
    public void sdsAc1_FR_HTTP_009_AC1_AC2_AC3_pathIsDecodedRawPathIsNotAndParamIsDecoded() {
        // The params come from a real lookup of /files/:name, as Dispatcher.invoke passes RouteMatch.params().
        Router<String> router = new Router<>(RouterPolicy.strict());
        router.add(Method.GET, "/files/:name", "h");
        router.freeze();
        Map<String, String> params = router.lookup("GET", "/files/a%20b", false).params();
        DefaultRequest req = request(new InMemoryHttpAdapter(), "/files/a%20b?x=1", params);
        assertEquals("/files/a b", req.path());
        assertEquals("/files/a%20b", req.rawPath());
        assertEquals("a b", req.param("name"));
        assertEquals(Method.GET, req.method());
    }

    // SDS-AC-1; FR-HTTP-009 AC-1
    @Test
    public void sdsAc1_FR_HTTP_009_AC1_decodePathKeepsPlusAndAppliesNoSlashOrCasePolicy() {
        assertEquals("/a+b", DefaultRequest.decodePath("/a+b"));
        assertEquals("//A/b c/", DefaultRequest.decodePath("//A/b%20c/"));
        assertEquals("/한", DefaultRequest.decodePath("/%ED%95%9C"));
        assertEquals("/%41", DefaultRequest.decodePath("/%2541"));
    }

    // SDS-AC-2; FR-HTTP-009 AC-4
    @Test
    public void sdsAc2_FR_HTTP_009_AC4_paramForAbsentNameThrowsNamingIt() {
        DefaultRequest req = request("/files/x");
        try {
            req.param("missing");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("missing"));
        }
    }

    // SDS-AC-17 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-4-5.sds.md; FR-HTTP-025 AC-1
    @Test
    public void sdsAc17_FR_HTTP_025_AC1_repeatedNamesKeepRequestOrder() {
        DefaultRequest req = request("/x?a=1&a=2&b=3");
        assertEquals("1", req.query("a"));
        assertEquals(Arrays.asList("1", "2"), req.queries("a"));
        assertEquals("3", req.query("b"));
        assertNull(req.query("c"));
        assertTrue(req.queries("c").isEmpty());
    }

    // SDS-AC-17 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-4-5.sds.md; FR-HTTP-025 AC-2
    @Test
    public void sdsAc17_FR_HTTP_025_AC2_plusIsSpaceAndUtf8NamesAndValuesDecode() {
        DefaultRequest req = request("/x?q=a+b%20c&%ED%95%9C=%EA%B8%80");
        assertEquals("a b c", req.query("q"));
        assertEquals("글", req.query("한"));
    }

    // SDS-AC-17 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-4-5.sds.md; FR-HTTP-025 AC-2
    @Test
    public void sdsAc17_FR_HTTP_025_AC2_pairWithoutEqualsIsEmptyValueAndEmptyNameIsKept() {
        Map<String, List<String>> q = DefaultRequest.parseQuery("flag&=v&k=a=b&p=%2B");
        assertEquals(Collections.singletonList(""), q.get("flag"));
        assertEquals(Collections.singletonList("v"), q.get(""));
        assertEquals(Collections.singletonList("a=b"), q.get("k"));
        assertEquals(Collections.singletonList("+"), q.get("p"));
        assertTrue(DefaultRequest.parseQuery(null).isEmpty());
        assertTrue(DefaultRequest.parseQuery("").isEmpty());
    }

    // SDS-AC-17 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-4-5.sds.md; FR-HTTP-025 AC-1
    @Test
    public void sdsAc17_FR_HTTP_025_AC1_queriesListIsUnmodifiable() {
        DefaultRequest req = request("/x?a=1");
        try {
            req.queries("a").add("2");
            fail("expected UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
            assertEquals(Collections.singletonList("1"), req.queries("a"));
        }
    }

    // SDS-AC-16 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-4-5.sds.md; FR-HTTP-025 AC-3
    @Test
    public void sdsAc16_FR_HTTP_025_AC3_parseQueryRejectsMalformedPercentAndInvalidUtf8() {
        for (String raw : new String[] {"x=%zz", "x=%E0%A4", "x=%C3%28", "x=%4", "%zz=1"}) {
            try {
                DefaultRequest.parseQuery(raw);
                fail("expected IllegalArgumentException for " + raw);
            } catch (IllegalArgumentException expected) {
                // rejected
            }
        }
    }

    // SDS-AC-16 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-4-5.sds.md; FR-HTTP-025 AC-3
    @Test
    public void sdsAc16_FR_HTTP_025_AC3_decodeQueryThrowsANewHttpException400OnEveryCall() {
        DefaultRequest req = request("/x?x=%zz");
        HttpException first = null;
        for (int i = 0; i < 2; i++) {
            try {
                req.decodeQuery();
                fail("expected HttpException");
            } catch (HttpException e) {
                assertEquals(400, e.status());
                assertNull(e.getMessage());
                if (first != null) {
                    assertNotSame(first, e);
                }
                first = e;
            }
        }
        try {
            req.query("x");
            fail("expected HttpException from req.query");
        } catch (HttpException e) {
            assertEquals(400, e.status());
        }
    }

    // SDS-AC-16 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-4-5.sds.md; FR-HTTP-025 AC-3
    @Test
    public void sdsAc16_FR_HTTP_025_AC3_malformedQueryIsTheStandard400AndHandlerNotCalled() {
        AtomicInteger calls = new AtomicInteger();
        RouteHandler recording = (req, res) -> calls.incrementAndGet();
        Router<RouteHandler> router = new Router<>(RouterPolicy.strict());
        router.add(Method.get(), "/x", recording);
        router.freeze();
        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
        adapter.bind("127.0.0.1", 0, new Dispatcher(router));
        for (String target : new String[] {"/x?x=%zz", "/x?x=%E0%A4", "/x?x=%C3%28"}) {
            ClientResponse r = adapter.send(ClientRequest.of("GET", target));
            DispatcherTest.assertError(400, "BAD_REQUEST", "Bad Request", r);
        }
        assertEquals(0, calls.get());
        // Control: a well-formed query passes the check and reaches the handler.
        ClientResponse ok = adapter.send(ClientRequest.of("GET", "/x?x=%41"));
        assertEquals(200, ok.status());
        assertEquals(1, calls.get());
    }

    // SDS-AC-5; FR-HTTP-011 AC-1
    @Test
    public void sdsAc5_FR_HTTP_011_AC1_headersMatchCaseInsensitivelyInOrder() {
        DefaultRequest req = request("/x", "X-Tag", "a", "x-tag", "b");
        assertEquals("a", req.header("X-TAG"));
        assertEquals(Arrays.asList("a", "b"), req.headers("x-Tag"));
        assertNull(req.header("None"));
        List<String> none = req.headers("None");
        assertTrue(none.isEmpty());
        try {
            none.add("z");
            fail("expected UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
            // unmodifiable
        }
        try {
            req.headers("x-tag").add("z");
            fail("expected UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
            // unmodifiable
        }
    }

    // SDS-AC-6; FR-HTTP-011 AC-2
    @Test
    public void sdsAc6_FR_HTTP_011_AC2_forwardedHeadersAreIgnored() {
        DefaultRequest req = request(new InMemoryHttpAdapter("10.0.0.5", false), "/x",
                Collections.<String, String>emptyMap(),
                "X-Forwarded-For", "1.2.3.4", "X-Forwarded-Proto", "https", "Forwarded", "for=1.2.3.4;proto=https");
        assertEquals("10.0.0.5", req.remoteAddress());
        assertFalse(req.isSecure());
        DefaultRequest secure = request(new InMemoryHttpAdapter("10.0.0.6", true), "/x",
                Collections.<String, String>emptyMap());
        assertTrue(secure.isSecure());
    }

    // SDS-AC-6; FR-HTTP-011 AC-3
    @Test
    public void sdsAc6_FR_HTTP_011_AC3_lastEventIdIsFirstValueOrNull() {
        assertEquals("42", request("/x", "Last-Event-ID", "42").lastEventId());
        assertEquals("42", request("/x", "last-event-id", "42", "Last-Event-ID", "43").lastEventId());
        assertNull(request("/x").lastEventId());
    }

    // SDS-AC-7; FR-HTTP-012 AC-1
    @Test
    public void sdsAc7_FR_HTTP_012_AC1_storedAttributeIsReadBackAndMissingIsNull() {
        DefaultRequest req = request("/x");
        StringBuilder u = new StringBuilder("user");
        req.attribute("user", u);
        assertSame(u, req.attribute("user", StringBuilder.class));
        assertNull(req.attribute("none", StringBuilder.class));
    }

    // SDS-AC-7; FR-HTTP-012 AC-1 - a primitive class (Kotlin's Int::class.java is int.class) reads the boxed value.
    @Test
    public void sdsAc7_FR_HTTP_012_AC1_primitiveClassReadsTheBoxedValue() {
        DefaultRequest req = request("/x");
        req.attribute("n", 5);
        req.attribute("b", true);
        req.attribute("l", 7L);
        assertEquals(Integer.valueOf(5), req.attribute("n", int.class));
        assertEquals(Boolean.TRUE, req.attribute("b", boolean.class));
        assertEquals(Long.valueOf(7), req.attribute("l", long.class));
        assertNull(req.attribute("none", int.class));
        try {
            req.attribute("b", int.class);
            fail("expected ClassCastException");
        } catch (ClassCastException expected) {
            // a stored Boolean is not an int
        }
    }

    // SDS-AC-7; FR-HTTP-012 AC-2 (unit part: two request objects never share a store, written from two threads;
    // two requests in flight through the Dispatcher at once is HttpAcceptanceTest.FR_HTTP_012_AC2_*)
    @Test
    public void sdsAc7_FR_HTTP_012_AC2_twoRequestObjectsKeepSeparateStoresAcrossThreads() throws Exception {
        DefaultRequest first = request("/x");
        DefaultRequest second = request("/x");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            Future<?> a = pool.submit(() -> {
                start.await();
                first.attribute("k", "first");
                return null;
            });
            Future<?> b = pool.submit(() -> {
                start.await();
                second.attribute("k", "second");
                second.attribute("other", "second");
                return null;
            });
            start.countDown();
            a.get(5, TimeUnit.SECONDS);
            b.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertEquals("first", first.attribute("k", String.class));
        assertEquals("second", second.attribute("k", String.class));
        assertNull(first.attribute("other", String.class));
    }

    // SDS-AC-7; FR-HTTP-012 AC-3
    @Test
    public void sdsAc7_FR_HTTP_012_AC3_eightThreadsWritingDistinctKeysAllSucceed() throws Exception {
        DefaultRequest req = request("/x");
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            CountDownLatch start = new CountDownLatch(1);
            Future<?>[] futures = new Future<?>[8];
            for (int t = 0; t < 8; t++) {
                int id = t;
                futures[t] = pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < 500; i++) {
                        req.attribute("k" + id + "-" + i, id * 1000 + i);
                    }
                    return null;
                });
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        for (int t = 0; t < 8; t++) {
            for (int i = 0; i < 500; i++) {
                assertEquals(Integer.valueOf(t * 1000 + i), req.attribute("k" + t + "-" + i, Integer.class));
            }
        }
    }

    // SDS-AC-7; FR-HTTP-012 AC-4
    @Test
    public void sdsAc7_FR_HTTP_012_AC4_typedNullValueRemovesTheKey() throws Exception {
        DefaultRequest req = request("/x");
        req.attribute("k", "v");
        Object v = null;
        req.attribute("k", v);
        assertNull(req.attribute("k", Object.class));
        java.lang.reflect.Method m = com.snoworca.naruserve.Request.class
                .getMethod("attribute", String.class, Object.class);
        java.lang.reflect.AnnotatedType value = m.getAnnotatedParameterTypes()[1];
        assertTrue(value.isAnnotationPresent(org.jspecify.annotations.Nullable.class)
                || hasNullableTypeUse(value));
    }

    private static boolean hasNullableTypeUse(java.lang.reflect.AnnotatedType t) {
        for (java.lang.annotation.Annotation a : t.getAnnotations()) {
            if (a.annotationType().getSimpleName().equals("Nullable")) {
                return true;
            }
        }
        return false;
    }

    // SDS-AC-8; FR-HTTP-012 AC-5
    @Test
    public void sdsAc8_FR_HTTP_012_AC5_wrongTypeThrowsClassCastException() {
        DefaultRequest req = request("/x");
        req.attribute("k", "text");
        try {
            req.attribute("k", Integer.class);
            fail("expected ClassCastException");
        } catch (ClassCastException expected) {
            assertEquals("text", req.attribute("k", String.class));
        }
    }
}
