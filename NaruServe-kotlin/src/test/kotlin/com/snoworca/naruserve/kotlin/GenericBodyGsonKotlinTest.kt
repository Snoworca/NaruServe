package com.snoworca.naruserve.kotlin

import com.snoworca.naruserve.gson.GsonJsonCodec
import com.snoworca.naruserve.testing.InMemoryHttpAdapter
import com.snoworca.naruserve.testing.TestApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real JSON decoding through HTTP, including access to DTO members after decoding. */
class GenericBodyGsonKotlinTest {
    class User {
        var name: String? = null
    }

    // IR-KT-002 AC-3: actual Gson preserves the requested DTO element types.
    @Test
    fun `FR-DX-006 AC-1 List User decodes actual DTO elements`() {
        val adapter = InMemoryHttpAdapter()
        val testApp = TestApp.create(adapter)
        testApp.app().jsonCodec(GsonJsonCodec())
        var actual: Any? = null
        testApp.app().post("/users") { req, res ->
            val users = req.body<List<User>>()
            actual = (users as List<*>).single()
            res.send(users.single().name ?: "missing")
        }
        testApp.start()
        try {
            val response = adapter.send(InMemoryHttpAdapter.ClientRequest.json("POST", "/users", "[{\"name\":\"Ada\"}]"))
            assertEquals(response.text(), 200, response.status())
            assertEquals("Ada", response.text())
            assertTrue(actual is User)
        } finally {
            testApp.app().shutdown(0, java.util.concurrent.TimeUnit.SECONDS)
        }
    }

    // IR-KT-002 AC-3: actual Gson preserves the requested DTO element types.
    @Test
    fun `FR-DX-006 AC-1 nested Map String List User decodes actual DTO elements`() {
        val adapter = InMemoryHttpAdapter()
        val testApp = TestApp.create(adapter)
        testApp.app().jsonCodec(GsonJsonCodec())
        var actual: Any? = null
        testApp.app().post("/groups") { req, res ->
            val groups = req.body<Map<String, List<User>>>()
            actual = (groups["team"] as List<*>).single()
            res.send(groups.getValue("team").single().name ?: "missing")
        }
        testApp.start()
        try {
            val response = adapter.send(InMemoryHttpAdapter.ClientRequest.json("POST", "/groups", "{\"team\":[{\"name\":\"Grace\"}]}"))
            assertEquals(response.text(), 200, response.status())
            assertEquals("Grace", response.text())
            assertTrue(actual is User)
        } finally {
            testApp.app().shutdown(0, java.util.concurrent.TimeUnit.SECONDS)
        }
    }

    // IR-KT-002 AC-3: actual Gson preserves the requested DTO element types.
    @Test
    fun `FR-DX-006 AC-2 generic JSON null remains BAD_REQUEST`() {
        val adapter = InMemoryHttpAdapter()
        val testApp = TestApp.create(adapter)
        testApp.app().jsonCodec(GsonJsonCodec())
        testApp.app().post("/users") { req, res ->
            res.json(req.body<List<User>>())
        }
        testApp.start()
        try {
            val response = adapter.send(InMemoryHttpAdapter.ClientRequest.json("POST", "/users", "null"))
            assertEquals(400, response.status())
            assertTrue(response.text().contains("\"code\":\"BAD_REQUEST\""))
        } finally {
            testApp.app().shutdown(0, java.util.concurrent.TimeUnit.SECONDS)
        }
    }
}
