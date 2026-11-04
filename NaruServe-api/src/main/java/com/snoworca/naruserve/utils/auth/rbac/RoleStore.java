package com.snoworca.naruserve.utils.auth.rbac;

import java.util.Set;

/**
 * The scopes of a role, for roles kept in a database. An unknown role has no scopes (an empty set). Rows that name a
 * reserved {@code auth-*} resource other than {@code auth-users}, {@code auth-groups} and {@code auth-apikeys}, or that
 * are not valid grants, are dropped with one WARN per role. An exception fails the guarded request closed (503).
 * {@link RolePolicy} is the in-code implementation.
 *
 * <p>After the first public release this interface gains default methods only.
 */
// @req FR-IAM-006
// @req CON-AUTH-006
@FunctionalInterface
public interface RoleStore {

    /** The granted scopes of {@code role}; empty for an unknown role. */
    Set<String> scopesOf(String role) throws Exception;
}
