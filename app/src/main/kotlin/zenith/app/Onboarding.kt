package zenith.app

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** What Zenith is allowed to do; re-read every time the app comes back to the front. */
data class Permissions(
    val location: Boolean,
    val notifications: Boolean,
    val installs: Boolean,
    val battery: Boolean,
    /** Notification access, for the music bar (Spotify…): advised, not required. */
    val media: Boolean = false,
    /** Automatic start by activity recognition: Play Services present, activity and "all the time" location. */
    val motionAvailable: Boolean = false,
    val activity: Boolean = false,
    val backgroundLocation: Boolean = false,
    /** "Display over other apps": lets an automatic start open the small window at once. */
    val overlay: Boolean = false,
    /** The device lets the user grant it (not on Android Go, nor where the screen is missing). */
    val overlayAvailable: Boolean = false,
) {
    val motion: Boolean get() = motionAvailable && activity && backgroundLocation

    /** The small window opens by itself only after an automatic start (riding, or a paired car). */
    fun overlayNeeded(motionWanted: Boolean): Boolean = overlayAvailable && ((motionAvailable && motionWanted) || CarLink.supported)

    /**
     * Required permissions still missing; the motion start counts only when wanted and possible
     * here. The notification access is not among them: a Spotify account can stand in for it, and
     * [MusicInvite] asks for one or the other.
     */
    fun missing(motionWanted: Boolean): Int =
        listOf(
            location, notifications, installs, battery,
            !(motionAvailable && motionWanted) || motion,
            !overlayNeeded(motionWanted) || overlay,
        ).count { !it }

    companion object {
        /**
         * A setting the device has no screen for (car head units, Android Go) cannot be granted:
         * it counts as done, or the welcome screen would never let the driver through.
         */
        fun read(context: Context): Permissions {
            val lowRam = runCatching { context.getSystemService(ActivityManager::class.java).isLowRamDevice }.getOrDefault(false)
            return Permissions(
                location = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED,
                notifications = NotificationManagerCompat.from(context).areNotificationsEnabled(),
                installs = context.packageManager.canRequestPackageInstalls() || !canOpen(context, installsIntent(context)),
                battery = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName) ||
                    !canOpen(context, batteryIntent(context)),
                media = NowPlaying.allowed(context) || lowRam || !canOpen(context, NowPlaying.accessIntent(context)),
                motionAvailable = Motion.available(context),
                activity = Motion.hasActivity(context),
                backgroundLocation = Motion.hasBackgroundLocation(context),
                overlay = canOverlay(context),
                overlayAvailable = !lowRam && canOpen(context, overlayIntent(context)),
            )
        }

        /**
         * Whether the device has the settings screen. Android 11+ hides other apps from this check
         * (package visibility) and every phone has these screens: only older systems are asked.
         */
        private fun canOpen(context: Context, intent: Intent): Boolean =
            Build.VERSION.SDK_INT >= 30 || runCatching { intent.resolveActivity(context.packageManager) != null }.getOrDefault(true)

        fun canOverlay(context: Context): Boolean = runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)

        /** Android's "display over other apps" switch (its list of apps on Android 11+). */
        fun overlayIntent(context: Context) =
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))

        fun locationPermissions() = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

        fun notificationPermission(): String? = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else null

        /** System screen with the "allow installs from this source" switch. */
        fun installsIntent(context: Context) =
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

        /** System dialog "let Zenith run in the background without restriction". */
        fun batteryIntent(context: Context) =
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
    }
}

private val Surface = Color(0xD916161A)
private val Green = Color(0xFF30D158)
private val Accent = Color(0xFF0A84FF)
private val Dim = Color(0xFF8E8E93)

/**
 * Every permission Zenith needs, each with the reason in plain words, so none of Android's prompts
 * comes as a surprise. All are required but the music's (advised): the screen comes back whenever
 * one is missing (outside a drive), and lets the driver through only once they are granted. A bar
 * fills as they come, each granted step lights up with a check and a ring of light.
 */
