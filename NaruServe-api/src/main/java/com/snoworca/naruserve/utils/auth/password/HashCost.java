package com.snoworca.naruserve.utils.auth.password;

/**
 * How expensive a new PBKDF2 hash is. {@link #calibrate(long)} measures this device once (a short warm-up and one
 * timed run) and picks the iteration count that takes about {@code targetMillis}, rounded down to a multiple of
 * 10,000 and kept within 50,000..600,000; {@link #fixed(int)} uses a given count. Auth also uses the target time as
 * the login timing base T. With no {@code .hashCost(...)} auth calibrates to {@link #DEFAULT_TARGET_MILLIS}.
 * {@code Auth.Builder.build()} refuses a calibration target outside 250..500 ms. Express counterpart: the salt-rounds
 * argument of {@code bcrypt.hash(pw, rounds)}, chosen by measurement.
 *
 * <pre>{@code
 * Auth.builder(secret).hashCost(HashCost.calibrate(250)).build();   // a slow Android device
 * Auth.builder(secret).hashCost(HashCost.fixed(600_000)).build();   // the same cost on every node
 * }</pre>
 */
// @req PERF-TOKEN-001
public final class HashCost {

    /** The lowest calibration target auth accepts, in milliseconds. */
    public static final long MIN_TARGET_MILLIS = 250L;

    /** The highest calibration target auth accepts, in milliseconds. */
    public static final long MAX_TARGET_MILLIS = 500L;

    /** The calibration target when the app sets no cost, in milliseconds. */
    public static final long DEFAULT_TARGET_MILLIS = 500L;

    /** The fewest iterations calibration picks. */
    public static final int MIN_ITERATIONS = 50_000;

    /** The most iterations calibration picks. */
    public static final int MAX_ITERATIONS = 600_000;

    private final long targetMillis;
    private final int iterations;

    private HashCost(long targetMillis, int iterations) {
        this.targetMillis = targetMillis;
        this.iterations = iterations;
    }

    /** Measure this device and aim one hash at {@code targetMillis} (auth accepts 250..500). */
    // @req PERF-TOKEN-001
    public static HashCost calibrate(long targetMillis) {
        if (targetMillis < 1) {
            throw new IllegalArgumentException("the calibration target must be positive: " + targetMillis + " ms");
        }
        return new HashCost(targetMillis, 0);
    }

    /** Exactly {@code iterations} rounds per new hash, without calibration. */
    // @req PERF-TOKEN-001
    public static HashCost fixed(int iterations) {
        if (iterations < 1 || iterations > Pbkdf2PasswordHasher.MAX_STORED_ITERATIONS) {
            throw new IllegalArgumentException("fixed iterations must be 1.."
                    + Pbkdf2PasswordHasher.MAX_STORED_ITERATIONS + ": " + iterations);
        }
        return new HashCost(0L, iterations);
    }

    /** {@code calibrate(DEFAULT_TARGET_MILLIS)}, the cost when the app sets none. */
    public static HashCost defaults() {
        return calibrate(DEFAULT_TARGET_MILLIS);
    }

    /** True for {@link #fixed(int)}. */
    public boolean isFixed() {
        return iterations > 0;
    }

    /** The calibration target in milliseconds; 0 for a fixed cost. */
    public long getTargetMillis() {
        return targetMillis;
    }

    /** The fixed iteration count; 0 for a calibrated cost. */
    public int getIterations() {
        return iterations;
    }

    @Override
    public String toString() {
        return isFixed() ? "HashCost.fixed(" + iterations + ")" : "HashCost.calibrate(" + targetMillis + " ms)";
    }
}
