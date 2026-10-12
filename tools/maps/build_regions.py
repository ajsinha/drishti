#!/usr/bin/env python3
# Project Drishti · Any data. Any domain. One grammar.
#
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
# All rights reserved.
#
# PROPRIETARY AND CONFIDENTIAL.
#
# This file is the confidential and proprietary property of Ashutosh Sinha.
# Unauthorised copying, use, modification, distribution or disclosure of this
# file, via any medium, is strictly prohibited except with the express prior
# written permission of the copyright holder.
#
# See the LICENSE file in the root of this repository for the full terms.

"""Builds the vendored region maps: one simplified GeoJSON file per country, from Natural Earth admin-1.

Source   Natural Earth 1:10m Admin 1 - States, Provinces (public domain, https://www.naturalearthdata.com/), fetched from the
         natural-earth-vector repository on GitHub.
Output   drishti-console/web/static/vendor/echarts/maps/regions/<ISO3>.json   one FeatureCollection per country
         drishti-console/web/static/vendor/echarts/maps/world-points.json     countries too small for the 1:110m world map
         drishti-console/web/static/vendor/echarts/maps/regions/README.md      source, licence and sizes
Usage    python3 tools/maps/build_regions.py [--cache DIR] [--budget-kb 1200]

Each feature is {"properties": {"code": "AU-NSW", "name": "New South Wales", "iso3": "AUS"}, "geometry": ...}. Codes are ISO 3166-2
(Natural Earth's own placeholder for a region without one, such as AU-X02~, is kept without the tilde). Regions that are tiny at
the country's scale are written as Point features ("point": true in properties) so they stay clickable. Simplification is
Douglas-Peucker, pure Python, with a tolerance relative to the country's size, raised until the file fits the budget.
Nothing is fetched at run time by the console: this tool is run by a developer and the files are committed.
"""
import argparse
import json
import sys
import urllib.request
from pathlib import Path

URL = "https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/ne_10m_admin_1_states_provinces.geojson"
ROOT = Path(__file__).resolve().parents[2]
MAPS = ROOT / "drishti-console/web/static/vendor/echarts/maps"
OUT = MAPS / "regions"
MAX_FILE = 1_500_000
MAX_TOTAL = 15_000_000


def fetch(cache: Path) -> dict:
    cache.mkdir(parents=True, exist_ok=True)
    f = cache / "ne_10m_admin_1_states_provinces.geojson"
    if not f.exists():
        print("downloading", URL)
        with urllib.request.urlopen(URL, timeout=120) as r:
            f.write_bytes(r.read())
    return json.loads(f.read_text(encoding="utf-8"))


def dp(points: list, eps: float) -> list:
    """Douglas-Peucker on an open polyline, iterative; keeps the first and last points."""
    n = len(points)
    if n < 3:
        return points
    keep = [False] * n
    keep[0] = keep[-1] = True
    stack = [(0, n - 1)]
    e2 = eps * eps
    while stack:
        a, b = stack.pop()
        ax, ay = points[a]
        bx, by = points[b]
        dx, dy = bx - ax, by - ay
        den = dx * dx + dy * dy
        best, idx = -1.0, -1
        for i in range(a + 1, b):
            px, py = points[i]
            if den == 0:
                d = (px - ax) ** 2 + (py - ay) ** 2
            else:
                t = ((px - ax) * dx + (py - ay) * dy) / den
                t = 0 if t < 0 else 1 if t > 1 else t
                d = (px - ax - t * dx) ** 2 + (py - ay - t * dy) ** 2
            if d > best:
                best, idx = d, i
        if best > e2:
            keep[idx] = True
            stack.append((a, idx))
            stack.append((idx, b))
    return [p for p, k in zip(points, keep) if k]


def area(ring: list) -> float:
    s = 0.0
    for i in range(len(ring) - 1):
        s += ring[i][0] * ring[i + 1][1] - ring[i + 1][0] * ring[i][1]
    return abs(s) / 2


def polygons(geom: dict) -> list:
    return [geom["coordinates"]] if geom["type"] == "Polygon" else list(geom["coordinates"])


def bbox(polys: list):
    xs = [p[0] for poly in polys for p in poly[0]]
    ys = [p[1] for poly in polys for p in poly[0]]
    return min(xs), min(ys), max(xs), max(ys)


def code_of(props: dict) -> str:
    c = (props.get("iso_3166_2") or "").replace("~", "")
    return c or "%s-%s" % ((props.get("iso_a2") or props["adm0_a3"])[:3], props.get("adm1_code", "X")[-4:])


