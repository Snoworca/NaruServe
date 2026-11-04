package com.snoworca.naruserve.utils.auth.internal;

import com.snoworca.naruserve.utils.auth.AuthErrorCode;
import com.snoworca.naruserve.utils.auth.AuthUnavailableException;
import com.snoworca.naruserve.utils.auth.user.AuthUser;
import com.snoworca.naruserve.utils.auth.user.UserStore;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;

/**
 * The default bodies of the five {@link UserStore} field methods: under the user's lock, read the row with
 * {@code findById}, write one changed copy with {@code update}, and read it again. {@code setEnabled},
 * {@code bumpTokenVersion} and {@code setRoles} retry an unconfirmed write three times, {@code changePassword} tries
 * once; then each throws an {@link AuthUnavailableException} with {@link AuthErrorCode#SECURITY_WRITE_CONFLICT}, so
 * auth never answers success for a security write it could not see. The rehash {@code updatePasswordHash} is not a
 * security write: an unconfirmed one is skipped silently (false).
 */
// @req IR-IAM-001
public final class UserFieldWrites {

    private UserFieldWrites() {
    }

    /** One field change of a fresh row; null when the change does not apply. */
    private interface Change {
        @Nullable AuthUser apply(AuthUser current);
    }

    /** A check that the re-read row carries the change. */
    private interface Confirm {
        boolean holds(AuthUser reread);
    }

    /** What an unconfirmed write does after its tries. */
    private enum Unconfirmed {
        /** Throw security_write_conflict. */
        THROW,
        /** Report false: the rehash is skipped silently. */
        SKIP
    }

    private static boolean write(UserStore store, String id, Change change, Confirm confirm, int tries,
            Unconfirmed onFailure) throws Exception {
        ReentrantLock lock = UserLocks.of(id);
        lock.lock();
        try {
            for (int attempt = 0; attempt < tries; attempt++) {
                AuthUser current = store.findById(id);
                if (current == null) {
                    return false;
                }
                AuthUser next = change.apply(current);
                if (next == null) {
                    return false;
                }
                if (store.update(next) == null) {
                    return false;
                }
                AuthUser reread = store.findById(id);
                if (reread != null && confirm.holds(reread)) {
                    return true;
                }
            }
            if (onFailure == Unconfirmed.SKIP) {
                return false;
            }
            throw new AuthUnavailableException(AuthErrorCode.SECURITY_WRITE_CONFLICT.getCode(),
                    "the user store did not apply a security write of user " + id, null);
        } finally {
            lock.unlock();
        }
    }

    /** The first try plus three retries of setEnabled, bumpTokenVersion and setRoles. */
    private static final int RETRIED = 4;

    /** {@link UserStore#updatePasswordHash}. */
    // @req IR-IAM-001
    public static boolean updatePasswordHash(UserStore store, String id, @Nullable String expectedOldHash,
            final String newHash) throws Exception {
        return write(store, id, c -> Objects.equals(c.getPasswordHash(), expectedOldHash)
                ? c.toBuilder().passwordHash(newHash).build() : null,
                r -> newHash.equals(r.getPasswordHash()), 1, Unconfirmed.SKIP);
    }

    /** {@link UserStore#changePassword}. */
    // @req IR-IAM-001
    public static boolean changePassword(UserStore store, String id, @Nullable String expectedOldHash,
            long expectedVer, final String newHash) throws Exception {
        final long[] ver = new long[1];
        return write(store, id, c -> {
            if (!Objects.equals(c.getPasswordHash(), expectedOldHash) || c.getTokenVersion() != expectedVer) {
                return null;
            }
            ver[0] = c.getTokenVersion() + 1;
            return c.toBuilder().passwordHash(newHash).tokenVersion(ver[0]).build();
        }, r -> newHash.equals(r.getPasswordHash()) && r.getTokenVersion() >= ver[0], 1, Unconfirmed.THROW);
    }

    /** {@link UserStore#setEnabled}. */
    // @req IR-IAM-001
    public static boolean setEnabled(UserStore store, String id, final boolean enabled) throws Exception {
        return write(store, id, c -> c.toBuilder().enabled(enabled).build(), r -> r.isEnabled() == enabled, RETRIED,
                Unconfirmed.THROW);
    }

    /** {@link UserStore#bumpTokenVersion}. */
    // @req IR-IAM-001
    public static boolean bumpTokenVersion(UserStore store, String id) throws Exception {
        final long[] ver = new long[1];
        return write(store, id, c -> {
            ver[0] = c.getTokenVersion() + 1;
            return c.toBuilder().tokenVersion(ver[0]).build();
        }, r -> r.getTokenVersion() >= ver[0], RETRIED, Unconfirmed.THROW);
    }

    /** {@link UserStore#setRoles}. */
    // @req IR-IAM-001
    public static boolean setRoles(UserStore store, String id, Set<String> roles) throws Exception {
        final Set<String> copy = new LinkedHashSet<>(roles);
        return write(store, id, c -> c.toBuilder().roles(copy).build(), r -> r.getRoles().equals(copy), RETRIED,
                Unconfirmed.THROW);
    }
}
