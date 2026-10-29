package zenith.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LoudnessTest {
    @Test
    fun `an ordinary phone volume leaves the sounds as made`() {
        assertEquals(1f, Loudness.uiGain(7, 15))
        assertEquals(1f, Loudness.uiGain(3, 15))
        assertEquals(1f, Loudness.uiGain(0, 15))
    }

    @Test
    fun `a volume at its top turns them down by 10 dB`() {
        assertEquals(0.316f, Loudness.uiGain(30, 30), 0.001f)
    }

    @Test
    fun `the higher the volume the lower the gain, never above 1`() {
        val gains = (0..30).map { Loudness.uiGain(it, 30) }
        assertTrue(gains.zipWithNext().all { (a, b) -> b <= a })
        assertTrue(gains.all { it in 0f..1f })
    }

    @Test
    fun `no volume range means no change`() {
        assertEquals(1f, Loudness.uiGain(5, 0))
    }
}
