package com.snoworca.naruserve.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/** The identity-deduplicated close list. SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-4-6.sds.md. */
public class OwnedResourcesTest {

    private final List<String> closed = Collections.synchronizedList(new ArrayList<String>());

    private AutoCloseable resource(String name) {
        return () -> closed.add(name);
    }

    // SDS-AC-10; FR-APP-008 AC-1
    @Test
    public void sdsAc10_FR_APP_008_AC1_ownDbThenRegisterUsersClosesUsersThenDb() {
        OwnedResources owned = new OwnedResources();
        owned.add(resource("db"));
        owned.add(resource("users"));
        assertTrue(owned.closeAll(null).isEmpty());
        assertEquals(Arrays.asList("users", "db"), closed);
    }

    // SDS-AC-11; FR-APP-008 AC-2
    @Test
    public void sdsAc11_FR_APP_008_AC2_oneObjectAddedThreeTimesClosesOnceAtItsFirstPosition() {
        OwnedResources owned = new OwnedResources();
        AutoCloseable shared = resource("shared");
        owned.add(resource("a"));
        owned.add(shared);
        owned.add(resource("b"));
        owned.add(shared);
        owned.add(shared);
        owned.closeAll(null);
        assertEquals(Arrays.asList("b", "shared", "a"), closed);
    }

    // SDS-AC-11; FR-APP-008 AC-2
    @Test
    public void sdsAc11_FR_APP_008_AC2_equalButDistinctObjectsAreBothClosed() {
        OwnedResources owned = new OwnedResources();
        // equals() is true for every pair, identity is not.
        class Equal implements AutoCloseable {
            final String name;

            Equal(String name) {
                this.name = name;
            }

            @Override
            public void close() {
                closed.add(name);
            }

            @Override
            public boolean equals(Object o) {
                return o instanceof Equal;
            }

            @Override
            public int hashCode() {
                return 1;
            }
        }
        owned.add(new Equal("x"));
        owned.add(new Equal("y"));
        owned.closeAll(null);
        assertEquals(Arrays.asList("y", "x"), closed);
    }

    // SDS-AC-12; FR-APP-008 AC-3
    @Test
    public void sdsAc12_FR_APP_008_AC3_secondOfThreeThrowsAndTheOthersAreStillClosed() {
        OwnedResources owned = new OwnedResources();
        Exception boom = new Exception("boom");
        owned.add(resource("1"));
        owned.add(() -> {
            closed.add("2");
            throw boom;
        });
        owned.add(resource("3"));
        List<Exception> failures = owned.closeAll(null);
        assertEquals(Arrays.asList("3", "2", "1"), closed);
        assertEquals(1, failures.size());
        assertSame(boom, failures.get(0));
    }

    // SDS-AC-10; FR-APP-008 AC-1
    @Test
    public void sdsAc10_FR_APP_008_AC1_secondCloseAllClosesNothingAndSkipIsNeverClosed() {
        OwnedResources owned = new OwnedResources();
        AutoCloseable logger = resource("logger");
        owned.add(resource("a"));
        owned.add(logger);
        owned.closeAll(logger);
        assertTrue(owned.closeAll(null).isEmpty());
        assertEquals(Arrays.asList("a"), closed);
    }

    // review P2-001; FR-APP-008 AC-3: an Error from one close() does not stop the closes of the rest; it is rethrown
    // once every resource was closed, with the Exception failures attached
    @Test
    public void review_P2_001_FR_APP_008_AC3_errorFromACloseStillClosesTheRestThenIsRethrown() {
        OwnedResources owned = new OwnedResources();
        Exception boom = new Exception("boom");
        AssertionError fatal = new AssertionError("fatal");
        owned.add(resource("1"));
        owned.add(() -> {
            closed.add("2");
            throw fatal;
        });
        owned.add(() -> {
            closed.add("3");
            throw boom;
        });
        try {
            owned.closeAll(null);
            org.junit.Assert.fail("the Error must be rethrown after every close ran");
        } catch (AssertionError e) {
            assertSame(fatal, e);
            assertEquals(Arrays.asList(boom), Arrays.asList(e.getSuppressed()));
        }
        assertEquals(Arrays.asList("3", "2", "1"), closed);
        assertTrue(owned.closeAll(null).isEmpty());
    }
}
