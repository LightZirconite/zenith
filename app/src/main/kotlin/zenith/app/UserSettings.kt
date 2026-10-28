package zenith.app

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import zenith.core.DriverProfile

/** User choices, kept in SharedPreferences. */
data class SettingsState(
    val probationary: Boolean = false,
    val raining: Boolean = false,
    /** False: alert tones only, no voice. */
    val voice: Boolean = true,
    /** Battery saver: a drive started by hand ends by itself once the car is parked. */
    val autoStop: Boolean = true,
    /** Drive started by itself when the phone starts riding in a vehicle (activity recognition). */
    val motionStart: Boolean = true,
    /** Rain mode from the weather (Open-Meteo) while driving. */
    val autoRain: Boolean = true,
    /** The driver set rain by hand during this drive: the weather no longer changes it. Not kept. */
    val rainManual: Boolean = false,
    /** Car paired for the automatic start (CompanionDeviceManager association), with its name. */
    val carAssociationId: Int? = null,
    val carName: String? = null,
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
            // Rain is not kept from one drive to the next: it is a quick toggle for today.
            raining = false,
            voice = p.getBoolean("voice", true),
            autoStop = p.getBoolean("auto_stop", true),
            autoRain = p.getBoolean("auto_rain", true),
            motionStart = p.getBoolean("motion_start", true),
            carAssociationId = p.getInt("car_id", -1).takeIf { it >= 0 },
            carName = p.getString("car_name", null),
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
            putBoolean("auto_stop", next.autoStop)
            putBoolean("auto_rain", next.autoRain)
            putBoolean("motion_start", next.motionStart)
            putInt("car_id", next.carAssociationId ?: -1)
            putString("car_name", next.carName)
        }
    }
}
