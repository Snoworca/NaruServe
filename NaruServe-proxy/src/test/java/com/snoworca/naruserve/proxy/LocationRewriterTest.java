package com.snoworca.naruserve.proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.snoworca.naruserve.proxy.ProxyResponseHeadersTest.RecordingResponse;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * The pure {@code Location} rewrite of {@link LocationRewriter} (FR-PROXY-013): the origin comparison, the base path
 * prefix comparison and the assembled result, without a backend.
 */
public class LocationRewriterTest {

    private static String rewrite(String target, String prefix, String location) {
        return LocationRewriter.rewrite(location, TargetUrl.parse(target), prefix);
    }

    // FR-PROXY-013 AC-1: a same-origin absolute Location loses its origin; the path stays when the prefix is empty
    @Test
    public void fr_PROXY_013_AC1_sameOriginAbsoluteLocationBecomesThePath() {
        assertEquals("/api/orders/x", rewrite("http://127.0.0.1:8081", "", "http://127.0.0.1:8081/api/orders/x"));
    }

    // FR-PROXY-013 AC-2: relative references are left unchanged
    @Test
    public void fr_PROXY_013_AC2_relativeReferencesAreUnchanged() {
        assertEquals("/x", rewrite("http://h", "", "/x"));
        assertEquals("x/y", rewrite("http://h", "", "x/y"));
        assertEquals("/x", rewrite("http://h", "/api/orders", "/x"));
        assertEquals("?a=1", rewrite("http://h", "", "?a=1"));
        assertEquals("", rewrite("http://h", "", ""));
    }

    // FR-PROXY-013 AC-2: another host and another port are left unchanged
    @Test
    public void fr_PROXY_013_AC2_anotherHostOrPortIsUnchanged() {
        assertEquals("http://other.example/x", rewrite("http://h", "", "http://other.example/x"));
        assertEquals("http://h:8080/x", rewrite("http://h:808", "", "http://h:8080/x"));
        assertEquals("http://h:80/x", rewrite("http://h:808", "", "http://h:80/x"));
        assertEquals("http://h.other/x", rewrite("http://h", "", "http://h.other/x"));
        assertEquals("http://hh/x", rewrite("http://h", "", "http://hh/x"));
    }

    // FR-PROXY-013 AC-2: a value the Proxy.to host and port grammar refuses is left unchanged
    @Test
    public void fr_PROXY_013_AC2_valuesOutsideTheTargetGrammarAreUnchanged() {
        assertEquals("http://u@h/x", rewrite("http://h", "", "http://u@h/x"));
        assertEquals("http://h:/x", rewrite("http://h", "", "http://h:/x"));
        assertEquals("http://h:0x50/x", rewrite("http://h", "", "http://h:0x50/x"));
        assertEquals("ftp://h/x", rewrite("http://h", "", "ftp://h/x"));
        assertEquals("http:/x", rewrite("http://h", "", "http:/x"));
        assertEquals("http:x", rewrite("http://h", "", "http:x"));
        assertEquals("mailto:a@h", rewrite("http://h", "", "mailto:a@h"));
        assertEquals("http://", rewrite("http://h", "", "http://"));
        assertEquals("//", rewrite("http://h", "", "//"));
    }

    // FR-PROXY-013 AC-3: under stripPrefix the client-side prefix replaces the origin and the base path
    @Test
    public void fr_PROXY_013_AC3_clientPrefixReplacesOriginAndBasePath() {
        assertEquals("/api/orders/x", rewrite("http://h:9", "/api/orders", "http://h:9/x"));
        assertEquals("/api/orders/x", rewrite("http://h:9/base", "/api/orders", "http://h:9/base/x"));
    }

