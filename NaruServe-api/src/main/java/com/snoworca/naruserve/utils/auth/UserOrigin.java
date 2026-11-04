package com.snoworca.naruserve.utils.auth;

/** Where a user change came from, so an event policy can tell sign-up from an admin or app write. */
// @req FR-AUTH-007
public enum UserOrigin {
    /** POST /auth/register. */
    REGISTER,
    /** An admin endpoint. */
    ADMIN,
    /** {@code auth.users()} called by app code. */
    APP,
    /** The user on their own account. */
    SELF,
    /** POST /auth/setup. */
    SETUP,
    /** The recovery path that restores the last admin. */
    RECOVERY,
    /** A builder {@code .user(...)} account created at start. */
    BOOTSTRAP
}
