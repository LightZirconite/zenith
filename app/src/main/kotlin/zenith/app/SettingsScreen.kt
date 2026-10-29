package zenith.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** Grouped lists the iOS way: glass a little see-through, the sky of home faint behind. */
private val GroupSurface = Color(0xCC1C1C1F)
private val Hairline = Color.White.copy(alpha = 0.1f)
private val Accent = Color(0xFF0A84FF)
private val Destructive = Color(0xFFFF453A)
private val Green = Color(0xFF30D158)
private val Dim = Color(0xFF8E8E93)
private val Secondary = Color(0xFFAEAEB2)

/** The few choices Zenith offers, in iOS groups. Everything else is automatic. */
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
    LargeTitlePage(title = "Réglages", leading = { BackButton(onBack) }) {
        // Each group arrives in turn, top to bottom.
        var order = 0
        Spacer(Modifier.height(4.dp))
        Section("Alertes", order++) {
            ToggleRow("Annonces vocales", "Désactivé : seulement les bips, sans voix.", settings.voice) { on ->
                onChange { it.copy(voice = on) }
            }
            Divider()
            ToggleRow(
                "Musique en pause pendant la voix",
                "Désactivé (conseillé) : pendant une annonce, Spotify, YouTube ou la radio baissent en douceur puis remontent " +
                    "de même. Activé : la musique baisse puis s'arrête le temps de l'annonce. Les bips, eux, ne font que la baisser.",
                settings.pauseMusic,
            ) { on -> onChange { it.copy(pauseMusic = on) } }
            Divider()
            ToggleRow(
                "Jeune permis",
                "Limites probatoires : 110 sur autoroute, 100 sur voie rapide, 80 sur route à 90.",
                settings.probationary,
            ) { on -> onChange { it.copy(probationary = on) } }
            Divider()
            ToggleRow(
                "Pluie automatique",
                "Pendant la conduite, la météo (Open-Meteo, gratuit) dit s'il pleut et les limites baissent seules : 110 sur " +
                    "autoroute, 100 sur voie rapide, 80 sur route. Rien à toucher. Seule une position arrondie à ~10 km est envoyée.",
                settings.autoRain,
            ) { on -> onChange { it.copy(autoRain = on) } }
        }
        Section("En voiture", order++) {
            MotionRows(settings, permissions, onChange, onMotionPermission)
            Divider()
            CarRows(settings.cars, onPairCar, onUnpairCar)
            Divider()
            ToggleRow(
                "Arrêt automatique",
                "Pour la batterie : Zenith s'arrête seul quand la voiture est garée depuis 10 minutes, quand tu repars à pied " +
                    "ou à l'intérieur sans GPS. Jamais sur autoroute ni pendant un bouchon. Une conduite lancée par l'une des " +
                    "voitures choisies au-dessus s'arrête, elle, avec la voiture.",
                settings.autoStop,
            ) { on -> onChange { it.copy(autoStop = on) } }
        }
        Section("Musique et petite fenêtre", order++) {
            InfoRow(
                "Musique",
                if (mediaAllowed) {
                    "Le titre en cours (Spotify, YouTube, Deezer…), sa progression et ses commandes s'affichent sous les alertes " +
                        "pendant la conduite. Une touche sur le titre ouvre le lecteur."
                } else {
                    "Titre en cours et commandes de Spotify ou de ton lecteur, sous les alertes. Sans compte ni clé. Android appelle " +
                        "cela « accès aux notifications » : Zenith n'y lit que le lecteur de musique. Si l'option est grisée : " +
                        "Infos de l'appli Zenith → ⋮ → Autoriser les paramètres restreints."
                },
                done = mediaAllowed,
            )
            if (!mediaAllowed) {
                Divider()
                ActionRow("Autoriser l'accès", sound = UiSound.Kind.OPEN, onClick = onAllowMedia)
            }
            Divider()
            ToggleRow(
                "Canvas",
                "La courte vidéo en boucle des titres (celle de Spotify), derrière la carte Musique et dans la petite fenêtre, " +
                    "quel que soit ton lecteur : Spotify, Deezer, YouTube Music, Apple Music… (pas pour une vidéo YouTube). " +
                    "Services gratuits non officiels (Spicy Lyrics, AuraMusic) : peut cesser de marcher, et tous les titres " +
                    "n'en ont pas. Environ 3 Mo par titre, gardés en cache. Sinon, la pochette floutée.",
                settings.spotifyCanvas,
            ) { on -> onChange { it.copy(spotifyCanvas = on) } }
            Divider()
            ToggleRow(
                "Petite fenêtre carrée",
                "Désactivé : en quittant Zenith pendant la conduite, un bandeau compact en largeur, qui laisse voir Waze ou " +
                    "Maps. Activé : une fenêtre carrée, plus grande. Un double appui sur la fenêtre l'agrandit aussi un moment.",
                settings.pipLarge,
            ) { on -> onChange { it.copy(pipLarge = on) } }
        }
        Section("Application", order++) {
            ToggleRow(
                "Sons de l'interface",
                "Petits sons doux sous le doigt, sur chaque bouton, au volume des médias. Les alertes de conduite ne sont pas concernées.",
                settings.uiSounds,
            ) { on -> onChange { it.copy(uiSounds = on) } }
            Divider()
            InfoRow(
                "Mises à jour",
                when (update) {
                    is UpdateState.UpToDate -> "À jour : version ${update.version}."
                    UpdateState.Checking -> "Recherche d'une nouvelle version…"
                    is UpdateState.Downloading -> "Téléchargement de la version ${update.version}… ${(update.progress * 100).roundToInt()} %"
                    is UpdateState.Ready -> "Version ${update.version} téléchargée, elle s'installe."
                    is UpdateState.Installing, is UpdateState.NeedsConfirmation -> "Installation en cours…"
                    is UpdateState.Failed -> update.message
                    UpdateState.Idle -> "Version $version. Zenith regarde seul s'il y a du nouveau à chaque retour dans l'app, puis toutes les 2 minutes."
                },
                done = update is UpdateState.UpToDate,
            )
            Divider()
            ActionRow("Rechercher maintenant", onClick = onCheckUpdate)
            if (onShareTrip != null) {
                Divider()
                InfoRow(
                    "Dernier trajet",
                    "Fichier GPX de ton dernier trajet, pour signaler une alerte manquée ou fausse : il se rejoue tel quel.",
                )
                Divider()
                ActionRow("Partager le trajet", sound = UiSound.Kind.OPEN, onClick = onShareTrip)
            }
        }
        Spacer(Modifier.height(18.dp))
        Text(
            "Zenith $version\nCartes © OpenFreeMap © OpenStreetMap\nRadars : data.gouv.fr (Licence Ouverte), OpenStreetMap (ODbL), Lufop\n" +
                "Astuce : appui long sur le logo de l'accueil pour un trajet de démo.",
            color = Dim,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            modifier = Modifier.padding(horizontal = 18.dp).cascade(order++),
        )
        Spacer(Modifier.height(12.dp))
    }
}

