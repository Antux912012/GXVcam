package org.gxvcam

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
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
import androidx.core.content.ContextCompat
import org.gxvcam.camera.Camera2Controller
import org.gxvcam.ui.theme.DarkBackground
import org.gxvcam.ui.theme.GXVcamTheme
import org.gxvcam.ui.viewfinder.CameraScreen

class MainActivity : ComponentActivity() {

    private lateinit var cameraController: Camera2Controller

    private val requiredPermissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        cameraController = Camera2Controller(applicationContext)

        setContent {
            GXVcamTheme {
                var hasPermissions by remember {
                    mutableStateOf(allPermissionsGranted())
                }

                val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) { permissions ->
                    hasPermissions = permissions.values.all { it }
                }

                if (hasPermissions) {
                    CameraScreen(controller = cameraController)
                } else {
                    PermissionRequestScreen {
                        permissionLauncher.launch(requiredPermissions)
                    }
                }
            }
        }
    }

    private fun allPermissionsGranted(): Boolean {
        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraController.closeCamera()
        cameraController.stopBackgroundThread()
    }
}

@Composable
fun PermissionRequestScreen(onRequestPermissions: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "GXVcam requires Camera & Audio permissions to capture photos.",
                color = androidx.compose.ui.graphics.Color.White,
                style = MaterialTheme.typography.bodyLarge
            )
            Button(onClick = onRequestPermissions) {
                Text("Grant Permissions")
            }
        }
    }
}
