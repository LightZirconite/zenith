package zenith.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import zenith.core.DriveSnapshot
import zenith.core.Radar

/** Live state shared between [DriveService] and the UI. */
object DriveState {
    private val _running = MutableStateFlow(false)
    private val _snapshot = MutableStateFlow<DriveSnapshot?>(null)
    private val _gps = MutableStateFlow(GpsInfo())
    private val _data = MutableStateFlow(DataStatus())
    private val _nearbyRadars = MutableStateFlow<List<Radar>>(emptyList())

    val running: StateFlow<Boolean> = _running
    val snapshot: StateFlow<DriveSnapshot?> = _snapshot
    val gps: StateFlow<GpsInfo> = _gps
    val data: StateFlow<DataStatus> = _data

    /** Radars around the car, for the map. */
    val nearbyRadars: StateFlow<List<Radar>> = _nearbyRadars

    internal fun started() {
        _snapshot.value = null
        _gps.value = GpsInfo()
        _running.value = true
    }

    internal fun update(snapshot: DriveSnapshot) {
        _snapshot.value = snapshot
        _gps.update { it.copy(accuracyM = snapshot.gpsAccuracyM, lastFixMs = System.currentTimeMillis()) }
    }

    internal fun satellites(used: Int, visible: Int) = _gps.update { it.copy(used = used, visible = visible) }

    internal fun nearbyRadars(radars: List<Radar>) {
        _nearbyRadars.value = radars
    }

    internal fun data(status: DataStatus) {
        _data.value = status
    }

    internal fun stopped() {
        _running.value = false
        _nearbyRadars.value = emptyList()
    }
}
