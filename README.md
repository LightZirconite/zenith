# Zenith

Alertes radars gratuites pour Android, simples et sans compte. Zenith tourne en fond, derrière Waze ou Google Maps, et te prévient par la voix et par des bips, même écran éteint.

<p align="center">
  <img src="docs/screenshots/demo.png" width="30%" alt="Radar fixe à 760 m, 85 km/h pour une limite de 70">
  <img src="docs/screenshots/conduite.png" width="30%" alt="Écran de conduite avec la musique">
  <img src="docs/screenshots/pip.png" width="30%" alt="Petite fenêtre au-dessus de Waze">
</p>

<p align="center">
  <a href="https://github.com/LightZirconite/zenith/releases/latest/download/zenith.apk"><b>⬇ Télécharger la dernière version</b></a>
</p>

## Installation

Zenith ne vient pas du Play Store : Play Protect peut bloquer l'installation, parce que l'app demande des autorisations sensibles (accès aux notifications pour la musique, position en arrière-plan, installation des mises à jour).

1. Ouvre le Play Store → ta photo de profil → **Play Protect** → ⚙ → désactive **Analyser les applications avec Play Protect**.
2. Télécharge et installe l'APK.
3. Réactive Play Protect.

Ensuite, Zenith se met à jour tout seul.

## Fonctionnalités

- Ta vitesse et la limitation de la route, en grand
- Radars fixes, feux rouges, tronçons et itinéraires de contrôle, uniquement sur ta route
- Alertes vocales en français et bips qui s'accélèrent si tu roules trop vite ; la musique baisse en douceur, sans s'arrêter
- Moyenne en direct sur les radars tronçons
- Petite carte pour te situer, et un décollage dans les étoiles vers la Terre le temps de trouver le GPS
- Petite fenêtre par-dessus Waze ou Maps
- Musique à l'écran (Spotify, YouTube, Deezer…), avec le Canvas Spotify qui prend plus de place à l'arrêt ; glisse pour changer de titre
- Démarre seul quand tu roules ou quand l'une de tes voitures se connecte, s'arrête seul une fois garé
- Mode pluie automatique
- Mises à jour automatiques

## Autorisations

Au premier lancement, Zenith explique chaque autorisation et un seul bouton « Continuer » les ouvre une par une.

- **Position précise** : mesurer ta vitesse et savoir quels radars sont sur ta route. Ta position ne quitte jamais le téléphone. Choisis « Pendant l'utilisation de l'appli ».
- **Notifications** : Android exige une notification pour que Zenith reste actif en fond. Elle affiche ta vitesse et le prochain radar.
- **Installer des applis inconnues** : pour les mises à jour automatiques, l'app ne vient pas du Play Store. Active l'interrupteur puis reviens.
- **Batterie sans restriction** : pour qu'Android ne coupe pas les alertes pendant un long trajet. Accepte la fenêtre d'Android.
- **Activité physique et position « Toujours »** : pour démarrer seul quand tu roules. Autorise l'activité, puis choisis « Toujours autoriser » pour la position.
- **Accès aux notifications** : pour afficher et contrôler ta musique. Zenith n'y lit que le lecteur. Touche « Activer » ; si Android bloque, va dans Infos de l'appli → ⋮ → « Autoriser les paramètres restreints », puis « Activer » à nouveau.
