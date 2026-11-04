package com.snoworca.naruserve.utils.auth.password;

/**
 * Where the salt went when the legacy code hashed it with the password ({@link PasswordHashers#saltedDigest}). Read
 * it off the old code: {@code sha256(salt + pw)} is {@link #SALT_THEN_PASSWORD}, {@code sha256(pw + salt)} is
 * {@link #PASSWORD_THEN_SALT}.
 */
// @req FR-TOKEN-003
public enum SaltOrder {
    /** The digest of the salt text followed by the password. */
    SALT_THEN_PASSWORD,
    /** The digest of the password followed by the salt text. */
    PASSWORD_THEN_SALT
}
