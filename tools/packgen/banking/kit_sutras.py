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

"""Sutra Next for generated Sutras (docs/architecture/SUTRA_NEXT.md, open question 4): the generators write a panel as a use of a
Drishti UI kit fragment (config/ui-kit/fragments) when one draws exactly that panel, and give every Sutra a generated doc:
docstring (summary, description, params, examples, since) built from the taxonomy and the kind's metadata.

A panel becomes a fragment use only when the fragment draws it exactly: the same kind, the same options, nothing the fragment
cannot say (columns, series, options, markers, pivots stay written in place). A parameter equal to the fragment's default is left
out of the use; an option the panel does not set is passed as '' (the fragment then leaves the key out, as the inline panel did).
"""
from __future__ import annotations

import json
import re
import textwrap
from functools import lru_cache
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[3]
KIT = ROOT / "config" / "ui-kit" / "fragments"
SINCE = "2.7.0"
SUMMARY_MAX = 120
CURVE_TABLE = ("Tenor", "Instrument", "Quote", "Zero", "DF")


@lru_cache(maxsize=None)
def defaults(name: str, version: int = 1) -> dict[str, str]:
    """A kit fragment's parameters and their defaults, read from its file (the kit is the one source)."""
    text = (KIT / f"{name}.v{version}.fragment.yaml").read_text(encoding="utf-8")
    block = re.search(r"(?ms)^params:\n(.*?)(?=^\S)", text).group(1)
    return {k: "" if v is None else str(v) for k, v in (yaml.safe_load(block) or {}).items()}


def use(name: str, values: dict, q) -> str | None:
    """`{ use: name@1, with: {...} }` with only the parameters that differ from the defaults; None when a value has no parameter."""
    d = defaults(name)
    if any(k not in d for k in values):
        return None
    w = {k: "" if v is None else str(v) for k, v in values.items()}
    text = (KIT / f"{name}.v1.fragment.yaml").read_text(encoding="utf-8")
    for k, v in w.items():      # the fragment fills %{k} as text: a value must read the same there as written in place
        if '"' in v or "\\" in v or (f'"%{{{k}}}"' not in text and v != "" and q(v) != v):
            return None
    w = {k: v for k, v in w.items() if v != d[k]}
    if not w:
        return f"  - {{ use: {name}@1 }}"
    return f"  - {{ use: {name}@1, with: {{ " + ", ".join(f"{k}: {q(v) if v != '' else chr(39) * 2}" for k, v in w.items()) + " } }"


def _area(p) -> str:
    return "" if p.area == "main" else p.area


def _plain(p, *allowed: str) -> bool:
    """Whether the panel sets nothing beyond what a simple fragment carries (its common keys plus `allowed`)."""
    extra = {"rows": p.rows, "columns": p.columns, "x": p.x, "y": p.y, "series": p.series, "label": p.label, "value": p.value,
             "fmt": p.fmt, "tone": p.tone, "limit": p.limit, "opts": p.opts, "pivot": p.pivot}
    return all(not v for k, v in extra.items() if k not in allowed)


def panel_use(p, q, tuned: bool = False) -> str | None:
    """The fragment use that draws this panel exactly, or None (the panel stays written in place)."""
    if tuned:
        return None
    common = {"id": p.id, "title": p.title, "code": p.code, "key": p.key, "area": _area(p)}
    if p.kind == "line" and _plain(p, "rows", "x", "y", "fmt") and p.rows and p.x and p.y:
        return use("series-line", {**common, "rows": p.rows, "x": p.x, "y": p.y, "fmt": p.fmt}, q)
    if p.kind == "hbar" and _plain(p, "rows", "label", "value", "fmt", "tone") and p.rows and p.label and p.value:
        return use("ranked-bars", {**common, "rows": p.rows, "label": p.label, "value": p.value, "fmt": p.fmt, "tone": p.tone}, q)
    if p.kind == "kv" and _plain(p, "rows") and p.rows:
        return use("field-list", {**common, "rows": p.rows}, q)
    t = curve_table(p)
    if t is not None:
        return use("curve-table", {**common, **t}, q)
    return None


def curve_table(p) -> dict | None:
    """A table of a curve's pillars (Tenor, Instrument, Quote, Zero, DF, nothing toned or totalled): curve-table's parameters."""
    if p.kind != "table" or not _plain(p, "rows", "columns") or tuple(c[0] for c in p.columns) != CURVE_TABLE:
        return None
    cols = [c + (None,) * (5 - len(c)) for c in p.columns]
    if any(c[3] or c[4] or not str(c[1]).startswith("@.") for c in cols) or cols[0][2] or cols[1][2]:
        return None
    f = [str(c[1])[2:] for c in cols]
    return {"rows": p.rows, "tenor": f[0], "instrument": f[1], "quote": f[2], "quote-fmt": cols[2][2] or "", "zero": f[3],
            "zero-fmt": cols[3][2] or "", "df": f[4], "df-fmt": cols[4][2] or ""}


