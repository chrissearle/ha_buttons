package org.chrissearle.buttons

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.chrissearle.buttons.ha.EntityState
import org.chrissearle.buttons.ha.Incoming
import org.chrissearle.buttons.ha.Outgoing
import org.chrissearle.buttons.ha.parseIncoming
import org.chrissearle.buttons.ha.parseStates
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MessagesTest {
    @Test
    fun parsesAuthMessages() {
        assertEquals(listOf(Incoming.AuthRequired), parseIncoming("""{"type":"auth_required","ha_version":"2026.11.0"}"""))
        assertEquals(listOf(Incoming.AuthOk), parseIncoming("""{"type":"auth_ok","ha_version":"2026.11.0"}"""))
        assertEquals(
            listOf(Incoming.AuthInvalid("Invalid access token or password")),
            parseIncoming("""{"type":"auth_invalid","message":"Invalid access token or password"}"""),
        )
    }

    @Test
    fun parsesErrorResult() {
        val msg = parseIncoming(
            """{"id":7,"type":"result","success":false,"error":{"code":"not_found","message":"Service not found."}}""",
        ).single()
        assertEquals(Incoming.Result(7, false, null, "Service not found."), msg)
    }

    @Test
    fun parsesGetStatesResult() {
        val msg = parseIncoming(
            """
            {"id":2,"type":"result","success":true,"result":[
              {"entity_id":"light.desk","state":"on","attributes":{"friendly_name":"Desk"}},
              {"entity_id":"input_boolean.guest","state":"off","attributes":{}}
            ]}
            """.trimIndent(),
        ).single()
        assertIs<Incoming.Result>(msg)
        assertEquals(
            listOf(EntityState("light.desk", "on", "Desk"), EntityState("input_boolean.guest", "off")),
            parseStates(msg.result),
        )
    }

    @Test
    fun parsesStateChangedEvent() {
        val msg = parseIncoming(
            """
            {"id":1,"type":"event","event":{"event_type":"state_changed","data":{
              "entity_id":"light.desk",
              "old_state":{"entity_id":"light.desk","state":"off"},
              "new_state":{"entity_id":"light.desk","state":"on","attributes":{}}
            }}}
            """.trimIndent(),
        ).single()
        assertEquals(Incoming.StateChanged("light.desk", EntityState("light.desk", "on")), msg)
    }

    @Test
    fun parsesCoalescedArray() {
        val msgs = parseIncoming("""[{"type":"auth_ok"},{"type":"other_thing","id":3}]""")
        assertEquals(listOf(Incoming.AuthOk, Incoming.Other("other_thing")), msgs)
    }

    @Test
    fun parsesPongAsSuccessfulResult() {
        val msg = parseIncoming("""{"type":"pong","id":7}""").single()
        assertEquals(Incoming.Result(id = 7, success = true, result = null, errorMessage = null), msg)
    }

    @Test
    fun buildsPing() {
        assertEquals("""{"id":4,"type":"ping"}""", Outgoing.ping(4).toString())
    }

    @Test
    fun buildsCallService() {
        val msg = Outgoing.callService(
            id = 5,
            domain = "script",
            service = "turn_on",
            target = buildJsonObject { put("entity_id", "script.movie") },
        )
        assertEquals(
            Json.parseToJsonElement(
                """{"id":5,"type":"call_service","domain":"script","service":"turn_on","target":{"entity_id":"script.movie"}}""",
            ),
            msg,
        )
    }
}
