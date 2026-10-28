#!/usr/bin/env python3
"""Builds speed-limit road tiles from an OSM extract.

Each drivable OSM way is split at junction nodes into segments carrying their speed limit
(explicit tag, French implicit value, or inferred from the road class). Segments are written
to 0.5° tiles: roads_<i>_<j>.json.gz with i = floor(lat * 2), j = floor(lon * 2). A segment
crossing a tile edge is written to every tile it touches (same id), so tiles stand alone.

Roads with no limit at all get the French default of where they are: 50 in a built-up area,
80 outside on a two-way road (since 2018). Built-up areas come from landuse polygons, rasterised
on a ~100 m grid; small isolated patches (farms, hamlets without entry signs) are dropped. Such
roads are also cut where they enter or leave a built-up area.

Usage: build_roads.py <extract.osm.pbf> <out_dir>
"""
import gzip
import json
import math
import os
import re
import sys
from collections import deque

import osmium
import osmium.index

TILE_PER_DEG = 2
CLASSES = [
    "motorway", "trunk", "primary", "secondary", "tertiary", "unclassified", "residential",
    "living_street", "motorway_link", "trunk_link", "primary_link", "secondary_link", "tertiary_link",
]
CLASS_INDEX = {c: i for i, c in enumerate(CLASSES)}
IMPLICIT = {"FR:urban": 50, "FR:rural": 80, "FR:motorway": 130, "FR:zone30": 30, "FR:walk": 6}
SRC_EXPLICIT, SRC_IMPLICIT, SRC_INFERRED, SRC_AREA = 0, 1, 2, 3

# Built-up areas (see module doc).
BUILTUP_LANDUSE = {"residential", "retail", "commercial"}
AREA_CLASSES = {"trunk", "primary", "secondary", "tertiary", "unclassified"}
CELL_LAT, CELL_LON = 1 / 1000, 1 / 700  # ~110 m both ways across France
MIN_PATCH_CELLS = 4  # ~5 ha
MIN_RUN_M = 100  # shorter in/out runs inside a way are noise along a boundary
URBAN_KMH, RURAL_KMH = 50, 80
ROW = 1_000_000  # cell key = row * ROW + column


def parse_speed(value):
    """Returns (kmh, source) for a maxspeed-like value, or (None, None)."""
    if not value:
        return None, None
    value = value.strip()
    if value in IMPLICIT:
        return IMPLICIT[value], SRC_IMPLICIT
    m = re.fullmatch(r"(\d+)(?: ?km/h)?", value)
    # Typos (1, 3, 320…) are no limit at all rather than a wrong one.
    if m and 5 <= int(m.group(1)) <= 130:
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
    # A limit tagged for one direction only says nothing about the other one.
    return fwd, bwd, src


def oneway_of(tags, highway):
    v = tags.get("oneway")
    if v in ("yes", "true", "1"):
        return 1
    if v == "-1":
        return -1
    if v == "no":
        return 0
    return 1 if highway in ("motorway", "motorway_link") or tags.get("junction") == "roundabout" else 0


def cell_key(lat, lon):
    return math.floor(lat / CELL_LAT) * ROW + math.floor(lon / CELL_LON)


def fill(rings, cells):
    """Adds the cells whose centre lies inside [rings] (even-odd rule: inner rings are holes)."""
    edges = [(a, b) for ring in rings for a, b in zip(ring, ring[1:] + ring[:1]) if a[0] != b[0]]
    if not edges:
        return
    lats = [p[0] for ring in rings for p in ring]
    for i in range(math.floor(min(lats) / CELL_LAT), math.floor(max(lats) / CELL_LAT) + 1):
        y = (i + 0.5) * CELL_LAT
        xs = sorted(a[1] + (y - a[0]) * (b[1] - a[1]) / (b[0] - a[0]) for a, b in edges if (a[0] <= y) != (b[0] <= y))
        for x0, x1 in zip(xs[0::2], xs[1::2]):
            for j in range(math.ceil(x0 / CELL_LON - 0.5), math.floor(x1 / CELL_LON - 0.5) + 1):
                cells.add(i * ROW + j)


def drop_small_patches(cells, min_cells):
    """Removes the 4-connected groups of fewer than [min_cells] cells."""
    seen = set()
    for start in list(cells):
        if start in seen:
            continue
        group, queue = [start], deque([start])
        seen.add(start)
        while queue:
            k = queue.popleft()
            for n in (k + 1, k - 1, k + ROW, k - ROW):
                if n in cells and n not in seen:
                    seen.add(n)
                    group.append(n)
                    queue.append(n)
        if len(group) < min_cells:
            cells.difference_update(group)