@Composable
fun Onboarding(
    permissions: Permissions,
    onLocation: () -> Unit,
    onNotifications: () -> Unit,
    onInstalls: () -> Unit,
    onBattery: () -> Unit,
    onDone: () -> Unit,
    /** Shown again because something is missing: not the first launch. */
    returning: Boolean = false,
    motionWanted: Boolean = true,
    onMotion: () -> Unit = {},
    onMedia: () -> Unit = {},
    onOverlay: () -> Unit = {},
    /** Where the notification access stands on Android 13+: 0 activate, 1 unlock (blocked), 2 activate again. */
    mediaStep: Int = 0,
    /** A Spotify account already shows the music: the access only adds the other players. */
    spotifyConnected: Boolean = false,
    /** The big button: the next missing permission, one after the other. */
    onNext: () -> Unit = {},
) {
    val missing = permissions.missing(motionWanted)
    val showMotion = permissions.motionAvailable && motionWanted
    val showOverlay = permissions.overlayNeeded(motionWanted)
    val total = 4 + (if (showMotion) 1 else 0) + (if (showOverlay) 1 else 0)
    LargeTitlePage(
        title = if (returning) "Autorisations" else "Bienvenue",
        bottom = { GoButton(missing, onClick = if (missing == 0) onDone else onNext) },
    ) {
        var order = 0
        Spacer(Modifier.height(8.dp))
        Text(
            if (returning) {
                "Il en manque pour que Zenith marche à fond : nouvelles fonctions, ou une autorisation retirée. Voici pourquoi chacune est là."
            } else {
                "Zenith a besoin de quelques autorisations pour t'alerter, même écran éteint ou derrière Waze. " +
                    "Android va afficher ses propres fenêtres : voici pourquoi chacune est là."
            },
            color = Dim,
            fontSize = 16.sp,
            modifier = Modifier.cascade(order++),
        )
        Spacer(Modifier.height(18.dp))
        Progress((total - missing).coerceAtLeast(0), total, Modifier.cascade(order++))
        Spacer(Modifier.height(18.dp))
        Step(
            title = "Position précise",
            text = "Pour mesurer ta vitesse et savoir quels radars sont sur ta route. Ta position ne quitte jamais le téléphone. " +
                "Choisis « Pendant l'utilisation de l'appli ».",
            granted = permissions.location,
            onClick = onLocation,
            modifier = Modifier.cascade(order++),
        )
        Step(
            title = "Notifications",
            text = "Android exige une notification visible pour que Zenith reste actif en arrière-plan. Elle affiche ta vitesse et le prochain radar.",
            granted = permissions.notifications,
            onClick = onNotifications,
            modifier = Modifier.cascade(order++),
        )
        Step(
            title = "Mises à jour automatiques",
            text = "Zenith se met à jour tout seul depuis GitHub. Comme l'app ne vient pas du Play Store, Android parle " +
                "d'« applications inconnues » : c'est normal. Active simplement l'interrupteur puis reviens.",
            granted = permissions.installs,
            onClick = onInstalls,
            modifier = Modifier.cascade(order++),
        )
        Step(
            title = "Batterie sans restriction",
            text = "Pour qu'Android ne coupe pas les alertes au milieu d'un long trajet. Zenith ne consomme que pendant la conduite.",
            granted = permissions.battery,
            onClick = onBattery,
            modifier = Modifier.cascade(order++),
        )
        if (showMotion) {
            Step(
                title = "Démarrage en roulant",
                text = when {
                    !permissions.activity ->
                        "Zenith démarre seul dès que le téléphone sent que tu roules en voiture, même fermé, presque sans batterie. " +
                            "D'abord « Activité physique », puis la position « Toujours autoriser »."
                    !permissions.backgroundLocation ->
                        "Encore la position « Toujours autoriser » : sans elle, Android coupe le GPS d'une conduite démarrée seule."
                    else -> "Zenith démarre seul quand tu roules en voiture et s'arrête une fois garé."
                },
                granted = permissions.motion,
                onClick = onMotion,
                modifier = Modifier.cascade(order++),
            )
        }
        // Android 13+ blocks this one for apps installed outside a store: it shows the unlock
        // option only after a first try, so the button walks through the three steps.
        val unlock = Build.VERSION.SDK_INT >= 33 && mediaStep == 1
        Step(
            title = "Musique · conseillé",
            text = when {
                unlock -> "Android l'a bloqué ? Touche Débloquer : ⋮ en haut à droite → « Autoriser les paramètres restreints », " +
                    "valide avec ton code, puis reviens ici."
                mediaStep == 2 -> "Débloqué : touche Activer, puis active Zenith."
                spotifyConnected -> "Spotify est connecté. L'accès ajoute les autres lecteurs (Deezer, YouTube Music, Apple Music…). " +
                    "Android appelle cela « accès aux notifications » : Zenith n'y lit que le lecteur."
                else -> "Titre, Canvas et commandes de ton lecteur (Spotify, Deezer, YouTube Music…) pendant la conduite, sans compte. " +
                    "Android appelle cela « accès aux notifications » : Zenith n'y lit que le lecteur. Pas obligatoire : " +
                    "avec Spotify, tu peux aussi connecter ton compte dans les réglages."
            },
            granted = permissions.media,
            onClick = onMedia,
            action = if (unlock) "Débloquer" else "Activer",
            modifier = Modifier.cascade(order++),
        )
        if (showOverlay) {
            Step(
                title = "Petite fenêtre automatique",
                text = "Quand Zenith démarre seul en voiture, sa petite fenêtre s'ouvre aussitôt par-dessus tes applis. " +
                    "Android appelle cela « superposition » : active Zenith puis reviens.",
                granted = permissions.overlay,
                onClick = onOverlay,
                action = "Activer",
                modifier = Modifier.cascade(order++),
            )
        }
        Spacer(Modifier.height(12.dp))
    }
}

