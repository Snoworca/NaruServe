package com.snoworca.naruserve.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.BodyAlreadyConsumedException;
import com.snoworca.naruserve.Request;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class BodyStateTest {

    private static void assertBeginRefused(BodyState s, BodyState.Kind kind) {
        BodyState.State before = s.state();
        try {
            s.begin(kind);
            fail("expected BodyAlreadyConsumedException in " + before);
        } catch (BodyAlreadyConsumedException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(before.name()));
            assertEquals(before, s.state());
        }
    }

    private static BodyState consumed(BodyState.Kind kind) {
        BodyState s = new BodyState();
        s.begin(kind);
        s.complete();
        return s;
    }

    // SDS-AC-9; FR-HTTP-019 AC-3
    @Test
    public void sdsAc9_FR_HTTP_019_AC3_secondReadOfAnyKindAfterAFirstReadThrows() {
        for (BodyState.Kind first : BodyState.Kind.values()) {
            for (BodyState.Kind second : BodyState.Kind.values()) {
                assertBeginRefused(consumed(first), second);
            }
        }
    }

    // SDS-AC-9; FR-HTTP-019 AC-3
    @Test
    public void sdsAc9_FR_HTTP_019_AC3_beginWhileParsingThrowsNamingParsing() {
        BodyState s = new BodyState();
        s.begin(BodyState.Kind.RAW);
        assertBeginRefused(s, BodyState.Kind.JSON);
    }

    // SDS-AC-10; FR-HTTP-019 AC-4
    @Test
    public void sdsAc10_FR_HTTP_019_AC4_repeatedSharedParseReturnsTheSameObjectParsedOnce() throws Exception {
        BodyState s = new BodyState();
        AtomicInteger runs = new AtomicInteger();
        Object first = s.sharedParse(() -> {
            runs.incrementAndGet();
            return new Object();
        });
        Object second = s.sharedParse(() -> {
            runs.incrementAndGet();
            return new Object();
        });
        assertSame(first, second);
        assertEquals(1, runs.get());
        assertEquals(BodyState.State.CONSUMED, s.state());
    }

    // SDS-AC-10; FR-HTTP-019 AC-4
    @Test
    public void sdsAc10_FR_HTTP_019_AC4_sharedParseAndBeginExcludeEachOther() throws Exception {
        BodyState afterBegin = new BodyState();
        afterBegin.begin(BodyState.Kind.JSON);
        afterBegin.complete();
        try {
            afterBegin.sharedParse(() -> "parsed");
            fail("expected BodyAlreadyConsumedException");
        } catch (BodyAlreadyConsumedException expected) {
            // refused
        }
        BodyState afterShared = new BodyState();
        afterShared.sharedParse(() -> "parsed");
        assertBeginRefused(afterShared, BodyState.Kind.FORM);
        assertBeginRefused(afterShared, BodyState.Kind.RAW);
    }

    // SDS-AC-10; FR-HTTP-019 AC-4
    @Test
    public void sdsAc10_FR_HTTP_019_AC4_sharedParseWhileAnotherReaderIsParsingThrows() throws Exception {
        BodyState s = new BodyState();
        s.begin(BodyState.Kind.JSON);
        try {
            s.sharedParse(() -> "parsed");
            fail("expected BodyAlreadyConsumedException");
        } catch (BodyAlreadyConsumedException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("PARSING"));
        }
    }

    // SDS-AC-10; FR-HTTP-019 AC-4
    @Test
    public void sdsAc10_FR_HTTP_019_AC4_concurrentSharedParseWaitsAndGetsTheSameResult() throws Exception {
        BodyState s = new BodyState();
        CountDownLatch parsing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger runs = new AtomicInteger();
        Object parsed = new Object();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Object> first = pool.submit(() -> s.sharedParse(() -> {
                runs.incrementAndGet();
                parsing.countDown();
                release.await();
                return parsed;
            }));
            assertTrue(parsing.await(5, TimeUnit.SECONDS));
            Future<Object> second = pool.submit(() -> s.sharedParse(() -> {
                runs.incrementAndGet();
                return new Object();
            }));
            release.countDown();
            assertSame(parsed, first.get(5, TimeUnit.SECONDS));
            assertSame(parsed, second.get(5, TimeUnit.SECONDS));
            assertEquals(1, runs.get());
        } finally {
            pool.shutdownNow();
        }
    }

    // SDS-AC-11; FR-HTTP-019 AC-2
    @Test
    public void sdsAc11_FR_HTTP_019_AC2_closeDoesNotWaitForARunningParser() throws Exception {
        BodyState s = new BodyState();
        CountDownLatch parsing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Object> parse = pool.submit(() -> s.sharedParse(() -> {
                parsing.countDown();
                release.await();
                return "parsed";
            }));
            assertTrue(parsing.await(5, TimeUnit.SECONDS));
            s.close();
            assertEquals(BodyState.State.CLOSED, s.state());
            release.countDown();
            parse.get(5, TimeUnit.SECONDS);
            assertEquals(BodyState.State.CLOSED, s.state());
            try {
                s.sharedParse(() -> "again");
                fail("expected BodyAlreadyConsumedException after close");
            } catch (BodyAlreadyConsumedException expected) {
                // closed
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // SDS-AC-11; FR-HTTP-019 AC-2
    @Test
    public void sdsAc11_FR_HTTP_019_AC2_completeAfterCloseKeepsClosed() {
        BodyState s = new BodyState();
        s.begin(BodyState.Kind.RAW);
        s.close();
        s.complete();
        assertEquals(BodyState.State.CLOSED, s.state());
    }

    // SDS-AC-10; FR-HTTP-019 AC-4
    @Test
    public void sdsAc10_FR_HTTP_019_AC4_failedParserLeavesConsumedWithoutCache() throws Exception {
        BodyState s = new BodyState();
        IOException boom = new IOException("boom");
        try {
            s.sharedParse(() -> {
                throw boom;
            });
            fail("expected the parser failure");
        } catch (IOException e) {
            assertSame(boom, e);
        }
        assertEquals(BodyState.State.CONSUMED, s.state());
        try {
            s.sharedParse(() -> "again");
            fail("expected BodyAlreadyConsumedException");
        } catch (BodyAlreadyConsumedException expected) {
            // no cache after a failure
        }
        assertBeginRefused(s, BodyState.Kind.RAW);
    }

    // SDS-AC-11; FR-HTTP-019 AC-2
    @Test
    public void sdsAc11_FR_HTTP_019_AC2_stateMovesUnreadParsingConsumedAndCloseFromAny() throws Exception {
        BodyState s = new BodyState();
        assertEquals(BodyState.State.UNREAD, s.state());
        s.begin(BodyState.Kind.JSON);
        assertEquals(BodyState.State.PARSING, s.state());
        s.complete();
        assertEquals(BodyState.State.CONSUMED, s.state());
        s.close();
        assertEquals(BodyState.State.CLOSED, s.state());

        BodyState unread = new BodyState();
        unread.close();
        assertEquals(BodyState.State.CLOSED, unread.state());
        assertBeginRefused(unread, BodyState.Kind.RAW);

        BodyState parsing = new BodyState();
        parsing.begin(BodyState.Kind.RAW);
        parsing.close();
        assertEquals(BodyState.State.CLOSED, parsing.state());

        BodyState shared = new BodyState();
        shared.sharedParse(() -> "parsed");
        shared.close();
        assertEquals(BodyState.State.CLOSED, shared.state());
        try {
            shared.sharedParse(() -> "parsed");
            fail("expected BodyAlreadyConsumedException after close");
        } catch (BodyAlreadyConsumedException expected) {
            // closed
        }
    }

    // SDS-AC-11; FR-HTTP-019 AC-1
    @Test
    public void sdsAc11_FR_HTTP_019_AC1_exceptionIsPublicUncheckedInRootPackage() {
        Class<BodyAlreadyConsumedException> c = BodyAlreadyConsumedException.class;
        assertEquals("com.snoworca.naruserve", c.getPackage().getName());
        assertTrue(Modifier.isPublic(c.getModifiers()));
        assertSame(RuntimeException.class, c.getSuperclass());
        assertEquals("m", new BodyAlreadyConsumedException("m").getMessage());
    }

    // SDS-AC-11; FR-HTTP-019 AC-5, re-pointed by IR-JSON-002 AC-8: body(Class) and body(TypeRef) are the only readers
    @Test
    public void sdsAc11_FR_HTTP_019_AC5_requestHasNoPublicBodyReadingMethod() {
        List<String> bodyNames = Arrays.asList("json", "form", "file", "files", "parts", "text", "bytes",
                "stream", "inputStream", "raw");
        List<String> bodyReaders = new java.util.ArrayList<>();
        for (Method m : Request.class.getMethods()) {
            if (m.getName().equals("body")) {
                bodyReaders.add(Arrays.toString(m.getParameterTypes()));
            }
            assertFalse(m.getName(), bodyNames.contains(m.getName()));
            assertFalse(m.getName(), java.io.InputStream.class.isAssignableFrom(m.getReturnType()));
            assertFalse(m.getName(), m.getReturnType() == byte[].class);
        }
        java.util.Collections.sort(bodyReaders);
        assertEquals(Arrays.asList("[class com.snoworca.naruserve.TypeRef]", "[class java.lang.Class]"), bodyReaders);
        Method body;
        try {
            body = DefaultRequest.class.getDeclaredMethod("body");
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
        assertFalse(Modifier.isPublic(body.getModifiers()));
        assertSame(BodyState.class, body.getReturnType());
    }
}
