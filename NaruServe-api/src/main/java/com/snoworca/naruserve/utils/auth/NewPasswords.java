package com.snoworca.naruserve.utils.auth;

import com.snoworca.naruserve.utils.auth.spi.BreachedPasswordChecker;
import java.util.Arrays;
import org.jspecify.annotations.Nullable;

/**
 * The check of every new password before any hash (FR-TOKEN-004): the {@link PasswordPolicy}, then the optional
 * {@link BreachedPasswordChecker}. Never used at login. A checker failure is logged and refuses the password with
 * {@link AuthUnavailableException}; its message never reaches a response.
 */
// @req FR-TOKEN-004
final class NewPasswords {

    private final PasswordPolicy policy;
    private final @Nullable BreachedPasswordChecker checker;
    private final Auth auth;

    NewPasswords(PasswordPolicy policy, @Nullable BreachedPasswordChecker checker, Auth auth) {
        this.policy = policy;
        this.checker = checker;
        this.auth = auth;
    }

    PasswordPolicy policy() {
        return policy;
    }

    /**
     * Why {@code password} may not be set, or null when it may; {@link AuthUnavailableException} when the breach
     * checker fails. The array is left as it is (the caller wipes it).
     */
    // @req FR-TOKEN-004
    @Nullable String problem(char[] password) {
        String v = policy.violation(password);
        if (v != null) {
            return v;
        }
        BreachedPasswordChecker c = checker;
        if (c == null) {
            return null;
        }
        char[] copy = Arrays.copyOf(password, password.length);
        try {
            return c.isBreached(copy) ? "the password appears in a list of breached passwords; choose another" : null;
        } catch (Exception e) {
            auth.log().error("auth: the breached-password check failed; the password was not set", e);
            throw new AuthUnavailableException("the breached-password check failed");
        } finally {
            Arrays.fill(copy, '\0');
        }
    }
}
