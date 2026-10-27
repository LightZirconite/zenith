#!/usr/bin/env python3
"""Builds speed-limit road tiles from an OSM extract.

Each drivable OSM way is split at junction nodes into segments carrying their speed limit
(explicit tag, French implicit value, or inferred from the road class). Segments are written
to 0.5° tiles: roads_<i>_<j>.json.gz with i = floor(lat * 2), j = floor(lon * 2). A segment
crossing a tile edge is written to every tile it touches (same id), so tiles stand alone.

Usage: build_roads.py <extract.osm.pbf> <out_dir>
"""
import gzip
import json
import math
import os
import re
import sys

import osmium
import osmium.index

TILE_PER_DEG = 2
CLASSES = [
    "motorway", "trunk", "primary", "secondary", "tertiary", "unclassified", "residential",
    "living_street", "motorway_link", "trunk_link", "primary_link", "secondary_link", "tertiary_link",
]
CLASS_INDEX = {c: i for i, c in enumerate(CLASSES)}
IMPLICIT = {"FR:urban": 50, "FR:rural": 80, "FR:motorway": 130, "FR:zone30": 30, "FR:walk": 6}
SRC_EXPLICIT, SRC_IMPLICIT, SRC_INFERRED = 0, 1, 2


def parse_speed(value):
    """Returns (kmh, source) for a maxspeed-like value, or (None, None)."""
    if not value:
        return None, None
    value = value.strip()
    if value in IMPLICIT:
        return IMPLICIT[value], SRC_IMPLICIT
    m = re.fullmatch(r"(\d+)(?: ?km/h)?", value)
    if m:
        return int(m.group(1)), SRC_EXPLICIT
    return None, None


def limits(tags, highway):
    """Returns (forward, backward, source) speed limits for a way."""
    fwd, src = parse_speed(tags.get("maxspeed"))
    bwd = fwd
    f2, s2 = parse_speed(tags.get("maxspeed:forward"))
    b2, s3 = parse_speed(tags.get("maxspeed:backward"))
    if f2:
        fwd, src = f2, s2
    if b2:
        bwd, src = b2, src or s3
    if fwd is None and bwd is None:
        for key in ("maxspeed:type", "source:maxspeed", "zone:maxspeed"):
            v, _ = parse_speed(tags.get(key))
            if v:
                return v, v, SRC_IMPLICIT
        inferred = {"motorway": 130, "residential": 50, "living_street": 20}.get(highway)
        if highway == "trunk" and tags.get("oneway") == "yes":
            inferred = 110
        if inferred:
            return inferred, inferred, SRC_INFERRED
        return None, None, None
    return fwd if fwd is not None else bwd, bwd if bwd is not None else fwd, src


def oneway_of(tags, highway):
    v = tags.get("oneway")
    if v in ("yes", "true", "1"):
        return 1
    if v == "-1":
        return -1
    if v == "no":
        return 0
    return 1 if highway in ("motorway", "motorway_link") or tags.get("junction") == "roundabout" else 0


class JunctionCounter(osmium.SimpleHandler):
    """Pass 1: finds nodes used more than once by drivable ways."""

    def __init__(self):
        super().__init__()
        self.seen = osmium.index.IdSet()
        self.junction = osmium.index.IdSet()

    def way(self, w):
        if w.tags.get("highway") not in CLASS_INDEX:
            return
        refs = [n.ref for n in w.nodes]
        for k, ref in enumerate(refs):
            if ref in self.seen or (0 < k < len(refs) - 1 and refs.count(ref) > 1):
                self.junction.set(ref)
            self.seen.set(ref)
        self.junction.set(refs[0])
        self.junction.set(refs[-1])


class TileWriter:
    def __init__(self, out_dir):
        self.out_dir = out_dir
        self.files = {}
        self.count = 0

    def write(self, segment, coords):
        lats = coords[0::2]
        lons = coords[1::2]
        keys = {
            (i, j)
            for i in range(math.floor(min(lats) * TILE_PER_DEG), math.floor(max(lats) * TILE_PER_DEG) + 1)
            for j in range(math.floor(min(lons) * TILE_PER_DEG), math.floor(max(lons) * TILE_PER_DEG) + 1)
        }
        line = json.dumps(segment, separators=(",", ":"), ensure_ascii=False)
        for key in keys:
            f = self.files.get(key)
            if f is None:
                f = gzip.open(os.path.join(self.out_dir, f"roads_{key[0]}_{key[1]}.json.gz"), "wt", encoding="utf-8")
                f.write('{"v":1,"segments":[\n')
                self.files[key] = f
                f.first = True
            if not f.first:
                f.write(",\n")
            f.first = False
            f.write(line)
        self.count += 1

    def close(self):
        for f in self.files.values():
            f.write("\n]}\n")
            f.close()


class SegmentBuilder(osmium.SimpleHandler):
    """Pass 2: splits ways at junctions and writes segments to tiles."""

    def __init__(self, junction, writer):
        super().__init__()
        self.junction = junction
        self.writer = writer

    def way(self, w):
        highway = w.tags.get("highway")
        if highway not in CLASS_INDEX:
            return
        tags = {t.k: t.v for t in w.tags}
        if tags.get("area") == "yes" or tags.get("access") in ("no", "private"):
            return
        nodes = [(n.ref, n.location) for n in w.nodes if n.location.valid()]
        if len(nodes) < 2:
            return
        fwd, bwd, src = limits(tags, highway)
        oneway = oneway_of(tags, highway)
        if oneway == -1:
            nodes.reverse()
            fwd, bwd = bwd, fwd
            oneway = 1
        name = tags.get("ref") or tags.get("name")
        start = 0
        piece = 0
        for k in range(1, len(nodes)):
            if k == len(nodes) - 1 or nodes[k][0] in self.junction:
                part = nodes[start:k + 1]
                coords = []
                for _, loc in part:
                    coords += [round(loc.lat, 6), round(loc.lon, 6)]
                seg_id = w.id * 1000 + piece
                self.writer.write(
                    [seg_id, part[0][0], part[-1][0], oneway, fwd, bwd, src, CLASS_INDEX[highway], name, coords],
                    coords,
                )
                start = k
                piece += 1


def main():
    pbf, out_dir = sys.argv[1], sys.argv[2]
    os.makedirs(out_dir, exist_ok=True)
    counter = JunctionCounter()
    counter.apply_file(pbf)
    writer = TileWriter(out_dir)
    SegmentBuilder(counter.junction, writer).apply_file(pbf, locations=True, idx="flex_mem")
    writer.close()
    print(f"{writer.count} segments, {len(writer.files)} tiles")


if __name__ == "__main__":
    main()
