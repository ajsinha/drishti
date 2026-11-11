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

"""The house on Home (HOW_IT_FITS.md "The house", docs/how-it-fits/kernel-and-rooms.svg): the kernel with the running distribution's
loaded modules plugged around it as rooms, and empty ``.dmod`` sockets for the rest. Drawn on the server as one SVG by the shared
partial ``_macros/house.html`` from :func:`picture`; no script. A room's hue is its place in the site's module list, so a module keeps
its hue in every distribution of the site."""
from __future__ import annotations

import math
from functools import lru_cache
from pathlib import Path

import yaml

from core import distribution as _dist
from core import modules as _modules

HUES = 6                                   # .house-h0 … .house-h5 (drishti_theme.css): the theme's link, accent, violet, ok, teal, neg


def universe(editions) -> list[str]:
    """Every module this site knows, in the server's (requirement) order: the server's list, else the manifests folder."""
    known = editions.server_modules
    if known is None:
        known = _manifests(str(editions.modules_dir))
    return [m for m in known if m != _modules.CORE]


@lru_cache(maxsize=4)
def _manifests(folder: str) -> dict:
    return _dist.module_manifests(Path(folder))


@lru_cache(maxsize=64)
def _manifest_title(folder: str, mid: str) -> str:
    """A module's ``title`` from its manifest under the manifests folder ('' when there is none)."""
    root = Path(folder)
    for d in sorted(p for p in root.iterdir() if p.is_dir()) if root.is_dir() else []:
        for f in (d / "module.yaml", d / "src" / "dmod" / "module.yaml", d / "dmod" / "module.yaml"):
            if f.is_file():
                try:
                    y = yaml.safe_load(f.read_text(encoding="utf-8")) or {}
                except (OSError, yaml.YAMLError):
                    continue
                if str(y.get("id") or "").strip() == mid:
                    return str(y.get("title") or "")
    return ""


def rooms(editions) -> list[dict]:
    """The rooms: the running distribution's modules that are loaded and on here, each with its title and hue."""
    d = editions.distribution
    everyone = universe(editions)
    person = _modules.person()
    loaded = person.get("loaded") if person is not None and person.get("loaded") else _modules.state().loaded
    if d.all:
        ids = [m for m in everyone if loaded is None or m in loaded]
        ids += sorted(m for m in (loaded or ()) if m not in ids and m != _modules.CORE)
    else:
        ids = [m for m in d.modules if loaded is None or m in loaded]
    titles = (person or {}).get("titles") or {}
    out = []
    for m in ids:
        if m == _modules.CORE or not _modules.module_on(m):
            continue
        title = str(titles.get(m) or "") if titles.get(m) not in (None, "", m) else ""
        title = title or _manifest_title(str(editions.modules_dir), m) or m.replace("-", " ")
        out.append({"id": m, "title": title, "hue": (everyone.index(m) if m in everyone else len(out)) % HUES})
    return out


def _lines(title: str, width: int = 15) -> list[str]:
    """A title in at most two lines of about ``width`` characters (the second cut with an ellipsis)."""
    words, lines = title.split(), [""]
    for w in words:
        if lines[-1] and len(lines[-1]) + 1 + len(w) > width:
            if len(lines) == 2:
                lines[-1] = lines[-1].rstrip() + "…"
                return lines
            lines.append(w)
        else:
            lines[-1] = (lines[-1] + " " + w).strip()
    return lines


def picture(rooms_: list[dict]) -> dict:
    """Where everything goes: the rooms and empty sockets on an ellipse round the kernel (top first, clockwise; at least four places),
    the small sockets between them on a ring, the roof over it all."""
    n = len(rooms_)
    k = max(4, n)
    rx, ry = (176, 128) if k <= 6 else (236, 176)
    tw, th = 136, 64
    w = 2 * (rx + tw // 2 + 12)
    cx, cy = w / 2, ry + th / 2 + 62
    h = cy + ry + th / 2 + 14
    places = []
    for i in range(k):
        a = math.radians(-90 + i * 360 / k)
        x, y = cx + rx * math.cos(a), cy + ry * math.sin(a)
        r = rooms_[i] if i < n else None
        places.append({"x": round(x - tw / 2, 1), "y": round(y - th / 2, 1), "cx": round(x, 1), "cy": round(y, 1), "w": tw, "h": th,
                       "room": r, "lines": _lines(r["title"]) if r else []})
    ring = min(rx, ry) + 18
    sockets = [{"cx": round(cx + ring * math.cos(math.radians(-90 + (i + .5) * 360 / k)), 1),
                "cy": round(cy + ring * math.sin(math.radians(-90 + (i + .5) * 360 / k)), 1)} for i in range(k)]
    top = cy - ry - th / 2
    roof = f"M{cx - 150:.0f} {top + 2:.0f} L{cx:.0f} {top - 54:.0f} L{cx + 150:.0f} {top + 2:.0f}"
    return {"w": round(w), "h": round(h), "cx": cx, "cy": cy, "ring": ring, "places": places, "sockets": sockets, "roof": roof,
            "rooms": rooms_}
