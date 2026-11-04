package com.snoworca.naruserve.utils.auth.internal;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The store each {@code ForwardingUserStore} wraps, for auth's PROD key rule, DEV WARN and doctor items (FR-IAM-015),
 * which must see a {@code FileUserStore} inside decorators but live in another package than the protected
 * {@code delegate()}. A decorator records its delegate once, in its constructor. The registry is keyed by object
 * identity ({@link System#identityHashCode} and {@code ==}) and holds decorators weakly: recording runs on a half-built
 * decorator, so no overridable {@code hashCode}/{@code equals} of it may run (a value-equality decorator, a Kotlin data
 * class, would throw or be filed under a hash that changes once its fields are set). Internal: not part of the API
 * contract.
 */
// @req FR-IAM-015
public final class UserStoreDelegates {

    /** A weak, identity-compared key: a cleared key equals only itself, so the queue can remove it. */
    private static final class Key extends WeakReference<Object> {
        private final int hash;

        Key(Object referent, @Nullable ReferenceQueue<Object> queue) {
            super(referent, queue);
            this.hash = System.identityHashCode(referent);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(@Nullable Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Key)) {
                return false;
            }
            Object mine = get();
            return mine != null && mine == ((Key) o).get();
        }
    }

    private static final ReferenceQueue<Object> CLEARED = new ReferenceQueue<>();
    private static final Map<Key, WeakReference<Object>> DELEGATES = new HashMap<>();

    private UserStoreDelegates() {
    }

    /** Records that {@code decorator} forwards to {@code delegate}; calls no method of either. */
    // @req FR-IAM-015
    public static void record(Object decorator, Object delegate) {
        synchronized (DELEGATES) {
            expunge();
            DELEGATES.put(new Key(decorator, CLEARED), new WeakReference<>(delegate));
        }
    }

    /** The store {@code decorator} forwards to; null when it recorded none. */
    // @req FR-IAM-015
    public static @Nullable Object delegateOf(Object decorator) {
        synchronized (DELEGATES) {
            expunge();
            WeakReference<Object> d = DELEGATES.get(new Key(decorator, null));
            return d == null ? null : d.get();
        }
    }

    private static void expunge() {
        for (Reference<?> r; (r = CLEARED.poll()) != null; ) {
            DELEGATES.remove(r);
        }
    }
}
