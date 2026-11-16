package zenith.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.math.abs
import kotlin.math.floor

/** Weather now at a place: precipitation over the last 15 minutes and the WMO weather code. */
data class WeatherNow(val precipitationMm: Double, val weatherCode: Int?)

/** Open-Meteo current weather (free, no key, nothing to register). */
object Weather {
    /**
     * Drizzle, rain, freezing rain, snow, showers, thunderstorms (WMO codes). Fog is not rain, nor
     * is a light drizzle (51) with no measurable water: the model gives it often on a dry road, and
     * lowering the limits then would show wrong ones.
     */
    private val WET_CODES = setOf(53, 55, 56, 57, 61, 63, 65, 66, 67, 71, 73, 75, 77, 80, 81, 82, 85, 86, 95, 96, 99)

    /**
     * Request for [p] rounded to 0.1° (~10 km): plenty for the weather, and little about where the car is.
     * Météo-France's models (AROME 1.3 km over France, ARPEGE elsewhere): the default blend often said
     * dry under a real rain (16/11/2026).
     */
    fun url(p: LatLon): String =
        "https://api.open-meteo.com/v1/forecast?latitude=${oneDecimal(p.lat)}&longitude=${oneDecimal(p.lon)}" +
            "&current=precipitation,weather_code&models=meteofrance_seamless"

    /** [x] with one decimal, half away from zero ("%.1f" without java.util.Formatter, absent on iOS). */
    internal fun oneDecimal(x: Double): String {
        val tenths = floor(abs(x) * 10 + 0.5).toLong()
        val sign = if (x < 0 || 1.0 / x < 0) "-" else ""
        return "$sign${tenths / 10}.${tenths % 10}"
    }

    /** The "current" block of an Open-Meteo answer; null when it is missing or unreadable. */
    fun parse(text: String): WeatherNow? = runCatching {
        val current = Json.parseToJsonElement(text) as? JsonObject ?: return null
        val now = current["current"] as? JsonObject ?: return null
        WeatherNow(
            precipitationMm = (now["precipitation"] as? JsonPrimitive)?.doubleOrNull ?: return null,
            weatherCode = (now["weather_code"] as? JsonPrimitive)?.intOrNull,
        )
    }.getOrNull()

    fun isWet(w: WeatherNow): Boolean = w.precipitationMm >= 0.1 || w.weatherCode in WET_CODES
}

/** What the sky looks like on screen. */
enum class SkyKind { CLEAR, RAIN, SNOW, STORM, FOG }

/** The weather drawn over the screen: [intensity] from 0 (none) to 1 (downpour, thick fog). */
data class SkyLook(val kind: SkyKind, val intensity: Float) {
    companion object {
        val CLEAR = SkyLook(SkyKind.CLEAR, 0f)
    }
}

/**
 * Weather effects on screen (rain, snow, storm, fog), the same rules at home, on the drive screen
 * and in the small window. They show the driver why the limits went down; they never hide the road,
 * the speed, an alert or the music's Canvas.
 */
object Sky {
    private val STORM = setOf(95, 96, 99)
    private val SNOW = setOf(71, 73, 75, 77, 85, 86)
    private val FOG = setOf(45, 48)
    private val LIGHT = setOf(51, 53, 56, 61, 66, 71, 77, 80, 85)
    private val MODERATE = setOf(55, 57, 63, 73, 81)
    private val HEAVY = setOf(65, 67, 75, 82, 86, 95, 96, 99)

    /** Look of [w]: kind from the WMO code, strength from the code and the water measured. */
    fun of(w: WeatherNow): SkyLook {
        val code = w.weatherCode
        val kind = when {
            code in STORM -> SkyKind.STORM
            code in SNOW -> SkyKind.SNOW
            Weather.isWet(w) -> SkyKind.RAIN
            code in FOG -> SkyKind.FOG
            else -> return SkyLook.CLEAR
        }
        if (kind == SkyKind.FOG) return SkyLook(kind, if (code == 48) 0.9f else 0.7f)
        val byCode = when (code) {
            in HEAVY -> 1f
            in MODERATE -> 0.65f
            in LIGHT -> 0.35f
            else -> 0.3f
        }
        // Precipitation of the last 15 minutes: 2 mm (8 mm/h) is a downpour.
        val byWater = (w.precipitationMm / 2.0).toFloat().coerceIn(0f, 1f)
        return SkyLook(kind, maxOf(byCode, byWater).coerceIn(0.3f, 1f))
    }

    /**
     * How strongly the effect shows: whole at home or cruising; an alert or an average-speed section
     * on screen pushes it back (the alert is the hero), and in music mode it gives way to the Canvas.
     * [musicFocus] 0 (the map has the screen) to 1 (the Canvas has it).
     */
    fun strength(look: SkyLook, alerting: Boolean, musicFocus: Float): Float {
        if (look.kind == SkyKind.CLEAR) return 0f
        val alert = if (alerting) ALERT_SHARE else 1f
        val music = 1f - (1f - MUSIC_SHARE) * musicFocus.coerceIn(0f, 1f)
        return (look.intensity * alert * music).coerceIn(0f, 1f)
    }

    /** Lightning now and then in a storm (never during an alert): the next one in [minMs]..[maxMs]. */
    const val FLASH_MIN_MS = 7_000L
    const val FLASH_MAX_MS = 18_000L

    private const val ALERT_SHARE = 0.35f
    private const val MUSIC_SHARE = 0.4f
}

/**
 * Rain mode from the weather, for the French rain limits ("par temps de pluie ou d'autres
 * précipitations"). On at the first wet report, off after [dryReports] dry ones in a row, so a gap
 * in a shower does not flip the limits back and forth.
 */
class RainDetector(private val dryReports: Int = 2) {
    var raining = false
        private set
    private var dry = 0

    /** Returns the rain mode after [w]. */
    fun report(w: WeatherNow): Boolean {
        if (Weather.isWet(w)) {
            raining = true
            dry = 0
        } else if (raining && ++dry >= dryReports) {
            raining = false
            dry = 0
        }
        return raining
    }
}
