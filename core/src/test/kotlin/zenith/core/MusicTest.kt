package zenith.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MusicTest {
    private val id = "3OHfY25tqY28d16oZczHc8"

    @Test
    fun `track id from Spotify URIs and links only`() {
        assertEquals(id, SpotifyTrack.idOf("spotify:track:$id"))
        assertEquals(id, SpotifyTrack.idOf("https://open.spotify.com/track/$id?si=abc123"))
        assertEquals(id, SpotifyTrack.idOf("https://open.spotify.com/intl-fr/track/$id"))
        assertNull(SpotifyTrack.idOf("spotify:episode:$id"))
        assertNull(SpotifyTrack.idOf("spotify:local:Artist:Album:Title:215"))
        assertNull(SpotifyTrack.idOf("spotify:track:tooShort"))
        assertNull(SpotifyTrack.idOf("0.2981739661"))
        assertNull(SpotifyTrack.idOf(null))
    }

    @Test
    fun `only the Spotify app's session gives a track id`() {
        assertEquals(id, SpotifyTrack.idFromSession("com.spotify.music", "spotify:track:$id", null))
        // The id may be in the media URI instead.
        assertEquals(id, SpotifyTrack.idFromSession("com.spotify.music", "playlist-index-3", "spotify:track:$id"))
        assertNull(SpotifyTrack.idFromSession("com.google.android.apps.youtube.music", "spotify:track:$id", null))
        assertNull(SpotifyTrack.idFromSession("com.spotify.music", null, null))
    }

    @Test
    fun `progress moves with the clock while playing and stays put when paused`() {
        val p = PlaybackProgress.of(positionMs = 60_000, updatedAtMs = 1_000, speed = 1f, durationMs = 240_000)!!
        assertEquals(70_000, p.positionAt(11_000, playing = true))
        assertEquals(60_000, p.positionAt(11_000, playing = false))
        assertEquals(0.25f, p.fractionAt(1_000, playing = true))
        // Never past the end, nor before the start (clock read before the update).
        assertEquals(240_000, p.positionAt(1_000_000, playing = true))
        assertEquals(60_000, p.positionAt(0, playing = true))
        // Faster playback (podcasts at 1.5×).
        assertEquals(75_000, p.copy(speed = 1.5f).positionAt(11_000, playing = true))
    }

    @Test
    fun `no progress line without a duration or a position`() {
        assertNull(PlaybackProgress.of(10_000, 0, 1f, null))
        assertNull(PlaybackProgress.of(10_000, 0, 1f, 0))
        // PlaybackState.PLAYBACK_POSITION_UNKNOWN
        assertNull(PlaybackProgress.of(-1, 0, 1f, 200_000))
        assertEquals(1f, PlaybackProgress.of(0, 0, Float.NaN, 200_000)!!.speed)
    }
}
