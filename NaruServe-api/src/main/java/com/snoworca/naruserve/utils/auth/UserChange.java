package com.snoworca.naruserve.utils.auth;

/** What a user update changed. */
// @req FR-AUTH-007
public enum UserChange {
    /** The username (a rename). */
    USERNAME,
    /** The role set. */
    ROLES,
    /** The enabled flag. */
    ENABLED,
    /** The password hash. */
    PASSWORD,
    /** The attributes map. */
    ATTRIBUTES
}
