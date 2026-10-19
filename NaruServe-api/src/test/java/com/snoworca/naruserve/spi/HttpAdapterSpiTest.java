package com.snoworca.naruserve.spi;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class HttpAdapterSpiTest {

    private static final List<Class<?>> SPI_TYPES = Arrays.<Class<?>>asList(HttpAdapter.class, ExchangeHandler.class,
            HttpExchange.class, UpgradeHandler.class, StreamDrain.class);

    private static List<Map.Entry<String, String>> headers(String... pairs) {
        List<Map.Entry<String, String>> out = new ArrayList<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.add(new SimpleEntry<>(pairs[i], pairs[i + 1]));
        }
        return out;
    }

    private static InMemoryHttpAdapter bound(ExchangeHandler handler) throws IOException {
        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
        adapter.bind("127.0.0.1", 0, handler);
        return adapter;
    }

    private static String utf8(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    // SDS-AC-1; IR-HTTP-003 AC-1
    @Test
    public void sdsAc1_IR_HTTP_003_AC1_headersInReceivedOrderWithDuplicatesAndRequestLine() throws Exception {
        AtomicReference<HttpExchange> seen = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter("10.0.0.7", true);
        adapter.bind("0.0.0.0", 0, exchange -> {
            seen.set(exchange);
            try {
                byte[] buf = new byte[16];
                int n = exchange.requestBody().read(buf);
                body.set(new String(buf, 0, n, StandardCharsets.UTF_8));
                exchange.commit(204, Collections.<Map.Entry<String, String>>emptyList(), 0);
                exchange.finish();
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        });
        adapter.send(ClientRequest.of("POST", "/p?k=v").header("X-Tag", "a").header("x-tag", "b")
                .header("Host", "h").body("hi".getBytes(StandardCharsets.UTF_8)));
        HttpExchange e = seen.get();
        assertEquals(headers("X-Tag", "a", "x-tag", "b", "Host", "h"), new ArrayList<>(e.headers()));
        assertTrue(e.headers() instanceof List);
        assertEquals("POST", e.methodToken());
        assertEquals("/p", e.rawPath());
        assertEquals("k=v", e.rawQuery());
        assertEquals("HTTP/1.1", e.httpVersion());
        assertEquals("10.0.0.7", e.remoteAddress());
        assertTrue(e.isSecure());
        assertEquals("hi", body.get());
    }

    // SDS-AC-2; IR-HTTP-003 AC-2
    @Test
    public void sdsAc2_IR_HTTP_003_AC2_oneCommitCarriesStatusHeadersLengthAndSecondCommitIsRefused() throws Exception {
        AtomicReference<Throwable> second = new AtomicReference<>();
        InMemoryHttpAdapter adapter = bound(exchange -> {
            try {
                OutputStream out = exchange.commit(201, headers("X-A", "1", "X-A", "2"), 3);
                out.write("abc".getBytes(StandardCharsets.UTF_8));
                try {
                    exchange.commit(500, Collections.<Map.Entry<String, String>>emptyList(), 0);
                } catch (Throwable t) {
                    second.set(t);
                }
                exchange.finish();
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        });
        ClientResponse r = adapter.send(ClientRequest.of("GET", "/"));
        assertEquals(201, r.status());
        assertEquals("1", r.header("x-a"));
        assertEquals(headers("X-A", "1", "X-A", "2"), new ArrayList<>(r.headers()));
        assertEquals("abc", utf8(r.body()));
        assertFalse(r.abnormallyClosed());
        assertTrue(String.valueOf(second.get()), second.get() instanceof IllegalStateException);
    }

    // SDS-AC-4; IR-HTTP-004 AC-3
    @Test
    public void sdsAc4_IR_HTTP_004_AC3_drainRunsOnAdapterThreadAndEndsOnlyWhenEndIsReported() throws Exception {
        CountDownLatch end = new CountDownLatch(1);
        CountDownLatch drainStarted = new CountDownLatch(1);
        AtomicReference<Thread> handlerThread = new AtomicReference<>();
        AtomicReference<Thread> drainThread = new AtomicReference<>();
        AtomicReference<Boolean> returnedBeforeEnd = new AtomicReference<>();
        CountDownLatch handlerReturned = new CountDownLatch(1);
        InMemoryHttpAdapter adapter = bound(exchange -> {
            handlerThread.set(Thread.currentThread());
            try {
                exchange.eventStream(200, headers("Content-Type", "text/event-stream"), out -> {
                    drainThread.set(Thread.currentThread());
                    out.write("a".getBytes(StandardCharsets.UTF_8));
                    drainStarted.countDown();
                    try {
                        end.await();
                    } catch (InterruptedException e) {
                        throw new IOException(e);
                    }
                    out.write("b".getBytes(StandardCharsets.UTF_8));
                });
            } catch (IOException e) {
                throw new AssertionError(e);
            }
            returnedBeforeEnd.set(end.getCount() == 1);
            handlerReturned.countDown();
        });
        Future<ClientResponse> f = adapter.submit(ClientRequest.of("GET", "/events"));
        assertTrue(drainStarted.await(5, TimeUnit.SECONDS));
        try {
            f.get(150, TimeUnit.MILLISECONDS);
            fail("response completed before the end was reported");
        } catch (TimeoutException expected) {
            // the drain is still running
        }
        // eventStream blocking until the drain ends would leave this latch at 1 while the end is still unreported.
        assertTrue("eventStream did not return while the drain ran", handlerReturned.await(5, TimeUnit.SECONDS));
        assertTrue("eventStream returned only after the end", Boolean.TRUE.equals(returnedBeforeEnd.get()));
        end.countDown();
        ClientResponse r = f.get(5, TimeUnit.SECONDS);
        assertEquals(200, r.status());
        assertEquals("ab", utf8(r.body()));
        assertNotNull(drainThread.get());
        assertNotEquals(handlerThread.get(), drainThread.get());
    }

    // SDS-AC-4; IR-HTTP-004 AC-1
    @Test
    public void sdsAc4_IR_HTTP_004_AC1_sendFileWritesExactlyTheByteRange() throws Exception {
        File file = File.createTempFile("naru-spi", ".bin");
        file.deleteOnExit();
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write("0123456789".getBytes(StandardCharsets.UTF_8));
        }
        InMemoryHttpAdapter adapter = bound(exchange -> {
            try {
                exchange.sendFile(206, headers("Content-Range", "bytes 2-5/10"), file, 2, 4);
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        });
        ClientResponse r = adapter.send(ClientRequest.of("GET", "/f"));
        assertEquals(206, r.status());
        assertEquals("bytes 2-5/10", r.header("Content-Range"));
        assertEquals("2345", utf8(r.body()));
    }

    // SDS-AC-4; IR-HTTP-004 AC-2
    @Test
    public void sdsAc4_IR_HTTP_004_AC2_upgradeWrites101ThenHandsOverTheExchangeStreams() throws Exception {
        AtomicReference<String> readFromClient = new AtomicReference<>();
        InMemoryHttpAdapter adapter = bound(exchange -> {
            try {
                InputStream body = exchange.requestBody();
                exchange.upgrade(headers("Upgrade", "websocket", "Connection", "Upgrade"), (in, out) -> {
                    assertSame(body, in);
                    byte[] buf = new byte[8];
                    int n = in.read(buf);
                    readFromClient.set(new String(buf, 0, n, StandardCharsets.UTF_8));
                    out.write("pong".getBytes(StandardCharsets.UTF_8));
                });
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        });
        ClientResponse r = adapter.send(ClientRequest.of("GET", "/ws").header("Upgrade", "websocket")
                .body("ping".getBytes(StandardCharsets.UTF_8)));
        assertEquals(101, r.status());
        assertEquals("websocket", r.header("upgrade"));
        assertTrue(r.upgradeHandedOver());
        assertEquals("ping", readFromClient.get());
        assertEquals("pong", utf8(r.body()));
    }

    // SDS-AC-5; IR-HTTP-004 AC-5
    @Test
    public void sdsAc5_IR_HTTP_004_AC5_portZeroGetsAStablePositivePortAndAPositivePortIsKept() throws Exception {
        InMemoryHttpAdapter unbound = new InMemoryHttpAdapter();
        try {
            unbound.boundPort();
            fail("boundPort before bind");
        } catch (IllegalStateException expected) {
            // not bound yet
        }
        HttpAdapter zero = bound(exchange -> { });
        int port = zero.boundPort();
        assertTrue("port " + port, port >= 49152);
        assertEquals(port, zero.boundPort());
        assertEquals(port, zero.boundPort());
        HttpAdapter other = bound(exchange -> { });
        assertNotEquals(port, other.boundPort());

        HttpAdapter fixed = new InMemoryHttpAdapter();
        fixed.bind("127.0.0.1", 8081, exchange -> { });
        assertEquals(8081, fixed.boundPort());
    }

    // SDS-AC-6; IR-HTTP-004 AC-6
    @Test
    public void sdsAc6_IR_HTTP_004_AC6_inFlightExchangeIsForceClosedAtTheDeadline() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        InMemoryHttpAdapter adapter = bound(exchange -> {
            try {
                OutputStream out = exchange.commit(200, Collections.<Map.Entry<String, String>>emptyList(), -1);
                entered.countDown();
                proceed.await();
                out.write('x');
                exchange.finish();
            } catch (IOException | InterruptedException e) {
                // force-closed: the write fails
            }
        });
        Future<ClientResponse> f = adapter.submit(ClientRequest.of("GET", "/slow"));
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        long t0 = System.nanoTime();
        adapter.stop(100, TimeUnit.MILLISECONDS);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        assertTrue("stop waited " + elapsedMs + " ms", elapsedMs >= 90 && elapsedMs < 2000);
        proceed.countDown();
        assertTrue(f.get(5, TimeUnit.SECONDS).abnormallyClosed());

        long r0 = System.nanoTime();
        adapter.release();
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - r0) <= 1500);
    }

    private static void collect(Type t, List<String> out) {
        if (t instanceof Class) {
            Class<?> c = (Class<?>) t;
            while (c.isArray()) {
                c = c.getComponentType();
            }
            if (!c.isPrimitive()) {
                out.add(c.getName());
            }
        } else if (t instanceof ParameterizedType) {
            collect(((ParameterizedType) t).getRawType(), out);
            for (Type a : ((ParameterizedType) t).getActualTypeArguments()) {
                collect(a, out);
            }
        } else if (t instanceof WildcardType) {
            for (Type b : ((WildcardType) t).getUpperBounds()) {
                collect(b, out);
            }
            for (Type b : ((WildcardType) t).getLowerBounds()) {
                collect(b, out);
            }
        } else if (t instanceof GenericArrayType) {
            collect(((GenericArrayType) t).getGenericComponentType(), out);
        }
    }

    private static boolean allowed(String name) {
        if (name.startsWith("java.nio.file.") || name.startsWith("java.net.http.")
                || name.equals("java.util.concurrent.CompletableFuture")) {
            return false;
        }
        return name.startsWith("java.") || name.startsWith("com.snoworca.naruserve.")
                || name.startsWith("org.jspecify.");
    }

    // SDS-AC-7; CON-HTTP-001 AC-1, IR-HTTP-004 AC-4
    @Test
    public void sdsAc7_CON_HTTP_001_AC1_IR_HTTP_004_AC4_spiSignaturesUseOnlyAllowedTypesFromApiMain() {
        for (Class<?> type : SPI_TYPES) {
            assertTrue(type + " is an interface", type.isInterface());
            String location = type.getProtectionDomain().getCodeSource().getLocation().getPath();
            // The fixtures plugin puts NaruServe-api main on the test classpath as its jar or its classes dir.
            assertTrue(type + " from " + location, location.contains("NaruServe-api/build/classes/java/main")
                    || location.endsWith("NaruServe-api/build/libs/NaruServe-api-0.1.0-SNAPSHOT.jar"));
            for (java.lang.reflect.Method m : type.getDeclaredMethods()) {
                List<String> refs = new ArrayList<>();
                collect(m.getGenericReturnType(), refs);
                for (Type p : m.getGenericParameterTypes()) {
                    collect(p, refs);
                }
                for (Type x : m.getGenericExceptionTypes()) {
                    collect(x, refs);
                }
                for (java.lang.annotation.Annotation a : m.getAnnotatedReturnType().getAnnotations()) {
                    refs.add(a.annotationType().getName());
                }
                for (String ref : refs) {
                    assertTrue(type.getSimpleName() + "." + m.getName() + " references " + ref, allowed(ref));
                }
            }
        }
        for (Class<?> callback : new Class<?>[] {ExchangeHandler.class, UpgradeHandler.class, StreamDrain.class}) {
            assertTrue(callback + " is @FunctionalInterface", callback.isAnnotationPresent(FunctionalInterface.class));
        }
    }

    // CON-HTTP-001 AC-2
    @Test
    public void CON_HTTP_001_AC2_dispatcherIsApiMainAndReferencesNoEngineOrPlatformClass() throws Exception {
        Class<?> dispatcher = com.snoworca.naruserve.internal.Dispatcher.class;
        String location = dispatcher.getProtectionDomain().getCodeSource().getLocation().getPath();
        assertTrue(location, location.contains("NaruServe-api/build/classes/java/main")
                || location.matches(".*/NaruServe-api/build/libs/NaruServe-api-[^/]*[0-9T]\\.jar"));
        java.util.regex.Pattern forbidden = java.util.regex.Pattern.compile(
                "(android|javax/servlet|jakarta/servlet|com/google/gson|com/snoworca/tinyhttpd|kotlin)/");
        List<Class<?>> scanned = new ArrayList<>(SPI_TYPES);
        scanned.add(dispatcher);
        for (Class<?> c : scanned) {
            String resource = c.getName().replace('.', '/') + ".class";
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            try (java.io.InputStream in = c.getClassLoader().getResourceAsStream(resource)) {
                assertNotNull(resource, in);
                byte[] chunk = new byte[8192];
                for (int n; (n = in.read(chunk)) > 0; ) {
                    buf.write(chunk, 0, n);
                }
            }
            // ISO-8859-1 keeps every byte, so the constant pool's internal class names survive the decode.
            java.util.regex.Matcher m = forbidden.matcher(new String(buf.toByteArray(), StandardCharsets.ISO_8859_1));
            if (m.find()) {
                fail(resource + " references " + m.group());
            }
        }
        // The scan bites: the same pattern finds kotlin/ in a Kotlin-compiled test class.
        assertTrue(forbidden.matcher("Lkotlin/jvm/internal/Intrinsics;").find());
    }

    /** Implements only the four wave 3 methods. */
    private static final class Wave3Adapter implements HttpAdapter {
        final List<String> binds = new ArrayList<>();

        @Override
        public void bind(String host, int port, ExchangeHandler handler) {
            binds.add(host + ":" + port);
        }

        @Override
        public int boundPort() {
            return 0;
        }

        @Override
        public void stop(long timeout, TimeUnit unit) {
        }

        @Override
        public void release() {
        }
    }

    private static ServerSettings settings(String address, int port) {
        return ServerSettings.builder().bindAddress(address).port(port)
                .logger(new com.snoworca.naruserve.testing.RecordingLogger()).errorRenderer(status -> "{}").build();
    }

    // SDS-AC-4 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-5-4.sds.md; IR-ENGINE-002 AC-1
    @Test
    public void sdsAc4_IR_ENGINE_002_AC1_aWave3OnlyAdapterBindsOnceThroughTheDefaultMethod() throws Exception {
        Wave3Adapter adapter = new Wave3Adapter();
        adapter.bind(settings("0.0.0.0", 8123), exchange -> { });
        assertEquals(Collections.singletonList("0.0.0.0:8123"), adapter.binds);
        assertTrue(HttpAdapter.class.getMethod("bind", ServerSettings.class, ExchangeHandler.class).isDefault());
        assertFalse(HttpAdapter.class.getMethod("bind", String.class, int.class, ExchangeHandler.class)
                .isDefault());

        InMemoryHttpAdapter inMemory = new InMemoryHttpAdapter();
        inMemory.bind(settings("127.0.0.1", 8124), exchange -> { });
        assertEquals(8124, inMemory.boundPort());
    }

    // IR-ENGINE-002 AC-3, IR-ENGINE-002 AC-5
    @Test
    public void IR_ENGINE_002_AC3_settingsIsAFinalGetterOnlyValueOfAllowedTypes() throws Exception {
        assertTrue(java.lang.reflect.Modifier.isFinal(ServerSettings.class.getModifiers()));
        assertTrue(java.lang.reflect.Modifier.isPublic(ServerSettings.class.getModifiers()));
        assertTrue(ErrorRenderer.class.isAnnotationPresent(FunctionalInterface.class));
        assertEquals(String.class, ErrorRenderer.class.getMethod("render", int.class).getReturnType());
        for (Class<?> type : new Class<?>[] {ServerSettings.class, ErrorRenderer.class}) {
            for (java.lang.reflect.Method m : type.getDeclaredMethods()) {
                if (m.isSynthetic() || !java.lang.reflect.Modifier.isPublic(m.getModifiers())) {
                    continue;
                }
                boolean isStatic = java.lang.reflect.Modifier.isStatic(m.getModifiers());
                assertTrue(type.getSimpleName() + "." + m.getName() + " is a setter",
                        isStatic || type == ErrorRenderer.class || m.getParameterCount() == 0);
                List<String> refs = new ArrayList<>();
                collect(m.getGenericReturnType(), refs);
                for (Type p : m.getGenericParameterTypes()) {
                    collect(p, refs);
                }
                for (String ref : refs) {
                    assertTrue(type.getSimpleName() + "." + m.getName() + " references " + ref, allowed(ref));
                }
            }
            for (java.lang.reflect.Field f : type.getDeclaredFields()) {
                List<String> refs = new ArrayList<>();
                collect(f.getGenericType(), refs);
                for (String ref : refs) {
                    assertTrue(type.getSimpleName() + "." + f.getName() + " references " + ref, allowed(ref));
                }
            }
        }
        try {
            settings("127.0.0.1", 1).allowedHosts().add("x");
            fail("allowedHosts() is modifiable");
        } catch (UnsupportedOperationException expected) {
            // unmodifiable
        }
    }
}
