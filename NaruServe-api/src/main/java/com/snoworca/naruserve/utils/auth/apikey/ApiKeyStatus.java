package com.snoworca.naruserve.utils.auth.apikey;

/**
 * The state of an API key in a listing, computed by auth from the owner's {@code ver} read from the store (never from
 * the principal cache); a key whose ownerVer is above that {@code ver} (an invariant violation) is {@link #STALE} too.
 * Auth endpoints write it as the lower-case strings {@code active}, {@code expired} and {@code stale}; an app passing
 * an {@link ApiKey} to its own JSON codec gets the codec's enum form. Values may be added in later versions, so a Kotlin
 * {@code when} over it needs an {@code else} branch. Under {@code MULTI_INSTANCE} a stale key whose ownerVer exceeds
 * the read ver still counts toward {@code maxKeysPerUser} (a late replica read may make a valid key look stale); a
 * stale key whose ownerVer is below the ver does not count.
 */
// @req DR-APIKEY-001
// @req IR-APIKEY-003
public enum ApiKeyStatus {
    /** Usable: not expired and issued for the owner's current {@code ver}. */
    ACTIVE,
    /** Past its {@code expiresAt}. */
    EXPIRED,
    /**
     * Issued for another owner {@code ver} than the one read (the owner's tokens were revoked since, or the read ver is
     * behind the key's); it does not authenticate at that ver.
     */
    STALE
}
