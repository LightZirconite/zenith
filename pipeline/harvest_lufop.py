#!/usr/bin/env python3
"""Grows our own Lufop radar database a little every day, within the free-plan quota.

France is covered by search circles. Each run visits the least recently visited circles
(never-visited first). A circle that returns the 200-result cap is split into four smaller
ones, so dense areas end up fully covered. Radars are merged into the stored database: new ones
added, seen ones refreshed, and ones missing twice in a row from a fully-read circle dropped.

Quota: the database keeps the time of every call of the last 24 hours, and the calls of any
24-hour window never exceed <budget> (so neither does any calendar day, whatever the number of
runs). The workflow runs several times a day at irregular times; each run spends a random share
of what is left, with irregular pauses (never under 6.5 s: 10 requests / minute).

France first, then abroad (the author's plan: the free quota builds what a paid plan gives, France
first, the rest of Western Europe with what France leaves, for holidays). With --france
<manifest.json> (the road tiles of the data release, built from the France extract), each circle
is French when it reaches a French road tile. Calls go, in this order, to: French circles never
read, French circles not read for a week, foreign circles never read, foreign circles not read for
a month (six months for an empty one: sea); quota left over refreshes France (read over a day ago),
then abroad (over a week ago), so the daily quota always works. The grid covers Western Europe (it is extended once,
grid v2, around the original French rectangle).

Usage: LUFOP_API_KEY=... harvest_lufop.py <lufop.json> [budget per 24 h] [all] [--france manifest.json]
"""
import json
import math
import os
import random
import re
import sys
import time
import urllib.parse
import urllib.request
from datetime import datetime, timezone

API = "https://api.lufop.net/api"
MAX_RESULTS = 200
MIN_INTERVAL_S = 6.5  # free plan: 10 requests / minute
MAX_INTERVAL_S = 15.0
RUN_SHARE = (30, 60)  # calls one run may spend, drawn at random, within the 24-hour budget
WINDOW_S = 24 * 3600
# Metropolitan France + Corsica, 60 km grid, 45 km circles (covers each square).
LAT_MIN, LAT_MAX, LON_MIN, LON_MAX = 41.3, 51.2, -5.3, 9.7
STEP_KM = 60.0
# Abroad, after France: Western Europe (Portugal to Austria, Sicily to Denmark).
EU_LAT_MIN, EU_LAT_MAX, EU_LON_MIN, EU_LON_MAX = 35.9, 55.1, -9.6, 19.0
GRID_VERSION = 2
DAY_S = 24 * 3600
FRANCE_REFRESH_S = 7 * DAY_S
ABROAD_REFRESH_S = 30 * DAY_S
EMPTY_REFRESH_S = 180 * DAY_S
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


def extend_to_europe(cells):
    """Adds the Western Europe circles of the same lattice that no circle covers yet (grid v2)."""
    dlat = STEP_KM / 111.32
    k0 = math.floor((EU_LAT_MIN - LAT_MIN) / dlat)
    k1 = math.ceil((EU_LAT_MAX - LAT_MIN) / dlat)
    added = []
    for k in range(k0, k1 + 1):
        lat = LAT_MIN + k * dlat
        dlon = STEP_KM / (111.32 * math.cos(math.radians(lat)))
        for m in range(math.floor((EU_LON_MIN - LON_MIN) / dlon), math.ceil((EU_LON_MAX - LON_MIN) / dlon) + 1):
            lon = LON_MIN + m * dlon
            if any(abs(c["lat"] - lat) < 0.5 and distance_km(lat, lon, c["lat"], c["lon"]) < RADIUS_KM * 0.9 for c in cells):
                continue
            added.append({"lat": round(lat, 4), "lon": round(lon, 4), "r": RADIUS_KM, "last": 0})
    cells.extend(added)
    return len(added)


def priority(cell, now):
    """Order of the next call (lowest first), or None when the circle is not due."""
    french = cell.get("fr", True)
    if cell["last"] == 0:
        return (0 if french else 2, 0)
    age = now - cell["last"]
    empty = cell.get("count", 1) == 0
    if french:
        if age >= FRANCE_REFRESH_S:
            return (1, cell["last"])
        # Quota to spare once everything is fresh: France read again, the oldest first…
        return (4, cell["last"]) if age >= DAY_S else None
    if age >= (EMPTY_REFRESH_S if empty else ABROAD_REFRESH_S):
        return (3, cell["last"])
    # …then abroad (never the sea).
    return (5, cell["last"]) if not empty and age >= 7 * DAY_S else None


