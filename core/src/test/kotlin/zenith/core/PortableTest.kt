package zenith.core

import java.net.URLEncoder
import java.time.Instant
import java.util.PriorityQueue
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/** The plain-Kotlin stand-ins for java.* (core also runs on iOS) give exactly what Java gave. */
class PortableTest {
    @Test
    fun `angles match java Math`() {
        val rnd = Random(7)
        repeat(10_000) {
            val d = rnd.nextDouble(-720.0, 720.0)
            assertEquals(Math.toRadians(d), rad(d))
            assertEquals(Math.toDegrees(d), deg(d))
        }
    }

    @Test
    fun `gpx time matches java Instant`() {
        val rnd = Random(11)
        val samples = listOf(0L, 1L, 999L, 1_000L, -1L, 951_782_400_000L, 1_793_445_909_123L) +
            List(5_000) { rnd.nextLong(-2_000_000_000_000L, 4_000_000_000_000L) }
        for (ms in samples) assertEquals(Instant.ofEpochMilli(ms).toString(), Gpx.isoInstant(ms), "ms=$ms")
    }

    @Test
    fun `canvas search url encodes like URLEncoder`() {
        val title = "Été à l'ouest * (feat. Ñandú) & co + 100% ~ 日本"
        val url = CanvasSearch.url(title, "Ninho", null, null)
        assertEquals(
            "https://canvas.auramusic.site/api/canvas?song=" + URLEncoder.encode(title, "UTF-8") + "&title=" +
                URLEncoder.encode(title, "UTF-8") + "&artist=Ninho",
            url,
        )
    }

    @Test
    fun `heap polls in PriorityQueue order, ties included`() {
        val rnd = Random(3)
        repeat(200) {
            val items = List(rnd.nextInt(1, 60)) { i -> rnd.nextInt(0, 8) to i }
            val cmp = compareBy<Pair<Int, Int>> { it.first }
            val pq = PriorityQueue(cmp)
            val heap = MinHeap(cmp)
            val a = mutableListOf<Pair<Int, Int>>()
            val b = mutableListOf<Pair<Int, Int>>()
            for ((k, item) in items.withIndex()) {
                pq += item
                heap += item
                if (k % 3 == 2) {
                    a += pq.poll()
                    b += heap.poll()
                }
            }
            while (pq.isNotEmpty()) a += pq.poll()
            while (heap.isNotEmpty()) b += heap.poll()
            assertEquals(a, b)
        }
    }

    @Test
    fun `weather request rounds like %_1f`() {
        for (v in listOf(48.8566, 2.3522, -4.4861, -0.04, 43.25, 0.0, -0.0, -1.05, 45.95)) {
            val expected = "https://api.open-meteo.com/v1/forecast?latitude=${String.format(java.util.Locale.ROOT, "%.1f", v)}" +
                "&longitude=${String.format(java.util.Locale.ROOT, "%.1f", 1.0)}&current=precipitation,weather_code"
            assertEquals(expected, Weather.url(LatLon(v, 1.0)), "v=$v")
        }
    }
}
