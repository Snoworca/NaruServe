package com.snoworca.naruserve.utils.auth.internal;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Per-user locks for the read-modify-write paths auth runs on a {@code SINGLE_INSTANCE} store: one lock stripe per
 * user id hash, so two writes of one user never interleave inside this process. Reentrant, so a default field method
 * called from inside a locked auth write does not deadlock.
 */
// @req IR-IAM-001
public final class UserLocks {

    private static final int STRIPES = 64;
    private static final ReentrantLock[] LOCKS = new ReentrantLock[STRIPES];

    static {
        for (int i = 0; i < STRIPES; i++) {
            LOCKS[i] = new ReentrantLock();
        }
    }

    private UserLocks() {
    }

    /** The lock of {@code userId}. */
    // @req IR-IAM-001
    public static ReentrantLock of(String userId) {
        return LOCKS[(userId.hashCode() & 0x7fffffff) % STRIPES];
    }
}
