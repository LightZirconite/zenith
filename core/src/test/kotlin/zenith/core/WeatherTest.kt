package zenith.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WeatherTest {
    private fun answer(mm: Double, code: Int) = """
        {"latitude":48.8,"longitude":2.3,"generationtime_ms":0.05,"utc_offset_seconds":0,"timezone":"GMT",
         "current_units":{"time":"iso8601","interval":"seconds","precipitation":"mm","weather_code":"wmo code"},
         "current":{"time":"2026-10-28T11:00","interval":900,"precipitation":$mm,"weather_code":$code}}
    """.trimIndent()

    @Test
    fun `reads the current block of an Open-Meteo answer`() {
        assertEquals(WeatherNow(0.4, 61), Weather.parse(answer(0.4, 61)))
        assertNull(Weather.parse("""{"error":true,"reason":"Latitude must be in range"}"""))
        assertNull(Weather.parse("not json"))
    }

    @Test
    fun `rain, drizzle and snow are wet, fog and clouds are not`() {
        assertTrue(Weather.isWet(WeatherNow(0.0, 53)))
        assertTrue(Weather.isWet(WeatherNow(0.0, 73)))
        assertTrue(Weather.isWet(WeatherNow(0.2, 3)))
        assertTrue(!Weather.isWet(WeatherNow(0.0, 45)))
        assertTrue(!Weather.isWet(WeatherNow(0.0, 3)))
    }

    @Test
    fun `rain mode starts at once and ends after two dry reports`() {
        val rain = RainDetector()
        val wet = WeatherNow(0.5, 61)
        val dry = WeatherNow(0.0, 2)
        // Wet → on; one dry report keeps it; wet again resets the count; two dry in a row → off.
        assertEquals(listOf(false, true, true, true, true, false), listOf(dry, wet, dry, wet, dry, dry).map(rain::report))
    }

    @Test
    fun `request is rounded to about ten kilometers`() {
        assertEquals(
            "https://api.open-meteo.com/v1/forecast?latitude=48.9&longitude=2.4&current=precipitation,weather_code",
            Weather.url(LatLon(48.8566, 2.3522)),
        )
    }
}
