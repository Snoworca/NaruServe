package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.utils.auth.spi.ForwardingLoginThrottle;
import com.snoworca.naruserve.utils.auth.spi.LoginThrottle;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

/** The LoginThrottle SPI (IR-FLOW-003) and the in-memory counter storage (REL-FLOW-001). */
public class LoginThrottleTest {

    /** An app throttle implementing only the three one-argument methods. */
    static final class OwnLimits implements LoginThrottle {
        final List<String> calls = Collections.synchronizedList(new ArrayList<String>());

        @Override
        public long retryAfter(String key) {
            calls.add("retryAfter " + key);
            return 7L;
        }

        @Override
        public long recordFailure(String key) {
            calls.add("recordFailure " + key);
            return 11L;
        }

        @Override
        public void reset(String key) {
            calls.add("reset " + key);
        }
    }

    // IR-FLOW-003 AC-1: a throttle with only the one-argument methods compiles (Java here, Kotlin in
    // AuthWave4KotlinTest); its limit-argument methods return the one-argument results; usesLimitArguments() is false
    @Test
    public void IR_FLOW_003_AC1_oneArgumentThrottleDefaults() throws Exception {
        OwnLimits own = new OwnLimits();
        assertEquals(own.retryAfter("k"), own.retryAfter("k", 100, 900_000L));
        assertEquals(own.recordFailure("k"), own.recordFailure("k", 100, 900_000L));
        assertFalse(own.usesLimitArguments());
        assertEquals(4, own.calls.size());
    }

    // IR-FLOW-003 component (the in-memory half; the FileUserStore default and the doctor half wait for those
    // stores): InMemoryLoginThrottle honours the arguments and reports usesLimitArguments() true
    @Test
    public void inMemoryThrottleHonoursTheArguments() {
        AtomicLong now = new AtomicLong(1_000_000L);
        InMemoryLoginThrottle t = InMemoryLoginThrottle.builder().timeSource(now::get).build();
        assertTrue(t.usesLimitArguments());
        assertEquals(0L, t.recordFailure("k", 3, 60_000L));
        now.addAndGet(10_000L);
        assertEquals(0L, t.recordFailure("k", 3, 60_000L));
        now.addAndGet(10_000L);
        assertTrue(t.recordFailure("k", 3, 60_000L) > 0);
        assertTrue(t.retryAfter("k", 3, 60_000L) > 0);
        now.addAndGet(60_000L);
        assertEquals(0L, t.retryAfter("k", 3, 60_000L));
    }

    /** A metrics decorator overriding only the two recordFailure methods. */
    static final class Metrics extends ForwardingLoginThrottle {
        final List<String> counted = new ArrayList<>();

        Metrics(LoginThrottle inner) {
            super(inner);
        }

        @Override
        public long recordFailure(String key) throws Exception {
            counted.add(key);
            return super.recordFailure(key);
        }

        @Override
        public long recordFailure(String key, int max, long windowMillis) throws Exception {
            counted.add(key + " " + max + "/" + windowMillis);
            return super.recordFailure(key, max, windowMillis);
        }
    }

    /** Records which overload reached it and with which limits. */
    static final class Recording implements LoginThrottle {
        final List<String> calls = new ArrayList<>();

        @Override
        public long retryAfter(String key) {
            calls.add("retryAfter(" + key + ")");
            return 0L;
        }

        @Override
        public long recordFailure(String key) {
            calls.add("recordFailure(" + key + ")");
            return 0L;
        }

        @Override
        public void reset(String key) {
            calls.add("reset(" + key + ")");
        }

        @Override
        public long retryAfter(String key, int max, long windowMillis) {
            calls.add("retryAfter(" + key + ", " + max + ", " + windowMillis + ")");
            return 0L;
        }

        @Override
        public long recordFailure(String key, int max, long windowMillis) {
            calls.add("recordFailure(" + key + ", " + max + ", " + windowMillis + ")");
            return 0L;
        }

        @Override
        public boolean usesLimitArguments() {
            return true;
        }
    }

    // IR-FLOW-003 AC-3: a decorator extending ForwardingLoginThrottle over InMemoryLoginThrottle declares true and both
    // limit-argument methods reach the wrapped throttle with the same max and windowMillis
    @Test
    public void IR_FLOW_003_AC3_forwardingDecorator() throws Exception {
        Metrics m = new Metrics(new InMemoryLoginThrottle());
        assertTrue(m.usesLimitArguments());
        m.recordFailure("ip", 2, 60_000L);
        assertTrue(m.recordFailure("ip", 2, 60_000L) > 0);
        assertTrue(m.retryAfter("ip", 2, 60_000L) > 0);
        assertEquals(0L, m.retryAfter("ip", 3, 60_000L));
        Recording r = new Recording();
        Metrics over = new Metrics(r);
        over.retryAfter("k", 100, 900_000L);
        over.recordFailure("k", 100, 900_000L);
        over.reset("k");
        assertTrue(over.usesLimitArguments());
        assertEquals("[retryAfter(k, 100, 900000), recordFailure(k, 100, 900000), reset(k)]", r.calls.toString());
        assertEquals("[k 100/900000]", over.counted.toString());
        assertFalse(new Metrics(new OwnLimits()).usesLimitArguments());
    }

