package com.snoworca.naruserve.utils.auth.user;

import org.jspecify.annotations.Nullable;

/**
 * The app's safe path to users, {@code auth.users()}: auth normalizes names, hashes passwords, runs the
 * {@code before*}/{@code on*} events with origin {@code APP} and writes under the per-user lock. Every
 * {@link AuthUser} it returns or passes to a mutator is a copy whose password hash is null, so
 * {@code res.json(auth.users().findById(id))} never carries a hash. It throws only {@link DuplicateUserException}
 * (409), {@code AuthUnavailableException} (503, also for any store exception), {@link IllegalArgumentException} (400)
 * and {@code AuthRejection} (a refusing listener). Express has no counterpart; passport leaves this to the app.
 */
// @req IR-IAM-003
public interface AuthUsers {

    /** The user with this id, hash removed, or null. */
    @Nullable AuthUser findById(String id);

    /** The user with this username (normalized first), hash removed, or null. */
    @Nullable AuthUser findByUsername(String username);

    /** Creates a user with a hashed password and the given roles; returns it with its id, hash removed. */
    AuthUser create(String username, char[] password, String... roles);

    /**
     * Creates a user; {@code init} may set attributes and the service-account flag on the new row (a service account
     * has a null password, every other user a password). Returns it with its id, hash removed.
     */
    AuthUser create(String username, char @Nullable [] password, @Nullable UserMutator init, String... roles);

    /**
     * Changes the user's attributes: {@code mutator} receives a fresh copy and must not change the id, username,
     * password hash, token version, enabled flag, roles or service-account flag ({@link IllegalArgumentException}).
     * Returns the stored user, hash removed, or null when there is no such user.
     */
    @Nullable AuthUser update(String id, UserMutator mutator);

    /** Deletes the user and its group memberships; false when there was none. */
    boolean delete(String id);

    /** Renames the user (normalized first); returns the stored user, hash removed, or null when there is none. */
    @Nullable AuthUser rename(String id, String newUsername);

    /**
     * True when {@code password} is the user's current password, for re-confirming it before a sensitive change in
     * the app's own route; false for a missing user, a user without a password hash (a service account or an SSO-only
     * user) and a wrong password, each after one hash verification so the time does not tell them apart. It runs in a
     * password hash slot ({@code AuthUnavailableException}, 503, when no slot frees up within 5 seconds) and under the
     * per-user limit shared by every current-password confirmation: 5 failures per 15 minutes, then
     * {@code AuthRejection(429, "too_many_attempts")} with {@code getRetryAfterSeconds()}; a success does not reset
     * that count, and a success never rehashes the stored hash. The array is wiped after the call; wipe your own copy
     * too. A JSON body cannot be read into a {@code char[]} (Gson reads strings only), so read the password as a
     * {@code String} field and pass {@code password.toCharArray()}.
     */
    // @req SEC-IAM-002
    boolean checkPassword(String id, char[] password);
}
