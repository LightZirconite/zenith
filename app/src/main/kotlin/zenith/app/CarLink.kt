package zenith.app

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import android.companion.ObservingDevicePresenceRequest
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat

/**
 * Automatic start in the car: the car's Bluetooth is paired with Zenith through Android's
 * companion device screen (no Bluetooth permission needed), then Android tells Zenith when the
 * car connects or disconnects. Android 13+.
 */
object CarLink {
    val supported: Boolean get() = Build.VERSION.SDK_INT >= 33

    /** Shows Android's device picker through [launch]; [onPaired] gets the chosen car. */
    @RequiresApi(33)
    fun pair(context: Context, launch: (IntentSender) -> Unit, onPaired: (AssociationInfo) -> Unit, onError: (String) -> Unit) {
        val request = AssociationRequest.Builder()
            .addDeviceFilter(BluetoothDeviceFilter.Builder().build())
            .setSingleDevice(false)
            .build()
        val cdm = context.getSystemService(CompanionDeviceManager::class.java)
        cdm.associate(request, context.mainExecutor, object : CompanionDeviceManager.Callback() {
            override fun onAssociationPending(intentSender: IntentSender) = launch(intentSender)

            override fun onAssociationCreated(associationInfo: AssociationInfo) {
                observe(context, associationInfo)
                onPaired(associationInfo)
            }

            override fun onFailure(error: CharSequence?) = onError(error?.toString() ?: "association refusée")
        })
    }

    /** Asks Android to report the car's connections to [CarPresenceService]. */
    @RequiresApi(33)
    @SuppressLint("MissingPermission")
    fun observe(context: Context, info: AssociationInfo) {
        val cdm = context.getSystemService(CompanionDeviceManager::class.java)
        runCatching {
            if (Build.VERSION.SDK_INT >= 36) {
                cdm.startObservingDevicePresence(ObservingDevicePresenceRequest.Builder().setAssociationId(info.id).build())
            } else {
                @Suppress("DEPRECATION")
                info.deviceMacAddress?.toString()?.let(cdm::startObservingDevicePresence)
            }
        }.onFailure { Log.w("Zenith", "car presence", it) }
    }

    @RequiresApi(33)
    fun unpair(context: Context, associationId: Int) {
        runCatching { context.getSystemService(CompanionDeviceManager::class.java).disassociate(associationId) }
            .onFailure { Log.w("Zenith", "car unpair", it) }
    }

    /** The car connected: start the drive, or ask for a tap when Android forbids a background start. */
    fun carConnected(context: Context) {
        val started = runCatching { DriveService.startAuto(context) }.isSuccess
        if (!started) notifyTapToStart(context)
    }

    fun carDisconnected(context: Context) = DriveService.stopAuto(context)

    /** Fallback notification: one tap starts the drive. */
    fun notifyTapToStart(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Voiture", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(
            context, 2, Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_START_DRIVE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Voiture connectée")
            .setContentText("Touche pour démarrer Zenith")
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    fun cancelTapToStart(context: Context) = context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)

    private const val CHANNEL_ID = "car"
    private const val NOTIFICATION_ID = 2
}

/** Woken by Android when the paired car connects or disconnects. */
@RequiresApi(33)
class CarPresenceService : CompanionDeviceService() {
    @RequiresApi(36)
    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        when (event.event) {
            DevicePresenceEvent.EVENT_BT_CONNECTED, DevicePresenceEvent.EVENT_BLE_APPEARED -> CarLink.carConnected(this)
            DevicePresenceEvent.EVENT_BT_DISCONNECTED, DevicePresenceEvent.EVENT_BLE_DISAPPEARED -> CarLink.carDisconnected(this)
        }
    }

    @Deprecated("Replaced by onDevicePresenceEvent on Android 16")
    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        if (Build.VERSION.SDK_INT < 36) CarLink.carConnected(this)
    }

    @Deprecated("Replaced by onDevicePresenceEvent on Android 16")
    override fun onDeviceDisappeared(associationInfo: AssociationInfo) {
        if (Build.VERSION.SDK_INT < 36) CarLink.carDisconnected(this)
    }
}
