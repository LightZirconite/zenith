package zenith.app

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import zenith.core.DriverProfile

/** A car paired for the automatic start: its CompanionDeviceManager association and its name. */
data class Car(val associationId: Int, val name: String)

/** User choices, kept in SharedPreferences. */
data class SettingsState(
    val probationary: Boolean = false,
    val raining: Boolean = false,
    /** False: alert tones only, no voice. */
    val voice: Boolean = true,
    /** The music pauses while the voice speaks (else it slides down and back up). */
    val pauseMusic: Boolean = false,
    /** The media volume (alerts and voice) raised to 60 % for a drive when lower, put back after. */
    val raiseVolume: Boolean = true,
    /** Battery saver: a drive started by hand ends by itself once the car is parked. */
    val autoStop: Boolean = true,
    /** Drive started by itself when the phone starts riding in a vehicle (activity recognition). */
    val motionStart: Boolean = true,
    /** Rain mode from the weather (Open-Meteo) while driving. */
    val autoRain: Boolean = true,
    /** Cars paired for the automatic start: any of them connecting starts the drive. */
    val cars: List<Car> = emptyList(),
    /** Spotify Canvas behind the music card and in picture-in-picture (unofficial free services). */
    val spotifyCanvas: Boolean = true,
    /** Square picture-in-picture window, larger; else a compact strip that leaves Waze visible. */
    val pipLarge: Boolean = false,
    /** Soft sounds under the finger on every button ([UiSound]); the alerts are not concerned. */
    val uiSounds: Boolean = true,
) {
    val profile: DriverProfile get() = DriverProfile(probationary = probationary, raining = raining)
}

/** Settings shared by the screen and the drive service; [init] is cheap and idempotent. */
object UserSettings {
    private const val PREFS = "zenith_settings"
    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state

    @Volatile
    private var loaded = false

    fun init(context: Context) {
        if (loaded) return
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _state.value = SettingsState(
            probationary = p.getBoolean("probationary", false),
            // Set by the weather during a drive, never kept from one drive to the next.
            raining = false,
            voice = p.getBoolean("voice", true),
            // New key: the voice now lowers the music by default, the old "pause" choice is not kept.
            pauseMusic = p.getBoolean("voice_pause", false),
            autoStop = p.getBoolean("auto_stop", true),
            raiseVolume = p.getBoolean("raise_volume", true),
            autoRain = p.getBoolean("auto_rain", true),
            motionStart = p.getBoolean("motion_start", true),
            cars = readCars(p.getString("cars", null))
                // The single car of earlier versions.
                ?: p.getInt("car_id", -1).takeIf { it >= 0 }?.let { listOf(Car(it, p.getString("car_name", null) ?: "Ta voiture")) }
                ?: emptyList(),
            spotifyCanvas = p.getBoolean("spotify_canvas", true),
            pipLarge = p.getBoolean("pip_large", false),
            uiSounds = p.getBoolean("ui_sounds", true),
        )
        loaded = true
    }

    fun update(context: Context, change: (SettingsState) -> SettingsState) {
        init(context)
        val next = change(_state.value)
        _state.value = next
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putBoolean("probationary", next.probationary)
            putBoolean("voice", next.voice)
            putBoolean("voice_pause", next.pauseMusic)
            putBoolean("auto_stop", next.autoStop)
            putBoolean("raise_volume", next.raiseVolume)
            putBoolean("auto_rain", next.autoRain)
            putBoolean("motion_start", next.motionStart)
            putString("cars", JSONArray(next.cars.map { JSONObject().put("id", it.associationId).put("name", it.name) }).toString())
            putBoolean("spotify_canvas", next.spotifyCanvas)
            putBoolean("pip_large", next.pipLarge)
            putBoolean("ui_sounds", next.uiSounds)
            remove("pause_music")
            remove("car_id")
            remove("car_name")
        }
    }

    private fun readCars(json: String?): List<Car>? = json?.let {
        runCatching {
            val array = JSONArray(it)
            List(array.length()) { i -> array.getJSONObject(i).let { o -> Car(o.getInt("id"), o.getString("name")) } }
        }.getOrNull()
    }
}
