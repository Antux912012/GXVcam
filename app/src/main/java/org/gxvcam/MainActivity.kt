package org.gxvcam

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.view.KeyEvent
import androidx.activity.viewModels
import org.gxvcam.camera.GXVcamController
import org.gxvcam.data.GXVcamPreferencesRepository
import org.gxvcam.sensors.GyroMotionTracker
import org.gxvcam.sensors.HorizonSensorManager
import org.gxvcam.ui.GXVcamScreen
import org.gxvcam.ui.state.GXVcamEvent
import org.gxvcam.ui.state.GXVcamViewModel
import org.gxvcam.ui.state.VolumeKeyAction
import org.gxvcam.ui.theme.DarkBackground
import org.gxvcam.ui.theme.GXVcamTheme

class MainActivity : ComponentActivity() {

    private val gxvViewModel: GXVcamViewModel by viewModels()
    private lateinit var cameraController: GXVcamController
    private lateinit var gyroMotionTracker: GyroMotionTracker

    private val requiredPermissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
    )

    private val mediaReadPermissions: Array<String>
        get() = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    private val launchPermissions: Array<String>
        get() = requiredPermissions + mediaReadPermissions

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Keep OLED screen awake during camera viewfinder framing and capture
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        cameraController = GXVcamController(applicationContext, lifecycleOwner = this)
        gyroMotionTracker = GyroMotionTracker(applicationContext)
        cameraController.gyroMotionTracker = gyroMotionTracker

        val preferencesRepository = GXVcamPreferencesRepository(applicationContext)
        gxvViewModel.attachPreferencesRepository(preferencesRepository)

        setContent {
            val uiState by gxvViewModel.uiState.collectAsStateWithLifecycle()

            GXVcamTheme(accentTheme = uiState.manualSettings.accentTheme) {
                var hasPermissions by remember {
                    mutableStateOf(allPermissionsGranted())
                }

                val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions(),
                ) { _ ->
                    hasPermissions = allPermissionsGranted()
                    if (hasPermissions) {
                        refreshLatestMediaThumbnail()
                    }
                }

                LaunchedEffect(hasPermissions) {
                    if (hasPermissions) {
                        val missingMedia = mediaReadPermissions.any {
                            ContextCompat.checkSelfPermission(baseContext, it) != PackageManager.PERMISSION_GRANTED
                        }
                        if (missingMedia) {
                            permissionLauncher.launch(mediaReadPermissions)
                        }
                        refreshLatestMediaThumbnail()
                    }
                }

                if (hasPermissions) {

                    val horizonSensorManager = remember {
                        HorizonSensorManager(
                            context = applicationContext,
                            onTiltChanged = { rollAngle ->
                                gxvViewModel.onEvent(GXVcamEvent.OnLevelHorizonUpdated(rollAngle))
                            },
                            onOrientationChanged = { cardinalAngle, surfaceRotation ->
                                cameraController.updateDeviceOrientation(surfaceRotation, cardinalAngle)
                                gxvViewModel.onEvent(GXVcamEvent.OnDeviceOrientationChanged(surfaceRotation, cardinalAngle))
                            },
                            onPlanarChanged = { planar ->
                                gxvViewModel.onEvent(GXVcamEvent.UpdateHorizonPlanar(planar))
                            }
                        )
                    }

                    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
                    DisposableEffect(lifecycleOwner) {
                        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                            when (event) {
                                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> {
                                    horizonSensorManager.start()
                                    gyroMotionTracker.stop()
                                    cameraController.virtualEvsSimulator.stop()
                                    refreshLatestMediaThumbnail()
                                }
                                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> {
                                    horizonSensorManager.stop()
                                    gyroMotionTracker.stop()
                                    cameraController.virtualEvsSimulator.stop()
                                    cameraController.proAudioInputRouter.stopMonitoring()
                                }
                                else -> {}
                            }
                        }
                        lifecycleOwner.lifecycle.addObserver(observer)
                        onDispose {
                            lifecycleOwner.lifecycle.removeObserver(observer)
                            horizonSensorManager.stop()
                            gyroMotionTracker.stop()
                            cameraController.virtualEvsSimulator.stop()
                        }
                    }

                    GXVcamScreen(
                        viewModel = gxvViewModel,
                        onOpenGallery = { openGallery(uiState.lastCapturedUri) },
                        viewfinderContent = {
                            AndroidView(
                                factory = { _ ->
                                    val container = cameraController.viewfinderContainer
                                    (container.parent as? android.view.ViewGroup)?.removeView(container)
                                    container.also {
                                        gxvViewModel.attachController(
                                            controller = cameraController,
                                            lifecycleOwner = this@MainActivity,
                                            surfaceProvider = cameraController.previewView.surfaceProvider,
                                        )
                                    }
                                },
                                modifier = Modifier.fillMaxSize(),
                            )
                        },
                        secondaryViewfinderContent = {
                            AndroidView(
                                factory = { _ ->
                                    val pv = cameraController.secondaryPreviewView
                                    (pv.parent as? android.view.ViewGroup)?.removeView(pv)
                                    pv
                                },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    )
                } else {
                    PermissionRequestScreen {
                        permissionLauncher.launch(launchPermissions)
                    }
                }
            }
        }
    }

    private fun refreshLatestMediaThumbnail() {
        val hasMediaAccess = mediaReadPermissions.any {
            ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
        }
        if (!hasMediaAccess) return

        Thread {
            val uri = queryLatestMediaUri()
            if (uri != null) {
                runOnUiThread {
                    gxvViewModel.onEvent(GXVcamEvent.OnPhotoCaptured(uri))
                }
            }
        }.start()
    }

    private fun queryLatestMediaUri(): android.net.Uri? {
        val projection = arrayOf(
            android.provider.MediaStore.MediaColumns._ID,
            android.provider.MediaStore.MediaColumns.DATE_ADDED
        )
        val candidates = mutableListOf<Pair<android.net.Uri, Long>>()

        fun queryUri(baseUri: android.net.Uri, selection: String?, args: Array<String>?): Pair<android.net.Uri, Long>? {
            return runCatching {
                contentResolver.query(
                    baseUri,
                    projection,
                    selection,
                    args,
                    "${android.provider.MediaStore.MediaColumns.DATE_ADDED} DESC"
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val id = cursor.getLong(cursor.getColumnIndexOrThrow(android.provider.MediaStore.MediaColumns._ID))
                        val date = cursor.getLong(cursor.getColumnIndexOrThrow(android.provider.MediaStore.MediaColumns.DATE_ADDED))
                        android.content.ContentUris.withAppendedId(baseUri, id) to date
                    } else null
                }
            }.getOrNull()
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            queryUri(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                "${android.provider.MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                arrayOf("%DCIM/GXVcam%")
            )?.let { candidates.add(it) }
            queryUri(
                android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                "${android.provider.MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                arrayOf("%DCIM/GXVcam%")
            )?.let { candidates.add(it) }
        }

        if (candidates.isEmpty()) {
            queryUri(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, null, null)?.let { candidates.add(it) }
            queryUri(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, null, null)?.let { candidates.add(it) }
        }

        return candidates.maxByOrNull { it.second }?.first
    }

    private fun openGallery(lastCapturedUri: android.net.Uri? = null) {
        val photosPkg = "com.google.android.apps.photos"
        val targetUri = lastCapturedUri ?: queryLatestMediaUri()
        if (targetUri != null) {
            val mimeType = runCatching { contentResolver.getType(targetUri) }.getOrNull() ?: "image/*"
            // 1. Prefer Google Photos viewing the exact captured photo or video
            try {
                val photosIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(targetUri, mimeType)
                    setPackage(photosPkg)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(photosIntent)
                return
            } catch (_: Exception) {}

            // 2. Fallback to any system viewer for that URI
            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(targetUri, mimeType)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
                return
            } catch (_: Exception) {}
        }

        // 3. Open Google Photos app directly if no URI yet or viewer failed
        try {
            val launchPhotos = packageManager.getLaunchIntentForPackage(photosPkg)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (launchPhotos != null) {
                startActivity(launchPhotos)
                return
            }
        } catch (_: Exception) {}

        // 4. Generic gallery fallback
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                type = "image/*"
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        } catch (_: Exception) {
            try {
                val intent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_APP_GALLERY)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(intent)
            } catch (_: Exception) {}
        }
    }

    private fun allPermissionsGranted(): Boolean {
        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            val action = gxvViewModel.uiState.value.manualSettings.volumeKeyAction
            when (action) {
                VolumeKeyAction.ZOOM -> {
                    val isZoomIn = (keyCode == KeyEvent.KEYCODE_VOLUME_UP)
                    if (event?.repeatCount == 0) {
                        gxvViewModel.onEvent(GXVcamEvent.OnVolumeZoomHold(isZoomIn = isZoomIn))
                    }
                    return true
                }
                VolumeKeyAction.SHUTTER -> {
                    if (event?.repeatCount == 0) {
                        val state = gxvViewModel.uiState.value
                        if (state.currentMode == org.gxvcam.ui.state.CameraMode.VIDEO) {
                            if (state.isRecording) {
                                gxvViewModel.onEvent(GXVcamEvent.OnStopRecording)
                            } else {
                                gxvViewModel.onEvent(GXVcamEvent.OnStartRecording)
                            }
                        } else {
                            gxvViewModel.onEvent(GXVcamEvent.OnShutterClicked)
                        }
                    }
                    return true
                }
                VolumeKeyAction.VOLUME -> {
                    return super.onKeyDown(keyCode, event)
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            val action = gxvViewModel.uiState.value.manualSettings.volumeKeyAction
            if (action == VolumeKeyAction.ZOOM) {
                gxvViewModel.onEvent(GXVcamEvent.OnVolumeZoomReleased)
                return true
            } else if (action == VolumeKeyAction.SHUTTER) {
                return true
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraController.release()
    }
}

@Composable
fun PermissionRequestScreen(onRequestPermissions: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "GXVcam requires Camera & Audio permissions to capture photos and video.",
                color = androidx.compose.ui.graphics.Color.White,
                style = MaterialTheme.typography.bodyLarge,
            )
            Button(onClick = onRequestPermissions) {
                Text("Grant Permissions")
            }
        }
    }
}
