package com.snoworca.naruserve.gson;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.utils.auth.apikey.ApiKey;
import com.snoworca.naruserve.utils.auth.apikey.ApiKeyStatus;
import com.snoworca.naruserve.utils.auth.apikey.StoredApiKey;
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
}
