package zenith.core

import kotlin.math.floor

/** Bounding box in degrees. */
data class BBox(val minLat: Double, val minLon: Double, val maxLat: Double, val maxLon: Double) {
    companion object {
        fun of(points: List<LatLon>) = BBox(
            points.minOf { it.lat }, points.minOf { it.lon },
            points.maxOf { it.lat }, points.maxOf { it.lon },
        )

        /** Box of roughly [radiusM] around [center]. */
        fun around(center: LatLon, radiusM: Double): BBox {
            val dLat = radiusM / 111_195.0
            val dLon = dLat / kotlin.math.cos(rad(center.lat)).coerceAtLeast(0.01)
            return BBox(center.lat - dLat, center.lon - dLon, center.lat + dLat, center.lon + dLon)
        }
    }
}

/** Uniform lat/lon grid; each item is stored in every cell its box touches. */
class GridIndex<T>(private val cellDeg: Double = 0.005) {
    private val cells = HashMap<Long, MutableList<T>>()

    private fun key(i: Int, j: Int) = (i.toLong() shl 32) or (j.toLong() and 0xffffffffL)
    private fun cell(v: Double) = floor(v / cellDeg).toInt()

    fun insert(box: BBox, item: T) {
        for (i in cell(box.minLat)..cell(box.maxLat)) {
            for (j in cell(box.minLon)..cell(box.maxLon)) {
                cells.getOrPut(key(i, j)) { mutableListOf() }.add(item)
            }
        }
    }

    /** Items whose cells intersect [box]; may include items slightly outside it, never duplicates. */
    fun query(box: BBox): Set<T> {
        val out = LinkedHashSet<T>()
        for (i in cell(box.minLat)..cell(box.maxLat)) {
            for (j in cell(box.minLon)..cell(box.maxLon)) {
                cells[key(i, j)]?.let(out::addAll)
            }
        }
        return out
    }
}
