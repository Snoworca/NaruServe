package com.snoworca.naruserve.internal;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * The default error page of the proxy's {@code ProxyException} (FR-PROXY-015 AC-4, PRD-99): its causes may carry the
 * target address, as an {@link java.net.UnknownHostException} or a TLS host name failure does, so the page prints the
 * exception's own line, every cause and suppressed exception by class name only, and all stack frames. The type is
 * matched by name because the core does not depend on the optional proxy module, whose consumer rules keep the name.
 * {@link Throwable#printStackTrace} stays untouched, so loggers and {@code getCause()} keep the full cause; every other
 * error keeps the plain {@code printStackTrace} page (FR-APP-024 AC-3).
 */
// @req FR-PROXY-015 AC-4
final class ConcealedCauses {

    private static final String PROXY_EXCEPTION = "com.snoworca.naruserve.proxy.ProxyException";

    private ConcealedCauses() {
    }

    static boolean covers(Throwable error) {
        return PROXY_EXCEPTION.equals(error.getClass().getName());
    }

    /** The {@code printStackTrace} layout of {@code error} with every cause and suppressed exception by class only. */
    static String trace(Throwable error) {
        StringBuilder out = new StringBuilder();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        append(out, error, "", "", seen, true);
        return out.toString();
    }

    private static void append(StringBuilder out, Throwable t, String caption, String prefix, Set<Throwable> seen,
            boolean top) {
        if (!seen.add(t)) {
            out.append(prefix).append(caption).append("[CIRCULAR REFERENCE: ").append(t.getClass().getName())
                    .append("]\n");
            return;
        }
        // The top line is the error's own toString: a ProxyException's message is null, so it is its class name.
        out.append(prefix).append(caption).append(top ? String.valueOf(t) : t.getClass().getName()).append('\n');
        for (StackTraceElement frame : t.getStackTrace()) {
            out.append(prefix).append("\tat ").append(frame).append('\n');
        }
        for (Throwable suppressed : t.getSuppressed()) {
            append(out, suppressed, "Suppressed: ", prefix + "\t", seen, false);
        }
        Throwable cause = t.getCause();
        if (cause != null) {
            append(out, cause, "Caused by: ", prefix, seen, false);
        }
    }
}
