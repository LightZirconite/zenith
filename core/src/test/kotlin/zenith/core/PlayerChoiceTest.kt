package zenith.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlayerChoiceTest {
    private val spotify = PlayerSession("s", SpotifyTrack.PACKAGE, playing = false, hasTitle = true)
    private val youtube = PlayerSession("y", VideoTitle.YOUTUBE, playing = false, hasTitle = true)
    private val deezer = PlayerSession("d", "com.deezer.android.app", playing = false, hasTitle = true)

    @Test
    fun `nothing playing shows the music app rather than a paused video, even an older one`() {
        assertEquals("s", PlayerChoice.pick(listOf(youtube, spotify), null)?.id)
    }

    @Test
    fun `a video playing wins over a paused music app`() {
        assertEquals("y", PlayerChoice.pick(listOf(spotify, youtube.copy(playing = true)), "s")?.id)
    }

    @Test
    fun `music playing wins over a video playing`() {
        assertEquals("s", PlayerChoice.pick(listOf(youtube.copy(playing = true), spotify.copy(playing = true)), "y")?.id)
    }

    @Test
    fun `back to the music app once the video is paused`() {
        assertEquals("s", PlayerChoice.pick(listOf(youtube, spotify), "y")?.id)
    }

    @Test
    fun `same rank keeps the one shown, else the most recent`() {
        assertEquals("d", PlayerChoice.pick(listOf(spotify, deezer), "d")?.id)
        assertEquals("s", PlayerChoice.pick(listOf(spotify, deezer), null)?.id)
    }

    @Test
    fun `sessions without anything to show are ignored`() {
        assertEquals("y", PlayerChoice.pick(listOf(spotify.copy(hasTitle = false), youtube), null)?.id)
        assertNull(PlayerChoice.pick(listOf(spotify.copy(hasTitle = false)), null))
    }
}
