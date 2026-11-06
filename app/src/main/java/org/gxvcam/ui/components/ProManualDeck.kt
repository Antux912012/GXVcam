package org.gxvcam.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.gxvcam.ui.state.HistogramData
import org.gxvcam.ui.state.ProHardwareCapabilities
import org.gxvcam.ui.state.ProSettings
import org.gxvcam.ui.state.WhiteBalanceMode
import org.gxvcam.ui.theme.DarkSurfaceContainerHigh
import org.gxvcam.ui.theme.GXVMotion
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun ProManualDeck(
    visible: Boolean,
    proSettings: ProSettings,
    activeControl: String?,
    hardwareCapabilities: ProHardwareCapabilities,
    onControlSelected: (String?) -> Unit,
    onProSettingChanged: ((ProSettings) -> ProSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(
            animationSpec = GXVMotion.spatialSmooth(),
            initialOffsetY = { it },
        ) + fadeIn(GXVMotion.fastEffects()),
        exit = slideOutVertically(
            animationSpec = GXVMotion.spatialSmooth(),
            targetOffsetY = { it },
        ) + fadeOut(GXVMotion.fastEffects()),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Expanded Control Slider Dial with Live Readout & Quick Presets
            activeControl?.let { control ->
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = DarkSurfaceContainerHigh,
                    tonalElevation = 6.dp,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        // Title + Value Readout + Auto button row
                        ProControlHeader(
                            control = control,
                            proSettings = proSettings,
                            hardwareCapabilities = hardwareCapabilities,
                            onResetToAuto = {
                                onProSettingChanged { s ->
                                    when (control) {
                                        "ISO" -> s.copy(isManualIso = false)
                                        "S" -> s.copy(isManualShutter = false)
                                        "WB" -> s.copy(whiteBalanceMode = WhiteBalanceMode.AUTO)
                                        "AF" -> s.copy(isManualFocus = false)
                                        "EV" -> s.copy(evIndex = 0)
                                        else -> s
                                    }
                                }
                            },
                        )

                        // Parameter Slider & Presets
                        when (control) {
                            "EV" -> EvSliderControl(
                                evIndex = proSettings.evIndex,
                                evRange = hardwareCapabilities.evRange,
                                onEvChanged = { ev ->
                                    onProSettingChanged { it.copy(evIndex = ev) }
                                },
                            )
                            "ISO" -> IsoSliderControl(
                                currentIso = proSettings.iso,
                                isManual = proSettings.isManualIso,
                                isoRange = hardwareCapabilities.isoRange,
                                onIsoChanged = { iso, manual ->
                                    onProSettingChanged { it.copy(iso = iso, isManualIso = manual) }
                                },
                            )
                            "S" -> ShutterSliderControl(
                                currentNs = proSettings.shutterSpeedNs,
                                isManual = proSettings.isManualShutter,
                                onShutterChanged = { ns, manual ->
                                    onProSettingChanged { it.copy(shutterSpeedNs = ns, isManualShutter = manual) }
                                },
                            )
                            "WB" -> WhiteBalanceControl(
                                currentMode = proSettings.whiteBalanceMode,
                                currentKelvin = proSettings.whiteBalanceKelvin,
                                onWbChanged = { mode, kelvin ->
                                    onProSettingChanged {
                                        it.copy(whiteBalanceMode = mode, whiteBalanceKelvin = kelvin)
                                    }
                                },
                            )
                            "AF" -> FocusControl(
                                currentDistance = proSettings.focusDistanceDiopters,
                                isManual = proSettings.isManualFocus,
                                maxDistance = hardwareCapabilities.minFocusDistanceDiopters,
                                onFocusChanged = { dist, manual ->
                                    onProSettingChanged {
                                        it.copy(focusDistanceDiopters = dist, isManualFocus = manual)
                                    }
                                },
                            )
                        }
                    }
                }
            }

            // 5 Circular / Oval Pro Setting Chips + Quick Reset All
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val chips = listOf(
                    ProChipData(
                        id = "EV",
                        label = "EV",
                        value = String.format(Locale.US, "%+.1f", proSettings.evIndex * hardwareCapabilities.evStep),
                        isManual = proSettings.evIndex != 0,
                    ),
                    ProChipData(
                        id = "ISO",
                        label = "ISO",
                        value = if (proSettings.isManualIso) "${proSettings.iso}" else "AUTO",
                        isManual = proSettings.isManualIso,
                    ),
                    ProChipData(
                        id = "S",
                        label = "S",
                        value = formatShutterSpeed(proSettings.shutterSpeedNs, proSettings.isManualShutter),
                        isManual = proSettings.isManualShutter,
                    ),
                    ProChipData(
                        id = "WB",
                        label = "WB",
                        value = if (proSettings.whiteBalanceMode == WhiteBalanceMode.AUTO) "AWB" else "${proSettings.whiteBalanceKelvin}K",
                        isManual = proSettings.whiteBalanceMode != WhiteBalanceMode.AUTO,
                    ),
                    ProChipData(
                        id = "AF",
                        label = "AF",
                        value = if (proSettings.isManualFocus) String.format(Locale.US, "%.1fd", proSettings.focusDistanceDiopters) else "AF-C",
                        isManual = proSettings.isManualFocus,
                    ),
                )

                chips.forEach { chip ->
                    val isSelected = activeControl == chip.id
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = when {
                            isSelected -> MaterialTheme.colorScheme.primaryContainer
                            chip.isManual -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.85f)
                            else -> DarkSurfaceContainerHigh
                        },
                        tonalElevation = if (isSelected || chip.isManual) 4.dp else 2.dp,
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .then(
                                if (chip.isManual && !isSelected) {
                                    Modifier.border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.7f), RoundedCornerShape(18.dp))
                                } else {
                                    Modifier
                                },
                            )
                            .clickable {
                                onControlSelected(if (isSelected) null else chip.id)
                            },
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                text = chip.label,
                                color = when {
                                    isSelected -> MaterialTheme.colorScheme.onPrimaryContainer
                                    chip.isManual -> MaterialTheme.colorScheme.primary
                                    else -> Color.White.copy(alpha = 0.6f)
                                },
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = chip.value,
                                color = when {
                                    isSelected -> MaterialTheme.colorScheme.primary
                                    chip.isManual -> Color.White
                                    else -> Color.White
                                },
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                            )
                        }
                    }
                }

                // If any parameter is manual, show a subtle reset all button
                if (proSettings.isAnyManual) {
                    FilledTonalIconButton(
                        onClick = {
                            onProSettingChanged { s ->
                                s.copy(
                                    isManualIso = false,
                                    isManualShutter = false,
                                    isManualFocus = false,
                                    whiteBalanceMode = WhiteBalanceMode.AUTO,
                                    evIndex = 0,
                                )
                            }
                        },
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = DarkSurfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.primary,
                        ),
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.RestartAlt,
                            contentDescription = "Reset All Pro to Auto",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

private data class ProChipData(
    val id: String,
    val label: String,
    val value: String,
    val isManual: Boolean,
)

@Composable
private fun ProControlHeader(
    control: String,
    proSettings: ProSettings,
    hardwareCapabilities: ProHardwareCapabilities,
    onResetToAuto: () -> Unit,
) {
    val isManual = when (control) {
        "ISO" -> proSettings.isManualIso
        "S" -> proSettings.isManualShutter
        "WB" -> proSettings.whiteBalanceMode != WhiteBalanceMode.AUTO
        "AF" -> proSettings.isManualFocus
        "EV" -> proSettings.evIndex != 0
        else -> false
    }

    val displayValue = when (control) {
        "EV" -> String.format(Locale.US, "%+.1f EV", proSettings.evIndex * hardwareCapabilities.evStep)
        "ISO" -> if (proSettings.isManualIso) "ISO ${proSettings.iso}" else "AUTO (100-6400)"
        "S" -> formatShutterSpeed(proSettings.shutterSpeedNs, proSettings.isManualShutter)
        "WB" -> when (proSettings.whiteBalanceMode) {
            WhiteBalanceMode.AUTO -> "AUTO AWB"
            WhiteBalanceMode.CUSTOM -> "${proSettings.whiteBalanceKelvin}K Custom"
            else -> "${proSettings.whiteBalanceKelvin}K ${proSettings.whiteBalanceMode.label}"
        }
        "AF" -> if (proSettings.isManualFocus) {
            if (proSettings.focusDistanceDiopters == 0f) "Infinity (∞)"
            else String.format(Locale.US, "%.1fd (~%.1fm)", proSettings.focusDistanceDiopters, 1f / proSettings.focusDistanceDiopters.coerceAtLeast(0.1f))
        } else "AUTO (AF-C Continuous)"
        else -> ""
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = when (control) {
                    "EV" -> "EXPOSURE COMPENSATION"
                    "ISO" -> "ISO SENSITIVITY"
                    "S" -> "SHUTTER SPEED"
                    "WB" -> "WHITE BALANCE"
                    "AF" -> "MANUAL FOCUS"
                    else -> control
                },
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                letterSpacing = 0.5.sp,
            )
            Text(
                text = displayValue,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )
        }

        if (isManual) {
            TextButton(
                onClick = onResetToAuto,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                modifier = Modifier.height(28.dp),
            ) {
                Text(
                    text = "AUTO",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun EvSliderControl(
    evIndex: Int,
    evRange: IntRange,
    onEvChanged: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("-3", fontSize = 10.sp, color = Color.Gray)
        Slider(
            value = evIndex.toFloat(),
            onValueChange = { onEvChanged(it.roundToInt()) },
            valueRange = evRange.first.toFloat()..evRange.last.toFloat(),
            steps = ((evRange.last - evRange.first) - 1).coerceAtLeast(0),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
            ),
            modifier = Modifier.weight(1f),
        )
        Text("+3", fontSize = 10.sp, color = Color.Gray)
    }
}

@Composable
private fun IsoSliderControl(
    currentIso: Int,
    isManual: Boolean,
    isoRange: IntRange,
    onIsoChanged: (Int, Boolean) -> Unit,
) {
    val presets = listOf(100, 200, 400, 800, 1600, 3200, 6400)

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // Quick Presets Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilterChip(
                selected = !isManual,
                onClick = { onIsoChanged(currentIso, false) },
                label = { Text("AUTO", fontSize = 11.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            )
            presets.forEach { iso ->
                val isSelected = isManual && currentIso == iso
                FilterChip(
                    selected = isSelected,
                    onClick = { onIsoChanged(iso, true) },
                    label = { Text("$iso", fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                )
            }
        }

        // Continuous Slider
        Slider(
            value = currentIso.toFloat().coerceIn(isoRange.first.toFloat(), isoRange.last.toFloat()),
            onValueChange = { onIsoChanged(it.roundToInt(), true) },
            valueRange = isoRange.first.toFloat()..isoRange.last.toFloat().coerceAtMost(6400f),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
            ),
        )
    }
}

@Composable
private fun ShutterSliderControl(
    currentNs: Long,
    isManual: Boolean,
    onShutterChanged: (Long, Boolean) -> Unit,
) {
    // Standard photographic shutter stops (in nanoseconds)
    val shutterStops = listOf(
        "1/4000" to 250_000L,
        "1/2000" to 500_000L,
        "1/1000" to 1_000_000L,
        "1/500" to 2_000_000L,
        "1/250" to 4_000_000L,
        "1/125" to 8_000_000L,
        "1/60" to 16_666_667L,
        "1/30" to 33_333_333L,
        "1/15" to 66_666_667L,
        "1/8" to 125_000_000L,
        "1/4" to 250_000_000L,
        "1/2s" to 500_000_000L,
        "1s" to 1_000_000_000L,
        "2s" to 2_000_000_000L,
        "4s" to 4_000_000_000L,
        "8s" to 8_000_000_000L,
        "15s" to 15_000_000_000L,
        "32s" to 32_000_000_000L,
    )

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilterChip(
                selected = !isManual,
                onClick = { onShutterChanged(currentNs, false) },
                label = { Text("AUTO", fontSize = 11.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            )
            shutterStops.forEach { (label, ns) ->
                val isSelected = isManual && currentNs == ns
                FilterChip(
                    selected = isSelected,
                    onClick = { onShutterChanged(ns, true) },
                    label = { Text(label, fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                )
            }
        }
    }
}

@Composable
private fun WhiteBalanceControl(
    currentMode: WhiteBalanceMode,
    currentKelvin: Int,
    onWbChanged: (WhiteBalanceMode, Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            WhiteBalanceMode.entries.forEach { mode ->
                val isSelected = currentMode == mode
                FilterChip(
                    selected = isSelected,
                    onClick = { onWbChanged(mode, mode.kelvin) },
                    label = { Text(mode.label, fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                )
            }
        }

        // Kelvin Fine Tuning Slider
        Slider(
            value = currentKelvin.toFloat().coerceIn(2500f, 9000f),
            onValueChange = { onWbChanged(WhiteBalanceMode.CUSTOM, it.roundToInt()) },
            valueRange = 2500f..9000f,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
            ),
        )
    }
}

@Composable
private fun FocusControl(
    currentDistance: Float,
    isManual: Boolean,
    maxDistance: Float,
    onFocusChanged: (Float, Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilterChip(
                selected = !isManual,
                onClick = { onFocusChanged(0f, false) },
                label = { Text("AF-C", fontSize = 11.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            )
            FilterChip(
                selected = isManual && currentDistance == 0f,
                onClick = { onFocusChanged(0f, true) },
                label = { Text("Infinity (∞)", fontSize = 11.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            )
            FilterChip(
                selected = isManual && currentDistance >= maxDistance * 0.8f,
                onClick = { onFocusChanged(maxDistance, true) },
                label = { Text("Macro", fontSize = 11.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            )
        }

        Slider(
            value = currentDistance.coerceIn(0f, maxDistance),
            onValueChange = { onFocusChanged(it, true) },
            valueRange = 0f..maxDistance,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
            ),
        )
    }
}

private fun formatShutterSpeed(ns: Long, isManual: Boolean): String {
    if (!isManual) return "AUTO"
    val sec = ns / 1_000_000_000.0
    return if (sec >= 1.0) {
        String.format(Locale.US, "%.1fs", sec)
    } else {
        val denom = (1.0 / sec).roundToInt()
        "1/${denom}s"
    }
}

@Composable
fun LiveRgbHistogram(
    histogram: HistogramData,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color.Black.copy(alpha = 0.70f),
        modifier = modifier
            .width(110.dp)
            .height(54.dp)
            .padding(4.dp),
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(4.dp)) {
            val w = size.width
            val h = size.height

            fun drawCurve(bins: FloatArray, color: Color) {
                if (bins.isEmpty()) return
                val path = Path()
                for (i in bins.indices) {
                    val x = (i / 255f) * w
                    val y = h - (bins[i].coerceIn(0f, 1f) * h)
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(
                    path = path,
                    color = color,
                    style = Stroke(width = 1.2.dp.toPx()),
                    blendMode = BlendMode.Screen,
                )
            }

            drawCurve(histogram.lumaBins, Color.White.copy(alpha = 0.8f))
            drawCurve(histogram.rBins, Color(0xFFFF453A).copy(alpha = 0.75f))
            drawCurve(histogram.gBins, Color(0xFF30D158).copy(alpha = 0.75f))
            drawCurve(histogram.bBins, Color(0xFF0A84FF).copy(alpha = 0.75f))
        }
    }
}
