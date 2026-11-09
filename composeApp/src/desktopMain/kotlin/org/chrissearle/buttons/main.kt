package org.chrissearle.buttons

import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import org.chrissearle.buttons.config.AppConfig
import org.chrissearle.buttons.config.ConfigException
import org.chrissearle.buttons.config.parseConfig
import org.chrissearle.buttons.ui.LabelHeight
import org.chrissearle.buttons.ui.TileSize
import org.chrissearle.buttons.ui.TileSpacing
import java.awt.Taskbar
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.net.ssl.X509TrustManager

/** Resolution order: `--config <path>`, then `BUTTONS_CONFIG`, then `~/.config/buttons/config.yaml`. */
fun resolveConfigPath(args: Array<String>): File {
    val argIndex = args.indexOf("--config")
    if (argIndex >= 0 && argIndex + 1 < args.size) return File(args[argIndex + 1])
    System.getenv("BUTTONS_CONFIG")?.takeIf { it.isNotBlank() }?.let { return File(it) }
    return File(System.getProperty("user.home"), ".config/buttons/config.yaml")
}

class LoadedConfig(val config: AppConfig, val trustManager: X509TrustManager?)

fun loadConfig(file: File): Result<LoadedConfig> = runCatching {
    if (!file.exists()) throw ConfigException("File not found. Copy config.example.yaml there and edit it.")
    val config = parseConfig(file.readText())
    val trustManager = config.homeassistant.caCert?.let { loadTrustManager(resolveCaCertPath(it, file)) }
    LoadedConfig(config, trustManager)
}

private fun loadAppIcon(): BufferedImage? =
    Thread.currentThread().contextClassLoader.getResourceAsStream("app-icon.png")?.use(ImageIO::read)

/** The packaged .app uses the .icns; this covers the dock icon when run via `gradlew run`. */
private fun setDockIcon(image: BufferedImage) {
    runCatching {
        if (Taskbar.isTaskbarSupported() && Taskbar.getTaskbar().isSupported(Taskbar.Feature.ICON_IMAGE)) {
            Taskbar.getTaskbar().iconImage = image
        }
    }
}

fun main(args: Array<String>) {
    val file = resolveConfigPath(args)
    val loaded = loadConfig(file)
    val iconImage = loadAppIcon()?.also(::setDockIcon)
    val icon = iconImage?.let { BitmapPainter(it.toComposeImageBitmap()) }

    application {
        val size = loaded.getOrNull()?.let { windowSizeFor(it.config) } ?: DpSize(520.dp, 320.dp)
        Window(
            onCloseRequest = ::exitApplication,
            title = "Buttons",
            icon = icon,
            state = rememberWindowState(size = size),
        ) {
            loaded.fold(
                onSuccess = { App(it.config) { createHttpClient(it.trustManager) } },
                onFailure = { ConfigErrorScreen(file.absolutePath, it.message ?: it.toString()) },
            )
        }
    }
}

private fun windowSizeFor(config: AppConfig): DpSize {
    val columns = config.effectiveColumns
    val rows = (config.buttons.size + columns - 1) / columns
    val width = TileSize * columns + TileSpacing * (columns + 1)
    val height = (TileSize + LabelHeight) * rows + TileSpacing * (rows + 1) +
        32.dp + // status bar
        32.dp // window title bar
    return DpSize(width.coerceAtLeast(300.dp), height)
}
