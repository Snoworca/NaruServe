package com.snoworca.naruserve.utils.auth.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.TestApp;
import java.util.Collections;
import java.util.Set;
import org.junit.Test;

/** The Origin/Referer check used before auth cookies are planted (SEC-FLOW-007 component). */
public class OriginsTest {

    private static String check(boolean secure, Set<String> trusted, String... headers) {
        TestApp t = TestApp.create(new InMemoryHttpAdapter("127.0.0.1", secure));
        t.app().post("/auth/login", (req, res) -> res.send(Origins.requestOrigin(req) + " "
                + Origins.passes(req, trusted)));
        return t.send("POST", "/auth/login", headers).text();
    }

    // SEC-FLOW-007 component: origins are scheme://host[:port], lower case, default port dropped
    @Test
    public void originOfAUrl() {
        assertEquals("https://app.example.com", Origins.of("https://App.Example.com"));
        assertEquals("https://app.example.com", Origins.of("https://app.example.com:443/path?q=1"));
        assertEquals("http://app.example.com:8080", Origins.of("http://app.example.com:8080/x"));
        assertEquals("http://localhost", Origins.of("http://localhost:80"));
        assertEquals("http://[::1]:8080", Origins.of("http://[::1]:8080/"));
        assertNull(Origins.of("null"));
        assertNull(Origins.of(""));
        assertNull(Origins.of("ftp://x"));
        assertNull(Origins.of("https://"));
        assertNull(Origins.of("//app.example.com"));
        assertNull(Origins.of("https://user@app.example.com"));
        assertNull(Origins.of("https://app.example.com:99999"));
    }

    // SEC-FLOW-007 component: same origin passes; cross origin, Origin null and neither header fail closed
    @Test
    public void sameOriginCrossOriginNullAndMissing() {
        Set<String> none = Collections.emptySet();
        assertEquals("http://app.test true", check(false, none, "Host", "app.test", "Origin", "http://app.test"));
        assertEquals("https://app.test true", check(true, none, "Host", "App.Test:443", "Origin", "https://app.test"));
        assertEquals("http://app.test false", check(false, none, "Host", "app.test", "Origin", "https://app.test"));
        assertEquals("http://app.test false", check(false, none, "Host", "app.test", "Origin", "http://evil.test"));
        assertEquals("http://app.test false", check(false, none, "Host", "app.test", "Origin", "null"));
        assertEquals("http://app.test false", check(false, none, "Host", "app.test"));
    }

    // SEC-FLOW-007 component: without Origin the Referer's origin decides; Origin wins when both are present
    @Test
    public void refererIsTheFallback() {
        Set<String> none = Collections.emptySet();
        assertEquals("http://app.test true", check(false, none, "Host", "app.test", "Referer",
                "http://app.test/login?next=/"));
        assertEquals("http://app.test false", check(false, none, "Host", "app.test", "Referer", "http://evil.test/"));
        assertEquals("http://app.test false", check(false, none, "Host", "app.test", "Origin", "http://evil.test",
                "Referer", "http://app.test/"));
    }

    // SEC-FLOW-007 component: a listed trusted origin passes
    @Test
    public void trustedOriginsPass() {
        Set<String> trusted = Collections.singleton("https://admin.example.com");
        assertEquals("http://app.test true", check(false, trusted, "Host", "app.test", "Origin",
                "https://admin.example.com"));
        assertEquals("http://app.test false", check(false, trusted, "Host", "app.test", "Origin",
                "https://other.example.com"));
        Set<String> spelled = Collections.singleton("https://Admin.Example.com:443/");
        assertEquals("http://app.test true", check(false, spelled, "Host", "app.test", "Origin",
                "https://admin.example.com"));
    }
}
