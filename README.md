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

La CI GitHub Actions (`.github/workflows/build-apk.yml`) lance les tests et publie l'APK debug en artifact.
