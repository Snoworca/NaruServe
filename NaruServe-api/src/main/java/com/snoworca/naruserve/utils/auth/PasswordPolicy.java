package com.snoworca.naruserve.utils.auth;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The length rule every new password must meet, set with {@code Auth.Builder.passwordPolicy(...)}; when not set, the
 * profile's ({@link #of(AuthProfile)}): at least 8 characters under {@code BASELINE} and {@code STANDARD}, 15 under
 * {@code STRICT}, and never more than 128. Characters are Unicode code points. No composition rule (digit, symbol) is
 * applied, following NIST SP 800-63B. Auth checks it for every new password (register, setup, the admin create and
 * reset, {@code auth.users().create} and the password change) before any hash, and never at login, so an existing
 * password that a stricter policy would refuse still logs in. Express counterpart: the length check of a
 * {@code express-validator} chain on the registration body.
 */
// @req FR-TOKEN-004
public final class PasswordPolicy {

    /** The longest password any policy allows: 128 characters, which bounds the PBKDF2 input. */
    public static final int MAX_LENGTH_LIMIT = 128;

    private final int minLength;
    private final int maxLength;

    private PasswordPolicy(int minLength, int maxLength) {
        this.minLength = minLength;
        this.maxLength = maxLength;
    }

    /** The default policy of {@code profile}: minimum 8 ({@code BASELINE}, {@code STANDARD}) or 15 ({@code STRICT}), maximum 128. */
    // @req FR-TOKEN-004
    public static PasswordPolicy of(AuthProfile profile) {
        Objects.requireNonNull(profile, "profile");
        return new PasswordPolicy(profile == AuthProfile.STRICT ? 15 : 8, MAX_LENGTH_LIMIT);
    }

    /** A policy from {@code minLength} (at least 1) to {@code maxLength} (at most 128, not below the minimum). */
    // @req FR-TOKEN-004
    public static PasswordPolicy length(int minLength, int maxLength) {
        if (minLength < 1) {
            throw new IllegalArgumentException("a password policy needs a minimum length of at least 1: " + minLength);
        }
        if (maxLength > MAX_LENGTH_LIMIT || maxLength < minLength) {
            throw new IllegalArgumentException("the maximum password length must lie in " + minLength + ".."
                    + MAX_LENGTH_LIMIT + ": " + maxLength);
        }
        return new PasswordPolicy(minLength, maxLength);
    }

    /** The fewest characters a new password may have. */
    public int getMinLength() {
        return minLength;
    }

    /** The most characters a new password may have. */
    public int getMaxLength() {
        return maxLength;
    }

    /** Why {@code password} breaks this policy, or null when it meets it; the text never contains the password. */
    // @req FR-TOKEN-004
    public @Nullable String violation(char[] password) {
        int n = Character.codePointCount(password, 0, password.length);
        if (n < minLength) {
            return "the password must have at least " + minLength + " characters";
        }
        if (n > maxLength) {
            return "the password must have at most " + maxLength + " characters";
        }
        return null;
    }

    @Override
    public String toString() {
        return "PasswordPolicy{" + minLength + ".." + maxLength + " characters}";
    }
}
