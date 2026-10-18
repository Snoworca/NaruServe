package com.snoworca.naruserve.routing;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PathValidatorTest {

    // SDS-AC-6; FR-ROUTE-004 AC-1
    @Test
    public void sdsAc6_FR_ROUTE_004_AC1_rejectList() {
        String[] reject = {"/a%2", "/a%zz", "/a%C3", "/a%FF", "/café", "/a%2Fb", "/a%2fb", "/a%00", "/a\u0000b",
            "/./a", "/a/..", "/a/%2e", "/a/%2E%2e", "/a/.%2e/b", "/a/%2e%2E?x=1", "/a%C0%AF", "/a%ED%A0%80",
            "/..", "/.", "/%2e/", "", "users", "?q=1", "/a%٣٣"};
        for (String p : reject) {
            assertFalse(p, PathValidator.isValid(p));
        }
    }

    // SDS-AC-6; FR-ROUTE-004 AC-3
    @Test
    public void sdsAc6_FR_ROUTE_004_AC3_acceptListWithAndWithoutQuery() {
        String[] accept = {"/users/42", "/files/%252e", "/caf%C3%A9", "/a/.hidden", "/", "/a+b"};
        for (String p : accept) {
            assertTrue(p, PathValidator.isValid(p));
            assertTrue(p + "?q=1", PathValidator.isValid(p + "?q=1"));
            assertTrue(p + "?a=%zz# x", PathValidator.isValid(p + "?a=%zz# x"));
        }
        assertFalse(PathValidator.isValid("/a%2Fb?q=1"));
    }

    // FR-ROUTE-004 AC-4
    @Test
    public void FR_ROUTE_004_AC4_validatorReturnsOnlyADecisionNeverADecodedPath() {
        for (java.lang.reflect.Method m : PathValidator.class.getMethods()) {
            if (m.getDeclaringClass() == PathValidator.class) {
                assertTrue(m.getName(), m.getName().equals("isValid") && m.getReturnType() == boolean.class);
            }
        }
    }

    // SDS-AC-7; FR-ROUTE-004 AC-2
    @Test
    public void sdsAc7_FR_ROUTE_004_AC2_rawSpaceControlDelHashAndNonAsciiRejected() {
        String[] reject = {"/a b", "/a\u0001", "/a\u001f", "/a\u007f", "/a#b", "/café", "/a\t"};
        for (String p : reject) {
            assertFalse(p, PathValidator.isValid(p));
        }
    }
}
