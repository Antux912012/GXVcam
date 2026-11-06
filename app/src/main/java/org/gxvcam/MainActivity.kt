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
import androidx.lifecycle.viewmodel.compose.viewModel
import org.gxvcam.camera.GXVcamController
import org.gxvcam.sensors.HorizonSensorManager
import org.gxvcam.ui.GXVcamScreen
import org.gxvcam.ui.state.GXVcamEvent
import org.gxvcam.ui.state.GXVcamViewModel
import org.gxvcam.ui.theme.DarkBackground
import org.gxvcam.ui.theme.GXVcamTheme

class MainActivity : ComponentActivity() {

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
            GXVcamTheme {
                var hasPermissions by remember {
                    mutableStateOf(allPermissionsGranted())
                }

                val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions(),
                ) { permissions ->
                    hasPermissions = permissions.values.all { it }
                }

                if (hasPermissions) {
                    val gxvViewModel: GXVcamViewModel = viewModel()

                    val horizonSensorManager = remember {
                        HorizonSensorManager(applicationContext) { rollAngle ->
                            gxvViewModel.onEvent(GXVcamEvent.OnLevelHorizonUpdated(rollAngle))
                        }
                    }

                    DisposableEffect(Unit) {
                        horizonSensorManager.start()
                        onDispose {
                            horizonSensorManager.stop()
                        }
                    }

                    GXVcamScreen(
                        viewModel = gxvViewModel,
                        onOpenGallery = { openGallery() },
                        viewfinderContent = {
                            AndroidView(
                                factory = { _ ->
                                    cameraController.previewView.also { pv ->
                                        gxvViewModel.attachController(
                                            controller = cameraController,
                                            lifecycleOwner = this@MainActivity,
                                            surfaceProvider = pv.surfaceProvider,
                                        )
                                    }
                                },
                                modifier = Modifier.fillMaxSize(),
                            )
                        },
                    )
                } else {
                    PermissionRequestScreen {
                        permissionLauncher.launch(requiredPermissions)
                    }
                }
            }
        }
    }

    private fun openGallery() {
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
