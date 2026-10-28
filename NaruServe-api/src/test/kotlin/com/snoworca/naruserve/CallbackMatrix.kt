package com.snoworca.naruserve

import com.snoworca.naruserve.spi.ErrorRenderer
import com.snoworca.naruserve.spi.ExchangeHandler
import com.snoworca.naruserve.spi.StreamDrain
import com.snoworca.naruserve.spi.UpgradeHandler

/**
 * The callback matrix: binary names of every callback interface that CallbackMatrixKotlinTest passes a lambda to.
 * CallbackInventoryTest compares it with the reflection inventory of the api jar.
 */
// @req FR-KT-003
object CallbackMatrix {
    @JvmField
    val CALLBACK_TYPES: List<String> = listOf(
        AsyncRouteHandler::class.java,
        Deferred.FailureHandler::class.java,
        ErrorMiddleware::class.java,
        ParamHandler::class.java,
        Configurer::class.java,
        ErrorHandler::class.java,
        LifecycleCallback::class.java,
        Middleware::class.java,
        RouteHandler::class.java,
        SseClient.CloseListener::class.java,
        WebSocketHandler::class.java,
        WebSocketSession.BinaryListener::class.java,
        WebSocketSession.CloseListener::class.java,
        WebSocketSession.ErrorListener::class.java,
        WebSocketSession.JsonListener::class.java,
        WebSocketSession.TextListener::class.java,
        ErrorRenderer::class.java,
        ExchangeHandler::class.java,
        StreamDrain::class.java,
        UpgradeHandler::class.java,
    ).map { it.name }
}
