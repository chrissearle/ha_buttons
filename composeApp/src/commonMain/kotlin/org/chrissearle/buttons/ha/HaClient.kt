package org.chrissearle.buttons.ha

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.chrissearle.buttons.config.HaConfig
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TimeSource

sealed interface ConnectionState {
    data object Connecting : ConnectionState
    data object Connected : ConnectionState

    /** The user disconnected manually; nothing is retried until they connect again. */
    data object Stopped : ConnectionState
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
 *
 * A heartbeat (HA `ping`) detects dead connections - e.g. a socket left half-open
 * after the machine slept - and a wall-clock jump (sleep/wake) triggers an immediate
 * check / reconnect.
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

    // Runs off the UI dispatcher: Swing timers (used by delay on Dispatchers.Main) are
    // unreliable after macOS sleep / App Nap, which could leave the retry loop stuck.
    fun start(scope: CoroutineScope): Job = scope.launch(Dispatchers.Default) {
        var backoffMs = INITIAL_BACKOFF_MS
        while (isActive) {
            _connection.value = ConnectionState.Connecting
            var reason: String? = "Connection closed"
            try {
                httpClient.webSocket(urlString = config.webSocketUrl) {
                    runSession(this) { backoffMs = INITIAL_BACKOFF_MS }
                }
            } catch (e: CancellationException) {
                // Only a real cancellation of the client stops the loop - a timeout
                // (TimeoutCancellationException) or engine-internal cancellation just retries.
                ensureActive()
                reason = if (e is kotlinx.coroutines.TimeoutCancellationException) "Timed out" else e.message
            } catch (e: AuthRejected) {
                _connection.value = ConnectionState.AuthFailed(e.message ?: "Authentication failed")
                return@launch
            } catch (e: Exception) {
                reason = e.message ?: e::class.simpleName
            } finally {
                closeSession()
            }

            val woke = waitBeforeRetry(backoffMs, reason)
            backoffMs = if (woke) INITIAL_BACKOFF_MS else (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    /**
     * Waits [backoffMs] showing a countdown. Returns early (true) if the machine
     * appears to have slept, so we reconnect straight after wake.
     */
    private suspend fun waitBeforeRetry(backoffMs: Long, reason: String?): Boolean {
        val wake = WakeDetector()
        val seconds = (backoffMs / 1000).toInt().coerceAtLeast(1)
        for (remaining in seconds downTo 1) {
            _connection.value = ConnectionState.Disconnected(reason, remaining)
            delay(TICK)
            if (wake.check()) return true
        }
        return false
    }

    /** Pings HA periodically (and right after wake); throws if a ping goes unanswered. */
    private suspend fun heartbeat() {
        val wake = WakeDetector()
        var lastPing = TimeSource.Monotonic.markNow()
        while (true) {
            delay(TICK)
            val woke = wake.check()
            if (woke || lastPing.elapsedNow() >= HEARTBEAT_INTERVAL) {
                val pong = request(Outgoing::ping)
                if (!pong.success) throw HaException("Ping failed")
                lastPing = TimeSource.Monotonic.markNow()
            }
        }
    }

    /** Detects sleep by the wall clock advancing much more than expected between ticks. */
    private class WakeDetector {
        private var last: Instant = Clock.System.now()

        fun check(): Boolean {
            val now = Clock.System.now()
            val gap = now - last
            last = now
            return gap > TICK + WAKE_THRESHOLD || gap.isNegative()
        }
    }

    /** Marks the connection as manually stopped. Call only once the connect loop has finished. */
    fun markStopped() {
        _connection.value = ConnectionState.Stopped
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

        try {
            coroutineScope {
                val heartbeat = launch { heartbeat() }
                reader.join()
                heartbeat.cancel()
            }
        } catch (e: HaException) {
            // Connection is dead: tear it down without waiting on a graceful close.
            reader.cancel()
            ws.cancel()
            throw e
        }
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
            withTimeoutOrNull(REQUEST_TIMEOUT) { deferred.await() }
                ?: throw HaException("Timed out waiting for Home Assistant")
        } finally {
            pendingLock.withLock { pending.remove(id) }
        }
    }

    // NonCancellable: must still clean up when the connect loop is cancelled (manual reconnect).
    private suspend fun closeSession() = withContext(NonCancellable) {
        lock.withLock { session = null }
        val waiting = pendingLock.withLock { pending.values.toList().also { pending.clear() } }
        waiting.forEach { it.completeExceptionally(HaException("Connection lost")) }
    }

    companion object {
        private const val INITIAL_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 30_000L
        private val REQUEST_TIMEOUT = 10.seconds
        private val HEARTBEAT_INTERVAL = 30.seconds
        private val TICK = 1.seconds
        private val WAKE_THRESHOLD: Duration = 5.seconds
    }
}
