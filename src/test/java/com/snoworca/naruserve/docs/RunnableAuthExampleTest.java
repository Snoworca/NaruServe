package com.snoworca.naruserve.docs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.RestApp;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/** examples/java AuthExample over real HTTP (OPS-AUTH-007 AC-7). */
public class RunnableAuthExampleTest {

    /** "status body" of one request; the body is always JSON so POST requests carry a length. */
    static String call(int port, String method, String path, String token, String json) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(10000);
        c.setRequestMethod(method);
        c.setRequestProperty("Connection", "close");
        if (token != null) {
            c.setRequestProperty("Authorization", "Bearer " + token);
        }
        if (json != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = c.getOutputStream()) {
                out.write(json.getBytes(StandardCharsets.UTF_8));
            }
        }
        try {
            int status = c.getResponseCode();
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            try (InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream()) {
                if (in != null) {
                    byte[] buf = new byte[1024];
                    for (int n = in.read(buf); n > 0; n = in.read(buf)) {
                        body.write(buf, 0, n);
                    }
                }
            }
            return status + " " + new String(body.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            c.disconnect();
        }
    }

    static String login(int port, String user, String password) throws Exception {
        String answer = call(port, "POST", "/auth/login", null,
                "{\"username\":\"" + user + "\",\"password\":\"" + password + "\"}");
        assertTrue(answer, answer.startsWith("200 "));
        Matcher m = Pattern.compile("\"accessToken\":\"([^\"]+)\"").matcher(answer);
        assertTrue(answer, m.find());
        return m.group(1);
    }

    // OPS-AUTH-007 AC-7: the Java auth example starts on port 0, serves /health in public, logs alice in over HTTP,
    // answers the protected /me with her Bearer token, lets the editor write and refuses bob, who has no role
    @Test
    public void OPS_AUTH_007_AC7_javaAuthExampleLogsInOverHttp() throws Exception {
        RestApp app = (RestApp) Class.forName("example.AuthExample").getMethod("create").invoke(null);
        try {
            int port = app.listen(0).port();
            assertEquals("200 ok", call(port, "GET", "/health", null, null));
            assertTrue(call(port, "GET", "/me", null, null).startsWith("401 "));
            String alice = login(port, "alice", "alice-password-1");
            assertEquals("200 alice", call(port, "GET", "/me", alice, null));
            assertEquals("201 created by alice", call(port, "POST", "/posts", alice, "{}"));
            String bob = login(port, "bob", "bob-password-1");
            assertEquals("200 bob", call(port, "GET", "/me", bob, null));
            String refused = call(port, "POST", "/posts", bob, "{}");
            assertTrue(refused, refused.startsWith("403 ") && refused.contains("insufficient_scope"));
        } finally {
            app.shutdown(5, TimeUnit.SECONDS);
        }
    }
}
