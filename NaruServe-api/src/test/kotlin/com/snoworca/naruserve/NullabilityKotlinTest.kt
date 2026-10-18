package com.snoworca.naruserve

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NullabilityKotlinTest {

    @Test
    fun `SDS-AC-8 CON-BUILD-011 AC-2 NonNull method assigns to String`() {
        val t: String = NullabilityFixture.annotatedNonNull()
        assertEquals("ok", t)
    }

    @Test
    fun `CON-BUILD-011 AC-2 Nullable method is seen as nullable String`() {
        val s: String? = NullabilityFixture.annotatedNullable()
        assertNull(s)
    }
}
