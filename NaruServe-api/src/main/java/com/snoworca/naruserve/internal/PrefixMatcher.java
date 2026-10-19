package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.routing.Router;
import com.snoworca.naruserve.routing.RouterPolicy;
import java.util.ArrayList;
import java.util.List;

/**
 * One {@code use(prefix, mw)} prefix, decoded with the app's {@link RouterPolicy} so it covers exactly the paths the
 * router would route under it. Trailing empty segments are dropped: {@code "/api/"} is {@code "/api"} and
 * {@code "/"} covers every path.
 */
// @req FR-APP-003
final class PrefixMatcher {

    private final List<String> segments;
    private final boolean caseInsensitive;

    // @req FR-APP-003
    PrefixMatcher(String prefix, RouterPolicy policy) {
        List<String> s = new ArrayList<>(Router.decodeSegments(prefix, policy));
        while (!s.isEmpty() && s.get(s.size() - 1).isEmpty()) {
            s.remove(s.size() - 1);
        }
        this.segments = s;
        this.caseInsensitive = policy.caseInsensitive();
    }

    /** True when {@code pathSegments} (from {@link Router#decodeSegments}) start with every prefix segment. */
    // @req FR-APP-003
    boolean matches(List<String> pathSegments) {
        if (pathSegments.size() < segments.size()) {
            return false;
        }
        for (int i = 0; i < segments.size(); i++) {
            String p = segments.get(i);
            String s = pathSegments.get(i);
            if (caseInsensitive ? !p.equalsIgnoreCase(s) : !p.equals(s)) {
                return false;
            }
        }
        return true;
    }
}
