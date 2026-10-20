package com.snoworca.naruserve

import com.snoworca.naruserve.testing.TestApp
import org.junit.Assert.assertEquals
import org.junit.Test

/** One call per public type, written without a backtick, run over the in-memory harness (FR-KT-002 AC-3). */
class KotlinSurfaceKotlinTest {

    // SDS-AC-8; FR-KT-002 AC-3
    @Test
    fun `FR-KT-002 AC-3 RestApp Request Response RouteBuilder and WebSocketSession need no backtick`() {
        val t = TestApp.create()
        val app: RestApp = t.app().get("/users/:id") { req, res ->
            val id: String = req.param("id")
            res.status(201).send(id)
        }
        val route: RouteBuilder = app.route("/items").post { _, res -> res.status(202).send("posted") }
        route.get { _, res -> res.send("listed") }
        app.ws("/ws") { _, ws -> ws.onText { m -> ws.sendText(m) } }

        val user = t.send("GET", "/users/7")
        assertEquals(201, user.status())
        assertEquals("7", user.text())
        assertEquals(202, t.send("POST", "/items").status())
        assertEquals("listed", t.send("GET", "/items").text())
    }
}
