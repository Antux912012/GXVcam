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

    private val requiredPermissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Keep OLED screen awake during camera viewfinder framing and capture
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        cameraController = GXVcamController(applicationContext, lifecycleOwner = this)

        setContent {
            val uiState by gxvViewModel.uiState.collectAsStateWithLifecycle()

            GXVcamTheme(accentTheme = uiState.manualSettings.accentTheme) {
                var hasPermissions by remember {
                    mutableStateOf(allPermissionsGranted())
                }

                val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions(),
                ) { permissions ->
                    hasPermissions = permissions.values.all { it }
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
                            }
                        )
                    }

                    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
                    DisposableEffect(lifecycleOwner) {
                        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                            when (event) {
                                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> horizonSensorManager.start()
                                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> horizonSensorManager.stop()
                                else -> {}
                            }
                        }
                        lifecycleOwner.lifecycle.addObserver(observer)
                        onDispose {
                            lifecycleOwner.lifecycle.removeObserver(observer)
                            horizonSensorManager.stop()
                        }
                    }

                    GXVcamScreen(
                        viewModel = gxvViewModel,
                        onOpenGallery = { openGallery(uiState.lastCapturedUri) },
                        viewfinderContent = {
                            AndroidView(
                                factory = { _ ->
                                    val pv = cameraController.previewView
                                    (pv.parent as? android.view.ViewGroup)?.removeView(pv)
                                    pv.also {
                                        gxvViewModel.attachController(
                                            controller = cameraController,
                                            lifecycleOwner = this@MainActivity,
                                            surfaceProvider = it.surfaceProvider,
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
                        permissionLauncher.launch(requiredPermissions)
                    }
                }
            }
        }
    }

    private fun openGallery(lastCapturedUri: android.net.Uri? = null) {
        if (lastCapturedUri != null) {
            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(lastCapturedUri, "image/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
                return
            } catch (_: Exception) {}
        }

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
                        gxvViewModel.onEvent(GXVcamEvent.OnShutterClicked)
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
