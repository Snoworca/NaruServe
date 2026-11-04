package com.snoworca.naruserve.utils.auth.group;

import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Groups in memory, lost on restart. Thread-safe; {@link #addParent(String, String)} runs auth's cycle and depth
 * check and the insert under one lock, so two concurrent edges can never close a cycle. Not shared between nodes,
 * so {@code Deployment.MULTI_INSTANCE} refuses it.
 */
// @req IR-IAM-004
public final class InMemoryGroupStore implements GroupAdminStore {

    private final GroupGraph graph = new GroupGraph();
    private final GroupLimits limits;

    /** An empty store with the default limits. */
    // @req IR-IAM-004
    public InMemoryGroupStore() {
        this(GroupLimits.defaults());
    }

    /** An empty store whose edge check uses {@code limits}. */
    // @req IR-IAM-004
    public InMemoryGroupStore(GroupLimits limits) {
        this.limits = limits;
    }

    @Override
    public synchronized Set<String> groupsOfUser(String userId) {
        return graph.groupsOfUser(userId);
    }

    @Override
    public synchronized Set<String> parentsOf(Set<String> groupIds) {
        return graph.parentsOf(groupIds);
    }

    @Override
    public synchronized boolean createGroup(String groupId) {
        return graph.create(groupId);
    }

    @Override
    public synchronized boolean deleteGroup(String groupId) {
        return graph.delete(groupId);
    }

    @Override
    public synchronized List<String> listGroups() {
        return graph.list();
    }

    @Override
    public synchronized boolean groupExists(String groupId) {
        return graph.exists(groupId);
    }

    @Override
    public synchronized void addMember(String groupId, String userId) {
        graph.addMember(groupId, userId);
    }

    @Override
    public synchronized void removeMember(String groupId, String userId) {
        graph.removeMember(groupId, userId);
    }

    @Override
    public synchronized Set<String> membersOf(String groupId) {
        return graph.membersOf(groupId);
    }

    /** Checks cycle and depth and inserts the edge under this store's lock; a refused edge is a 409 AuthRejection. */
    // @req IR-IAM-004
    @Override
    public synchronized void addParent(String childId, String parentId) {
        graph.addParent(childId, parentId, limits);
    }

    /** Runs {@code hook} between the edge check and the insert of every later addParent (null: none); for the tests. */
    void afterEdgeCheck(@Nullable Runnable hook) {
        graph.checked = hook;
    }

    @Override
    public synchronized void removeParent(String childId, String parentId) {
        graph.removeParent(childId, parentId);
    }

    @Override
    public synchronized Set<String> childrenOf(String groupId) {
        return graph.childrenOf(groupId);
    }

    @Override
    public synchronized void removeUser(String userId) {
        graph.removeUser(userId);
    }

    /**
     * Gives {@code role} to the group, so its members, direct or through child groups, get the role's scopes; a
     * second grant is a no-op. An unknown group or an empty role, or one holding a tab, CR or LF, throws
     * {@link IllegalArgumentException}. A grant after start reaches a signed-in user when its cached principal expires.
     */
    // @req FR-IAM-016
    public synchronized void grantRole(String groupId, String role) {
        graph.grantRole(groupId, role);
    }

    /** Takes {@code role} back from the group; a no-op when it does not hold it. */
    // @req FR-IAM-016
    public synchronized void revokeRole(String groupId, String role) {
        graph.revokeRole(groupId, role);
    }

    /** The roles the group itself holds (not those of its parents); empty when none. */
    // @req FR-IAM-016
    public synchronized Set<String> rolesOf(String groupId) {
        return graph.rolesOf(groupId);
    }

    /** The union of the roles the given groups hold. */
    // @req FR-IAM-016
    @Override
    public synchronized Set<String> rolesOfGroups(Set<String> groupIds) {
        return graph.rolesOfGroups(groupIds);
    }

    @Override
    public synchronized String toString() {
        return "InMemoryGroupStore{groups=" + graph.members.size() + "}";
    }
}
