package zenith.app

import android.Manifest
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Rational
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

class MainActivity : ComponentActivity() {
    /** Bumped on every onResume: permissions and updates are re-checked when the user comes back. */
    private val resumes = MutableStateFlow(0)

    /** "Car connected · tap to start" notification was tapped. */
    private val startRequested = MutableStateFlow(false)
    private val inPip = MutableStateFlow(false)

    /** Driving: leaving Zenith (for Waze, Maps…) shrinks it into a picture-in-picture window. */
    private var pipWanted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("zenith", MODE_PRIVATE)
        UserSettings.init(this)
        handleIntent(intent)
        setContent {
            val context = LocalContext.current
            val running by DriveState.running.collectAsStateWithLifecycle()
            val snapshot by DriveState.snapshot.collectAsStateWithLifecycle()
            val gps by DriveState.gps.collectAsStateWithLifecycle()
            val data by DriveState.data.collectAsStateWithLifecycle()
            val radars by DriveState.nearbyRadars.collectAsStateWithLifecycle()
            val zones by DriveState.nearbyZones.collectAsStateWithLifecycle()
            val update by Updater.state.collectAsStateWithLifecycle()
            val resumeCount by resumes.collectAsStateWithLifecycle()
            val pip by inPip.collectAsStateWithLifecycle()
            val demo by DriveState.demo.collectAsStateWithLifecycle()
            val settings by UserSettings.state.collectAsStateWithLifecycle()
            val media by NowPlaying.state.collectAsStateWithLifecycle()
            val startNow by startRequested.collectAsStateWithLifecycle()
            var showSettings by remember { mutableStateOf(false) }
            val pairLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { }
            BackHandler(enabled = showSettings) { showSettings = false }
            val navApps = remember(resumeCount) { NavApp.installed(context) }
            var permissionResults by remember { mutableIntStateOf(0) }
            val permissions = remember(resumeCount, permissionResults) { Permissions.read(context) }
            var onboarded by remember { mutableStateOf(prefs.getBoolean(KEY_ONBOARDED, false)) }
            // Every permission is required: the welcome screen comes back whenever one is missing,
            // a new one or one taken back (never during a drive).
            val showWelcome = !running && (!onboarded || permissions.missing(settings.motionStart) > 0)
            // "Car connected · tap to start" notification.
            LaunchedEffect(startNow, permissions.location) {
                if (startNow && permissions.location) {
                    startRequested.value = false
                    DriveService.start(context)
                }
            }

            val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
                permissionResults++
                if (!showWelcome && result[Manifest.permission.ACCESS_FINE_LOCATION] == true) DriveService.start(context)
            }
            // "Démarrage en roulant": activity recognition first, then the location "all the time".
            val onMotion = {
                val details = { Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")) }
                val activity = Motion.activityPermission()
                when {
                    !permissions.activity && activity != null -> requestOrSettings(arrayOf(activity), { launcher.launch(it) }, details)
                    // Android 11+ answers this one with its own settings page ("Toujours autoriser").
                    !permissions.backgroundLocation && Build.VERSION.SDK_INT >= 29 ->
                        requestOrSettings(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), { launcher.launch(it) }, details)
                    else -> Unit
                }
            }

