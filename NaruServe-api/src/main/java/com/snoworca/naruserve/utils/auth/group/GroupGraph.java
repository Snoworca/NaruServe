package com.snoworca.naruserve.utils.auth.group;

import com.snoworca.naruserve.utils.auth.internal.AuthArgumentException;
import com.snoworca.naruserve.utils.auth.internal.GroupEdges;
import com.snoworca.naruserve.utils.auth.internal.GroupIds;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/** The in-memory group state shared by the in-memory and file stores; callers hold its monitor. */
// @req IR-IAM-004
final class GroupGraph {

    final Map<String, Set<String>> members = new TreeMap<>();
    final Map<String, Set<String>> groupsOfUser = new TreeMap<>();
    final Map<String, Set<String>> parents = new TreeMap<>();
    final Map<String, Set<String>> children = new TreeMap<>();
    /** The roles each group confers (FR-IAM-016). */
    final Map<String, Set<String>> roles = new TreeMap<>();
    /** Runs between the edge check and the insert of {@link #addParent}; null in production, set by the tests. */
    volatile @Nullable Runnable checked;

    private static Set<String> slot(Map<String, Set<String>> map, String key) {
        Set<String> s = map.get(key);
        if (s == null) {
            s = new LinkedHashSet<>();
            map.put(key, s);
        }
        return s;
    }

    private static Set<String> copy(Map<String, Set<String>> map, String key) {
        Set<String> s = map.get(key);
        return s == null ? Collections.<String>emptySet() : Collections.unmodifiableSet(new LinkedHashSet<>(s));
    }

    private static void unlink(Map<String, Set<String>> map, String key, String value) {
        Set<String> s = map.get(key);
        if (s != null) {
            s.remove(value);
            if (s.isEmpty()) {
                map.remove(key);
            }
        }
    }

    static String checkId(String groupId) {
        if (!GroupIds.isValid(groupId)) {
            throw new AuthArgumentException("group id must match [a-z0-9._-]{1,64}: " + groupId);
        }
        return groupId;
    }

    Set<String> groupsOfUser(String userId) {
        return copy(groupsOfUser, userId);
    }

    Set<String> parentsOf(Set<String> groupIds) {
        Set<String> out = new LinkedHashSet<>();
        for (String g : groupIds) {
            Set<String> p = parents.get(g);
            if (p != null) {
                out.addAll(p);
            }
        }
        return Collections.unmodifiableSet(out);
    }

    boolean create(String groupId) {
        checkId(groupId);
        if (members.containsKey(groupId)) {
            return false;
        }
        members.put(groupId, new LinkedHashSet<String>());
        return true;
    }

    boolean delete(String groupId) {
        Set<String> m = members.remove(groupId);
        if (m == null) {
            return false;
        }
        for (String user : m) {
            unlink(groupsOfUser, user, groupId);
        }
        for (String p : copy(parents, groupId)) {
            unlink(children, p, groupId);
        }
        parents.remove(groupId);
        for (String c : copy(children, groupId)) {
            unlink(parents, c, groupId);
        }
        children.remove(groupId);
        roles.remove(groupId);
        return true;
    }

    List<String> list() {
        return Collections.unmodifiableList(new ArrayList<>(members.keySet()));
    }

    boolean exists(String groupId) {
        return members.containsKey(groupId);
    }

    void addMember(String groupId, String userId) {
        requireGroup(groupId);
        slot(members, groupId).add(userId);
        slot(groupsOfUser, userId).add(groupId);
    }

    void removeMember(String groupId, String userId) {
        Set<String> m = members.get(groupId);
        if (m != null) {
            m.remove(userId);
        }
        unlink(groupsOfUser, userId, groupId);
    }

    Set<String> membersOf(String groupId) {
        return copy(members, groupId);
    }

    /** Checks the edge against this graph (cycle and depth, under the caller's lock) and inserts it. */
    void addParent(String childId, String parentId, GroupLimits limits) {
        requireGroup(childId);
        requireGroup(parentId);
        if (parents.containsKey(childId) && parents.get(childId).contains(parentId)) {
            return;
        }
        GroupEdges.check(new GroupEdges.Graph() {
            @Override
            public Set<String> parentsOf(String group) {
                return copy(parents, group);
            }

            @Override
            public Set<String> childrenOf(String group) {
                return copy(children, group);
            }
        }, childId, parentId, limits);
        Runnable hook = checked;
        if (hook != null) {
            hook.run();
        }
        slot(parents, childId).add(parentId);
        slot(children, parentId).add(childId);
    }

    /** Inserts a stored edge as it is, without the check (reading a file that was checked when written). */
    void link(String childId, String parentId) {
        slot(parents, childId).add(parentId);
        slot(children, parentId).add(childId);
    }

    void removeParent(String childId, String parentId) {
        unlink(parents, childId, parentId);
        unlink(children, parentId, childId);
    }

    Set<String> childrenOf(String groupId) {
        return copy(children, groupId);
    }

    void removeUser(String userId) {
        for (String g : copy(groupsOfUser, userId)) {
            removeMember(g, userId);
        }
    }

    /** A role name: not empty and without the tab, CR or LF the groups file uses as separators. */
    static String checkRole(String role) {
        if (role.isEmpty() || role.indexOf('\t') >= 0 || role.indexOf('\r') >= 0 || role.indexOf('\n') >= 0) {
            throw new AuthArgumentException("a role name must not be empty or hold a tab, CR or LF");
        }
        return role;
    }

    // @req FR-IAM-016
    void grantRole(String groupId, String role) {
        checkRole(role);
        requireGroup(groupId);
        slot(roles, groupId).add(role);
    }

    // @req FR-IAM-016
    void revokeRole(String groupId, String role) {
        unlink(roles, groupId, role);
    }

    // @req FR-IAM-016
    Set<String> rolesOf(String groupId) {
        return copy(roles, groupId);
    }

    // @req FR-IAM-016
    Set<String> rolesOfGroups(Set<String> groupIds) {
        Set<String> out = new LinkedHashSet<>();
        for (String g : groupIds) {
            Set<String> r = roles.get(g);
            if (r != null) {
                out.addAll(r);
            }
        }
        return Collections.unmodifiableSet(out);
    }

    private void requireGroup(String groupId) {
        if (!members.containsKey(groupId)) {
            throw new AuthArgumentException("no group " + groupId);
        }
    }
}
