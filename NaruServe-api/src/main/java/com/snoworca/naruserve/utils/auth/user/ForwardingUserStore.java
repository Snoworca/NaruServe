package com.snoworca.naruserve.utils.auth.user;

import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The base class of every {@link UserStore} decorator (a cache, a tenant filter, an audit wrapper): decorators must
 * extend this class, override only what they change, and leave the rest forwarded. Every method, defaults
 * included, goes to the wrapped store, with one rule for the five field methods: they are forwarded only when
 * {@code delegate().supportsAtomicFieldWrites()} is true; otherwise the {@code UserStore} default runs on this
 * decorator, so its read-modify-write goes through this decorator's own {@code findById} and {@code update} (and a
 * cache decorator sees, and invalidates, the write). When {@code UserStore} gains a default method, this class
 * overrides it in the same release.
 */
// @req IR-IAM-002
public abstract class ForwardingUserStore implements UserStore {

    private final UserStore delegate;

    /** A decorator over {@code delegate}. */
    // @req IR-IAM-002
    protected ForwardingUserStore(UserStore delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        // FR-IAM-015: auth holds a FileUserStore inside decorators to the same key rule as a bare one
        com.snoworca.naruserve.utils.auth.internal.UserStoreDelegates.record(this, delegate);
    }

    /** The wrapped store. */
    protected UserStore delegate() {
        return delegate;
    }

    @Override
    public @Nullable AuthUser findByUsername(String username) throws Exception {
        return delegate.findByUsername(username);
    }

    @Override
    public @Nullable AuthUser findById(String id) throws Exception {
        return delegate.findById(id);
    }

    @Override
    public AuthUser create(AuthUser user) throws Exception {
        return delegate.create(user);
    }

    @Override
    public @Nullable AuthUser update(AuthUser user) throws Exception {
        return delegate.update(user);
    }

    @Override
    public boolean delete(String id) throws Exception {
        return delegate.delete(id);
    }

    // @req IR-IAM-002
    @Override
    public boolean updatePasswordHash(String id, @Nullable String expectedOldHash, String newHash) throws Exception {
        return delegate.supportsAtomicFieldWrites() ? delegate.updatePasswordHash(id, expectedOldHash, newHash)
                : UserStore.super.updatePasswordHash(id, expectedOldHash, newHash);
    }

    // @req IR-IAM-002
    @Override
    public boolean changePassword(String id, @Nullable String expectedOldHash, long expectedVer, String newHash)
            throws Exception {
        return delegate.supportsAtomicFieldWrites() ? delegate.changePassword(id, expectedOldHash, expectedVer, newHash)
                : UserStore.super.changePassword(id, expectedOldHash, expectedVer, newHash);
    }

    // @req IR-IAM-002
    @Override
    public boolean setEnabled(String id, boolean enabled) throws Exception {
        return delegate.supportsAtomicFieldWrites() ? delegate.setEnabled(id, enabled)
                : UserStore.super.setEnabled(id, enabled);
    }

    // @req IR-IAM-002
    @Override
    public boolean bumpTokenVersion(String id) throws Exception {
        return delegate.supportsAtomicFieldWrites() ? delegate.bumpTokenVersion(id)
                : UserStore.super.bumpTokenVersion(id);
    }

    // @req IR-IAM-002
    @Override
    public boolean setRoles(String id, Set<String> roles) throws Exception {
        return delegate.supportsAtomicFieldWrites() ? delegate.setRoles(id, roles) : UserStore.super.setRoles(id, roles);
    }

    @Override
    public UserPage list(int offset, int limit, @Nullable String search) throws Exception {
        return delegate.list(offset, limit, search);
    }

    @Override
    public boolean supportsAtomicFieldWrites() {
        return delegate.supportsAtomicFieldWrites();
    }
}
