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

"""Studio's Summary tab: a YAML Sutra read back as a short page (description, notes, what it matches, the strip,
the panels and the function keys). Derived and read-only; the template escapes everything the author wrote."""
from __future__ import annotations

from typing import Any

import yaml


def _text(v: Any) -> str:
    return "" if v is None else str(v)


def _list(v: Any) -> list:
    return v if isinstance(v, list) else []


def _map(v: Any) -> dict:
    return v if isinstance(v, dict) else {}


def _columns(cols: Any) -> list[str]:
    return [_text(c.get("label") or c.get("bind")) for c in _list(cols) if isinstance(c, dict)]


def _panel(p: dict) -> dict:
    body = _map(p.get("body"))
    return {"id": _text(p.get("id")), "kind": _text(p.get("kind")), "title": _text(p.get("title")),
            "description": _text(p.get("description")), "key": _text(p.get("key")), "area": _text(p.get("area") or "main"),
            "rows": _text(p.get("rows") or p.get("each")),
            "columns": _columns(p.get("columns") or p.get("fields") or body.get("columns") or body.get("fields")),
            "body": _text(body.get("kind"))}


def summarize(text: str) -> dict:
    """The Sutra as plain data for the template; raises ValueError when the YAML cannot be read."""
    try:
        doc = yaml.safe_load(text or "")
    except yaml.YAMLError as e:
        mark = getattr(e, "problem_mark", None)
        raise ValueError(f"line {mark.line + 1}: {getattr(e, 'problem', e)}" if mark else str(e)) from e
    if doc is None:
        doc = {}
    if not isinstance(doc, dict):
        raise ValueError("a Sutra is a YAML map of keys (rachana, sutra, version, match, …)")
    match, title = _map(doc.get("match")), _map(doc.get("title"))
    return {
        "sutra": _text(doc.get("sutra")), "version": _text(doc.get("version")), "rachana": _text(doc.get("rachana")),
        "domain": _text(doc.get("domain")), "description": _text(doc.get("description")), "notes": _text(doc.get("notes")),
        "match": {"kind": _text(match.get("kind")), "where": _text(match.get("where")), "priority": _text(match.get("priority"))},
        "title": {k: _text(title.get(k)) for k in ("pill", "id", "with")},
        "strip": [{"label": _text(f.get("label") or f.get("bind")), "bind": _text(f.get("bind")), "fmt": _text(f.get("fmt"))}
                  for f in _list(doc.get("strip")) if isinstance(f, dict)],
        "panels": [_panel(p) for p in _list(doc.get("panels")) if isinstance(p, dict)],
        "fkeys": [{"key": _text(k), "action": _text(v)} for k, v in _map(doc.get("keys")).items()],
    }
