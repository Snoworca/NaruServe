package com.snoworca.naruserve.utils.auth.internal;

import com.snoworca.naruserve.utils.auth.password.HashCost;

/**
 * A {@link HashCost} resolved on this device: the PBKDF2 iteration count of new hashes and the login timing base T.
 * Under {@code calibrate(t)} T is t and the count comes from one timed sample; under {@code fixed(n)} the count is n
 * and T is one timing of a hash at n, taken once.
 */
// @req PERF-TOKEN-001
public final class HashTiming {

    /** Times one PBKDF2 derivation; the device clock in production, a stub in tests. */
    @FunctionalInterface
    public interface Measurer {
        /** The nanoseconds a derivation of {@code iterations} rounds takes. */
        long nanosFor(int iterations);
    }

    /** The rounds of the calibration sample. */
    public static final int SAMPLE_ITERATIONS = 20_000;

    private final int iterations;
    private final long timingNanos;
    private final long measuredNanosPerHash;

    private HashTiming(int iterations, long timingNanos, long measuredNanosPerHash) {
        this.iterations = iterations;
        this.timingNanos = timingNanos;
        this.measuredNanosPerHash = measuredNanosPerHash;
    }

    /** Resolves {@code cost} with {@code measurer}: one sample for a calibration, one timing for a fixed cost. */
    // @req PERF-TOKEN-001
    public static HashTiming resolve(HashCost cost, Measurer measurer) {
        if (cost.isFixed()) {
            long t = Math.max(1L, measurer.nanosFor(cost.getIterations()));
            return new HashTiming(cost.getIterations(), t, t);
        }
        long sample = Math.max(1L, measurer.nanosFor(SAMPLE_ITERATIONS));
        int n = iterationsFor(cost.getTargetMillis(), SAMPLE_ITERATIONS, sample);
        long perHash = (long) ((double) sample * n / SAMPLE_ITERATIONS);
        return new HashTiming(n, cost.getTargetMillis() * 1_000_000L, perHash);
    }

    /** {@link #resolve(HashCost, Measurer)} on this device. */
    public static HashTiming resolve(HashCost cost) {
        return resolve(cost, Pbkdf2::nanosFor);
    }

    /**
     * The iterations a hash of {@code targetMillis} takes when {@code sampleIterations} took {@code sampleNanos}:
     * rounded down to a multiple of 10,000 and kept within {@link HashCost#MIN_ITERATIONS} and
     * {@link HashCost#MAX_ITERATIONS}.
     */
    // @req PERF-TOKEN-001
    public static int iterationsFor(long targetMillis, int sampleIterations, long sampleNanos) {
        double raw = (double) targetMillis * 1_000_000d * sampleIterations / Math.max(1L, sampleNanos);
        long rounded = ((long) raw / 10_000L) * 10_000L;
        return (int) Math.max(HashCost.MIN_ITERATIONS, Math.min(HashCost.MAX_ITERATIONS, rounded));
    }

    /** The rounds of new hashes. */
    public int iterations() {
        return iterations;
    }

    /** The login timing base T in nanoseconds. */
    public long timingNanos() {
        return timingNanos;
    }

    /** The time one hash at {@link #iterations()} takes on this device, as measured (estimated for a calibration). */
    public long measuredNanosPerHash() {
        return measuredNanosPerHash;
    }
}
