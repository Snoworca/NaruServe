package com.snoworca.naruserve.utils.auth.internal;

import java.util.Set;

/**
 * The request-time scope decision: no regular expression, at most three hash lookups per required scope (the scope
 * itself, its {@code resource:*} and {@code *}). Grants and required scopes were validated at registration.
 */
// @req FR-IAM-005
public final class ScopeMatcher {

    private ScopeMatcher() {
    }

    /** True when {@code grants} covers the wildcard-free {@code required} scope. */
    // @req FR-IAM-005
    public static boolean allows(Set<String> grants, String required) {
        if (grants.contains(required)) {
            return true;
        }
        int colon = required.indexOf(':');
        return colon > 0 && grants.contains(required.substring(0, colon + 1) + "*") || grants.contains("*");
    }
}
