package com.snoworca.naruserve

import com.snoworca.naruserve.internal.DefaultRestApp
import com.snoworca.naruserve.testing.TestApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class RawBodyLimitKotlinTest {

    // IR-APP-009 AC-6: rawBodyLimit chains into jsonLimit from Kotlin and returns the receiver; that the result is
    // RestApp rather than the platform type RestApp! rests on the package @NullMarked scope, which
    // RawBodyLimitTest IR_APP_009_AC6_rawBodyLimitCarriesNoNullnessEscape pins.
    @Test
    fun `IR-APP-009 AC-6 Kotlin chains rawBodyLimit and jsonLimit without a platform type`() {
        val app: RestApp = TestApp.create().app()
        val chained: RestApp = app.rawBodyLimit(512L shl 20).jsonLimit(1024L)
        assertSame(app, chained)
        val impl = app as DefaultRestApp
        assertEquals(536870912L, impl.rawBodyLimit())
        assertEquals(1024L, impl.jsonLimit())
    }
}