    // FR-PROXY-013 AC-4: the base path is compared on raw characters as a whole segment prefix
    @Test
    public void fr_PROXY_013_AC4_basePathMustEqualOrContinueWithASlash() {
        assertEquals("/api/orders/y", rewrite("http://h/base", "", "http://h/base/api/orders/y"));
        assertEquals("http://h/basement/y", rewrite("http://h/base", "", "http://h/basement/y"));
        assertEquals("http://h/other/y", rewrite("http://h/base", "", "http://h/other/y"));
        assertEquals("http://h/BASE/y", rewrite("http://h/base", "", "http://h/BASE/y"));
        assertEquals("http://h/%62ase/y", rewrite("http://h/base", "", "http://h/%62ase/y"));
        assertEquals("http://h", rewrite("http://h/base", "", "http://h"));
        assertEquals("http://h/", rewrite("http://h/base", "", "http://h/"));
    }

    // FR-PROXY-013 AC-5: scheme and host compare case-insensitively and a default port equals an omitted one
    @Test
    public void fr_PROXY_013_AC5_originComparisonIgnoresCaseAndDefaultPorts() {
        assertEquals("/x", rewrite("http://h", "", "http://H:80/x"));
        assertEquals("/x", rewrite("http://h", "", "HTTP://h/x"));
        assertEquals("/x", rewrite("https://h", "", "https://h:443/x"));
        assertEquals("/x", rewrite("http://h:80", "", "http://h/x"));
        assertEquals("/x", rewrite("http://H", "", "http://h/x"));
        assertEquals("https://h/x", rewrite("http://h", "", "https://h/x"));
        assertEquals("http://h/x", rewrite("https://h", "", "http://h/x"));
        assertEquals("http://h:443/x", rewrite("https://h", "", "http://h:443/x"));
    }

    // FR-PROXY-013 AC-5: IPv6 literals compare with their brackets, case-insensitively
    @Test
    public void fr_PROXY_013_AC5_ipv6LiteralsCompareCaseInsensitively() {
        assertEquals("/x", rewrite("http://[::1]:9", "", "http://[::1]:9/x"));
        assertEquals("/x", rewrite("http://[fe80::a]:9", "", "http://[FE80::A]:9/x"));
        assertEquals("http://[::2]:9/x", rewrite("http://[::1]:9", "", "http://[::2]:9/x"));
    }

    // FR-PROXY-013 AC-6: an empty resulting path becomes "/"
    @Test
    public void fr_PROXY_013_AC6_emptyResultingPathIsSlash() {
        assertEquals("/", rewrite("http://h/base", "", "http://h/base"));
        assertEquals("/", rewrite("http://h", "", "http://h"));
        assertEquals("/", rewrite("http://h/base", "", "http://h/base/"));
        assertEquals("/", rewrite("http://h", "", "http://h/"));
        assertEquals("/?a=1", rewrite("http://h", "", "http://h?a=1"));
        assertEquals("/#f", rewrite("http://h/base", "", "http://h/base#f"));
    }

    // FR-PROXY-013 AC-6: a non-empty client prefix is the whole path when nothing follows the base path
    @Test
    public void fr_PROXY_013_AC6_prefixAloneIsTheResultingPath() {
        assertEquals("/api/orders", rewrite("http://h/base", "/api/orders", "http://h/base"));
        assertEquals("/api/orders/", rewrite("http://h/base", "/api/orders", "http://h/base/"));
        assertEquals("/api/orders?a=1", rewrite("http://h", "/api/orders", "http://h?a=1"));
    }

