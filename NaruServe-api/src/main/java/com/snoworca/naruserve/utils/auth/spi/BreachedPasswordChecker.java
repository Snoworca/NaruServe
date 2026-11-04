package com.snoworca.naruserve.utils.auth.spi;

/**
 * Tells whether a new password is known to be breached (for example by a k-anonymity lookup or a local list), set with
 * {@code Auth.Builder.breachedPasswordChecker(...)}. Auth calls it for every new password (register, setup, the admin
 * create and reset, {@code auth.users().create} and the password change) after the {@code PasswordPolicy} and before
 * any hash; never at login. True refuses the password; an exception refuses it too (the store is not written and the
 * exception text never reaches the response). The array is a copy that auth wipes afterwards. Express counterpart: a
 * {@code hibp} check inside a registration route.
 */
// @req FR-TOKEN-004
@FunctionalInterface
public interface BreachedPasswordChecker {

    /** True when {@code password} must not be used because it is known to be breached. */
    boolean isBreached(char[] password) throws Exception;
}
