package zenith.app

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Raw GPS fixes at 1 Hz from the platform provider (no Google Play Services needed). */
@SuppressLint("MissingPermission") // Callers collect only after the permission is granted.
fun gpsFixes(context: Context): Flow<Location> = callbackFlow {
    val manager = context.getSystemService(LocationManager::class.java)
    val listener = LocationListener { trySend(it) }
    manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, listener, Looper.getMainLooper())
    awaitClose { manager.removeUpdates(listener) }
}
