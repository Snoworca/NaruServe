package com.snoworca.naruserve.utils.auth.user;

import com.snoworca.naruserve.utils.auth.internal.UserFieldWrites;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Users, implemented by the app over its own table (or one of the shipped stores). Only the five required methods
 * are abstract; every method is synchronous and may throw any exception, which auth answers 503.
 *
 * <p>Contract every store keeps:
 * <ul>
 *   <li>Ids are Strings and a deleted id is never reused (an old group membership would grant its rights to a new
 *   user).</li>
 *   <li>ver only increases; a store never lowers or restores it.</li>
 *   <li>create and update throw DuplicateUserException on a unique-key violation.</li>
 *   <li>ver should be read-after-write consistent; this is recommended under MULTI_INSTANCE.</li>
 *   <li>{@link #findByUsername(String)} receives the already-normalized username (NFKC, lower case); which column it
 *   matches (name, e-mail, employee number) is the store's choice.</li>
 *   <li>An app-provided store must persist the service-account flag ({@link AuthUser#isServiceAccount()}): a row that
 *   loses it becomes an ordinary user.</li>
 * </ul>
 *
 * <p>The five field methods are defaults that auth runs under a per-user lock: they read the row with
 * {@link #findById(String)}, write one changed copy with {@link #update(AuthUser)} and read it again to confirm the
 * write (an unconfirmed security write ends in a 503 {@code security_write_conflict}; an unconfirmed rehash is
 * skipped). A store that implements them atomically (required under MULTI_INSTANCE) overrides all five and returns true
 * from {@link #supportsAtomicFieldWrites()}. A decorator extends {@link ForwardingUserStore}.
 *
 * <p>After the first public release this interface gains default methods only.
 */
// @req IR-IAM-001
public interface UserStore {

    /** The user with this normalized username, or null. */
    @Nullable AuthUser findByUsername(String username) throws Exception;

    /** The user with this id, or null. */
    @Nullable AuthUser findById(String id) throws Exception;

    /** Stores a new user whose id is null, chooses its id and returns the stored user. */
    AuthUser create(AuthUser user) throws Exception;

    /** Replaces the whole row of {@code user.getId()}; returns the stored user, or null when there is no such row. */
    @Nullable AuthUser update(AuthUser user) throws Exception;

    /** Deletes the user; false when there was none. */
    boolean delete(String id) throws Exception;

    /** Replaces the hash when it is still {@code expectedOldHash} (a rehash at login); the token version stays. */
    // @req IR-IAM-001
    default boolean updatePasswordHash(String id, @Nullable String expectedOldHash, String newHash) throws Exception {
        return UserFieldWrites.updatePasswordHash(this, id, expectedOldHash, newHash);
    }

    /**
     * Replaces the hash and raises the token version by one, only when the row still has {@code expectedOldHash} and
     * {@code expectedVer}; false otherwise. A null {@code expectedOldHash} matches a null stored hash (an SSO user
     * getting its first password). The hash and the version change in one write, so no session survives a password
     * change: ver only increases, and the one bump revokes every access, refresh and device token, WebSocket session
     * and API key of the user. API keys issued before a password change must be reissued.
     */
    // @req REL-IAM-002
    // @req SEC-IAM-003
    // @req IR-IAM-001
    default boolean changePassword(String id, @Nullable String expectedOldHash, long expectedVer, String newHash)
            throws Exception {
        return UserFieldWrites.changePassword(this, id, expectedOldHash, expectedVer, newHash);
    }

    /** Sets the enabled flag; false when there is no such user. */
    // @req IR-IAM-001
    default boolean setEnabled(String id, boolean enabled) throws Exception {
        return UserFieldWrites.setEnabled(this, id, enabled);
    }

    /**
     * Raises the token version by one, which invalidates every token issued before; false when there is no user. ver
     * only increases. Auth calls it for a logout of all devices and after {@code setEnabled(id, false)} on an admin
     * disable; role, group, profile and rehash writes never call it.
     */
    // @req IR-IAM-001
    default boolean bumpTokenVersion(String id) throws Exception {
        return UserFieldWrites.bumpTokenVersion(this, id);
    }

    /** Replaces the role set; false when there is no such user. */
    // @req IR-IAM-001
    default boolean setRoles(String id, Set<String> roles) throws Exception {
        return UserFieldWrites.setRoles(this, id, roles);
    }

    /**
     * One page of users for the admin list, {@code search} narrowing it (the store decides how); the default throws
     * {@link UnsupportedOperationException}. The shipped stores override it.
     */
    // @req IR-IAM-001
    default UserPage list(int offset, int limit, @Nullable String search) throws Exception {
        throw new UnsupportedOperationException(getClass().getName() + " does not implement UserStore.list");
    }

    /** True when the five field methods are overridden with atomic implementations; the default is false. */
    // @req IR-IAM-001
    default boolean supportsAtomicFieldWrites() {
        return false;
    }
}
