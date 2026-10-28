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
- Alertes vocales en français et bips qui s'accélèrent si tu roules trop vite
- Moyenne en direct sur les radars tronçons
- Petite carte pour te situer
- Petite fenêtre par-dessus Waze ou Maps
- Android Auto : le même écran sur la voiture, et les radars par-dessus Waze ou Maps
- Musique à l'écran (Spotify, YouTube, Deezer…), avec le Canvas Spotify
- Démarre seul quand tu roules, s'arrête seul une fois garé
- Mode pluie automatique
- Mises à jour automatiques

## Android Auto

Les alertes passent toujours par la voiture : bips et voix sortent sur ses haut-parleurs, même avec Waze ou Maps à l'écran.

L'écran Zenith sur la voiture (carte, vitesse, limite, alertes, radars par-dessus Waze) n'apparaît que si Zenith est installé depuis le Play Store : Google l'exige pour ce type d'appli, « Sources inconnues » ne suffit pas ([documentation Google](https://developer.android.com/training/cars/testing)). La voie officielle, sans validation de Google, est la piste de **test interne** du Play Store, visible de toi seul :

1. Crée un compte développeur sur [play.google.com/console](https://play.google.com/console) (25 $, une fois).
2. Crée l'appli « Zenith » (nom de paquet `zenith.app`), puis dans **Tester → Test interne**, ajoute ton adresse comme testeur et envoie le fichier `zenith.aab` de la [dernière release](https://github.com/LightZirconite/zenith/releases/latest). Laisse Google gérer la clé de signature.
3. Pour que chaque version parte seule sur le Play Store : dans Google Cloud, crée un compte de service avec une clé JSON, invite-le dans la Play Console (**Utilisateurs et autorisations**, droit de publier en test), puis colle le JSON dans le secret GitHub `PLAY_SERVICE_ACCOUNT_JSON`.
4. Sur le téléphone : désinstalle Zenith, ouvre le lien de test interne et installe-le depuis le Play Store. Dans Android Auto → **Personnaliser le lanceur**, coche Zenith.

Installé depuis le Play Store, Zenith ne se met plus à jour par GitHub : c'est le Play Store qui s'en charge.

## Autorisations

Au premier lancement, Zenith explique chaque autorisation et un seul bouton « Continuer » les ouvre une par une.

- **Position précise** : mesurer ta vitesse et savoir quels radars sont sur ta route. Ta position ne quitte jamais le téléphone. Choisis « Pendant l'utilisation de l'appli ».
- **Notifications** : Android exige une notification pour que Zenith reste actif en fond. Elle affiche ta vitesse et le prochain radar.
- **Installer des applis inconnues** : pour les mises à jour automatiques, l'app ne vient pas du Play Store. Active l'interrupteur puis reviens.
- **Batterie sans restriction** : pour qu'Android ne coupe pas les alertes pendant un long trajet. Accepte la fenêtre d'Android.
- **Activité physique et position « Toujours »** : pour démarrer seul quand tu roules. Autorise l'activité, puis choisis « Toujours autoriser » pour la position.
- **Accès aux notifications** : pour afficher et contrôler ta musique. Zenith n'y lit que le lecteur. Touche « Activer » ; si Android bloque, va dans Infos de l'appli → ⋮ → « Autoriser les paramètres restreints », puis « Activer » à nouveau.
