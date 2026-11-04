package com.snoworca.naruserve.utils.auth.password;

/**
 * Turns passwords into stored hash strings and checks them. Each hasher owns a prefix of the stored string
 * ({@code pbkdf2-sha256$}, {@code $2b$}) and {@link #accepts(String)} says whether a stored string is one of its own,
 * so stored strings carry their algorithm and cost. Auth uses one hasher ({@code .passwordHasher(...)}): a stored
 * string it does not accept is checked against a dummy hash and fails. To read several formats (moving bcrypt
 * accounts to PBKDF2) give it {@link PasswordHashers#chain(PasswordHasher, PasswordHasher...)}. Auth runs every {@code hash} and
 * {@code verify} inside its hash concurrency limit. The default is {@link Pbkdf2PasswordHasher}. Express counterpart:
 * the {@code bcrypt.hash} / {@code bcrypt.compare} pair, as an object.
 *
 * <p>Passwords are {@code char[]} so callers can wipe them; a hasher never keeps them. After the first public release
 * this interface gains default methods only.
 */
// @req SEC-TOKEN-007
public interface PasswordHasher {

    /** True when {@code stored} is a hash string of this hasher (decided by its prefix only). */
    boolean accepts(String stored);

    /** A new hash string of {@code password} with a fresh random salt; an empty password is refused. */
    String hash(char[] password);

    /**
     * True when {@code password} matches {@code stored}. A string this hasher does not accept, a malformed one or one
     * asking for an unbounded cost is false, never an exception.
     */
    boolean verify(char[] password, String stored);

    /**
     * True when {@code stored}, after a successful {@link #verify}, should be replaced by a new {@link #hash}: its cost
     * is outside the current target, or it belongs to a legacy algorithm. False by default.
     */
    default boolean needsRehash(String stored) {
        return false;
    }
}
