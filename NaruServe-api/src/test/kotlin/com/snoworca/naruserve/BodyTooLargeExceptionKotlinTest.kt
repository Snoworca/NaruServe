package com.snoworca.naruserve

import com.snoworca.naruserve.internal.LimitedInputStream
import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BodyTooLargeExceptionKotlinTest {

    @Test
    fun `IR-HTTP-011 AC-5 catch BodyTooLargeException and read limit as Long`() {
        var caught: Long = -1
        try {
            throw BodyTooLargeException(10)
        } catch (e: BodyTooLargeException) {
            val limit: Long = e.limit()
            val asIo: IOException = e
            val message: String? = asIo.message
            assertTrue(message.orEmpty().contains("10"))
            caught = limit
        }
        assertEquals(10L, caught)
    }

    @Test
    fun `IR-HTTP-011 AC-5 an overrun of the limited stream is caught by the public type`() {
        val stream = LimitedInputStream(ByteArrayInputStream(ByteArray(11)), 10, -1)
        try {
            stream.readBytes()
            fail("expected BodyTooLargeException")
        } catch (e: BodyTooLargeException) {
            val limit: Long = e.limit()
            assertEquals(10L, limit)
        }
    }
}
