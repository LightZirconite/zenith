package zenith.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.math.floor

/** Everything radars.json holds. */
data class RadarData(val radars: List<Radar>, val zones: List<ControlZone>)

/** radars.json built by pipeline/build_radars.py. */
object RadarPack {
    fun parse(text: String): List<Radar> = parseAll(text).radars

    /** Radars and control zones ("zones": flat [lat, lon, ...] polylines; absent from older packs). */
    fun parseAll(text: String): RadarData {
        val root = Json.parseToJsonElement(text).jsonObject
        val zones = (root["zones"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val flat = (o["pts"] as? JsonArray)?.map { (it as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null }
            if (flat == null || flat.size < 4 || flat.size % 2 != 0) return@mapNotNull null
            ControlZone(o.str("id") ?: return@mapNotNull null, flat.chunked(2) { LatLon(it[0], it[1]) })
        }
        return RadarData(radars(root), zones)
    }

    private fun radars(root: JsonObject): List<Radar> =
        root.getValue("radars").jsonArray.mapNotNull { e ->
            val o = e.jsonObject
            val type = runCatching { RadarType.valueOf(o.str("type") ?: "") }.getOrNull() ?: return@mapNotNull null
            Radar(
                id = o.str("id") ?: return@mapNotNull null,
                position = LatLon(o.num("lat") ?: return@mapNotNull null, o.num("lon") ?: return@mapNotNull null),
                type = type,
                limitKmh = o.num("limit")?.toInt(),
                directionDeg = o.num("dir"),
                sectionLengthM = o.num("len"),
                // Report time (epoch seconds), only meaningful for mobile radars.
                reportedAtMs = o.num("at")?.takeIf { type.mobile }?.let { (it * 1000).toLong() },
            )
        }

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.num(key: String) = (this[key] as? JsonPrimitive)?.doubleOrNull
}

/** 0.5° road tiles built by pipeline/build_roads.py: roads_<i>_<j>.json.gz, i = floor(lat * 2). */
object RoadTiles {
    const val PER_DEG = 2
    private val CLASSES = listOf(
        "motorway", "trunk", "primary", "secondary", "tertiary", "unclassified", "residential",
        "living_street", "motorway_link", "trunk_link", "primary_link", "secondary_link", "tertiary_link",
    )
    private val SOURCES = listOf(LimitSource.EXPLICIT, LimitSource.IMPLICIT, LimitSource.INFERRED, LimitSource.AREA)

    data class Key(val i: Int, val j: Int) {
        val fileName: String get() = "roads_${i}_$j.json.gz"
    }

    fun keyOf(p: LatLon) = Key(floor(p.lat * PER_DEG).toInt(), floor(p.lon * PER_DEG).toInt())

    /** Tiles covering a [marginM] box around [p], so the next tile is ready before the edge. */
    fun keysAround(p: LatLon, marginM: Double): Set<Key> {
        val box = BBox.around(p, marginM)
        return buildSet {
            for (i in floor(box.minLat * PER_DEG).toInt()..floor(box.maxLat * PER_DEG).toInt()) {
                for (j in floor(box.minLon * PER_DEG).toInt()..floor(box.maxLon * PER_DEG).toInt()) add(Key(i, j))
            }
        }
    }

    /** Segment rows: [id, from, to, oneway, fwd, bwd, src, class, name, [lat, lon, ...]]. */
    fun parse(text: String): List<RoadSegment> {
        val rows = Json.parseToJsonElement(text).jsonObject.getValue("segments").jsonArray
        return rows.mapNotNull { row ->
            val a = row.jsonArray
            val flat = a[9].jsonArray.map { it.jsonPrimitive.doubleOrNull ?: return@mapNotNull null }
            if (flat.size < 4) return@mapNotNull null
            RoadSegment(
                id = a[0].jsonPrimitive.long,
                fromNode = a[1].jsonPrimitive.long,
                toNode = a[2].jsonPrimitive.long,
                points = flat.chunked(2) { LatLon(it[0], it[1]) },
                oneway = a[3].jsonPrimitive.intOrNull == 1,
                maxspeedForward = a[4].intOrNull(),
                maxspeedBackward = a[5].intOrNull(),
                limitSource = a[6].intOrNull()?.let { SOURCES.getOrNull(it) } ?: LimitSource.INFERRED,
                roadClass = a[7].intOrNull()?.let { CLASSES.getOrNull(it) },
                name = (a[8] as? JsonPrimitive)?.takeIf { it.isString }?.content,
            )
        }
    }

    private fun kotlinx.serialization.json.JsonElement.intOrNull(): Int? =
        if (this is JsonNull) null else (this as? JsonPrimitive)?.intOrNull
}
