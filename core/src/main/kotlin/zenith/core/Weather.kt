package zenith.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import java.util.Locale

/** Weather now at a place: precipitation over the last 15 minutes and the WMO weather code. */
data class WeatherNow(val precipitationMm: Double, val weatherCode: Int?)

/** Open-Meteo current weather (free, no key, nothing to register). */
object Weather {
    /** Drizzle, rain, freezing rain, snow, showers, thunderstorms (WMO codes). Fog is not rain. */
    private val WET_CODES = setOf(51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 71, 73, 75, 77, 80, 81, 82, 85, 86, 95, 96, 99)

    /** Request for [p] rounded to 0.1° (~10 km): plenty for the weather, and little about where the car is. */
    fun url(p: LatLon): String = String.format(
        Locale.ROOT,
        "https://api.open-meteo.com/v1/forecast?latitude=%.1f&longitude=%.1f&current=precipitation,weather_code",
        p.lat,
        p.lon,
    )

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
