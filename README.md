# Zenith

Application Android minimaliste d'alertes de conduite : vitesse, limitation, radars et contrôles. Pas de carte, pas d'itinéraire.

## Modules

- `core/` : Kotlin pur (géo, règles françaises de vitesse, radar tronçon). Testé sur JVM.
- `app/` : application Android (Jetpack Compose).

## Développement

```bash
./gradlew :core:test          # tests unitaires
./gradlew :app:assembleDebug  # APK debug (nécessite le SDK Android)
```

La CI GitHub Actions (`.github/workflows/build-apk.yml`) lance les tests et publie l'APK debug en artifact.
