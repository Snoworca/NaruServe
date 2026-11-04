package com.snoworca.naruserve.utils.auth.spi;

import com.snoworca.naruserve.utils.auth.jwt.JwtKeys;
import org.jspecify.annotations.Nullable;

/**
 * Where the signing key comes from: {@code KeySources.of/fromEnv/fromFile/fromBytes/rotating} or an app
 * implementation (a vault, a hardware store). Auth reads it once, in {@code build()}, and checks the strength of every
 * key there. Kotlin: {@code KeySource { vault.read("jwt") }}.
 *
 * <p>The key set ({@link #keys()}) is one signing key and zero or more verification keys addressed by kid; by default
 * it is {@link #signingKey()} alone under {@link #signingKid()}. Override {@link #keys()} to rotate keys. After the
 * first public release this interface gains default methods only.
 */
// @req SEC-AUTH-001
// @req SEC-TOKEN-004
@FunctionalInterface
public interface KeySource {

    /** The HMAC signing key, at least 32 bytes in PROD; auth keeps a copy. */
    byte[] signingKey() throws Exception;

    /**
     * The kid of {@link #signingKey()} in token headers; null (the default) derives it from the key, so the same key
     * always has the same kid.
     */
    default @Nullable String signingKid() {
        return null;
    }

    /** The key set auth signs and verifies with; by default the signing key alone. */
    // @req SEC-TOKEN-004
    default JwtKeys keys() throws Exception {
        byte[] key = signingKey();
        String kid = signingKid();
        return kid == null ? JwtKeys.of(key) : JwtKeys.of(kid, key);
    }
}
