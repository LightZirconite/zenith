package zenith.core

import kotlin.test.Test
import kotlin.test.assertTrue

class ReplayTest {
    @Test
    fun `timeline lists what was said, with time and speed`() {
        val demo = DemoDrive.load()
        val lines = Replay.timeline(demo.fixes, demo.network, demo.radars)
        assertTrue(lines.any { "Radar fixe dans" in it && "D 107" in it }, lines.joinToString("\n"))
        assertTrue(lines.any { "flash probable" in it })
        assertTrue(lines.first().matches(Regex("""\d\d:\d\d .*""")))
    }
}
