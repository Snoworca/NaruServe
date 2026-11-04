package com.snoworca.naruserve.utils.auth.spi;

import org.jspecify.annotations.Nullable;

/**
 * Refresh-token records, so a refresh can be rotated once, its reuse detected and its family revoked. When none is
 * configured auth picks the default that follows the user store: {@code InMemoryRefreshTokenStore} for
 * {@code InMemoryUserStore}, a {@code FileRefreshTokenStore} beside a {@code FileUserStore}'s file, and memory with
 * a doctor WARN for any other store under {@code SINGLE_INSTANCE} ({@code MULTI_INSTANCE} then fails in
 * {@code build()}: an app over its own database implements this interface there, since auth ships no database
 * store). Auth writes records only when tokens are issued, rotated or
 * revoked and when the 10-minute cleanup removes expired ones, never on a failed login. Every method is synchronous
 * and may throw; auth answers a failure 503. After the first public release this interface gains default methods only.
 */
// @req FR-TOKEN-002
public interface RefreshTokenStore {

    /** Stores a new record; its token id is new. */
    void save(RefreshRecord record) throws Exception;

    /** The record of {@code tokenId}, or null. */
    @Nullable RefreshRecord find(String tokenId) throws Exception;

    /**
     * Marks the record used at {@code nowMillis} when it is unused and not revoked, atomically: of concurrent calls for
     * one record exactly one returns true; every other call, and any call for a used, revoked or missing record,
     * returns false.
     */
    boolean markUsed(String tokenId, long nowMillis) throws Exception;

    /** Marks every record of the family revoked and returns how many were not revoked before. */
    int revokeFamily(String familyId) throws Exception;

    /**
     * Deletes the records whose expiry is at or before {@code nowMillis} and returns how many; the cleanup scheduler
     * calls it every 10 minutes. The default deletes nothing.
     */
    // @req FR-TOKEN-002
    default int purgeExpired(long nowMillis) throws Exception {
        return 0;
    }
}
