package org.chrissearle.buttons.config

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlException
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlNull
import com.charleskorn.kaml.YamlScalar
import com.charleskorn.kaml.YamlTaggedNode
import kotlinx.serialization.SerialName
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class AppConfig(
    val homeassistant: HaConfig,
    val ui: UiConfig = UiConfig(),
    val buttons: List<ButtonConfig>,
) {
    /** Columns actually laid out: `ui.columns`, but never more than there are buttons. */
    val effectiveColumns: Int
        get() = ui.columns.coerceAtMost(buttons.size).coerceAtLeast(1)
}

@Serializable
data class HaConfig(
    val url: String,
    val token: String,
    /**
     * Optional path to a PEM file with the CA certificate(s) that signed your Home Assistant
     * server certificate. When set, ONLY these CAs are trusted. When omitted, the system trust
     * store is used (e.g. for a Nabu Casa `https://xxxx.ui.nabu.casa` URL).
     * Relative paths are resolved against the config file's directory.
     * There is deliberately no option to disable certificate verification.
     */
    @SerialName("ca_cert")
    val caCert: String? = null,
) {
    val isSecure: Boolean
        get() = webSocketUrl.startsWith("wss://")

    /** WebSocket endpoint derived from the configured base URL. */
    val webSocketUrl: String
        get() {
            val base = url.trim().trimEnd('/')
            val ws = when {
                base.startsWith("https://") -> "wss://" + base.removePrefix("https://")
                base.startsWith("http://") -> "ws://" + base.removePrefix("http://")
                base.startsWith("ws://") || base.startsWith("wss://") -> base
                else -> "ws://$base"
            }
            return if (ws.endsWith("/api/websocket")) ws else "$ws/api/websocket"
        }
}

@Serializable
data class UiConfig(
    val columns: Int = 4,
)

@Serializable
data class ButtonConfig(
    val name: String,
    val icon: String? = null,
    /**
     * Entity whose on/off state the tile shows, e.g. `light.desk` or `input_boolean.guest_mode`.
     * Without [action], clicking toggles it.
     */
    val entity: String? = null,
    /** Action to call on click, e.g. `script.turn_on`. Replaces the toggle when [entity] is also set. */
    val action: String? = null,
    /** Optional action data (service_data). */
    val data: JsonObject? = null,
    /** Optional action target, e.g. `{ entity_id: script.movie }`. */
    val target: JsonObject? = null,
)

class ConfigException(message: String, cause: Throwable? = null) : Exception(message, cause)

private val json = Json {
    ignoreUnknownKeys = false
    isLenient = true
}

/**
 * Parses the YAML config. YAML is first converted to a JSON tree so that free-form
 * `data` / `target` blocks can be passed straight through to Home Assistant.
 */
fun parseConfig(text: String): AppConfig {
    val tree = try {
        Yaml.default.parseToYamlNode(text).toJson()
    } catch (e: YamlException) {
        throw ConfigException("Invalid YAML: ${e.message}", e)
    }

    val config = try {
        json.decodeFromJsonElement(AppConfig.serializer(), tree)
    } catch (e: SerializationException) {
        throw ConfigException("Invalid config: ${e.message}", e)
    } catch (e: IllegalArgumentException) {
        throw ConfigException("Invalid config: ${e.message}", e)
    }

    validate(config)
    return config
}

private fun validate(config: AppConfig) {
    val errors = mutableListOf<String>()

    if (config.homeassistant.url.isBlank()) errors += "homeassistant.url is empty"
    if (config.homeassistant.token.isBlank()) errors += "homeassistant.token is empty"
    if (config.homeassistant.caCert != null && !config.homeassistant.isSecure) {
        errors += "homeassistant.ca_cert is set but url is not https:// - the certificate would never be used"
    }
    if (config.ui.columns < 1) errors += "ui.columns must be at least 1"
    if (config.buttons.isEmpty()) errors += "no buttons configured"

    config.buttons.forEachIndexed { i, b ->
        val label = "buttons[$i] (${b.name})"
        if (b.entity == null && b.action == null) errors += "$label: needs 'entity' and/or 'action'"
        if (b.entity != null && !b.entity.contains('.')) {
            errors += "$label: entity '${b.entity}' should look like domain.object_id"
        }
        if (b.action != null && b.action.split('.').let { it.size != 2 || it.any(String::isBlank) }) {
            errors += "$label: action '${b.action}' should look like domain.service"
        }
        if (b.action == null && (b.data != null || b.target != null)) {
            errors += "$label: 'data' and 'target' only apply to action buttons"
        }
    }

    if (errors.isNotEmpty()) {
        throw ConfigException(errors.joinToString(separator = "\n"))
    }
}

private val intPattern = Regex("^[-+]?[0-9]+$")
private val floatPattern = Regex("^[-+]?([0-9]*\\.[0-9]+|[0-9]+\\.?[0-9]*)([eE][-+]?[0-9]+)?$")

private fun YamlNode.toJson(): JsonElement = when (this) {
    is YamlNull -> JsonNull
    is YamlMap -> JsonObject(entries.entries.associate { (k, v) -> k.content to v.toJson() })
    is YamlList -> JsonArray(items.map { it.toJson() })
    is YamlTaggedNode -> innerNode.toJson()
    is YamlScalar -> scalarToJson(content)
}

private fun scalarToJson(content: String): JsonPrimitive = when {
    content == "true" -> JsonPrimitive(true)
    content == "false" -> JsonPrimitive(false)
    intPattern.matches(content) -> content.toLongOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(content)
    floatPattern.matches(content) -> content.toDoubleOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(content)
    else -> JsonPrimitive(content)
}