class BuiltUp:
    """Built-up grid. A point counts as built-up when its cell or one of the 4 next to it is: roads
    often run between landuse polygons, on cells left out of them."""

    def __init__(self, cells):
        self.cells = cells

    def covers(self, lat, lon):
        k = cell_key(lat, lon)
        return any(n in self.cells for n in (k, k + 1, k - 1, k + ROW, k - ROW))


def smooth(flags, coords):
    """Flips in/out runs shorter than MIN_RUN_M between two runs of the other state."""
    runs, start = [], 0
    for k in range(1, len(flags) + 1):
        if k == len(flags) or flags[k] != flags[start]:
            runs.append((start, k))
            start = k
    out = list(flags)
    for a, b in runs[1:-1]:
        length = sum(math.dist((coords[k][0] * 111_000, coords[k][1] * 78_000), (coords[k + 1][0] * 111_000, coords[k + 1][1] * 78_000))
                     for k in range(max(a - 1, 0), b))
        if length < MIN_RUN_M:
            out[a:b] = [not flags[a]] * (b - a)
    return out


class JunctionCounter(osmium.SimpleHandler):
    """Pass 1: finds nodes used more than once by drivable ways, and the built-up areas."""

    def __init__(self):
        super().__init__()
        self.seen = osmium.index.IdSet()
        self.junction = osmium.index.IdSet()
        self.cells = set()

    def area(self, a):
        if a.tags.get("landuse") not in BUILTUP_LANDUSE:
            return
        try:
            rings = []
            for outer in a.outer_rings():
                rings.append([(n.lat, n.lon) for n in outer])
                rings.extend([(n.lat, n.lon) for n in inner] for inner in a.inner_rings(outer))
        except osmium.InvalidLocationError:
            return
        fill(rings, self.cells)

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
    """Pass 2: splits ways at junctions (and built-up edges) and writes segments to tiles."""

    def __init__(self, junction, builtup, writer):
        super().__init__()
        self.junction = junction
        self.builtup = builtup
        self.writer = writer
        self.area_segments = 0

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
        # No limit at all: default of the place, 50 built-up, 80 outside on a two-way road.
        flags = None
        if fwd is None and bwd is None and highway in AREA_CLASSES:
            coords = [(loc.lat, loc.lon) for _, loc in nodes]
            flags = smooth([self.builtup.covers(*c) for c in coords], coords)
        start = 0
        piece = 0
        for k in range(1, len(nodes)):
            edge = flags is not None and flags[k] != flags[k - 1]
            if k == len(nodes) - 1 or nodes[k][0] in self.junction or edge:
                part = nodes[start:k + 1]
                coords = []
                for _, loc in part:
                    coords += [round(loc.lat, 6), round(loc.lon, 6)]
                seg_fwd, seg_bwd, seg_src = fwd, bwd, src
                if flags is not None:
                    seg_fwd = seg_bwd = URBAN_KMH if flags[start] else (RURAL_KMH if not oneway else None)
                    seg_src = SRC_AREA if seg_fwd else None
                    self.area_segments += seg_fwd is not None
                seg_id = w.id * 1000 + piece
                self.writer.write(
                    [seg_id, part[0][0], part[-1][0], oneway, seg_fwd, seg_bwd, seg_src, CLASS_INDEX[highway], name, coords],
                    coords,
                )
                start = k
                piece += 1


def main():
    pbf, out_dir = sys.argv[1], sys.argv[2]
    os.makedirs(out_dir, exist_ok=True)
    counter = JunctionCounter()
    counter.apply_file(pbf, locations=True, idx="flex_mem")
    cells = counter.cells
    raw = len(cells)
    drop_small_patches(cells, MIN_PATCH_CELLS)
    print(f"zones bâties : {len(cells)} cellules de ~110 m ({raw - len(cells)} isolées retirées)")
    writer = TileWriter(out_dir)
    builder = SegmentBuilder(counter.junction, BuiltUp(cells), writer)
    builder.apply_file(pbf, locations=True, idx="flex_mem")
    writer.close()
    print(f"{writer.count} segments ({builder.area_segments} avec la limite par défaut du lieu), {len(writer.files)} tiles")


if __name__ == "__main__":
    main()
