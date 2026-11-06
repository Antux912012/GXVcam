package org.gxvcam.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.gxvcam.ui.state.HistogramData
import org.gxvcam.ui.state.ProHardwareCapabilities
import org.gxvcam.ui.state.ProLayoutPosition
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
    layoutPosition: ProLayoutPosition = ProLayoutPosition.SIDEBAR_RIGHT,
    isFull: Boolean = false,
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

    val onResetAll: () -> Unit = {
        onProSettingChanged { s ->
            s.copy(
                isManualIso = false,
                isManualShutter = false,
                isManualFocus = false,
                whiteBalanceMode = WhiteBalanceMode.AUTO,
                evIndex = 0,
            )
        }
    }

    when (layoutPosition) {
        ProLayoutPosition.SIDEBAR_RIGHT -> {
            AnimatedVisibility(
                visible = visible,
                enter = slideInHorizontally(
                    animationSpec = GXVMotion.spatialSmooth(),
                    initialOffsetX = { it },
                ) + fadeIn(GXVMotion.fastEffects()),
                exit = slideOutHorizontally(
                    animationSpec = GXVMotion.spatialSmooth(),
                    targetOffsetX = { it },
                ) + fadeOut(GXVMotion.fastEffects()),
                modifier = modifier,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Floating Flyout Card on Left of Rail
                    AnimatedVisibility(
                        visible = activeControl != null,
                        enter = fadeIn(GXVMotion.fastEffects()) + slideInHorizontally(
                            animationSpec = GXVMotion.spatialSmooth(),
                            initialOffsetX = { it / 2 },
                        ),
                        exit = fadeOut(GXVMotion.fastEffects()) + slideOutHorizontally(
                            animationSpec = GXVMotion.spatialSmooth(),
                            targetOffsetX = { it / 2 },
                        ),
                    ) {
                        activeControl?.let { control ->
                            ExpandedControlCard(
                                control = control,
                                proSettings = proSettings,
                                hardwareCapabilities = hardwareCapabilities,
                                onProSettingChanged = onProSettingChanged,
                                onClose = { onControlSelected(null) },
                                isFull = isFull,
                                modifier = Modifier.width(260.dp),
                            )
                        }
                    }

                    // Vertical Rail on Right
                    VerticalChipsRail(
                        chips = chips,
                        activeControl = activeControl,
                        isAnyManual = proSettings.isAnyManual,
                        onControlSelected = onControlSelected,
                        onResetAll = onResetAll,
                        isFull = isFull,
                    )
                }
            }
        }

        ProLayoutPosition.SIDEBAR_LEFT -> {
            AnimatedVisibility(
                visible = visible,
                enter = slideInHorizontally(
                    animationSpec = GXVMotion.spatialSmooth(),
                    initialOffsetX = { -it },
                ) + fadeIn(GXVMotion.fastEffects()),
                exit = slideOutHorizontally(
                    animationSpec = GXVMotion.spatialSmooth(),
                    targetOffsetX = { -it },
                ) + fadeOut(GXVMotion.fastEffects()),
                modifier = modifier,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Vertical Rail on Left
                    VerticalChipsRail(
                        chips = chips,
                        activeControl = activeControl,
                        isAnyManual = proSettings.isAnyManual,
                        onControlSelected = onControlSelected,
                        onResetAll = onResetAll,
                        isFull = isFull,
                    )

                    // Floating Flyout Card on Right of Rail
                    AnimatedVisibility(
                        visible = activeControl != null,
                        enter = fadeIn(GXVMotion.fastEffects()) + slideInHorizontally(
                            animationSpec = GXVMotion.spatialSmooth(),
                            initialOffsetX = { -it / 2 },
                        ),
                        exit = fadeOut(GXVMotion.fastEffects()) + slideOutHorizontally(
                            animationSpec = GXVMotion.spatialSmooth(),
                            targetOffsetX = { -it / 2 },
                        ),
                    ) {
                        activeControl?.let { control ->
                            ExpandedControlCard(
                                control = control,
                                proSettings = proSettings,
                                hardwareCapabilities = hardwareCapabilities,
                                onProSettingChanged = onProSettingChanged,
                                onClose = { onControlSelected(null) },
                                isFull = isFull,
                                modifier = Modifier.width(260.dp),
                            )
                        }
                    }
                }
            }
        }

        ProLayoutPosition.TOP_BAR -> {
            AnimatedVisibility(
                visible = visible,
                enter = slideInVertically(
                    animationSpec = GXVMotion.spatialSmooth(),
                    initialOffsetY = { -it },
                ) + fadeIn(GXVMotion.fastEffects()),
                exit = slideOutVertically(
                    animationSpec = GXVMotion.spatialSmooth(),
                    targetOffsetY = { -it },
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
                    HorizontalChipsRail(
                        chips = chips,
                        activeControl = activeControl,
                        isAnyManual = proSettings.isAnyManual,
                        onControlSelected = onControlSelected,
                        onResetAll = onResetAll,
                        isFull = isFull,
                    )

                    AnimatedVisibility(
                        visible = activeControl != null,
                        enter = fadeIn(GXVMotion.fastEffects()) + slideInVertically(
                            animationSpec = GXVMotion.spatialSmooth(),
                            initialOffsetY = { -it / 2 },
                        ),
                        exit = fadeOut(GXVMotion.fastEffects()) + slideOutVertically(
                            animationSpec = GXVMotion.spatialSmooth(),
                            targetOffsetY = { -it / 2 },
                        ),
                    ) {
                        activeControl?.let { control ->
                            ExpandedControlCard(
                                control = control,
                                proSettings = proSettings,
                                hardwareCapabilities = hardwareCapabilities,
                                onProSettingChanged = onProSettingChanged,
                                onClose = { onControlSelected(null) },
                                isFull = isFull,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }

        ProLayoutPosition.BOTTOM_DOCK -> {
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
                    AnimatedVisibility(
                        visible = activeControl != null,
                        enter = fadeIn(GXVMotion.fastEffects()) + slideInVertically(
                            animationSpec = GXVMotion.spatialSmooth(),
                            initialOffsetY = { it / 2 },
                        ),
                        exit = fadeOut(GXVMotion.fastEffects()) + slideOutVertically(
                            animationSpec = GXVMotion.spatialSmooth(),
                            targetOffsetY = { it / 2 },
                        ),
                    ) {
                        activeControl?.let { control ->
                            ExpandedControlCard(
                                control = control,
                                proSettings = proSettings,
                                hardwareCapabilities = hardwareCapabilities,
                                onProSettingChanged = onProSettingChanged,
                                onClose = { onControlSelected(null) },
                                isFull = isFull,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }

                    HorizontalChipsRail(
                        chips = chips,
                        activeControl = activeControl,
                        isAnyManual = proSettings.isAnyManual,
                        onControlSelected = onControlSelected,
                        onResetAll = onResetAll,
                        isFull = isFull,
                    )
                }
            }
        }
    }
}

@Composable
private fun VerticalChipsRail(
    chips: List<ProChipData>,
    activeControl: String?,
    isAnyManual: Boolean,
    onControlSelected: (String?) -> Unit,
    onResetAll: () -> Unit,
    isFull: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = if (isFull) Color(0x55000000) else DarkSurfaceContainerHigh.copy(alpha = 0.90f),
        tonalElevation = if (isFull) 0.dp else 6.dp,
        border = BorderStroke(1.dp, if (isFull) Color.White.copy(alpha = 0.20f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.30f)),
        modifier = modifier.width(62.dp),
    ) {
        Column(
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            chips.forEach { chip ->
                val isSelected = activeControl == chip.id
                ProChipItem(
                    chip = chip,
                    isSelected = isSelected,
                    onClick = { onControlSelected(if (isSelected) null else chip.id) },
                    isFull = isFull,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                )
            }

            if (isAnyManual) {
                FilledTonalIconButton(
                    onClick = onResetAll,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.primary,
                    ),
                    modifier = Modifier.size(38.dp),
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

@Composable
private fun HorizontalChipsRail(
    chips: List<ProChipData>,
    activeControl: String?,
    isAnyManual: Boolean,
    onControlSelected: (String?) -> Unit,
    onResetAll: () -> Unit,
    isFull: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        chips.forEach { chip ->
            val isSelected = activeControl == chip.id
            ProChipItem(
                chip = chip,
                isSelected = isSelected,
                onClick = { onControlSelected(if (isSelected) null else chip.id) },
                isFull = isFull,
                modifier = Modifier
                    .weight(1f)
                    .height(50.dp),
            )
        }

        if (isAnyManual) {
            FilledTonalIconButton(
                onClick = onResetAll,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = DarkSurfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.primary,
                ),
                modifier = Modifier.size(38.dp),
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

@Composable
private fun ProChipItem(
    chip: ProChipData,
    isSelected: Boolean,
    onClick: () -> Unit,
    isFull: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = when {
            isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = if (isFull) 0.88f else 1.0f)
            chip.isManual -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = if (isFull) 0.85f else 0.85f)
            else -> if (isFull) Color(0x33000000) else DarkSurfaceContainerHigh
        },
        border = if (isFull && !isSelected && !chip.isManual) BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)) else null,
        tonalElevation = if (isFull) 0.dp else if (isSelected || chip.isManual) 4.dp else 2.dp,
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .then(
                if (chip.isManual && !isSelected) {
                    Modifier.border(1.2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp))
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 5.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = chip.label,
                color = when {
                    isSelected -> MaterialTheme.colorScheme.onPrimaryContainer
                    chip.isManual -> MaterialTheme.colorScheme.primary
                    else -> Color.White.copy(alpha = 0.70f)
                },
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp,
            )
            Spacer(modifier = Modifier.height(2.dp))
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

@Composable
private fun ExpandedControlCard(
    control: String,
    proSettings: ProSettings,
    hardwareCapabilities: ProHardwareCapabilities,
    onProSettingChanged: ((ProSettings) -> ProSettings) -> Unit,
    onClose: () -> Unit,
    isFull: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = if (isFull) Color(0x66000000) else DarkSurfaceContainerHigh.copy(alpha = 0.95f),
        tonalElevation = if (isFull) 0.dp else 8.dp,
        border = BorderStroke(1.dp, if (isFull) Color.White.copy(alpha = 0.22f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
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
                onClose = onClose,
            )

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
    onClose: () -> Unit,
) {
    val isManual = when (control) {
        "ISO" -> proSettings.isManualIso
        "S" -> proSettings.isManualShutter
        "WB" -> proSettings.whiteBalanceMode != WhiteBalanceMode.AUTO
        "AF" -> proSettings.isManualFocus
        "EV" -> proSettings.evIndex != 0
        else -> false
    }

    val controlTitle = when (control) {
        "EV" -> "EXPOSURE (EV)"
        "ISO" -> "ISO SENSITIVITY"
        "S" -> "SHUTTER SPEED"
        "WB" -> "WHITE BALANCE"
        "AF" -> "MANUAL FOCUS"
        else -> control
    }

    val displayValue = when (control) {
        "EV" -> String.format(Locale.US, "%+.1f EV", proSettings.evIndex * hardwareCapabilities.evStep)
        "ISO" -> if (proSettings.isManualIso) "ISO ${proSettings.iso}" else "AUTO"
        "S" -> formatShutterSpeed(proSettings.shutterSpeedNs, proSettings.isManualShutter)
        "WB" -> when (proSettings.whiteBalanceMode) {
            WhiteBalanceMode.AUTO -> "AUTO AWB"
            WhiteBalanceMode.CUSTOM -> "${proSettings.whiteBalanceKelvin}K Custom"
            else -> "${proSettings.whiteBalanceKelvin}K • ${proSettings.whiteBalanceMode.label}"
        }
        "AF" -> if (proSettings.isManualFocus) {
            if (proSettings.focusDistanceDiopters == 0f) "Infinity (∞)"
            else String.format(Locale.US, "%.1fd", proSettings.focusDistanceDiopters)
        } else "AUTO (AF-C)"
        else -> ""
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = controlTitle,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                letterSpacing = 0.5.sp,
            )
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (isManual) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            ) {
                Text(
                    text = displayValue,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isManual) MaterialTheme.colorScheme.onPrimaryContainer else Color.White,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (isManual) {
                FilledTonalButton(
                    onClick = onResetToAuto,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.primary,
                    ),
                    modifier = Modifier.height(28.dp),
                ) {
                    Text(
                        text = "AUTO",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            IconButton(
                onClick = onClose,
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close",
                    tint = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(16.dp),
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
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("-3 EV", fontSize = 10.sp, color = Color.Gray)
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
        Text("+3 EV", fontSize = 10.sp, color = Color.Gray)
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

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilterChip(
                selected = !isManual,
                onClick = { onIsoChanged(currentIso, false) },
                label = { Text("AUTO", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
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

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("${isoRange.first}", fontSize = 10.sp, color = Color.Gray)
            Slider(
                value = (if (isManual) currentIso else 100).toFloat().coerceIn(isoRange.first.toFloat(), isoRange.last.toFloat()),
                onValueChange = { onIsoChanged(it.roundToInt(), true) },
                valueRange = isoRange.first.toFloat()..isoRange.last.toFloat(),
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                ),
                modifier = Modifier.weight(1f),
            )
            Text("${isoRange.last}", fontSize = 10.sp, color = Color.Gray)
        }
    }
}

@Composable
private fun ShutterSliderControl(
    currentNs: Long,
    isManual: Boolean,
    onShutterChanged: (Long, Boolean) -> Unit,
) {
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

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilterChip(
                selected = !isManual,
                onClick = { onShutterChanged(currentNs, false) },
                label = { Text("AUTO", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
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
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
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

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("2500K", fontSize = 10.sp, color = Color.Gray)
            Slider(
                value = currentKelvin.toFloat().coerceIn(2500f, 9000f),
                onValueChange = { onWbChanged(WhiteBalanceMode.CUSTOM, it.roundToInt()) },
                valueRange = 2500f..9000f,
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                ),
                modifier = Modifier.weight(1f),
            )
            Text("9000K", fontSize = 10.sp, color = Color.Gray)
        }
    }
}

@Composable
private fun FocusControl(
    currentDistance: Float,
    isManual: Boolean,
    maxDistance: Float,
    onFocusChanged: (Float, Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilterChip(
                selected = !isManual,
                onClick = { onFocusChanged(0f, false) },
                label = { Text("AF-C", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            )
            FilterChip(
                selected = isManual && currentDistance == 0f,
                onClick = { onFocusChanged(0f, true) },
                label = { Text("Infinity ∞", fontSize = 11.sp) },
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

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("∞", fontSize = 12.sp, color = Color.Gray)
            Slider(
                value = currentDistance.coerceIn(0f, maxDistance),
                onValueChange = { onFocusChanged(it, true) },
                valueRange = 0f..maxDistance,
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                ),
                modifier = Modifier.weight(1f),
            )
            Text("Macro", fontSize = 10.sp, color = Color.Gray)
        }
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
    isFull: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isFull) Color(0x40000000) else Color.Black.copy(alpha = 0.70f),
        border = if (isFull) BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)) else null,
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
