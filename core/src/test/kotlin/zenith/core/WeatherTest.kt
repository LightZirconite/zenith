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
        assertTrue(!Weather.isWet(WeatherNow(0.0, 51)))
        assertTrue(Weather.isWet(WeatherNow(0.1, 51)))
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

    @Test
    fun `the sky on screen follows the weather code and the water`() {
        assertEquals(SkyLook.CLEAR, Sky.of(WeatherNow(0.0, 2)))
        assertEquals(SkyLook.CLEAR, Sky.of(WeatherNow(0.0, 51)))
        assertEquals(SkyKind.RAIN, Sky.of(WeatherNow(0.0, 61)).kind)
        assertEquals(SkyKind.RAIN, Sky.of(WeatherNow(0.3, 3)).kind)
        assertEquals(SkyKind.SNOW, Sky.of(WeatherNow(0.1, 73)).kind)
        assertEquals(SkyKind.STORM, Sky.of(WeatherNow(1.0, 95)).kind)
        assertEquals(SkyKind.FOG, Sky.of(WeatherNow(0.0, 45)).kind)
        // A downpour is stronger than a light rain, whatever the code says.
        assertTrue(Sky.of(WeatherNow(0.1, 61)).intensity < Sky.of(WeatherNow(0.1, 65)).intensity)
        assertEquals(1f, Sky.of(WeatherNow(3.0, 61)).intensity)
    }

    @Test
    fun `alerts and the music push the effect back`() {
        val rain = SkyLook(SkyKind.RAIN, 1f)
        assertEquals(1f, Sky.strength(rain, alerting = false, musicFocus = 0f))
        assertTrue(Sky.strength(rain, alerting = true, musicFocus = 0f) < 0.5f)
        assertTrue(Sky.strength(rain, alerting = false, musicFocus = 1f) < 0.5f)
        assertEquals(0f, Sky.strength(SkyLook.CLEAR, alerting = false, musicFocus = 0f))
    }
}
