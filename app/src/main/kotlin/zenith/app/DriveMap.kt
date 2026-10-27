package zenith.app

import android.content.Context
import android.util.Log
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import zenith.core.DriveSnapshot
import zenith.core.LatLon
import zenith.core.Radar
import zenith.core.RadarType

/**
 * Small tilted map at the top of the drive screen: position dot, road ahead and nearby radars as
 * mini limit signs. Not interactive; it fades into the black background toward the bottom.
 * Vector tiles from OpenFreeMap (free, no key). Any map failure leaves an empty black box.
 */
@Composable
fun DriveMap(snapshot: DriveSnapshot?, radars: List<Radar>, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val holder = remember { MapHolder() }
    val view = remember { holder.create(context) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    DisposableEffect(lifecycle, view) {
        val map = view as? MapView
        val observer = LifecycleEventObserver { _, event ->
            runCatching {
                when (event) {
                    Lifecycle.Event.ON_START -> map?.onStart()
                    Lifecycle.Event.ON_RESUME -> map?.onResume()
                    Lifecycle.Event.ON_PAUSE -> map?.onPause()
                    Lifecycle.Event.ON_STOP -> map?.onStop()
                    else -> Unit
                }
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            runCatching {
                map?.onPause()
                map?.onStop()
                map?.onDestroy()
            }
        }
    }

    LaunchedEffect(snapshot) { snapshot?.let(holder::follow) }
    LaunchedEffect(radars, snapshot?.alerts?.firstOrNull()?.radar?.id) {
        holder.showRadars(radars, snapshot?.alerts?.map { it.radar.id }?.toSet().orEmpty())
    }

    Box(modifier) {
        AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
        // Fade: fully visible at the top, melting into the app background at the bottom.
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.35f),
                    0.25f to Color.Transparent,
                    0.55f to Color.Transparent,
                    1f to Color.Black,
                ),
            ),
        )
        Text(
            "© OpenFreeMap © OpenStreetMap",
            color = Color.White.copy(alpha = 0.25f),
            fontSize = 9.sp,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 6.dp, bottom = 2.dp),
        )
    }
}

/** Owns the MapView and its style objects; every call is a no-op until the style is loaded. */
private class MapHolder {
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var pendingRadars: String? = null
    private var lastCamera: CameraPosition? = null

    fun create(context: Context): View = runCatching {
        MapLibre.getInstance(context)
        // TextureView: blends with the Compose fade drawn on top of it.
        MapView(context, MapLibreMapOptions.createFromAttributes(context).textureMode(true)).apply {
            onCreate(null)
            getMapAsync { m ->
                map = m
                m.uiSettings.setAllGesturesEnabled(false)
                m.uiSettings.setCompassEnabled(false)
                m.uiSettings.setLogoEnabled(false)
                m.uiSettings.setAttributionEnabled(false)
                lastCamera?.let(m::setCameraPosition)
                m.setStyle(Style.Builder().fromUri(STYLE)) { s ->
                    runCatching { setUp(s) }.onFailure { Log.w("Zenith", "map style", it) }
                }
            }
        }
    }.getOrElse {
        Log.w("Zenith", "map", it)
        View(context)
    }

    /** Camera behind the car, tilted, zoomed out with speed, the car low in the frame. */
    fun follow(s: DriveSnapshot) {
        val kmh = (s.speedKmh ?: 0).toDouble()
        val camera = CameraPosition.Builder()
            .target(LatLng(s.position.lat, s.position.lon))
            .bearing(s.headingDeg ?: lastCamera?.bearing ?: 0.0)
            .tilt(55.0)
            .zoom(16.6 - ((kmh - 30).coerceIn(0.0, 100.0) / 100.0) * 2.2)
            .padding(0.0, (map?.height ?: 0f) * 0.35, 0.0, 0.0)
            .build()
        lastCamera = camera
        runCatching {
            map?.easeCamera(CameraUpdateFactory.newCameraPosition(camera), 1_000, false)
            style?.getSourceAs<GeoJsonSource>(SRC_ME)?.setGeoJson(point(s.position, "{}"))
            style?.getSourceAs<GeoJsonSource>(SRC_AHEAD)?.setGeoJson(line(s.ahead))
        }
    }

    fun showRadars(radars: List<Radar>, active: Set<String>) {
        val json = collection(
            radars.map { r ->
                val props = """{"c":"${color(r.type)}","l":"${r.limitKmh ?: ""}","r":${if (r.id in active) 12 else 9}}"""
                point(r.position, props)
            },
        )
        val source = style?.getSourceAs<GeoJsonSource>(SRC_RADARS)
        if (source == null) pendingRadars = json else runCatching { source.setGeoJson(json) }
    }

