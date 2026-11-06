package org.gxvcam.ui.components

import android.graphics.Bitmap
import android.net.Uri
import android.util.Size
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.gxvcam.ui.state.CameraMode
import org.gxvcam.ui.state.GXVcamUiState
import org.gxvcam.ui.theme.DarkSurfaceContainerHigh
import org.gxvcam.ui.theme.GXVMotion

@Composable
fun BottomControlDeck(
    uiState: GXVcamUiState,
    onShutterClick: () -> Unit,
    onFlipCamera: () -> Unit,
    onSelectMode: (CameraMode) -> Unit,
    onOpenGallery: () -> Unit,
    isFull: Boolean = false,
    modifier: Modifier = Modifier
) {
    BottomControlDeck(
        currentMode = uiState.currentMode,
        isRecording = uiState.isRecording,
        lastCapturedUri = uiState.lastCapturedUri,
        onModeSelected = onSelectMode,
        onShutterClick = onShutterClick,
        onFlipCamera = onFlipCamera,
        onOpenGallery = onOpenGallery,
        isFull = isFull,
        modifier = modifier
    )
}

@Composable
fun BottomControlDeck(
    currentMode: CameraMode,
    isRecording: Boolean,
    lastCapturedUri: Uri? = null,
    onModeSelected: (CameraMode) -> Unit,
    onShutterClick: () -> Unit,
    onFlipCamera: () -> Unit,
    onOpenGallery: () -> Unit,
    isFull: Boolean = false,
    modifier: Modifier = Modifier
) {
    val tactile = rememberTactileFeedback()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Material 3 Expressive Mode Carousel with Sliding Capsule Pill
        ExpressiveModeCarousel(
            currentMode = currentMode,
            onModeSelected = { mode ->
                tactile.triggerSnap()
                onModeSelected(mode)
            }
        )

        // 3-Column Ergonomic Action Deck
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left Column: Gallery Thumbnail with M3 Expressive Press Physics
            val galleryInteractionSource = remember { MutableInteractionSource() }
            val isGalleryPressed by galleryInteractionSource.collectIsPressedAsState()
            val galleryScale by animateFloatAsState(
                targetValue = if (isGalleryPressed) 0.90f else 1.0f,
                animationSpec = GXVMotion.shutterPressSpring,
                label = "galleryScale"
            )

            Surface(
                shape = RoundedCornerShape(20.dp),
                color = if (isFull) Color(0x55000000) else DarkSurfaceContainerHigh,
                border = BorderStroke(1.dp, Color.White.copy(alpha = if (isFull) 0.25f else 0.12f)),
                tonalElevation = if (isFull) 0.dp else 4.dp,
                modifier = Modifier
                    .size(56.dp)
                    .scale(galleryScale)
                    .clip(RoundedCornerShape(20.dp))
                    .clickable(
                        interactionSource = galleryInteractionSource,
                        indication = null,
                        onClick = {
                            tactile.triggerSnap()
                            onOpenGallery()
                        }
                    )
            ) {
                val context = LocalContext.current
                var thumbnailBitmap by remember(lastCapturedUri) { mutableStateOf<Bitmap?>(null) }

                LaunchedEffect(lastCapturedUri) {
                    if (lastCapturedUri != null) {
                        thumbnailBitmap = withContext(Dispatchers.IO) {
                            try {
                                context.contentResolver.loadThumbnail(lastCapturedUri, Size(140, 140), null)
                            } catch (e: Exception) {
                                null
                            }
                        }
                    } else {
                        thumbnailBitmap = null
                    }
                }

                Box(contentAlignment = Alignment.Center) {
                    val bitmap = thumbnailBitmap
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "Gallery",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.PhotoLibrary,
                            contentDescription = "Gallery",
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }

            // Center Column: Primary Google Camera Morphing Shutter Button
            ExpressiveShutterButton(
                currentMode = currentMode,
                isRecording = isRecording,
                onClick = {
                    tactile.triggerSnap()
                    onShutterClick()
                }
            )

            // Right Column: Camera Flip Button with Rotation Spring and Press Damping
            val flipInteractionSource = remember { MutableInteractionSource() }
            val isFlipPressed by flipInteractionSource.collectIsPressedAsState()
            val flipScale by animateFloatAsState(
                targetValue = if (isFlipPressed) 0.90f else 1.0f,
                animationSpec = GXVMotion.shutterPressSpring,
                label = "flipScale"
            )

            var flipAngle by remember { mutableFloatStateOf(0f) }
            val animatedRotation by animateFloatAsState(
                targetValue = flipAngle,
                animationSpec = GXVMotion.spatialBouncy(),
                label = "flipRotation"
            )

            FilledTonalIconButton(
                onClick = {
                    flipAngle += 180f
                    tactile.triggerSnap()
                    onFlipCamera()
                },
                interactionSource = flipInteractionSource,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = if (isFull) Color(0x55000000) else DarkSurfaceContainerHigh,
                    contentColor = Color.White
                ),
                modifier = Modifier
                    .size(56.dp)
                    .scale(flipScale)
                    .clip(CircleShape)
                    .then(
                        if (isFull) Modifier.border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape)
                        else Modifier.border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape)
                    )
            ) {
                Icon(
                    imageVector = Icons.Default.Cameraswitch,
                    contentDescription = "Flip Camera",
                    modifier = Modifier
                        .size(26.dp)
                        .rotate(animatedRotation)
                )
            }
        }
    }
}

