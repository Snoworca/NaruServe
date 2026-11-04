package com.snoworca.naruserve.gson;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.GsonBuilder;
import com.snoworca.naruserve.utils.auth.user.AuthUser;
import java.io.StringWriter;
import java.lang.reflect.Modifier;
import org.junit.Test;

/** The transient AuthUser.passwordHash under the default Gson codec (DR-IAM-001 AC-3). */
public class AuthUserGsonTest {

    private static String encode(GsonJsonCodec codec, Object value) throws Exception {
        StringWriter w = new StringWriter();
        codec.encode(value, w);
        return w.toString();
    }

    // DR-IAM-001 AC-3: new GsonJsonCodec() leaves passwordHash out of the JSON of an AuthUser with a hash
    @Test
    public void DR_IAM_001_AC3_defaultGsonOmitsThePasswordHash() throws Exception {
        AuthUser u = AuthUser.builder().id("7").username("alice").passwordHash("$pbkdf2-sha256$the-hash")
                .roles("editor").build();
        String json = encode(new GsonJsonCodec(), u);
        assertFalse(json, json.contains("passwordHash"));
        assertFalse(json, json.contains("the-hash"));
        assertTrue(json, json.contains("\"username\":\"alice\""));
        // the documented limit (AC-7): a Gson that excludes only static fields does write the hash
        String leaky = encode(new GsonJsonCodec(new GsonBuilder().excludeFieldsWithModifiers(Modifier.STATIC)
                .create()), u);
        assertTrue(leaky, leaky.contains("the-hash"));
    }
}
