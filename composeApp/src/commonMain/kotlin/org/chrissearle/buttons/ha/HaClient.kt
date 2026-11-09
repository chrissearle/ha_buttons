package org.chrissearle.buttons.ha

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.chrissearle.buttons.config.HaConfig
import kotlin.time.Duration.Companion.seconds

sealed interface ConnectionState {
    data object Connecting : ConnectionState
    data object Connected : ConnectionState
    data class Disconnected(val reason: String?, val retryInSeconds: Int) : ConnectionState

    /** Authentication was rejected - retrying will not help until the config is fixed. */
    data class AuthFailed(val message: String) : ConnectionState
}

class HaException(message: String) : Exception(message)

private class AuthRejected(message: String) : Exception(message)

/**
 * Minimal Home Assistant WebSocket client.
 *
 * Keeps a live map of the states of [entityIds] and lets callers toggle entities
 * and call actions. Reconnects automatically with exponential backoff.
 */
class HaClient(
    private val config: HaConfig,
    private val entityIds: Set<String>,
    private val httpClient: HttpClient,
) {
    private val _states = MutableStateFlow<Map<String, EntityState>>(emptyMap())
    val states: StateFlow<Map<String, EntityState>> = _states.asStateFlow()

    private val _connection = MutableStateFlow<ConnectionState>(ConnectionState.Connecting)
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    /** Guards [session] and [nextId], and serialises sends. */
    private val lock = Mutex()
    private val pendingLock = Mutex()
    private var session: DefaultClientWebSocketSession? = null
    private var nextId = 0
    private val pending = mutableMapOf<Int, CompletableDeferred<Incoming.Result>>()

    fun start(scope: CoroutineScope): Job = scope.launch {
        var backoffMs = INITIAL_BACKOFF_MS
        while (isActive) {
            _connection.value = ConnectionState.Connecting
            var reason: String? = "Connection closed"
            try {
                httpClient.webSocket(urlString = config.webSocketUrl) {
                    runSession(this) { backoffMs = INITIAL_BACKOFF_MS }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AuthRejected) {
                _connection.value = ConnectionState.AuthFailed(e.message ?: "Authentication failed")
                return@launch
            } catch (e: Exception) {
                reason = e.message ?: e::class.simpleName
            } finally {
                closeSession()
            }

            _connection.value = ConnectionState.Disconnected(reason, (backoffMs / 1000).toInt())
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    /** Toggles any toggleable entity (light, input_boolean, switch, ...). */
    suspend fun toggle(entityId: String): Result<Unit> =
        callService(
            domain = "homeassistant",
            service = "toggle",
            target = buildJsonObject { put("entity_id", entityId) },
        )

    /** Calls an arbitrary action given as `domain.service`. */
    suspend fun callAction(action: String, data: JsonObject?, target: JsonObject?): Result<Unit> {
        val (domain, service) = action.split('.', limit = 2)
        return callService(domain, service, data, target)
    }

    private suspend fun callService(
        domain: String,
        service: String,
        serviceData: JsonObject? = null,
        target: JsonObject? = null,
    ): Result<Unit> = runCatching {
        val result = request { id -> Outgoing.callService(id, domain, service, serviceData, target) }
        if (!result.success) throw HaException(result.errorMessage ?: "$domain.$service failed")
    }.onFailure { if (it is CancellationException) throw it }

    private suspend fun runSession(ws: DefaultClientWebSocketSession, onConnected: () -> Unit) {
        authenticate(ws)

        lock.withLock { session = ws }
        val reader = ws.launch {
            for (frame in ws.incoming) {
                if (frame is Frame.Text) parseIncoming(frame.readText()).forEach { handle(it) }
            }
        }

        // Subscribe first so no change is missed between the snapshot and the subscription.
        val subscribed = request(Outgoing::subscribeStateChanged)
        if (!subscribed.success) throw HaException(subscribed.errorMessage ?: "subscribe_events failed")

        val snapshot = request(Outgoing::getStates)
        if (!snapshot.success) throw HaException(snapshot.errorMessage ?: "get_states failed")
        _states.value = parseStates(snapshot.result)
            .filter { it.entityId in entityIds }
            .associateBy { it.entityId }

        _connection.value = ConnectionState.Connected
        onConnected()

        reader.join()
    }

    private suspend fun authenticate(ws: DefaultClientWebSocketSession) {
        suspend fun next(): Incoming {
            while (true) {
                val frame = ws.incoming.receive()
                if (frame is Frame.Text) return parseIncoming(frame.readText()).first()
            }
        }

        withTimeout(REQUEST_TIMEOUT) {
            val first = next()
            if (first !is Incoming.AuthRequired) throw HaException("Unexpected greeting: $first")
            ws.send(Frame.Text(Outgoing.auth(config.token).toString()))
            when (val reply = next()) {
                Incoming.AuthOk -> Unit
                is Incoming.AuthInvalid -> throw AuthRejected(reply.message)
                else -> throw HaException("Unexpected auth reply: $reply")
            }
        }
    }

    private suspend fun handle(message: Incoming) {
        when (message) {
            is Incoming.Result -> {
                val deferred = pendingLock.withLock { pending.remove(message.id) }
                deferred?.complete(message)
            }

            is Incoming.StateChanged -> {
                if (message.entityId !in entityIds) return
                _states.update { current ->
                    val newState = message.newState
                    if (newState == null) current - message.entityId else current + (message.entityId to newState)
                }
            }

            else -> Unit
        }
    }

    private suspend fun request(build: (Int) -> JsonObject): Incoming.Result {
        val deferred = CompletableDeferred<Incoming.Result>()
        val id = lock.withLock {
            val ws = session ?: throw HaException("Not connected to Home Assistant")
            val id = ++nextId
            pendingLock.withLock { pending[id] = deferred }
            ws.send(Frame.Text(build(id).toString()))
            id
        }
        return try {
            withTimeout(REQUEST_TIMEOUT) { deferred.await() }
        } finally {
            pendingLock.withLock { pending.remove(id) }
        }
    }

    private suspend fun closeSession() {
        lock.withLock { session = null }
        val waiting = pendingLock.withLock { pending.values.toList().also { pending.clear() } }
        waiting.forEach { it.completeExceptionally(HaException("Connection lost")) }
    }

    companion object {
        private const val INITIAL_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 30_000L
        private val REQUEST_TIMEOUT = 10.seconds
    }
}
