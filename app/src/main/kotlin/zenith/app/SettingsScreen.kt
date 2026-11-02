package zenith.app

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Image
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
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
    /** Where the notification access stands on Android 13+: 0 activate, 1 unlock (blocked), 2 activate again. */
    mediaStep: Int = 0,
    spotify: SpotifyAccount = SpotifyAccount(configured = false),
    onSpotifyConnect: () -> Unit = {},
    onSpotifyDisconnect: () -> Unit = {},
    permissions: Permissions? = null,
    onMotionPermission: () -> Unit = {},
    update: UpdateState = UpdateState.Idle,
    onCheckUpdate: () -> Unit = {},
) {
    LargeTitlePage(title = "Réglages", leading = { BackButton(onBack) }) {
        // Each group arrives in turn, top to bottom.
        var order = 0
        Spacer(Modifier.height(4.dp))
        val data by DriveState.data.collectAsState()
        StatusCard(data, update, Modifier.cascade(order++))
        Section(
            "Alertes",
            order++,
            footer = "Les bips et la voix baissent la musique en douceur, sans jamais la couper net. Pluie : la météo vient " +
                "d'Open-Meteo (gratuit) ; seule une position arrondie à ~10 km est envoyée.",
        ) {
            val noFrench by Speaker.frenchMissing.collectAsState()
            ToggleRow(
                "Annonces vocales",
                if (noFrench) {
                    "Pas de voix française sur cet appareil : installe « Services vocaux Google » (ou une voix française) " +
                        "dans les réglages de synthèse vocale. En attendant, seuls les bips sonnent."
                } else {
                    "Sinon, seulement les bips"
                },
                settings.voice,
                icon = Glyphs.voice,
                tint = Color(0xFF5E5CE6),
            ) { on -> onChange { it.copy(voice = on) } }
            Divider()
            ToggleRow("Volume monté en conduite", "À 60 % au départ, rendu à l'arrêt", settings.raiseVolume, icon = Glyphs.volume, tint = Color(0xFFFF9F0A)) { on ->
                onChange { it.copy(raiseVolume = on) }
            }
            Divider()
            ToggleRow("Musique en pause pendant la voix", "Désactivé conseillé : elle baisse seulement", settings.pauseMusic, icon = Glyphs.pause, tint = Color(0xFFFF375F)) { on ->
                onChange { it.copy(pauseMusic = on) }
            }
            Divider()
            ToggleRow("Jeune permis", "110 · 100 · 80 au lieu de 130 · 110 · 90", settings.probationary, icon = Glyphs.learner, tint = Color(0xFF30B0C7)) { on ->
                onChange { it.copy(probationary = on) }
            }
            Divider()
            ToggleRow("Pluie automatique", "Limites de pluie, sans rien toucher", settings.autoRain, icon = Glyphs.rain, tint = Color(0xFF0A84FF)) { on ->
                onChange { it.copy(autoRain = on) }
            }
        }
        Section(
            "En voiture",
            order++,
            footer = "Pas toi au volant ? Touche « Arrêter » : Zenith ne redémarre plus seul avant la fin du trajet " +
                "(désactiver le démarrage automatique est déconseillé). Démarrage en roulant : il faut « Activité physique », " +
                "puis la position « Toujours autoriser », sinon Android coupe le GPS d'une conduite démarrée seule. " +
                "L'arrêt automatique ne joue jamais sur autoroute ni dans un bouchon ; une conduite lancée par une voiture " +
                "s'arrête avec elle.",
        ) {
            ToggleRow(
                "Démarrage automatique",
                if (settings.autoStart) "En roulant ou quand ta voiture se connecte" else "Seulement quand tu touches « Démarrer »",
                settings.autoStart,
                icon = Glyphs.auto,
                tint = Color(0xFF32D74B),
            ) { on -> onChange { it.copy(autoStart = on) } }
            if (settings.autoStart) {
                Divider()
                MotionRows(settings, permissions, onChange, onMotionPermission)
                Divider()
                CarRows(settings.cars, onPairCar, onUnpairCar)
            }
            Divider()
            ToggleRow("Arrêt automatique", "Garé 10 min, à pied ou sans GPS", settings.autoStop, icon = Glyphs.park, tint = Color(0xFF8E8E93)) { on ->
                onChange { it.copy(autoStop = on) }
            }
        }
        Section(
            "Musique et petite fenêtre",
            order++,
            footer = "Musique : Android appelle cela « accès aux notifications » ; Zenith n'y lit que le lecteur. Option grisée : " +
                "Infos de l'appli Zenith → ⋮ → Autoriser les paramètres restreints. Avec Spotify, le compte suffit aussi (Spotify " +
                "n'accepte que les comptes ajoutés par l'auteur, 5 au plus). Canvas : services gratuits non officiels (Spicy Lyrics, " +
                "AuraMusic), environ 3 Mo par titre gardés en cache, sinon la pochette floutée. Un double appui sur la petite " +
                "fenêtre l'agrandit un moment.",
        ) {
            InfoRow(
                "Musique",
                if (mediaAllowed) "Titre, Canvas et commandes de ton lecteur" else "Conseillé : Deezer, YouTube Music, Apple Music…",
                done = mediaAllowed,
                icon = Glyphs.music,
                tint = Color(0xFFFF2D55),
            )
            if (!mediaAllowed) {
                Divider()
                ActionRow(
                    if (Build.VERSION.SDK_INT >= 33 && mediaStep == 1) "Débloquer (paramètres restreints)" else "Autoriser l'accès",
                    sound = UiSound.Kind.OPEN,
                    onClick = onAllowMedia,
                )
            }
            Divider()
            SpotifyRows(spotify, mediaAllowed, onSpotifyConnect, onSpotifyDisconnect)
            Divider()
            ToggleRow("Canvas", "La vidéo en boucle des titres, derrière la carte", settings.spotifyCanvas, icon = Glyphs.canvas, tint = Color(0xFFBF5AF2)) { on ->
                onChange { it.copy(spotifyCanvas = on) }
            }
            Divider()
            ToggleRow("Petite fenêtre carrée", "Sinon un bandeau compact, qui laisse voir Waze", settings.pipLarge, icon = Glyphs.pip, tint = Color(0xFFFF9F0A)) { on ->
                onChange { it.copy(pipLarge = on) }
            }
        }
        Section("Application", order++) {
            ToggleRow("Sons de l'interface", "Un son doux sous le doigt, au volume des médias", settings.uiSounds, icon = Glyphs.sound, tint = Color(0xFFAF52DE)) { on ->
                onChange { it.copy(uiSounds = on) }
            }
            Divider()
            InfoRow(
                "Mises à jour",
                when (update) {
                    is UpdateState.UpToDate -> "À jour : version ${update.version}"
                    UpdateState.Checking -> "Recherche d'une nouvelle version…"
                    is UpdateState.Downloading -> "Téléchargement de la version ${update.version}… ${(update.progress * 100).roundToInt()} %"
                    is UpdateState.Ready -> "Version ${update.version} téléchargée, elle s'installe"
                    is UpdateState.Installing, is UpdateState.NeedsConfirmation -> "Installation en cours…"
                    is UpdateState.Failed -> update.message
                    UpdateState.Idle -> "Version $version, vérifiée à chaque ouverture"
                },
                done = update is UpdateState.UpToDate,
                icon = Glyphs.update,
                tint = Color(0xFF0A84FF),
            )
            Divider()
            ActionRow("Rechercher maintenant", onClick = onCheckUpdate)
            if (onShareTrip != null) {
                Divider()
                InfoRow("Dernier trajet", "Fichier GPX, pour signaler une alerte manquée", icon = Glyphs.trip, tint = Color(0xFF8E8E93))
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

private val Months = listOf(
    "janvier", "février", "mars", "avril", "mai", "juin", "juillet", "août", "septembre", "octobre", "novembre", "décembre",
)

/** At the top: Zenith, how many radars and from when, and whether the app is up to date. */
@Composable
private fun StatusCard(data: DataStatus, update: UpdateState, modifier: Modifier) {
    val base = data.radarsUpdatedMs?.let { ms ->
        val c = java.util.Calendar.getInstance().apply { timeInMillis = ms }
        "base du ${c.get(java.util.Calendar.DAY_OF_MONTH)} ${Months[c.get(java.util.Calendar.MONTH)]}, " +
            "%02d:%02d".format(c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE))
    }
    val line = when {
        data.radarCount == 0 -> "Radars chargés au démarrage"
        base == null -> "${"%,d".format(data.radarCount).replace(',', ' ')} radars"
        else -> "${"%,d".format(data.radarCount).replace(',', ' ')} radars · $base"
    }
    val pill = when (update) {
        is UpdateState.UpToDate -> "À jour" to Green
        is UpdateState.Downloading, is UpdateState.Ready, is UpdateState.Installing, is UpdateState.NeedsConfirmation -> "Mise à jour" to Accent
        else -> null
    }
    Row(
        modifier.padding(top = 14.dp).fillMaxWidth().glass(RoundedCornerShape(24.dp), GroupSurface).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(painterResource(R.drawable.ic_logo_foreground), contentDescription = null, modifier = Modifier.size(54.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text("Zenith", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(line, color = Secondary, fontSize = 13.sp, maxLines = 2)
        }
        pill?.let { (text, color) ->
            Spacer(Modifier.width(8.dp))
            Text(
                text,
                color = color,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.background(color.copy(alpha = 0.16f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
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

/** A gray heading over its group, then the group, then its [footer] in gray, arriving in turn ([index]). */
@Composable
private fun Section(title: String, index: Int, footer: String? = null, content: @Composable ColumnScope.() -> Unit) {
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
        footer?.let {
            Text(it, color = Dim, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 8.dp))
        }
    }
}

/** The hairline between two rows of a group, inset like iOS. */
@Composable
private fun Divider() {
    Box(Modifier.padding(start = RowTextStart).fillMaxWidth().height(0.5.dp).background(Hairline))
}

/** Where a row's words start: after its coloured tile. Hairlines and actions line up there. */
private val RowTextStart = 60.dp

/** A row's coloured tile, or the room for one. */
@Composable
private fun RowIcon(icon: ImageVector?, tint: Color) {
    if (icon != null) GlyphTile(icon, tint) else Spacer(Modifier.size(30.dp))
    Spacer(Modifier.width(12.dp))
}

/** A setting: its tile, its name, what it does in a line, and the switch; the whole row toggles it. */
@Composable
private fun ToggleRow(
    title: String,
    text: String,
    on: Boolean,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    tint: Color = Accent,
    onToggle: (Boolean) -> Unit,
) {
    val press = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth()
            .tap(enabled = enabled, sound = if (on) UiSound.Kind.OFF else UiSound.Kind.ON, lift = 1f, interaction = press) { onToggle(!on) }
            .padding(horizontal = 18.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowIcon(icon, tint)
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 17.sp)
            Spacer(Modifier.height(2.dp))
            Text(text, color = Secondary, fontSize = 13.sp, lineHeight = 17.sp)
        }
        Spacer(Modifier.width(14.dp))
        GlassSwitch(on && enabled, press)
    }
}

/** A row that only says something; [done] puts a green check next to its name. */
@Composable
private fun InfoRow(title: String, text: String, done: Boolean = false, icon: ImageVector? = null, tint: Color = Accent) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        RowIcon(icon, tint)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = Color.White, fontSize = 17.sp, modifier = Modifier.weight(1f, fill = false))
                if (done) {
                    Spacer(Modifier.width(8.dp))
                    Text("✓", color = Green, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(text, color = Secondary, fontSize = 13.sp, lineHeight = 17.sp)
        }
    }
}

/** An action, the iOS way: colored words across the row. */
@Composable
private fun ActionRow(label: String, color: Color = Accent, sound: UiSound.Kind = UiSound.Kind.TAP, onClick: () -> Unit) {
    Text(
        label,
        color = color,
        fontSize = 17.sp,
        modifier = Modifier.fillMaxWidth().tap(sound = sound, lift = 1f, onClick = onClick).padding(start = RowTextStart, end = 18.dp, top = 13.dp, bottom = 13.dp),
    )
}

/**
 * The cars whose Bluetooth starts Zenith: one row each, removable, and a row to add another.
 * Any of them connecting starts the drive; its disconnection ends it.
 */
@Composable
private fun CarRows(cars: List<Car>, onAdd: () -> Unit, onRemove: (Car) -> Unit) {
    InfoRow(
        "Démarrage en voiture",
        when {
            !CarLink.supported -> "Nécessite Android 13 ou plus récent"
            cars.isEmpty() -> "Choisis le Bluetooth de ta voiture"
            cars.size == 1 -> "Démarre et s'arrête avec « ${cars[0].name} »"
            else -> "Démarre et s'arrête avec tes ${cars.size} voitures"
        },
        icon = Glyphs.link,
        tint = Color(0xFF64D2FF),
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
            Row(Modifier.fillMaxWidth().padding(start = RowTextStart, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
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

/**
 * The Spotify account, read through its Web API: the music card for a Spotify user without the
 * notification access. Spotify's own session wins when the access is there.
 */
@Composable
private fun SpotifyRows(account: SpotifyAccount, mediaAllowed: Boolean, onConnect: () -> Unit, onDisconnect: () -> Unit) {
    InfoRow(
        "Compte Spotify",
        when {
            !account.configured -> "Identifiant Spotify absent de cette version"
            account.connected -> "Connecté : ${account.userName ?: "ton compte"}" + when {
                account.premium == false -> " · sans Premium, commandes grisées"
                mediaAllowed -> " · l'appli Spotify passe d'abord"
                else -> ""
            }
            else -> "Sans l'accès aux notifications, la carte le lit"
        },
        done = account.connected,
        icon = Glyphs.music,
        tint = Color(0xFF30D158),
    )
    if (!account.configured) return
    Divider()
    if (account.connected) {
        ActionRow("Se déconnecter", color = Destructive, sound = UiSound.Kind.CLOSE, onClick = onDisconnect)
    } else {
        ActionRow("Se connecter à Spotify", sound = UiSound.Kind.OPEN, onClick = onConnect)
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
            !available -> "Nécessite les services Google Play"
            missing != null -> "Il manque une autorisation"
            settings.motionStart -> "Même fermé, presque sans batterie"
            else -> "Seulement avec le Bluetooth de tes voitures"
        },
        settings.motionStart,
        enabled = available,
        icon = Glyphs.car,
        tint = Color(0xFF34C759),
    ) { on -> onChange { it.copy(motionStart = on) } }
    if (missing != null) {
        Divider()
        ActionRow(missing, sound = UiSound.Kind.OPEN, onClick = onPermission)
    }
}
