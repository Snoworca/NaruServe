package com.snoworca.naruserve.utils.auth.group;

import com.snoworca.naruserve.utils.auth.internal.GroupExpander;
import java.util.Collections;
import java.util.Set;

/**
 * Read access to groups, implemented by the app over its own tables. Groups form a DAG: a group may have several
 * parents. Group ids are lower case ({@code [a-z0-9._-]{1,64}}) and memberships are keyed by user id, so a rename
 * keeps them. Only the two direct relations are required; the set-valued {@link #parentsOf(Set)} lets a SQL store
 * answer one level with one {@code IN} query.
 *
 * <p>After the first public release this interface gains default methods only.
 */
// @req DR-IAM-002
// @req FR-IAM-007
// @req CON-AUTH-006
public interface GroupStore {

    /** The groups the user is a direct member of. */
    Set<String> groupsOfUser(String userId) throws Exception;

    /** The union of the direct parents of {@code groupIds}. */
    Set<String> parentsOf(Set<String> groupIds) throws Exception;

    /**
     * Every group of the user, direct or inherited. The default walks up level by level with one
     * {@link #parentsOf(Set)} call per level and the default {@link GroupLimits}; it fails rather than truncate when
     * the depth exceeds 8 or the result exceeds 256 groups. Override it with one query (a recursive CTE) if the
     * database can; auth still checks the size of the result against its configured limits. A decorator that
     * overrides this method only to delegate makes auth call it, so the delegate's default runs with the default
     * limits rather than the configured ones; a decorator should leave this method alone.
     */
    // @req FR-IAM-007
    default Set<String> effectiveGroupsOfUser(String userId) throws Exception {
        return GroupExpander.expand(this, userId, GroupLimits.defaults());
    }

    /** The roles the given groups confer; the default confers none. */
    // @req DR-IAM-002
    default Set<String> rolesOfGroups(Set<String> groupIds) throws Exception {
        return Collections.emptySet();
    }
}
