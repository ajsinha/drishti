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

"""The Pivot's presets (config/pivot-presets.yaml; docs/guides/BI_GUIDE.md › Presets: the risk cubes): the MR and CCR cubes are named settings of
the one pivot screen, a source, a lens and a landing, never grids of their own. Read once; the file is the product's, not a person's."""
from __future__ import annotations

from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path

import yaml

FILE = Path(__file__).resolve().parent.parent / "config" / "pivot-presets.yaml"


@dataclass(frozen=True)
class Preset:
    name: str
    title: str
    word: str
    source: str
    lens: str
    landing: str
    help: str
    engine: str = ""
    under_construction: str = ""
    mode: str = "views"
    view: str = ""
    views: tuple = ()
    meanwhile: dict | None = None


@lru_cache(maxsize=1)
def presets() -> dict[str, Preset]:
    data = yaml.safe_load(FILE.read_text(encoding="utf-8")) or {}
    out = {}
    for name, p in (data.get("presets") or {}).items():
        out[str(name)] = Preset(name=str(name), title=str(p.get("title") or name), word=str(p.get("word") or "").upper(), source=str(p.get("source") or ""),
                                lens=str(p.get("lens") or ""), landing=str(p.get("landing") or "source"), help=str(p.get("help") or "pivot"),
                                engine=str(p.get("engine") or ""), under_construction=str(p.get("under_construction") or ""),
                                mode=mode_of(p.get("mode")), view=str(p.get("view") or ""), views=tuple(_view(v) for v in (p.get("views") or [])),
                                meanwhile=p.get("meanwhile"))
    return out


MODES = ("views", "compact", "panes")


def mode_of(word) -> str:
    """How the pivot screen opens (BI_GUIDE.md section 2): views (the View picker in front of the compact line, the default), compact
    (the compact line alone) or panes (the Fields and Build panes); anything else is the default."""
    w = str(word or "").strip().lower()
    return w if w in MODES else "views"


def _view(v: dict) -> dict:
    """A ready-made view as the View picker takes it: id, name, detail and the partial view laid over the landing."""
    out = {"id": str(v.get("id") or v.get("name") or ""), "name": str(v.get("name") or v.get("id") or ""), "detail": str(v.get("detail") or "")}
    if v.get("landing"):
        out["landing"] = True
    for k in ("rows", "columns", "values", "sort", "top", "expandTo", "options", "filters"):
        if k in v:
            out[k] = v[k]
    return out


def get(name: str) -> Preset | None:
    return presets().get((name or "").strip().lower())


def command(words: list[str]) -> str | None:
    """Where a preset's word goes (``CCUBE ...`` -> ``/pivot?preset=ccr``), or None when the words are no preset's: ``<WORD>`` its landing,
    ``<WORD> @owner/name`` a saved view, ``<WORD> <text>`` the text laid over the landing (the preset form of the PIVOT grammar)."""
    from urllib.parse import urlencode
    if words and words[-1].upper() == "<GO>":
        words = words[:-1]
    cube = next((p for p in presets().values() if words and p.word and p.word == words[0].upper()), None)
    if cube is None:
        return None
    where = "/pivot?preset=" + cube.name
    if len(words) == 1:
        return where
    if len(words) == 2 and words[1].startswith("@") and "/" in words[1]:
        return where + "&" + urlencode({"view": words[1][1:]})
    return where + "&" + urlencode({"text": " ".join(words[1:])})
