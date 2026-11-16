package zenith.app

import android.content.Context
import android.location.LocationManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import zenith.core.LatLon
import zenith.core.Sky
import zenith.core.SkyKind
import zenith.core.SkyLook
import zenith.core.Weather
import zenith.core.WeatherNow
import java.net.HttpURLConnection
import java.net.URL

/**
 * The weather drawn over the screen ([WeatherLayer]): read at home from the last known position
 * as soon as the app opens, then from each of the drive's weather reports (DriveService). Nothing
 * is kept: a new launch reads the sky again.
 */
object SkyWatch {
    private val _look = MutableStateFlow(SkyLook.CLEAR)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile
    private var readAtMs = 0L
    @Volatile
    private var reading = false

    val look: StateFlow<SkyLook> = _look

    /** A drive's weather report: the screen follows it. */
    fun report(now: WeatherNow) {
        readAtMs = System.currentTimeMillis()
        _look.value = Sky.of(now)
    }

    /** The hidden demo drives in a light rain, so the effect has its place there too. */
    fun demo(on: Boolean) {
        _look.value = if (on) SkyLook(SkyKind.RAIN, 0.55f) else SkyLook.CLEAR
        readAtMs = if (on) System.currentTimeMillis() else 0L
    }

    /** At home: the sky where the phone last was, at most every [EVERY_MS]. */
    fun refreshAtHome(context: Context) {
        if (!UserSettings.state.value.weatherFx || DriveState.running.value || reading) return
        if (System.currentTimeMillis() - readAtMs < EVERY_MS) return
        val app = context.applicationContext
        reading = true
        scope.launch {
            try {
                val p = lastKnown(app) ?: return@launch
                val now = Weather.parse(httpGet(Weather.url(p))) ?: return@launch
                // A drive started meanwhile reads its own.
                if (!DriveState.running.value) report(now)
            } catch (e: Exception) {
                Log.w("Zenith", "home weather", e)
            } finally {
                reading = false
            }
        }
    }

    private fun lastKnown(context: Context): LatLon? = runCatching {
        val manager = context.getSystemService(LocationManager::class.java)
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
            ?.let { LatLon(it.latitude, it.longitude) }
    }.getOrNull()

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

    private const val EVERY_MS = 10 * 60_000L
}
