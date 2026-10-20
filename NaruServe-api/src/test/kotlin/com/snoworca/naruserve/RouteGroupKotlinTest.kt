package com.snoworca.naruserve

import com.snoworca.naruserve.testing.TestApp
import org.junit.Assert.assertEquals
import org.junit.Test

class RouteGroupKotlinTest {
    // FR-DX-009 AC-1, AC-2, AC-3: ordinary SAM lambdas and chained route middleware.
    @Test fun nestedGroupsUseKotlinLambdasAndPreserveMiddlewareOrder() {
        val t = TestApp.create()
        val calls = mutableListOf<String>()
        t.app().group("/api") { api ->
            api.group("/v1") { v1 ->
                v1.route("/users").use { _, _, next ->
                    calls.add("route")
                    next.run()
                }.get { _, res -> res.send("users") }
            }
            api.use { _, _, next ->
                calls.add("auth")
                next.run()
            }
        }
        assertEquals("users", t.send("GET", "/api/v1/users").text())
        assertEquals(listOf("auth", "route"), calls)
    }
}
