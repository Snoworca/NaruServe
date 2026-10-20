package com.snoworca.naruserve;

import java.util.Iterator;
import java.util.NoSuchElementException;
import org.jspecify.annotations.Nullable;

/**
 * Immutable set of {@link Method}s. Every combinator returns a new set and leaves the receiver unchanged.
 * Iteration and {@link #toString()} follow {@link Method} declaration order.
 */
// @req IR-ROUTE-002
// @req IR-ROUTE-003
public final class MethodSet implements Iterable<Method> {

    // Lazy holder: Method.<clinit> builds Method.any() through this class, so this class's own <clinit> must not
    // touch Method or two threads first using one each would deadlock on the class-init locks.
    private static final class Values {
        private static final Method[] ALL = Method.values();
    }

    private final int mask;

    private MethodSet(int mask) {
        this.mask = mask;
    }

    /** Exactly the given methods; throws {@link NullPointerException} for a null array or element. */
    // @req IR-ROUTE-002
    public static MethodSet of(Method... methods) {
        if (methods == null) {
            throw new NullPointerException("methods");
        }
        int mask = 0;
        for (Method m : methods) {
            mask |= bit(m);
        }
        return new MethodSet(mask);
    }

    private static int bit(Method method) {
        if (method == null) {
            throw new NullPointerException("method");
        }
        return 1 << method.ordinal();
    }

    // @req IR-ROUTE-002
    public MethodSet get() {
        return with(Method.GET);
    }

    // @req IR-ROUTE-002
    public MethodSet head() {
        return with(Method.HEAD);
    }

    // @req IR-ROUTE-002
    public MethodSet post() {
        return with(Method.POST);
    }

    // @req IR-ROUTE-002
    public MethodSet put() {
        return with(Method.PUT);
    }

    // @req IR-ROUTE-002
    public MethodSet patch() {
        return with(Method.PATCH);
    }

    // @req IR-ROUTE-002
    public MethodSet delete() {
        return with(Method.DELETE);
    }

    // @req IR-ROUTE-002
    public MethodSet options() {
        return with(Method.OPTIONS);
    }

    /** This set plus {@code method}; the receiver itself when it is already a member. */
    // @req IR-ROUTE-002
    public MethodSet with(Method method) {
        int next = mask | bit(method);
        return next == mask ? this : new MethodSet(next);
    }

    /** This set without {@code method}; the receiver itself when it is not a member. */
    // @req IR-ROUTE-002
    public MethodSet except(Method method) {
        int next = mask & ~bit(method);
        return next == mask ? this : new MethodSet(next);
    }

    // @req IR-ROUTE-003
    public boolean contains(Method method) {
        return (mask & bit(method)) != 0;
    }

    // @req IR-ROUTE-003
    public int size() {
        return Integer.bitCount(mask);
    }

    /** Members in {@link Method#ordinal()} order; {@code remove()} throws {@link UnsupportedOperationException}. */
    // @req IR-ROUTE-003
    @Override
    public Iterator<Method> iterator() {
        return new Iterator<Method>() {
            private int rest = mask;

            @Override
            public boolean hasNext() {
                return rest != 0;
            }

            @Override
            public Method next() {
                if (rest == 0) {
                    throw new NoSuchElementException();
                }
                int ordinal = Integer.numberOfTrailingZeros(rest);
                rest &= rest - 1;
                return Values.ALL[ordinal];
            }

            // Overridden explicitly: Android API 21 has no default Iterator.remove().
            @Override
            public void remove() {
                throw new UnsupportedOperationException("MethodSet is immutable");
            }
        };
    }

    @Override
    public boolean equals(@Nullable Object o) {
        return o instanceof MethodSet && ((MethodSet) o).mask == mask;
    }

    @Override
    public int hashCode() {
        return mask;
    }

    /** Members in declaration order joined by {@code ", "}, the {@code Allow} header form. */
    // @req IR-ROUTE-003
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (Method m : this) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(m.name());
        }
        return sb.toString();
    }
}
