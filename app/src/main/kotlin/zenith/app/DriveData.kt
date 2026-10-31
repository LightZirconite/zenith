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
import kotlinx.serialization.json.longOrNull
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
    /** The road tiles around the car's latest position are in (or known unavailable): the take-off may land. */
    val roadsReady: Boolean = false,
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
    /** Radars abroad (Lufop harvest outside France), fetched only once the car drives abroad. */
    private val abroadFile = File(dir, "radars_abroad.json")
    private val manifestFile = File(dir, "manifest.json")
    /** Content version of the cached tiles (manifest "schema"). */
    private val schemaFile = File(tileDir, "schema")
    private val tiles = ConcurrentHashMap<RoadTiles.Key, List<RoadSegment>>()
    private val pending = ConcurrentHashMap.newKeySet<RoadTiles.Key>()
    private val failedAtMs = ConcurrentHashMap<RoadTiles.Key, Long>()
    /** The tiles around the latest position asked for. */
    @Volatile
    private var lastWanted: Collection<RoadTiles.Key> = emptyList()
    private val mutex = Mutex()
    /** Published tiles and their sizes (manifest); null when the manifest could not be read. */
    private var availableTiles: Map<String, Long?>? = null

    @Volatile
    var network: RoadNetwork? = null
        private set

    /** Where the radars go: France's list, plus the radars abroad once the car is there. */
    @Volatile
    private var onRadars: ((RadarData) -> Unit)? = null
    @Volatile
    private var france: RadarData? = null
    @Volatile
    private var abroad: RadarData? = null
    @Volatile
    private var abroadAsked = false

    private val _status = MutableStateFlow(DataStatus())
    val status: StateFlow<DataStatus> = _status

    /** Loads cached radars and zones now, refreshes them from GitHub when older than [RADARS_MAX_AGE_MS]. */
    fun loadRadars(onLoaded: (RadarData) -> Unit) = scope.launch(Dispatchers.IO) {
        onRadars = onLoaded
        if (radarFile.exists()) publishRadars()
        if (!radarFile.exists() || age(radarFile) > RADARS_MAX_AGE_MS) {
            runCatching { download("radars.json", radarFile) }
                .onSuccess { publishRadars() }
                // With a cached list the drive goes on with it: nothing to warn about.
                .onFailure { if (radarFile.exists()) Log.w("Zenith", "data radars refresh", it) else fail("radars", it) }
        }
    }

    /** Makes sure the road tiles around [p] are loaded; cheap to call on every fix. */
    fun ensureTiles(p: LatLon) {
        val wanted = RoadTiles.keysAround(p, 5_000.0)
        lastWanted = wanted
        checkAbroad(RoadTiles.keyOf(p))
        val now = System.currentTimeMillis()
        val missing = wanted.filter { !tiles.containsKey(it) && it !in pending && now - (failedAtMs[it] ?: 0L) > RETRY_MS }
        if (missing.isEmpty()) {
            val ready = settled(wanted)
            if (_status.value.roadsReady != ready) _status.update { it.copy(roadsReady = ready) }
            return
        }
        _status.update { it.copy(roadsReady = false) }
        pending += missing
        scope.launch(Dispatchers.IO) {
            mutex.withLock {
                _status.update { it.copy(loading = true) }
                val available = availableTiles ?: loadManifest()
                checkAbroad(RoadTiles.keyOf(p))
                var failed: String? = null
                for (key in missing) {
                    val file = File(tileDir, key.fileName)
                    val segments = runCatching {
                        if (available != null && key.fileName !in available) return@runCatching emptyList()
                        // A size different from the manifest's: the tile was rebuilt (weekly), fetch the new one.
                        val outdated = available?.get(key.fileName)?.let { it != file.length() } ?: (age(file) > 30 * DAY_MS)
                        if (!file.exists()) {
                            download(key.fileName, file)
                        } else if (outdated) {
                            // Offline: the cached tile still does.
                            runCatching { download(key.fileName, file) }.onFailure { Log.w("Zenith", "data tile refresh", it) }
                        }
                        runCatching { RoadTiles.parse(GZIPInputStream(file.inputStream()).bufferedReader().use { it.readText() }) }
                            // A damaged cached tile would fail forever: fetched again on the next try.
                            .onFailure { file.delete() }
                            .getOrThrow()
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
                _status.update { it.copy(tilesLoaded = tiles.size, loading = false, tileError = failed, roadsReady = settled(lastWanted)) }
            }
        }
    }

    /** Each tile is loaded, or failed and waits for its retry (offline): nothing more to wait for. */
    private fun settled(keys: Collection<RoadTiles.Key>) = keys.all { (tiles.containsKey(it) || failedAtMs.containsKey(it)) && it !in pending }

    private fun loadManifest(): Map<String, Long?>? {
        if (!manifestFile.exists() || age(manifestFile) > DAY_MS) {
            // Without it every tile is simply tried: a failing tile says so itself.
            runCatching { download("manifest.json", manifestFile) }.onFailure { Log.w("Zenith", "data manifest", it) }
        }
        return runCatching {
            val manifest = Json.parseToJsonElement(manifestFile.readText()).jsonObject
            dropOutdatedTiles(manifest["schema"]?.jsonPrimitive?.intOrNull ?: 1)
            manifest.getValue("tiles").jsonObject.mapValues { (_, size) -> size.jsonPrimitive.longOrNull }
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

    private fun publishRadars() {
        val pack = runCatching { RadarPack.parseAll(radarFile.readText()) }.onFailure { fail("radars", it) }.getOrNull() ?: return
        france = pack
        _status.update { it.copy(radarsUpdatedMs = radarFile.lastModified(), radarError = null) }
        deliver()
    }

    /** France's radars, with those abroad once loaded, to the drive. */
    private fun deliver() {
        val home = france ?: return
        val away = abroad
        val pack = if (away == null) home else RadarData(home.radars + away.radars, home.zones)
        _status.update { it.copy(radarCount = pack.radars.size, zoneCount = pack.zones.size) }
        onRadars?.invoke(pack)
    }

    /**
     * The car outside France (its tile is not among France's road tiles, manifest read): the radars
     * abroad are fetched once per drive, refreshed after a week, the cached copy used offline.
     */
    private fun checkAbroad(here: RoadTiles.Key) {
        val available = availableTiles ?: return
        if (abroadAsked || here.fileName in available) return
        abroadAsked = true
        scope.launch(Dispatchers.IO) {
            if (!abroadFile.exists() || age(abroadFile) > ABROAD_MAX_AGE_MS) {
                runCatching { download("radars_abroad.json", abroadFile) }.onFailure { Log.w("Zenith", "data radars abroad", it) }
            }
            if (!abroadFile.exists()) return@launch
            abroad = runCatching { RadarPack.parseAll(abroadFile.readText()) }.onFailure { Log.w("Zenith", "data radars abroad", it) }.getOrNull()
            deliver()
        }
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
        /** radars.json is rebuilt six times a day (new official cameras, Lufop). */
        const val RADARS_MAX_AGE_MS = 4 * 3_600_000L
        /** Radars abroad grow slowly (what the quota leaves after France). */
        const val ABROAD_MAX_AGE_MS = 7 * DAY_MS
    }
}
