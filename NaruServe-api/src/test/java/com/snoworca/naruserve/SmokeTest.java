package com.snoworca.naruserve;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class SmokeTest {

    // SDS-AC-10; OPS-BUILD-002 AC-3
    @Test
    public void sdsAc10_OPS_BUILD_002_AC3_javaSmokeTestRunsInTheBuild() {
        assertEquals("ok", NullabilityFixture.annotatedNonNull());
        assertNull(NullabilityFixture.annotatedNullable());
    }
}
