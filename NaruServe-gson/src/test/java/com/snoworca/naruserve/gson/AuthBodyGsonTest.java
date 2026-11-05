package com.snoworca.naruserve.gson;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.utils.auth.Auth;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Auth-router bodies under the shipped Gson codec (OPS-AUTH-014 AC-1, the answer OPS-AUTH-017 AC-2 documents). */
public class AuthBodyGsonTest {

    private final InMemoryHttpAdapter adapter = new InMemoryHttpAdapter("127.0.0.1", true);
    private RestApp app;

    @Before
    public void start() {
        app = RestApp.builder().httpAdapter(adapter).jsonCodec(new GsonJsonCodec()).build();
        Auth.builder("k9Qv2Lm7Xr4Tz8Wb1Nc6Ys3Hd5Gf0Ja-Pq").build().install(app);
        app.listen(0);
    }

    @After
    public void stop() {
        app.shutdown(5, TimeUnit.SECONDS);
    }

    private ClientResponse login(String body) {
        return adapter.send(ClientRequest.json("POST", "/auth/login", body));
    }

    // OPS-AUTH-014 AC-1: under Gson a body that does not parse and a JSON array or string get the one 400 message
    @Test
    public void OPS_AUTH_014_AC1_oneMessageUnderTheGsonCodec() {
        for (String body : new String[] {"{\"username\":", "[1]", "\"alice\""}) {
            ClientResponse r = login(body);
            assertEquals(body + ": " + r.text(), 400, r.status());
            assertTrue(r.text(), r.text().contains("\"BAD_REQUEST\""));
            assertTrue(r.text(), r.text().contains("the request body must be a JSON object"));
        }
    }

    // OPS-AUTH-017 AC-2: the documented answer holds: Gson reads [] as an empty object, so it is the missing field
    @Test
    public void OPS_AUTH_017_AC2_emptyArrayGetsTheMissingFieldAnswer() {
        ClientResponse r = login("[]");
        assertEquals(r.text(), 400, r.status());
        assertTrue(r.text(), r.text().contains("username must be a JSON string"));
    }
}
