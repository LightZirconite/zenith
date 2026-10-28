package zenith.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** Cards and rows: glass a little see-through, the sky of home faint behind. */
private val Surface = Color(0xD916161A)
private val Accent = Color(0xFF0A84FF)
private val Green = Color(0xFF30D158)
private val Dim = Color(0xFF8E8E93)

/** The few choices Zenith offers. Everything else is automatic. */
@Composable
fun SettingsScreen(
    settings: SettingsState,
    version: String,
    onChange: ((SettingsState) -> SettingsState) -> Unit,
    onPairCar: () -> Unit,
    onUnpairCar: (Car) -> Unit,
    onBack: () -> Unit,
    onShareTrip: (() -> Unit)? = null,
    mediaAllowed: Boolean = false,
    onAllowMedia: () -> Unit = {},
    permissions: Permissions? = null,
    onMotionPermission: () -> Unit = {},
    update: UpdateState = UpdateState.Idle,
    onCheckUpdate: () -> Unit = {},
) {
    LargeTitlePage(
        title = "Réglages",
        leading = {
            Text(
                "‹ Retour",
                color = Accent,
                fontSize = 17.sp,
                modifier = Modifier.tap(onClick = onBack).clip(RoundedCornerShape(12.dp)).padding(vertical = 10.dp, horizontal = 4.dp),
            )
        },
    ) {
        // Each block arrives in turn, top to bottom.
        var order = 0
        Spacer(Modifier.height(8.dp))
        SectionTitle("Alertes", Modifier.cascade(order++))
        Toggle(
            "Annonces vocales",
            "Désactivé : seulement les bips, sans voix.",
            settings.voice,
            Modifier.cascade(order++),
        ) { on -> onChange { it.copy(voice = on) } }
        Toggle(
            "Musique en pause pendant la voix",
            "Désactivé (conseillé) : pendant une annonce, Spotify, YouTube ou la radio baissent en douceur puis remontent " +
                "de même. Activé : la musique baisse puis s'arrête le temps de l'annonce. Les bips, eux, ne font que la baisser.",
            settings.pauseMusic,
            Modifier.cascade(order++),
        ) { on -> onChange { it.copy(pauseMusic = on) } }
        Toggle(
            "Jeune permis",
            "Limites probatoires : 110 sur autoroute, 100 sur voie rapide, 80 sur route à 90.",
            settings.probationary,
            Modifier.cascade(order++),
        ) { on -> onChange { it.copy(probationary = on) } }
        Toggle(
            "Pluie automatique",
            "Pendant la conduite, la météo (Open-Meteo, gratuit) dit s'il pleut et les limites baissent seules : 110 sur " +
                "autoroute, 100 sur voie rapide, 80 sur route. Rien à toucher. Seule une position arrondie à ~10 km est envoyée.",
            settings.autoRain,
            Modifier.cascade(order++),
        ) { on -> onChange { it.copy(autoRain = on) } }
        SectionTitle("En voiture", Modifier.cascade(order++))
        MotionCard(settings, permissions, onChange, onMotionPermission, Modifier.cascade(order++))
        CarsCard(settings.cars, onPairCar, onUnpairCar, Modifier.cascade(order++))
        Toggle(
            "Arrêt automatique",
            "Pour la batterie : Zenith s'arrête seul quand la voiture est garée depuis 10 minutes, quand tu repars à pied " +
                "ou à l'intérieur sans GPS. Jamais sur autoroute ni pendant un bouchon. Une conduite lancée par l'une des " +
                "voitures choisies au-dessus s'arrête, elle, avec la voiture.",
            settings.autoStop,
            Modifier.cascade(order++),
        ) { on -> onChange { it.copy(autoStop = on) } }
        SectionTitle("Musique et petite fenêtre", Modifier.cascade(order++))
        Card(Modifier.cascade(order++)) {
            Text("Musique", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                if (mediaAllowed) {
                    "Le titre en cours (Spotify, YouTube, Deezer…), sa progression et ses commandes s'affichent sous les alertes " +
                        "pendant la conduite. Une touche sur le titre ouvre le lecteur."
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
                Pill("Autoriser", onClick = onAllowMedia)
            }
        }
        Toggle(
            "Canvas",
            "La courte vidéo en boucle des titres (celle de Spotify), derrière la carte Musique et dans la petite fenêtre, " +
                "quel que soit ton lecteur : Spotify, Deezer, YouTube Music, Apple Music… (pas pour une vidéo YouTube). " +
                "Services gratuits non officiels (Spicy Lyrics, AuraMusic) : peut cesser de marcher, et tous les titres " +
                "n'en ont pas. Environ 3 Mo par titre, gardés en cache. Sinon, la pochette floutée.",
            settings.spotifyCanvas,
            Modifier.cascade(order++),
        ) { on -> onChange { it.copy(spotifyCanvas = on) } }
        Toggle(
            "Petite fenêtre carrée",
            "Désactivé : en quittant Zenith pendant la conduite, un bandeau compact en largeur, qui laisse voir Waze ou " +
                "Maps. Activé : une fenêtre carrée, plus grande. Un double appui sur la fenêtre l'agrandit aussi un moment.",
            settings.pipLarge,
            Modifier.cascade(order++),
        ) { on -> onChange { it.copy(pipLarge = on) } }
        SectionTitle("Application", Modifier.cascade(order++))
        UpdateCard(version, update, onCheckUpdate, Modifier.cascade(order++))
        if (onShareTrip != null) {
            Card(Modifier.cascade(order++)) {
                Text("Dernier trajet", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Fichier GPX de ton dernier trajet, pour signaler une alerte manquée ou fausse : il se rejoue tel quel.",
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 14.sp,
                )
                Spacer(Modifier.height(12.dp))
                Pill("Partager", onClick = onShareTrip)
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "Zenith $version\nCartes © OpenFreeMap © OpenStreetMap\nRadars : data.gouv.fr (Licence Ouverte), OpenStreetMap (ODbL), Lufop\n" +
                "Astuce : appui long sur le logo de l'accueil pour un trajet de démo.",
            color = Dim,
            fontSize = 12.sp,
            modifier = Modifier.cascade(order++),
        )
    }
}

/**
 * The cars whose Bluetooth starts Zenith: one row each, removable, and a button to add another.
 * Any of them connecting starts the drive; its disconnection ends it.
 */
@Composable
private fun CarsCard(cars: List<Car>, onAdd: () -> Unit, onRemove: (Car) -> Unit, modifier: Modifier = Modifier) {
    Card(modifier.animateContentSize(spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow))) {
        Text("Démarrage automatique en voiture", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            when {
                !CarLink.supported -> "Nécessite Android 13 ou plus récent."
                cars.isEmpty() -> "Choisis le Bluetooth de ta voiture : Zenith démarrera seul en montant, et s'arrêtera en " +
                    "descendant. Plusieurs voitures possibles."
                cars.size == 1 -> "Zenith démarre quand « ${cars[0].name} » se connecte et s'arrête à la déconnexion."
                else -> "Zenith démarre dès que l'une de tes ${cars.size} voitures se connecte, et s'arrête à sa déconnexion."
            },
            color = Color.White.copy(alpha = 0.75f),
            fontSize = 14.sp,
        )
        if (CarLink.supported) {
            for (car in cars) {
                key(car.associationId) { CarRow(car, onRemove) }
            }
            Spacer(Modifier.height(12.dp))
            Pill(if (cars.isEmpty()) "Choisir la voiture" else "Ajouter une voiture", onClick = onAdd)
        }
    }
}

/** A paired car, popping in when just added. */
@Composable
private fun CarRow(car: Car, onRemove: (Car) -> Unit) {
    val shown = remember { MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(
        visibleState = shown,
        enter = fadeIn() + expandVertically(spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)) +
            scaleIn(spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow), initialScale = 0.9f),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp).glass(RoundedCornerShape(14.dp), Color.White.copy(alpha = 0.05f))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).background(Green, CircleShape))
            Spacer(Modifier.width(10.dp))
            Text(
                car.name,
                color = Color.White,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            Pill("Retirer", color = Color(0xFF2C2C30), onClick = { onRemove(car) })
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
    modifier: Modifier = Modifier,
) {
    val available = permissions?.motionAvailable == true
    val missing = when {
        !available || !settings.motionStart -> null
        permissions?.activity != true -> "Autoriser l'activité physique"
        permissions?.backgroundLocation != true -> "Autoriser la position « Toujours »"
        else -> null
    }
    Card(modifier) {
        Row(
            Modifier.fillMaxWidth().tap(enabled = available) { onChange { it.copy(motionStart = !it.motionStart) } },
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
                        else -> "Désactivé : Zenith ne démarre seul qu'avec le Bluetooth de tes voitures."
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
            Pill(missing, onClick = onPermission)
        }
    }
}

