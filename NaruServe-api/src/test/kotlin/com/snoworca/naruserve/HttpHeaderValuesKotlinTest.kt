package com.snoworca.naruserve

import com.snoworca.naruserve.common.HttpHeaderValues
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpHeaderValuesKotlinTest {

    private fun check(value: String): Boolean {
        val ok: Boolean = HttpHeaderValues.isFieldValue(value)
        return ok
    }

    @Test
    fun `IR-HTTP-012 AC-5 isFieldValue reads as Boolean without a platform type`() {
        assertTrue(check("text/plain"))
        assertTrue(check("a\tb"))
        assertFalse(check("a\r\nb"))
        assertFalse(check("Ā"))
        val name: Boolean = HttpHeaderValues.isToken("X-Trace")
        assertTrue(name && check("abc"))
    }
}
