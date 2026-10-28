package zenith.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import androidx.car.app.AppManager
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.connection.CarConnection
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.notification.CarAppExtender
import androidx.car.app.notification.CarNotificationManager
import androidx.car.app.notification.CarPendingIntent
import androidx.car.app.validation.HostValidator
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import zenith.core.ActiveAlert
import zenith.core.RadarType
import kotlin.math.roundToInt

/**
 * Android Auto, seen from the phone: whether it is projecting to a car screen (Car App Library's
 * [CarConnection], no permission needed) and whether Zenith's own car screen is the one showing.
 * Android Auto connecting starts the drive on the phone; disconnecting ends the drive it started.
 */
object AndroidAuto {
    private val _connected = MutableStateFlow(false)
    private val _screenVisible = MutableStateFlow(false)
    private var watching = false

    /** The phone is projecting to a car screen. */
    val connected: StateFlow<Boolean> = _connected

    /** Zenith's own screen is the one on the car (else Waze, Maps or another app fills it). */
    val screenVisible: StateFlow<Boolean> = _screenVisible

    /** Follows Android Auto for the life of the process; main thread, cheap and idempotent. */
    fun watch(context: Context) {
        if (watching) return
        val app = context.applicationContext
        runCatching {
            CarConnection(app).type.observeForever { type ->
                val projecting = type == CarConnection.CONNECTION_TYPE_PROJECTION
                if (projecting == _connected.value) return@observeForever
                _connected.value = projecting
                if (projecting) start(app) else DriveService.stopIfStartedBy(app, DriveOrigin.ANDROID_AUTO)
            }
            watching = true
        }.onFailure { Log.w("Zenith", "android auto", it) }
    }

    /** Starts a drive that ends with Android Auto, or asks for a tap when Android forbids the start. */
    fun start(context: Context) {
        if (DriveState.running.value) return
        runCatching { DriveService.startAndroidAuto(context) }
            .onFailure { CarLink.notifyTapToStart(context, "Android Auto connecté") }
    }

    internal fun screen(visible: Boolean) {
        _screenVisible.value = visible
    }
}

/**
 * Zenith in Android Auto: a navigation app in the car's launcher (navigation apps alone may draw
 * their own screen). Hosts are checked against Google's list, any host in a debug build (the
 * Desktop Head Unit).
 */
class ZenithCarService : CarAppService() {
    override fun createHostValidator(): HostValidator =
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(this).addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample).build()
        }

    override fun onCreateSession(sessionInfo: SessionInfo): Session = DriveSession()
}

/** One car connection: opening Zenith on the car starts the drive on the phone. */
private class DriveSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen {
        val app = carContext.applicationContext
        AndroidAuto.watch(app)
        AndroidAuto.start(app)
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                // The connection watch normally ends the drive; this covers a host without it.
                if (!AndroidAuto.connected.value) DriveService.stopIfStartedBy(app, DriveOrigin.ANDROID_AUTO)
            }
        })
        return DriveCarScreen(carContext)
    }
}

/**
 * The car screen: a navigation template, so Zenith draws the whole screen itself ([CarSurface]),
 * the host adding only its buttons: start and demo while idle, stop while driving.
 */
private class DriveCarScreen(carContext: CarContext) : Screen(carContext) {
    private val hint = MutableStateFlow("")
    private val surface = CarSurface(carContext, hint)
    private var shownRunning: Boolean? = null

    init {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(surface)
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                AndroidAuto.screen(true)
                surface.shown(true)
            }

            override fun onStop(owner: LifecycleOwner) {
                AndroidAuto.screen(false)
                surface.shown(false)
            }

