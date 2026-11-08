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

"""Admin → Configuration (``/admin/configuration``, 2.9.11.e; docs/guides/OPERATIONS_GUIDE.md, "The configuration editor"): every setting of
docs/admin/CONFIGURATION.md in three tiers (everyday, admin, expert; the tier is the document's Tier column, kept in
tools/settings_schemas.yaml), searchable, each with its value in effect and where it came from (the server's ``GET /admin/settings`` and
this console's own, core/settings_page.py), and a change **proposed**, never written live: a second administrator approves it (the
server's four-eyes proposals). The server's and the engines' keys are written by the server on approval; a console key is written here,
into this console's ``config/application.local.yaml``, by the console the approver uses. Secrets are never shown and never proposed.
"""
from __future__ import annotations

import html
import re
from pathlib import Path

import yaml

from core.settings_page import BEGIN, END, _CELL, _PIECE, _ROW, is_secret, shown

TIERS = (("everyday", "Everyday"), ("admin", "Admin"), ("expert", "Expert"))
PAGE_ROWS = 200
_TAG = re.compile(r"<[^>]+>")


def _text(cell: str) -> str:
    return re.sub(r"\s+", " ", html.unescape(_TAG.sub(" ", cell))).strip()


def editable_area(area_id: str) -> str:
    """The proposal area of a document area (server, engines, console), or "" when its settings are changed elsewhere."""
    if area_id.startswith("the-server"):
        return "server"
    if area_id.startswith("compute-engines"):
        return "engines"
    if area_id.startswith("the-console-applicationyaml"):
        return "console"
    return ""


def rows(page_html: str) -> list[dict]:
    """Every setting of the rendered document: ``{area, area_title, key, type, default, env, applies, meaning, change, tier}``."""
    start = page_html.find(BEGIN)
    end = page_html.find(END)
    if start < 0:
        return []
    gen = page_html[start:end if end > start else len(page_html)]
    out: list[dict] = []
    area = title = ""
    for piece in _PIECE.findall(gen):
        if piece.startswith("<h3"):
            area = re.search(r'id="([^"]*)"', piece).group(1)
            title = _text(piece)
            continue
        found = _ROW.findall(piece)
        heads = [_text(c[2]) for c in _CELL.findall(found[0])] if found else []
        if not heads or heads[0] != "Key" or "Tier" not in heads:
            continue
        at = {h: i for i, h in enumerate(heads)}
        for row in found[1:]:
            cells = [_text(c[2]) for c in _CELL.findall(row)]
            if len(cells) < len(heads):
                continue
            out.append({"area": area, "area_title": title, "key": cells[at["Key"]], "type": cells[at["Type"]],
                        "default": cells[at["Default"]], "env": cells[at["Env var"]], "applies": cells[at["Applies to"]],
                        "meaning": cells[at["Meaning"]], "change": cells[at["Restart or live"]], "tier": cells[at["Tier"]],
                        "edit": editable_area(area)})
    return out


def pick(every: list[dict], q: str = "", tier: str = "", area: str = "") -> list[dict]:
    """The rows a search keeps: every word in the key, environment variable, meaning or area; the tier and area when given."""
    words = [w for w in (q or "").lower().split() if w]
    out = []
    for r in every:
        if tier and r["tier"] != tier:
            continue
        if area and r["area"] != area:
            continue
        hay = " ".join((r["key"], r["env"], r["meaning"], r["area_title"], r["tier"])).lower()
        if all(w in hay for w in words):
            out.append(r)
    return out


def with_effect(r: dict, server: dict, console: dict) -> dict:
    """The row with its value in effect and where it came from (secrets masked), and whether a change may be proposed here."""
    eff = (console if r["edit"] == "console" else server if r["edit"] in ("server", "engines") else {}).get(r["key"])
    secret = is_secret(r["key"]) or bool(eff and eff.get("secret"))
    return {**r, "value": eff.get("value", "") if eff else "", "origin": eff.get("origin", "") if eff else "",
            "source": eff.get("source", "") if eff else "", "secret": secret, "proposable": bool(r["edit"]) and not secret
            and "<" not in r["key"] and "[]" not in r["key"]}


# ---- the console's own file -----------------------------------------------------------------------------------------------------

def _parse(value: str):
    try:
        return yaml.safe_load(value) if value.strip() else ""
    except yaml.YAMLError:
        return value


def write_console_key(path: Path, key: str, value: str, note: str) -> None:
    """An approved console key into the console's application.local.yaml (the dotted key as nested maps), every other key kept."""
    if is_secret(key):
        raise ValueError("a secret is never written from Admin → Configuration")
    data = (yaml.safe_load(path.read_text(encoding="utf-8")) or {}) if path.is_file() else {}
    node = data
    parts = key.split(".")
    for p in parts[:-1]:
        if not isinstance(node.get(p), dict):
            node[p] = {}
        node = node[p]
    node[parts[-1]] = _parse(value)
    head = ("# The console's own settings (read after application.yaml). Admin -> Configuration writes approved changes here; the last: "
            + note.replace("\n", " ") + "\n")
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(path.name + ".writing")
    tmp.write_text(head + yaml.safe_dump(data, sort_keys=False, allow_unicode=True), encoding="utf-8")
    tmp.replace(path)


def shown_value(key: str, value) -> str:
    return shown(key, value)
