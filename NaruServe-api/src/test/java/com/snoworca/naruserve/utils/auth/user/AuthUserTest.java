package com.snoworca.naruserve.utils.auth.user;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;
import org.junit.Test;

/** AuthUser and AuthIds (DR-IAM-001). */
public class AuthUserTest {

    private static AuthUser alice() {
        return AuthUser.builder().id("7").username("alice").passwordHash("$pbkdf2-sha256$secret-hash")
                .tokenVersion(3).enabled(true).roles("editor", "viewer").attribute("email", "a@example.com").build();
    }

    // DR-IAM-001 AC-1: the getters, and the collections they return are unmodifiable
    @Test
    public void DR_IAM_001_AC1_gettersAndUnmodifiableCollections() {
        AuthUser u = alice();
        assertEquals("7", u.getId());
        assertEquals("alice", u.getUsername());
        assertEquals("$pbkdf2-sha256$secret-hash", u.getPasswordHash());
        assertEquals(3L, u.getTokenVersion());
        assertTrue(u.isEnabled());
        assertFalse(u.isServiceAccount());
        assertEquals(new HashSet<>(Arrays.asList("editor", "viewer")), u.getRoles());
        assertEquals(Collections.singletonMap("email", "a@example.com"), u.getAttributes());
        assertEquals("a@example.com", u.getAttribute("email"));
        assertNull(u.getAttribute("phone"));
        try {
            u.getRoles().add("admin");
            fail("roles are modifiable");
        } catch (UnsupportedOperationException expected) {
            // immutable value
        }
        try {
            u.getAttributes().put("x", "y");
            fail("attributes are modifiable");
        } catch (UnsupportedOperationException expected) {
            // immutable value
        }
        AuthUser fresh = AuthUser.builder().username("bob").build();
        assertNull(fresh.getId());
        assertNull(fresh.getPasswordHash());
        assertTrue(fresh.isEnabled());
        assertEquals(0L, fresh.getTokenVersion());
        assertTrue(fresh.getRoles().isEmpty());
        assertTrue(AuthUser.builder().username("svc").serviceAccount(true).build().isServiceAccount());
        assertTrue(Modifier.isFinal(AuthUser.class.getModifiers()));
    }

    // DR-IAM-001 AC-2: no direct-scope field or accessor in v1
    @Test
    public void DR_IAM_001_AC2_noDirectScopes() {
        for (Method m : AuthUser.class.getMethods()) {
            assertFalse(m.getName(), m.getName().toLowerCase(java.util.Locale.ROOT).contains("scope"));
        }
        for (Method m : AuthUser.Builder.class.getMethods()) {
            assertFalse(m.getName(), m.getName().toLowerCase(java.util.Locale.ROOT).contains("scope"));
        }
        for (Field f : AuthUser.class.getDeclaredFields()) {
            assertFalse(f.getName(), f.getName().toLowerCase(java.util.Locale.ROOT).contains("scope"));
        }
    }

    // DR-IAM-001 AC-3 (field half; the Gson half is AuthUserGsonTest in NaruServe-gson): passwordHash is transient
    @Test
    public void DR_IAM_001_AC3_passwordHashFieldIsTransient() throws Exception {
        assertTrue(Modifier.isTransient(AuthUser.class.getDeclaredField("passwordHash").getModifiers()));
    }

    // DR-IAM-001 AC-4: toBuilder round trip and copy-on-change
    @Test
    public void DR_IAM_001_AC4_toBuilderRoundTripAndCopyOnChange() {
        AuthUser original = alice();
        AuthUser same = original.toBuilder().build();
        assertEquals(original, same);
        assertEquals(original.hashCode(), same.hashCode());
        AuthUser changed = original.toBuilder().attribute("email", "b@example.com").build();
        assertEquals("a@example.com", original.getAttribute("email"));
        assertEquals("b@example.com", changed.getAttribute("email"));
        assertNotEquals(original, changed);
        assertNotEquals(original, original.toBuilder().passwordHash(null).build());
        AuthUser removed = original.toBuilder().attribute("email", null).build();
        assertTrue(removed.getAttributes().isEmpty());
        assertEquals(original, alice());
    }

    // DR-IAM-001 AC-5: toString leaves the hash out
    @Test
    public void DR_IAM_001_AC5_toStringOmitsTheHash() {
        String s = alice().toString();
        assertFalse(s, s.contains("secret-hash"));
        assertFalse(s, s.contains("pbkdf2"));
        assertTrue(s, s.contains("alice"));
        assertFalse(s, s.contains("a@example.com"));
        assertTrue(s, s.contains("email"));
    }

    // DR-IAM-001 AC-6: a user needs a username
    @Test
    public void DR_IAM_001_AC6_buildWithoutUsernameThrows() {
        try {
            AuthUser.builder().id("1").build();
            fail("built without a username");
        } catch (IllegalStateException expected) {
            // username is required
        }
    }

    // DR-IAM-001 AC-8: AuthIds maps numeric and UUID store keys; a non-parsing id is null, never an exception
    @Test
    public void DR_IAM_001_AC8_authIdsParseLongAndUuid() {
        assertEquals(Long.valueOf(7), AuthIds.parseLong("7"));
        assertEquals(Long.valueOf(0), AuthIds.parseLong("0"));
        assertEquals(Long.valueOf(Long.MAX_VALUE), AuthIds.parseLong(String.valueOf(Long.MAX_VALUE)));
        for (String bad : new String[] {"abc", "007", "+7", " 7", "7 ", "-7", "", "9223372036854775808", "7a"}) {
            assertNull(bad, AuthIds.parseLong(bad));
        }
        UUID u = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        assertEquals(u, AuthIds.parseUuid("123e4567-e89b-12d3-a456-426614174000"));
        for (String bad : new String[] {"abc", "123E4567-E89B-12D3-A456-426614174000", "123e4567e89b12d3a456426614174000",
                " 123e4567-e89b-12d3-a456-426614174000", "123e4567-e89b-12d3-a456-42661417400", ""}) {
            assertNull(bad, AuthIds.parseUuid(bad));
        }
        assertEquals("7", String.valueOf(AuthIds.parseLong("7")));
        assertEquals("123e4567-e89b-12d3-a456-426614174000", AuthIds.parseUuid(u.toString()).toString());
    }

    // DR-IAM-001: attributes and roles are copied at build, so the builder's later changes do not leak
    @Test
    public void builderCopiesItsCollections() {
        AuthUser.Builder b = AuthUser.builder().username("c").roles(Arrays.asList("a"));
        AuthUser first = b.build();
        b.roles("b").attribute("k", "v");
        Map<String, String> attrs = first.getAttributes();
        assertTrue(attrs.isEmpty());
        assertEquals(Collections.singleton("a"), first.getRoles());
        assertEquals(Collections.singleton("b"), b.build().getRoles());
    }
}
