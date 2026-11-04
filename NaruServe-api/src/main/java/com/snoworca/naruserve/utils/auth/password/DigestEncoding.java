package com.snoworca.naruserve.utils.auth.password;

/**
 * How a legacy digest is written in the stored string of {@link PasswordHashers#digest} and
 * {@link PasswordHashers#saltedDigest}. Express counterpart: the {@code 'hex'} / {@code 'base64'} argument of
 * {@code crypto.createHash(alg).update(pw).digest(encoding)}.
 */
// @req FR-TOKEN-003
public enum DigestEncoding {
    /** Hexadecimal, upper or lower case ({@code 5f4dcc3b...}); what MySQL {@code MD5()} and PHP {@code md5()} give. */
    HEX,
    /** Standard base64 ({@code +} and {@code /}), with or without {@code =} padding. */
    BASE64
}
