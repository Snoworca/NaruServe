package com.snoworca.naruserve;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

/** Public method names of the api jar avoid the Kotlin hard keywords (FR-KT-002). */
public class KotlinKeywordsTest {

    // SDS-AC-6; FR-KT-002 AC-1, FR-KT-002 AC-3
    @Test
    public void sdsAc6_FR_KT_002_AC1_AC3_realJarHasNoKeywordNamedMethod() {
        assertEquals(Collections.emptyList(), KotlinContractChecks.keywordFailures(ApiJarClasses.publicTypes()));
    }

    // SDS-AC-7; FR-KT-002 AC-2
    @Test
    public void sdsAc7_FR_KT_002_AC2_keywordsFixtureFailsOncePerWordAndSoftNamesPass() {
        assertEquals(Arrays.asList("Keywords.as", "Keywords.fun", "Keywords.in", "Keywords.is", "Keywords.object",
                        "Keywords.typealias", "Keywords.typeof", "Keywords.val", "Keywords.var", "Keywords.when"),
                KotlinContractChecks.keywordFailures(
                        Collections.<Class<?>>singletonList(KotlinContractFixtures.Keywords.class)));
        assertEquals(Collections.emptyList(), KotlinContractChecks.keywordFailures(
                Collections.<Class<?>>singletonList(KotlinContractFixtures.SoftNames.class)));
    }
}
