package zenith.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Surface = Color(0xFF16161A)
private val Accent = Color(0xFF0A84FF)
private val Dim = Color(0xFF8E8E93)

/** The few choices Zenith offers. Everything else is automatic. */
@Composable
fun SettingsScreen(
    settings: SettingsState,
    version: String,
    onChange: ((SettingsState) -> SettingsState) -> Unit,
    onPairCar: () -> Unit,
    onUnpairCar: () -> Unit,
    onBack: () -> Unit,
    onShareTrip: (() -> Unit)? = null,
    mediaAllowed: Boolean = false,
    onAllowMedia: () -> Unit = {},
    permissions: Permissions? = null,
    onMotionPermission: () -> Unit = {},
) {
    Column(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text(
            "‹ Retour",
            color = Accent,
            fontSize = 17.sp,
            modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onBack).padding(vertical = 10.dp, horizontal = 4.dp),
        )
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(8.dp))
            Text("Réglages", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(20.dp))
            Toggle(
                "Jeune permis",
                "Limites probatoires : 110 sur autoroute, 100 sur voie rapide, 80 sur route à 90.",
                settings.probationary,
            ) { on -> onChange { it.copy(probationary = on) } }
            Toggle(
                "Pluie automatique",
                "Pendant la conduite, la météo (Open-Meteo, gratuit) dit s'il pleut et les limites baissent seules : 110 sur " +
                    "autoroute, 100 sur voie rapide, 80 sur route. Seule une position arrondie à ~10 km est envoyée. " +
                    "Un appui sur la puce Pluie/Sec reprend la main jusqu'à la fin du trajet.",
                settings.autoRain,
            ) { on -> onChange { it.copy(autoRain = on) } }
            Toggle(
                "Pluie maintenant",
                "Limites de pluie tout de suite, sans attendre la météo.",
                settings.raining,
            ) { on -> onChange { it.copy(raining = on, rainManual = true) } }
            Toggle(
                "Annonces vocales",
                "Désactivé : seulement les bips, sans voix.",
                settings.voice,
            ) { on -> onChange { it.copy(voice = on) } }
            Toggle(
                "Arrêt automatique",
                "Pour la batterie : Zenith s'arrête seul quand la voiture est garée depuis 10 minutes, quand tu repars à pied " +
                    "ou à l'intérieur sans GPS. Jamais sur autoroute ni pendant un bouchon. Avec ta voiture choisie ci-dessous, " +
                    "il démarre et s'arrête aussi seul avec elle.",
                settings.autoStop,
            ) { on -> onChange { it.copy(autoStop = on) } }
            MotionCard(settings, permissions, onChange, onMotionPermission)
            Card {
                Text("Démarrage automatique en voiture", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                val text = when {
                    !CarLink.supported -> "Nécessite Android 13 ou plus récent."
                    settings.carName != null -> "Zenith démarre quand « ${settings.carName} » se connecte et s'arrête à la déconnexion."
                    else -> "Choisis le Bluetooth de ta voiture : Zenith démarrera seul en montant, et s'arrêtera en descendant."
                }
                Text(text, color = Color.White.copy(alpha = 0.75f), fontSize = 14.sp)
                if (CarLink.supported) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        if (settings.carName != null) "Retirer la voiture" else "Choisir la voiture",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(50))
                            .background(if (settings.carName != null) Color(0xFF2C2C30) else Accent)
                            .clickable(onClick = if (settings.carName != null) onUnpairCar else onPairCar)
                            .padding(horizontal = 16.dp, vertical = 9.dp),
                    )
                }
            }
            Card {
                Text("Musique", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (mediaAllowed) {
                        "Le titre en cours (Spotify, Deezer, YouTube Music…) et ses commandes s'affichent sous les alertes pendant la conduite."
                    } else {
                        "Titre en cours et commandes de Spotify ou de ton lecteur, sous les alertes. Sans compte ni clé. Android appelle " +
                            "cela « accès aux notifications » : Zenith n'y lit que le lecteur de musique. Si l'option est grisée : " +
                            "Infos de l'appli Zenith → ⋮ → Autoriser les paramètres restreints."
                    },
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 14.sp,
                )
                if (!mediaAllowed) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Autoriser",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(Accent).clickable(onClick = onAllowMedia)
                            .padding(horizontal = 16.dp, vertical = 9.dp),
                    )
                }
            }
            if (onShareTrip != null) {
                Card {
                    Text("Dernier trajet", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Fichier GPX de ton dernier trajet, pour signaler une alerte manquée ou fausse : il se rejoue tel quel.",
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = 14.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Partager",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(Accent).clickable(onClick = onShareTrip)
                            .padding(horizontal = 16.dp, vertical = 9.dp),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "Zenith $version\nCartes © OpenFreeMap © OpenStreetMap\nRadars : data.gouv.fr (Licence Ouverte), OpenStreetMap (ODbL), Lufop\n" +
                    "Astuce : appui long sur le logo de l'accueil pour un trajet de démo.",
                color = Dim,
                fontSize = 12.sp,
            )
        }
    }
}

/** Automatic start by activity recognition, with the permission still missing if any. */
@Composable
private fun MotionCard(
    settings: SettingsState,
    permissions: Permissions?,
    onChange: ((SettingsState) -> SettingsState) -> Unit,
    onPermission: () -> Unit,
) {
    val available = permissions?.motionAvailable == true
    val missing = when {
        !available || !settings.motionStart -> null
        permissions?.activity != true -> "Autoriser l'activité physique"
        permissions?.backgroundLocation != true -> "Autoriser la position « Toujours »"
        else -> null
    }
    Card {
        Row(
            Modifier.fillMaxWidth().clickable(enabled = available) { onChange { it.copy(motionStart = !it.motionStart) } },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Démarrage en roulant", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    when {
                        !available -> "Nécessite les services Google Play."
                        missing != null ->
                            "Zenith démarre seul dès que le téléphone sent que tu roules en voiture, même fermé (services Google, " +
                                "presque sans batterie). Il faut « Activité physique », puis la position « Toujours autoriser » : " +
                                "sans elle, Android coupe le GPS d'une conduite démarrée seule."
                        settings.motionStart -> "Zenith démarre seul quand tu roules en voiture, même fermé, et s'arrête une fois garé."
                        else -> "Désactivé : Zenith ne démarre seul qu'avec le Bluetooth de ta voiture."
                    },
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 14.sp,
                )
            }
            Spacer(Modifier.width(14.dp))
            Switch(settings.motionStart && available)
        }
        if (missing != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                missing,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(Accent).clickable(onClick = onPermission)
                    .padding(horizontal = 16.dp, vertical = 9.dp),
            )
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(RoundedCornerShape(20.dp)).background(Surface).padding(18.dp)) {
        content()
    }
}

@Composable
private fun Toggle(title: String, text: String, on: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(RoundedCornerShape(20.dp)).background(Surface)
            .clickable { onToggle(!on) }.padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(text, color = Color.White.copy(alpha = 0.75f), fontSize = 14.sp)
        }
        Spacer(Modifier.width(14.dp))
        Switch(on)
    }
}

/** iOS-like switch, animated. */
@Composable
private fun Switch(on: Boolean) {
    val track by animateColorAsState(if (on) Color(0xFF30D158) else Color(0xFF3A3A3C), label = "track")
    val knob by animateDpAsState(if (on) 20.dp else 0.dp, label = "knob")
    Box(Modifier.size(width = 50.dp, height = 30.dp).clip(RoundedCornerShape(50)).background(track).padding(3.dp)) {
        Box(Modifier.offset(x = knob).size(24.dp).background(Color.White, CircleShape))
    }
}
