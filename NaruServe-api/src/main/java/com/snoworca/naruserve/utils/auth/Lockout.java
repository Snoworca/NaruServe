package com.snoworca.naruserve.utils.auth;

/**
 * The optional hard account lockout of password login, {@code .lockout(Lockout.after(5).forMinutes(15))}: after that
 * many failed password logins of one account its password login is refused for that long, answered exactly like a
 * wrong password ({@code 401 invalid_credentials}), so the refusal does not tell that the account exists or is
 * locked. It applies to password login only: a trusted-device login and API-key authentication are never locked, and
 * an admin releases a lock with {@code DELETE /auth/admin/users/:id/lockout}. Off unless configured, because a lockout
 * lets anyone lock a victim out on purpose; the default protection is the login throttle. Express counterpart: the
 * {@code express-brute} / {@code rate-limiter-flexible} "consecutive failures by username" recipe.
 */
// @req FR-FLOW-009
public final class Lockout {

    private final int failures;
    private final long durationMillis;

    private Lockout(int failures, long durationMillis) {
        this.failures = failures;
        this.durationMillis = durationMillis;
    }

    /** The first half of a policy: lock after {@code failures} failed password logins (at least 1). */
    // @req FR-FLOW-009
    public static After after(int failures) {
        if (failures < 1) {
            throw new IllegalArgumentException("a lockout needs at least 1 failure: " + failures);
        }
        return new After(failures);
    }

    /** The failed password logins that lock the account. */
    public int getFailures() {
        return failures;
    }

    /** How long a lock lasts, in milliseconds. */
    public long getDurationMillis() {
        return durationMillis;
    }

    @Override
    public String toString() {
        return "Lockout{after " + failures + " failures for " + durationMillis / 60_000L + " min}";
    }

    /** {@link Lockout#after(int)}, waiting for the duration. */
    public static final class After {
        private final int failures;

        private After(int failures) {
            this.failures = failures;
        }

        /** The policy locking for {@code minutes} (at least 1). */
        // @req FR-FLOW-009
        public Lockout forMinutes(long minutes) {
            if (minutes < 1) {
                throw new IllegalArgumentException("a lockout lasts at least 1 minute: " + minutes);
            }
            return new Lockout(failures, minutes * 60_000L);
        }
    }
}
