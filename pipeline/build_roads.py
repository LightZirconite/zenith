#!/usr/bin/env python3
"""Builds speed-limit road tiles from an OSM extract.

Each drivable OSM way is split at junction nodes into segments carrying their speed limit
(explicit tag, French implicit value, or inferred from the road class). Segments are written
to 0.5° tiles: roads_<i>_<j>.json.gz with i = floor(lat * 2), j = floor(lon * 2). A segment
crossing a tile edge is written to every tile it touches (same id), so tiles stand alone.

Roads with no limit at all get 50 deep inside a built-up area (the road's cell and the 8 around
it built up), and no limit elsewhere. Measured on France (13/11/2026) against the tagged roads:
guessing 50 built-up / 80 outside everywhere was right only 60 % of the time; even restricted to
communal roads far from any built-up area, 80 was wrong 4 times in 10 (villages without mapped
landuse, departmental roads back at 90), and 50 on a trunk road in town was nearly always wrong.
Built-up areas come from landuse polygons and from blocks of residential streets, on a ~110 m
grid; small isolated patches are dropped. Such roads are cut where the default changes.

Each run checks that rule against the roads whose limit is tagged (two-way roads of the same
classes), by road class ("--check" prints only that, without writing tiles).

Usage: build_roads.py <extract.osm.pbf> <out_dir> [--check]
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
IMPLICIT = {
    "FR:urban": 50, "FR:rural": 80, "FR:motorway": 130, "FR:zone30": 30, "FR:zone20": 20, "FR:living_street": 20,
    "FR:walk": 6,
}
SRC_EXPLICIT, SRC_IMPLICIT, SRC_INFERRED, SRC_AREA = 0, 1, 2, 3

# Built-up areas (see module doc).
BUILTUP_LANDUSE = {"residential", "retail", "commercial"}
AREA_CLASSES = {"primary", "secondary", "tertiary", "unclassified"}
CELL_LAT, CELL_LON = 1 / 1000, 1 / 700  # ~110 m both ways across France
MIN_PATCH_CELLS = 4  # ~5 ha
STREET_CELLS = 4  # a 3 × 3 block with residential streets in this many cells is built up
URBAN_RING = 1  # cells around the road that must all be built up for 50
MIN_RUN_M = 100  # shorter in/out runs inside a way are noise along a boundary
URBAN_KMH = 50
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
        for key in ("maxspeed:type", "source:maxspeed", "zone:maxspeed", "zone:traffic"):
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


def street_blocks(streets, min_cells):
    """Cells whose 3 × 3 block holds residential streets in at least [min_cells] cells: a street grid."""
    out = set()
    for k in streets:
        for c in (k + di * ROW + dj for di in (-1, 0, 1) for dj in (-1, 0, 1)):
            if c in out:
                continue
            if sum((c + di * ROW + dj) in streets for di in (-1, 0, 1) for dj in (-1, 0, 1)) >= min_cells:
                out.add(c)
    return out


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


def smooth(flags, coords):
    """A run shorter than MIN_RUN_M between two runs of one same other state takes that state."""
    runs, start = [], 0
    for k in range(1, len(flags) + 1):
        if k == len(flags) or flags[k] != flags[start]:
            runs.append((start, k))
            start = k
    out = list(flags)
    for a, b in runs[1:-1]:
        length = sum(math.dist((coords[k][0] * 111_000, coords[k][1] * 78_000), (coords[k + 1][0] * 111_000, coords[k + 1][1] * 78_000))
                     for k in range(max(a - 1, 0), b))
        before, after = flags[a - 1], flags[b] if b < len(flags) else None
        if length < MIN_RUN_M and before == after:
            out[a:b] = [before] * (b - a)
    return out


class Place:
    """Default limit of the place, only where it is clear (see the module doc); None elsewhere."""

    def __init__(self, cells):
        self.cells = cells

    def default(self, lat, lon):
        k = cell_key(lat, lon)
        r = URBAN_RING
        return URBAN_KMH if all(k + di * ROW + dj in self.cells for di in range(-r, r + 1) for dj in range(-r, r + 1)) else None

    def along(self, coords):
        return smooth([self.default(lat, lon) for lat, lon in coords], coords)


class LimitCheck:
    """The default of the place against tagged limits: for each two-way road of AREA_CLASSES with
    one tagged limit, the default is worked out along it as for an untagged road."""

    def __init__(self, place):
        self.place = place
        self.length = {}  # (class, guess, tagged) -> m

    def add(self, highway, limit, coords):
        guesses = self.place.along(coords)
        for k, ((a, b), (c, d)) in enumerate(zip(coords, coords[1:])):
            key = (highway, guesses[k], limit)
            self.length[key] = self.length.get(key, 0) + math.dist((a * 111_000, b * 78_000), (c * 111_000, d * 78_000))

    def report(self):
        print("Contrôle de la limite par défaut du lieu (routes à double sens dont la limite est connue) :")
        for highway in sorted({h for h, _, _ in self.length}, key=CLASSES.index):
            rows = {(g, t): v for (h, g, t), v in self.length.items() if h == highway}
            total = sum(rows.values()) or 1
            guessed = sum(v for (g, _), v in rows.items() if g is not None)
            right = sum(v for (g, t), v in rows.items() if g is not None and g == t)
            print(f"  {highway:13} {total / 1000:9,.0f} km  limite devinée {guessed / total:6.1%}  "
                  f"juste {right / (guessed or 1):6.1%}  "
                  + "  ".join(f"{g}→vrai {t} {v / (guessed or 1):5.1%}" for (g, t), v in sorted(rows.items(), key=str)
                              if g is not None and g != t and v / (guessed or 1) >= 0.01))


class JunctionCounter(osmium.SimpleHandler):
    """Pass 1: finds nodes used more than once by drivable ways, and the built-up areas."""

    def __init__(self):
        super().__init__()
        self.seen = osmium.index.IdSet()
        self.junction = osmium.index.IdSet()
        self.cells = set()
        # Cells crossed by residential streets: a built-up area where no landuse is mapped.
        self.streets = set()

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
        if w.tags.get("highway") in ("residential", "living_street"):
            for n in w.nodes:
                if n.location.valid():
                    self.streets.add(cell_key(n.location.lat, n.location.lon))
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

    def __init__(self, junction, place, writer, check=None):
        super().__init__()
        self.junction = junction
        self.place = place
        self.writer = writer
        self.check = check
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
        if self.check is not None and src == SRC_EXPLICIT and fwd == bwd and oneway == 0 and highway in AREA_CLASSES:
            self.check.add(highway, fwd, [(loc.lat, loc.lon) for _, loc in nodes])
        if self.writer is None:
            return
        if oneway == -1:
            nodes.reverse()
            fwd, bwd = bwd, fwd
            oneway = 1
        name = tags.get("ref") or tags.get("name")
        # No limit at all: default of the place where it is clear (Place), else none.
        flags = None
        if fwd is None and bwd is None and highway in AREA_CLASSES:
            flags = self.place.along([(loc.lat, loc.lon) for _, loc in nodes])
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
                    seg_fwd = seg_bwd = flags[start]
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
    only_check = "--check" in sys.argv[3:]
    counter = JunctionCounter()
    counter.apply_file(pbf, locations=True, idx="flex_mem")
    cells = counter.cells | street_blocks(counter.streets, STREET_CELLS)
    del counter.streets
    raw = len(cells)
    drop_small_patches(cells, MIN_PATCH_CELLS)
    print(f"zones bâties : {len(cells)} cellules de ~110 m ({raw - len(cells)} isolées retirées)")
    place = Place(cells)
    check = LimitCheck(place)
    writer = None
    if not only_check:
        os.makedirs(out_dir, exist_ok=True)
        writer = TileWriter(out_dir)
    builder = SegmentBuilder(counter.junction, place, writer, check)
    builder.apply_file(pbf, locations=True, idx="flex_mem")
    if writer:
        writer.close()
        print(f"{writer.count} segments ({builder.area_segments} avec la limite par défaut du lieu), {len(writer.files)} tiles")
    check.report()


if __name__ == "__main__":
    main()
