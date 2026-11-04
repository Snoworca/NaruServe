package com.snoworca.naruserve.utils.auth.user;

/**
 * Changes one user for {@code auth.users().update(id, mutator)} and the {@code init} of {@code create}: it receives
 * a copy without the password hash and returns the changed copy, usually {@code u.toBuilder()...build()}.
 * {@code java.util.function.Function} is API 24+, hence this SAM. Kotlin: {@code users.update(id) { it.toBuilder()
 * .attribute("nickname", "Al").build() }}.
 */
// @req IR-IAM-003
@FunctionalInterface
public interface UserMutator {

    /** The changed user. */
    AuthUser apply(AuthUser user);
}
