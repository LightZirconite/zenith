# Zenith

Application Android minimaliste d'alertes de conduite : vitesse, limitation, radars, tronçons et itinéraires de contrôle. Pas d'itinéraire ni de navigation : une petite carte inclinée pour se situer, et des alertes sonores qui marchent écran éteint, derrière Waze ou Maps.

<p align="center">
  <img src="docs/screenshots/demo.png" width="30%" alt="Radar fixe à 760 m, 85 km/h pour une limite de 70">
  <img src="docs/screenshots/conduite.png" width="30%" alt="Écran de conduite avec la musique et son Canvas">
  <img src="docs/screenshots/pip.png" width="30%" alt="Fenêtre picture-in-picture au-dessus de Waze">
</p>

## Modules

- `core/` : Kotlin pur, testé sur JVM. Filtre de Kalman, map-matching HMM, horizon routier, moteur d'alertes radars (approche, freinage, passage, tronçon, excès), itinéraires de contrôle, règles françaises de vitesse, lecture/écriture GPX et replay (`DrivingSession`).
- `app/` : application Android (Jetpack Compose).

## Développement

```bash
./gradlew :core:test          # tests unitaires
./gradlew :app:assembleDebug  # APK debug (nécessite le SDK Android)
```

Chaque push sur `main` lance les tests, compile l'APK signé et le publie en release (`.github/workflows/release.yml`). Dernier APK : https://github.com/LightZirconite/zenith/releases/latest/download/zenith.apk

La clé de signature se crée une seule fois avec `scripts/setup-signing.sh` (gardée dans `~/.zenith`, à sauvegarder).

## Données (sans serveur ni base de données)

GitHub Actions fabrique les données, publiées comme fichiers de la release [`data`](https://github.com/LightZirconite/zenith/releases/tag/data). L'app les télécharge et les garde en cache.

- `data.yml` (chaque lundi) : extrait OpenStreetMap France (Geofabrik) → tuiles de limitations `roads_*.json.gz` (routes sans limite : 50 en zone bâtie, 80 hors zone, d'après l'occupation du sol) + radars OSM `osm_radars.json`, puis `radars.json`.
- `radars.yml` (six fois par jour, heures irrégulières) : récolte Lufop sous quota dans `lufop.json.enc` (jamais plus de 150 requêtes sur 24 h glissantes ; base qui grossit à chaque passage, publiée chiffrée avec le secret `LUFOP_DB_KEY`), puis fusion liste officielle data.gouv + carte Sécurité routière (radars récents, longueurs des tronçons, itinéraires de contrôle) + OSM + Lufop → `radars.json`. `official.json` garde la dernière copie valide des sources officielles, au cas où l'une tomberait.

La clé Lufop est le secret de dépôt `LUFOP_API_KEY` (Settings → Secrets and variables → Actions). Sans elle, la récolte Lufop est simplement sautée.