            // While driving: screen stays on, and leaving the app opens the picture-in-picture window.
            LaunchedEffect(running) {
                pipWanted = running
                updatePip()
                if (running) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            LaunchedEffect(resumeCount) { Updater.checkIfDue(context) }
            // Activity recognition follows the setting and the permissions (renewed at each start too).
            LaunchedEffect(permissions.motion, settings.motionStart) { Motion.sync(context) }
            // Music bar: picks up a notification access granted during the drive.
            LaunchedEffect(resumeCount, running) { if (running) NowPlaying.start(context) }
            // Parked: install as soon as the update is downloaded (silent once Zenith installed itself once).
            // Driving: the banner waits for a tap.
            LaunchedEffect(update, running, permissions.installs) {
                if (update is UpdateState.Ready && !running && permissions.installs) Updater.install(context)
            }
            // Android's own "update?" screen, opened only while Zenith is in front (it would be blocked otherwise).
            LaunchedEffect(update, resumeCount) {
                val pending = update as? UpdateState.NeedsConfirmation ?: return@LaunchedEffect
                if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@LaunchedEffect
                runCatching { startActivity(pending.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                Updater.confirmationShown()
            }

            if (pip) {
                PipScreen(snapshot, radars, zones, media = media, canvas = settings.spotifyCanvas)
            } else if (showSettings && !showWelcome) {
                SettingsScreen(
                    settings = settings,
                    version = remember { runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull().orEmpty() },
                    onChange = { change -> UserSettings.update(context, change) },
                    onPairCar = {
                        if (CarLink.supported) {
                            CarLink.pair(
                                context,
                                launch = { sender -> pairLauncher.launch(IntentSenderRequest.Builder(sender).build()) },
                                onPaired = { info ->
                                    UserSettings.update(context) { it.copy(carAssociationId = info.id, carName = info.displayName?.toString() ?: "ta voiture") }
                                },
                                onError = { Log.w("Zenith", "car pairing: $it") },
                            )
                        }
                    },
                    onUnpairCar = {
                        val id = settings.carAssociationId
                        if (id != null && CarLink.supported) CarLink.unpair(context, id)
                        UserSettings.update(context) { it.copy(carAssociationId = null, carName = null) }
                    },
                    onBack = { showSettings = false },
                    onShareTrip = remember(resumeCount) { latestTrip(context) }?.let { trip -> { shareTrip(trip) } },
                    mediaAllowed = permissions.media,
                    onAllowMedia = { openSettings(NowPlaying.accessIntent(context)) },
                    permissions = permissions,
                    onMotionPermission = onMotion,
                )
            } else if (showWelcome) {
                Onboarding(
                    permissions = permissions,
                    returning = onboarded,
                    motionWanted = settings.motionStart,
                    onLocation = {
                        requestOrSettings(Permissions.locationPermissions(), { launcher.launch(it) }) {
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                        }
                    },
                    onNotifications = {
                        val permission = Permissions.notificationPermission()
                        val settings = { Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName) }
                        if (permission == null) openSettings(settings()) else requestOrSettings(arrayOf(permission), { launcher.launch(it) }, settings)
                    },
                    onInstalls = { openSettings(Permissions.installsIntent(context)) },
                    onBattery = { openSettings(Permissions.batteryIntent(context)) },
                    onMotion = onMotion,
                    onMedia = { openSettings(NowPlaying.accessIntent(context)) },
                    onMediaUnlock = { openSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) },
                    onDone = {
                        prefs.edit { putBoolean(KEY_ONBOARDED, true) }
                        onboarded = true
                    },
                )
            } else {
                DriveScreen(
                    running = running,
                    snapshot = snapshot,
                    radars = radars,
                    zones = zones,
                    gps = gps,
                    data = data,
                    update = update,
                    canInstall = permissions.installs,
                    onStart = {
                        if (permissions.location) DriveService.start(context) else launcher.launch(Permissions.locationPermissions())
                    },
                    onStop = { DriveService.stop(context) },
                    onInstallUpdate = {
                        if (permissions.installs) Updater.install(context) else openSettings(Permissions.installsIntent(context))
                    },
                    navApps = navApps,
                    onOpenNavApp = { it.launch(context) },
                    demo = demo,
                    onDemo = { if (permissions.location) DriveService.startDemo(context) },
                    onOpenSettings = { showSettings = true },
                    media = media,
                    mediaCanvas = settings.spotifyCanvas,
                    onMediaOpen = { NowPlaying.open(context) },
                    onMediaLike = NowPlaying::toggleLike,
                    onMediaPrevious = NowPlaying::previous,
                    onMediaPlayPause = NowPlaying::playPause,
                    onMediaNext = NowPlaying::next,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == ACTION_START_DRIVE) startRequested.value = true
    }

    override fun onResume() {
        super.onResume()
        resumes.value++
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Before Android 12 there is no auto-enter: do it by hand when the user leaves.
        if (pipWanted && Build.VERSION.SDK_INT < 31) runCatching { enterPictureInPictureMode(pipParams()) }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip.value = isInPictureInPictureMode
    }

    private fun updatePip() {
        runCatching { setPictureInPictureParams(pipParams()) }
    }

    private fun pipParams(): PictureInPictureParams = PictureInPictureParams.Builder()
        .setAspectRatio(Rational(1, 1))
        .apply {
            if (Build.VERSION.SDK_INT >= 31) {
                setAutoEnterEnabled(pipWanted)
                setSeamlessResizeEnabled(false)
            }
        }
        .build()

    /**
     * Shows Android's prompt, or its settings screen once the prompt is blocked: after two
     * refusals Android silently ignores requests, so the button would otherwise do nothing.
     */
    private fun requestOrSettings(permissions: Array<String>, request: (Array<String>) -> Unit, settings: () -> Intent) {
        val prefs = getSharedPreferences("zenith", MODE_PRIVATE)
        val key = "asked_${permissions.first()}"
        val blocked = prefs.getBoolean(key, false) && !shouldShowRequestPermissionRationale(permissions.first())
        if (blocked) {
            openSettings(settings())
        } else {
            prefs.edit { putBoolean(key, true) }
            request(permissions)
        }
    }

    private fun shareTrip(file: File) {
        runCatching {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
            val send = Intent(Intent.ACTION_SEND).setType("application/gpx+xml").putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(send, "Partager le trajet"))
        }.onFailure { Log.w("Zenith", "share trip", it) }
    }

    private fun openSettings(intent: Intent) {
        runCatching { startActivity(intent) }
            .onFailure { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
    }

    companion object {
        const val ACTION_START_DRIVE = "zenith.app.START_DRIVE"
        private const val KEY_ONBOARDED = "onboarded"
    }
}