    private fun setUp(s: Style) {
        restyle(s)
        s.addSource(GeoJsonSource(SRC_AHEAD, line(emptyList())))
        s.addSource(GeoJsonSource(SRC_RADARS, pendingRadars ?: collection(emptyList())))
        s.addSource(GeoJsonSource(SRC_ME, collection(emptyList())))
        s.addLayer(
            LineLayer("ahead-glow", SRC_AHEAD).withProperties(
                PropertyFactory.lineColor(ACCENT), PropertyFactory.lineWidth(14f), PropertyFactory.lineOpacity(0.18f),
                PropertyFactory.lineBlur(6f), PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
        s.addLayer(
            LineLayer("ahead", SRC_AHEAD).withProperties(
                PropertyFactory.lineColor(ACCENT), PropertyFactory.lineWidth(5f), PropertyFactory.lineOpacity(0.85f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
        val radarColor = Expression.toColor(Expression.get("c"))
        s.addLayer(
            CircleLayer("radar-halo", SRC_RADARS).withProperties(
                PropertyFactory.circleColor(radarColor), PropertyFactory.circleOpacity(0.25f),
                PropertyFactory.circleRadius(Expression.sum(Expression.get("r"), Expression.literal(6))), PropertyFactory.circleBlur(0.6f),
            ),
        )
        s.addLayer(
            CircleLayer("radar", SRC_RADARS).withProperties(
                PropertyFactory.circleColor("#FFFFFF"), PropertyFactory.circleRadius(Expression.get("r")),
                PropertyFactory.circleStrokeColor(radarColor), PropertyFactory.circleStrokeWidth(3f),
            ),
        )
        s.addLayer(
            SymbolLayer("radar-limit", SRC_RADARS).withProperties(
                PropertyFactory.textField(Expression.get("l")), PropertyFactory.textFont(arrayOf("Noto Sans Bold")),
                PropertyFactory.textSize(10f), PropertyFactory.textColor("#000000"),
                PropertyFactory.textAllowOverlap(true), PropertyFactory.textIgnorePlacement(true),
            ),
        )
        s.addLayer(
            CircleLayer("me-glow", SRC_ME).withProperties(
                PropertyFactory.circleColor(ACCENT), PropertyFactory.circleRadius(18f), PropertyFactory.circleOpacity(0.25f), PropertyFactory.circleBlur(0.8f),
            ),
        )
        s.addLayer(
            CircleLayer("me", SRC_ME).withProperties(
                PropertyFactory.circleColor(ACCENT), PropertyFactory.circleRadius(7f),
                PropertyFactory.circleStrokeColor("#FFFFFF"), PropertyFactory.circleStrokeWidth(2.5f),
            ),
        )
        style = s
        pendingRadars = null
    }

    /** Darker, calmer roads and black background, so the map melts into the app. */
    private fun restyle(s: Style) {
        val colors = mapOf(
            "background" to PropertyFactory.backgroundColor("#000000"),
            "water" to PropertyFactory.fillColor("#0B1220"),
            "building" to PropertyFactory.fillColor("#0E0E11"),
            "highway_minor" to PropertyFactory.lineColor("#24242A"),
            "highway_major_casing" to PropertyFactory.lineColor("#1A1A1F"),
            "highway_major_inner" to PropertyFactory.lineColor("#34343C"),
            "highway_major_subtle" to PropertyFactory.lineColor("#34343C"),
            "highway_motorway_casing" to PropertyFactory.lineColor("#1E1E24"),
            "highway_motorway_inner" to PropertyFactory.lineColor("#45454F"),
            "highway_motorway_subtle" to PropertyFactory.lineColor("#45454F"),
        )
        for ((id, value) in colors) runCatching { s.getLayer(id)?.setProperties(value) }
        for (layer in s.layers) {
            if (layer.id.startsWith("boundary") || layer.id.startsWith("place_country") || layer.id.startsWith("place_state")) {
                runCatching { layer.setProperties(PropertyFactory.visibility(Property.NONE)) }
            }
        }
    }

    private fun point(p: LatLon, props: String) =
        """{"type":"Feature","properties":$props,"geometry":{"type":"Point","coordinates":[${p.lon},${p.lat}]}}"""

    private fun collection(features: List<String>) = """{"type":"FeatureCollection","features":[${features.joinToString(",")}]}"""

    private fun line(points: List<LatLon>): String =
        if (points.size < 2) collection(emptyList())
        else """{"type":"Feature","properties":{},"geometry":{"type":"LineString","coordinates":[${points.joinToString(",") { "[${it.lon},${it.lat}]" }}]}}"""

    private fun color(type: RadarType) = when (type) {
        RadarType.RED_LIGHT -> "#FF9F0A"
        RadarType.SECTION_START, RadarType.SECTION_END -> "#BF5AF2"
        RadarType.WORKZONE -> "#FFD60A"
        RadarType.MOBILE, RadarType.MOBILE_VEHICLE, RadarType.POLICE -> "#FF375F"
        else -> "#FF3B30"
    }

    private companion object {
        const val STYLE = "https://tiles.openfreemap.org/styles/dark"
        const val ACCENT = "#0A84FF"
        const val SRC_ME = "me"
        const val SRC_AHEAD = "ahead"
        const val SRC_RADARS = "radars"
    }
}
