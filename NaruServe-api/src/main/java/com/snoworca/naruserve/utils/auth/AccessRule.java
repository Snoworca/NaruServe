package com.snoworca.naruserve.utils.auth;

import com.snoworca.naruserve.Request;

/**
 * An app-defined access decision for {@code auth.check(rule)}: true lets the request through, false answers 403.
 * Kotlin: {@code auth.check { p, req -> p.hasScope("posts:write") || p.id == req.param("id") }}.
 */
// @req IR-AUTH-004
@FunctionalInterface
public interface AccessRule {

    /** True when {@code principal} may make {@code request}. */
    boolean allows(AuthPrincipal principal, Request request) throws Exception;
}
