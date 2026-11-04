package com.snoworca.naruserve.gson;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.testing.TestApp;
import com.snoworca.naruserve.utils.auth.Auth;
import com.snoworca.naruserve.utils.auth.user.AuthUser;
import com.snoworca.naruserve.utils.auth.user.InMemoryUserStore;
import org.junit.Test;

/** res.json(auth.users().findById(id)) under the default Gson codec (IR-IAM-003 AC-2). */
public class AuthUsersGsonTest {

    // IR-IAM-003 AC-2 (default Gson half): the passwordHash member is absent from the JSON of a facade user
    @Test
    public void IR_IAM_003_AC2_defaultGsonWritesNoHash() {
        InMemoryUserStore store = new InMemoryUserStore();
        Auth auth = Auth.builder("k9Qv2Lm7Xr4Tz8Wb1Nc6Ys3Hd5Gf0Ja-Pq").users(store).permit("/u").build();
        AuthUser alice = auth.users().create("alice", "a long password".toCharArray(), "editor");
        String stored = store.findById(alice.getId()).getPasswordHash();
        assertNotNull(stored);
        TestApp t = TestApp.create();
        t.app().jsonCodec(new GsonJsonCodec());
        auth.install(t.app());
        t.app().get("/u", (req, res) -> res.json(auth.users().findById(alice.getId())));
        String body = t.send("GET", "/u").text();
        assertTrue(body, body.contains("\"username\":\"alice\""));
        assertFalse(body, body.contains("passwordHash"));
        assertFalse(body, body.contains(stored));
    }
}