/**
 * Pixel Camera / Google Camera Expressive Morphing Shutter
 * - Tactile spring scale physics on press
 * - Seamless morphing from circular photo trigger to video recording square
 * - Breathing pulse ring animation during active video recording
 */
@Composable
fun ExpressiveShutterButton(
    currentMode: CameraMode,
    isRecording: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.88f else 1.0f,
        animationSpec = GXVMotion.shutterPressSpring,
        label = "shutterScale"
    )

    // Corner radius: 36.dp (circle) -> 10.dp (rounded recording square)
    val innerShapeCorner by animateDpAsState(
        targetValue = if (isRecording) 10.dp else 36.dp,
        animationSpec = GXVMotion.pillMorph(),
        label = "shutterShapeCorner"
    )

    // Core size: 62.dp (idle) -> 34.dp (recording square)
    val innerSize by animateDpAsState(
        targetValue = if (isRecording) 34.dp else 62.dp,
        animationSpec = GXVMotion.spatialBouncy(),
        label = "shutterInnerSize"
    )

    val innerColor by animateColorAsState(
        targetValue = when {
            currentMode == CameraMode.VIDEO || isRecording -> Color(0xFFFF3B30)
            currentMode == CameraMode.NIGHT -> Color(0xFFFFD600)
            currentMode == CameraMode.PRO -> Color(0xFFFF9100)
            else -> MaterialTheme.colorScheme.primary
        },
        animationSpec = GXVMotion.fastEffects(),
        label = "shutterColor"
    )

    // Video Recording Breathing Animation for the outer ring
    val infiniteTransition = rememberInfiniteTransition(label = "recordingPulse")
    val recordingRingAlpha by if (isRecording) {
        infiniteTransition.animateFloat(
            initialValue = 0.45f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(800, easing = GXVMotion.EmphasizedDecelerateEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "recordingRingAlpha"
        )
    } else {
        remember { mutableFloatStateOf(0.95f) }
    }

    val outerRingColor = if (isRecording) {
        Color(0xFFFF3B30).copy(alpha = recordingRingAlpha)
    } else {
        Color.White.copy(alpha = 0.95f)
    }

    Box(
        modifier = modifier
            .size(86.dp)
            .scale(scale)
            .border(4.5.dp, outerRingColor, CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        // Inner Tactile Morphing Core
        Box(
            modifier = Modifier
                .size(innerSize.coerceAtLeast(0.dp))
                .clip(RoundedCornerShape(innerShapeCorner.coerceAtLeast(0.dp)))
                .background(innerColor)
        )
    }
}

/**
 * Google Camera / GCam Expressive Mode Carousel
 * Displays camera modes in a horizontal pill carousel with a floating sliding indicator pill
 */
@Composable
fun ExpressiveModeCarousel(
    currentMode: CameraMode,
    onModeSelected: (CameraMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val modes = remember {
        listOf(
            CameraMode.PRO,
            CameraMode.NIGHT,
            CameraMode.PHOTO,
            CameraMode.VIDEO,
            CameraMode.PORTRAIT
        )
    }

    val listState = rememberLazyListState()

    // Keep active mode scrolled into view
    val activeIndex = modes.indexOf(currentMode)
    LaunchedEffect(activeIndex) {
        if (activeIndex >= 0) {
            listState.animateScrollToItem(
                index = activeIndex,
                scrollOffset = -150
            )
        }
    }

    LazyRow(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        contentPadding = PaddingValues(horizontal = 24.dp)
    ) {
        items(modes, key = { it.name }) { mode ->
            val isSelected = mode == currentMode

            val textScale by animateFloatAsState(
                targetValue = if (isSelected) 1.05f else 0.95f,
                animationSpec = GXVMotion.spatialBouncy(),
                label = "modeTextScale"
            )

            val containerColor by animateColorAsState(
                targetValue = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                animationSpec = GXVMotion.fastEffects(),
                label = "modeContainerColor"
            )

            val contentColor by animateColorAsState(
                targetValue = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else Color.White.copy(alpha = 0.65f),
                animationSpec = GXVMotion.fastEffects(),
                label = "modeContentColor"
            )

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(containerColor)
                    .clickable { onModeSelected(mode) }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = mode.name,
                    color = contentColor,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    fontSize = 13.sp,
                    letterSpacing = 0.5.sp,
                    modifier = Modifier.scale(textScale)
                )
            }
        }
    }
}
