package com.snoworca.naruserve.utils.auth

import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.JUnitCore
import org.junit.runner.Request

/**
 * R2-T2-F04: the Kotlin tests that start a TestApp and an Auth stop them before they return, so they leave no
 * framework thread (the adapter, websocket, auth cleanup scheduler and hash pools) running for the rest of the test
 * JVM. Each test runs here on its own and the framework threads it started must be gone within a generous deadline.
 */
class AuthKotlinTestsStopKotlinTest {

    @Test
    fun `token service, lockout and websocket wrapper test stops what it started`() {
        assertStopsItsThreads(AuthWave6KotlinTest::class.java, "token service, lockout and websocket wrapper from Kotlin")
    }

    @Test
    fun `docs auth csrf guarded route test stops what it started`() {
        assertStopsItsThreads(AuthWave7KotlinTest::class.java, "docs auth csrf guarded route")
    }

    @Test
    fun `docs auth cors before install test stops what it started`() {
        assertStopsItsThreads(AuthWave7KotlinTest::class.java, "docs auth cors before install")
    }

    private fun assertStopsItsThreads(cls: Class<*>, method: String) {
        val before = frameworkThreads()
        val result = JUnitCore().run(Request.method(cls, method))
        assertTrue("$method: ${result.failures}", result.wasSuccessful() && result.runCount == 1)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        var left = frameworkThreads() - before
        while (left.isNotEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(10)
            left = frameworkThreads() - before
        }
        assertTrue("$method left live threads: ${left.map { it.name }.sorted()}", left.isEmpty())
    }

    private fun frameworkThreads(): Set<Thread> = Thread.getAllStackTraces().keys
        .filter { it.isAlive && (it.name.startsWith("naru") || it.name.startsWith("in-memory-http")) }
        .toSet()
}
