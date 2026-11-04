package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertEquals;

import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.utils.auth.group.InMemoryGroupStore;
import com.snoworca.naruserve.utils.auth.rbac.RolePolicy;
import org.junit.After;
import org.junit.Test;

/** A role given to a parent group through the built-in store reaches the members of its child (FR-IAM-016 AC-3). */
public class GroupRoleGrantTest {

    private final W8 f = new W8();

    @After
    public void stop() {
        f.stop();
    }

    // FR-IAM-016 AC-3: editor -> posts:write on parent; a direct member of child passes require("posts:write"), a user
    // in no group gets 403
    @Test
    public void FR_IAM_016_AC3_parentGroupRoleReachesChildMembers() throws Exception {
        String carol = f.user("carol", W8.PW);
        f.user("dave", W8.PW);
        InMemoryGroupStore groups = new InMemoryGroupStore();
        groups.createGroup("parent");
        groups.createGroup("child");
        groups.addParent("child", "parent");
        groups.addMember("child", carol);
        groups.grantRole("parent", "editor");
        f.routes = w -> w.app.get("/api/write", w.auth.require("posts:write"), (req, res) -> res.send("written"));
        f.start(b -> b.groups(groups).roles(RolePolicy.builder().role("editor", "posts:write").build()));
        ClientResponse ok = f.get("/api/write", "Authorization",
                "Bearer " + f.loginBody("carol", W8.PW).get("accessToken"));
        assertEquals(ok.text(), 200, ok.status());
        assertEquals("written", ok.text());
        ClientResponse no = f.get("/api/write", "Authorization",
                "Bearer " + f.loginBody("dave", W8.PW).get("accessToken"));
        assertEquals(no.text(), 403, no.status());
    }
}
