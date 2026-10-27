# Zenith

Application Android minimaliste d'alertes de conduite : vitesse, limitation, radars et contrôles. Pas de carte, pas d'itinéraire.

## Modules

- `core/` : Kotlin pur, testé sur JVM. Filtre de Kalman, map-matching HMM, moteur d'alertes radars (approche, freinage, passage, tronçon, excès), règles françaises de vitesse, lecture/écriture GPX et replay (`DrivingSession`).
- `app/` : application Android (Jetpack Compose).

## Développement

```bash
./gradlew :core:test          # tests unitaires
./gradlew :app:assembleDebug  # APK debug (nécessite le SDK Android)
```

Chaque push sur `main` lance les tests, compile l'APK signé et le publie en release (`.github/workflows/release.yml`). Dernier APK : https://github.com/LightZirconite/zenith/releases/latest/download/zenith.apk

La clé de signature se crée une seule fois avec `scripts/setup-signing.sh` (gardée dans `~/.zenith`, à sauvegarder).
