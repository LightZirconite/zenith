package zenith.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import zenith.core.ControlZone
import zenith.core.DriveSnapshot
import zenith.core.Radar

/** What started the running drive; a drive started by the car ends with the car. */
enum class DriveOrigin {
    /** The Start button, or the "tap to start" notification. */
    HAND,

    /** The hidden simulated drive. */
    DEMO,

    /** The paired car's Bluetooth connected. */
    CAR,

    /** The phone started riding in a vehicle (activity recognition). */
    MOTION,
}

/** Live state shared between [DriveService] and the UI. */
object DriveState {
    private val _running = MutableStateFlow(false)
    private val _snapshot = MutableStateFlow<DriveSnapshot?>(null)
    private val _gps = MutableStateFlow(GpsInfo())
    private val _data = MutableStateFlow(DataStatus())
    private val _nearbyRadars = MutableStateFlow<List<Radar>>(emptyList())
    private val _nearbyZones = MutableStateFlow<List<ControlZone>>(emptyList())
    private val _demo = MutableStateFlow(false)
    private val _origin = MutableStateFlow(DriveOrigin.HAND)

    val running: StateFlow<Boolean> = _running
    val snapshot: StateFlow<DriveSnapshot?> = _snapshot
    val gps: StateFlow<GpsInfo> = _gps
    val data: StateFlow<DataStatus> = _data

    /** Radars around the car, for the map. */
    val nearbyRadars: StateFlow<List<Radar>> = _nearbyRadars

    /** Control itineraries around the car, for the map. */
    val nearbyZones: StateFlow<List<ControlZone>> = _nearbyZones

    /** The running drive is the hidden simulated one. */
    val demo: StateFlow<Boolean> = _demo

    /** What started the running drive. */
    val origin: StateFlow<DriveOrigin> = _origin

    internal fun started(origin: DriveOrigin) {
        _snapshot.value = null
        _gps.value = GpsInfo()
        _demo.value = origin == DriveOrigin.DEMO
        _origin.value = origin
        _running.value = true
    }

    internal fun update(snapshot: DriveSnapshot) {
        _snapshot.value = snapshot
        _gps.update { it.copy(accuracyM = snapshot.gpsAccuracyM, lastFixMs = System.currentTimeMillis()) }
    }

    internal fun satellites(used: Int, visible: Int) = _gps.update { it.copy(used = used, visible = visible) }

    internal fun nearby(radars: List<Radar>, zones: List<ControlZone>) {
        _nearbyRadars.value = radars
        _nearbyZones.value = zones
    }

    internal fun data(status: DataStatus) {
        _data.value = status
    }

    internal fun stopped() {
        _running.value = false
        _snapshot.value = null
        _demo.value = false
        _origin.value = DriveOrigin.HAND
        _nearbyRadars.value = emptyList()
        _nearbyZones.value = emptyList()
    }
}
