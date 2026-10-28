package com.snoworca.naruserve.spi;

import static com.snoworca.naruserve.testing.TestSupport.headers;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.TestSupport;
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
            HttpExchange.class, UpgradeHandler.class, StreamDrain.class, WebSocketEndpoint.class,
            WebSocketChannel.class);

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

    // SDS-AC-4; IR-HTTP-007 AC-3
    @Test
    public void sdsAc4_IR_HTTP_007_AC3_drainRunsOnAdapterThreadAndEndsOnlyWhenEndIsReported() throws Exception {
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

    // SDS-AC-4; IR-HTTP-007 AC-1
    @Test
    public void sdsAc4_IR_HTTP_007_AC1_sendFileWritesExactlyTheByteRange() throws Exception {
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

    // SDS-AC-4; IR-HTTP-007 AC-2
    @Test
    public void sdsAc4_IR_HTTP_007_AC2_upgradeWrites101ThenHandsOverTheExchangeStreams() throws Exception {
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

    // SDS-AC-5; IR-HTTP-007 AC-5
    @Test
    public void sdsAc5_IR_HTTP_007_AC5_portZeroGetsAStablePositivePortAndAPositivePortIsKept() throws Exception {
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

    // SDS-AC-6; IR-HTTP-007 AC-6
    @Test
    public void sdsAc6_IR_HTTP_007_AC6_inFlightExchangeIsForceClosedAtTheDeadline() throws Exception {
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

    // SDS-AC-7; CON-HTTP-001 AC-1, IR-HTTP-007 AC-4
    @Test
    public void sdsAc7_CON_HTTP_001_AC1_IR_HTTP_007_AC4_spiSignaturesUseOnlyAllowedTypesFromApiMain() {
        for (Class<?> type : SPI_TYPES) {
            assertTrue(type + " is an interface", type.isInterface());
            String location = type.getProtectionDomain().getCodeSource().getLocation().getPath();
            // The fixtures plugin puts NaruServe-api main on the test classpath as its jar or its classes dir.
            assertTrue(type + " from " + location, location.contains("NaruServe-api/build/classes/java/main")
                    || location.endsWith("NaruServe-api/build/libs/NaruServe-api-0.9.0.jar"));
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
        return TestSupport.settings().bindAddress(address).port(port).build();
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

    // Wave 7 part 1: SDS-AC-n below cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-7-1.sds.md.

    /** The nine SPI types of the wave 6 close 9ce5cc4. */
    private static final List<Class<?>> WAVE_6_TYPES = Arrays.<Class<?>>asList(AppFactory.class, ErrorRenderer.class,
            ExchangeHandler.class, HttpAdapter.class, HttpExchange.class, JsonCodec.class, ServerSettings.class,
            StreamDrain.class, UpgradeHandler.class);

    /** Every public method of {@link #WAVE_6_TYPES} at 9ce5cc4 (unchanged since 40b9665), copied by reflection. */
    private static final List<String> WAVE_6_SPI = Arrays.asList(
            "AppFactory.create()->com.snoworca.naruserve.RestApp",
            "ErrorRenderer.render(int)->java.lang.String",
            "ExchangeHandler.handle(com.snoworca.naruserve.spi.HttpExchange)->void",
            "HttpAdapter.bind(com.snoworca.naruserve.spi.ServerSettings,com.snoworca.naruserve.spi.ExchangeHandler)->void",
            "HttpAdapter.bind(java.lang.String,int,com.snoworca.naruserve.spi.ExchangeHandler)->void",
            "HttpAdapter.boundPort()->int",
            "HttpAdapter.release()->void",
            "HttpAdapter.stop(long,java.util.concurrent.TimeUnit)->void",
            "HttpExchange.abort()->void",
            "HttpExchange.commit(int,java.util.List<java.util.Map$Entry<java.lang.String, java.lang.String>>,long)"
                    + "->java.io.OutputStream",
            "HttpExchange.eventStream(int,java.util.List<java.util.Map$Entry<java.lang.String, java.lang.String>>,"
                    + "com.snoworca.naruserve.spi.StreamDrain)->void",
            "HttpExchange.finish()->void",
            "HttpExchange.headers()->java.util.List<java.util.Map$Entry<java.lang.String, java.lang.String>>",
            "HttpExchange.httpVersion()->java.lang.String",
            "HttpExchange.isSecure()->boolean",
            "HttpExchange.methodToken()->java.lang.String",
            "HttpExchange.rawPath()->java.lang.String",
            "HttpExchange.rawQuery()->java.lang.String",
            "HttpExchange.remoteAddress()->java.lang.String",
            "HttpExchange.requestBody()->java.io.InputStream",
            "HttpExchange.sendFile(int,java.util.List<java.util.Map$Entry<java.lang.String, java.lang.String>>,"
                    + "java.io.File,long,long)->void",
            "HttpExchange.upgrade(java.util.List<java.util.Map$Entry<java.lang.String, java.lang.String>>,"
                    + "com.snoworca.naruserve.spi.UpgradeHandler)->void",
            "JsonCodec.decode(java.io.Reader,java.lang.reflect.Type)->T",
            "JsonCodec.encode(java.lang.Object,java.io.Writer)->void",
            "ServerSettings.allowedHosts()->java.util.List<java.lang.String>",
            "ServerSettings.bindAddress()->java.lang.String",
            "ServerSettings.bodyReadTimeoutMillis()->long",
            "ServerSettings.builder()->com.snoworca.naruserve.spi.ServerSettings$Builder",
            "ServerSettings.errorRenderer()->com.snoworca.naruserve.spi.ErrorRenderer",
            "ServerSettings.headerTimeoutMillis()->long",
            "ServerSettings.logger()->com.snoworca.naruserve.Logger",
            "ServerSettings.maxConnections()->int",
            "ServerSettings.maxConnectionsPerAddress()->int",
            "ServerSettings.maxHeaderCount()->int",
            "ServerSettings.maxHeaderSize()->int",
            "ServerSettings.maxRequestLineLength()->int",
            "ServerSettings.maxSseStreams()->int",
            "ServerSettings.maxUpgradedConnections()->int",
            "ServerSettings.maxWorkers()->int",
            "ServerSettings.minBodyRate()->int",
            "ServerSettings.port()->int",
            "ServerSettings.writeTimeoutMillis()->long",
            "StreamDrain.drain(java.io.OutputStream)->void",
            "UpgradeHandler.upgraded(java.io.InputStream,java.io.OutputStream)->void");

    /** Public, non-synthetic declared methods of {@code type} as {@code Type.method(params)->return}. */
    private static List<String> signatures(Class<?> type) {
        List<String> out = new ArrayList<>();
        for (java.lang.reflect.Method m : type.getDeclaredMethods()) {
            if (m.isSynthetic() || !java.lang.reflect.Modifier.isPublic(m.getModifiers())) {
                continue;
            }
            StringBuilder sb = new StringBuilder(type.getSimpleName()).append('.').append(m.getName()).append('(');
            Type[] params = m.getGenericParameterTypes();
            for (int i = 0; i < params.length; i++) {
                sb.append(i == 0 ? "" : ",").append(params[i].getTypeName());
            }
            out.add(sb.append(")->").append(m.getGenericReturnType().getTypeName()).toString());
        }
        return out;
    }

    /** {@code name(Simple,...)->Simple} mapped to the declared exception types, for every declared method. */
    private static Map<String, String> shortSignatures(Class<?> type) {
        Map<String, String> out = new java.util.TreeMap<>();
        for (java.lang.reflect.Method m : type.getDeclaredMethods()) {
            if (m.isSynthetic()) {
                continue;
            }
            StringBuilder sb = new StringBuilder(m.getName()).append('(');
            Class<?>[] params = m.getParameterTypes();
            for (int i = 0; i < params.length; i++) {
                sb.append(i == 0 ? "" : ",").append(params[i].getSimpleName());
            }
            sb.append(")->").append(m.getReturnType().getSimpleName());
            out.put(sb.toString(), Arrays.toString(m.getExceptionTypes()));
        }
        return out;
    }

    // SDS-AC-1; IR-HTTP-007 AC-4, IR-WS-002 AC-5
    @Test
    public void sdsAc1_IR_HTTP_007_AC4_IR_WS_002_AC5_wave6SpiIsKeptAndOnlyWebSocketAndAttachAreAdded()
            throws Exception {
        List<String> now = new ArrayList<>();
        for (Class<?> type : WAVE_6_TYPES) {
            now.addAll(signatures(type));
        }
        List<String> missing = new ArrayList<>(WAVE_6_SPI);
        missing.removeAll(now);
        assertEquals("removed or changed since 9ce5cc4", Collections.emptyList(), missing);
        List<String> added = new ArrayList<>(now);
        added.removeAll(WAVE_6_SPI);
        Collections.sort(added);
        assertEquals(Arrays.asList(
                // IR-HTTP-009 AC-1: additive default preserves all historical abstract SPI members.
                "HttpExchange.onAbort(java.lang.Runnable)->void",
                "HttpExchange.webSocket(com.snoworca.naruserve.spi.WebSocketSettings,"
                        + "com.snoworca.naruserve.spi.WebSocketEndpoint)->void",
                "StreamDrain.abort()->void",
                "StreamDrain.attach(java.io.OutputStream)->void"), added);
        java.lang.reflect.Method webSocket =
                HttpExchange.class.getMethod("webSocket", WebSocketSettings.class, WebSocketEndpoint.class);
        assertTrue(webSocket.isDefault());
        assertEquals(Collections.<Class<?>>singletonList(IOException.class),
                Arrays.asList(webSocket.getExceptionTypes()));
        assertTrue(StreamDrain.class.getMethod("attach", OutputStream.class).isDefault());
    }

    /** An output stream that fails the test on any call. */
    private static final class Untouchable extends OutputStream {
        @Override
        public void write(int b) {
            throw new AssertionError("written");
        }

        @Override
        public void flush() {
            throw new AssertionError("flushed");
        }

        @Override
        public void close() {
            throw new AssertionError("closed");
        }
    }

    // SDS-AC-2; IR-HTTP-007 AC-4
    @Test
    public void sdsAc2_IR_HTTP_007_AC4_streamDrainStaysFunctionalAndEventStreamAttachesTheDrainStream()
            throws Exception {
        assertTrue(StreamDrain.class.isAnnotationPresent(FunctionalInterface.class));
        List<String> abstracts = new ArrayList<>();
        for (java.lang.reflect.Method m : StreamDrain.class.getDeclaredMethods()) {
            if (java.lang.reflect.Modifier.isAbstract(m.getModifiers())) {
                abstracts.add(m.getName() + Arrays.toString(m.getParameterTypes()));
            }
        }
        assertEquals(Collections.singletonList("drain[class java.io.OutputStream]"), abstracts);
        java.lang.reflect.Method attach = StreamDrain.class.getMethod("attach", OutputStream.class);
        assertTrue(attach.isDefault());
        assertEquals(0, attach.getExceptionTypes().length);
        StreamDrain lambda = out -> { };
        assertTrue(StreamDrain.class.getMethod("abort").isDefault()); // IR-HTTP-007 AC-3
        lambda.abort(); // additive no-op: existing lambda adapters remain valid
        lambda.attach(new Untouchable());

        AtomicReference<OutputStream> attached = new AtomicReference<>();
        AtomicReference<OutputStream> drained = new AtomicReference<>();
        AtomicReference<Boolean> attachedBeforeReturn = new AtomicReference<>();
        AtomicReference<Boolean> attachedBeforeDrain = new AtomicReference<>();
        InMemoryHttpAdapter adapter = bound(exchange -> {
            try {
                exchange.eventStream(200, headers("Content-Type", "text/event-stream"), new StreamDrain() {
                    @Override
                    public void attach(OutputStream out) {
                        attached.set(out);
                    }

                    @Override
                    public void drain(OutputStream out) throws IOException {
                        attachedBeforeDrain.set(attached.get() != null);
                        drained.set(out);
                        out.write("x".getBytes(StandardCharsets.UTF_8));
                    }
                });
            } catch (IOException e) {
                throw new AssertionError(e);
            }
            attachedBeforeReturn.set(attached.get() != null);
        });
        ClientResponse r = adapter.send(ClientRequest.of("GET", "/events"));
        assertEquals("attach ran before eventStream returned", Boolean.TRUE, attachedBeforeReturn.get());
        assertEquals("attach ran before drain", Boolean.TRUE, attachedBeforeDrain.get());
        assertNotNull(drained.get());
        assertSame(attached.get(), drained.get());
        assertEquals("x", utf8(r.body()));
    }

    // SDS-AC-3; IR-WS-002 AC-2
    @Test
    public void sdsAc3_IR_WS_002_AC2_onlyTheSendMethodsOfTheChannelThrowIOException() {
        assertTrue(WebSocketChannel.class.isInterface());
        Map<String, String> want = new java.util.TreeMap<>();
        want.put("sendText(String)->void", "[class java.io.IOException]");
        want.put("sendBinary(byte[])->void", "[class java.io.IOException]");
        want.put("ping(byte[])->void", "[class java.io.IOException]");
        want.put("close(int,String)->void", "[]");
        want.put("abort()->void", "[]");
        want.put("isOpen()->boolean", "[]");
        assertEquals(want, shortSignatures(WebSocketChannel.class));
    }

    /** The checkout root: the test JVM runs in the module directory or, from an IDE, in the root. */
    private static File checkoutRoot() {
        File dir = new File(System.getProperty("user.dir")).getAbsoluteFile();
        return new File(dir, "settings.gradle").isFile() ? dir : dir.getParentFile();
    }

    private static void javaFiles(File dir, List<File> out) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            if (f.isDirectory()) {
                javaFiles(f, out);
            } else if (f.getName().endsWith(".java")) {
                out.add(f);
            }
        }
    }

    private static String read(File f) throws IOException {
        return new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    // SDS-AC-4; IR-WS-002 AC-1
    @Test
    public void sdsAc4_IR_WS_002_AC1_endpointHasTheFourCallbacksAndNoAdapterSideImplementor() throws Exception {
        assertTrue(WebSocketEndpoint.class.isInterface());
        assertFalse(WebSocketEndpoint.class.isAnnotationPresent(FunctionalInterface.class));
        Map<String, String> want = new java.util.TreeMap<>();
        want.put("onOpen(WebSocketChannel)->void", "[]");
        want.put("onText(String)->void", "[]");
        want.put("onBinary(byte[])->void", "[]");
        want.put("onClose(int,String)->void", "[]");
        assertEquals(want, shortSignatures(WebSocketEndpoint.class));

        java.util.regex.Pattern implementsEndpoint = java.util.regex.Pattern.compile(
                "(implements|extends)[^{;]*\\bWebSocketEndpoint\\b|new\\s+WebSocketEndpoint\\s*\\(");
        // The scan bites.
        assertTrue(implementsEndpoint.matcher("final class X implements HttpExchange, WebSocketEndpoint {").find());
        List<String> implementors = new ArrayList<>();
        for (String tree : new String[] {"NaruServe-tinyhttpd/src/main/java", "NaruServe-api/src/testFixtures/java"}) {
            List<File> files = new ArrayList<>();
            javaFiles(new File(checkoutRoot(), tree), files);
            assertFalse(tree + " has no sources", files.isEmpty());
            for (File f : files) {
                if (implementsEndpoint.matcher(read(f)).find()) {
                    implementors.add(tree + "/" + f.getName());
                }
            }
        }
        assertEquals(Collections.emptyList(), implementors);
        assertFalse(WebSocketEndpoint.class.isAssignableFrom(
                com.snoworca.naruserve.testing.InMemoryWebSocketChannel.class));
    }

    /** Implements only the wave 3 exchange methods; every call fails the test. */
    private static final class Wave3Exchange implements HttpExchange {
        private static AssertionError touched(String name) {
            return new AssertionError("touched: " + name);
        }

        @Override
        public String methodToken() {
            throw touched("methodToken");
        }

        @Override
        public String rawPath() {
            throw touched("rawPath");
        }

        @Override
        public String rawQuery() {
            throw touched("rawQuery");
        }

        @Override
        public String httpVersion() {
            throw touched("httpVersion");
        }

        @Override
        public List<Map.Entry<String, String>> headers() {
            throw touched("headers");
        }

        @Override
        public InputStream requestBody() {
            throw touched("requestBody");
        }

        @Override
        public String remoteAddress() {
            throw touched("remoteAddress");
        }

        @Override
        public boolean isSecure() {
            throw touched("isSecure");
        }

        @Override
        public OutputStream commit(int status, List<Map.Entry<String, String>> headers, long contentLength) {
            throw touched("commit");
        }

        @Override
        public void finish() {
            throw touched("finish");
        }

        @Override
        public void abort() {
            throw touched("abort");
        }

        @Override
        public void sendFile(int status, List<Map.Entry<String, String>> headers, File file, long offset,
                long length) {
            throw touched("sendFile");
        }

        @Override
        public void upgrade(List<Map.Entry<String, String>> headers, UpgradeHandler handler) {
            throw touched("upgrade");
        }

        @Override
        public void eventStream(int status, List<Map.Entry<String, String>> headers, StreamDrain drain) {
            throw touched("eventStream");
        }
    }

    /** An endpoint that fails the test on any callback. */
    private static final class UntouchedEndpoint implements WebSocketEndpoint {
        @Override
        public void onOpen(WebSocketChannel channel) {
            throw new AssertionError("onOpen");
        }

        @Override
        public void onText(String text) {
            throw new AssertionError("onText");
        }

        @Override
        public void onBinary(byte[] data) {
            throw new AssertionError("onBinary");
        }

        @Override
        public void onClose(int code, String reason) {
            throw new AssertionError("onClose");
        }
    }

    // SDS-AC-5; IR-WS-002 AC-4
    @Test
    public void sdsAc5_IR_WS_002_AC4_anExchangeWithoutAnOverrideRefusesWebSocketUntouched() throws Exception {
        try {
            new Wave3Exchange().webSocket(new WebSocketSettings(1024, 0), new UntouchedEndpoint());
            fail("webSocket returned on an exchange that does not override it");
        } catch (UnsupportedOperationException expected) {
            assertEquals("webSocket is not supported by this adapter", expected.getMessage());
        }
    }

    /** Ends the session as soon as it opens. */
    private static final class ClosingEndpoint implements WebSocketEndpoint {
        @Override
        public void onOpen(WebSocketChannel channel) {
            channel.close(1000, "");
        }

        @Override
        public void onText(String text) {
        }

        @Override
        public void onBinary(byte[] data) {
        }

        @Override
        public void onClose(int code, String reason) {
        }
    }

    // SDS-AC-6; IR-WS-002 AC-3, IR-ENGINE-002 AC-3
    @Test(timeout = 20000)
    public void sdsAc6_IR_WS_002_AC3_settingsIsAFinalGetterOnlyValueAndReadsBack4096And2000() throws Exception {
        Class<?> type = WebSocketSettings.class;
        assertTrue(java.lang.reflect.Modifier.isFinal(type.getModifiers()));
        assertTrue(java.lang.reflect.Modifier.isPublic(type.getModifiers()));
        Map<String, Class<?>> getters = new java.util.TreeMap<>();
        for (java.lang.reflect.Method m : type.getDeclaredMethods()) {
            if (m.isSynthetic() || !java.lang.reflect.Modifier.isPublic(m.getModifiers())) {
                continue;
            }
            assertFalse(m.getName() + " is static", java.lang.reflect.Modifier.isStatic(m.getModifiers()));
            assertEquals(m.getName() + " is a setter", 0, m.getParameterCount());
            getters.put(m.getName(), m.getReturnType());
            List<String> refs = new ArrayList<>();
            collect(m.getGenericReturnType(), refs);
            for (String ref : refs) {
                assertTrue("WebSocketSettings." + m.getName() + " references " + ref, allowed(ref));
            }
        }
        Map<String, Class<?>> want = new java.util.TreeMap<>();
        want.put("maxMessageBytes", int.class);
        want.put("pingIntervalMillis", long.class);
        assertEquals(want, getters);
        for (java.lang.reflect.Field f : type.getDeclaredFields()) {
            assertTrue(f.getName(), java.lang.reflect.Modifier.isFinal(f.getModifiers()));
            assertTrue(f.getName(), f.getType() == int.class || f.getType() == long.class);
        }
        assertEquals(1, type.getConstructors().length);
        assertArrayEquals(new Class<?>[] {int.class, long.class}, type.getConstructors()[0].getParameterTypes());

        InMemoryHttpAdapter adapter = bound(exchange -> {
            try {
                exchange.webSocket(new WebSocketSettings(4096, 2000), new ClosingEndpoint());
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        });
        Future<ClientResponse> f = adapter.submit(ClientRequest.of("GET", "/ws"));
        com.snoworca.naruserve.testing.InMemoryWebSocketChannel channel = adapter.nextChannel(5, TimeUnit.SECONDS);
        assertEquals(101, f.get(5, TimeUnit.SECONDS).status());
        assertEquals(4096, channel.settings().maxMessageBytes());
        assertEquals(2000L, channel.settings().pingIntervalMillis());
    }
}