    // FR-PROXY-013 AC-8: the result never carries an origin; a resulting path a client would read as a
    // network-path reference (another origin) gets a leading "/." so it stays a path on the client's origin
    @Test
    public void fr_PROXY_013_AC8_rewrittenValueHasNoOrigin() {
        assertEquals("/api//evil.example/x",
                rewrite("http://127.0.0.1:9", "/api", "http://127.0.0.1:9//evil.example/x"));
        assertEquals("/.//evil.example/x", rewrite("http://127.0.0.1:9", "", "http://127.0.0.1:9//evil.example/x"));
        assertEquals("/./\\evil.example/x", rewrite("http://127.0.0.1:9", "", "http://127.0.0.1:9/\\evil.example/x"));
        assertEquals("/./\t/evil.example/x", rewrite("http://127.0.0.1:9", "", "http://127.0.0.1:9/\t/evil.example/x"));
        assertEquals("/./\\\\evil.example/x?a#f",
                rewrite("http://127.0.0.1:9", "", "http://127.0.0.1:9/\\\\evil.example/x?a#f"));
        assertEquals("/.//evil.example/x",
                rewrite("http://127.0.0.1:9/base", "", "http://127.0.0.1:9/base//evil.example/x"));
        assertEquals("/.//evil.example/x", rewrite("http://h", "", "//h//evil.example/x"));
        assertFalse(rewrite("http://127.0.0.1:9/base", "", "http://127.0.0.1:9/base/x").contains("127.0.0.1"));
    }

    // FR-PROXY-013 AC-9: a network-path reference is resolved against the target scheme
    @Test
    public void fr_PROXY_013_AC9_networkPathReferenceUsesTheTargetScheme() {
        assertEquals("/api/orders/x", rewrite("http://127.0.0.1:9", "", "//127.0.0.1:9/api/orders/x"));
        assertEquals("/x", rewrite("https://h", "", "//h/x"));
        assertEquals("/x", rewrite("https://h", "", "//h:443/x"));
        assertEquals("//h:80/x", rewrite("https://h", "", "//h:80/x"));
        assertEquals("//other.example/x", rewrite("http://h", "", "//other.example/x"));
        assertEquals("/", rewrite("http://h/base", "", "//h/base"));
    }

    // FR-PROXY-013 AC-10: query and fragment after the base path are kept unchanged
    @Test
    public void fr_PROXY_013_AC10_queryAndFragmentAreKept() {
        assertEquals("/api/orders/x?a=1#f", rewrite("http://h:9", "", "http://h:9/api/orders/x?a=1#f"));
        assertEquals("/p/x?r=http://h:9/y#f", rewrite("http://h:9", "/p", "http://h:9/x?r=http://h:9/y#f"));
        assertEquals("/x?a=%2F&b#f?g", rewrite("http://h/base", "", "http://h/base/x?a=%2F&b#f?g"));
        assertEquals("/?q=/base", rewrite("http://h/base", "", "http://h/base?q=/base"));
        assertEquals("http://h/basex?q", rewrite("http://h/base", "", "http://h/basex?q"));
    }

    private static List<Map.Entry<String, String>> lines(String... nameValuePairs) {
        List<Map.Entry<String, String>> out = new ArrayList<>();
        for (int i = 0; i < nameValuePairs.length; i += 2) {
            out.add(new SimpleImmutableEntry<>(nameValuePairs[i], nameValuePairs[i + 1]));
        }
        return out;
    }

    // FR-PROXY-013 AC-1: every copied Location line is rewritten and put back in order, other lines untouched
    @Test
    public void fr_PROXY_013_AC1_applyRewritesEveryCopiedLocationLineInOrder() {
        RecordingResponse rec = new RecordingResponse(null);

        LocationRewriter.apply(lines("X-Other", "http://h/a", "location", "http://h/a", "Location", "/rel",
                "LOCATION", "//h/b"), TargetUrl.parse("http://h"), "/m", rec);

        assertEquals(Arrays.asList("header(Location, /m/a)", "append(Location, /rel)", "append(Location, /m/b)"),
                rec.headerCalls());
    }

    // FR-PROXY-013 AC-2: when no copied Location line changes, the response is not touched
    @Test
    public void fr_PROXY_013_AC2_applyLeavesTheResponseAloneWhenNothingChanges() {
        RecordingResponse rec = new RecordingResponse(null);

        LocationRewriter.apply(lines("Location", "/x", "Location", "http://other.example/x"),
                TargetUrl.parse("http://h"), "", rec);
        LocationRewriter.apply(lines("X-Other", "http://h/x"), TargetUrl.parse("http://h"), "", rec);

        assertEquals(Collections.emptyList(), rec.headerCalls());
    }
}