def curve_overview(line, table, q) -> str | None:
    """A zero-rate line followed by its pillars over the same rows: one curve-overview use, when it says both exactly."""
    t = curve_table(table)
    if t is None or line.kind != "line" or not _plain(line, "rows", "x", "y", "fmt") or line.rows != t["rows"] \
            or line.x != t["tenor"] or line.y != t["zero"] or (line.fmt or "") != t["zero-fmt"] or line.area != "main" \
            or table.area != "main" or table.code:
        return None
    inner = defaults("curve-table")
    if any(t[k] != inner[k] for k in ("instrument", "quote", "quote-fmt", "df", "df-fmt")):
        return None
    return use("curve-overview", {"rows": t["rows"], "tenor": t["tenor"], "zero": t["zero"], "zero-fmt": t["zero-fmt"],
                                  "curve-id": line.id, "curve-title": line.title, "curve-code": line.code, "curve-key": line.key,
                                  "table-id": table.id, "table-title": table.title, "table-key": table.key}, q)


def panel_uses(panels, q, tuned=frozenset()) -> list[str | None]:
    """Per panel its fragment use (None: written in place); a curve's line and pillars become one curve-overview (the second None
    is then the empty string: nothing written)."""
    out: list[str | None] = []
    i = 0
    while i < len(panels):
        p = panels[i]
        nxt = panels[i + 1] if i + 1 < len(panels) else None
        both = curve_overview(p, nxt, q) if nxt is not None and p.id not in tuned and nxt.id not in tuned else None
        if both:
            out += [both, ""]
            i += 2
            continue
        out.append(panel_use(p, q, p.id in tuned))
        i += 1
    return out


def source_footer(p, q) -> str | None:
    """A screen's last panel, its data source and licence as a field list, followed by how the view was built and its links: one
    source-footer use, when the field list has no code or key of its own."""
    if p.kind != "kv" or not _plain(p, "rows") or p.rows != defaults("source-footer")["source-rows"] or p.code or p.key:
        return None
    return use("source-footer", {"source-id": p.id, "source-title": p.title, "source-rows": p.rows, "source-area": _area(p)}, q)


def summary(text: str) -> str:
    """One line of at most 120 characters: the text, or its first sentence, or the text cut at a word."""
    text = " ".join(text.split())
    if len(text) <= SUMMARY_MAX:
        return text
    first = re.split(r"(?<=[.;:])\s", text)[0]
    if len(first) <= SUMMARY_MAX:
        return first
    return text[:SUMMARY_MAX - 1].rsplit(" ", 1)[0].rstrip(",;:") + "…"


def _sentence_list(items: list[str]) -> str:
    items = [i for i in items if i]
    return items[0] if len(items) == 1 else ", ".join(items[:-1]) + " and " + items[-1]


def doc_lines(k, panel_titles: list[str], examples: list[str], controls: list[tuple[str, str]] = (), override: dict | None = None) -> list[str]:
    """A generated Sutra docstring: summary and description from the kind's description, its panels and its strip; params from its
    controls; examples the URL and the command line that open a committed sample."""
    o = override or {}
    desc = " ".join(k.desc.split())
    body = [desc if desc.endswith(".") else desc + "."]
    body.append(f"One page per {k.label[0].lower() + k.label[1:]}, its panels: " + "; ".join(t.rstrip(".") for t in panel_titles) + ".")
    if k.strip:
        body.append("The strip gives " + _sentence_list([s[0] for s in k.strip[:8]]) + ".")
    if k.links:
        body.append("Linked entities: " + _sentence_list([f"{lab.lower() if lab[:2] != lab[:2].upper() else lab} ({kind})"
                                                           for _, (kind, lab) in k.links.items()]) + ".")
    text = o.get("description") or " ".join(body)
    out = ["doc:", f"  summary: {json.dumps(o.get('summary') or summary(desc), ensure_ascii=False)}", "  description: >-"]
    out += ["    " + line for line in textwrap.wrap(text, 124)]
    if controls:
        out.append("  params:")
        out += [f"    {cid}: {{ type: text, default: '', meaning: {json.dumps('The ' + label + ' control', ensure_ascii=False)} }}"
                for cid, label in controls]
    else:
        out.append("  params: {}")
    out += ["  emits: []", "  expects: []", "  examples:"]
    out += [f"    - {json.dumps(e, ensure_ascii=False)}" for e in (o.get("examples") or examples)]
    out.append(f"  since: \"{SINCE}\"")
    return out


CONTROL = re.compile(r"id: (\S+?), .*?label: ([^,]+),")


def controls_of(lines: list[str]) -> list[tuple[str, str]]:
    """(id, label) of each control line the generator writes."""
    return [(m.group(1), m.group(2).strip().strip('"')) for m in (CONTROL.search(x) for x in lines) if m]


@lru_cache(maxsize=None)
def first_ids(catalog: Path) -> dict[str, str]:
    """The first committed sample id of each kind, from a pack's samples/catalog.json."""
    out: dict[str, str] = {}
    if catalog.exists():
        for e in json.loads(catalog.read_text(encoding="utf-8")):
            out.setdefault(e["kind"], e["id"])
    return out
