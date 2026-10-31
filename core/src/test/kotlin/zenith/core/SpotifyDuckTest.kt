package zenith.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpotifyDuckTest {
    private val phone = listOf("Pixel 8", "Téléphone de Lucas")

    private fun duck(
        type: String? = "Automobile",
        name: String? = "Peugeot 208",
        volume: Boolean = true,
        playing: Boolean = true,
        premium: Boolean? = true,
    ) = SpotifyDuck.shouldDuck(type, name, volume, playing, premium, phone)

    @Test
    fun `music on the car or a speaker is lowered`() {
        assertTrue(duck())
        assertTrue(duck(type = "Speaker", name = "Salon"))
        assertTrue(duck(type = null, name = null))
    }

    @Test
    fun `this phone is left to Android's own ducking`() {
        assertFalse(duck(type = "Smartphone", name = "Pixel 8"))
        assertFalse(duck(type = "smartphone", name = " téléphone de lucas "))
        assertFalse(duck(type = "Smartphone", name = null))
    }

    @Test
    fun `another phone is lowered`() {
        assertTrue(duck(type = "Smartphone", name = "iPhone de Marie"))
    }

    @Test
    fun `nothing to lower without volume, music or Premium`() {
        assertFalse(duck(volume = false))
        assertFalse(duck(playing = false))
        assertFalse(duck(premium = false))
        // Unknown yet: tried, Spotify's answer tells.
        assertTrue(duck(premium = null))
    }

    @Test
    fun `ducked to a quarter, never below the floor nor above the volume`() {
        assertEquals(20, SpotifyDuck.duckedVolume(80))
        assertEquals(25, SpotifyDuck.duckedVolume(100))
        assertEquals(10, SpotifyDuck.duckedVolume(30))
        assertEquals(6, SpotifyDuck.duckedVolume(6))
        assertEquals(0, SpotifyDuck.duckedVolume(0))
        assertEquals(25, SpotifyDuck.duckedVolume(140))
    }

    @Test
    fun `the volume goes back unless the driver changed it`() {
        assertEquals(80, SpotifyDuck.restoreVolume(before = 80, duckedTo = 20, now = 20))
        assertEquals(80, SpotifyDuck.restoreVolume(before = 80, duckedTo = 20, now = null))
        assertNull(SpotifyDuck.restoreVolume(before = 80, duckedTo = 20, now = 45))
        assertNull(SpotifyDuck.restoreVolume(before = 6, duckedTo = 6, now = 6))
    }
}
