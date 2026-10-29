package zenith.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LoudnessTest {
    @Test
    fun `up to about half volume the sounds play as made`() {
        assertEquals(1f, Loudness.uiGain(7, 15))
        assertEquals(1f, Loudness.uiGain(3, 15))
        assertEquals(1f, Loudness.uiGain(0, 15))
    }

    @Test
    fun `at 80 percent they sound as at half volume`() {
        // Speaker curve: 80 % is -5.5 dB, the cap -17 dB, so 11.5 dB down.
        assertEquals(0.266f, Loudness.uiGain(12, 15), 0.002f)
    }

    @Test
    fun `at the top they are 17 dB down`() {
        assertEquals(0.141f, Loudness.uiGain(30, 30), 0.002f)
    }

    @Test
    fun `the higher the volume the lower the gain, never above 1`() {
        val gains = (0..30).map { Loudness.uiGain(it, 30) }
        assertTrue(gains.zipWithNext().all { (a, b) -> b <= a })
        assertTrue(gains.all { it in 0f..1f })
    }

    @Test
    fun `the curve follows Android's points`() {
        assertEquals(-34.0, Loudness.streamDb(0.2), 1e-9)
        assertEquals(-11.0, Loudness.streamDb(0.6), 1e-9)
        assertEquals(0.0, Loudness.streamDb(1.0), 1e-9)
        assertEquals(-56.0, Loudness.streamDb(0.005), 1e-9)
    }

    @Test
    fun `no volume range means no change`() {
        assertEquals(1f, Loudness.uiGain(5, 0))
    }
}
