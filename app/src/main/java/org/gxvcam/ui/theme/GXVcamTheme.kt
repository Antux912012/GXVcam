package org.gxvcam.ui.theme

import android.os.Build
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

// OLED Pure Black palette & semi-transparent container surfaces
val OledBlack = Color(0xFF000000)
val DarkSurfaceElevated = Color(0xFF0E0E11)
val DarkSurfaceContainer = Color(0xFF18181C)
val DarkSurfaceContainerHigh = Color(0xD91E1E24) // 85% alpha for OLED overlay

private val BaseDarkColorScheme = darkColorScheme(
    primary = AccentTeal,
    onPrimary = OledBlack,
    primaryContainer = AccentTealContainer,
    background = OledBlack,
    surface = DarkSurfaceElevated,
    surfaceContainer = DarkSurfaceContainer,
    surfaceContainerHigh = DarkSurfaceContainerHigh,
    surfaceVariant = DarkSurfaceVariant
)

val M3ExpressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

@Composable
fun GXVcamTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dynamicColors = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        dynamicDarkColorScheme(context)
    } else {
        BaseDarkColorScheme
    }

    // Force OLED pure black background and custom elevated dark surfaces while preserving Monet accents
    val expressiveColorScheme = dynamicColors.copy(
        background = OledBlack,
        surface = DarkSurfaceElevated,
        surfaceContainer = DarkSurfaceContainer,
        surfaceContainerHigh = DarkSurfaceContainerHigh
    )

    MaterialTheme(
        colorScheme = expressiveColorScheme,
        shapes = M3ExpressiveShapes,
        content = content
    )
}
