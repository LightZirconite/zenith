package zenith.app

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import zenith.core.LatLon
import zenith.core.RadarData
import zenith.core.RadarPack
import zenith.core.RoadNetwork
import zenith.core.RoadSegment
import zenith.core.RoadTiles
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream

/** What the data layer has loaded, for the UI. */
data class DataStatus(
    val radarCount: Int = 0,
    /** Control itineraries (official map) in memory. */
    val zoneCount: Int = 0,
    val radarsUpdatedMs: Long? = null,
    val tilesLoaded: Int = 0,
    val loading: Boolean = false,
    /** Radar list unavailable (no cache and no network). */
    val radarError: String? = null,
    /** Road tiles around the car unavailable; cleared once they load. */
    val tileError: String? = null,
) {
    val error: String? get() = tileError ?: radarError
}

/**
 * Offline data built weekly by the "data" workflow and published on the GitHub "data" release:
 * radars.json (whole France) and 0.5° road tiles with speed limits, downloaded around the car
 * and cached on the phone, so alerts keep working without network.
 */
class DriveData(context: Context, private val scope: CoroutineScope) {
    private val dir = File(context.filesDir, "data").apply { mkdirs() }
    private val tileDir = File(dir, "tiles").apply { mkdirs() }
    private val radarFile = File(dir, "radars.json")
    private val manifestFile = File(dir, "manifest.json")
    /** Content version of the cached tiles (manifest "schema"). */
    private val schemaFile = File(tileDir, "schema")
    private val tiles = ConcurrentHashMap<RoadTiles.Key, List<RoadSegment>>()
    private val pending = ConcurrentHashMap.newKeySet<RoadTiles.Key>()
    private val failedAtMs = ConcurrentHashMap<RoadTiles.Key, Long>()
    private val mutex = Mutex()
    private var availableTiles: Set<String>? = null

    @Volatile
    var network: RoadNetwork? = null
        private set

    private val _status = MutableStateFlow(DataStatus())
    val status: StateFlow<DataStatus> = _status

    /** Loads cached radars and zones now, refreshes them from GitHub when older than a day. */
    fun loadRadars(onLoaded: (RadarData) -> Unit) = scope.launch(Dispatchers.IO) {
        if (radarFile.exists()) publishRadars(onLoaded)
        if (!radarFile.exists() || age(radarFile) > DAY_MS) {
            runCatching { download("radars.json", radarFile) }
                .onSuccess { publishRadars(onLoaded) }
                // With a cached list the drive goes on with it: nothing to warn about.
                .onFailure { if (radarFile.exists()) Log.w("Zenith", "data radars refresh", it) else fail("radars", it) }
        }
    }

    /** Makes sure the road tiles around [p] are loaded; cheap to call on every fix. */
    fun ensureTiles(p: LatLon) {
        val wanted = RoadTiles.keysAround(p, 5_000.0)
        val now = System.currentTimeMillis()
        val missing = wanted.filter { !tiles.containsKey(it) && it !in pending && now - (failedAtMs[it] ?: 0L) > RETRY_MS }
        if (missing.isEmpty()) return
        pending += missing
        scope.launch(Dispatchers.IO) {
            mutex.withLock {
                _status.update { it.copy(loading = true) }
                val available = availableTiles ?: loadManifest()
                var failed: String? = null
                for (key in missing) {
                    val file = File(tileDir, key.fileName)
                    val segments = runCatching {
                        if (available != null && key.fileName !in available) return@runCatching emptyList()
                        if (!file.exists() || age(file) > 30 * DAY_MS) download(key.fileName, file)
                        RoadTiles.parse(GZIPInputStream(file.inputStream()).bufferedReader().use { it.readText() })
                    }.onFailure {
                        Log.w("Zenith", "data tile ${key.fileName}", it)
                        failed = "Données tuile ${key.fileName} indisponibles"
                    }.getOrNull()
                    if (segments != null) tiles[key] = segments else failedAtMs[key] = System.currentTimeMillis()
                    pending -= key
                }
                // Keep a ring of neighbours in memory, drop tiles far behind.
                val keep = wanted.flatMap { k -> (-1..1).flatMap { di -> (-1..1).map { dj -> RoadTiles.Key(k.i + di, k.j + dj) } } }.toSet()
                tiles.keys.retainAll(keep)
                network = RoadNetwork(tiles.values.flatten().distinctBy { it.id })
                // A tile that failed once and came back (network back) no longer shows an error.
                _status.update { it.copy(tilesLoaded = tiles.size, loading = false, tileError = failed) }
            }
        }
    }

    private fun loadManifest(): Set<String>? {
        if (!manifestFile.exists() || age(manifestFile) > DAY_MS) {
            // Without it every tile is simply tried: a failing tile says so itself.
            runCatching { download("manifest.json", manifestFile) }.onFailure { Log.w("Zenith", "data manifest", it) }
        }
        return runCatching {
            val manifest = Json.parseToJsonElement(manifestFile.readText()).jsonObject
            dropOutdatedTiles(manifest["schema"]?.jsonPrimitive?.intOrNull ?: 1)
            manifest.getValue("tiles").jsonObject.keys
        }.getOrNull().also { availableTiles = it }
    }

    /**
     * Tiles of an older content version (before the limits from built-up areas, say) are deleted
     * once, so they come back new instead of waiting for their 30 days.
     */
    private fun dropOutdatedTiles(schema: Int) {
        val cached = runCatching { schemaFile.readText().trim().toInt() }.getOrDefault(1)
        if (schema <= cached) return
        tileDir.listFiles { f -> f.name.endsWith(".json.gz") }?.forEach { it.delete() }
        schemaFile.writeText(schema.toString())
    }

    private fun publishRadars(onLoaded: (RadarData) -> Unit) {
        val pack = runCatching { RadarPack.parseAll(radarFile.readText()) }.onFailure { fail("radars", it) }.getOrNull() ?: return
        _status.update {
            it.copy(radarCount = pack.radars.size, zoneCount = pack.zones.size, radarsUpdatedMs = radarFile.lastModified(), radarError = null)
        }
        onLoaded(pack)
    }

    private fun fail(what: String, e: Throwable) {
        Log.w("Zenith", "data $what", e)
        _status.update { it.copy(radarError = "Données $what indisponibles") }
    }

    private fun age(f: File) = System.currentTimeMillis() - f.lastModified()

    private fun download(name: String, target: File) {
        val tmp = File(target.parentFile, target.name + ".part")
        val conn = URL("$BASE/$name").openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        conn.instanceFollowRedirects = true
        try {
            check(conn.responseCode == 200) { "HTTP ${conn.responseCode} pour $name" }
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            check(tmp.renameTo(target)) { "renommage de $name" }
        } finally {
            conn.disconnect()
            tmp.delete()
        }
    }

    private companion object {
        const val BASE = "https://github.com/LightZirconite/zenith/releases/download/data"
        const val DAY_MS = 24 * 3_600_000L
        const val RETRY_MS = 60_000L
    }
}
