# Zenith : guide pour les agents

Lis ce fichier en entier avant de toucher au projet. Le README décrit le projet pour les humains ; ici, ce sont les règles, les décisions déjà prises et les pièges déjà rencontrés.

## Vision (à garder en tête à chaque modification)

- **Un Coyote gratuit, natif et minimaliste.** L'app doit être la plus simple et la plus rapide possible : un écran, une vitesse, une limite, la prochaine alerte. Chaque ajout doit justifier sa place à l'écran.
- **N'annoncer que ce qui est sur la route suivie.** Un radar sur une route parallèle, une bretelle qu'on ne prend pas ou une autre route « devant à vol d'oiseau » ne doit jamais être annoncé ni affiché comme « prochain ». Mieux vaut ne rien afficher qu'une information fausse ; mais un radar réel ne doit jamais être raté (en cas de doute sur la route, on alerte).
- **Des alertes à la Coyote** : sons forts et progressifs (bips qui s'accélèrent à l'approche quand on est trop vite), voix courte en français, ça marche écran éteint et Waze/Maps devant.
- **Le maximum de données gratuites, rassemblées par nos scripts.** La CI récupère tout ce qui est gratuit (liste officielle, OSM, Lufop avec la clé de l'auteur, et toute nouvelle source gratuite) et le fusionne dans de simples fichiers de la release `data`. L'app les télécharge régulièrement. Pas de serveur : la base grossit et se met à jour toute seule, jour après jour.
- **Dépôt public assumé** : la CI GitHub est gratuite et illimitée, il n'y a aucun intérêt à fermer le code.

## Ce qu'est Zenith

Application Android **minimaliste** d'alertes de conduite : vitesse, limitation, radars (positions exactes, façon Coyote), contrôles, tronçons. **Pas d'itinéraire, pas de navigation** : seulement une petite carte inclinée en haut de l'écran de conduite pour se situer (point bleu, route suivie en surbrillance, radars en mini-panneaux), qui se fond dans le fond noir. Elle tourne en fond, en complément de Waze ou Google Maps : un service de premier plan qui alerte par sons et voix même écran éteint.

Usage personnel de l'auteur, pas grand public. Interface et textes en français.

## Décisions prises (ne pas remettre en cause sans demande explicite)

- **Android natif Kotlin uniquement.** Pas d'iOS, pas de PWA, pas de Flutter.
- **Aucun serveur, aucune base de données hébergée.** GitHub Actions fabrique les données, publiées comme fichiers de la release `data`. L'app les télécharge et les met en cache. Supabase, Firebase, Cloudflare D1, VPS : écartés.
- **Dépôt public** (Actions gratuites et illimitées). Rien de secret dans le code : clés en secrets GitHub uniquement.
- **Radars en position exacte**, toutes sources activées par défaut, le plus de données possible sur la France.
- **Android Auto plus tard** (Car App Library). Au-dessus de Waze/Maps : picture-in-picture (fait), pas de bulle `SYSTEM_ALERT_WINDOW`.
- **Sons originaux synthétisés** (`Tones.kt`), jamais ceux de Coyote (protégés).

## Architecture

```
core/      Kotlin pur (JVM), testé : géo, Kalman, map-matching HMM, moteur d'alertes, règles FR,
           lecture des packs, GPX, DrivingSession (pipeline déterministe : replay = direct).
app/       Android Compose : DriveService (GPS 1 Hz, premier plan), DriveData (téléchargement/cache),
           DriveScreen, DriveMap (MapLibre), Onboarding (autorisations expliquées), Speaker (TTS FR),
           Tones, TripRecorder (GPX), Updater (auto-mise à jour).
           RoadHorizon : radars accrochés aux routes, chemin le plus probable, routes atteignables.
pipeline/  Python : build_roads.py (tuiles de limitations), build_radars.py (osm | merge | check),
           harvest_lufop.py (base Lufop qui grossit chaque jour), lufop_db.sh (base chiffrée).
.github/workflows/
  release.yml    push sur main touchant l'app → tests + APK signé + release vX (latest)
  build-apk.yml  PR → tests + APK debug
  data.yml       lundi → extrait OSM France → tuiles + osm_radars.json + radars.json
  radars.yml     chaque jour → récolte Lufop (≤150 requêtes) + fusion → radars.json, lufop.json.enc
```

### Release `data` (la « base de données »)

| Fichier | Produit par | Contenu |
|---|---|---|
| `radars.json` | data.yml, radars.yml | Fusion officiel (data.gouv) + OSM + Lufop, lue par l'app |
| `osm_radars.json` | data.yml | Radars et relations `enforcement` OSM (sens, tronçons) |
| `lufop.json.enc` | radars.yml | Notre base Lufop **chiffrée** (AES-256, secret `LUFOP_DB_KEY`) : zones du quadrillage + radars vus (`first`, `seen`, `missing`) |
| `roads_<i>_<j>.json.gz` | data.yml | Tuiles 0,5° (`i = floor(lat*2)`, `j = floor(lon*2)`) de segments routiers avec limitation |
| `manifest.json` | data.yml | Liste et tailles des tuiles |

La release `data` est **prerelease et jamais latest** : sinon `releases/latest/download/zenith.apk` casse.

Format d'un segment de tuile : `[id, from, to, oneway, fwd, bwd, src, classe, nom, [lat, lon, ...]]` avec `src` 0 = explicite, 1 = implicite (`FR:urban`…), 2 = déduit de la classe. Les indices de classe sont dans `pipeline/build_roads.py` (`CLASSES`) et `core/DataPacks.kt` : **les deux listes doivent rester identiques.**

## Commandes

```bash
./gradlew :core:test                       # tests JVM (fonctionne en local avec un JDK 17+)
./gradlew :app:assembleDebug               # nécessite le SDK Android (voir plus bas)
./gradlew :core:replay --args="trajet.gpx radars.json tuiles/"   # rejoue un trajet partagé depuis l'app
python pipeline/build_roads.py extrait.osm.pbf out/tiles
python pipeline/build_radars.py osm extrait.osm.pbf out/osm_radars.json
python pipeline/build_radars.py merge out/osm_radars.json out/lufop.json out/radars.json   # lufop.json peut être "-"
LUFOP_API_KEY=... python pipeline/harvest_lufop.py out/lufop.json 150
GH_REPO=LightZirconite/zenith LUFOP_DB_KEY=$(cat ~/.zenith/lufop-db.key) pipeline/lufop_db.sh fetch out/lufop.json
```

Tester le pipeline en local sur un petit extrait : `https://download.geofabrik.de/europe/france/corse-latest.osm.pbf` (35 Mo, environ 20 s). `pip install osmium` (pyosmium) n'est nécessaire que pour `build_roads.py` et l'étape `osm`.

## Environnement de l'auteur

- Pas de JDK ni de SDK Android installés sur le système. L'auteur a choisi la **CI comme référence de compilation** pour l'APK : ne lui demande pas d'installer quoi que ce soit, et **n'accepte jamais les licences du SDK Android à sa place**.
- Pour `:core:test` en local, un JDK portable (Temurin 21) dans un dossier temporaire suffit.
- L'auteur installe l'app depuis son téléphone via https://github.com/LightZirconite/zenith/releases/latest/download/zenith.apk. L'app se met ensuite à jour seule (`Updater.kt`).
- Clé de signature : `~/.zenith/` (keystore + mot de passe), copiée dans les secrets `ZENITH_*`. Ne jamais la régénérer : chaque APK doit garder la même signature, sinon les mises à jour échouent.
- Clé de la base Lufop : `~/.zenith/lufop-db.key`, copiée dans le secret `LUFOP_DB_KEY`. Ne jamais la régénérer ni la changer sans rechiffrer : sans elle, la base accumulée est perdue.
- Remotes : `origin` = GitHub, `gitea` = miroir perso de l'auteur.

## Conventions

- Messages de commit en français, Conventional Commits (`feat:`, `fix:`, `refactor:`, `docs:`…), titre court, puis le détail en puces.
- **Aucune ligne `Co-Authored-By` ni aucune signature ou mention d'IA** dans les commits, les PR ou le code. L'auteur des commits est `LightZirconite <123307773+LightZirconite@users.noreply.github.com>` (`git config user.name` / `user.email` dans le dépôt).
- Code et commentaires de code en anglais ; tout texte visible par l'utilisateur en français.
- Style Kotlin : celui du code existant (data classes, `object` pour les utilitaires purs, KDoc courte au-dessus de chaque classe).
- Toute logique testable va dans `core` avec des tests ; `app` reste une couche mince.
- `versionCode` = numéro d'exécution de `release.yml`. Ne pas le coder en dur.

## App : carte, autorisations, mises à jour

- **Carte** (`DriveMap.kt`) : MapLibre Android, tuiles vectorielles OpenFreeMap (gratuites, sans clé), style `dark` retouché (fond noir pour le fondu). Caméra détaillée ci-dessous. Toute erreur de carte laisse une boîte noire : la carte ne doit jamais faire planter l'app (une app qui plante au démarrage ne peut plus se mettre à jour). Attribution OSM/OpenFreeMap affichée en petit, obligatoire.
- **Caméra de la carte** : voiture à 45 % de la hauteur de la carte (le compteur couvre la partie fondue en dessous), vue assez haute (40° environ) pour voir loin devant ; dézoome avec la vitesse et, au besoin, pour cadrer le prochain radar de la route (jusqu'à 4 km), zoom lissé. On peut glisser/pincer la carte pour regarder ailleurs : elle se recadre seule au bout de 8 s. Flèche de position posée à plat sur la route.
- **Attribution OpenFreeMap / OpenStreetMap** : sur l'écran d'accueil (pas sur la carte, à la demande de l'auteur). Ne pas la supprimer.
- **Picture-in-picture** (`PipScreen.kt`) : pendant une conduite, quitter Zenith (pour Waze, Maps…) l'ouvre en petite fenêtre carrée, Zenith en miniature : carte en fond sur toute la fenêtre (assombrie, plus sombre vers le bas), pastille du prochain radar en haut, grosse vitesse et panneau de limite en bas, cadre rouge qui pulse si trop vite près d'un radar. Tailles proportionnelles à la fenêtre. La carte ne suit que start/stop du cycle de vie : en PiP l'activité est en pause mais visible, une MapView en pause se figerait. Auto sur Android 12+, `onUserLeaveHint` avant. Raccourcis « Ouvrir Waze / Maps » pendant la conduite (`<queries>` dans le manifeste).
- **Notification** : vitesse et limite en titre, prochain radar en texte ; sur Android 16, puce de vitesse dans la barre d'état (`setRequestPromotedOngoing`, `POST_PROMOTED_NOTIFICATIONS`, ne pas la coloriser sinon Android refuse la promotion).
- **Écran de lancement** : thème `Theme.Zenith` noir, logo sur fond sombre (Android 12+), logo qui respire sur l'écran d'accueil.
- **Démo cachée** : appui long sur le logo de l'écran d'accueil → `DriveService.startDemo`. `core/DemoDrive.kt` rejoue environ 2 min sur la vraie D 107 (tuile de Corse `core/src/main/resources/demo/roads_85_19.json`, embarquée dans l'APK) : radar 1 passé trop vite, radar 2 à la limite, zone 50 annoncée. Mêmes sons, voix, carte, notification et PiP que la conduite réelle ; ni GPS, ni GPX, ni téléchargement. Puce « Démo » à l'écran. Les limites absentes de la D 107 sont complétées pour la démo seulement.
- **Réglages** (`SettingsScreen.kt`, `UserSettings.kt` ; ne pas nommer l'objet `Settings`, conflit avec `android.provider.Settings`) : jeune permis, pluie (aussi en puce pendant la conduite, non conservée d'un trajet à l'autre), voix ou bips seuls, voiture pour le démarrage auto, partage du dernier trajet GPX (FileProvider). Le profil est appliqué en direct à `DrivingSession.profile`.
- **Démarrage auto en voiture** (`CarLink.kt`, Android 13+) : la voiture est choisie par l'écran compagnon d'Android (`CompanionDeviceManager`, sans autorisation Bluetooth), puis `CarPresenceService` reçoit connexion et déconnexion. Si Android refuse le service de premier plan depuis l'arrière-plan, une notification « Voiture connectée · touche pour démarrer » prend le relais. À la déconnexion, seule une conduite démarrée par la voiture s'arrête.
- **Tunnels** : sans fix depuis 2,5 s, `DrivingSession.coast` avance la voiture le long de la route suivie à sa dernière vitesse (60 s max), puce « Tunnel · position estimée ».
- **Trajets GPX** : les 10 derniers sont gardés, les plus vieux supprimés.
- **APK arm64-v8a seulement** (`abiFilters`) : MapLibre embarque du code natif, l'APK universel serait 3 fois plus gros.
- **Premier lancement** (`Onboarding.kt`) : chaque autorisation est expliquée avant la fenêtre d'Android (position obligatoire ; notifications, installation d'applications inconnues et batterie sans restriction conseillées). Après deux refus, Android n'affiche plus sa fenêtre : le bouton ouvre alors les réglages.
- **Mises à jour** (`Updater.kt`) : vérification à chaque retour au premier plan (30 min minimum entre deux). À l'arrêt, installation dès le téléchargement fini. La demande de confirmation d'Android passe par l'état (`NeedsConfirmation`) et n'est ouverte que quand l'activité est au premier plan : lancée depuis le `BroadcastReceiver` en arrière-plan, Android la bloquait et la mise à jour restait figée. Après une première auto-mise à jour, Zenith devient l'installateur de l'app et Android 12+ installe les suivantes sans confirmation (`USER_ACTION_NOT_REQUIRED`). L'APK téléchargé est supprimé après l'installation.

## Horizon électronique (`core/RoadHorizon.kt`)

- `RadarSnapper` rattache chaque radar aux chaussées à moins de 30 m. Toutes celles à moins de 10 m de la plus proche sont gardées (radar entre deux chaussées : alerte des deux côtés). Sans sens mesuré, une route à sens unique donne le sens.
- `mostProbablePath` suit la route (même nom/`ref`, puis même classe, puis virage nettement le plus droit) et **s'arrête au premier embranchement ambigu**. Le « prochain radar » et la prochaine limite ne viennent que de ce chemin, distance par la route.
- `reachable` : Dijkstra sur toutes les branches jusqu'à 3,2 km, portée constante (sinon un radar suivi sortirait de la portée en ralentissant et serait annoncé deux fois). Un radar hors du chemin probable n'alerte qu'à 500 m (`branchAlertM`) : la bretelle n'alerte pas toute l'autoroute, mais prévient avant la sortie.
- Repli sûr : radar à plus de 30 m de toute route, ou pas de tuile chargée → ancienne logique du cône devant la voiture (le « prochain » est alors limité à 3 km).

## Pièges déjà rencontrés

- **« Prochain radar à 14 km » sur une autre route** : l'ancien moteur prenait le radar le plus proche à vol d'oiseau dans un cône de ±35° jusqu'à 15 km (à 14 km, le cône fait 16 km de large). Corrigé par l'horizon : ne jamais revenir à une recherche en ligne droite quand une route est reconnue.
- **« Limite 80 » répété** quand la vitesse oscille autour de limite + 3 : l'annonce attend 30 s, ou 10 s sous la limite.
- **Date des mobiles Lufop** : `update` doit passer dans `radars.json` (`at`, secondes epoch, heure de Paris) ; sans date, un mobile vieux d'une semaine serait annoncé comme en direct. `RadarPack` ne garde la date que pour les types mobiles.
- **Quota Lufop partagé** : la clé sert à la CI (150/jour) ; des requêtes depuis le téléphone (mobiles en direct) prendraient sur les mêmes 200/jour.
- **Android 12+** interdit de lancer un service de premier plan `location` depuis l'arrière-plan (démarrage auto au Bluetooth) : passer par `CompanionDeviceManager`.
- **Maven Central en 429** derrière certains proxys (conteneurs cloud) : un init script local `~/.gradle/init.d/` qui met en premier le miroir `https://maven-central.storage-download.googleapis.com/maven2/` suffit. Ne pas l'ajouter au dépôt.

- **AGP 9** : Kotlin intégré, ne **pas** appliquer `org.jetbrains.kotlin.android` ; `kotlin { compilerOptions { } }` au lieu de `kotlinOptions`.
- **compileSdk 37** obligatoire avec les AndroidX actuels (targetSdk 36).
- `x in concurrentHashMap` appelle `containsValue` : utiliser `containsKey` (le compilateur bloque, heureusement).
- **Overpass est trop instable** pour la CI (502/504 sur tous les miroirs, plus de 40 s par petite tuile) et exige un User-Agent. On lit l'extrait OSM Geofabrik à la place.
- **Waze live-map** (`/live-map/api/georss`) répond 403 sans navigateur : non branché.
- **Doc Lufop** (api.lufop.net) derrière une vérification anti-bot Cloudflare : ne pas la contourner (ni faux User-Agent, ni résolution de défi).
- **Map-matching aux bretelles** : sans hystérésis, la limite affichée clignote (130 → 70) à chaque sortie d'autoroute. L'hystérésis suit aussi la continuation du segment courant, avec une pénalité de changement de classe, et elle est désactivée pendant les 5 premiers points.
- **Vitesse à l'arrêt** : le GPS donne 1 à 6 km/h de bruit. `SpeedStabilizer` affiche 0 sous 5 km/h (sortie sous 2,5), n'affiche rien tant que la précision dépasse 30 m, et privilégie la vitesse Doppler.
- Tests de géométrie : toujours vérifier sur **plusieurs graines de bruit** (voir `MapMatcherTest`), une seule graine cache les vrais problèmes.

## API Lufop (format relevé dans un client open source, somansch/lufop_radar)

- `GET https://api.lufop.net/api?key=K&format=json&pays=FR&nbr=200&q=LAT,LON&m=M`, où `m` = rayon en km × 10.
- Plan gratuit : 200 requêtes/jour, 200 résultats par appel, **10 requêtes/minute**, FR/BE/CH, pas de `since=`.
- Réponse : liste d'objets (`name`, `lat`, `lng`, `vitesse`, `azimut`, `flash`, `update` « AAAA-MM-JJ hh:mm:ss », `voie`, `commune`) ; `null` quand la zone est vide ; un objet `{message}` en cas d'erreur. **Pas de champ `id`** en pratique : `harvest_lufop.py` identifie un radar par `lat,lng` (5 décimales).
- `pays=FR` renvoie aussi les radars espagnols et italiens proches de la frontière (noms « Radar Fixe ES 120 »…). Ils sont gardés.
- Premier passage réel (27/10/2026) : 150 requêtes → 3 135 radars ; quadrillage 413 zones → 425 après découpage des zones saturées.
- Le type se déduit du `name` (« Radar Fixe », « Feu Rouge », « Mobile », « Chantier », « Covoiturage » sans vitesse = à ignorer).
- `azimut` (degrés, rempli pour environ 43 % des radars) et `flash` (`D`, `B`, `F`, ou vide ; probablement dos / les deux / face) ne sont **pas documentés** : on n'en tire pas encore de sens de circulation (un sens faux ferait rater l'alerte). À valider en comparant avec les radars OSM qui ont une relation `enforcement` avant de s'en servir.

## Données : règles

- Toutes les sources sont bienvenues. Chacune garde sa trace dans le champ `src` du radar (`gouv`, `osm`, `lufop`, combinées par `+`).
- Fusion : regroupement à 80 m. La liste officielle fait foi pour la position, le type et la limite ; OSM apporte le sens et les tronçons ; Lufop ajoute ce qui manque et confirme.
- Ne jamais deviner une limitation à l'aveugle : une route sans tag reste sans limite plutôt que d'afficher un 80 faux dans un village. Les limites déduites (`src` 2) s'affichent avec un anneau gris.
- **La base Lufop n'est jamais publiée en clair** (dépôt public : la redistribuer pourrait faire couper la clé). Toujours passer par `pipeline/lufop_db.sh`. Une base présente sur la release doit se relire, sinon le job échoue : jamais de redémarrage silencieux à zéro.
- Le quota Lufop ne doit jamais être dépassé : budget 150/jour dans `radars.yml`, pause de 6,5 s entre deux appels, arrêt à la première erreur.
- La base Lufop est sauvegardée **juste après la récolte**, avant la fusion : le quota du jour est dépensé, rien ne doit le faire perdre.
- `build_radars.py check` refuse de publier un `radars.json` qui perd plus de 10 % des radars ; le résumé du job donne les comptes par source, par type et les noms Lufop.

## Prochaines étapes connues

Fait : horizon électronique, prochaine limite, date des mobiles Lufop, garde-fou CI, carte, PiP, démo, réglages (probatoire, pluie, voix), démarrage auto en voiture, tunnels, feux rouges sans limite, points en jeu, sens OSM forward/backward, rapport d'azimut Lufop, partage GPX.

1. **Zones bâties** (OSM `traffic_sign=city_limit`, `landuse=residential`, `place`) pour déduire 50/80 sur les routes sans tag.
2. **Azimut Lufop** : lire le rapport du job `radars` ; au-dessus de 95 % d'accord (direct ou opposé), s'en servir dans `lufop_items`.
3. **Typage Lufop** : 1 605 radars « fixes » Lufop absents de la liste officielle (frontière, autonomes, leurres ?) ; lire l'histogramme des noms dans le résumé du job `radars` avant de typer plus finement.
4. **Tronçons** : environ 26 fins de tronçon sans début ; reconstruire le début par l'horizon arrière, reprendre un tronçon en cours après redémarrage.
5. Radars mobiles Lufop en direct depuis le téléphone (clé saisie dans des réglages, une requête par ~10 km, quota partagé).
6. Profil moto.
7. Android Auto (Car App Library).
