package zenith.ios

import zenith.core.BBox
import zenith.core.CanvasAnswer
import zenith.core.CanvasSearch
import zenith.core.CanvasService
import zenith.core.Cockpit
import zenith.core.DemoDrive
import zenith.core.DemoScenario
import zenith.core.DriveVolume
import zenith.core.DriverProfile
import zenith.core.DrivingSession
import zenith.core.GpsFix
import zenith.core.Gpx
import zenith.core.HttpAnswer
import zenith.core.LatLon
import zenith.core.Loudness
import zenith.core.ParkDetector
import zenith.core.PlayerChoice
import zenith.core.PlayerSession
import zenith.core.RadarData
import zenith.core.RadarIndex
import zenith.core.RadarPack
import zenith.core.RainDetector
import zenith.core.RoadNetwork
import zenith.core.RoadSegment
import zenith.core.RoadTiles
import zenith.core.SpeedStabilizer
import zenith.core.SpotifyDuck
import zenith.core.SpotifyTrack
import zenith.core.Weather

/**
 * Entry points for the Swift app. A Kotlin exception reaching Swift aborts the app, so every call
 * that may throw (a downloaded file, a drive step) is caught here: a drive never stops on an error,
 * as on Android. Missing numbers are -1 both ways (see Views.kt).
 */
object Bridge {
    // ---- Drive ----

    /** A CoreLocation fix: a negative accuracy, speed or course means unknown. */
    fun fix(timeMs: Long, lat: Double, lon: Double, accuracyM: Double, speedMps: Double, courseDeg: Double): GpsFix =
        GpsFix(timeMs, LatLon(lat, lon), accuracyM.takeIf { it >= 0 }, speedMps.takeIf { it >= 0 }, courseDeg.takeIf { it >= 0 })

    /** The same fix at another time (the demo replays its fixes on the wall clock). */
    fun retime(fix: GpsFix, timeMs: Long): GpsFix = GpsFix(timeMs, fix.position, fix.accuracyM, fix.speedMps, fix.bearingDeg)

    fun fixTimeMs(fix: GpsFix): Long = fix.timeMs

    /** A live drive on the road network the app keeps loading around the car. */
    fun liveSession(network: () -> RoadNetwork?): DrivingSession = DrivingSession(network, emptyList())

    fun process(session: DrivingSession, fix: GpsFix): CoreSnapshot? =
        runCatching { session.process(fix)?.let(CoreSnapshot::of) }.getOrNull()

    /** GPS lost (tunnel): the car moves on along the road; nil when there is nothing to follow. */
    fun coast(session: DrivingSession, nowMs: Long): CoreSnapshot? =
        runCatching { session.coast(nowMs)?.let(CoreSnapshot::of) }.getOrNull()

    fun setProfile(session: DrivingSession, probationary: Boolean, raining: Boolean) {
        session.profile = DriverProfile(probationary, raining)
    }

    fun setRadars(session: DrivingSession, data: RadarData) {
        runCatching {
            session.setRadars(data.radars)
            session.setZones(data.zones)
        }
    }

    fun speedStabilizer() = SpeedStabilizer()

    /** Speed for the big number from one fix, before the road horizon; -1 when not shown. */
    fun liveSpeed(stabilizer: SpeedStabilizer, fix: GpsFix, filteredMps: Double): Int =
        runCatching { stabilizer.update(fix, filteredMps) }.getOrNull() ?: -1

    // ---- Data ----

    fun radarData(text: String): RadarData? = runCatching { RadarPack.parseAll(text) }.getOrNull()

    /** France's radars and zones with the radars abroad (radars_abroad.json, no zones). */
    fun withAbroad(home: RadarData, abroad: RadarData): RadarData = RadarData(home.radars + abroad.radars, home.zones)

    fun radarCount(data: RadarData): Int = data.radars.size

    fun zoneCount(data: RadarData): Int = data.zones.size

    fun radarIndex(data: RadarData): RadarIndex = RadarIndex(data.radars)

    /** Radars within [radiusM] of the car, for the map. */
    fun nearbyRadars(index: RadarIndex, lat: Double, lon: Double, radiusM: Double): List<CoreRadar> =
        runCatching { index.near(LatLon(lat, lon), radiusM).map(CoreRadar::of) }.getOrDefault(emptyList())

