#!/usr/bin/env python3
"""Grows our own Lufop radar database a little every day, within the free-plan quota.

France is covered by search circles. Each run visits the least recently visited circles
(never-visited first) with at most <budget> requests, 10 per minute. A circle that returns
the 200-result cap is split into four smaller ones, so dense areas end up fully covered.
Radars are merged into the stored database: new ones added, seen ones refreshed, and ones
missing twice in a row from a fully-read circle dropped (removed or moved).

Usage: LUFOP_API_KEY=... harvest_lufop.py <lufop.json> [budget]
"""
import json
import math
import os
import sys
import time
import urllib.parse
import urllib.request
from datetime import datetime, timezone

API = "https://api.lufop.net/api"
MAX_RESULTS = 200
MIN_INTERVAL_S = 6.5  # free plan: 10 requests / minute
# Metropolitan France + Corsica, 60 km grid, 45 km circles (covers each square).
LAT_MIN, LAT_MAX, LON_MIN, LON_MAX = 41.3, 51.2, -5.3, 9.7
STEP_KM = 60.0
RADIUS_KM = 45.0
MIN_RADIUS_KM = 2.0
USER_AGENT = "Zenith/0.1 (https://github.com/LightZirconite/zenith)"


def distance_km(lat1, lon1, lat2, lon2):
    p1, p2 = math.radians(lat1), math.radians(lat2)
    h = math.sin((p2 - p1) / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(math.radians(lon2 - lon1) / 2) ** 2
    return 2 * 6371.0088 * math.asin(math.sqrt(h))


def initial_cells():
    cells = []
    lat = LAT_MIN
    while lat < LAT_MAX + STEP_KM / 111.32:
        dlon = STEP_KM / (111.32 * math.cos(math.radians(lat)))
        lon = LON_MIN
        while lon < LON_MAX + dlon:
            cells.append({"lat": round(lat, 4), "lon": round(lon, 4), "r": RADIUS_KM, "last": 0})
            lon += dlon
        lat += STEP_KM / 111.32
    return cells


def split(cell):
    """Four children whose circles together cover the whole parent circle."""
    r = cell["r"] * 0.72  # centres at ±R/2: each child must reach R/2·√2 ≈ 0.707 R
    d = cell["r"] / 2
    dlat = d / 111.32
    dlon = d / (111.32 * math.cos(math.radians(cell["lat"])))
    return [
        {"lat": round(cell["lat"] + sy * dlat, 5), "lon": round(cell["lon"] + sx * dlon, 5), "r": max(r, MIN_RADIUS_KM), "last": 0}
        for sy in (-1, 1) for sx in (-1, 1)
    ]


def query(key, cell):
    params = {
        "key": key, "format": "json", "pays": "FR", "nbr": str(MAX_RESULTS),
        "q": f"{cell['lat']},{cell['lon']}", "m": str(max(1, math.ceil(cell["r"] * 10))),
    }
    req = urllib.request.Request(f"{API}?{urllib.parse.urlencode(params)}", headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=60) as r:
        data = json.loads(r.read() or b"null")
    if data is None:
        return []
    if not isinstance(data, list):
        raise RuntimeError(f"Lufop error: {data.get('message') if isinstance(data, dict) else data}")
    return data


def radar_id(r):
    if r.get("id") not in (None, ""):
        return str(r["id"])
    return f"{float(r['lat']):.5f},{float(r['lng']):.5f}"


def main():
    path = sys.argv[1]
    budget = int(sys.argv[2]) if len(sys.argv) > 2 else 150
    key = os.environ.get("LUFOP_API_KEY")
    if not key:
        print("LUFOP_API_KEY absent: récolte Lufop ignorée")
        return
    db = json.load(open(path)) if os.path.exists(path) else {"v": 1, "cells": initial_cells(), "radars": {}}
    now = int(time.time())
    used = added = dropped = 0
    last_call = 0.0
    while used < budget:
        cell = min(db["cells"], key=lambda c: c["last"])
        if cell["last"] > now - 3600:
            break  # everything visited within the hour
        wait = MIN_INTERVAL_S - (time.monotonic() - last_call)
        if wait > 0:
            time.sleep(wait)
        last_call = time.monotonic()
        try:
            results = query(key, cell)
        except Exception as e:  # noqa: BLE001 - quota or network: stop for today, keep what we have
            print(f"arrêt après {used} requêtes : {e}")
            break
        used += 1
        cell["last"] = now
        cell["count"] = len(results)
        for r in results:
            try:
                rid = radar_id(r)
                entry = {k: r.get(k) for k in ("name", "lat", "lng", "vitesse", "azimut", "flash", "update", "voie", "commune")}
            except (KeyError, ValueError, TypeError):
                continue
            entry["seen"] = now
            entry["missing"] = 0
            if rid not in db["radars"]:
                added += 1
                entry["first"] = now
            else:
                entry["first"] = db["radars"][rid].get("first", now)
            db["radars"][rid] = entry
        if len(results) >= MAX_RESULTS and cell["r"] > MIN_RADIUS_KM:
            db["cells"].remove(cell)
            db["cells"].extend(split(cell))
            continue
        # Fully read circle: radars we know inside it but not returned are probably gone.
        returned = {radar_id(r) for r in results if "lat" in r and "lng" in r}
        for rid, entry in list(db["radars"].items()):
            if rid in returned:
                continue
            try:
                inside = distance_km(cell["lat"], cell["lon"], float(entry["lat"]), float(entry["lng"])) < cell["r"] * 0.95
            except (TypeError, ValueError):
                continue
            if inside:
                entry["missing"] = entry.get("missing", 0) + 1
                if entry["missing"] >= 2:
                    del db["radars"][rid]
                    dropped += 1
    db["updated"] = datetime.now(timezone.utc).isoformat(timespec="seconds")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(db, f, separators=(",", ":"), ensure_ascii=False)
    visited = sum(1 for c in db["cells"] if c["last"])
    print(f"{used} requêtes, {added} nouveaux, {dropped} retirés, {len(db['radars'])} radars Lufop, "
          f"{visited}/{len(db['cells'])} zones visitées")


if __name__ == "__main__":
    main()
