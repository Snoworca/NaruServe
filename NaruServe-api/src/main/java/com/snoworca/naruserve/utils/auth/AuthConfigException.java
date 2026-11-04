package com.snoworca.naruserve.utils.auth;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Everything {@code Auth.Builder.build()} found wrong, reported at once: one entry per violation, and the message
 * lists every entry. Unchecked. A message never contains a secret value.
 */
// @req FR-AUTH-001
public class AuthConfigException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final List<String> violations;

    /** An exception listing {@code violations}, at least one. */
    // @req FR-AUTH-001
    public AuthConfigException(List<String> violations) {
        super(message(violations));
        this.violations = Collections.unmodifiableList(new ArrayList<>(violations));
    }

    private static String message(List<String> violations) {
        if (violations.isEmpty()) {
            throw new IllegalArgumentException("AuthConfigException needs at least one violation");
        }
        if (violations.size() == 1) {
            return "Auth configuration is invalid: " + violations.get(0);
        }
        StringBuilder b = new StringBuilder("Auth configuration has ").append(violations.size()).append(" problems:");
        for (int i = 0; i < violations.size(); i++) {
            b.append("\n  ").append(i + 1).append(". ").append(violations.get(i));
        }
        return b.toString();
    }

    /** The violations, in the order the rules ran; unmodifiable. */
    public List<String> getViolations() {
        return violations;
    }
}
