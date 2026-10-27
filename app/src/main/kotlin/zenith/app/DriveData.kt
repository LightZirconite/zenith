package zenith.app

import android.content.Context
import zenith.core.Radar
import zenith.core.RoadNetwork

/**
 * Road network and radars used by a drive. Empty until the offline packs from the data
 * pipeline (M1) are downloaded: the app then shows speed only, without limits or radars.
 */
object DriveData {
    fun network(@Suppress("UNUSED_PARAMETER") context: Context): RoadNetwork? = null

    fun radars(@Suppress("UNUSED_PARAMETER") context: Context): List<Radar> = emptyList()
}
