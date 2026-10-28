package zenith.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CanvasSearchTest {
    private val video = "https://canvaz.scdn.co/upload/artist/7IlRNXHjoOCgEAWN5qYksg/video/779bd210c17c4becb062f9c54ebf45a7.cnvs.mp4"
    private val found = """{"success":true,"url":"$video","canvasUrl":"$video","trackId":"7sKDftgGirHWTVFMtJoDoh"}"""
    private val none = """{"success":false,"error":"No Spotify track with canvas found for the given song/artist/album"}"""

    @Test
    fun urlCarriesTheWords() {
        val url = CanvasSearch.url("Djadja", "Aya Nakamura", null, 170_000)
        assertTrue(url.startsWith("https://canvas.auramusic.site/api/canvas?song=Djadja"))
        assertTrue("artist=Aya+Nakamura" in url && "durationMs=170000" in url)
    }

    @Test
    fun readsAMatchAndNothingElse() {
        assertEquals(CanvasAnswer.Found(CanvasMatch("7sKDftgGirHWTVFMtJoDoh", video)), CanvasSearch.parse(HttpAnswer(200, found)))
        assertEquals(CanvasAnswer.None, CanvasSearch.parse(HttpAnswer(200, none)))
        assertEquals(CanvasAnswer.None, CanvasSearch.parse(HttpAnswer(404, none)))
        // A video off Spotify's CDN is never downloaded.
        assertEquals(CanvasAnswer.None, CanvasSearch.parse(HttpAnswer(200, found.replace("canvaz.scdn.co", "evil.example.com"))))
        assertNull(CanvasSearch.parse(HttpAnswer(503, "")))
    }

    @Test
    fun searchIsRememberedUnderBothKeys() {
        var asked = 0
        val finder = CanvasFinder({ asked++; HttpAnswer(200, found) })
        val match = finder.search("Djadja", "Aya Nakamura", null, null)
        assertEquals("7sKDftgGirHWTVFMtJoDoh", match?.trackId)
        // Same song, other case and accents: no second request; nor for its Spotify id.
        assertEquals(match, finder.search("DJADJA", "Áya Nakamura", null, null))
        assertEquals(video, finder.find("7sKDftgGirHWTVFMtJoDoh"))
        assertEquals(1, asked)
    }
}
