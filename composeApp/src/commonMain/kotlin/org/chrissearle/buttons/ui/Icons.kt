package org.chrissearle.buttons.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Bed
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.DoorFront
import androidx.compose.material.icons.filled.Garage
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Kitchen
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.LocalFlorist
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.ToggleOn
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.ui.graphics.vector.ImageVector

/** Icon names usable in the config's `icon:` field. */
val namedIcons: Map<String, ImageVector> = mapOf(
    "ac" to Icons.Filled.AcUnit,
    "air" to Icons.Filled.Air,
    "fan" to Icons.Filled.Air,
    "alarm" to Icons.Filled.Alarm,
    "bed" to Icons.Filled.Bed,
    "moon" to Icons.Filled.Bedtime,
    "night" to Icons.Filled.Bedtime,
    "bolt" to Icons.Filled.Bolt,
    "code" to Icons.Filled.Code,
    "coffee" to Icons.Filled.Coffee,
    "door" to Icons.Filled.DoorFront,
    "garage" to Icons.Filled.Garage,
    "home" to Icons.Filled.Home,
    "kitchen" to Icons.Filled.Kitchen,
    "lightbulb" to Icons.Filled.Lightbulb,
    "light" to Icons.Filled.Lightbulb,
    "fire" to Icons.Filled.LocalFireDepartment,
    "heat" to Icons.Filled.LocalFireDepartment,
    "plant" to Icons.Filled.LocalFlorist,
    "lock" to Icons.Filled.Lock,
    "unlock" to Icons.Filled.LockOpen,
    "movie" to Icons.Filled.Movie,
    "music" to Icons.Filled.MusicNote,
    "notifications" to Icons.Filled.Notifications,
    "bell" to Icons.Filled.Notifications,
    "scene" to Icons.Filled.Palette,
    "palette" to Icons.Filled.Palette,
    "person" to Icons.Filled.Person,
    "play" to Icons.Filled.PlayArrow,
    "plug" to Icons.Filled.Power,
    "power" to Icons.Filled.PowerSettingsNew,
    "security" to Icons.Filled.Security,
    "speaker" to Icons.Filled.Speaker,
    "star" to Icons.Filled.Star,
    "stop" to Icons.Filled.Stop,
    "toggle" to Icons.Filled.ToggleOn,
    "touch" to Icons.Filled.TouchApp,
    "tv" to Icons.Filled.Tv,
    "camera" to Icons.Filled.Videocam,
    "water" to Icons.Filled.WaterDrop,
    "sun" to Icons.Filled.WbSunny,
)

/** Resolves the configured icon, falling back to a sensible default for the entity/action domain. */
fun iconFor(name: String?, target: String): ImageVector {
    name?.lowercase()?.let(namedIcons::get)?.let { return it }
    return when (target.substringBefore('.')) {
        "light" -> Icons.Filled.Lightbulb
        "input_boolean", "switch" -> Icons.Filled.ToggleOn
        "script" -> Icons.Filled.PlayArrow
        "scene" -> Icons.Filled.Palette
        "fan" -> Icons.Filled.Air
        "notify" -> Icons.Filled.Notifications
        else -> Icons.Filled.TouchApp
    }
}
