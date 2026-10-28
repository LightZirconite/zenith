package zenith.core

import kotlin.test.Test
import kotlin.test.assertEquals

class VideoTitleTest {
    @Test
    fun dropsTagsInBrackets() {
        assertEquals("Ninho - Jefe", VideoTitle.clean("Ninho - Jefe (Clip officiel)"))
        assertEquals("Song", VideoTitle.clean("Song [Official Music Video]"))
        assertEquals("Song", VideoTitle.clean("Song (Lyrics) [4K]"))
        assertEquals("Song (Live at Bercy)", VideoTitle.clean("Song (Live at Bercy)"))
        assertEquals("Song (feat. X)", VideoTitle.clean("Song (feat. X) (Official Audio)"))
    }

    @Test
    fun channelBecomesArtist() {
        assertEquals("Ninho", VideoTitle.channel("NinhoVEVO"))
        assertEquals("Ninho", VideoTitle.channel("Ninho - Topic"))
        assertEquals("Aya Nakamura", VideoTitle.channel("Aya Nakamura Officiel"))
        assertEquals("Squeezie", VideoTitle.channel("Squeezie"))
    }

    @Test
    fun splitsArtistAndSong() {
        assertEquals("Jefe" to "Ninho", VideoTitle.display("Ninho - Jefe (Clip officiel)", "NinhoVEVO"))
        assertEquals("Jefe" to "Ninho", VideoTitle.display("Ninho - Jefe", "Ninho"))
        // A channel that is not the artist (a label, a compilation): its own name stays out, the title's artist shows.
        assertEquals("Djadja" to "Aya Nakamura", VideoTitle.display("Aya Nakamura - Djadja (Clip officiel)", "Rec. 118 Official"))
        // A vlog keeps its title and its channel.
        assertEquals("Ma journée - partie 2" to "Squeezie", VideoTitle.display("Ma journée - partie 2", "Squeezie"))
    }
}
