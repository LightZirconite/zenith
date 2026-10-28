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
import androidx.compose.foundation.clickable
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
) {
    companion object {
        fun read(context: Context) = Permissions(
            location = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED,
            notifications = NotificationManagerCompat.from(context).areNotificationsEnabled(),
            installs = context.packageManager.canRequestPackageInstalls(),
            battery = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName),
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
 * First launch: every permission Zenith needs, each with the reason in plain words, so none of
 * Android's prompts comes as a surprise. Only the location is required to start.
 */
@Composable
fun Onboarding(
    permissions: Permissions,
    onLocation: () -> Unit,
    onNotifications: () -> Unit,
    onInstalls: () -> Unit,
    onBattery: () -> Unit,
    onDone: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().background(Bg).safeDrawingPadding().padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(24.dp))
            Text("Bienvenue", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "Zenith a besoin de quelques autorisations pour t'alerter, même écran éteint ou derrière Waze. " +
                    "Android va afficher ses propres fenêtres : voici pourquoi chacune est là.",
                color = Dim,
                fontSize = 16.sp,
            )
            Spacer(Modifier.height(24.dp))
            Step(
                title = "Position précise",
                text = "Pour mesurer ta vitesse et savoir quels radars sont sur ta route. Ta position ne quitte jamais le téléphone. " +
                    "Choisis « Pendant l'utilisation de l'appli ».",
                granted = permissions.location,
                required = true,
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
        }
        Spacer(Modifier.height(12.dp))
        Text(
            if (permissions.location) "C'est parti" else "Autorise d'abord la position",
            color = if (permissions.location) Color.Black else Dim,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                .background(if (permissions.location) Color.White else Surface)
                .clickable(enabled = permissions.location, onClick = onDone).padding(vertical = 18.dp),
        )
    }
}

@Composable
private fun Step(title: String, text: String, granted: Boolean, required: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(RoundedCornerShape(20.dp)).background(Surface)
            .clickable(enabled = !granted, onClick = onClick).padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            // The label is measured first and stays on one line; a long title wraps in what is left
            // (it used to take the whole width and squeeze the label into one letter per line).
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(8.dp))
                Text(if (required) "obligatoire" else "conseillé", color = Dim, fontSize = 13.sp, maxLines = 1, softWrap = false)
            }
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
                "Autoriser",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(Accent).padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}
