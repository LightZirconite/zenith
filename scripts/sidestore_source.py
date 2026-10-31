#!/usr/bin/env python3
"""Writes the SideStore (AltStore format) source of the iOS app, the new build first.

    sidestore_source.py previous.json out.json --version 0.1.N --build N --url IPA_URL --size BYTES \
        --notes "commit message" --repo Owner/zenith

SideStore compares "version" (x.y.z) to the installed one and offers the update; the app compares
"buildVersion". The 5 latest builds are listed (older IPAs are removed from the release by the CI).
"""

import argparse
import datetime
import json
import sys

KEEP = 5
BUNDLE_ID = "io.github.lightzirconite.zenith"


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("previous")
    p.add_argument("out")
    p.add_argument("--version", required=True)
    p.add_argument("--build", required=True)
    p.add_argument("--url", required=True)
    p.add_argument("--size", required=True, type=int)
    p.add_argument("--notes", default="")
    p.add_argument("--repo", required=True)
    a = p.parse_args()

    try:
        with open(a.previous, encoding="utf-8") as f:
            old = json.load(f)
        versions = old["apps"][0]["versions"]
    except (OSError, ValueError, KeyError, IndexError, TypeError):
        versions = []

    raw = f"https://raw.githubusercontent.com/{a.repo}/main"
    now = datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0).isoformat()
    new = {
        "version": a.version,
        "buildVersion": a.build,
        "date": now,
        "localizedDescription": a.notes.strip() or "Nouvelle version",
        "downloadURL": a.url,
        "size": a.size,
        "minOSVersion": "26.0",
    }
    versions = [new] + [v for v in versions if v.get("buildVersion") != a.build][: KEEP - 1]

    source = {
        "name": "Zenith",
        "identifier": f"{BUNDLE_ID}.source",
        "subtitle": "Radars et limitations, façon Coyote",
        "description": "Zenith : alertes radars sur ta route, gratuites, sans compte ni pub. Code ouvert.",
        "iconURL": f"{raw}/ios/Zenith/Assets.xcassets/AppIcon.appiconset/icon-1024.png",
        "website": f"https://github.com/{a.repo}",
        "tintColor": "#FF3B30",
        "apps": [
            {
                "name": "Zenith",
                "bundleIdentifier": BUNDLE_ID,
                "developerName": a.repo.split("/")[0],
                "subtitle": "Radars et limitations sur ta route",
                "localizedDescription": (
                    "Vitesse, limitation, radars en position exacte (sources officielles, OSM, Lufop), "
                    "seulement sur ta route. Bips et voix même écran éteint, Waze ou Maps devant. "
                    "Petite fenêtre, activité en direct, musique (Spotify, Apple Music)."
                ),
                "iconURL": f"{raw}/ios/Zenith/Assets.xcassets/AppIcon.appiconset/icon-1024.png",
                "tintColor": "#FF3B30",
                "category": "utilities",
                "screenshotURLs": [],
                "versions": versions,
                "appPermissions": {
                    "entitlements": [],
                    "privacy": {
                        "NSLocationAlwaysAndWhenInUseUsageDescription": "Alertes écran éteint et démarrage seul en roulant.",
                        "NSLocationWhenInUseUsageDescription": "Vitesse, route suivie, radars et limitations.",
                        "NSMotionUsageDescription": "Démarrage seul quand tu montes en voiture.",
                        "NSAppleMusicUsageDescription": "Titre en cours d'Apple Music sur l'écran de conduite.",
                    },
                },
            }
        ],
        "news": [],
    }
    with open(a.out, "w", encoding="utf-8") as f:
        json.dump(source, f, ensure_ascii=False, indent=2)
    return 0


if __name__ == "__main__":
    sys.exit(main())
