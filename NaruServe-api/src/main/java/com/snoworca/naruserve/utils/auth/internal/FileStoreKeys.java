package com.snoworca.naruserve.utils.auth.internal;

import com.snoworca.naruserve.utils.auth.AuthFileKey;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import org.jspecify.annotations.Nullable;

/**
 * The AuthFileKey a {@code FileUserStore} was made with, for auth's PROD check, DEV WARN, file defaults and doctor
 * count (FR-IAM-015), which live in another package than {@code FileUserStore}. The store records its key once, at
 * the end of its constructor; the key is not public API of the store. Internal: not part of the API contract.
 */
// @req FR-IAM-015
public final class FileStoreKeys {

    /** A store with no recorded key maps to this, so "recorded without a key" and "not recorded" differ. */
    private static final Object NONE = new Object();
    private static final Map<Object, Object> KEYS = Collections.synchronizedMap(new WeakHashMap<Object, Object>());

    private FileStoreKeys() {
    }

    /** Records the key {@code store} was made with (null: none); a store records once. */
    // @req FR-IAM-015
    public static void record(Object store, @Nullable AuthFileKey key) {
        synchronized (KEYS) {
            if (KEYS.containsKey(store)) {
                throw new IllegalStateException("the key of this store is already recorded");
            }
            KEYS.put(store, key == null ? NONE : key);
        }
    }

    /** The key {@code store} recorded; null when it has none or recorded nothing. */
    // @req FR-IAM-015
    public static @Nullable AuthFileKey keyOf(Object store) {
        Object k = KEYS.get(store);
        return k instanceof AuthFileKey ? (AuthFileKey) k : null;
    }
}
