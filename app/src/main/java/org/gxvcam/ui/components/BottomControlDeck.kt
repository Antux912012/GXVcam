package org.gxvcam.ui.components

import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.graphics.Bitmap
import android.os.Build
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
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
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Mode Carousel
        ModeCarousel(
            currentMode = currentMode,
            onModeSelected = { mode ->
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onModeSelected(mode)
            }
        )

        // 3-Column Action Deck
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left Column: Gallery Thumbnail Button
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = DarkSurfaceContainerHigh,
                tonalElevation = 4.dp,
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onOpenGallery()
                    }
            ) {
                val context = LocalContext.current
                var thumbnailBitmap by remember(lastCapturedUri) { mutableStateOf<Bitmap?>(null) }

                LaunchedEffect(lastCapturedUri) {
                    if (lastCapturedUri != null) {
                        thumbnailBitmap = withContext(Dispatchers.IO) {
                            try {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                    context.contentResolver.loadThumbnail(lastCapturedUri, Size(120, 120), null)
                                } else {
                                    @Suppress("DEPRECATION")
                                    android.provider.MediaStore.Images.Thumbnails.getThumbnail(
                                        context.contentResolver,
                                        android.content.ContentUris.parseId(lastCapturedUri),
                                        android.provider.MediaStore.Images.Thumbnails.MINI_KIND,
                                        null
                                    )
                                }
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

            // Center Column: Primary Morphing Shutter Button
            ExpressiveShutterButton(
                currentMode = currentMode,
                isRecording = isRecording,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onShutterClick()
                }
            )

            // Right Column: FilledTonalIconButton Camera Facing Flip
            var flipAngle by remember { mutableFloatStateOf(0f) }
            val animatedRotation by animateFloatAsState(
                targetValue = flipAngle,
                animationSpec = GXVMotion.spatialSmooth(),
                label = "flipRotation"
            )

            FilledTonalIconButton(
                onClick = {
                    flipAngle += 180f
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onFlipCamera()
                },
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = DarkSurfaceContainerHigh,
                    contentColor = Color.White
                ),
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Cameraswitch,
                    contentDescription = "Flip Camera",
                    modifier = Modifier
                        .size(28.dp)
                        .rotate(animatedRotation)
                )
            }
        }
    }
}

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
        targetValue = if (isPressed) 0.86f else 1.0f,
        animationSpec = GXVMotion.spatialBouncy(),
        label = "shutterScale"
    )

    val innerShapeCorner by animateDpAsState(
        targetValue = if (isRecording) 12.dp else 36.dp,
        animationSpec = GXVMotion.spatialBouncy(),
        label = "shutterShapeCorner"
    )

    val innerSize by animateDpAsState(
        targetValue = if (isRecording) 36.dp else 60.dp,
        animationSpec = GXVMotion.spatialBouncy(),
        label = "shutterInnerSize"
    )

    val innerColor by animateColorAsState(
        targetValue = when {
            currentMode == CameraMode.VIDEO || isRecording -> Color(0xFFFF3B30)
            else -> MaterialTheme.colorScheme.primary
        },
        animationSpec = GXVMotion.fastEffects(),
        label = "shutterColor"
    )

    Box(
        modifier = modifier
            .size(86.dp)
            .scale(scale)
            .border(4.dp, Color.White.copy(alpha = 0.95f), CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(innerSize.coerceAtLeast(0.dp))
                .clip(RoundedCornerShape(innerShapeCorner.coerceAtLeast(0.dp)))
                .background(innerColor)
        )
    }
}

@Composable
fun ModeCarousel(
    currentMode: CameraMode,
    onModeSelected: (CameraMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val modes = listOf(
        CameraMode.PRO,
        CameraMode.NIGHT,
        CameraMode.PHOTO,
        CameraMode.VIDEO,
        CameraMode.PORTRAIT
    )

    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        contentPadding = PaddingValues(horizontal = 16.dp)
    ) {
        items(modes) { mode ->
            val isSelected = mode == currentMode
            Box(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .clickable { onModeSelected(mode) }
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = mode.name,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.65f),
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 14.sp
                    )
                    if (isSelected) {
                        Box(
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .size(width = 16.dp, height = 3.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                }
            }
        }
    }
}
