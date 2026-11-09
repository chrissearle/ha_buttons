package org.chrissearle.buttons

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.chrissearle.buttons.config.ConfigException
import org.chrissearle.buttons.config.HaConfig
import org.chrissearle.buttons.config.parseConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigTest {
    private val valid = """
        homeassistant:
          url: http://homeassistant.local:8123/
          token: abc123
        ui:
          columns: 3
        buttons:
          - name: Desk lamp
            entity: light.desk
            icon: lightbulb
          - name: Guest mode
            entity: input_boolean.guest_mode
          - name: Movie time
            action: script.turn_on
            target: { entity_id: script.movie_time }
          - name: Notify
            action: notify.mobile_app_phone
            data:
              message: Hello
              priority: 5
              sticky: true
    """.trimIndent()

    @Test
    fun parsesValidConfig() {
        val config = parseConfig(valid)
        assertEquals("abc123", config.homeassistant.token)
        assertEquals(3, config.ui.columns)
        assertEquals(4, config.buttons.size)
        assertEquals("light.desk", config.buttons[0].entity)
        assertNull(config.buttons[1].icon)
        assertEquals("script.movie_time", config.buttons[2].target!!["entity_id"]!!.jsonPrimitive.content)
        val data = config.buttons[3].data!!
        assertEquals(JsonPrimitive("Hello"), data["message"])
        assertEquals(JsonPrimitive(5L), data["priority"])
        assertEquals(JsonPrimitive(true), data["sticky"])
    }

    @Test
    fun columnsDefaultToFour() {
        val config = parseConfig(
            """
            homeassistant: { url: "http://ha:8123", token: t }
            buttons:
              - { name: A, entity: light.a }
            """.trimIndent(),
        )
        assertEquals(4, config.ui.columns)
        // Only one button, so only one column is laid out.
        assertEquals(1, config.effectiveColumns)
    }

    @Test
    fun acceptsActionWithStateEntity() {
        val config = parseConfig(
            """
            homeassistant: { url: "http://ha:8123", token: t }
            buttons:
              - { name: Meeting, entity: input_boolean.meeting_state, action: script.meeting_change }
            """.trimIndent(),
        )
        assertEquals("input_boolean.meeting_state", config.buttons[0].entity)
        assertEquals("script.meeting_change", config.buttons[0].action)
    }

    @Test
    fun rejectsDataOnToggleOnlyButton() {
        val e = assertFailsWith<ConfigException> {
            parseConfig(
                """
                homeassistant: { url: "http://ha:8123", token: t }
                buttons:
                  - { name: A, entity: light.a, data: { brightness: 10 } }
                """.trimIndent(),
            )
        }
        assertTrue(e.message!!.contains("only apply to action"), e.message)
    }

    @Test
    fun rejectsButtonWithNeither() {
        assertFailsWith<ConfigException> {
            parseConfig(
                """
                homeassistant: { url: "http://ha:8123", token: t }
                buttons:
                  - { name: A }
                """.trimIndent(),
            )
        }
    }

    @Test
    fun rejectsMalformedAction() {
        assertFailsWith<ConfigException> {
            parseConfig(
                """
                homeassistant: { url: "http://ha:8123", token: t }
                buttons:
                  - { name: A, action: turn_on }
                """.trimIndent(),
            )
        }
    }

    @Test
    fun rejectsMissingToken() {
        assertFailsWith<ConfigException> {
            parseConfig(
                """
                homeassistant: { url: "http://ha:8123" }
                buttons:
                  - { name: A, entity: light.a }
                """.trimIndent(),
            )
        }
    }

    @Test
    fun parsesCaCert() {
        val config = parseConfig(
            """
            homeassistant: { url: "https://ha.lan:8123", token: t, ca_cert: ca.pem }
            buttons:
              - { name: A, entity: light.a }
            """.trimIndent(),
        )
        assertEquals("ca.pem", config.homeassistant.caCert)
    }

    @Test
    fun rejectsCaCertWithPlainHttp() {
        val e = assertFailsWith<ConfigException> {
            parseConfig(
                """
                homeassistant: { url: "http://ha.lan:8123", token: t, ca_cert: ca.pem }
                buttons:
                  - { name: A, entity: light.a }
                """.trimIndent(),
            )
        }
        assertTrue(e.message!!.contains("ca_cert"), e.message)
    }

    @Test
    fun rejectsInvalidYaml() {
        assertFailsWith<ConfigException> { parseConfig("homeassistant: [unclosed") }
    }

    @Test
    fun derivesWebSocketUrl() {
        assertEquals("ws://ha.local:8123/api/websocket", HaConfig("http://ha.local:8123/", "t").webSocketUrl)
        assertEquals("wss://ha.example.com/api/websocket", HaConfig("https://ha.example.com", "t").webSocketUrl)
        assertEquals("ws://ha:8123/api/websocket", HaConfig("ws://ha:8123/api/websocket", "t").webSocketUrl)
    }
}