/** How many of the permissions are granted: a bar of light filling with a spring, green when full. */
@Composable
private fun Progress(done: Int, total: Int, modifier: Modifier = Modifier) {
    val k by animateFloatAsState(done / total.toFloat(), spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessLow), label = "progress")
    val color by animateColorAsState(if (done == total) Green else Accent, tween(600), label = "progressColor")
    Column(modifier) {
        Text("$done sur $total", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.1f))) {
            Box(
                Modifier.fillMaxHeight().fillMaxWidth(k.coerceIn(0f, 1f)).clip(RoundedCornerShape(50))
                    .background(Brush.horizontalGradient(listOf(color.copy(alpha = 0.6f), color))),
            )
        }
    }
}

/** The one button: the next permission, the count rolling down, then a glowing "C'est parti". */
@Composable
private fun GoButton(missing: Int, onClick: () -> Unit) {
    val ready = missing == 0
    val glow = rememberInfiniteTransition(label = "go")
    val pulse by glow.animateFloat(0f, 1f, infiniteRepeatable(tween(1_400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "goPulse")
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
            .drawBehind {
                if (!ready) return@drawBehind
                val grow = 8.dp.toPx() * pulse
                drawRoundRect(
                    Green.copy(alpha = 0.35f * (1f - pulse)),
                    topLeft = Offset(-grow, -grow),
                    size = Size(size.width + 2 * grow, size.height + 2 * grow),
                    cornerRadius = CornerRadius(18.dp.toPx() + grow),
                    style = Stroke(2.dp.toPx()),
                )
            }
            .tap(
                shape = RoundedCornerShape(18.dp),
                sound = if (ready) UiSound.Kind.SUCCESS else UiSound.Kind.OPEN,
                lift = 1.04f,
                glow = PressShade,
                onClick = onClick,
            )
            .clip(RoundedCornerShape(18.dp)).background(Color.White).padding(vertical = 18.dp),
    ) {
        AnimatedContent(
            missing,
            transitionSpec = {
                (slideInVertically(spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow)) { it } + fadeIn(tween(250)))
                    .togetherWith(slideOutVertically(tween(250)) { -it } + fadeOut(tween(200)))
            },
            label = "goText",
        ) { left ->
            Text(
                when (left) {
                    0 -> "C'est parti"
                    1 -> "Continuer · dernière autorisation"
                    else -> "Continuer · encore $left"
                },
                color = Color.Black,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun Step(
    title: String,
    text: String,
    granted: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    action: String = "Autoriser",
) {
    // Granted while the screen is open: a ring of light runs out from the check, a warm little chord.
    val view = LocalView.current
    var seen by remember { mutableStateOf(granted) }
    val burst = remember { Animatable(1f) }
    LaunchedEffect(granted) {
        if (granted && !seen) {
            UiSound.play(UiSound.Kind.SUCCESS, view)
            burst.snapTo(0f)
            burst.animateTo(1f, tween(800, easing = LinearOutSlowInEasing))
        }
        seen = granted
    }
    val edge by animateColorAsState(if (granted) Green.copy(alpha = 0.35f) else Color.Transparent, tween(600), label = "stepEdge")
    Row(
        modifier.fillMaxWidth().padding(bottom = 12.dp)
            .tap(enabled = !granted, shape = RoundedCornerShape(22.dp), sound = UiSound.Kind.OPEN, lift = 0.98f, onClick = onClick)
            .glass(RoundedCornerShape(22.dp), Surface)
            .drawBehind { drawRect(Brush.horizontalGradient(listOf(Color.Transparent, edge), startX = size.width * 0.5f)) }
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(text, color = Color.White.copy(alpha = 0.75f), fontSize = 14.sp)
        }
        Spacer(Modifier.width(14.dp))
        AnimatedContent(
            granted,
            transitionSpec = {
                (scaleIn(spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMediumLow), initialScale = 0.3f) + fadeIn(tween(200)))
                    .togetherWith(scaleOut(tween(200), targetScale = 0.6f) + fadeOut(tween(150)))
            },
            contentAlignment = Alignment.Center,
            label = "stepState",
        ) { done ->
            if (done) {
                Box(
                    Modifier.size(30.dp).drawBehind {
                        val k = burst.value
                        if (k < 1f) {
                            drawCircle(Green.copy(alpha = 0.6f * (1f - k)), radius = size.minDimension / 2 * (1f + 1.4f * k), style = Stroke(2.dp.toPx() * (1f - k) + 0.5f))
                        }
                    }.background(Green, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("✓", color = Color.Black, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            } else {
                Text(
                    action,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(Accent).padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}

private val SpotifyGreen = Color(0xFF1ED760)

/**
 * The invitation to let Zenith see the music, on home once the required permissions are granted:
 * the notification access is no longer required, but without it (or a Spotify account) the music
 * card, its Canvas and its controls stay away. A glass card rising on a dim veil with a soft breath;
 * the access walks through Android 13's unlock like the welcome screen, Spotify signs in in the
 * browser. Never closed by accident: the veil holds the touches, only "Plus tard" or back close it.
 */
@Composable
fun MusicInvite(
    visible: Boolean,
    /** Where the notification access stands on Android 13+: 0 activate, 1 unlock (blocked), 2 activate again. */
    mediaStep: Int,
    /** A Spotify client id is built in: the account can stand in for the access. */
    spotifyAvailable: Boolean,
    onAllowMedia: () -> Unit,
    onSpotify: () -> Unit,
    onLater: () -> Unit,
) {
    val view = LocalView.current
    LaunchedEffect(visible) { if (visible) UiSound.play(UiSound.Kind.OPEN, view) }
    BackHandler(enabled = visible) {
        UiSound.play(UiSound.Kind.CLOSE, view)
        onLater()
    }
    AnimatedVisibility(visible, enter = fadeIn(tween(350)), exit = fadeOut(tween(300))) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).pointerInput(Unit) { detectTapGestures { } })
    }
    Box(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible,
            enter = slideInVertically(spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow)) { it / 2 } +
                scaleIn(spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow), initialScale = 0.9f) + fadeIn(tween(250)),
            exit = slideOutVertically(tween(300)) { it / 3 } + scaleOut(tween(300), targetScale = 0.95f) + fadeOut(tween(250)),
        ) {
            val unlock = Build.VERSION.SDK_INT >= 33 && mediaStep == 1
            Column(
                Modifier.widthIn(max = 520.dp).fillMaxWidth().glass(RoundedCornerShape(28.dp), Surface).sheen()
                    .verticalScroll(rememberScrollState()).padding(20.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(34.dp).background(SpotifyGreen, CircleShape), contentAlignment = Alignment.Center) {
                        Text("♪", color = Color.Black, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(12.dp))
                    Text("Ta musique pendant la conduite", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "Sans l'accès aux notifications, la carte Musique, le Canvas et les commandes ne marchent pas pour Deezer, " +
                        "YouTube Music, Apple Music, etc. C'est son nom chez Android : Zenith n'y lit que le lecteur. Vivement conseillé.",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 15.sp,
                    lineHeight = 20.sp,
                )
                if (unlock) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Android l'a bloqué ? Touche Débloquer : ⋮ en haut à droite → « Autoriser les paramètres restreints », " +
                            "valide avec ton code, puis reviens et touche Activer.",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 15.sp,
                        lineHeight = 20.sp,
                    )
                }
                if (spotifyAvailable) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Si tu utilises Spotify et n'arrives pas à activer l'accès (paramètres restreints), connecte plutôt Spotify : " +
                            "c'est aussi complet et plus rapide.",
                        color = Dim,
                        fontSize = 14.sp,
                        lineHeight = 19.sp,
                    )
                }
                Spacer(Modifier.height(8.dp))
                InviteButton(if (unlock) "Débloquer" else "Activer l'accès", Color.White, Color.Black, UiSound.Kind.OPEN, onAllowMedia)
                if (spotifyAvailable) InviteButton("Se connecter à Spotify", SpotifyGreen, Color.Black, UiSound.Kind.OPEN, onSpotify)
                InviteButton("Plus tard", null, Color.White.copy(alpha = 0.85f), UiSound.Kind.CLOSE, onLater)
            }
        }
    }
}

/** A full-width button of [MusicInvite]: filled with [fill], or glass when null. */
@Composable
private fun InviteButton(label: String, fill: Color?, textColor: Color, sound: UiSound.Kind, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Box(
        Modifier.fillMaxWidth().padding(top = 10.dp)
            .tap(shape = shape, sound = sound, lift = 1.04f, glow = if (fill != null) PressShade else PressLight, onClick = onClick)
            .then(if (fill != null) Modifier.clip(shape).background(fill) else Modifier.glass(shape))
            .padding(vertical = 15.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = textColor, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    }
}
