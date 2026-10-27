# Zenith : guide pour les agents

Lis ce fichier en entier avant de toucher au projet. Le README décrit le projet pour les humains ; ici, ce sont les règles, les décisions déjà prises et les pièges déjà rencontrés.

## Ce qu'est Zenith

Application Android **minimaliste** d'alertes de conduite : vitesse, limitation, radars (positions exactes, façon Coyote), contrôles, tronçons. **Pas de carte, pas d'itinéraire.** Elle tourne en fond, en complément de Waze ou Google Maps : un service de premier plan qui alerte par sons et voix même écran éteint.

Usage personnel de l'auteur, pas grand public. Interface et textes en français.

## Décisions prises (ne pas remettre en cause sans demande explicite)

- **Android natif Kotlin uniquement.** Pas d'iOS, pas de PWA, pas de Flutter.
- **Aucun serveur, aucune base de données hébergée.** GitHub Actions fabrique les données, publiées comme fichiers de la release `data`. L'app les télécharge et les met en cache. Supabase, Firebase, Cloudflare D1, VPS : écartés.
- **Dépôt public** (Actions gratuites et illimitées). Rien de secret dans le code : clés en secrets GitHub uniquement.
- **Radars en position exacte**, toutes sources activées par défaut, le plus de données possible sur la France.
- **Android Auto plus tard** (Car App Library). Bulle flottante au-dessus de Waze/Maps : prévue, pas faite.
- **Sons originaux synthétisés** (`Tones.kt`), jamais ceux de Coyote (protégés).

## Architecture

```
core/      Kotlin pur (JVM), testé : géo, Kalman, map-matching HMM, moteur d'alertes, règles FR,
           lecture des packs, GPX, DrivingSession (pipeline déterministe : replay = direct).
app/       Android Compose : DriveService (GPS 1 Hz, premier plan), DriveData (téléchargement/cache),
           DriveScreen, Speaker (TTS FR), Tones, TripRecorder (GPX), Updater (auto-mise à jour).
pipeline/  Python : build_roads.py (tuiles de limitations), build_radars.py (osm | merge),
           harvest_lufop.py (base Lufop qui grossit chaque jour).
.github/workflows/
  release.yml    push sur main touchant l'app → tests + APK signé + release vX (latest)
  build-apk.yml  PR → tests + APK debug
  data.yml       lundi → extrait OSM France → tuiles + osm_radars.json + radars.json
  radars.yml     chaque jour → récolte Lufop (≤150 requêtes) + fusion → radars.json, lufop.json
```

### Release `data` (la « base de données »)

| Fichier | Produit par | Contenu |
|---|---|---|
| `radars.json` | data.yml, radars.yml | Fusion officiel (data.gouv) + OSM + Lufop, lue par l'app |
| `osm_radars.json` | data.yml | Radars et relations `enforcement` OSM (sens, tronçons) |
| `lufop.json` | radars.yml | Notre base Lufop : zones du quadrillage + radars vus (`first`, `seen`, `missing`) |
| `roads_<i>_<j>.json.gz` | data.yml | Tuiles 0,5° (`i = floor(lat*2)`, `j = floor(lon*2)`) de segments routiers avec limitation |
| `manifest.json` | data.yml | Liste et tailles des tuiles |

La release `data` est **prerelease et jamais latest** : sinon `releases/latest/download/zenith.apk` casse.

Format d'un segment de tuile : `[id, from, to, oneway, fwd, bwd, src, classe, nom, [lat, lon, ...]]` avec `src` 0 = explicite, 1 = implicite (`FR:urban`…), 2 = déduit de la classe. Les indices de classe sont dans `pipeline/build_roads.py` (`CLASSES`) et `core/DataPacks.kt` : **les deux listes doivent rester identiques.**

## Commandes

```bash
./gradlew :core:test                       # tests JVM (fonctionne en local avec un JDK 17+)
./gradlew :app:assembleDebug               # nécessite le SDK Android (voir plus bas)
python pipeline/build_roads.py extrait.osm.pbf out/tiles
python pipeline/build_radars.py osm extrait.osm.pbf out/osm_radars.json
python pipeline/build_radars.py merge out/osm_radars.json out/lufop.json out/radars.json   # lufop.json peut être "-"
LUFOP_API_KEY=... python pipeline/harvest_lufop.py out/lufop.json 150
```

Tester le pipeline en local sur un petit extrait : `https://download.geofabrik.de/europe/france/corse-latest.osm.pbf` (35 Mo, environ 20 s). `pip install osmium` (pyosmium) n'est nécessaire que pour `build_roads.py` et l'étape `osm`.

## Environnement de l'auteur

- Pas de JDK ni de SDK Android installés sur le système. L'auteur a choisi la **CI comme référence de compilation** pour l'APK : ne lui demande pas d'installer quoi que ce soit, et **n'accepte jamais les licences du SDK Android à sa place**.
- Pour `:core:test` en local, un JDK portable (Temurin 21) dans un dossier temporaire suffit.
- L'auteur installe l'app depuis son téléphone via https://github.com/LightZirconite/zenith/releases/latest/download/zenith.apk. L'app se met ensuite à jour seule (`Updater.kt`).
- Clé de signature : `~/.zenith/` (keystore + mot de passe), copiée dans les secrets `ZENITH_*`. Ne jamais la régénérer : chaque APK doit garder la même signature, sinon les mises à jour échouent.
- Remotes : `origin` = GitHub, `gitea` = miroir perso de l'auteur.

## Conventions

- Messages de commit en français, titre court, puis le détail en puces. Terminer par la ligne Co-Authored-By de l'agent.
- Code et commentaires de code en anglais ; tout texte visible par l'utilisateur en français.
- Style Kotlin : celui du code existant (data classes, `object` pour les utilitaires purs, KDoc courte au-dessus de chaque classe).
- Toute logique testable va dans `core` avec des tests ; `app` reste une couche mince.
- `versionCode` = numéro d'exécution de `release.yml`. Ne pas le coder en dur.

## Pièges déjà rencontrés

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
- Le quota Lufop ne doit jamais être dépassé : budget 150/jour dans `radars.yml`, pause de 6,5 s entre deux appels, arrêt à la première erreur.

## Prochaines étapes connues

1. Détecter les zones bâties (OSM `landuse` / `place` / panneaux d'agglomération) pour déduire 50/80 sur les routes sans tag.
2. Radars mobiles Lufop en direct depuis le téléphone (clé saisie dans des réglages, une requête par ~10 km).
3. Bulle flottante au-dessus de Waze/Maps, démarrage auto au Bluetooth de la voiture, profils (probatoire, moto).
4. Horizon électronique : n'annoncer que les radars sur la route réellement suivie, et le prochain changement de limite.
5. Android Auto (Car App Library).
