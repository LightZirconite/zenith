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
import zenith.core.Announcer
import zenith.core.DriveSnapshot
import zenith.core.DrivingSession

/**
 * Foreground service running a drive: 1 Hz GPS → DrivingSession → shared state, voice and GPX.
 * Keeps alerting with the screen off or another app (Waze, Maps) in front.
 */
class DriveService : Service(), LocationListener {
    private lateinit var session: DrivingSession
    private lateinit var recorder: TripRecorder
    private lateinit var speaker: Speaker
    private var lastNotified: Pair<Int, Int?>? = null
    private var lastNotifiedAtMs = 0L

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

        session = DrivingSession(DriveData.network(this), DriveData.radars(this))
        recorder = TripRecorder(this)
        speaker = Speaker(this)
        DriveState.started()
        getSystemService(LocationManager::class.java)
            .requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, this, Looper.getMainLooper())
        return START_STICKY
    }

    override fun onLocationChanged(location: Location) {
        val fix = location.toFix()
        recorder.add(fix)
        val snapshot = session.process(fix) ?: return
        DriveState.update(snapshot)
        snapshot.events.mapNotNull(Announcer::text).forEach(speaker::say)
        refreshNotification(snapshot)
    }

    override fun onDestroy() {
        if (::session.isInitialized) {
            getSystemService(LocationManager::class.java).removeUpdates(this)
            recorder.close()
            speaker.shutdown()
        }
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
        val text = when {
            s == null -> "Recherche du GPS…"
            s.limitKmh != null -> "${s.speedKmh} km/h · limite ${s.limitKmh}"
            else -> "${s.speedKmh} km/h"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
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
