package com.snoworca.naruserve;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.NullMarked;
import org.junit.Test;

/** The nullability contract of the public API packages, read from the main jar (FR-KT-001). */
public class KotlinNullabilityTest {

    private static Class<?> jarRequest() throws ClassNotFoundException {
        return Class.forName("com.snoworca.naruserve.Request", false, ApiJarClasses.loader());
    }

    // SDS-AC-1; FR-KT-001 AC-1
    @Test
    public void sdsAc1_FR_KT_001_AC1_publicPackagesAreNullMarkedAndInternalKeepsIt() {
        assertEquals(Collections.emptyList(),
                KotlinContractChecks.nullMarkedFailures(ApiJarClasses.loader(), ApiJarClasses.PUBLIC_PACKAGES));
        Class<?> internal = ApiJarClasses.packageInfo("com.snoworca.naruserve.internal");
        assertNotNull(internal);
        assertTrue(internal.isAnnotationPresent(NullMarked.class));
        assertEquals(Collections.singletonList("package " + KotlinContractFixtures.BARE_PACKAGE + " lacks @NullMarked"),
                KotlinContractChecks.nullMarkedFailures(KotlinNullabilityTest.class.getClassLoader(),
                        Collections.singletonList(KotlinContractFixtures.BARE_PACKAGE)));
    }

    // SDS-AC-2; FR-KT-001 AC-2
    @Test
    public void sdsAc2_FR_KT_001_AC2_accessorTableMatchesRequestReturnAnnotations() throws Exception {
        Map<String, Boolean> table = KotlinAccessorTable.rows();
        assertEquals(Boolean.TRUE, table.get("query"));
        assertEquals(Boolean.TRUE, table.get("attribute"));
        assertEquals(Boolean.FALSE, table.get("param"));
        assertEquals(Boolean.FALSE, table.get("service"));
        List<Class<?>> request = Collections.<Class<?>>singletonList(jarRequest());
        assertEquals(Collections.emptyList(), KotlinContractChecks.accessorFailures(request, table));

        Map<String, Boolean> flipped = new HashMap<>(table);
        flipped.put("param", Boolean.TRUE);
        flipped.put("query", Boolean.FALSE);
        assertEquals(Arrays.asList("Request.param", "Request.query"),
                KotlinContractChecks.accessorFailures(request, flipped));
    }

    // SDS-AC-3; FR-KT-001 AC-3
    @Test
    public void sdsAc3_FR_KT_001_AC3_jarTypesCarryNoNonNullAndTheTestPathIsNotScanned() {
        List<Class<?>> types = ApiJarClasses.publicTypes();
        assertEquals(Collections.emptyList(), KotlinContractChecks.nonNullFailures(types));
        for (Class<?> type : types) {
            assertSame(type.getName(), ApiJarClasses.loader(), type.getClassLoader());
            assertFalse(type.getName(), type.getName().endsWith("NullabilityFixture"));
        }
        // The test-path fixture does carry one, so the check would see it there.
        assertEquals(Collections.singletonList("NullabilityFixture.annotatedNonNull"),
                KotlinContractChecks.nonNullFailures(Collections.<Class<?>>singletonList(NullabilityFixture.class)));
    }

    // SDS-AC-4; FR-KT-001 AC-4
    @Test
    public void sdsAc4_FR_KT_001_AC4_eachFixtureFailsByName() {
        assertEquals(Collections.singletonList("package " + KotlinContractFixtures.BARE_PACKAGE + " lacks @NullMarked"),
                KotlinContractChecks.nullMarkedFailures(KotlinNullabilityTest.class.getClassLoader(),
                        Collections.singletonList(KotlinContractFixtures.BARE_PACKAGE)));
        assertEquals(Collections.singletonList("Unannotated.query"), KotlinContractChecks.accessorFailures(
                Collections.<Class<?>>singletonList(KotlinContractFixtures.Unannotated.class), KotlinAccessorTable.rows()));
        assertEquals(Collections.singletonList("NonNullMember.name"), KotlinContractChecks.nonNullFailures(
                Collections.<Class<?>>singletonList(KotlinContractFixtures.NonNullMember.class)));
    }

    // SDS-AC-5; FR-KT-001 AC-5
    @Test
    public void sdsAc5_FR_KT_001_AC5_publicTypesIncludeNestedTypesAndExcludeInternal() {
        boolean request = false;
        boolean nested = false;
        for (Class<?> type : ApiJarClasses.publicTypes()) {
            assertFalse(type.getName(), type.getName().startsWith("com.snoworca.naruserve.internal."));
            request |= type.getName().equals("com.snoworca.naruserve.Request");
            nested |= type.getName().equals("com.snoworca.naruserve.WebSocketSession$TextListener");
        }
        assertTrue("Request enumerated", request);
        assertTrue("nested WebSocketSession.TextListener enumerated", nested);
    }
}