            override fun onDestroy(owner: LifecycleOwner) = surface.release()
        })
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                DriveState.running.collect { if (it != shownRunning) invalidate() }
            }
        }
    }

    override fun onGetTemplate(): Template {
        val running = DriveState.running.value
        shownRunning = running
        val located = ContextCompat.checkSelfPermission(carContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        hint.value = if (located) "Touche Démarrer pour les alertes" else "Autorise la position sur ton téléphone"
        val actions = when {
            running -> listOf(action("Arrêter") { DriveService.stop(carContext) })
            !located -> listOf(action("Autoriser", primary = true) { askLocation() })
            else -> listOf(
                action("Démarrer", primary = true) { AndroidAuto.start(carContext.applicationContext) },
                action("Démo") { runCatching { DriveService.startDemo(carContext) } },
            )
        }
        val strip = ActionStrip.Builder().apply { actions.forEach { addAction(it) } }.build()
        return NavigationTemplate.Builder().setActionStrip(strip).build()
    }

    /** Android's permission prompt, shown on the phone. */
    private fun askLocation() {
        carContext.requestPermissions(Permissions.locationPermissions().toList()) { granted, _ ->
            if (Manifest.permission.ACCESS_FINE_LOCATION in granted) AndroidAuto.start(carContext.applicationContext)
            invalidate()
        }
    }

    private fun action(title: String, primary: Boolean = false, onClick: () -> Unit): Action =
        Action.Builder()
            .setTitle(title)
            .setOnClickListener { onClick() }
            .apply { if (primary) setFlags(Action.FLAG_PRIMARY) }
            .build()
}

/**
 * The radar ahead as a heads-up notification on the car screen while another app (Waze, Maps)
 * fills it: Android Auto shows a car app's notifications over any app. One per radar, updated in
 * place as it nears (by 100 m, silently), gone once passed; a touch opens Zenith on the car. No
 * sound of its own: the drive's tones and voice already play. Drive thread only.
 */
object CarAlerts {
    private const val CHANNEL_ID = "car_alerts"
    private const val NOTIFICATION_ID = 3

    @Volatile
    private var shownRadar: String? = null
    private var shownText: String? = null

    @SuppressLint("MissingPermission") // Checked: nothing is posted without the notifications.
    fun update(context: Context, alert: ActiveAlert?) {
        if (alert == null) return clear(context)
        val title = "${alert.radar.type.label} · ${distanceText((alert.distanceM / 100).roundToInt() * 100.0)}"
        val text = when {
            alert.overLimit -> "Ralentis : limite ${alert.limitKmh}"
            alert.limitKmh != null -> "Limite ${alert.limitKmh}"
            else -> "Sur ta route"
        }
        if (alert.radar.id == shownRadar && "$title\n$text" == shownText) return
        val manager = CarNotificationManager.from(context)
        if (!manager.areNotificationsEnabled()) return
        runCatching {
            if (alert.radar.id != shownRadar) {
                // A new radar pops up again; the same one only changes in place.
                manager.cancel(NOTIFICATION_ID)
                manager.createNotificationChannel(
                    NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_HIGH)
                        .setName("Radars sur l'écran de la voiture")
                        .setSound(null, null)
                        .setVibrationEnabled(false)
                        .build(),
                )
            }
            val badge = badge(alert.radar.type, alert.limitKmh)
            val open = CarPendingIntent.getCarApp(
                context, 5, Intent().setComponent(ComponentName(context, ZenithCarService::class.java)), PendingIntent.FLAG_IMMUTABLE,
            )
            val car = CarAppExtender.Builder()
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_notification)
                .setLargeIcon(badge)
                .setContentIntent(open)
                .setImportance(NotificationManagerCompat.IMPORTANCE_HIGH)
                .apply { if (alert.overLimit) setColor(CarColor.RED) }
                .build()
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setLargeIcon(badge)
                .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
                .setOnlyAlertOnce(true)
                .setTimeoutAfter(TIMEOUT_MS)
                .extend(car)
            manager.notify(NOTIFICATION_ID, notification)
            shownRadar = alert.radar.id
            shownText = "$title\n$text"
        }.onFailure { Log.w("Zenith", "car alert", it) }
    }

    fun clear(context: Context) {
        if (shownRadar == null) return
        shownRadar = null
        shownText = null
        runCatching { CarNotificationManager.from(context).cancel(NOTIFICATION_ID) }
    }

    /** The round badge of the screen (white disc, ring in the radar's color, limit inside) as a bitmap. */
    private fun badge(type: RadarType, limitKmh: Int?, px: Int = 144): Bitmap {
        val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val c = px / 2f
        canvas.drawCircle(c, c, c, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = radarColorArgb(type).toInt() })
        canvas.drawCircle(c, c, c - px / 9f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE })
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.BLACK
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
            textSize = px * if ((limitKmh ?: 0) >= 100) 0.30f else 0.36f
        }
        canvas.drawText(limitKmh?.toString() ?: "!", c, c - (paint.descent() + paint.ascent()) / 2, paint)
        return bitmap
    }

    /** A notification left behind (drive thread busy, radar lost from sight) does not linger. */
    private const val TIMEOUT_MS = 60_000L
}
