package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.testing.TestApp;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/**
 * JSON-only bodies on auth POSTs (SEC-FLOW-005). The account endpoints are not built yet, so stand-in handlers on
 * auth.routes() record whether a request got past the body-format check.
 */
public class AuthJsonBodyTest {

    private InMemoryHttpAdapter adapter;
    private AtomicInteger reached;

    private void app(TokenTransport transport) {
        adapter = new InMemoryHttpAdapter("203.0.113.9", true);
        TestApp t = TestApp.create(adapter);
        reached = new AtomicInteger();
        Auth auth = AuthTestSupport.transportBuilder(new AuthTestSupport.TestTokens(), new RecordingLogger())
                .transport(transport).build();
        for (String path : new String[] {"/login", "/logout", "/refresh"}) {
            auth.routes().post(path, (req, res) -> {
                reached.incrementAndGet();
                res.send("reached");
            });
        }
        auth.install(t.app());
        t.start();
    }

    private ClientResponse post(String path, String contentType, String body, String... headers) {
        ClientRequest r = ClientRequest.of("POST", path).body(body.getBytes(StandardCharsets.UTF_8));
        if (contentType != null) {
            r.header("Content-Type", contentType);
        }
        for (int i = 0; i < headers.length; i += 2) {
            r.header(headers[i], headers[i + 1]);
        }
        return adapter.send(r);
    }

    private static void unsupported(ClientResponse r) {
        assertEquals(r.text(), 415, r.status());
        assertEquals("UNSUPPORTED_MEDIA_TYPE", AuthTestSupport.code(r));
        assertNull(r.header("Set-Cookie"));
    }

    // SEC-FLOW-005 AC-1: any mode, a cross-origin form-shaped POST /auth/login -> 415 before the body is read
    @Test
    public void SEC_FLOW_005_AC1_formLoginIs415() {
        for (TokenTransport mode : TokenTransport.values()) {
            app(mode);
            String form = "username=alice&password=pw";
            unsupported(post("/auth/login", "application/x-www-form-urlencoded", form, "Content-Length",
                    String.valueOf(form.length()), "Origin", "https://evil.example"));
            assertEquals(0, reached.get());
        }
    }

    // SEC-FLOW-005 AC-4: logout with Content-Length 5 and text/plain -> 415; login chunked text/plain -> 415
    @Test
    public void SEC_FLOW_005_AC4_bodyByLengthOrChunkedIsChecked() {
        app(TokenTransport.HEADER);
        unsupported(post("/auth/logout", "text/plain", "hello", "Content-Length", "5"));
        unsupported(post("/auth/login", "text/plain", "hello", "Transfer-Encoding", "chunked"));
        assertEquals(0, reached.get());
        // a JSON body and a bodiless POST pass the check
        ClientResponse json = post("/auth/login", "application/json; charset=utf-8", "{}", "Content-Length", "2");
        assertEquals(json.text(), 200, json.status());
        ClientResponse bodiless = post("/auth/logout", null, "", "Content-Length", "0");
        assertEquals(bodiless.text(), 200, bodiless.status());
        assertEquals(2, reached.get());
    }
}
