package com.snoworca.naruserve.utils.auth.spi;

import com.snoworca.naruserve.utils.auth.AuthEventContext;
import com.snoworca.naruserve.utils.auth.user.AuthUser;
import org.jspecify.annotations.Nullable;

/**
 * The password credential seam of {@code POST /auth/login}: checks one username and password and returns the stored
 * user they belong to, or null. It replaces the built-in password authenticator (the user store plus the configured
 * {@code PasswordHasher}) through {@code Auth.Builder.authenticator(...)}, for example to check passwords against a
 * directory. Bearer tokens and API keys are verified by auth itself and cannot be replaced.
 *
 * <p>Return null for every failure (unknown user, wrong password, disabled user) without telling them apart. A returned
 * user must exist in the auth user store (its id is set), because tokens are issued for that id and every request
 * reloads it; a disabled returned user still fails. Auth calls this inside a password hash slot and holds every failure
 * answer to the login failure floor, so a custom check cannot shorten the timing of a failure. An exception becomes a
 * 503 JSON answer, never a login. The password array is wiped after the call; copy what you keep. Express counterpart:
 * the verify callback of a passport {@code LocalStrategy}.
 */
// @req IR-TOKEN-001
@FunctionalInterface
public interface Authenticator {

    /** The stored user {@code username} and {@code password} belong to, or null. */
    @Nullable AuthUser authenticate(String username, char[] password, AuthEventContext ctx) throws Exception;
}