/** Where the self-update stands, with a button to look at once instead of waiting for the next check. */
@Composable
private fun UpdateCard(version: String, update: UpdateState, onCheck: () -> Unit, modifier: Modifier = Modifier) {
    Card(modifier) {
        Text("Mises à jour", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            when (update) {
                is UpdateState.UpToDate -> "À jour : version ${update.version}."
                UpdateState.Checking -> "Recherche d'une nouvelle version…"
                is UpdateState.Downloading -> "Téléchargement de la version ${update.version}… ${(update.progress * 100).roundToInt()} %"
                is UpdateState.Ready -> "Version ${update.version} téléchargée, elle s'installe."
                is UpdateState.Installing, is UpdateState.NeedsConfirmation -> "Installation en cours…"
                is UpdateState.Failed -> update.message
                UpdateState.Idle -> "Version $version. Zenith regarde seul s'il y a du nouveau à chaque retour dans l'app, puis toutes les 2 minutes."
            },
            color = Color.White.copy(alpha = 0.75f),
            fontSize = 14.sp,
        )
        Spacer(Modifier.height(12.dp))
        Pill("Rechercher maintenant", onClick = onCheck)
    }
}

/** Small gray heading over a group of settings, iOS-style. */
@Composable
private fun SectionTitle(title: String, modifier: Modifier = Modifier) {
    Text(
        title.uppercase(),
        color = Dim,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.6.sp,
        modifier = modifier.padding(start = 6.dp, top = 14.dp, bottom = 8.dp),
    )
}

