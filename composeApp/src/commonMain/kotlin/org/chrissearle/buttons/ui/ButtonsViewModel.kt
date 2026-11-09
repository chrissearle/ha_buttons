package org.chrissearle.buttons.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.chrissearle.buttons.config.ButtonConfig
import org.chrissearle.buttons.ha.ConnectionState
import org.chrissearle.buttons.ha.EntityState
import org.chrissearle.buttons.ha.HaClient

enum class Flash { Success, Failure }

data class ButtonUiState(
    val index: Int,
    val name: String,
    val icon: String?,
    /** Entity id for entity buttons, action name for action buttons. */
    val target: String,
    val isAction: Boolean,
    val isOn: Boolean,
    val available: Boolean,
    val flash: Flash?,
)

class ButtonsViewModel(
    private val buttons: List<ButtonConfig>,
    private val client: HaClient,
    private val scope: CoroutineScope,
) {
    private val flashes = MutableStateFlow<Map<Int, Flash>>(emptyMap())

    private val _message = MutableStateFlow<String?>(null)

    /** Last error from a button press, shown in the status bar. */
    val message: StateFlow<String?> = _message.asStateFlow()

    val connection: StateFlow<ConnectionState> = client.connection

    val uiState: StateFlow<List<ButtonUiState>> =
        combine(client.states, client.connection, flashes) { states, connection, flashes ->
            buttons.mapIndexed { i, b -> b.toUiState(i, states, connection is ConnectionState.Connected, flashes[i]) }
        }.stateIn(
            scope,
            SharingStarted.Eagerly,
            buttons.mapIndexed { i, b -> b.toUiState(i, emptyMap(), connected = false, flash = null) },
        )

    fun start() {
        client.start(scope)
    }

    fun onClick(index: Int) {
        val button = buttons[index]
        scope.launch {
            val result = when {
                button.entity != null -> client.toggle(button.entity)
                button.action != null -> client.callAction(button.action, button.data, button.target)
                else -> return@launch
            }

            result.onFailure { _message.value = "${button.name}: ${it.message}" }
            result.onSuccess { _message.value = null }

            // Entity buttons show success through their state; actions need explicit feedback.
            val flash = when {
                result.isFailure -> Flash.Failure
                button.action != null -> Flash.Success
                else -> null
            }
            if (flash != null) {
                flashes.update { it + (index to flash) }
                delay(FLASH_MS)
                flashes.update { it - index }
            }
        }
    }

    private fun ButtonConfig.toUiState(
        index: Int,
        states: Map<String, EntityState>,
        connected: Boolean,
        flash: Flash?,
    ): ButtonUiState {
        val state = entity?.let(states::get)
        return ButtonUiState(
            index = index,
            name = name,
            icon = icon,
            target = entity ?: action.orEmpty(),
            isAction = action != null,
            isOn = state?.isOn == true,
            available = connected && (action != null || state?.isAvailable == true),
            flash = flash,
        )
    }

    private companion object {
        const val FLASH_MS = 600L
    }
}
