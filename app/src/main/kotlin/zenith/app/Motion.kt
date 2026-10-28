package zenith.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity

/**
 * Automatic start without the car's Bluetooth: Google Play Services tell, at almost no battery
 * cost, when the phone starts or stops riding in a vehicle, even with Zenith closed. Android lets
 * a foreground service start on such an event, but its GPS then needs the location "all the
 * time". Registrations do not survive a reboot or an app update: [MotionBootReceiver] renews them.
 */
object Motion {
    fun available(context: Context): Boolean = runCatching {
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    }.getOrDefault(false)

    /** Runtime permission for activity recognition (install-time before Android 10). */
    fun activityPermission(): String? = if (Build.VERSION.SDK_INT >= 29) Manifest.permission.ACTIVITY_RECOGNITION else null

    fun hasActivity(context: Context) = activityPermission()?.let { granted(context, it) } ?: true

    /** Location "all the time" (no separate permission before Android 10). */
    fun hasBackgroundLocation(context: Context) =
        granted(context, if (Build.VERSION.SDK_INT >= 29) Manifest.permission.ACCESS_BACKGROUND_LOCATION else Manifest.permission.ACCESS_FINE_LOCATION)

    fun ready(context: Context) = available(context) && hasActivity(context) && hasBackgroundLocation(context)

    /** Registers for vehicle enter / exit when the setting is on and everything is allowed, else unregisters. */
    @SuppressLint("MissingPermission")
    fun sync(context: Context) {
        UserSettings.init(context)
        if (!available(context)) return
        val client = ActivityRecognition.getClient(context)
        val events = pendingIntent(context)
        runCatching {
            if (UserSettings.state.value.motionStart && hasActivity(context) && hasBackgroundLocation(context)) {
                val transitions = listOf(ActivityTransition.ACTIVITY_TRANSITION_ENTER, ActivityTransition.ACTIVITY_TRANSITION_EXIT).map {
                    ActivityTransition.Builder().setActivityType(DetectedActivity.IN_VEHICLE).setActivityTransition(it).build()
                }
                client.requestActivityTransitionUpdates(ActivityTransitionRequest(transitions), events)
                    .addOnFailureListener { Log.w("Zenith", "activity transitions", it) }
            } else {
                client.removeActivityTransitionUpdates(events)
            }
        }.onFailure { Log.w("Zenith", "activity transitions", it) }
    }

    /** The phone started riding in a vehicle: start the drive, or ask for a tap if Android refuses. */
    fun vehicleEntered(context: Context) {
        UserSettings.init(context)
        if (!UserSettings.state.value.motionStart || DriveState.running.value) return
        val started = runCatching { DriveService.startMotion(context) }.isSuccess
        if (!started) CarLink.notifyTapToStart(context, "En voiture ?")
    }

    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** Mutable: Play Services add the transition events to it. */
    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        4,
        Intent(context, MotionReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0,
    )
}

/** Vehicle enter / exit events from Play Services. */
class MotionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!ActivityTransitionResult.hasResult(intent)) return
        val now = SystemClock.elapsedRealtimeNanos()
        // Events can come late (replayed after a registration): only the last few minutes count.
        val last = ActivityTransitionResult.extractResult(intent)?.transitionEvents.orEmpty()
            .filter { it.activityType == DetectedActivity.IN_VEHICLE && now - it.elapsedRealTimeNanos < MAX_AGE_NS }
            .maxByOrNull { it.elapsedRealTimeNanos } ?: return
        when (last.transitionType) {
            ActivityTransition.ACTIVITY_TRANSITION_ENTER -> Motion.vehicleEntered(context)
            ActivityTransition.ACTIVITY_TRANSITION_EXIT -> DriveService.motionExit(context)
        }
    }

    private companion object {
        const val MAX_AGE_NS = 3 * 60 * 1_000_000_000L
    }
}

/** Renews the activity recognition registration after a reboot or an update of Zenith. */
class MotionBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) Motion.sync(context)
    }
}
