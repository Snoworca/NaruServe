package com.snoworca.naruserve

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MethodKotlinTest {

    private val hardKeywords = setOf(
        "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface",
        "is", "null", "object", "package", "return", "super", "this", "throw", "true", "try", "typealias",
        "typeof", "val", "var", "when", "while",
    )

    @Test
    fun `SDS-AC-8 IR-ROUTE-003 AC-3 combinators, in and for loop without backticks`() {
        val set: MethodSet = Method.get().post().except(Method.POST)
        assertTrue(Method.GET in set)
        assertFalse(Method.POST in set)
        val seen = mutableListOf<Method>()
        for (m in Method.of(Method.DELETE, Method.GET)) {
            seen.add(m)
        }
        assertEquals(listOf(Method.GET, Method.DELETE), seen)
        val n: Int = set.size()
        assertEquals(1, n)
    }

    @Test
    fun `SDS-AC-8 IR-ROUTE-003 AC-3 no public method name is a Kotlin hard keyword`() {
        val names = (Method::class.java.methods + MethodSet::class.java.methods).map { it.name }
        assertTrue(names.filter { it in hardKeywords }.toString(), names.none { it in hardKeywords })
    }
}
