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
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import zenith.core.AlertEvent
import zenith.core.Announcer
import zenith.core.DriveSnapshot
import zenith.core.DrivingSession
import zenith.core.OverspeedLevel

/**
 * Foreground service running a drive: 1 Hz GPS → DrivingSession → shared state, sounds, voice
 * and GPX. Keeps alerting with the screen off or Waze / Maps in front.
 */
class DriveService : Service(), LocationListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var session: DrivingSession
    private lateinit var data: DriveData
    private lateinit var recorder: TripRecorder
    private lateinit var speaker: Speaker
    private lateinit var tones: TonePlayer
    private lateinit var gpsStatus: GpsStatusWatcher
    private var lastNotified: Pair<Int?, Int?>? = null
    private var lastNotifiedAtMs = 0L
    private var lastDangerBeepMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (::session.isInitialized) return START_STICKY
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return START_NOT_STICKY
        }
        createChannel()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(null), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)

        data = DriveData(this, scope)
        session = DrivingSession({ data.network }, emptyList())
        recorder = TripRecorder(this)
        speaker = Speaker(this)
        tones = TonePlayer()
        DriveState.started()
        scope.launch { data.status.collect(DriveState::data) }
        data.loadRadars { radars -> scope.launch { session.setRadars(radars) } }

        val manager = getSystemService(LocationManager::class.java)
        gpsStatus = GpsStatusWatcher(manager, DriveState::satellites).also { it.start() }
        manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, this, Looper.getMainLooper())
        return START_STICKY
    }

    override fun onLocationChanged(location: Location) {
        val fix = location.toFix()
        recorder.add(fix)
        data.ensureTiles(fix.position)
        val snapshot = session.process(fix) ?: return
        DriveState.update(snapshot)
        snapshot.events.forEach(::announce)
        dangerBeep(snapshot)
        refreshNotification(snapshot)
    }

    /** Tone first, then the spoken text right after it. */
    private fun announce(event: AlertEvent) {
        val tone = when (event) {
            is AlertEvent.Approach, is AlertEvent.SectionStarted -> Tone.APPROACH
            is AlertEvent.Brake -> Tone.BRAKE
            is AlertEvent.Overspeed -> Tone.OVERSPEED.takeIf { event.level == OverspeedLevel.WARN }
            is AlertEvent.Passed -> Tone.PASSED.takeIf { event.wouldFlash }
            is AlertEvent.SectionEnded -> Tone.PASSED
            is AlertEvent.LimitChanged -> null
        }
        tone?.let(tones::play)
        Announcer.text(event)?.let { speaker.say(it, delayMs = tone?.let(tones::durationMs)?.plus(80) ?: 0) }
    }

    /** Like Coyote: while over the limit near a radar, beeps get faster as it gets closer. */
    private fun dangerBeep(s: DriveSnapshot) {
        val alert = s.alerts.firstOrNull { it.overLimit } ?: return
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
        if (::session.isInitialized) {
            val manager = getSystemService(LocationManager::class.java)
            manager.removeUpdates(this)
            gpsStatus.stop()
            recorder.close()
            speaker.shutdown()
            tones.release()
        }
        scope.cancel()
        DriveState.stopped()
        super.onDestroy()
    }

    /** Updates the notification when speed or limit changes, at most every 2 s. */
    private fun refreshNotification(s: DriveSnapshot) {
        val key = s.speedKmh to s.limitKmh
        if (key == lastNotified || s.timeMs - lastNotifiedAtMs < 2_000) return
        lastNotified = key
        lastNotifiedAtMs = s.timeMs
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(s))
    }

    private fun notification(s: DriveSnapshot?): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, DriveService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val speed = s?.speedKmh?.let { "$it km/h" }
        val text = when {
            speed == null -> "Recherche des satellites…"
            s?.limitKmh != null -> "$speed · limite ${s.limitKmh}"
            else -> speed
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Zenith actif")
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, "Arrêter", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Conduite", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "drive"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "zenith.app.STOP"

        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, DriveService::class.java))

        fun stop(context: Context) {
            context.stopService(Intent(context, DriveService::class.java))
        }
    }
}
