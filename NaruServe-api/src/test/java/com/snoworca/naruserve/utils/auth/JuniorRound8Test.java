package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.utils.auth.group.GroupStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.After;
import org.junit.Test;

/** The code findings of the eighth junior study are answered with the fix (OPS-AUTH-020). */
public class JuniorRound8Test {

    private final List<AdminFixture> admins = new ArrayList<>();

    @After
    public void stop() {
        AdminFixture.stopAll(admins);
    }

    private AdminFixture admin(java.util.function.UnaryOperator<Auth.Builder> config) throws Exception {
        AdminFixture a = new AdminFixture();
        admins.add(a);
        a.start(config);
        return a;
    }

    private static String message(ClientResponse r) {
        return String.valueOf(((Map<?, ?>) W8.object(r).get("error")).get("message"));
    }

    // OPS-AUTH-020 AC-1: without a GroupAdminStore the group admin endpoints answer 404 naming .groups(...)
    @Test
    public void OPS_AUTH_020_AC1_groupEndpointsWithoutAdminStoreSaySo() throws Exception {
        final AdminFixture.RoleGroups g = new AdminFixture.RoleGroups();
        AdminFixture a = admin(b -> b.groups(new GroupStore() { // a plain GroupStore, not a GroupAdminStore
            @Override
            public Set<String> groupsOfUser(String userId) {
                return g.groupsOfUser(userId);
            }

            @Override
            public Set<String> parentsOf(Set<String> groupIds) {
                return g.parentsOf(groupIds);
            }
        }));
        String root = a.user("root", "admin");
        for (String[] c : new String[][] {{"POST", "/auth/admin/groups", "{\"id\":\"eng\"}"}, {"GET", "/auth/admin/groups/eng", null},
                {"PUT", "/auth/admin/groups/eng/members/x", null}}) {
            ClientResponse r = a.call(c[0], c[1], root, c[2]);
            assertEquals(c[1] + " " + r.text(), 404, r.status());
            assertEquals("NOT_FOUND", W8.code(r));
            assertTrue(r.text(), message(r).contains("GroupAdminStore") && message(r).contains(".groups("));
        }
    }

    // OPS-AUTH-020 AC-2: a non-integer ?limit= or ?offset= on A1 names the parameter
    @Test
    public void OPS_AUTH_020_AC2_badPagingParameterIsNamed() throws Exception {
        AdminFixture a = admin(b -> b);
        String root = a.user("root", "admin");
        ClientResponse r = a.call("GET", "/auth/admin/users?limit=abc", root, null);
        assertEquals(r.text(), 400, r.status());
        assertEquals("limit must be a 32-bit integer", message(r));
        ClientResponse o = a.call("GET", "/auth/admin/users?offset=x", root, null);
        assertEquals(o.text(), 400, o.status());
        assertEquals("offset must be a 32-bit integer", message(o));
    }

    // OPS-AUTH-020 AC-3: an API-key issuance request-shape refusal keeps its message
    @Test
    public void OPS_AUTH_020_AC3_keyIssuanceShapeRefusalIsNamed() throws Exception {
        AdminFixture a = admin(b -> b.apiKeys(new AdminFixture.ListKeys()));
        String root = a.user("root", "admin", "keyswriter");
        String u1 = a.user("u1");
        ClientResponse r = a.call("POST", "/auth/admin/apikeys", root,
                "{\"ownerUserId\":\"" + u1 + "\",\"name\":\"\",\"scopes\":[\"posts:read\"]}");
        assertEquals(r.text(), 400, r.status());
        assertTrue(r.text(), message(r).startsWith("name must be a JSON string of 1 to "));
    }
}
