package zenith.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CockpitTest {
    @Test
    fun `music grows when slow, the map takes over from town speeds`() {
        assertEquals(1f, Cockpit.musicBySpeed(0))
        assertEquals(1f, Cockpit.musicBySpeed(8))
        assertEquals(0f, Cockpit.musicBySpeed(40))
        assertEquals(0f, Cockpit.musicBySpeed(130))
        assertEquals(0f, Cockpit.musicBySpeed(null))
        // Smooth and always shrinking in between.
        var last = 1f
        for (v in 8..40) {
            val k = Cockpit.musicBySpeed(v)
            assertTrue(k <= last, "at $v km/h")
            last = k
        }
        assertEquals(0.5f, Cockpit.musicBySpeed(24), 0.01f)
    }

    @Test
    fun `an alert gives the map back, a tap overrides the speed`() {
        assertEquals(0f, Cockpit.music(0, choice = true, alerting = true))
        assertEquals(1f, Cockpit.music(110, choice = true, alerting = false))
        assertEquals(0f, Cockpit.music(0, choice = false, alerting = false))
        assertEquals(1f, Cockpit.music(0, choice = null, alerting = false))
    }

    @Test
    fun `a tap holds until the speed agrees with it`() {
        // Big asked on the road: kept while driving, handed back to the speed once stopped.
        assertEquals(true, Cockpit.keepChoice(true, 90))
        assertEquals(true, Cockpit.keepChoice(true, 20))
        assertNull(Cockpit.keepChoice(true, 3))
        // Small asked at a light: kept while slow, handed back once on the road.
        assertEquals(false, Cockpit.keepChoice(false, 0))
        assertEquals(false, Cockpit.keepChoice(false, 25))
        assertNull(Cockpit.keepChoice(false, 50))
        // Unknown speed: nothing changes.
        assertEquals(false, Cockpit.keepChoice(false, null))
        assertNull(Cockpit.keepChoice(null, 50))
        // The tap flips what is shown.
        assertTrue(Cockpit.toggle(0.2f))
        assertFalse(Cockpit.toggle(0.8f))
    }

    @Test
    fun `buttons step back on the road without flickering in town`() {
        var cruising = false
        val speeds = listOf(0, 10, 20, 29, 31, 25, 15, 13, 12, 20, 29, 30)
        val seen = speeds.map { cruising = Cockpit.cruising(it, cruising); cruising }
        assertEquals(listOf(false, false, false, false, true, true, true, true, false, false, false, true), seen)
        assertTrue(Cockpit.cruising(null, true))
    }
}
