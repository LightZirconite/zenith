package zenith.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.util.Log
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import kotlin.math.cos
import kotlin.math.ln

/**
 * Tilted map at the top of the drive screen: arrow for the car, road ahead and nearby radars as
 * mini limit signs. The camera frames the next radar on the road when there is one; dragging or
 * pinching looks around, and the camera comes back on its own after a few seconds.
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
        // Long, progressive fade: clear at the top, melting into the black app background below.
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.45f),
                    0.12f to Color.Transparent,
                    0.42f to Color.Transparent,
                    0.62f to Color.Black.copy(alpha = 0.35f),
                    0.8f to Color.Black.copy(alpha = 0.75f),
                    1f to Color.Black,
                ),
            ),
        )
    }
}

/** Owns the MapView and its style objects; every call is a no-op until the style is loaded. */
private class MapHolder {
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var pendingRadars: String? = null
    private var lastCamera: CameraPosition? = null
    private var zoom: Double? = null
    private var density = 1f

    /** The driver is looking around: the camera stops following until then. */
    private var freeUntilMs = 0L

    fun create(context: Context): View = runCatching {
        density = context.resources.displayMetrics.density
        MapLibre.getInstance(context)
        // TextureView: blends with the Compose fade drawn on top of it.
        MapView(context, MapLibreMapOptions.createFromAttributes(context).textureMode(true)).apply {
            onCreate(null)
            getMapAsync { m ->
                map = m
                m.uiSettings.setCompassEnabled(false)
                m.uiSettings.setLogoEnabled(false)
                m.uiSettings.setAttributionEnabled(false)
                m.uiSettings.setRotateGesturesEnabled(false)
                m.uiSettings.setTiltGesturesEnabled(false)
                m.addOnCameraMoveStartedListener { reason ->
                    if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                        freeUntilMs = SystemClock.elapsedRealtime() + LOOK_AROUND_MS
                    }
                }
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

    /**
     * Camera behind the car, seen fairly high from above so the road far ahead shows. Zooms out
     * with speed, and further when needed to frame the next radar on the road (up to 4 km).
     */
    fun follow(s: DriveSnapshot) {
        runCatching {
            style?.getSourceAs<GeoJsonSource>(SRC_ME)?.setGeoJson(point(s.position, """{"h":${s.headingDeg ?: lastCamera?.bearing ?: 0.0}}"""))
            style?.getSourceAs<GeoJsonSource>(SRC_AHEAD)?.setGeoJson(line(s.ahead))
        }
        val m = map ?: return
        if (SystemClock.elapsedRealtime() < freeUntilMs) return

        val kmh = (s.speedKmh ?: 0).toDouble()
        val speedZoom = 16.3 - ((kmh - 30).coerceIn(0.0, 100.0) / 100.0) * 2.3
        val heightPx = m.height.toDouble().takeIf { it > 0 } ?: (300.0 * density)
        // Car at 45 % of the map height: the speed counter covers the faded part below.
        val aheadPx = heightPx * CAR_AT / density
        val framed = s.alerts.firstOrNull()?.distanceM ?: s.upcoming?.takeIf { it.roadName != null }?.distanceM
        val fitZoom = framed?.takeIf { it in 50.0..4_000.0 }?.let { d ->
            // Ground meters per logical pixel at zoom z: 40075016.686 cos(lat) / (512 · 2^z);
            // tilt stretches the far half of the view, roughly ×1.7 at 40°.
            log2(40_075_016.686 * cos(Math.toRadians(s.position.lat)) * aheadPx * 1.7 / (512 * d * 1.25))
        }
        val target = minOf(speedZoom, fitZoom ?: speedZoom).coerceIn(12.5, 17.0)
        val z = zoom?.let { it + (target - it) * 0.3 } ?: target
        zoom = z
        // Zoomed out for a far radar: look more from above.
        val tilt = 42.0 - ((speedZoom - z).coerceIn(0.0, 2.0) / 2.0) * 12.0
        val camera = CameraPosition.Builder()
            .target(LatLng(s.position.lat, s.position.lon))
            .bearing(s.headingDeg ?: lastCamera?.bearing ?: 0.0)
            .tilt(tilt)
            .zoom(z)
            .padding(0.0, 0.0, 0.0, heightPx * (1 - 2 * CAR_AT))
            .build()
        lastCamera = camera
        runCatching { m.easeCamera(CameraUpdateFactory.newCameraPosition(camera), 1_000, false) }
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
        s.addImage(PUCK, puck(density))
        s.addSource(GeoJsonSource(SRC_AHEAD, line(emptyList())))
        s.addSource(GeoJsonSource(SRC_RADARS, pendingRadars ?: collection(emptyList())))
        s.addSource(GeoJsonSource(SRC_ME, collection(emptyList())))
        s.addLayer(
            LineLayer("ahead-glow", SRC_AHEAD).withProperties(
                PropertyFactory.lineColor(ACCENT), PropertyFactory.lineWidth(16f), PropertyFactory.lineOpacity(0.16f),
                PropertyFactory.lineBlur(8f), PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
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
                PropertyFactory.circleColor(ACCENT), PropertyFactory.circleRadius(22f), PropertyFactory.circleOpacity(0.22f),
                PropertyFactory.circleBlur(1f), PropertyFactory.circlePitchAlignment(Property.CIRCLE_PITCH_ALIGNMENT_MAP),
            ),
        )
        // Navigation arrow lying flat on the road, pointing the travel direction.
        s.addLayer(
            SymbolLayer("me", SRC_ME).withProperties(
                PropertyFactory.iconImage(PUCK), PropertyFactory.iconRotate(Expression.get("h")),
                PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_MAP),
                PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true),
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

    /** Arrow pointing up: blue body, white outline, soft blue glow. */
    private fun puck(density: Float): Bitmap {
        val size = (40 * density).toInt()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val f = size.toFloat()
        val path = Path().apply {
            moveTo(f * 0.5f, f * 0.12f)
            lineTo(f * 0.82f, f * 0.86f)
            lineTo(f * 0.5f, f * 0.68f)
            lineTo(f * 0.18f, f * 0.86f)
            close()
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0xFF0A84FF.toInt()
            setShadowLayer(f * 0.08f, 0f, 0f, 0xAA0A84FF.toInt())
        }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeWidth = f * 0.07f
            color = 0xFFFFFFFF.toInt()
        }
        canvas.drawPath(path, fill)
        canvas.drawPath(path, stroke)
        return bitmap
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

    private fun log2(x: Double) = ln(x) / ln(2.0)

    private companion object {
        const val STYLE = "https://tiles.openfreemap.org/styles/dark"
        const val ACCENT = "#0A84FF"
        const val PUCK = "puck"
        const val SRC_ME = "me"
        const val SRC_AHEAD = "ahead"
        const val SRC_RADARS = "radars"
        const val CAR_AT = 0.45
        const val LOOK_AROUND_MS = 8_000L
    }
}
