package org.chrissearle.buttons

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.chrissearle.buttons.config.AppConfig
import org.chrissearle.buttons.ha.ConnectionState
import org.chrissearle.buttons.ha.HaClient
import org.chrissearle.buttons.ui.ButtonGrid
import org.chrissearle.buttons.ui.ButtonsViewModel

enum class ConnectionCommand { Connect, Disconnect, Reconnect }

/** Bridge between the window's menu bar and the [App]'s connection. */
class ConnectionControl {
    val commands = MutableSharedFlow<ConnectionCommand>(extraBufferCapacity = 1)

    /** Null until the app has loaded (e.g. config error). */
    val state = MutableStateFlow<ConnectionState?>(null)
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            content()
        }
    }
}

@Composable
fun App(config: AppConfig, control: ConnectionControl, createHttpClient: () -> HttpClient) {
    val viewModel = remember(config) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val http = createHttpClient()
        val client = HaClient(
            config = config.homeassistant,
            entityIds = config.buttons.mapNotNull { it.entity }.toSet(),
            httpClient = http,
        )
        Triple(ButtonsViewModel(config.buttons, client, scope), scope, http)
    }

    DisposableEffect(viewModel) {
        val (vm, scope, http) = viewModel
        vm.start()
        onDispose {
            scope.cancel()
            http.close()
        }
    }

    val vm = viewModel.first
    LaunchedEffect(vm) {
        launch { vm.connection.collect { control.state.value = it } }
        control.commands.collect {
            when (it) {
                ConnectionCommand.Connect -> vm.connect()
                ConnectionCommand.Disconnect -> vm.disconnect()
                ConnectionCommand.Reconnect -> vm.reconnect()
            }
        }
    }
    val buttons by vm.uiState.collectAsState()
    val connection by vm.connection.collectAsState()
    val message by vm.message.collectAsState()

    AppTheme {
        Column(Modifier.fillMaxSize()) {
            ButtonGrid(
                buttons = buttons,
                columns = config.effectiveColumns,
                onClick = vm::onClick,
                modifier = Modifier.weight(1f),
            )
            StatusBar(connection, message)
        }
    }
}

@Composable
private fun StatusBar(connection: ConnectionState, message: String?) {
    val (dot, text) = when (connection) {
        ConnectionState.Connecting -> Color(0xFFFFB300) to "Connecting…"
        ConnectionState.Connected -> Color(0xFF43A047) to "Connected"
        ConnectionState.Stopped -> Color(0xFF9E9E9E) to "Disconnected"
        is ConnectionState.Disconnected ->
            Color(0xFFE53935) to "Disconnected (${connection.reason}) – retrying in ${connection.retryInSeconds}s"

        is ConnectionState.AuthFailed -> Color(0xFFE53935) to "Authentication failed: ${connection.message}"
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Box(Modifier.size(8.dp).background(dot, CircleShape))
        Box(Modifier.width(8.dp))
        Text(
            text = message ?: text,
            style = MaterialTheme.typography.bodySmall,
            color = if (message != null) Color(0xFFEF9A9A) else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun ConfigErrorScreen(path: String, message: String) {
    AppTheme {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Text("Could not load config", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.error)
            Text(path, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
