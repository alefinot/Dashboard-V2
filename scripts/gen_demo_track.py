#!/usr/bin/env python3
"""Generate src/demo_track.h - the Monza circuit centreline the demo GPS drives.

Demo mode synthesizes NMEA sentences; until 2026-11-15 it integrated speed x a
slowly rotating course, so the "car" wandered across Munich. It now follows the
real Autodromo Nazionale di Monza (F1 layout, 5.793 km). This script is the
source of that table:

  1. fetch every highway=raceway way in the Monza bbox from OpenStreetMap
     (Overpass API, retried - the public endpoints queue requests);
  2. stitch the F1 loop by shared endpoints, starting at the way named
     "Rettifilo di partenza". The old Sopraelevata oval (historic=ruins), the
     pit lane, the junior loop and the motorcycle-only first chicane are not
     part of the race lap and are skipped;
  3. assert the chain closes and measures a Monza lap (5.70-5.90 km);
  4. resample the centreline at uniform arc length (DEMO_TRACK_N points) so the
     firmware can sample it by distance with an O(1) index + lerp;
  5. write src/demo_track.h with its provenance.

Run by hand, never as part of the build:

    python scripts/gen_demo_track.py
    python scripts/gen_demo_track.py --from-json monza-overpass.json   # offline

Track data (c) OpenStreetMap contributors, ODbL 1.0 -
https://www.openstreetmap.org/copyright
"""

import argparse
import json
import math
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT_PATH = ROOT / "src" / "demo_track.h"

# Monza, tight enough to exclude the rest of the royal park, wide enough to hold
# the whole circuit (the loop spans ~2.2 x 2.2 km).
BBOX = (45.600, 9.250, 45.645, 9.320)
QUERY = '[out:json][timeout:90];way["highway"="raceway"](%s,%s,%s,%s);out tags geom;' % BBOX

ENDPOINTS = [
    "https://overpass-api.de/api/interpreter",
    "https://overpass.openstreetmap.fr/api/interpreter",
]
USER_AGENT = "DashboardPlusPlus-demo-track/1.0 (firmware build tool)"

START_NAME = "Rettifilo di partenza"
# Parts of the OSM Monza map that are not the car race lap. Matched on the way's
# name; a raceway without one of these names is a lap section.
SKIP_NAMES = ("pit lane", "sopraelevata", "raccordo", "tondo")

# Uniform resampling. 180 chords of ~32 m keep the shape (chord shortening is
# ~0.3 % of the lap) while staying a 1.5 KB const table in flash.
DEMO_TRACK_N = 180

MIN_LAP_M = 5700.0
MAX_LAP_M = 5900.0

EARTH_R = 6371000.0


class TrackError(Exception):
    pass


