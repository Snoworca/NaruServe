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
        RegistrationListener::class.java,
        WebSocketSession.BinaryListener::class.java,
        WebSocketSession.CloseListener::class.java,
        WebSocketSession.ErrorListener::class.java,
        WebSocketSession.JsonListener::class.java,
        WebSocketSession.TextListener::class.java,
        ErrorRenderer::class.java,
        ExchangeHandler::class.java,
        StreamDrain::class.java,
        UpgradeHandler::class.java,
        com.snoworca.naruserve.utils.TimeSource::class.java,
        com.snoworca.naruserve.utils.auth.AccessRule::class.java,
        com.snoworca.naruserve.utils.auth.audit.AuditSink::class.java,
        com.snoworca.naruserve.utils.auth.rbac.RoleStore::class.java,
        com.snoworca.naruserve.utils.auth.spi.AuthResponses::class.java,
        com.snoworca.naruserve.utils.auth.spi.Authenticator::class.java,
        com.snoworca.naruserve.utils.auth.spi.BreachedPasswordChecker::class.java,
        com.snoworca.naruserve.utils.auth.spi.KeySource::class.java,
        com.snoworca.naruserve.utils.auth.user.UserMutator::class.java,
    ).map { it.name }
}