@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth().padding(bottom = 12.dp).glass(RoundedCornerShape(20.dp), Surface).padding(18.dp)) {
        content()
    }
}

/** Rounded action button inside a card. */
@Composable
private fun Pill(text: String, color: Color = Accent, onClick: () -> Unit) {
    Text(
        text,
        color = Color.White,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.tap(onClick = onClick).clip(RoundedCornerShape(50)).background(color)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    )
}

@Composable
private fun Toggle(title: String, text: String, on: Boolean, modifier: Modifier = Modifier, onToggle: (Boolean) -> Unit) {
    Row(
        modifier.fillMaxWidth().padding(bottom = 12.dp).tap { onToggle(!on) }
            .glass(RoundedCornerShape(20.dp), Surface).padding(18.dp),
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

/** iOS-like switch, animated: the knob springs across, the track glows green. */
@Composable
private fun Switch(on: Boolean) {
    val track by animateColorAsState(if (on) Green else Color(0xFF3A3A3C), label = "track")
    val knob by animateDpAsState(if (on) 20.dp else 0.dp, spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMediumLow), label = "knob")
    Box(Modifier.size(width = 50.dp, height = 30.dp).clip(RoundedCornerShape(50)).background(track).padding(3.dp)) {
        Box(Modifier.offset(x = knob).size(24.dp).background(Color.White, CircleShape))
    }
}
