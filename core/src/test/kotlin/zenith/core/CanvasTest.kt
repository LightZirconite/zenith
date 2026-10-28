package zenith.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CanvasTest {
    private val id = "3OHfY25tqY28d16oZczHc8"
    private val video = "https://canvaz.scdn.co/upload/licensor/5bSw7fRotCnRCcO9br14W5/video/60e9129aa6d14c5ea2ccb3626a692bb1.cnvs.mp4"

    // Real answers (October 2026), shortened.
    private val spicyFound = """{"object":"canvaslist.entries","request_id":"req_01a1","lookup":{"type":"track_id","value":["$id"]},
        "data":{"$id":{"url":"$video","type":"VIDEO_LOOPING_RANDOM","fileId":"5ea1c72b","uri":"spotify:canvas:2WRMefgM4xJ06PE23nFkl3"}}}"""
    private val spicyNone = """{"object":"canvaslist.entries","lookup":{"type":"track_id","value":["$id"]},"data":{"$id":null}}"""
    private val auraFound = """{"success":true,"url":"$video","canvasUrl":"$video","trackId":"$id","trackUri":"spotify:track:$id",
        "canvasesList":[{"id":"60e9","canvasUrl":"$video","trackUri":"spotify:track:$id","otherId":"","canvasUri":"spotify:canvas:2WRM"}]}"""
    private val auraNone = """{"success":false,"error":"No canvas found for provided track(s)"}"""

    @Test
    fun `reads Spicy Lyrics' canvas list`() {
        val s = CanvasService.SPICY_LYRICS
        assertEquals("https://regional.canvasstore.slnode.spicylyrics.org/provider/canvaslist/$id", s.url(id))
        assertEquals(CanvasAnswer.Video(video), s.parse(HttpAnswer(200, spicyFound), id))
        assertEquals(CanvasAnswer.None, s.parse(HttpAnswer(200, spicyNone), id))
        // A still image is no video; an error, another track or a server failure is not "none".
        assertEquals(CanvasAnswer.None, s.parse(HttpAnswer(200, spicyFound.replace(video, "https://i.scdn.co/image/ab67616d.jpg")), id))
        assertNull(s.parse(HttpAnswer(200, """{"data":{"$id":{"error":"upstream"}}}"""), id))
        assertNull(s.parse(HttpAnswer(200, spicyFound), "2plbrEY59IikOBgBGLjaoe"))
        assertNull(s.parse(HttpAnswer(502, ""), id))
        assertNull(s.parse(HttpAnswer(200, "<html>Just a moment…</html>"), id))
    }

    @Test
    fun `reads AuraMusic's canvas answer`() {
        val s = CanvasService.AURA_MUSIC
        assertEquals("https://canvas.auramusic.site/api/canvas?trackId=$id", s.url(id))
        assertEquals(CanvasAnswer.Video(video), s.parse(HttpAnswer(200, auraFound), id))
        assertEquals(CanvasAnswer.None, s.parse(HttpAnswer(404, auraNone), id))
        assertNull(s.parse(HttpAnswer(200, auraFound), "2plbrEY59IikOBgBGLjaoe"))
        assertNull(s.parse(HttpAnswer(500, "oops"), id))
    }

    @Test
    fun `only HTTPS MP4s from Spotify's CDN are videos`() {
        assertTrue(CanvasService.isVideoUrl(video))
        assertTrue(!CanvasService.isVideoUrl(video.replace("https://", "http://")))
        assertTrue(!CanvasService.isVideoUrl("https://example.com/video.mp4"))
        assertTrue(!CanvasService.isVideoUrl("https://canvaz.scdn.co.evil.com/a.mp4"))
        assertTrue(!CanvasService.isVideoUrl("https://canvaz.scdn.co/upload/a.jpg"))
    }

    /** Fake services: answers by URL prefix, and a log of what was asked. */
    private class Fake(var spicy: HttpAnswer?, var aura: HttpAnswer?) {
        val asked = mutableListOf<String>()
        fun get(url: String): HttpAnswer? {
            asked += url.substringBefore(".site").substringBefore(".org").substringAfterLast('.')
            return if ("spicylyrics" in url) spicy else aura
        }
    }

    @Test
    fun `asks the second service only when the first fails, and remembers answers`() {
        var now = 0L
        val fake = Fake(HttpAnswer(200, spicyNone), HttpAnswer(200, auraFound))
        val finder = CanvasFinder(fake::get, { now })
        // "None" from the first one is believed.
        assertNull(finder.find(id))
        assertEquals(listOf("spicylyrics"), fake.asked)
        // Remembered for a day, then asked again.
        now += CanvasFinder.NONE_TTL_MS - 1
        assertNull(finder.find(id))
        assertEquals(1, fake.asked.size)
        now += 2
        fake.spicy = HttpAnswer(503, "")
        assertEquals(video, finder.find(id))
        assertEquals(listOf("spicylyrics", "spicylyrics", "auramusic"), fake.asked)
        // A video is kept for days, without asking.
        now += CanvasFinder.VIDEO_TTL_MS - 1
        assertEquals(video, finder.find(id))
        assertEquals(3, fake.asked.size)
        // Forgotten (it would not play): asked again.
        finder.forget(id)
        assertEquals(video, finder.find(id))
        assertEquals(5, fake.asked.size)
    }

    @Test
    fun `pauses longer after each failure of every service`() {
        var now = 0L
        val fake = Fake(null, HttpAnswer(502, ""))
        val finder = CanvasFinder(fake::get, { now })
        assertNull(finder.find(id))
        assertEquals(2, fake.asked.size)
        // One minute without asking, then two after a second failure.
        now += CanvasFinder.FIRST_PAUSE_MS - 1
        assertNull(finder.find("2plbrEY59IikOBgBGLjaoe"))
        assertEquals(2, fake.asked.size)
        now += 1
        assertNull(finder.find(id))
        assertEquals(4, fake.asked.size)
        now += CanvasFinder.FIRST_PAUSE_MS
        assertNull(finder.find(id))
        assertEquals(4, fake.asked.size)
        // Back up: the pause ends at once.
        now += CanvasFinder.FIRST_PAUSE_MS
        fake.spicy = HttpAnswer(200, spicyFound)
        assertEquals(video, finder.find(id))
        fake.spicy = HttpAnswer(200, spicyNone.replace(id, "2plbrEY59IikOBgBGLjaoe"))
        assertNull(finder.find("2plbrEY59IikOBgBGLjaoe"))
        assertEquals(6, fake.asked.size)
    }

    @Test
    fun `a broken request or a bad id asks nobody`() {
        val finder = CanvasFinder({ error("no network") })
        assertNull(finder.find(id))
        var asked = 0
        assertNull(CanvasFinder({ asked++; null }).find("not-a-track-id"))
        assertEquals(0, asked)
    }
}