    /** Control itineraries whose box meets the [marginM] box around the car, for the map. */
    fun nearbyZones(data: RadarData, lat: Double, lon: Double, marginM: Double): List<CoreZone> = runCatching {
        val around = BBox.around(LatLon(lat, lon), marginM)
        data.zones.filter { z ->
            val b = BBox.of(z.points)
            b.minLat <= around.maxLat && b.maxLat >= around.minLat && b.minLon <= around.maxLon && b.maxLon >= around.minLon
        }.map { CoreZone.of(it) }
    }.getOrDefault(emptyList())

    /** Roads within [radiusM] of the car (grid index of the network), for the small window's map. */
    fun roadsNear(network: RoadNetwork?, lat: Double, lon: Double, radiusM: Double): List<CoreRoad> = runCatching {
        network?.candidates(LatLon(lat, lon), radiusM).orEmpty().distinctBy { it.segment.id }.map { c ->
            CoreRoad(c.segment.points.flatMap { listOf(it.lat, it.lon) }, c.segment.roadClass)
        }
    }.getOrDefault(emptyList())

    fun tile(text: String): List<RoadSegment>? = runCatching { RoadTiles.parse(text) }.getOrNull()

    fun network(tiles: List<List<RoadSegment>>): RoadNetwork = RoadNetwork(tiles.flatten().distinctBy { it.id })

    /** Road tiles around a point: [i0, j0, i1, j1, …]. */
    fun tilesAround(lat: Double, lon: Double, marginM: Double): List<Int> =
        RoadTiles.keysAround(LatLon(lat, lon), marginM).flatMap { listOf(it.i, it.j) }

    fun tileFileName(i: Int, j: Int): String = RoadTiles.Key(i, j).fileName

    // ---- Demo ----

    fun demo(tileText: String): DemoScenario? = runCatching { DemoDrive.scenario(tileText) }.getOrNull()

    fun demoSession(s: DemoScenario): DrivingSession = DrivingSession(s.network, s.radars, zones = s.zones)

    fun demoFixes(s: DemoScenario): List<GpsFix> = s.fixes

    fun demoData(s: DemoScenario): RadarData = RadarData(s.radars, s.zones)

    fun demoNetwork(s: DemoScenario): RoadNetwork = s.network

    // ---- Parking, weather, trip ----

    /** [falseStartMs] <= 0: none (a drive started by hand). */
    fun parkDetector(startMs: Long, falseStartMs: Long): ParkDetector =
        ParkDetector(startMs, falseStartMs = falseStartMs.takeIf { it > 0 })

    /** True: the drive is over. [speedKmh] -1 unknown. */
    fun parkOnFix(p: ParkDetector, timeMs: Long, lat: Double, lon: Double, speedKmh: Int, roadClass: String?, charging: Boolean): Boolean =
        runCatching { p.onFix(timeMs, LatLon(lat, lon), speedKmh.takeIf { it >= 0 }, roadClass, charging) }.getOrDefault(false)

    fun parkOnSilence(p: ParkDetector, nowMs: Long): Boolean = runCatching { p.onSilence(nowMs) }.getOrDefault(false)

    fun rainDetector() = RainDetector()

    fun weatherUrl(lat: Double, lon: Double): String = Weather.url(LatLon(lat, lon))

    /** Rain mode after this Open-Meteo answer: 1 raining, 0 dry, -1 unreadable (unchanged). */
    fun weatherReport(rain: RainDetector, text: String): Int {
        val now = Weather.parse(text) ?: return -1
        return if (rain.report(now)) 1 else 0
    }

    fun gpxHeader(name: String): String = Gpx.header(name)

    fun gpxPoint(fix: GpsFix): String = Gpx.point(fix)

    fun gpxFooter(): String = Gpx.FOOTER

    // ---- Screen rules (Cockpit), volume, loudness ----

    /** [choice]: -1 none, 0 small, 1 big. */
    fun music(speedKmh: Int, choice: Int, alerting: Boolean): Float =
        Cockpit.music(speedKmh.takeIf { it >= 0 }, choice.toChoice(), alerting)

    /** The tap's choice still holding (-1 none, 0, 1). */
    fun keepChoice(choice: Int, speedKmh: Int, ageMs: Long): Int =
        when (Cockpit.keepChoice(choice.toChoice(), speedKmh.takeIf { it >= 0 }, ageMs)) {
            null -> -1
            true -> 1
            false -> 0
        }