/** The way back the iOS way: a round glass button with a chevron. */
@Composable
private fun BackButton(onBack: () -> Unit) {
    Box(
        Modifier.size(42.dp).tap(shape = CircleShape, sound = UiSound.Kind.CLOSE, lift = 1.12f, onClick = onBack).glass(CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Chevron(Color.White, Modifier.size(width = 10.dp, height = 17.dp).padding(end = 1.dp))
    }
}

/** A gray heading over its group, then the group, arriving in turn ([index]). */
@Composable
private fun Section(title: String, index: Int, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.cascade(index)) {
        Text(
            title.uppercase(),
            color = Dim,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.5.sp,
            modifier = Modifier.padding(start = 18.dp, top = 22.dp, bottom = 7.dp),
        )
        Column(
            Modifier.fillMaxWidth().glass(RoundedCornerShape(24.dp), GroupSurface)
                .animateContentSize(spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)),
            content = content,
        )
    }
}

/** The hairline between two rows of a group, inset like iOS. */
@Composable
private fun Divider() {
    Box(Modifier.padding(start = 18.dp).fillMaxWidth().height(0.5.dp).background(Hairline))
}

/** A setting: its name, what it does, and the switch; the whole row toggles it. */
@Composable
private fun ToggleRow(title: String, text: String, on: Boolean, enabled: Boolean = true, onToggle: (Boolean) -> Unit) {
    val press = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth()
            .tap(enabled = enabled, sound = if (on) UiSound.Kind.OFF else UiSound.Kind.ON, lift = 1f, interaction = press) { onToggle(!on) }
            .padding(horizontal = 18.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 17.sp)
            Spacer(Modifier.height(3.dp))
            Text(text, color = Secondary, fontSize = 13.sp, lineHeight = 17.sp)
        }
        Spacer(Modifier.width(14.dp))
        GlassSwitch(on && enabled, press)
    }
}

