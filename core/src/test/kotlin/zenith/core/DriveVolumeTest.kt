package zenith.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DriveVolumeTest {
    @Test
    fun `a low volume is raised to 60 percent`() {
        assertEquals(9, DriveVolume.raiseTo(4, 15))
        assertEquals(18, DriveVolume.raiseTo(0, 30))
    }

    @Test
    fun `a volume already high enough is left alone`() {
        assertNull(DriveVolume.raiseTo(9, 15))
        assertNull(DriveVolume.raiseTo(15, 15))
        assertNull(DriveVolume.raiseTo(3, 0))
    }

    @Test
    fun `the volume goes back unless the driver changed it`() {
        assertEquals(4, DriveVolume.restoreTo(before = 4, raisedTo = 9, now = 9))
        assertNull(DriveVolume.restoreTo(before = 4, raisedTo = 9, now = 12))
        assertNull(DriveVolume.restoreTo(before = 4, raisedTo = 9, now = 6))
    }
}
