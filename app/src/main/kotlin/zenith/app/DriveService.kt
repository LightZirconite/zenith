package zenith.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import zenith.core.AlertEvent
import zenith.core.Announcer
import zenith.core.BBox
import zenith.core.ControlZone
import zenith.core.DemoDrive
import zenith.core.DriveSnapshot
import zenith.core.DrivingSession
import zenith.core.GpsFix
import zenith.core.LatLon
import zenith.core.OverspeedLevel
import zenith.core.ParkDetector
import zenith.core.RadarIndex
import zenith.core.RainDetector
import zenith.core.Weather
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Foreground service running a drive: 1 Hz GPS → DrivingSession → shared state, sounds, voice
 * and GPX. Keeps alerting with the screen off or Waze / Maps in front. The pipeline runs on its own
 * thread, one fix after the other (the road horizon is too heavy for the UI thread), and a failing
 * step is logged and skipped: a drive never stops on an error.
 */
class DriveService : Service(), LocationListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val driveExecutor = Executors.newSingleThreadExecutor { Thread(it, "zenith-drive") }
    private val drive = driveExecutor.asCoroutineDispatcher()
    private lateinit var session: DrivingSession
    private lateinit var ducker: AudioDucker
    private lateinit var speaker: Speaker
    private lateinit var tones: TonePlayer

    // Live drive only (the demo has no GPS, no downloads and records nothing).
    private var data: DriveData? = null
    private var recorder: TripRecorder? = null
    private var gpsStatus: GpsStatusWatcher? = null
    private var parking: ParkDetector? = null
    private var ending = false
    private var started = false
    private var origin = DriveOrigin.HAND
    private val demo get() = origin == DriveOrigin.DEMO
    private var lastFixTimeMs = 0L
    private var lastFixWallMs = 0L
    private var lastNotified: Triple<Int?, Int?, String?>? = null
    private var lastNotifiedAtMs = 0L
    private var lastDangerBeepMs = 0L
    @Volatile
    private var radarIndex = RadarIndex(emptyList())
    @Volatile
    private var zoneBoxes: List<Pair<ControlZone, BBox>> = emptyList()
    private var lastNearbyMs = 0L
    /** Wall time of a vehicle exit received while still fast; 0 when none is pending. */
    @Volatile
    private var vehicleLeftAtMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_MOTION_EXIT) {
            // The phone left the vehicle: a drive it started ends once the car is slow or stopped,
            // now or within a few minutes (the event often comes while still rolling into the driveway).
            val slow = (DriveState.snapshot.value?.speedKmh ?: 0) < 10
            if (started && origin == DriveOrigin.MOTION) {
                if (slow) stopSelf() else vehicleLeftAtMs = System.currentTimeMillis()
            }
            if (!started) stopSelf()
            return if (started) START_STICKY else START_NOT_STICKY
        }
        if (intent?.action == ACTION_RESHOW) {
            // The notification was swiped away (allowed since Android 14): the drive goes on, put it back.
            if (!started) {
                stopSelf()
                return START_NOT_STICKY
            }
            repostNotification()
            return if (demo) START_NOT_STICKY else START_STICKY
        }
        if (started) return START_STICKY
        // Android requires the location permission for a "location" foreground service, demo included.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return START_NOT_STICKY
        }
        origin = when (intent?.action) {
            ACTION_DEMO -> DriveOrigin.DEMO
            ACTION_AUTO -> DriveOrigin.CAR
            ACTION_MOTION -> DriveOrigin.MOTION
            else -> DriveOrigin.HAND
        }
        createChannel()
        val foreground = runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(null), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        }
        if (foreground.isFailure) {
            // Android refused a start from the background (car connected while the phone was idle).
            Log.w("Zenith", "foreground start", foreground.exceptionOrNull())
            when (origin) {
                DriveOrigin.CAR -> CarLink.notifyTapToStart(this)
                DriveOrigin.MOTION -> CarLink.notifyTapToStart(this, "En voiture ?")
                else -> Unit
            }
            stopSelf()
            return START_NOT_STICKY
        }
        started = true
        CarLink.cancelTapToStart(this)
        UserSettings.init(this)
        ducker = AudioDucker(this)
        speaker = Speaker(this, ducker)
        tones = TonePlayer(ducker)
        DriveState.started(origin)
        NowPlaying.start(this)
        if (demo) startDemo() else startLive()
        scope.launch { UserSettings.state.collect { session.profile = it.profile } }
        return if (demo) START_NOT_STICKY else START_STICKY
    }

    private fun startLive() {
        val live = DriveData(this, scope)
        data = live
        session = DrivingSession({ live.network }, emptyList())
        recorder = TripRecorder(this)
        // Activity recognition sometimes fires at home: such a drive ends if it never really drives.
        parking = ParkDetector(System.currentTimeMillis(), falseStartMs = if (origin == DriveOrigin.MOTION) FALSE_START_MS else null)
        watchWeather()
        scope.launch { live.status.collect(DriveState::data) }
        live.loadRadars { pack ->
            radarIndex = RadarIndex(pack.radars)
            zoneBoxes = pack.zones.map { it to BBox.of(it.points) }
            scope.launch(drive) {
                lastNearbyMs = 0L
                session.setRadars(pack.radars)
                session.setZones(pack.zones)
            }
        }
        val manager = getSystemService(LocationManager::class.java)
        // Road tiles around the last known position load while the GPS gets its first fix.
        runCatching {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
                .firstNotNullOfOrNull { manager.getLastKnownLocation(it) }
        }.getOrNull()?.let { live.ensureTiles(LatLon(it.latitude, it.longitude)) }
        gpsStatus = GpsStatusWatcher(manager, DriveState::satellites).also { it.start() }
        manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, this, Looper.getMainLooper())
        // Tunnel: no fix for 2.5 s → the car keeps moving along the road (DrivingSession.coast).
        scope.launch(drive) {
            while (true) {
                delay(1_000)
                val lost = System.currentTimeMillis() - lastFixWallMs
                if (lastFixWallMs > 0 && lost > 2_500) guarded { session.coast(lastFixTimeMs + lost)?.let(::show) }
                if (parking?.onSilence(System.currentTimeMillis()) == true) endByItself()
            }
        }
    }

    /** Rain mode from the weather: checked every 10 minutes at the car's position (rounded to ~10 km), spoken when it changes. */
    private fun watchWeather() {
        val rain = RainDetector()
        scope.launch(Dispatchers.IO) {
            while (true) {
                val s = UserSettings.state.value
                val p = DriveState.snapshot.value?.position
                // Setting turned off while it rained: the limits come back at once, not at the next drive.
                if (!s.autoRain && s.raining) UserSettings.update(this@DriveService) { it.copy(raining = false) }
                if (p == null || !s.autoRain) {
                    delay(20_000)
                    continue
                }
                val now = runCatching { Weather.parse(httpGet(Weather.url(p))) }
                    .onFailure { Log.w("Zenith", "weather", it) }.getOrNull()
                if (now != null) {
                    val raining = rain.report(now)
                    if (raining != s.raining) {
                        UserSettings.update(this@DriveService) { it.copy(raining = raining) }
                        tones.play(Tone.CHIME)
                        if (s.voice) {
                            speaker.say(
                                if (raining) "Pluie, limites abaissées" else "Fin de la pluie, limites normales",
                                delayMs = tones.durationMs(Tone.CHIME) + 80,
                                pauseMusic = s.pauseMusic,
                            )
                        }
                    }
                }
                delay(if (now != null) WEATHER_EVERY_MS else 2 * 60_000L)
            }
        }
    }

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        try {
            check(conn.responseCode == 200) { "HTTP ${conn.responseCode}" }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** Hidden demo: replays [DemoDrive] in real time through the same pipeline, then stops. */
    private fun startDemo() {
        val scenario = DemoDrive.load()
        session = DrivingSession(scenario.network, scenario.radars, zones = scenario.zones)
        radarIndex = RadarIndex(scenario.radars)
        zoneBoxes = scenario.zones.map { it to BBox.of(it.points) }
        scope.launch(drive) {
            val t0 = System.currentTimeMillis()
            for (fix in scenario.fixes) {
                val at = t0 + fix.timeMs
                delay((at - System.currentTimeMillis()).coerceAtLeast(0))
                process(fix.copy(timeMs = at))
            }
            delay(3_000)
            stopSelf()
        }
    }

    override fun onLocationChanged(location: Location) {
        val fix = location.toFix()
        data?.ensureTiles(fix.position)
        val wallMs = System.currentTimeMillis()
        scope.launch(drive) {
            recorder?.add(fix)
            lastFixTimeMs = fix.timeMs
            lastFixWallMs = wallMs
            process(fix)
            val s = DriveState.snapshot.value
            if (parking?.onFix(wallMs, fix.position, s?.speedKmh, s?.road?.segment?.roadClass, charging()) == true) endByItself()
            val left = vehicleLeftAtMs
            when {
                left == 0L -> Unit
                wallMs - left > VEHICLE_EXIT_GRACE_MS -> vehicleLeftAtMs = 0
                (s?.speedKmh ?: 0) < 10 -> stopSelf()
            }
        }
    }

    /**
     * Battery saver: a drive started by hand ends by itself once over (car parked, walking away,
     * indoors without GPS). A drive started by the car ends when the car disconnects instead.
     */
    private fun endByItself() {
        if (origin == DriveOrigin.CAR) return
        if (ending || !UserSettings.state.value.autoStop) return
        ending = true
        Log.i("Zenith", "drive over: stopping by itself")
        stopSelf()
    }

    private fun charging() = runCatching { getSystemService(BatteryManager::class.java).isCharging }.getOrDefault(false)

    /** Drive thread only. */
    private fun process(fix: GpsFix) = guarded { session.process(fix)?.let(::show) }

    /** A failing step must not end the drive (nor crash the app, which could then no longer update). */
    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.e("Zenith", "drive step", e)
        }
    }

    private fun show(snapshot: DriveSnapshot) {
        DriveState.update(snapshot)
        if (snapshot.timeMs - lastNearbyMs >= 3_000 || lastNearbyMs == 0L) {
            lastNearbyMs = snapshot.timeMs
            val around = BBox.around(snapshot.position, 4_000.0)
            val zones = zoneBoxes.filter { (_, b) ->
                b.minLat <= around.maxLat && b.maxLat >= around.minLat && b.minLon <= around.maxLon && b.maxLon >= around.minLon
            }.map { it.first }
            DriveState.nearby(radarIndex.near(snapshot.position, 3_000.0), zones)
        }
        snapshot.events.forEach(::announce)
        dangerBeep(snapshot)
        refreshNotification(snapshot)
    }

    /** Tone first, then the spoken text right after it. */
    private fun announce(event: AlertEvent) {
        val tone = when (event) {
            is AlertEvent.Approach -> if (event.radar.type.measuresSpeed) Tone.APPROACH else Tone.CHIME
            is AlertEvent.SectionStarted -> Tone.APPROACH
            is AlertEvent.Brake -> Tone.BRAKE
            is AlertEvent.Overspeed -> Tone.OVERSPEED.takeIf { event.level == OverspeedLevel.WARN }
            is AlertEvent.Passed -> Tone.PASSED.takeIf { event.wouldFlash }
            is AlertEvent.SectionEnded -> Tone.PASSED
            is AlertEvent.LimitChanged -> null
            is AlertEvent.ZoneEntered -> Tone.CHIME
            is AlertEvent.ZoneLeft -> null
        }
        tone?.let(tones::play)
        val settings = UserSettings.state.value
        if (!settings.voice) return
        // What changes the driver's speed now goes first, dropping older announcements still queued.
        val urgent = event is AlertEvent.Brake || (event is AlertEvent.Overspeed && event.level == OverspeedLevel.WARN) ||
            (event is AlertEvent.Passed && event.wouldFlash)
        Announcer.text(event)?.let {
            speaker.say(it, delayMs = tone?.let { t -> tones.durationMs(t) + 80 } ?: 0, urgent = urgent, pauseMusic = settings.pauseMusic)
        }
    }

    /** Like Coyote: while over the limit near a radar, beeps get faster as it gets closer. */
    private fun dangerBeep(s: DriveSnapshot) {
        val alert = s.alerts.firstOrNull { it.overLimit } ?: return
        if (speaker.speaking) return // never over the voice
        val interval = when {
            alert.distanceM < 200 -> 500L
            alert.distanceM < 500 -> 1_000L
            else -> 2_000L
        }
        if (s.timeMs - lastDangerBeepMs >= interval) {
            lastDangerBeepMs = s.timeMs
            tones.play(Tone.DANGER)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        if (started) {
            if (!demo) getSystemService(LocationManager::class.java).removeUpdates(this)
            gpsStatus?.stop()
            val trip = recorder
            // After the pending fixes, on the drive thread that owns the recorder.
            driveExecutor.execute { trip?.close() }
            speaker.shutdown()
            tones.release()
            ducker.shutdown()
            NowPlaying.stop()
        }
        driveExecutor.shutdown()
        // The next drive starts from the weather again, not from this drive's rain.
        if (started && !demo) UserSettings.update(this) { it.copy(raining = false) }
        DriveState.stopped()
        super.onDestroy()
    }

    /**
     * Updates the notification when speed, limit or next radar changes, at most every 2 s, and
     * re-posts it every 30 s anyway so it comes back if it was dismissed.
     */
    private fun refreshNotification(s: DriveSnapshot) {
        val key = Triple(s.speedKmh, s.limitKmh, listOfNotNull(sectionText(s), nextRadarText(s), zoneText(s)).joinToString())
        val stale = s.timeMs - lastNotifiedAtMs >= 30_000
        if (!stale && (key == lastNotified || s.timeMs - lastNotifiedAtMs < 2_000)) return
        lastNotified = key
        lastNotifiedAtMs = s.timeMs
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(s))
    }

    private fun repostNotification() {
        lastNotified = null
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(DriveState.snapshot.value)) }
    }

    private fun notification(s: DriveSnapshot?): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, DriveService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val reshow = PendingIntent.getService(
            this, 3, Intent(this, DriveService::class.java).setAction(ACTION_RESHOW), PendingIntent.FLAG_IMMUTABLE,
        )
        val speed = s?.speedKmh
        val limit = s?.limitKmh
        val next = s?.let(::nextRadarText)
        val title = when {
            demo && speed == null -> "Zenith · démo"
            speed == null -> "Zenith · recherche des satellites…"
            limit != null -> "$speed km/h · limite $limit"
            else -> "$speed km/h"
        }
        val zone = s?.let(::zoneText)
        val section = s?.let(::sectionText)
        val text = section ?: next ?: zone ?: s?.road?.segment?.name ?: "Aucun radar sur ta route pour l'instant"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(listOfNotNull(section, next, zone, s?.road?.segment?.name).joinToString("\n").ifEmpty { text }))
            .setContentIntent(open)
            .setDeleteIntent(reshow)
            .addAction(0, "Arrêter", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            // Android 16: shown as a live chip in the status bar with the current speed.
            .setRequestPromotedOngoing(true)
            .apply { speed?.let { setShortCriticalText("$it") } }
            .build()
    }

    /** "Radar fixe · 1,2 km", rounded to 100 m so the notification does not refresh every second. */
    private fun nextRadarText(s: DriveSnapshot): String? {
        val alert = s.alerts.firstOrNull()
        val next = s.upcoming
        val (label, distanceM) = when {
            alert != null -> alert.radar.type.label to alert.distanceM
            next != null -> next.radar.type.label to next.distanceM
            else -> return null
        }
        val km = "%.1f".format(distanceM / 1_000).replace('.', ',')
        return "$label · $km km"
    }

    /** "Tronçon : moyenne 67, limite 70 · encore 0,8 km", tenths of km so it changes rarely. */
    private fun sectionText(s: DriveSnapshot): String? = s.section?.let {
        "Tronçon : moyenne ${it.averageKmh.toInt()}, limite ${it.limitKmh} · encore ${"%.1f".format(it.remainingM / 1_000).replace('.', ',')} km"
    }

    /** "Itinéraire de contrôle · 12 km", whole kilometers so it changes rarely. */
    private fun zoneText(s: DriveSnapshot): String? =
        s.zone?.let { "Itinéraire de contrôle · ${(it.remainingM / 1_000).toInt().coerceAtLeast(1)} km" }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Conduite", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "drive"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "zenith.app.STOP"
        private const val ACTION_DEMO = "zenith.app.DEMO"
        private const val ACTION_AUTO = "zenith.app.AUTO"
        private const val ACTION_MOTION = "zenith.app.MOTION"
        private const val ACTION_MOTION_EXIT = "zenith.app.MOTION_EXIT"
        private const val ACTION_RESHOW = "zenith.app.RESHOW"
        private const val WEATHER_EVERY_MS = 10 * 60_000L
        private const val FALSE_START_MS = 5 * 60_000L
        /** A vehicle exit received while fast stops the drive at the first slow fix within this delay. */
        private const val VEHICLE_EXIT_GRACE_MS = 3 * 60_000L

        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, DriveService::class.java))

        /** Car connected (paired Bluetooth): throws when Android forbids the background start. */
        fun startAuto(context: Context) {
            if (DriveState.running.value) return
            context.startForegroundService(Intent(context, DriveService::class.java).setAction(ACTION_AUTO))
        }

        /** Phone riding in a vehicle (activity recognition): throws when Android forbids the start. */
        fun startMotion(context: Context) {
            if (DriveState.running.value) return
            context.startForegroundService(Intent(context, DriveService::class.java).setAction(ACTION_MOTION))
        }

        /** Phone out of the vehicle: ends a drive the activity recognition started, once slow. */
        fun motionExit(context: Context) {
            if (!DriveState.running.value) return
            runCatching { context.startService(Intent(context, DriveService::class.java).setAction(ACTION_MOTION_EXIT)) }
        }

        /** Car gone: ends the drive only if [origin] started it. */
        fun stopIfStartedBy(context: Context, origin: DriveOrigin) {
            if (DriveState.running.value && DriveState.origin.value == origin) stop(context)
        }

        /** Hidden simulated drive, to see and hear the alerts without driving. */
        fun startDemo(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, DriveService::class.java).setAction(ACTION_DEMO))

        fun stop(context: Context) {
            context.stopService(Intent(context, DriveService::class.java))
        }
    }
}
