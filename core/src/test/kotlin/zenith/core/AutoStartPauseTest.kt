package zenith.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutoStartPauseTest {
    @Test
    fun `no pause allows the automatic start`() = assertTrue(AutoStartPause.allows(0, 1_000_000))

    @Test
    fun `a stop by hand holds the automatic start for hours, not forever`() {
        val stop = 10_000_000L
        assertFalse(AutoStartPause.allows(stop, stop + 20_000))
        assertFalse(AutoStartPause.allows(stop, stop + 5 * 3_600_000L))
        assertTrue(AutoStartPause.allows(stop, stop + AutoStartPause.MAX_MS))
    }

    @Test
    fun `a clock set back never keeps the pause`() = assertTrue(AutoStartPause.allows(10_000_000, 5_000_000))

    @Test
    fun `only a drive that started by itself is paused by its stop`() {
        assertTrue(AutoStartPause.pausesOnStop(startedByItself = true))
        assertFalse(AutoStartPause.pausesOnStop(startedByItself = false))
    }
}
