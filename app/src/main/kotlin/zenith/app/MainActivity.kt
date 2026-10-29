package zenith.app

import android.Manifest
import android.app.PictureInPictureParams
import android.content.Context
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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {
    /** Bumped on every onResume: permissions and updates are re-checked when the user comes back. */
    private val resumes = MutableStateFlow(0)

    /** "Car connected · tap to start" notification was tapped. */
    private val startRequested = MutableStateFlow(false)
    private val inPip = MutableStateFlow(false)

    /** Driving: leaving Zenith (for Waze, Maps…) shrinks it into a picture-in-picture window. */
    private var pipWanted = false

    /**
     * Opened by [openSmallWindow]: the activity goes into its small window once resumed, showing
     * the small window's content meanwhile (no take-off flight, no music paused for it).
     */
    private val openingSmall = MutableStateFlow(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("zenith", MODE_PRIVATE)
        UserSettings.init(this)
        UiSound.init(this)
        inPip.value = isInPictureInPictureMode
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
            val smallSoon by openingSmall.collectAsStateWithLifecycle()
            val demo by DriveState.demo.collectAsStateWithLifecycle()
            val settings by UserSettings.state.collectAsStateWithLifecycle()
            val media by NowPlaying.state.collectAsStateWithLifecycle()
            val startNow by startRequested.collectAsStateWithLifecycle()
            val musicChoice by DriveState.musicChoice.collectAsStateWithLifecycle()
            var showSettings by remember { mutableStateOf(false) }
            val scope = rememberCoroutineScope()
            val pairLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { }
            val view = LocalView.current
            BackHandler(enabled = showSettings) {
                UiSound.play(UiSound.Kind.CLOSE, view)
                showSettings = false
            }
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

            // Activity granted: the location "all the time" is asked right after, without another tap.
            var chainBackground by remember { mutableStateOf(false) }
            val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
                permissionResults++
                if (!showWelcome && result[Manifest.permission.ACCESS_FINE_LOCATION] == true) DriveService.start(context)
                if (result[Manifest.permission.ACTIVITY_RECOGNITION] == true) chainBackground = true
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
            LaunchedEffect(chainBackground, permissions) {
                if (chainBackground) {
                    chainBackground = false
                    if (permissions.activity && !permissions.backgroundLocation) onMotion()
                }
            }
            val onLocation = {
                requestOrSettings(Permissions.locationPermissions(), { launcher.launch(it) }) {
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                }
            }
            val onNotifications = {
                val permission = Permissions.notificationPermission()
                val screen = { Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName) }
                if (permission == null) openSettings(screen()) else requestOrSettings(arrayOf(permission), { launcher.launch(it) }, screen)
            }
            // Notification access, Android 13+ for an app installed outside a store: the first try is
            // blocked, then the app info screen offers the unlock, then the switch works.
            var mediaStep by remember { mutableIntStateOf(prefs.getInt(KEY_MEDIA_STEP, 0)) }
            val onMedia = {
                if (Build.VERSION.SDK_INT >= 33 && mediaStep == 1) {
                    openSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                    mediaStep = 2
                } else {
                    openSettings(NowPlaying.accessIntent(context))
                    if (Build.VERSION.SDK_INT >= 33) mediaStep = 1
                }
                prefs.edit { putInt(KEY_MEDIA_STEP, mediaStep) }
            }
            // The welcome screen's big button: the next missing permission, in the order that asks least.
            val onNext = {
                when {
                    !permissions.location -> onLocation()
                    !permissions.notifications -> onNotifications()
                    !permissions.battery -> openSettings(Permissions.batteryIntent(context))
                    !permissions.installs -> openSettings(Permissions.installsIntent(context))
                    permissions.motionAvailable && settings.motionStart && !permissions.motion -> onMotion()
                    !permissions.media -> onMedia()
                    permissions.overlayNeeded(settings.motionStart) && !permissions.overlay -> openSettings(Permissions.overlayIntent(context))
                    else -> Unit
                }
            }

            // While driving: screen stays on, and leaving the app opens the picture-in-picture window.
            LaunchedEffect(running) {
                pipWanted = running
                updatePip()
                if (running) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                // The drive ended in the small window (parked, car gone, demo over): it closes
                // instead of staying frozen on the last speed over Waze.
                if (!running && inPip.value) finishAndRemoveTask()
            }
            LaunchedEffect(settings.pipLarge) { updatePip() }
            // Each return to the app, then every 2 minutes while it stays in front: a new version never waits.
            LaunchedEffect(Unit) {
                lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                    while (true) {
                        Updater.checkIfDue(context)
                        delay(2 * 60_000L)
                    }
                }
            }
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

            val screen = when {
                showWelcome -> Screen.WELCOME
                showSettings -> Screen.SETTINGS
                else -> Screen.DRIVE
            }
            if (pip || smallSoon) {
                PipScreen(snapshot, radars, zones, media = media, canvas = settings.spotifyCanvas, musicChoice = musicChoice)
            } else AnimatedContent(screen, transitionSpec = { screenTransition(initialState, targetState) }, label = "screen") { shown ->
                when (shown) {
                Screen.SETTINGS -> SettingsScreen(
                    settings = settings,
                    version = remember { runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull().orEmpty() },
                    onChange = { change -> UserSettings.update(context, change) },
                    onPairCar = {
                        if (CarLink.supported) {
                            CarLink.pair(
                                context,
                                launch = { sender -> pairLauncher.launch(IntentSenderRequest.Builder(sender).build()) },
                                onPaired = { info ->
                                    UserSettings.update(context) { s ->
                                        val car = Car(info.id, info.displayName?.toString() ?: "Voiture ${s.cars.size + 1}")
                                        s.copy(cars = s.cars.filter { it.associationId != car.associationId } + car)
                                    }
                                },
                                onError = { Log.w("Zenith", "car pairing: $it") },
                            )
                        }
                    },
                    onUnpairCar = { car ->
                        if (CarLink.supported) CarLink.unpair(context, car.associationId)
                        UserSettings.update(context) { s -> s.copy(cars = s.cars.filter { it.associationId != car.associationId }) }
                    },
                    onBack = { showSettings = false },
                    onShareTrip = remember(resumeCount) { latestTrip(context) }?.let { trip -> { shareTrip(trip) } },
                    mediaAllowed = permissions.media,
                    onAllowMedia = onMedia,
                    permissions = permissions,
                    onMotionPermission = onMotion,
                    update = update,
                    onCheckUpdate = { scope.launch { Updater.checkAndDownload(context, force = true) } },
                )
                Screen.WELCOME -> Onboarding(
                    permissions = permissions,
                    returning = onboarded,
                    motionWanted = settings.motionStart,
                    onLocation = onLocation,
                    onNotifications = onNotifications,
                    onInstalls = { openSettings(Permissions.installsIntent(context)) },
                    onBattery = { openSettings(Permissions.batteryIntent(context)) },
                    onMotion = onMotion,
                    onMedia = onMedia,
                    onOverlay = { openSettings(Permissions.overlayIntent(context)) },
                    mediaStep = mediaStep,
                    onNext = onNext,
                    onDone = {
                        prefs.edit { putBoolean(KEY_ONBOARDED, true) }
                        onboarded = true
                    },
                )
                Screen.DRIVE -> DriveScreen(
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
                    onMediaSeek = NowPlaying::seekBy,
                    musicChoice = musicChoice,
                    onMusicChoice = DriveState::chooseMusic,
                )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == ACTION_START_DRIVE) startRequested.value = true
        if (intent?.action == ACTION_OPEN_SMALL_WINDOW) openingSmall.value = true
    }

    override fun onStart() {
        super.onStart()
        visible = true
    }

    override fun onStop() {
        super.onStop()
        visible = false
    }

    override fun onResume() {
        super.onResume()
        resumes.value++
        // Opened by an automatic start: straight into the small window, over what was in front.
        if (openingSmall.value) {
            if (!DriveState.running.value || isInPictureInPictureMode) {
                openingSmall.value = false
            } else {
                window.decorView.post {
                    // A screen without picture-in-picture (some head units): the full screen instead.
                    val entered = runCatching { enterPictureInPictureMode(pipParams()) }.getOrDefault(false)
                    if (!entered) openingSmall.value = false
                }
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Before Android 12 there is no auto-enter: do it by hand when the user leaves.
        if (pipWanted && Build.VERSION.SDK_INT < 31) runCatching { enterPictureInPictureMode(pipParams()) }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip.value = isInPictureInPictureMode
        openingSmall.value = false
    }

    private fun updatePip() {
        runCatching { setPictureInPictureParams(pipParams()) }
    }

    /**
     * Android sizes the window from its shape alone: a wide strip stays low over Waze, a square
     * one is larger (setting). A double tap on the window toggles Android's own bigger size.
     */
    private fun pipParams(): PictureInPictureParams = PictureInPictureParams.Builder()
        .setAspectRatio(if (UserSettings.state.value.pipLarge) Rational(1, 1) else Rational(16, 9))
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
            .onFailure { runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) } }
    }

    /**
     * Between full screens, the iOS way: the settings are pushed in from the right over home, which
     * slides a third of the way left, sinks a little and dims; back, the reverse, on the same spring.
     * The welcome page lifts toward the viewer as home comes up.
     */
    private fun screenTransition(from: Screen, to: Screen): ContentTransform = when {
        to == Screen.SETTINGS -> ContentTransform(
            slideInHorizontally(PushSpring) { it },
            slideOutHorizontally(PushSpring) { -it / 3 } + scaleOut(PushSpringFloat, targetScale = 0.94f) +
                fadeOut(tween(380), targetAlpha = 0.25f),
        )
        from == Screen.SETTINGS -> ContentTransform(
            slideInHorizontally(PushSpring) { -it / 3 } + scaleIn(PushSpringFloat, initialScale = 0.94f) + fadeIn(tween(380), initialAlpha = 0.25f),
            slideOutHorizontally(PushSpring) { it },
            // The page leaving stays on top while it slides away.
            targetContentZIndex = -1f,
        )
        else -> ContentTransform(
            scaleIn(spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessVeryLow), initialScale = 0.85f) + fadeIn(tween(600, delayMillis = 150)),
            scaleOut(tween(500), targetScale = 1.12f) + fadeOut(tween(400)),
        )
    }

    companion object {
        const val ACTION_START_DRIVE = "zenith.app.START_DRIVE"
        private const val ACTION_OPEN_SMALL_WINDOW = "zenith.app.OPEN_SMALL_WINDOW"

        /** An activity of Zenith is on screen (full or in its small window). */
        @Volatile
        private var visible = false

        /**
         * A drive started by itself (riding, car connected): Zenith opens straight into its small
         * window over what is in front. Android lets an app open a window from the background only
         * with "display over other apps" ([Permissions.overlay]); without it, the drive runs as before.
         */
        fun openSmallWindow(context: Context) {
            if (visible || !Permissions.canOverlay(context)) return
            runCatching {
                context.startActivity(
                    Intent(context, MainActivity::class.java).setAction(ACTION_OPEN_SMALL_WINDOW)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure { Log.w("Zenith", "small window", it) }
        }
        private const val KEY_ONBOARDED = "onboarded"
        private const val KEY_MEDIA_STEP = "media_step"
    }
}

/** iOS's push: a quick spring, no bounce. */
private val PushSpring = spring(dampingRatio = 1f, stiffness = 420f, visibilityThreshold = IntOffset.VisibilityThreshold)
private val PushSpringFloat = spring<Float>(dampingRatio = 1f, stiffness = 420f)

/** The full screens, for the transitions between them (the small window has none). */
private enum class Screen { DRIVE, SETTINGS, WELCOME }
