package zenith.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
    /** Notification access, for the music bar (Spotify…). */
    val media: Boolean = false,
    /** Automatic start by activity recognition: Play Services present, activity and "all the time" location. */
    val motionAvailable: Boolean = false,
    val activity: Boolean = false,
    val backgroundLocation: Boolean = false,
) {
    val motion: Boolean get() = motionAvailable && activity && backgroundLocation

    /** Permissions still missing; the motion start counts only when wanted and possible here. */
    fun missing(motionWanted: Boolean): Int =
        listOf(location, notifications, installs, battery, media, !(motionAvailable && motionWanted) || motion).count { !it }

    companion object {
        fun read(context: Context) = Permissions(
            location = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED,
            notifications = NotificationManagerCompat.from(context).areNotificationsEnabled(),
            installs = context.packageManager.canRequestPackageInstalls(),
            battery = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName),
            media = NowPlaying.allowed(context),
            motionAvailable = Motion.available(context),
            activity = Motion.hasActivity(context),
            backgroundLocation = Motion.hasBackgroundLocation(context),
        )

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

private val Bg = Color(0xFF000000)
private val Surface = Color(0xFF16161A)
private val Green = Color(0xFF30D158)
private val Accent = Color(0xFF0A84FF)
private val Dim = Color(0xFF8E8E93)

/**
 * Every permission Zenith needs, each with the reason in plain words, so none of Android's prompts
 * comes as a surprise. All are required: the screen comes back whenever one is missing (outside a
 * drive), and lets the driver through only once everything is granted.
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
    /** Where the notification access stands on Android 13+: 0 activate, 1 unlock (blocked), 2 activate again. */
    mediaStep: Int = 0,
    /** The big button: the next missing permission, one after the other. */
    onNext: () -> Unit = {},
) {
    val missing = permissions.missing(motionWanted)
    Column(
        Modifier.fillMaxSize().background(Bg).safeDrawingPadding().padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(24.dp))
            Text(if (returning) "Autorisations" else "Bienvenue", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
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
            )
            Spacer(Modifier.height(24.dp))
            Step(
                title = "Position précise",
                text = "Pour mesurer ta vitesse et savoir quels radars sont sur ta route. Ta position ne quitte jamais le téléphone. " +
                    "Choisis « Pendant l'utilisation de l'appli ».",
                granted = permissions.location,
                onClick = onLocation,
            )
            Step(
                title = "Notifications",
                text = "Android exige une notification visible pour que Zenith reste actif en arrière-plan. Elle affiche ta vitesse et le prochain radar.",
                granted = permissions.notifications,
                onClick = onNotifications,
            )
            Step(
                title = "Mises à jour automatiques",
                text = "Zenith se met à jour tout seul depuis GitHub. Comme l'app ne vient pas du Play Store, Android parle " +
                    "d'« applications inconnues » : c'est normal. Active simplement l'interrupteur puis reviens.",
                granted = permissions.installs,
                onClick = onInstalls,
            )
            Step(
                title = "Batterie sans restriction",
                text = "Pour qu'Android ne coupe pas les alertes au milieu d'un long trajet. Zenith ne consomme que pendant la conduite.",
                granted = permissions.battery,
                onClick = onBattery,
            )
            if (permissions.motionAvailable && motionWanted) {
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
                )
            }
            // Android 13+ blocks this one for apps installed outside a store: it shows the unlock
            // option only after a first try, so the button walks through the three steps.
            val unlock = Build.VERSION.SDK_INT >= 33 && mediaStep == 1
            Step(
                title = "Musique",
                text = when {
                    unlock -> "Android l'a bloqué ? Touche Débloquer : ⋮ en haut à droite → « Autoriser les paramètres restreints », " +
                        "valide avec ton code, puis reviens ici."
                    mediaStep == 2 -> "Débloqué : touche Activer, puis active Zenith."
                    else -> "Titre et commandes de Spotify, YouTube ou ton lecteur pendant la conduite, sans compte. Android appelle " +
                        "cela « accès aux notifications » : Zenith n'y lit que le lecteur. Touche Activer, puis active Zenith."
                },
                granted = permissions.media,
                onClick = onMedia,
                action = if (unlock) "Débloquer" else "Activer",
            )
        }
        Spacer(Modifier.height(12.dp))
        // One button for everything: each tap opens the next missing permission.
        Text(
            when (missing) {
                0 -> "C'est parti"
                1 -> "Continuer · dernière autorisation"
                else -> "Continuer · encore $missing"
            },
            color = Color.Black,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().tap(onClick = if (missing == 0) onDone else onNext).clip(RoundedCornerShape(18.dp))
                .background(Color.White).padding(vertical = 18.dp),
        )
    }
}

@Composable
private fun Step(title: String, text: String, granted: Boolean, onClick: () -> Unit, action: String = "Autoriser") {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 12.dp).tap(enabled = !granted, onClick = onClick)
            .glass(RoundedCornerShape(20.dp), Surface).padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(text, color = Color.White.copy(alpha = 0.75f), fontSize = 14.sp)
        }
        Spacer(Modifier.width(14.dp))
        if (granted) {
            Box(Modifier.size(30.dp).background(Green, CircleShape), contentAlignment = Alignment.Center) {
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
