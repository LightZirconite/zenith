#!/usr/bin/env python3
"""Builds radars.json by merging every radar source we have.

- Ministère de l'Intérieur list (data.gouv.fr, Licence Ouverte): authoritative position, type, limit.
- OSM speed cameras and enforcement relations (ODbL): measured direction, sections, extra radars.
- Our Lufop database (pipeline/harvest_lufop.py): mobile / roadwork radars and ones missing above.

Usage:
  build_radars.py osm <france.osm.pbf> <osm_radars.json>     weekly, from the OSM extract
  build_radars.py merge <osm_radars.json> <lufop.json|-> <radars.json>
  build_radars.py check <previous radars.json|-> <radars.json>   fails if more than 10 % of radars vanished

Every mode appends a short report to $GITHUB_STEP_SUMMARY when it is set (CI job summary).
"""
import csv
import io
import json
import math
import os
import sys
import urllib.request
import re
from collections import Counter
from datetime import datetime, timezone
from zoneinfo import ZoneInfo

OFFICIAL_DATASET = "https://www.data.gouv.fr/api/1/datasets/liste-des-radars-fixes-en-france/"
USER_AGENT = "Zenith/0.1 (https://github.com/LightZirconite/zenith)"
OFFICIAL_TYPES = {
    "ETF": "FIXED", "ETT": "FIXED", "ETU": "FIXED", "ETD": "DISCRIMINANT",
    "ETFR": "RED_LIGHT", "ETPN": "LEVEL_CROSSING", "ETVM": "SECTION_END",
}
MERGE_M = 80
MAX_LOSS = 0.10
PARIS = ZoneInfo("Europe/Paris")


def fetch(url, data=None, timeout=900):
    req = urllib.request.Request(url, data=data, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read()


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

        def node(self, n):
            tags = n.tags
            if tags.get("highway") == "speed_camera" or n.id in self.refs:
                self.nodes.append({"type": "node", "id": n.id, "lat": n.location.lat, "lon": n.location.lon,
                                   "tags": dict((t.k, t.v) for t in tags)})

    rel = Relations()
    rel.apply_file(pbf)
    nodes = Nodes(rel.refs)
    nodes.apply_file(pbf)
    return nodes.nodes + rel.relations


def osm_items(elements):
    nodes = {e["id"]: e for e in elements if e["type"] == "node"}
    items = {}
    for e in elements:
        if e["type"] == "node" and e.get("tags", {}).get("highway") == "speed_camera":
            t = e["tags"]
            d = speed(t.get("direction")) if str(t.get("direction", "")).replace(".", "").isdigit() else None
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
        item = {"id": f"lufop-{rid}", "pos": pos, "type": kind, "limit": speed(r.get("vitesse")), "dir": None}
        if kind == "MOBILE":
            item["at"] = lufop_time(r.get("update"))  # the app ignores stale mobile reports
        items.append(item)
    # Names without digits ("Radar Fixe 90" → "Radar Fixe"): shows which kinds Lufop really holds,
    # without publishing the base itself.
    kinds = Counter(re.sub(r"\s*\d+.*$", "", r.get("name") or "?").strip() for r in json.load(open(path))["radars"].values())
    summary(["### Noms Lufop", "| nom | radars |", "|---|---|"] + [f"| {k} | {v} |" for k, v in kinds.most_common(25)])
    return items


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
    for item in lufop:
        match = min(nearby(item["pos"], MERGE_M), key=lambda o: distance(o["pos"], item["pos"]), default=None)
        if match is None:
            match = min((e for e in extra if distance(e["pos"], item["pos"]) <= MERGE_M),
                        key=lambda e: distance(e["pos"], item["pos"]), default=None)
        if match:
            match["limit"] = match["limit"] or item["limit"]
            match["src"].append("lufop")
        else:
            extra.append({**item, "src": ["lufop"]})

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
        radars.append({"id": f"sec-{k}", "pos": s["from"], "type": "SECTION_START", "limit": limit, "dir": d, "len": length, "src": ["osm"]})
        if not ends:
            radars.append({"id": f"sec-{k}-end", "pos": s["to"], "type": "SECTION_END", "limit": limit, "dir": d, "src": ["osm"]})
    return radars


def write_pack(radars, gouv_url, out):
    doc = {
        "v": 1,
        "generated": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "sources": {"gouv": gouv_url, "osm": "OpenStreetMap contributors (ODbL)", "lufop": "Lufop"},
        "radars": [
            {k: v for k, v in {
                "id": r["id"], "lat": round(r["pos"][0], 6), "lon": round(r["pos"][1], 6), "type": r["type"],
                "limit": r.get("limit"), "dir": r.get("dir"), "len": r.get("len"), "at": r.get("at"),
                "src": "+".join(sorted(set(r["src"]))),
            }.items() if v is not None}
            for r in radars
        ],
    }
    with open(out, "w", encoding="utf-8") as f:
        json.dump(doc, f, separators=(",", ":"))
    by_src = Counter(r["src"] for r in doc["radars"])
    by_type = Counter(r["type"] for r in doc["radars"])
    with_dir = sum(1 for r in doc["radars"] if "dir" in r)
    summary(
        [f"### radars.json : {len(doc['radars'])} radars, {with_dir} avec sens", "| source | radars |", "|---|---|"]
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
        gouv, gouv_url = official()
        write_pack(merge(gouv, items, sections, lufop_items(sys.argv[3])), gouv_url, sys.argv[4])
    elif mode == "check":
        check(sys.argv[2], sys.argv[3])
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main()