    // IR-FLOW-003 AC-5: retryAfter 0 allows, a positive value is the remaining block in ms; recordFailure returns the
    // new block in ms, 0 while not blocked
    @Test
    public void IR_FLOW_003_AC5_returnValues() {
        AtomicLong now = new AtomicLong(5_000_000L);
        InMemoryLoginThrottle t = InMemoryLoginThrottle.builder().timeSource(now::get).build();
        assertEquals(0L, t.retryAfter("u", 2, 1_000L));
        assertEquals(0L, t.recordFailure("u", 2, 1_000L));
        now.addAndGet(200L);
        assertEquals(800L, t.recordFailure("u", 2, 1_000L));
        now.addAndGet(300L);
        assertEquals(500L, t.retryAfter("u", 2, 1_000L));
        t.reset("u");
        assertEquals(0L, t.retryAfter("u", 2, 1_000L));
        // the one-argument methods use the throttle's own limits (5 per 15 minutes by default)
        for (int i = 0; i < 4; i++) {
            assertEquals(0L, t.recordFailure("own"));
        }
        assertEquals(15 * 60_000L, t.recordFailure("own"));
        for (int bad : new int[] {0, -1}) {
            try {
                t.retryAfter("own", bad, 1_000L);
                org.junit.Assert.fail("accepted max " + bad);
            } catch (IllegalArgumentException expected) {
                // the limits are checked like recordFailure's
            }
        }
    }

    // IR-FLOW-003 AC-6: the Javadoc tells decorators to extend ForwardingLoginThrottle and says a throttle not
    // overriding the limit-argument methods applies its own limits
    @Test
    public void IR_FLOW_003_AC6_javadocStatesTheRules() throws Exception {
        String src = new String(Files.readAllBytes(new File(
                "src/main/java/com/snoworca/naruserve/utils/auth/spi/LoginThrottle.java").toPath()),
                StandardCharsets.UTF_8).replaceAll("\\s*\\*\\s*", " ");
        assertTrue(src.contains("Decorators extend {@link ForwardingLoginThrottle}"));
        assertTrue(src.contains("an implementation that does not override them applies its own limits"));
    }

    // REL-FLOW-001 AC-1: with a cap of 3, a fourth key evicts the oldest and logs exactly one WARN; later evictions
    // log none
    @Test
    public void REL_FLOW_001_AC1_capEvictsOldestWithOneWarn() {
        RecordingLogger log = new RecordingLogger();
        AtomicLong now = new AtomicLong(1L);
        InMemoryLoginThrottle t = InMemoryLoginThrottle.builder().maxKeys(3).logger(log).timeSource(now::get).build();
        for (String k : new String[] {"a", "b", "c"}) {
            t.recordFailure(k, 1, 60_000L);
        }
        assertTrue(t.retryAfter("a", 1, 60_000L) > 0); // reading a key does not make it younger
        assertEquals(0, log.count("WARN"));
        t.recordFailure("d", 1, 60_000L);
        assertEquals(1, log.count("WARN"));
        assertEquals(0L, t.retryAfter("a", 1, 60_000L)); // evicted: forgotten
        assertTrue(t.retryAfter("b", 1, 60_000L) > 0);
        assertTrue(t.retryAfter("d", 1, 60_000L) > 0);
        t.recordFailure("e", 1, 60_000L);
        t.recordFailure("f", 1, 60_000L);
        assertEquals(1, log.count("WARN"));
        assertTrue(log.lines.get(0), log.lines.get(0).contains("cap of 3 keys"));
    }

    // REL-FLOW-001 AC-2: 100,000 keys on a JVM, 10,000 on Android
    @Test
    public void REL_FLOW_001_AC2_defaultCapByRuntime() {
        assertEquals(100_000, new InMemoryLoginThrottle().getMaxKeys());
        assertEquals(100_000, InMemoryLoginThrottle.defaultMaxKeys("OpenJDK 64-Bit Server VM", "Azul Systems, Inc."));
        assertEquals(10_000, InMemoryLoginThrottle.defaultMaxKeys("Dalvik", "The Android Project"));
        assertEquals(10_000, InMemoryLoginThrottle.defaultMaxKeys(null, "The Android Project"));
    }
}
