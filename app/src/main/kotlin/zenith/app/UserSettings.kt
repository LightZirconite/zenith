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
            putInt("car_id", next.carAssociationId ?: -1)
            putString("car_name", next.carName)
        }
    }
}
