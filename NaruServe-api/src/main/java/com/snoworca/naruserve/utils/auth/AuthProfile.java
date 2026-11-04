package com.snoworca.naruserve.utils.auth;

/**
 * A security preset: it changes policy defaults only, never behaviour that a setter turns on or off, and a value set
 * explicitly on the builder wins over the profile. {@link #STANDARD} is used when {@code .profile(...)} is not
 * called. The defaults of each profile are listed in the auth guide.
 */
// @req FR-AUTH-003
public enum AuthProfile {
    /** A small app or a device server. */
    BASELINE,
    /** A typical web service; the default. */
    STANDARD,
    /** An enterprise deployment. */
    STRICT
}
