package org.gxvcam.ui.theme

import android.os.Build
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.gxvcam.ui.state.AccentTheme

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

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun GXVcamTheme(
    accentTheme: AccentTheme = AccentTheme.PANTONE_POINCIANA,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val baseScheme = if (accentTheme == AccentTheme.DYNAMIC_SYSTEM && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        dynamicDarkColorScheme(context)
    } else if (accentTheme == AccentTheme.DYNAMIC_SYSTEM) {
        BaseDarkColorScheme
    } else {
        val primary = Color(accentTheme.colorHex)
        val container = Color(accentTheme.containerHex)
        BaseDarkColorScheme.copy(
            primary = primary,
            onPrimary = OledBlack,
            primaryContainer = container,
            onPrimaryContainer = Color.White,
            tertiary = primary,
            tertiaryContainer = container,
            onTertiary = OledBlack,
            onTertiaryContainer = Color.White,
        )
    }

    // Apply Nord Polar Night / Frost surfaces when NORD_FROST is active, otherwise OLED pure black
    val expressiveColorScheme = if (accentTheme == AccentTheme.NORD_FROST) {
        baseScheme.copy(
            background = NordPolarNight0,
            onBackground = NordSnowStorm,
            surface = NordPolarNight1,
            onSurface = NordSnowStorm,
            surfaceContainer = NordPolarNight1,
            surfaceContainerHigh = NordPolarNight2,
            surfaceVariant = NordPolarNight2,
            onSurfaceVariant = NordSnowStorm.copy(alpha = 0.85f),
            primary = NordFrostCyan,
            onPrimary = NordPolarNight0,
            primaryContainer = NordPolarNight1,
            onPrimaryContainer = NordFrostCyan,
            secondary = NordFrostBlue,
            onSecondary = NordPolarNight0,
            tertiary = NordFrostBlue,
            onTertiary = NordPolarNight0
        )
    } else {
        baseScheme.copy(
            background = OledBlack,
            surface = DarkSurfaceElevated,
            surfaceContainer = DarkSurfaceContainer,
            surfaceContainerHigh = DarkSurfaceContainerHigh,
            primary = if (accentTheme == AccentTheme.DYNAMIC_SYSTEM) baseScheme.primary else Color(accentTheme.colorHex),
            primaryContainer = if (accentTheme == AccentTheme.DYNAMIC_SYSTEM) baseScheme.primaryContainer else Color(accentTheme.containerHex),
        )
    }

    MaterialExpressiveTheme(
        colorScheme = expressiveColorScheme,
        motionScheme = MotionScheme.expressive(),
        shapes = M3ExpressiveShapes,
        content = content
    )
}