def build_country(iso3: str, feats: list, eps_rel: float, digits: int) -> dict:
    merged = {}
    for ft in feats:
        p = ft["properties"]
        c = code_of(p)
        m = merged.setdefault(c, {"name": p.get("name") or c, "polys": [], "lat": p.get("latitude"), "lon": p.get("longitude")})
        m["polys"].extend(polygons(ft["geometry"]))
    allpolys = [q for m in merged.values() for q in m["polys"]]
    x0, y0, x1, y1 = bbox(allpolys)
    ext = max(x1 - x0, y1 - y0, 0.05)
    eps = ext * eps_rel
    tiny = ext * 0.012            # a region whose largest side is under 1.2% of the country is a point
    out = []
    for c, m in merged.items():
        bx0, by0, bx1, by1 = bbox(m["polys"])
        polys = []
        for poly in m["polys"]:
            rings = []
            for i, ring in enumerate(poly):
                r = dp([(round(x, digits), round(y, digits)) for x, y in ring], eps)
                if len(r) >= 4 and (i == 0 or area(r) > eps * eps * 4):
                    rings.append([list(p) for p in r])
                elif i == 0:
                    break
            if rings and (area(rings[0]) > eps * eps * 3 or not polys):
                polys.append(rings)
        props = {"code": c, "name": m["name"], "iso3": iso3}
        if not polys or max(bx1 - bx0, by1 - by0) < tiny:
            lon = m["lon"] if m["lon"] is not None else (bx0 + bx1) / 2
            lat = m["lat"] if m["lat"] is not None else (by0 + by1) / 2
            props["point"] = True
            out.append({"type": "Feature", "properties": props, "geometry": {"type": "Point", "coordinates": [round(lon, 3), round(lat, 3)]}})
        else:
            geom = {"type": "Polygon", "coordinates": polys[0]} if len(polys) == 1 else {"type": "MultiPolygon", "coordinates": polys}
            out.append({"type": "Feature", "properties": props, "geometry": geom})
    return {"type": "FeatureCollection", "properties": {"iso3": iso3, "bbox": [round(x0, 3), round(y0, 3), round(x1, 3), round(y1, 3)]}, "features": out}


def dump(fc: dict) -> bytes:
    return json.dumps(fc, separators=(",", ":"), ensure_ascii=False).encode("utf-8")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--cache", default=str(Path.home() / ".cache/drishti-maps"))
    ap.add_argument("--budget-kb", type=int, default=1200)
    a = ap.parse_args()
    data = fetch(Path(a.cache))
    by = {}
    names = {}
    for ft in data["features"]:
        p = ft["properties"]
        by.setdefault(p["adm0_a3"], []).append(ft)
        names[p["adm0_a3"]] = (p.get("admin"), p.get("iso_a2"))
    world = json.loads((MAPS / "world-countries.json").read_text(encoding="utf-8"))
    world3 = {f["properties"]["iso3"] for f in world["features"]}
    OUT.mkdir(parents=True, exist_ok=True)
    for old in OUT.glob("*.json"):
        old.unlink()
    sizes = {}
    for iso3, feats in sorted(by.items()):
        rel, digits = 0.0004, 3
        while True:
            body = dump(build_country(iso3, feats, rel, digits))
            if len(body) <= a.budget_kb * 1000 or rel > 0.2:
                break
            rel *= 1.6
            digits = 2
        (OUT / ("%s.json" % iso3)).write_bytes(body)
        sizes[iso3] = len(body)
    points = []
    for iso3, feats in sorted(by.items()):
        if iso3 in world3:
            continue
        lons = [f["properties"]["longitude"] for f in feats if f["properties"].get("longitude") is not None]
        lats = [f["properties"]["latitude"] for f in feats if f["properties"].get("latitude") is not None]
        if lons and lats:
            nm, i2 = names[iso3]
            points.append({"iso3": iso3, "iso2": i2 if i2 and i2 != "-99" else None, "name": nm,
                           "lon": round(sum(lons) / len(lons), 3), "lat": round(sum(lats) / len(lats), 3)})
    (MAPS / "world-points.json").write_text(json.dumps(points, separators=(",", ":"), ensure_ascii=False), encoding="utf-8")
    total = sum(sizes.values())
    top = sorted(sizes.items(), key=lambda kv: -kv[1])[:8]
    readme = f"""# Region maps

One GeoJSON file per country (`<ISO3>.json`): the country's regions (states, provinces, counties) with ISO 3166-2 codes
(`AU-NSW`), written by `tools/maps/build_regions.py`. The console loads a file only when a map drills into that country.

| | |
|---|---|
| Source | Natural Earth 1:10m Admin 1 - States, Provinces (<https://www.naturalearthdata.com/downloads/10m-cultural-vectors/>), from <{URL}> |
| Licence | Public domain (Natural Earth terms of use: no permission needed, no attribution required; credited in THIRD-PARTY-NOTICES.md) |
| Processing | grouped by country (`adm0_a3`), features with the same ISO 3166-2 code merged, rings simplified with Douglas-Peucker (tolerance relative to the country's size, raised until the file is at most {a.budget_kb} KB), coordinates rounded to 2 or 3 decimals, regions under 1.2% of the country's size written as Point features (`point: true`) so they stay clickable |
| Files | {len(sizes)} countries |
| Total size | {total / 1e6:.1f} MB (limit 15 MB) |
| Largest | {', '.join('%s %d KB' % (k, v // 1000) for k, v in top)} (limit 1.5 MB each) |

`../world-points.json` lists the {len(points)} countries and territories the 1:110m world map has no shape for (Cayman Islands,
Bermuda ...) with one point each, so a map can draw them as clickable dots.

Natural Earth's placeholder codes for regions with no ISO 3166-2 code (`AU-X02~`) are kept without the tilde (`AU-X02`).
Regenerate with `python3 tools/maps/build_regions.py` (about a minute; downloads the 40 MB source once into `~/.cache/drishti-maps`).
"""
    (OUT / "README.md").write_text(readme, encoding="utf-8")
    print("%d files, %.1f MB, largest %s" % (len(sizes), total / 1e6, top[0]))
    if total > MAX_TOTAL or top[0][1] > MAX_FILE:
        print("over the limits", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
