package com.snoworca.naruserve.utils.auth.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.utils.auth.password.HashCost;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** Calibration and fixed cost (PERF-TOKEN-001). */
public class HashTimingTest {

    /** Reports a fixed time for any sample and records what it was asked. */
    private static final class Stub implements HashTiming.Measurer {
        final long nanos;
        final List<Integer> asked = new ArrayList<>();

        Stub(long nanos) {
            this.nanos = nanos;
        }

        @Override
        public long nanosFor(int iterations) {
            asked.add(iterations);
            return nanos;
        }
    }

    /** The sample time that makes {@code target} ms worth {@code iterations} iterations (slightly above). */
    private static long sampleFor(long targetMillis, double iterations) {
        return (long) (targetMillis * 1_000_000d * HashTiming.SAMPLE_ITERATIONS / iterations);
    }

    // PERF-TOKEN-001 AC-1: a measurement worth 123,456 iterations selects 120,000
    @Test
    public void PERF_TOKEN_001_AC1_roundsDownToTenThousand() {
        Stub stub = new Stub(sampleFor(250, 123_456.5));
        HashTiming t = HashTiming.resolve(HashCost.calibrate(250), stub);
        assertEquals(120_000, t.iterations());
        assertEquals(250_000_000L, t.timingNanos());
        assertEquals(1, stub.asked.size());
        assertEquals(HashTiming.SAMPLE_ITERATIONS, (int) stub.asked.get(0));
    }

    // PERF-TOKEN-001 AC-2: below 50,000 selects 50,000; above 600,000 selects 600,000
    @Test
    public void PERF_TOKEN_001_AC2_floorAndCeiling() {
        assertEquals(50_000, HashTiming.resolve(HashCost.calibrate(250), new Stub(sampleFor(250, 12_345)))
                .iterations());
        assertEquals(600_000, HashTiming.resolve(HashCost.calibrate(500), new Stub(sampleFor(500, 2_000_000)))
                .iterations());
        assertEquals(590_000, HashTiming.iterationsFor(500, 20_000, sampleFor(500, 599_999.5)));
    }

    // PERF-TOKEN-001 AC-3: fixed(n) uses n without calibrating; its one start-time timing sets only T
    @Test
    public void PERF_TOKEN_001_AC3_fixedUsesNAndTimesOnce() {
        Stub stub = new Stub(123_000_000L);
        HashTiming t = HashTiming.resolve(HashCost.fixed(77_777), stub);
        assertEquals(77_777, t.iterations());
        assertEquals(123_000_000L, t.timingNanos());
        assertEquals(1, stub.asked.size());
        assertEquals(77_777, (int) stub.asked.get(0));
        Passwords p = new Passwords(null, HashCost.fixed(1_234), null, stub);
        String stored = p.hash("pw".toCharArray());
        assertTrue(stored, stored.startsWith("pbkdf2-sha256$1234$"));
        assertEquals(1_234, p.timing().iterations());
    }
}
