package zenith.app

import android.content.Context
import android.location.LocationManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

    /** What the settings say: the last reading and when, or why there is none ("Météo à l'écran"). */
    private val _status = MutableStateFlow("Pas encore de relevé")
    val status: StateFlow<String> = _status

    private fun said(look: SkyLook, atMs: Long) {
        val c = java.util.Calendar.getInstance().apply { timeInMillis = atMs }
        val hhmm = "%02d:%02d".format(c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE))
        _status.value = "${name(look)} · relevé à $hhmm"
    }

    private fun name(l: SkyLook): String = when (l.kind) {
        SkyKind.CLEAR -> "Pas de pluie"
        SkyKind.RAIN -> if (l.intensity >= 0.65f) "Forte pluie" else "Pluie"
        SkyKind.SNOW -> "Neige"
        SkyKind.STORM -> "Orage"
        SkyKind.FOG -> "Brouillard"
    }

    /** A drive's weather report: the screen follows it. */
    fun report(now: WeatherNow) {
        readAtMs = System.currentTimeMillis()
        _look.value = Sky.of(now)
        said(_look.value, readAtMs)
    }

    private var demoJob: Job? = null

    /**
     * The hidden demo shows every weather in turn ([DEMO_SKIES], [DEMO_EACH_MS] each): rain, snow,
     * storm, fog. On screen only: the demo's limits and announcements stay those of a dry road.
     */
    fun demo(on: Boolean) {
        demoJob?.cancel()
        demoJob = null
        readAtMs = if (on) System.currentTimeMillis() else 0L
        if (!on) {
            _look.value = SkyLook.CLEAR
            return
        }
        demoJob = scope.launch {
            var i = 0
            while (true) {
                _look.value = DEMO_SKIES[i % DEMO_SKIES.size]
                i++
                delay(DEMO_EACH_MS)
            }
        }
    }

    /** At home: the sky where the phone last was, at most every [EVERY_MS]. */
    fun refreshAtHome(context: Context) {
        if (!UserSettings.state.value.weatherFx || DriveState.running.value || reading) return
        if (System.currentTimeMillis() - readAtMs < EVERY_MS) return
        val app = context.applicationContext
        reading = true
        scope.launch {
            try {
                val p = lastKnown(app) ?: run {
                    _status.value = "Position inconnue pour l'instant"
                    return@launch
                }
                val now = Weather.parse(httpGet(Weather.url(p))) ?: run {
                    _status.value = "Réponse météo illisible"
                    return@launch
                }
                // A drive started meanwhile reads its own.
                if (!DriveState.running.value) report(now)
            } catch (e: Exception) {
                Log.w("Zenith", "home weather", e)
                _status.value = "Relevé impossible (réseau)"
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
    private const val DEMO_EACH_MS = 45_000L
    private val DEMO_SKIES = listOf(
        SkyLook(SkyKind.RAIN, 0.55f),
        SkyLook(SkyKind.SNOW, 0.7f),
        SkyLook(SkyKind.STORM, 1f),
        SkyLook(SkyKind.FOG, 0.8f),
    )
}
