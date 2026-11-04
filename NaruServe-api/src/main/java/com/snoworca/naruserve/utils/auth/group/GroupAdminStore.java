package com.snoworca.naruserve.utils.auth.group;

import java.util.List;
import java.util.Set;

/**
 * Group writes for the admin group endpoints, on top of the {@link GroupStore} reads. A store fed from LDAP or
 * another directory implements only {@link GroupStore}. Member and edge writes are idempotent. Auth checks cycles and
 * the depth limit before {@link #addParent(String, String)}; a database store may still race between that check and
 * the insert, which is harmless for reads because expansion skips groups it has seen.
 *
 * <p>After the first public release this interface gains default methods only.
 */
// @req IR-IAM-004
public interface GroupAdminStore extends GroupStore {

    /** Creates an empty group; false when it already exists. */
    boolean createGroup(String groupId) throws Exception;

    /** Deletes the group together with its memberships and every edge it is child or parent of; false when absent. */
    boolean deleteGroup(String groupId) throws Exception;

    /** Every group id, sorted. */
    List<String> listGroups() throws Exception;

    /** True when the group exists. */
    boolean groupExists(String groupId) throws Exception;

    /** Makes the user a direct member; no error when it already is. */
    void addMember(String groupId, String userId) throws Exception;

    /** Removes a direct membership; no error when there is none. */
    void removeMember(String groupId, String userId) throws Exception;

    /** The direct members of the group. */
    Set<String> membersOf(String groupId) throws Exception;

    /** Adds the edge child ⊂ parent; auth has checked cycles and depth. No error when it exists. */
    void addParent(String childId, String parentId) throws Exception;

    /** Removes the edge child ⊂ parent; no error when there is none. */
    void removeParent(String childId, String parentId) throws Exception;

    /** The direct children of the group. */
    Set<String> childrenOf(String groupId) throws Exception;

    /**
     * Removes every direct membership of the user, run when the user is deleted. The default removes them one group
     * at a time; a database store overrides it with one {@code DELETE}.
     */
    // @req IR-IAM-004
    default void removeUser(String userId) throws Exception {
        for (String g : groupsOfUser(userId)) {
            removeMember(g, userId);
        }
    }
}
