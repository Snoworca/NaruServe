package com.snoworca.naruserve.gson;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.TestApp;
import com.snoworca.naruserve.utils.auth.Auth;
import com.snoworca.naruserve.utils.auth.apikey.ApiKey;
import com.snoworca.naruserve.utils.auth.apikey.ApiKeyStatus;
import com.snoworca.naruserve.utils.auth.apikey.InMemoryApiKeyStore;
import com.snoworca.naruserve.utils.auth.apikey.StoredApiKey;
import com.snoworca.naruserve.utils.auth.rbac.RolePolicy;
import com.snoworca.naruserve.utils.auth.user.InMemoryUserStore;
import java.io.StringWriter;
import java.util.Collections;
import org.junit.Test;

/** An ApiKey passed to the app's own JSON codec (IR-APIKEY-003 AC-4). */
public class ApiKeyGsonTest {

    // IR-APIKEY-003 AC-4: an app route passing an ApiKey to res.json(...) with the default Gson codec emits the enum
    // the way the codec does (ACTIVE), unlike the auth list endpoints, which write the lower-case strings themselves
    @Test
    public void IR_APIKEY_003_AC4_theAppCodecWritesTheEnumName() throws Exception {
        StoredApiKey row = StoredApiKey.builder().id("k1").prefix("aBcd1234")
                .secretHash("9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08").ownerUserId("u1")
                .ownerVer(0L).name("ci").scopes(Collections.singleton("posts:read")).createdAt(1L).build();
        StringWriter w = new StringWriter();
        new GsonJsonCodec().encode(ApiKey.of(row, ApiKeyStatus.ACTIVE), w);
        String json = w.toString();
        assertTrue(json, json.contains("\"status\":\"ACTIVE\""));
        assertFalse(json, json.contains("9f86d081"));
    }

    // IR-APIKEY-003 AC-3: on an app whose codec writes the enum as ACTIVE, the auth list endpoint still writes "active"
    // IR-APIKEY-003 AC-4: on that app a route passing an ApiKey to res.json(...) writes "ACTIVE"
    @Test
    public void IR_APIKEY_003_AC3_AC4_theRouteFollowsTheCodecAndTheEndpointDoesNot() {
        Auth auth = Auth.builder("k9Qv2Lm7Xr4Tz8Wb1Nc6Ys3Hd5Gf0Ja-Pq").users(new InMemoryUserStore())
                .apiKeys(new InMemoryApiKeyStore()).permit("/k")
                .roles(RolePolicy.builder().role("reader", "posts:read").build()).build();
        String password = "a long password";
        String id = auth.users().create("bot", password.toCharArray(), "reader").getId();
        auth.apiKeys().issue(id, "ci", null, "posts:read");
        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
        TestApp t = TestApp.create(adapter);
        t.app().jsonCodec(new GsonJsonCodec());
        auth.install(t.app());
        t.app().get("/k", (req, res) -> res.json(auth.apiKeys().list(id).get(0)));
        t.start();
        String route = t.send("GET", "/k").text();
        assertTrue(route, route.contains("\"status\":\"ACTIVE\""));
        String login = adapter.send(ClientRequest.json("POST", "/auth/login",
                "{\"username\":\"bot\",\"password\":\"" + password + "\"}")).text();
        String token = new Gson().fromJson(login, JsonObject.class).get("accessToken").getAsString();
        String listed = t.send("GET", "/auth/me/apikeys", "Authorization", "Bearer " + token).text();
        assertTrue(listed, listed.contains("\"status\":\"active\""));
        assertFalse(listed, listed.contains("ACTIVE"));
    }
}
