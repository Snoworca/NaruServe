package com.snoworca.naruserve.utils.auth.apikey;

import java.util.Objects;

/**
 * The result of issuing a key: its metadata and, this one time, the plain key {@code nk_<prefix8>_<secret32>} to hand
 * to the client; auth stores only the secret's hash and cannot show it again. {@link #toString()} prints
 * {@code nk_<prefix8>_***}.
 */
// @req DR-APIKEY-001
public final class IssuedApiKey {

    private final ApiKey key;
    private final String plainKey;

    /** {@code key} with its plain form, which must start with {@code nk_<prefix>_}. */
    // @req DR-APIKEY-001
    public IssuedApiKey(ApiKey key, String plainKey) {
        this.key = Objects.requireNonNull(key, "key");
        Objects.requireNonNull(plainKey, "plainKey");
        if (!plainKey.startsWith("nk_" + key.getPrefix() + "_")) {
            throw new IllegalArgumentException("the plain key does not belong to nk_" + key.getPrefix());
        }
        this.plainKey = plainKey;
    }

    /** The key's metadata. */
    public ApiKey getKey() {
        return key;
    }

    /** The plain key; give it to the client now, it is never available again. */
    public String getPlainKey() {
        return plainKey;
    }

    @Override
    public String toString() {
        return "IssuedApiKey{key=nk_" + key.getPrefix() + "_***, id=" + key.getId() + ", name=" + key.getName() + "}";
    }
}
