package com.snoworca.naruserve.utils.auth.rbac;

/**
 * The scope grammar and covering rule, usable without an Auth instance. A scope is {@code resource:action}, each
 * segment {@code [a-z0-9-]{1,32}}. Wildcards appear only on the grant side and only as {@code resource:*} or
 * {@code *}. {@code *} covers everything, {@code r:*} covers {@code r:*} and every {@code r:a}, and {@code r:a} covers
 * only itself; no action implies another. Resources starting with {@code auth-} are reserved: only
 * {@code auth-users}, {@code auth-groups} and {@code auth-apikeys} are accepted.
 */
// @req FR-IAM-005
public final class Scopes {

    private Scopes() {
    }

    /** True when {@code scope} may be granted: {@code resource:action}, {@code resource:*} or {@code *}. */
    // @req FR-IAM-005
    public static boolean isValidGrant(String scope) {
        if (scope.equals("*")) {
            return true;
        }
        int colon = scope.indexOf(':');
        if (colon < 0 || scope.indexOf(':', colon + 1) >= 0) {
            return false;
        }
        String resource = scope.substring(0, colon);
        String action = scope.substring(colon + 1);
        return isSegment(resource) && !isReserved(resource) && (action.equals("*") || isSegment(action));
    }

    /** True when {@code scope} may be required by a guard: a valid grant without a wildcard. */
    // @req FR-IAM-005
    public static boolean isValidRequired(String scope) {
        return scope.indexOf('*') < 0 && isValidGrant(scope);
    }

    /** {@code scope}, or {@link IllegalArgumentException} naming it when it is not a valid grant. */
    // @req FR-IAM-005
    public static String validateGrant(String scope) {
        if (!isValidGrant(scope)) {
            throw new IllegalArgumentException("invalid scope grant '" + scope + "': use resource:action, resource:* "
                    + "or *, each segment [a-z0-9-]{1,32}; auth-* resources other than auth-users, auth-groups and "
                    + "auth-apikeys are reserved");
        }
        return scope;
    }

    /** {@code scope}, or {@link IllegalArgumentException} naming it when it is not a valid required scope. */
    // @req FR-IAM-005
    public static String validateRequired(String scope) {
        if (!isValidRequired(scope)) {
            throw new IllegalArgumentException("invalid required scope '" + scope + "': use resource:action without "
                    + "a wildcard, each segment [a-z0-9-]{1,32}; only grants may contain *");
        }
        return scope;
    }

    /**
     * True when {@code grant} covers {@code other}, itself a grant or a required scope. Both must be valid grants,
     * otherwise {@link IllegalArgumentException}.
     */
    // @req FR-IAM-005
    public static boolean covers(String grant, String other) {
        validateGrant(grant);
        validateGrant(other);
        if (grant.equals("*")) {
            return true;
        }
        if (other.equals("*")) {
            return false;
        }
        if (grant.endsWith(":*")) {
            int colon = grant.length() - 2;
            return other.length() > colon && other.charAt(colon) == ':' && other.startsWith(grant.substring(0, colon));
        }
        return grant.equals(other);
    }

    private static boolean isSegment(String s) {
        int n = s.length();
        if (n < 1 || n > 32) {
            return false;
        }
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (!(c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '-')) {
                return false;
            }
        }
        return true;
    }

    private static boolean isReserved(String resource) {
        return resource.startsWith("auth-") && !resource.equals("auth-users") && !resource.equals("auth-groups")
                && !resource.equals("auth-apikeys");
    }
}