def france_tiles(path):
    """(i, j) of the 0.5° road tiles of France listed by the data release manifest; None when unreadable."""
    try:
        with open(path, encoding="utf-8") as f:
            names = json.load(f)["tiles"]
    except (OSError, ValueError, KeyError, TypeError):
        return None
    tiles = set()
    for name in names:
        m = re.fullmatch(r"roads_(-?\d+)_(-?\d+)\.json\.gz", name)
        if m:
            tiles.add((int(m.group(1)), int(m.group(2))))
    return tiles or None


def touches_france(cell, tiles):
    """The circle reaches at least one French road tile (tiles: i = floor(lat * 2), j = floor(lon * 2))."""
    dlat = cell["r"] / 111.32
    dlon = cell["r"] / (111.32 * math.cos(math.radians(cell["lat"])))
    for i in range(math.floor((cell["lat"] - dlat) * 2), math.floor((cell["lat"] + dlat) * 2) + 1):
        for j in range(math.floor((cell["lon"] - dlon) * 2), math.floor((cell["lon"] + dlon) * 2) + 1):
            if (i, j) in tiles:
                return True
    return False


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
    args = sys.argv[1:]
    france = None
    if "--france" in args:
        k = args.index("--france")
        france = france_tiles(args[k + 1]) if k + 1 < len(args) else None
        del args[k:k + 2]
    path = args[0]
    budget = int(args[1]) if len(args) > 1 else 150
    key = os.environ.get("LUFOP_API_KEY")
    if not key:
        print("LUFOP_API_KEY absent: récolte Lufop ignorée")
        return
    db = json.load(open(path)) if os.path.exists(path) else {"v": 1, "cells": initial_cells(), "radars": {}}
    if db.get("grid", 1) < GRID_VERSION:
        print(f"grille étendue à l'Europe de l'Ouest : {extend_to_europe(db['cells'])} zones ajoutées")
        db["grid"] = GRID_VERSION
    if france:
        for c in db["cells"]:
            c["fr"] = touches_france(c, france)
    now = int(time.time())
    if "calls" not in db and db.get("updated"):
        # Base from before the call log: its last run counts as a full budget spent at that time.
        db["calls"] = [int(datetime.fromisoformat(db["updated"]).timestamp())] * budget
    db["calls"] = [t for t in db.get("calls", []) if t > now - WINDOW_S]
    left = budget - len(db["calls"])
    spend_all = len(args) > 2 and args[2] == "all"  # manual run: no random share
    share = left if spend_all else min(left, random.randint(*RUN_SHARE))
    print(f"{len(db['calls'])} requêtes ces dernières 24 h, {max(left, 0)} restantes, {max(share, 0)} pour ce passage")
    used = added = dropped = 0
    last_call = 0.0
    while used < share:
        due = [(p, c) for c in db["cells"] if (p := priority(c, now)) is not None]
        if not due:
            break  # France read this week, abroad this month: nothing to ask
        cell = min(due, key=lambda pc: pc[0])[1]
        wait = random.uniform(MIN_INTERVAL_S, MAX_INTERVAL_S) - (time.monotonic() - last_call)
        if wait > 0:
            time.sleep(wait)
        last_call = time.monotonic()
        # Counted before the answer: a call that fails still uses the quota.
        db["calls"].append(int(time.time()))
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
            children = split(cell)
            for c in children:
                c["fr"] = touches_france(c, france) if france else cell.get("fr", True)
            db["cells"].extend(children)
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
    fr = [c for c in db["cells"] if c.get("fr", True)]
    abroad = [c for c in db["cells"] if not c.get("fr", True)]
    print(f"{used} requêtes, {added} nouveaux, {dropped} retirés, {len(db['radars'])} radars Lufop ; "
          f"France {sum(1 for c in fr if c['last'])}/{len(fr)} zones lues, "
          f"étranger {sum(1 for c in abroad if c['last'])}/{len(abroad)}")


if __name__ == "__main__":
    main()
