package org.chrissearle.buttons.ha

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** The subset of a Home Assistant entity state that the app cares about. */
data class EntityState(
    val entityId: String,
    val state: String,
    val friendlyName: String? = null,
) {
    val isOn: Boolean get() = state == "on"
    val isAvailable: Boolean get() = state != "unavailable" && state != "unknown"
}

/** Messages received from the Home Assistant WebSocket API. */
sealed interface Incoming {
    data object AuthRequired : Incoming
    data object AuthOk : Incoming
    data class AuthInvalid(val message: String) : Incoming
    data class Result(
        val id: Int,
        val success: Boolean,
        val result: JsonElement?,
        val errorMessage: String?,
    ) : Incoming

    data class StateChanged(val entityId: String, val newState: EntityState?) : Incoming
    data class Other(val type: String?) : Incoming
}

/** Builders for messages sent to the Home Assistant WebSocket API. */
object Outgoing {
    fun auth(token: String): JsonObject = buildJsonObject {
        put("type", "auth")
        put("access_token", token)
    }

    fun getStates(id: Int): JsonObject = buildJsonObject {
        put("id", id)
        put("type", "get_states")
    }

    fun subscribeStateChanged(id: Int): JsonObject = buildJsonObject {
        put("id", id)
        put("type", "subscribe_events")
        put("event_type", "state_changed")
    }

    fun callService(
        id: Int,
        domain: String,
        service: String,
        serviceData: JsonObject? = null,
        target: JsonObject? = null,
    ): JsonObject = buildJsonObject {
        put("id", id)
        put("type", "call_service")
        put("domain", domain)
        put("service", service)
        if (serviceData != null) put("service_data", serviceData)
        if (target != null) put("target", target)
    }
}

internal val haJson = Json { ignoreUnknownKeys = true }

/** Parses one text frame. HA may send a JSON array when messages are coalesced. */
fun parseIncoming(text: String): List<Incoming> =
    when (val element = haJson.parseToJsonElement(text)) {
        is JsonArray -> element.map { parseMessage(it.jsonObject) }
        is JsonObject -> listOf(parseMessage(element))
        else -> emptyList()
    }

private fun JsonObject.string(key: String): String? =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull

private fun parseMessage(obj: JsonObject): Incoming = when (val type = obj.string("type")) {
    "auth_required" -> Incoming.AuthRequired
    "auth_ok" -> Incoming.AuthOk
    "auth_invalid" -> Incoming.AuthInvalid(obj.string("message") ?: "Authentication failed")
    "result" -> Incoming.Result(
        id = obj["id"]?.jsonPrimitive?.intOrNull ?: -1,
        success = obj["success"]?.jsonPrimitive?.booleanOrNull ?: false,
        result = obj["result"],
        errorMessage = (obj["error"] as? JsonObject)?.string("message"),
    )

    "event" -> parseEvent(obj) ?: Incoming.Other(type)
    else -> Incoming.Other(type)
}

private fun parseEvent(obj: JsonObject): Incoming? {
    val event = obj["event"] as? JsonObject ?: return null
    if (event.string("event_type") != "state_changed") return null
    val data = event["data"] as? JsonObject ?: return null
    val entityId = data.string("entity_id") ?: return null
    val newState = (data["new_state"] as? JsonObject)?.let(::parseState)
    return Incoming.StateChanged(entityId, newState)
}

/** Parses a HA state object (as found in `get_states` results and `state_changed` events). */
fun parseState(obj: JsonObject): EntityState? {
    val entityId = obj.string("entity_id") ?: return null
    val state = obj.string("state") ?: return null
    val friendlyName = (obj["attributes"] as? JsonObject)?.string("friendly_name")
    return EntityState(entityId, state, friendlyName)
}

/** Parses the `result` payload of a `get_states` call. */
fun parseStates(result: JsonElement?): List<EntityState> =
    (result as? JsonArray)?.jsonArray?.mapNotNull { (it as? JsonObject)?.let(::parseState) } ?: emptyList()