def haversine_m(a, b):
    """Great-circle distance in metres between (lat, lon) pairs."""
    lat1, lon1 = math.radians(a[0]), math.radians(a[1])
    lat2, lon2 = math.radians(b[0]), math.radians(b[1])
    dlat = lat2 - lat1
    dlon = lon2 - lon1
    h = math.sin(dlat / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin(dlon / 2) ** 2
    return 2 * EARTH_R * math.asin(math.sqrt(h))


def fetch_overpass():
    """POST the raceway query, retrying across the public endpoints."""
    body = urllib.parse.urlencode({"data": QUERY}).encode("utf-8")
    last_error = None
    for attempt in range(3):
        for endpoint in ENDPOINTS:
            try:
                req = urllib.request.Request(
                    endpoint,
                    data=body,
                    headers={
                        "Content-Type": "application/x-www-form-urlencoded",
                        "Accept": "application/json",
                        "User-Agent": USER_AGENT,
                    },
                )
                with urllib.request.urlopen(req, timeout=120) as resp:
                    payload = json.loads(resp.read().decode("utf-8"))
                elements = payload.get("elements", [])
                if elements:
                    return elements
                last_error = "empty response from " + endpoint
            except Exception as exc:  # network/HTTP/JSON - try the next endpoint
                last_error = "%s: %s" % (endpoint, exc)
        time.sleep(5 * (attempt + 1))
    raise TrackError("Overpass fetch failed: %s" % last_error)


def load_ways(elements):
    """Keep drawable, race-lap ways as dicts of id/name/points."""
    ways = []
    for el in elements:
        tags = el.get("tags") or {}
        geom = el.get("geometry") or []
        if el.get("type") != "way" or len(geom) < 2:
            continue
        name = (tags.get("name") or "").strip()
        low = name.lower()
        if tags.get("historic") == "ruins":
            continue
        if any(skip in low for skip in SKIP_NAMES):
            continue
        if "motorcycle" in (tags.get("note") or "").lower():
            continue
        ways.append({
            "id": el["id"],
            "name": name,
            "pts": [(p["lat"], p["lon"]) for p in geom],
        })
    if not ways:
        raise TrackError("no raceway ways in the response")
    return ways


def pt_key(pt):
    return "%.6f,%.6f" % (pt[0], pt[1])


def stitch_loop(ways):
    """Follow shared endpoints from the start way until the loop closes."""
    start = next((w for w in ways if w["name"] == START_NAME), None)
    if start is None:
        raise TrackError('start way "%s" not found' % START_NAME)

    by_start = {}
    for way in ways:
        by_start.setdefault(pt_key(way["pts"][0]), []).append(way)

    chain = [start]
    used = {start["id"]}
    cur = start
    for _ in range(64):
        key = pt_key(cur["pts"][-1])
        nxt = next((w for w in by_start.get(key, []) if w["id"] not in used), None)
        if nxt is None:
            break
        chain.append(nxt)
        used.add(nxt["id"])
        cur = nxt

    if pt_key(cur["pts"][-1]) != pt_key(start["pts"][0]):
        raise TrackError(
            "chain does not close after %d ways (ends at %s, started at %s)"
            % (len(chain), pt_key(cur["pts"][-1]), pt_key(start["pts"][0]))
        )
    if len(chain) < 10:
        raise TrackError("suspiciously short chain: %d ways" % len(chain))
    return chain


def flatten(chain):
    """Concatenate the chain's points, dropping the duplicated joints and the
    repeated first point at the end (the loop is closed by wrap-around)."""
    pts = []
    for way in chain:
        for pt in way["pts"]:
            if not pts or pt_key(pts[-1]) != pt_key(pt):
                pts.append(pt)
    if pt_key(pts[0]) == pt_key(pts[-1]):
        pts.pop()
    return pts


def path_length_m(pts):
    return sum(haversine_m(pts[i], pts[(i + 1) % len(pts)]) for i in range(len(pts)))


def resample(pts, n):
    """n points at exactly uniform arc length around the closed loop."""
    cum = [0.0]
    for i in range(len(pts)):
        cum.append(cum[-1] + haversine_m(pts[i], pts[(i + 1) % len(pts)]))
    lap = cum[-1]
    out = []
    seg = 0
    for i in range(n):
        target = lap * i / n
        while seg + 1 < len(cum) - 1 and cum[seg + 1] <= target:
            seg += 1
        a = pts[seg]
        b = pts[(seg + 1) % len(pts)]
        span = cum[seg + 1] - cum[seg]
        f = 0.0 if span <= 0.0 else (target - cum[seg]) / span
        out.append((a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f))
    return out, lap


def sketch(pts, width=64, height=26):
    """Rough ASCII plan view - a human check that this is the Monza shape."""
    lat0 = sum(p[0] for p in pts) / len(pts)
    kx = math.cos(math.radians(lat0))
    xs = [p[1] * kx for p in pts]
    ys = [p[0] for p in pts]
    minx, maxx, miny, maxy = min(xs), max(xs), min(ys), max(ys)
    grid = [[" "] * width for _ in range(height)]
    for x, y in zip(xs, ys):
        col = round((x - minx) / (maxx - minx) * (width - 1))
        row = height - 1 - round((y - miny) / (maxy - miny) * (height - 1))
        grid[row][col] = "#"
    return "\n".join("".join(row) for row in grid)


def write_header(pts, lap_m, chain, step_m):
    way_ids = ", ".join(str(w["id"]) for w in chain)
    lines = []
    lines.append("// Auto-generated by scripts/gen_demo_track.py - do not edit by hand.")
    lines.append("//")
    lines.append("// Autodromo Nazionale di Monza, F1 layout: the demonstration GPS stream in")
    lines.append("// src/sensors.cpp (demoTrackSample) drives this centreline instead of an")
    lines.append("// arbitrary course. Starts at the entry to the Rettifilo main straight and")
    lines.append("// runs in the direction of travel.")
    lines.append("//")
    lines.append("// Source: OpenStreetMap raceway ways %s," % way_ids)
    lines.append("// stitched by shared endpoints and resampled at uniform arc length.")
    lines.append("// Data (c) OpenStreetMap contributors, ODbL 1.0 -")
    lines.append("// https://www.openstreetmap.org/copyright")
    lines.append("//")
    lines.append("// %d points, %.1f m lap, %.2f m between points. Uniform spacing is what makes" %
                 (len(pts), lap_m, step_m))
    lines.append("// demoTrackSample() an index plus a lerp: no search table, no allocation.")
    lines.append("#pragma once")
    lines.append("")
    lines.append("struct DemoTrackPoint {")
    lines.append("  float lat;")
    lines.append("  float lon;")
    lines.append("};")
    lines.append("")
    lines.append("static const float DEMO_TRACK_LAP_M = %.1ff;" % lap_m)
    lines.append("static const float DEMO_TRACK_STEP_M = %.4ff;" % step_m)
    lines.append("static const int DEMO_TRACK_N = %d;" % len(pts))
    lines.append("")
    lines.append("static const DemoTrackPoint DEMO_TRACK[DEMO_TRACK_N] = {")
    for lat, lon in pts:
        lines.append("    {%.6ff, %.6ff}," % (lat, lon))
    lines.append("};")
    lines.append("")
    OUT_PATH.write_text("\n".join(lines), encoding="utf-8", newline="\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--from-json", type=Path,
                        help="read a previously saved Overpass JSON response instead of fetching")
    args = parser.parse_args()

    if args.from_json:
        payload = json.loads(args.from_json.read_text(encoding="utf-8"))
        elements = payload.get("elements", [])
    else:
        elements = fetch_overpass()

    ways = load_ways(elements)
    chain = stitch_loop(ways)
    pts = flatten(chain)

    raw_lap = path_length_m(pts)
    if not (MIN_LAP_M <= raw_lap <= MAX_LAP_M):
        raise TrackError("stitched lap is %.0f m, outside %.0f-%.0f m - the OSM mapping "
                         "changed or the wrong branch was followed" %
                         (raw_lap, MIN_LAP_M, MAX_LAP_M))

    resampled, lap_m = resample(pts, DEMO_TRACK_N)
    step_m = lap_m / DEMO_TRACK_N
    chord_lap = path_length_m(resampled)
    if chord_lap < MIN_LAP_M or chord_lap > MAX_LAP_M:
        raise TrackError("resampled lap is %.0f m, outside the Monza window" % chord_lap)

    write_header(resampled, lap_m, chain, step_m)

    print("Monza demo track")
    print("  ways stitched : %d (%s)" % (len(chain), ", ".join(w["name"] or "unnamed" for w in chain)))
    print("  raw points    : %d, raw lap %.1f m" % (len(pts), raw_lap))
    print("  table         : %d points, lap %.1f m, step %.2f m, chords %.1f m" %
          (DEMO_TRACK_N, lap_m, step_m, chord_lap))
    print("  start         : %.6f, %.6f" % resampled[0])
    print("  written       : %s" % OUT_PATH.relative_to(ROOT))
    print()
    print(sketch(resampled))
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except TrackError as exc:
        print("FAILED: %s" % exc, file=sys.stderr)
        sys.exit(1)
