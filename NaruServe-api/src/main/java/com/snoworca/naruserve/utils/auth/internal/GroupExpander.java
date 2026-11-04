package com.snoworca.naruserve.utils.auth.internal;

import com.snoworca.naruserve.utils.auth.AuthUnavailableException;
import com.snoworca.naruserve.utils.auth.group.GroupLimits;
import com.snoworca.naruserve.utils.auth.group.GroupStore;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The default group expansion: from the direct groups upward, one {@code parentsOf} call per level. A group is
 * counted once, so diamonds and stored cycles terminate. Passing the depth or size limit fails with
 * {@link AuthUnavailableException} (503, fail closed) instead of returning part of the groups.
 */
// @req FR-IAM-007
public final class GroupExpander {

    private GroupExpander() {
    }

    /** Every group of {@code userId}, direct and inherited, unmodifiable. */
    // @req FR-IAM-007
    public static Set<String> expand(GroupStore store, String userId, GroupLimits limits) throws Exception {
        Set<String> result = new LinkedHashSet<>(store.groupsOfUser(userId));
        checkSize(result, userId, limits);
        Set<String> frontier = new LinkedHashSet<>(result);
        int depth = 0;
        while (!frontier.isEmpty()) {
            Set<String> next = new LinkedHashSet<>();
            for (String parent : store.parentsOf(Collections.unmodifiableSet(frontier))) {
                if (!result.contains(parent)) {
                    next.add(parent);
                }
            }
            if (next.isEmpty()) {
                break;
            }
            depth++;
            if (depth > limits.getMaxDepth()) {
                throw new AuthUnavailableException("group expansion of user " + userId + " goes past depth "
                        + limits.getMaxDepth() + "; no partial group set is used");
            }
            result.addAll(next);
            checkSize(result, userId, limits);
            frontier = next;
        }
        return Collections.unmodifiableSet(result);
    }

    /** Fails when {@code groups} holds more than the size limit. */
    // @req FR-IAM-007
    public static void checkSize(Set<String> groups, String userId, GroupLimits limits) {
        if (groups.size() > limits.getMaxSize()) {
            throw new AuthUnavailableException("group expansion of user " + userId + " reaches more than "
                    + limits.getMaxSize() + " groups; no partial group set is used");
        }
    }
}