/** A row that only says something; [done] puts a green check next to its name. */
@Composable
private fun InfoRow(title: String, text: String, done: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 13.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Color.White, fontSize = 17.sp, modifier = Modifier.weight(1f, fill = false))
            if (done) {
                Spacer(Modifier.width(8.dp))
                Text("✓", color = Green, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(text, color = Secondary, fontSize = 13.sp, lineHeight = 17.sp)
    }
}

/** An action, the iOS way: colored words across the row. */
@Composable
private fun ActionRow(label: String, color: Color = Accent, sound: UiSound.Kind = UiSound.Kind.TAP, onClick: () -> Unit) {
    Text(
        label,
        color = color,
        fontSize = 17.sp,
        modifier = Modifier.fillMaxWidth().tap(sound = sound, lift = 1f, onClick = onClick).padding(horizontal = 18.dp, vertical = 13.dp),
    )
}

/**
 * The cars whose Bluetooth starts Zenith: one row each, removable, and a row to add another.
 * Any of them connecting starts the drive; its disconnection ends it.
 */
@Composable
private fun CarRows(cars: List<Car>, onAdd: () -> Unit, onRemove: (Car) -> Unit) {
    InfoRow(
        "Démarrage automatique en voiture",
        when {
            !CarLink.supported -> "Nécessite Android 13 ou plus récent."
            cars.isEmpty() -> "Choisis le Bluetooth de ta voiture : Zenith démarrera seul en montant, et s'arrêtera en " +
                "descendant. Plusieurs voitures possibles."
            cars.size == 1 -> "Zenith démarre quand « ${cars[0].name} » se connecte et s'arrête à la déconnexion."
            else -> "Zenith démarre dès que l'une de tes ${cars.size} voitures se connecte, et s'arrête à sa déconnexion."
        },
    )
    if (!CarLink.supported) return
    for (car in cars) {
        key(car.associationId) { CarRow(car, onRemove) }
    }
    Divider()
    ActionRow(if (cars.isEmpty()) "Choisir la voiture" else "Ajouter une voiture", sound = UiSound.Kind.OPEN, onClick = onAdd)
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
        Column {
            Divider()
            Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(Green, CircleShape))
                Spacer(Modifier.width(12.dp))
                Text(car.name, color = Color.White, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(
                    "Retirer",
                    color = Destructive,
                    fontSize = 17.sp,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.tap(shape = RoundedCornerShape(12.dp), sound = UiSound.Kind.CLOSE) { onRemove(car) }
                        .padding(horizontal = 10.dp, vertical = 9.dp),
                )
            }
        }
    }
}

/** Automatic start by activity recognition, with the permission still missing if any. */
@Composable
private fun MotionRows(
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
    ToggleRow(
        "Démarrage en roulant",
        when {
            !available -> "Nécessite les services Google Play."
            missing != null ->
                "Zenith démarre seul dès que le téléphone sent que tu roules en voiture, même fermé (services Google, " +
                    "presque sans batterie). Il faut « Activité physique », puis la position « Toujours autoriser » : " +
                    "sans elle, Android coupe le GPS d'une conduite démarrée seule."
            settings.motionStart -> "Zenith démarre seul quand tu roules en voiture, même fermé, et s'arrête une fois garé."
            else -> "Désactivé : Zenith ne démarre seul qu'avec le Bluetooth de tes voitures."
        },
        settings.motionStart,
        enabled = available,
    ) { on -> onChange { it.copy(motionStart = on) } }
    if (missing != null) {
        Divider()
        ActionRow(missing, sound = UiSound.Kind.OPEN, onClick = onPermission)
    }
}
