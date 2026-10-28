#!/usr/bin/env python3
"""Builds radars.json by merging every radar source we have.

- Ministère de l'Intérieur list (data.gouv.fr, Licence Ouverte): authoritative position, type, limit.
- Sécurité routière map (radars.securite-routiere.gouv.fr, the official map's own feed): radars newer
  than the data.gouv list, section lengths, and the "itinéraires de contrôle" where a movable radar
  rotates between cabins (published as zones).
- OSM speed cameras and enforcement relations (ODbL): measured direction, sections, extra radars.
- Our Lufop database (pipeline/harvest_lufop.py): mobile / roadwork radars and ones missing above.

Usage:
  build_radars.py osm <france.osm.pbf> <osm_radars.json>     weekly, from the OSM extract
  build_radars.py merge <osm_radars.json> <lufop.json|-> <radars.json> [official.json]
  build_radars.py check <previous radars.json|-> <radars.json>   fails if more than 10 % of radars vanished

official.json caches the last good copy of both official sources: when one is down, the merge uses
its cached copy instead of losing it (the file is rewritten with whatever was fetched fresh).

Every mode appends a short report to $GITHUB_STEP_SUMMARY when it is set (CI job summary).
"""
import csv
import io
import json
import math
import os
import sys
import time
import urllib.request
import re
from collections import Counter
from datetime import datetime, timezone
from zoneinfo import ZoneInfo

OFFICIAL_DATASET = "https://www.data.gouv.fr/api/1/datasets/liste-des-radars-fixes-en-france/"
SR_LIST = "https://radars.securite-routiere.gouv.fr/radars/all?_format=json"
SR_DETAIL = "https://radars.securite-routiere.gouv.fr/radars/{}?_format=json"
USER_AGENT = "Zenith/0.1 (https://github.com/LightZirconite/zenith)"
OFFICIAL_TYPES = {
    "ETF": "FIXED", "ETT": "FIXED", "ETU": "FIXED", "ETD": "DISCRIMINANT",
    "ETFR": "RED_LIGHT", "ETPN": "LEVEL_CROSSING", "ETVM": "SECTION_END",
}
SR_TYPES = {
    "fixes": "FIXED", "urbain": "FIXED", "discriminants": "DISCRIMINANT", "feux": "RED_LIGHT",
    "niveaux": "LEVEL_CROSSING", "troncons": "SECTION_END",
}
SR_PAUSE_S = 0.3  # between two detail requests: a handful per run, stay gentle
SR_DETAIL_MAX_AGE_S = 30 * 86400
ZONE_TOLERANCE_M = 12  # zone polylines simplified to this, well under the in-app matching distance
MERGE_M = 80
MAX_LOSS = 0.10
PARIS = ZoneInfo("Europe/Paris")


def fetch(url, data=None, timeout=900, attempts=3):
    """GET (or POST) with a few retries: a transient error must not cost a source."""
    req = urllib.request.Request(url, data=data, headers={"User-Agent": USER_AGENT})
    for attempt in range(attempts):
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                return r.read()
        except Exception:  # noqa: BLE001 - retried, then raised
            if attempt == attempts - 1:
                raise
            time.sleep(5 * (attempt + 1))