    fun toggleChoice(shown: Float): Boolean = Cockpit.toggle(shown)

    fun cruising(speedKmh: Int, was: Boolean): Boolean = Cockpit.cruising(speedKmh.takeIf { it >= 0 }, was)

    fun choiceMs(): Long = Cockpit.CHOICE_MS

    /** Volume level to set for a drive (0 to [max] steps), -1 when already high enough. */
    fun raiseVolumeTo(current: Int, max: Int): Int = DriveVolume.raiseTo(current, max) ?: -1

    /** Volume level to restore at the end, -1 when the driver changed it meanwhile. */
    fun restoreVolumeTo(before: Int, raisedTo: Int, now: Int): Int = DriveVolume.restoreTo(before, raisedTo, now) ?: -1

    fun uiGain(volume: Int, max: Int): Float = Loudness.uiGain(volume, max)

    fun tapGain(volume: Int, max: Int): Float = Loudness.tapGain(volume, max)

    // ---- Music ----

    /** The Spotify track id in a "spotify:track:…" URI or open.spotify.com link, else nil. */
    fun spotifyTrackId(value: String?): String? = SpotifyTrack.idOf(value)

    /**
     * The player the music card follows ([PlayerChoice]); parallel lists, [packages] use Android
     * package names for the platforms ("com.spotify.music", "com.apple.android.music"). Returns its id.
     */
    fun pickPlayer(ids: List<String>, packages: List<String>, playing: List<Boolean>, hasTitle: List<Boolean>, current: String?): String? =
        runCatching {
            PlayerChoice.pick(ids.indices.map { PlayerSession(ids[it], packages[it], playing[it], hasTitle[it]) }, current)?.id
        }.getOrNull()

    /** Spotify playing on another device (car, speaker) to lower while Zenith speaks; [premium] -1 unknown. */
    fun spotifyShouldDuck(
        deviceType: String?,
        deviceName: String?,
        supportsVolume: Boolean,
        playing: Boolean,
        premium: Int,
        phoneNames: List<String>,
    ): Boolean = SpotifyDuck.shouldDuck(deviceType, deviceName, supportsVolume, playing, premium.toChoice(), phoneNames)

    fun spotifyDuckedVolume(current: Int): Int = SpotifyDuck.duckedVolume(current)

    /** Volume to put back, -1 for none; [now] -1 unknown. */
    fun spotifyRestoreVolume(before: Int, duckedTo: Int, now: Int): Int =
        SpotifyDuck.restoreVolume(before, duckedTo, now.takeIf { it >= 0 }) ?: -1

    /** Canvas services to ask in order for a Spotify track. */
    fun canvasUrls(trackId: String): List<String> = CanvasService.entries.map { it.url(trackId) }

    /** Answer of the [index]th service: kind 0 failed (ask the next), 1 no canvas, 2 video at url. */
    fun canvasAnswer(index: Int, trackId: String, status: Int, body: String): CanvasResult =
        result(runCatching { CanvasService.entries[index].parse(HttpAnswer(status, body), trackId) }.getOrNull())

    fun canvasSearchUrl(title: String, artist: String?, album: String?, durationMs: Long): String =
        CanvasSearch.url(title, artist, album, durationMs.takeIf { it > 0 })

    /** Search answer: kind 0 failed, 1 none, 3 found (trackId and url). */
    fun canvasSearchAnswer(status: Int, body: String): CanvasResult =
        result(runCatching { CanvasSearch.parse(HttpAnswer(status, body)) }.getOrNull())

    fun isCanvasVideoUrl(url: String): Boolean = CanvasService.isVideoUrl(url)

    private fun result(a: CanvasAnswer?): CanvasResult = when (a) {
        null -> CanvasResult(0, null, null)
        is CanvasAnswer.None -> CanvasResult(1, null, null)
        is CanvasAnswer.Video -> CanvasResult(2, a.url, null)
        is CanvasAnswer.Found -> CanvasResult(3, a.match.url, a.match.trackId)
    }

    private fun Int.toChoice(): Boolean? = when (this) {
        1 -> true
        0 -> false
        else -> null
    }
}

/** A canvas lookup answer (see [Bridge.canvasAnswer]). */
class CanvasResult(val kind: Int, val url: String?, val trackId: String?)
