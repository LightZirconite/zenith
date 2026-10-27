package zenith.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            val context = LocalContext.current
            val running by DriveState.running.collectAsStateWithLifecycle()
            val snapshot by DriveState.snapshot.collectAsStateWithLifecycle()
            val gps by DriveState.gps.collectAsStateWithLifecycle()
            val data by DriveState.data.collectAsStateWithLifecycle()
            val update by Updater.state.collectAsStateWithLifecycle()
            var locationGranted by remember {
                mutableStateOf(
                    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                        PackageManager.PERMISSION_GRANTED,
                )
            }
            val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
                locationGranted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true || locationGranted
                if (locationGranted) DriveService.start(context)
            }

            LaunchedEffect(Unit) { Updater.checkAndDownload(context) }
            // Install right away when parked; while driving the banner waits for a tap.
            LaunchedEffect(update, running) {
                if (update is UpdateState.Ready && !running) installUpdate()
            }

            DriveScreen(
                running = running,
                snapshot = snapshot,
                gps = gps,
                data = data,
                update = update,
                onStart = {
                    if (locationGranted) DriveService.start(context) else launcher.launch(requiredPermissions())
                },
                onStop = { DriveService.stop(context) },
                onInstallUpdate = ::installUpdate,
            )
        }
    }

    private fun installUpdate() {
        if (packageManager.canRequestPackageInstalls()) {
            Updater.install(this)
        } else {
            // One-time: allow Zenith to install its own updates.
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
        }
    }

    private fun requiredPermissions(): Array<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()
}