def distance(a, b):
    lat1, lon1, lat2, lon2 = map(math.radians, (a[0], a[1], b[0], b[1]))
    h = math.sin((lat2 - lat1) / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin((lon2 - lon1) / 2) ** 2
    return 2 * 6371008.8 * math.asin(math.sqrt(h))


def bearing(a, b):
    lat1, lat2 = math.radians(a[0]), math.radians(b[0])
    dlon = math.radians(b[1] - a[1])
    y = math.sin(dlon) * math.cos(lat2)
    x = math.cos(lat1) * math.sin(lat2) - math.sin(lat1) * math.cos(lat2) * math.cos(dlon)
    return round(math.degrees(math.atan2(y, x)) % 360, 1)


def speed(value):
    try:
        return int(str(value).split()[0])
    except (TypeError, ValueError, IndexError):
        return None


def official():
    meta = json.loads(fetch(OFFICIAL_DATASET))
    csvs = [r for r in meta["resources"] if r["format"].lower() == "csv"]
    latest = max(csvs, key=lambda r: r["last_modified"])
    text = fetch(latest["url"]).decode("latin-1")
    rows = list(csv.reader(io.StringIO(text), delimiter=";"))[1:]
    out = []
    for row in rows:
        if len(row) < 6:
            continue
        rid, kind, _, vma, lat, lon = (c.strip() for c in row[:6])
        try:
            pos = (float(lat), float(lon))
        except ValueError:
            continue
        out.append({"id": f"fr-{rid}", "pos": pos, "type": OFFICIAL_TYPES.get(kind, "FIXED"), "limit": speed(vma), "src": ["gouv"]})
    return out, latest["url"]


def sr_detail(rid):
    """Limit (light vehicles) and section length of one radar of the Sécurité routière map."""
    d = json.loads(fetch(SR_DETAIL.format(rid), timeout=60))
    limit = next((int(m.group(1)) for r in d.get("rulesmesured") or []
                  if (m := re.fullmatch(r"Vitesse VL (\d+)", (r.get("name") or "").strip()))), None)
    try:
        length = round(float(str(d.get("radartronconkm") or "").replace(",", ".")) * 1000)
    except ValueError:
        length = None
    return {"limit": limit, "len": length or None}


def securite_routiere(known_ids, details):
    """Radars and control itineraries of the official map. Details (one request each) only for the
    sections, whose length the list lacks, and for radars the data.gouv list does not have yet;
    [details] caches them by id (refreshed after SR_DETAIL_MAX_AGE_S) and is updated in place."""
    radars, zones = [], []
    for e in json.loads(fetch(SR_LIST, timeout=120)):
        try:
            pos = (float(e["lat"]), float(e["lng"]))
        except (KeyError, TypeError, ValueError):
            continue
        if e.get("type") == "itineraire":
            pts = [(float(p[0]), float(p[1])) for p in e.get("geoJson") or [] if isinstance(p, list) and len(p) == 2]
            if len(pts) >= 2:
                zones.append({"id": str(e["id"]), "pts": simplify(pts, ZONE_TOLERANCE_M)})
            continue
        kind = SR_TYPES.get(e.get("type"))
        if kind is None:
            continue
        radars.append({"id": f"fr-{e['id']}", "pos": pos, "type": kind, "limit": None, "src": ["sr"]})
    read = 0
    now = int(time.time())
    for r in radars:
        # Without the data.gouv list at all, only the sections: thousands of requests otherwise.
        if r["type"] != "SECTION_END" and (not known_ids or r["id"] in known_ids):
            continue
        cached = details.get(r["id"])
        if cached is None or now - cached["at"] > SR_DETAIL_MAX_AGE_S:
            try:
                cached = details[r["id"]] = {**sr_detail(r["id"][3:]), "at": now}
                read += 1
            except Exception as e:  # noqa: BLE001 - one detail missing keeps the radar, without it
                print(f"détail {r['id']} indisponible : {e}")
            time.sleep(SR_PAUSE_S)
        if cached:
            r.update({k: v for k, v in cached.items() if k != "at"})
    print(f"Sécurité routière : {len(radars)} radars, {len(zones)} itinéraires, {read} détails lus ({len(details)} en cache)")
    return radars, zones


def simplify(pts, tolerance_m):
    """Douglas-Peucker on a local plane: drops points closer than tolerance_m to the simplified line."""
    lat0 = math.radians(pts[0][0])
    xy = [(p[1] * 111_320 * math.cos(lat0), p[0] * 110_574) for p in pts]
    keep = [False] * len(pts)
    keep[0] = keep[-1] = True
    stack = [(0, len(pts) - 1)]
    while stack:
        a, b = stack.pop()
        (ax, ay), (bx, by) = xy[a], xy[b]
        dx, dy = bx - ax, by - ay
        norm = math.hypot(dx, dy)
        far, far_d = None, tolerance_m
        for k in range(a + 1, b):
            px, py = xy[k]
            d = abs(dx * (ay - py) - dy * (ax - px)) / norm if norm else math.hypot(px - ax, py - ay)
            if d > far_d:
                far, far_d = k, d
        if far is not None:
            keep[far] = True
            stack += [(a, far), (far, b)]
    return [p for p, k in zip(pts, keep) if k]


def official_sources(cache_path):
    """Both official sources, fresh when reachable, else their last good copy from cache_path."""
    cache = {}
    if cache_path and os.path.exists(cache_path):
        cache = json.load(open(cache_path))
    now = datetime.now(timezone.utc).isoformat(timespec="seconds")
    try:
        radars, url = official()
        cache["gouv"] = {"fetched": now, "url": url, "radars": radars}
    except Exception as e:  # noqa: BLE001 - fall back on the cached copy below
        print(f"::warning::liste data.gouv indisponible ({e}) : copie du {cache.get('gouv', {}).get('fetched', '-')}")
    known = {r["id"] for r in cache.get("gouv", {}).get("radars", [])}
    details = cache.setdefault("sr_details", {})
    try:
        radars, zones = securite_routiere(known, details)
        cache["sr"] = {"fetched": now, "radars": radars, "zones": zones}
    except Exception as e:  # noqa: BLE001 - fall back on the cached copy below
        print(f"::warning::carte Sécurité routière indisponible ({e}) : copie du {cache.get('sr', {}).get('fetched', '-')}")
    if "gouv" not in cache and "sr" not in cache:
        sys.exit("::error::aucune source officielle disponible, ni en direct ni en cache")
    if cache_path:
        with open(cache_path, "w", encoding="utf-8") as f:
            json.dump(cache, f, separators=(",", ":"), ensure_ascii=False)
    gouv = cache.get("gouv", {"radars": [], "url": None})
    sr = cache.get("sr", {"radars": [], "zones": []})
    tuples = lambda rs: [{**r, "pos": tuple(r["pos"]), "src": list(r["src"])} for r in rs]
    return tuples(gouv["radars"]), gouv["url"], tuples(sr["radars"]), [{**z, "pts": [tuple(p) for p in z["pts"]]} for z in sr["zones"]]


def summary(lines):
    """Prints a report and appends it to the CI job summary."""
    text = "\n".join(lines)
    print(text)
    path = os.environ.get("GITHUB_STEP_SUMMARY")
    if path:
        with open(path, "a", encoding="utf-8") as f:
            f.write(text + "\n\n")


def lufop_time(value):
    """Lufop "update" (Paris local time, "YYYY-MM-DD hh:mm:ss") as epoch seconds, or None."""
    try:
        return int(datetime.strptime(str(value), "%Y-%m-%d %H:%M:%S").replace(tzinfo=PARIS).timestamp())
    except ValueError:
        return None


def osm_elements(pbf):
    """Enforcement relations, speed cameras and member node locations from an OSM extract."""
    import osmium  # only needed for this weekly step

    class Relations(osmium.SimpleHandler):
        """Pass 1: enforcement relations and the node ids they reference."""

        def __init__(self):
            super().__init__()
            self.relations = []
            self.refs = set()

        def relation(self, r):
            if r.tags.get("type") != "enforcement":
                return
            members = [{"type": {"n": "node", "w": "way", "r": "relation"}[m.type], "ref": m.ref, "role": m.role} for m in r.members]
            self.relations.append({"type": "relation", "id": r.id, "tags": dict((t.k, t.v) for t in r.tags), "members": members})
            self.refs.update(m["ref"] for m in members if m["type"] == "node")


    class Nodes(osmium.SimpleHandler):
        """Pass 2: speed camera nodes and the locations of relation members."""

        def __init__(self, refs):
            super().__init__()
            self.refs = refs
            self.nodes = []
            self.relative = set()  # cameras tagged direction=forward/backward

        def node(self, n):
            tags = n.tags
            if tags.get("highway") == "speed_camera" or n.id in self.refs:
                self.nodes.append({"type": "node", "id": n.id, "lat": n.location.lat, "lon": n.location.lon,
                                   "tags": dict((t.k, t.v) for t in tags)})
                if tags.get("highway") == "speed_camera" and tags.get("direction") in ("forward", "backward"):
                    self.relative.add(n.id)

    class WayBearings(osmium.SimpleHandler):
        """Pass 3: bearing of the road at each camera tagged forward/backward (way order)."""

        def __init__(self, wanted):
            super().__init__()
            self.wanted = wanted
            self.bearings = {}

        def way(self, w):
            if "highway" not in w.tags:
                return
            nodes = [(n.ref, n.location) for n in w.nodes]
            for k, (ref, _) in enumerate(nodes):
                if ref not in self.wanted or ref in self.bearings:
                    continue
                a, b = nodes[max(k - 1, 0)][1], nodes[min(k + 1, len(nodes) - 1)][1]
                if a.valid() and b.valid() and (a.lat, a.lon) != (b.lat, b.lon):
                    self.bearings[ref] = bearing((a.lat, a.lon), (b.lat, b.lon))

    rel = Relations()
    rel.apply_file(pbf)
    nodes = Nodes(rel.refs)
    nodes.apply_file(pbf)
    ways = WayBearings(nodes.relative)
    if nodes.relative:
        ways.apply_file(pbf, locations=True, idx="flex_mem")
    for e in nodes.nodes:
        if e["id"] in ways.bearings:
            e["way_bearing"] = ways.bearings[e["id"]]
    return nodes.nodes + rel.relations


def osm_items(elements):
    nodes = {e["id"]: e for e in elements if e["type"] == "node"}
    items = {}
    for e in elements:
        if e["type"] == "node" and e.get("tags", {}).get("highway") == "speed_camera":
            t = e["tags"]
            d = speed(t.get("direction")) if str(t.get("direction", "")).replace(".", "").isdigit() else None
            # forward / backward: relative to the way the camera stands on.
            if d is None and "way_bearing" in e and t.get("direction") in ("forward", "backward"):
                d = round((e["way_bearing"] + (180 if t["direction"] == "backward" else 0)) % 360, 1)
            items[e["id"]] = {"pos": (e["lat"], e["lon"]), "limit": speed(t.get("maxspeed")), "dir": d, "type": "FIXED"}
    sections = []
    for r in (e for e in elements if e["type"] == "relation"):
        t = r.get("tags", {})
        roles = {}
        for m in r["members"]:
            if m["type"] == "node" and m["ref"] in nodes:
                roles.setdefault(m["role"], (nodes[m["ref"]]["lat"], nodes[m["ref"]]["lon"]))
        device_ref = next((m["ref"] for m in r["members"] if m["role"] == "device" and m["type"] == "node"), None)
        kind = t.get("enforcement")
        if kind == "average_speed" and "from" in roles and "to" in roles:
            sections.append({"from": roles["from"], "to": roles["to"], "limit": speed(t.get("maxspeed"))})
            continue
        device = roles.get("device")
        if device is None:
            continue
        direction = bearing(roles["from"], device) if "from" in roles and distance(roles["from"], device) > 5 else (
            bearing(device, roles["to"]) if "to" in roles and distance(device, roles["to"]) > 5 else None)
        item = items.setdefault(device_ref, {"pos": device, "limit": None, "dir": None, "type": "FIXED"})
        item["dir"] = direction if direction is not None else item["dir"]
        item["limit"] = speed(t.get("maxspeed")) or item["limit"]
        if kind == "traffic_signals":
            item["type"] = "RED_LIGHT"
    return list(items.values()), sections


def lufop_items(path):
    """Radars from our Lufop database. Their azimuth meaning is undocumented, so no direction."""
    if path == "-" or not os.path.exists(path):
        return []
    items = []
    for rid, r in json.load(open(path))["radars"].items():
        name = (r.get("name") or "").lower()
        if "covoiturage" in name and not r.get("vitesse"):
            continue  # lane-occupancy cameras, not speed
        if "feu" in name:
            kind = "RED_LIGHT"
        elif "chantier" in name:
            kind = "WORKZONE"
        elif "mobile" in name:
            kind = "MOBILE"
        elif "tron" in name:
            kind = "SECTION_END"
        else:
            kind = "FIXED"
        try:
            pos = (float(r["lat"]), float(r["lng"]))
        except (KeyError, TypeError, ValueError):
            continue
        item = {"id": f"lufop-{rid}", "pos": pos, "type": kind, "limit": speed(r.get("vitesse")), "dir": None,
                "azimut": speed(r.get("azimut")), "flash": (r.get("flash") or "").strip() or "-"}
        if kind == "MOBILE":
            item["at"] = lufop_time(r.get("update"))  # the app ignores stale mobile reports
        items.append(item)
    # Names without digits ("Radar Fixe 90" → "Radar Fixe"): shows which kinds Lufop really holds,
    # without publishing the base itself.
    kinds = Counter(re.sub(r"\s*\d+.*$", "", r.get("name") or "?").strip() for r in json.load(open(path))["radars"].values())
    summary(["### Noms Lufop", "| nom | radars |", "|---|---|"] + [f"| {k} | {v} |" for k, v in kinds.most_common(25)])
    return items


def merge_official(gouv, sr):
    """data.gouv list + Sécurité routière map. Same id (or same type within MERGE_M) = same radar;
    the map adds its newer radars and the section lengths ("end_len", used only without a start)."""
    by_id = {r["id"]: r for r in gouv}
    out = list(gouv)
    for r in sr:
        g = by_id.get(r["id"]) or min(
            (o for o in gouv if o["type"] == r["type"] and distance(o["pos"], r["pos"]) <= MERGE_M),
            key=lambda o: distance(o["pos"], r["pos"]), default=None)
        if g is None:
            g = {k: v for k, v in r.items() if k != "len"}
            out.append(g)
        else:
            g["src"].append("sr")
            g["limit"] = g["limit"] or r.get("limit")
        if g["type"] == "SECTION_END" and r.get("len"):
            g["end_len"] = r["len"]
    return out


def merge(official_radars, osm, sections, lufop=()):
    cell = lambda p: (round(p[0] * 100), round(p[1] * 100))  # ~1 km buckets
    grid = {}
    for o in official_radars:
        grid.setdefault(cell(o["pos"]), []).append(o)

    def nearby(p, radius):
        c = cell(p)
        for di in (-1, 0, 1):
            for dj in (-1, 0, 1):
                for o in grid.get((c[0] + di, c[1] + dj), []):
                    if distance(o["pos"], p) <= radius:
                        yield o

    extra = []
    for k, item in enumerate(osm):
        match = min(nearby(item["pos"], MERGE_M), key=lambda o: distance(o["pos"], item["pos"]), default=None)
        if match:
            match.setdefault("dir", item["dir"])
            if match.get("dir") is None:
                match["dir"] = item["dir"]
            match["limit"] = match["limit"] or item["limit"]
            match["src"].append("osm")
        else:
            extra.append({"id": f"osm-{k}", "pos": item["pos"], "type": item["type"], "limit": item["limit"], "dir": item["dir"], "src": ["osm"]})

    # Lufop last: fills limits, confirms, and adds what the others miss (mobile, roadwork…).
    # Its undocumented "azimut" is compared with known directions (report only, not used yet).
    azimut_diffs = []
    by_flash = {}
    for item in lufop:
        match = min(nearby(item["pos"], MERGE_M), key=lambda o: distance(o["pos"], item["pos"]), default=None)
        if match is None:
            match = min((e for e in extra if distance(e["pos"], item["pos"]) <= MERGE_M),
                        key=lambda e: distance(e["pos"], item["pos"]), default=None)
        if match:
            match["limit"] = match["limit"] or item["limit"]
            match["src"].append("lufop")
            if match.get("dir") is not None and item.get("azimut") is not None:
                diff = abs((item["azimut"] - match["dir"] + 180) % 360 - 180)
                azimut_diffs.append(diff)
                by_flash.setdefault(item["flash"], []).append(diff)
        else:
            extra.append({k: v for k, v in item.items() if k not in ("azimut", "flash")} | {"src": ["lufop"]})
    if azimut_diffs:
        same = sum(1 for d in azimut_diffs if d <= 30) / len(azimut_diffs)
        opposite = sum(1 for d in azimut_diffs if d >= 150) / len(azimut_diffs)
        summary([f"### Azimut Lufop contre sens connus : {len(azimut_diffs)} radars comparés, "
                 f"{same:.0%} dans le même sens (±30°), {opposite:.0%} à l'opposé. Seuil pour s'en servir : 95 %.",
                 "", "Par valeur de « flash » (si l'une donne le sens à coup sûr, elle tranche) :",
                 "| flash | radars | même sens | opposé |", "|---|---|---|---|"]
                + [f"| {f} | {len(d)} | {sum(1 for x in d if x <= 30) / len(d):.0%} | {sum(1 for x in d if x >= 150) / len(d):.0%} |"
                   for f, d in sorted(by_flash.items(), key=lambda kv: -len(kv[1]))])

    radars = official_radars + extra
    for k, s in enumerate(sections):
        length = round(distance(s["from"], s["to"]))
        if length < 200:
            continue
        d = bearing(s["from"], s["to"])
        ends = list(nearby(s["to"], 300))
        limit = s["limit"] or next((e["limit"] for e in ends if e["limit"]), None)
        for e in ends:
            if e["type"] == "SECTION_END":
                e["dir"] = e.get("dir") or d
                e["has_start"] = True
        radars.append({"id": f"sec-{k}", "pos": s["from"], "type": "SECTION_START", "limit": limit, "dir": d, "len": length, "src": ["osm"]})
        if not ends:
            radars.append({"id": f"sec-{k}-end", "pos": s["to"], "type": "SECTION_END", "limit": limit, "dir": d, "src": ["osm"]})
    # A section end without a known start carries the section length: the app places the start on
    # the road behind it (and can pick up a section already under way).
    for r in radars:
        if r["type"] == "SECTION_END" and r.get("end_len") and not r.get("has_start"):
            r["len"] = r["end_len"]
    return radars


def write_pack(radars, zones, gouv_url, out):
    doc = {
        "v": 1,
        "generated": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "sources": {"gouv": gouv_url, "sr": "https://radars.securite-routiere.gouv.fr",
                    "osm": "OpenStreetMap contributors (ODbL)", "lufop": "Lufop"},
        "radars": [
            {k: v for k, v in {
                "id": r["id"], "lat": round(r["pos"][0], 6), "lon": round(r["pos"][1], 6), "type": r["type"],
                "limit": r.get("limit"), "dir": r.get("dir"), "len": r.get("len"), "at": r.get("at"),
                "src": "+".join(sorted(set(r["src"]))),
            }.items() if v is not None}
            for r in radars
        ],
        # Control itineraries: flat [lat, lon, lat, lon, ...] polylines, like the road tiles.
        "zones": [
            {"id": z["id"], "kind": "ITINERARY", "pts": [round(c, 5) for p in z["pts"] for c in p]}
            for z in zones
        ],
    }
    with open(out, "w", encoding="utf-8") as f:
        json.dump(doc, f, separators=(",", ":"))
    by_src = Counter(r["src"] for r in doc["radars"])
    by_type = Counter(r["type"] for r in doc["radars"])
    with_dir = sum(1 for r in doc["radars"] if "dir" in r)
    zone_km = sum(sum(distance(a, b) for a, b in zip(z["pts"], z["pts"][1:])) for z in zones) / 1000
    summary(
        [f"### radars.json : {len(doc['radars'])} radars, {with_dir} avec sens, "
         f"{sum(1 for r in doc['radars'] if r['type'] == 'SECTION_END' and 'len' in r)} fins de tronçon sans début avec longueur, "
         f"{len(zones)} itinéraires de contrôle ({zone_km:.0f} km)", "| source | radars |", "|---|---|"]
        + [f"| {k} | {v} |" for k, v in by_src.most_common()]
        + ["", "| type | radars |", "|---|---|"]
        + [f"| {k} | {v} |" for k, v in by_type.most_common()]
    )


def check(previous, current):
    """Refuses a pack that lost more than MAX_LOSS of the previous one (broken source, bad merge)."""
    new = json.load(open(current))["radars"]
    if previous == "-" or not os.path.exists(previous):
        summary([f"Contrôle : pas de version précédente, {len(new)} radars."])
        return
    old = json.load(open(previous))["radars"]
    old_ids, new_ids = {r["id"] for r in old}, {r["id"] for r in new}
    summary([f"Contrôle : {len(old)} → {len(new)} radars, {len(new_ids - old_ids)} ajoutés, {len(old_ids - new_ids)} retirés."])
    if len(new) < len(old) * (1 - MAX_LOSS):
        sys.exit(f"::error::radars.json perd plus de {MAX_LOSS:.0%} des radars ({len(old)} → {len(new)}) : publication refusée")


def main():
    mode = sys.argv[1]
    if mode == "osm":
        items, sections = osm_items(osm_elements(sys.argv[2]))
        with open(sys.argv[3], "w", encoding="utf-8") as f:
            json.dump({"items": items, "sections": sections}, f, separators=(",", ":"))
        print(f"OSM : {len(items)} radars, {len(sections)} tronçons")
    elif mode == "merge":
        osm = json.load(open(sys.argv[2]))
        items = [{**i, "pos": tuple(i["pos"])} for i in osm["items"]]
        sections = [{**x, "from": tuple(x["from"]), "to": tuple(x["to"])} for x in osm["sections"]]
        gouv, gouv_url, sr, zones = official_sources(sys.argv[5] if len(sys.argv) > 5 else None)
        radars = merge(merge_official(gouv, sr), items, sections, lufop_items(sys.argv[3]))
        write_pack(radars, zones, gouv_url, sys.argv[4])
    elif mode == "check":
        check(sys.argv[2], sys.argv[3])
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main()
