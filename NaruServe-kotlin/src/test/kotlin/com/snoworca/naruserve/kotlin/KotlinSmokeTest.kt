package com.snoworca.naruserve.kotlin

import org.junit.Assert.assertEquals
import org.junit.Test

class KotlinSmokeTest {

    @Test
    fun `SDS-AC-9 SDS-AC-10 OPS-BUILD-002 AC-2 AC-3 Kotlin smoke test runs in the build`() {
        assertEquals("NaruServe-kotlin", KotlinModule.NAME)
    }
}
