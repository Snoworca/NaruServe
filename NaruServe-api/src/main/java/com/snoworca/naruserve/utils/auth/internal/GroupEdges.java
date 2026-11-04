package com.snoworca.naruserve.utils.auth.internal;

import com.snoworca.naruserve.utils.auth.AuthErrorCode;
import com.snoworca.naruserve.utils.auth.AuthRejection;
import com.snoworca.naruserve.utils.auth.group.GroupAdminStore;
import com.snoworca.naruserve.utils.auth.group.GroupLimits;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The check auth runs before a group edge child ⊂ parent is stored: no self edge, no cycle (the child must not be an
 * ancestor of the parent: a walk upward from the parent), and no chain longer than the depth limit (the longest path
 * above the parent, plus the new edge, plus the longest path below the child). A refusal is
 * {@code AuthRejection(409, "group_cycle")}, and the store's {@code addParent} is not called.
 */
// @req IR-IAM-004
public final class GroupEdges {

    /** The two direct relations the check walks. */
    public interface Graph {
        /** The direct parents of {@code group}. */
        Set<String> parentsOf(String group) throws Exception;

        /** The direct children of {@code group}. */
        Set<String> childrenOf(String group) throws Exception;
    }

    private GroupEdges() {
    }

    /** The relations of an admin store. */
    // @req IR-IAM-004
    public static Graph of(final GroupAdminStore store) {
        return new Graph() {
            @Override
            public Set<String> parentsOf(String group) throws Exception {
                return store.parentsOf(Collections.singleton(group));
            }

            @Override
            public Set<String> childrenOf(String group) throws Exception {
                return store.childrenOf(group);
            }
        };
    }

    /** Checks the edge, then calls {@code store.addParent}; a refused edge never reaches the store. */
    // @req IR-IAM-004
    public static void addParent(GroupAdminStore store, String childId, String parentId, GroupLimits limits)
            throws Exception {
        checkChecked(of(store), childId, parentId, limits);
        store.addParent(childId, parentId);
    }

    /** {@link #checkChecked} for a graph whose relations throw no checked exception. */
    // @req IR-IAM-004
    public static void check(Graph graph, String childId, String parentId, GroupLimits limits) {
        try {
            checkChecked(graph, childId, parentId, limits);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Throws {@code AuthRejection(409, "group_cycle")} when the edge would close a cycle or pass the depth limit. */
    // @req IR-IAM-004
    public static void checkChecked(Graph graph, String childId, String parentId, GroupLimits limits)
            throws Exception {
        if (childId.equals(parentId)) {
            throw refuse("group " + childId + " cannot be its own parent");
        }
        int cap = limits.getMaxDepth() + 1;
        int above = longest(graph, parentId, true, childId, cap, new HashSet<String>(), new HashMap<String, Integer>());
        if (above < 0) {
            throw refuse("group " + childId + " is already an ancestor of " + parentId + "; the edge would close a cycle");
        }
        int below = longest(graph, childId, false, null, cap, new HashSet<String>(), new HashMap<String, Integer>());
        if (above + 1 + below > limits.getMaxDepth()) {
            throw refuse("the edge " + childId + " ⊂ " + parentId + " makes a group chain longer than "
                    + limits.getMaxDepth());
        }
    }

    /**
     * The longest path in edges from {@code node} upward (or downward), capped at {@code cap}; -1 when the walk meets
     * {@code forbidden}. Stored cycles stop at the nodes on the current path.
     */
    private static int longest(Graph graph, String node, boolean up, @Nullable String forbidden, int cap, Set<String> path,
            Map<String, Integer> memo) throws Exception {
        if (node.equals(forbidden)) {
            return -1;
        }
        Integer known = memo.get(node);
        if (known != null) {
            return known;
        }
        if (path.size() > cap || !path.add(node)) {
            return 0;
        }
        int best = 0;
        for (String next : up ? graph.parentsOf(node) : graph.childrenOf(node)) {
            if (path.contains(next)) {
                continue;
            }
            int d = longest(graph, next, up, forbidden, cap, path, memo);
            if (d < 0) {
                path.remove(node);
                return -1;
            }
            best = Math.max(best, Math.min(cap, d + 1));
        }
        path.remove(node);
        memo.put(node, best);
        return best;
    }

    private static AuthRejection refuse(String message) {
        return new AuthRejection(AuthErrorCode.GROUP_CYCLE.getStatus(), AuthErrorCode.GROUP_CYCLE.getCode(), message);
    }
}
