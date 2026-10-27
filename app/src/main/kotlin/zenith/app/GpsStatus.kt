package zenith.app

import android.annotation.SuppressLint
import android.location.GnssStatus
import android.location.LocationManager
import android.os.Handler
import android.os.Looper

/** Satellites seen and used, from the platform GNSS status. */
data class GpsInfo(val used: Int = 0, val visible: Int = 0, val accuracyM: Double? = null, val lastFixMs: Long? = null)

class GpsStatusWatcher(private val manager: LocationManager, private val onChange: (used: Int, visible: Int) -> Unit) {
    private val callback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            for (i in 0 until status.satelliteCount) if (status.usedInFix(i)) used++
            onChange(used, status.satelliteCount)
        }
    }

    @SuppressLint("MissingPermission") // Started by DriveService after the location permission check.
    fun start() {
        manager.registerGnssStatusCallback(callback, Handler(Looper.getMainLooper()))
    }

    fun stop() = manager.unregisterGnssStatusCallback(callback)
}
