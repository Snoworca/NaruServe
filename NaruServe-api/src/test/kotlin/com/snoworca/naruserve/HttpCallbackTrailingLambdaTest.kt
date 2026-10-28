package com.snoworca.naruserve

import com.snoworca.naruserve.testing.TestApp
import org.junit.Assert.assertEquals
import org.junit.Test

/** Compile and execute idiomatic three-argument trailing lambdas for the shared app/router/builder surface. */
class HttpCallbackTrailingLambdaTest {
    // FR-KT-003 AC-3; FR-APP-021 AC-3; FR-HTTP-028 AC-1
    @Test fun `all app convenience verbs accept a trailing continuation lambda`() {
        val t = TestApp.create()
        val app = t.app()
        app.get("/get") { _, response, _ -> response.send("GET") }
        app.post("/post") { _, response, _ -> response.send("POST") }
        app.put("/put") { _, response, _ -> response.send("PUT") }
        app.patch("/patch") { _, response, _ -> response.send("PATCH") }
        app.delete("/delete") { _, response, _ -> response.send("DELETE") }
        app.head("/head") { _, response, _ -> response.header("X-Verb", "HEAD").end() }
        app.options("/options") { _, response, _ -> response.send("OPTIONS") }
        for (verb in listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")) {
            assertEquals(verb, t.send(verb, "/" + verb.lowercase()).text())
        }
        assertEquals("HEAD", t.send("HEAD", "/head").header("X-Verb"))
    }
    // FR-KT-003 AC-3; FR-APP-021 AC-2 AC-3; FR-HTTP-028 AC-1
    @Test fun `all builder verbs and extended method accept trailing continuation lambdas`() {
        val t = TestApp.create()
        val app = t.app()
        app.route("/get").get { _, response, _ -> response.send("GET") }
        app.route("/post").post { _, response, _ -> response.send("POST") }
        app.route("/put").put { _, response, _ -> response.send("PUT") }
        app.route("/patch").patch { _, response, _ -> response.send("PATCH") }
        app.route("/delete").delete { _, response, _ -> response.send("DELETE") }
        app.route("/head").head { _, response, _ -> response.header("X-Verb", "HEAD").end() }
        app.route("/options").options { _, response, _ -> response.send("OPTIONS") }
        app.route("/search").method("M-SEARCH") { request, response, _ -> response.send(request.methodToken()) }
        for (verb in listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")) {
            assertEquals(verb, t.send(verb, "/" + verb.lowercase()).text())
        }
        assertEquals("HEAD", t.send("HEAD", "/head").header("X-Verb"))
        assertEquals("M-SEARCH", t.send("M-SEARCH", "/search").text())
    }
    // FR-KT-003 AC-3; FR-APP-021 AC-3; FR-ROUTE-019 AC-5; FR-ROUTE-020 AC-1
    @Test fun `router alternatives group builder and continuation order remain available without explicit SAM casts`() {
        val t = TestApp.create()
        val app = t.app()
        val trace = ArrayList<String>()
        val child = Router.create()
        child.get(arrayOf("/one", "/two")) { _, _, next -> trace += "child"; next.run() }
        child.get("/one") { _, response -> response.send("one") }
        child.get("/two") { _, response -> response.send("two") }
        app.use("/api", child)
        app.method("COPY", arrayOf("/copy", "/copy2")) { _, response, _ -> response.send("copy") }
        app.group("/group") { group -> group.route("/x").get { _, response, _ -> response.send("group") } }
        assertEquals("one", t.send("GET", "/api/one").text())
        assertEquals("two", t.send("GET", "/api/two").text())
        assertEquals(listOf("child", "child"), trace)
        assertEquals("copy", t.send("COPY", "/copy").text())
        assertEquals("copy", t.send("COPY", "/copy2").text())
        assertEquals("group", t.send("GET", "/group/x").text())
    }
}
