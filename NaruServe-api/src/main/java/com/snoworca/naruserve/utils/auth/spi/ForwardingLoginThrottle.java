package com.snoworca.naruserve.utils.auth.spi;

import java.util.Objects;

/**
 * The base class of {@link LoginThrottle} decorators (metrics, logging): every method, the defaults and
 * {@link #usesLimitArguments()} included, goes to the wrapped throttle, so overriding some methods never makes the
 * declaration lie. Override what you add to and call {@code super}.
 *
 * <pre>{@code
 * LoginThrottle counted = new ForwardingLoginThrottle(new InMemoryLoginThrottle()) {
 *     @Override
 *     public long recordFailure(String key, int max, long windowMillis) throws Exception {
 *         failures.increment();
 *         return super.recordFailure(key, max, windowMillis);
 *     }
 * };
 * }</pre>
 */
// @req IR-FLOW-003
public abstract class ForwardingLoginThrottle implements LoginThrottle {

    private final LoginThrottle delegate;

    /** Forwards to {@code delegate}. */
    protected ForwardingLoginThrottle(LoginThrottle delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    /** The wrapped throttle. */
    protected final LoginThrottle delegate() {
        return delegate;
    }

    @Override
    public long retryAfter(String key) throws Exception {
        return delegate.retryAfter(key);
    }

    @Override
    public long recordFailure(String key) throws Exception {
        return delegate.recordFailure(key);
    }

    @Override
    public void reset(String key) throws Exception {
        delegate.reset(key);
    }

    @Override
    public long retryAfter(String key, int max, long windowMillis) throws Exception {
        return delegate.retryAfter(key, max, windowMillis);
    }

    @Override
    public long recordFailure(String key, int max, long windowMillis) throws Exception {
        return delegate.recordFailure(key, max, windowMillis);
    }

    @Override
    public boolean usesLimitArguments() {
        return delegate.usesLimitArguments();
    }
}
