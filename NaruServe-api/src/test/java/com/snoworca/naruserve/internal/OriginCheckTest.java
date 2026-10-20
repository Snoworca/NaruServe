package com.snoworca.naruserve.internal;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

/** Origin normalisation and admission. SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-7-3.sds.md. */
public class OriginCheckTest {

    private final OriginCheck sameOnly = new OriginCheck(Collections.<String>emptyList());

    // SDS-AC-7; FR-WS-003 AC-3
    @Test
    public void sdsAc7_FR_WS_003_AC3_sameOriginComparesNormalisedSchemeHostAndPort() {
        assertTrue(sameOnly.admits(null, "example.test", false));
        assertTrue(sameOnly.admits(null, null, false));
        assertTrue(sameOnly.admits("HTTP://Example.Test", "example.test:80", false));
        assertTrue(sameOnly.admits("http://example.test:80", "EXAMPLE.test", false));
        assertTrue(sameOnly.admits("https://example.test", "example.test:443", true));
        assertTrue(sameOnly.admits("https://example.test:8443", "example.test:8443", true));
        assertFalse(sameOnly.admits("https://example.test", "example.test", false));
        assertFalse(sameOnly.admits("http://example.test", "example.test", true));
        assertFalse(sameOnly.admits("http://example.test:8081", "example.test:8080", false));
        assertTrue(sameOnly.admits("http://[::1]:8080", "[::1]:8080", false));
        assertTrue(sameOnly.admits("http://[::1]", "[::1]", false));
        assertFalse(sameOnly.admits("http://[::1]:8081", "[::1]:8080", false));
    }

    // SDS-AC-7; FR-WS-003 AC-3
    @Test
    public void sdsAc7_FR_WS_003_AC3_unparseableOriginOrMissingHostIsNotSameOrigin() {
        assertFalse(sameOnly.admits("http://example.test", null, false));
        assertFalse(sameOnly.admits("example.test", "example.test", false));
        assertFalse(sameOnly.admits("http://", "example.test", false));
        assertFalse(sameOnly.admits("http://example.test:x", "example.test", false));
        assertFalse(sameOnly.admits("http://example.test:", "example.test", false));
        assertFalse(sameOnly.admits("http://example.test/path", "example.test", false));
        assertFalse(sameOnly.admits("ftp://example.test", "example.test", false));
        assertFalse(sameOnly.admits("http://[::1", "[::1]", false));
        assertFalse(sameOnly.admits("http://example.test", "example.test:x", false));
    }

    // SDS-AC-8; FR-WS-003 AC-4, FR-WS-003 AC-6
    @Test
    public void sdsAc8_FR_WS_003_AC4_AC6_literalNullIsAdmittedOnlyWithAStar() {
        assertFalse(sameOnly.admits("null", "example.test", false));
        assertFalse(new OriginCheck(Arrays.asList("https://app.example.com")).admits("null", "example.test", false));
        assertTrue(new OriginCheck(Arrays.asList("*")).admits("null", "example.test", false));
        assertTrue(new OriginCheck(Arrays.asList("https://app.example.com", "*")).admits("null", "x", false));
    }

    // SDS-AC-9; FR-WS-003 AC-5, FR-WS-003 AC-6
    @Test
    public void sdsAc9_FR_WS_003_AC5_AC6_allowlistEntriesAreNormalisedAndStarAdmitsAll() {
        OriginCheck listed = new OriginCheck(Arrays.asList("HTTPS://App.Example.com"));
        assertTrue(listed.admits("https://APP.example.com:443", "api.example.com", false));
        assertTrue(listed.admits("https://app.example.com", "api.example.com", false));
        assertFalse(listed.admits("http://app.example.com", "api.example.com", false));
        assertFalse(listed.admits("https://other.example.com", "api.example.com", false));
        assertTrue(listed.admits("http://api.example.com", "api.example.com", false));
        OriginCheck star = new OriginCheck(Arrays.asList("https://app.example.com", "*"));
        assertTrue(star.admits("http://evil.test", "example.test", false));
        assertTrue(star.admits("garbage", "example.test", false));
    }
}
