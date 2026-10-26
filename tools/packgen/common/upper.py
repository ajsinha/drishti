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

"""UPPER_SNAKE attribute names: every document a shipped pack carries names its attributes as production systems do
(COUNTERPARTY_ID, NET_MTM, PEAK_EE, ZERO_RATE, TENOR), whatever the generator calls them inside.

The banking, world-economy and news generators build their documents with short internal names (counterpartyId, netMtm,
zeroRate); this module is the one boundary that turns them into the names a pack writes:

    name("counterpartyId")              COUNTERPARTY_ID      (idempotent: name("COUNTERPARTY_ID") is the same)
    path("points.tenor=10Y.zeroRate")   POINTS.TENOR=10Y.ZERO_RATE
    expr("max(high, $.price)")          max(HIGH, $.PRICE)   (functions, quoted text, numbers and ctl.<control> untouched)
    rachana("size($.members) > 0")      size($.MEMBERS) > 0  (only the $. and @. paths of a Rachana-EL expression)
    record(doc)                         every key of a document, _meta kept, its live walk's paths and expressions renamed
    kind(k)                             a taxonomy Kind (id field, strip, panels, links, badge) in the names its documents use

Digits stay with the word they qualify (pd1y -> PD_1Y, var99 -> VAR_99, holidays2026 -> HOLIDAYS_2026); the measures
written as one token keep it (dv01 -> DV01, cs01 -> CS01, ie01 -> IE01, pv01 -> PV01, iso3 -> ISO3, pnl1d -> PNL_1D).
"""
from __future__ import annotations

import dataclasses
import re

# tokens a name keeps whole (a measure or a code, not a word and a number)
WHOLE = {"dv01": "DV01", "cs01": "CS01", "ie01": "IE01", "pv01": "PV01", "iso3": "ISO3", "iso2": "ISO2", "areaKm2": "AREA_KM2"}
UPPER = re.compile(r"^[A-Z0-9_]+$")
_CAMEL = re.compile(r"(?<=[a-z])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])")
_DIGITS = re.compile(r"(?<=[A-Za-z])(?=\d)")


def name(n: str) -> str:
    """One attribute name in UPPER_SNAKE."""
    if not n or UPPER.match(n) or n.startswith("_"):
        return n
    if n in WHOLE:
        return WHOLE[n]
    for k, v in WHOLE.items():                      # a whole token inside a longer name (bookDv01 -> BOOK_DV01)
        if n.lower().endswith(k.lower()) and len(n) > len(k) and n[-len(k) - 1].isalpha():
            return name(n[:-len(k)]) + "_" + v
    s = _CAMEL.sub("_", n)
    s = _DIGITS.sub("_", s)
    return re.sub(r"_+", "_", s).upper().strip("_")


_SEGMENT = re.compile(r"^([A-Za-z_][A-Za-z0-9_]*)(.*)$")


def path(p: str) -> str:
    """A dotted document path (walk spec, BI column path): each name segment renamed; indexes, '*', '-1' and selectors kept,
    a selector's field renamed (tenor=10Y -> TENOR=10Y)."""
    if not isinstance(p, str):
        return p
    out = []
    for seg in p.split("."):
        if "=" in seg:
            f, _, v = seg.partition("=")
            out.append(name(f) + "=" + v)
        elif re.match(r"^[A-Za-z_]", seg):
            out.append(name(seg))
        else:
            out.append(seg)
    return ".".join(out)


KEYWORDS = {"true", "false", "null", "and", "or", "not", "in", "if", "then", "else", "ctl", "today", "it", "row", "doc", "view"}
_TOKEN = re.compile(r"'(?:[^'\\]|\\.)*'|\"(?:[^\"\\]|\\.)*\"|\d+(?:\.\d+)?[A-Za-z_]\w*|\d+(?:\.\d+)?(?:[eE][-+]?\d+)?|[A-Za-z_]\w*|.", re.S)


def expr(e: str) -> str:
    """A walk expression (DemoWalk): every bare field name renamed; functions (a name before '('), quoted text, numbers,
    keywords and a control's id after ctl. are kept."""
    if not isinstance(e, str):
        return e
    toks = _TOKEN.findall(e)
    out = []
    for i, t in enumerate(toks):
        if re.match(r"^[A-Za-z_]\w*$", t) and t not in KEYWORDS:
            nxt = next((x for x in toks[i + 1:] if not x.isspace()), "")
            prev = "".join(toks[max(0, i - 2):i])
            if nxt != "(" and not prev.endswith("ctl.") and not prev.endswith("'"):
                t = name(t)
        out.append(t)
    return "".join(out)


_RPATH = re.compile(r"([$@])((?:\.(?:[A-Za-z_]\w*|\*)|\[[^\]]*\])+)")


def rachana(e):
    """A Rachana-EL expression or a ${...} template: the names on its $. and @. paths renamed (functions, controls and quoted
    text are not paths, so they stay)."""
    if not isinstance(e, str) or ("$" not in e and "@" not in e):
        return e

    def seg(m: re.Match) -> str:
        body = re.sub(r"\.([A-Za-z_]\w*)", lambda s: "." + name(s.group(1)), re.sub(r"\[([^\]]*)\]", lambda b: "[" + rachana(b.group(1)) + "]", m.group(2)))
        return m.group(1) + body

    return _RPATH.sub(seg, e)


# ---- documents ---------------------------------------------------------------------------------------------------
WALK_GRAMMAR = {"step", "places", "min", "max", "scale", "shape", "jitter", "mode", "with", "path", "set", "expr", "append", "every", "keep"}


def keys(o):
    """Every key of a JSON value in UPPER_SNAKE (no _meta handling: for plain nested data)."""
    if isinstance(o, dict):
        return {name(k): keys(v) for k, v in o.items()}
    if isinstance(o, list):
        return [keys(x) for x in o]
    return o


def _walk_entry(v):
    if isinstance(v, dict):
        out = {}
        for k, x in v.items():
            if k == "with":
                out[k] = [path(w) if isinstance(w, str) else {**w, "path": path(w["path"])} if isinstance(w, dict) and "path" in w else w for w in x]
            elif k == "set" or k == "path":
                out[k] = path(x)
            elif k == "expr":
                out[k] = expr(x)
            else:
                out[k] = x
        return out
    return v


def walk(w: dict) -> dict:
    """A sample's _meta.walk: the paths it moves, its derived paths and their expressions renamed; its grammar kept."""
    out = {}
    for k, v in w.items():
        if k in ("derive", "append"):
            out[k] = [_walk_entry(x) for x in v] if isinstance(v, list) else v
        else:
            out[path(k)] = _walk_entry(v)
    return out


def record(doc: dict) -> dict:
    """A document as a pack writes it: its keys in UPPER_SNAKE, _meta (Drishti's) kept with its walk renamed."""
    out = {}
    for k, v in doc.items():
        if k == "_meta" and isinstance(v, dict):
            out[k] = {mk: (walk(mv) if mk == "walk" and isinstance(mv, dict) else mv) for mk, mv in v.items()}
        else:
            out[name(k)] = keys(v)
    return out


# ---- taxonomy ----------------------------------------------------------------------------------------------------
FIELD_OPTS = {"label", "group", "size", "value", "by", "across", "volume", "date", "detail", "status", "x", "y", "series", "z", "lat", "lon",
              "color", "start", "end", "open", "high", "low", "close", "weight", "parent", "event", "text", "name"}
EXPR_OPTS = {"nodes", "edges", "rows", "each", "source", "children", "highlight", "where", "options", "max", "min", "limit"}
TEXT_OPTS = {"xLabel", "yLabel", "unit", "sum", "layout", "agg", "xFmt", "yFmt", "fmt", "tone", "height", "heat", "title", "kind", "colors", "bins"}


def _field(v):
    """A panel option that names a field (x: tenor) or holds an expression ($.limit)."""
    if not isinstance(v, str):
        return v
    return rachana(v) if ("$" in v or "@" in v) else name(v) if re.match(r"^[A-Za-z_]\w*$", v) else v


def _opt(k: str, v):
    if k in TEXT_OPTS:
        return v
    if k == "markers" and isinstance(v, list):
        return [{**m, "value": rachana(m["value"])} if isinstance(m, dict) and "value" in m else m for m in v]
    if k == "link":
        return v                                     # its id template's {iso3}, {iso2} and {code} are the map's tokens, not fields
    if k == "shade" and isinstance(v, dict):
        return {**v, "field": rachana(v.get("field")), "by": rachana(v.get("by"))}
    if k in EXPR_OPTS:
        return rachana(v)
    if k in FIELD_OPTS:
        return [_field(x) for x in v] if isinstance(v, list) else _field(v)
    return rachana(v)


def pivot(pv):
    if not isinstance(pv, dict):
        return pv

    def f(x):
        if isinstance(x, str):
            return name(x)
        if isinstance(x, dict):
            return {**x, "field": name(x["field"])} if "field" in x else x
        return x

    return {k: ([f(x) for x in v] if isinstance(v, list) and k != "heat" else v) for k, v in pv.items()}


def panel(p):
    return dataclasses.replace(
        p, rows=rachana(p.rows), columns=[(c[0], rachana(c[1]), *c[2:]) for c in p.columns], x=_field(p.x), y=_field(p.y),
        series=[(s[0], _field(s[1]), *s[2:]) for s in p.series], label=_field(p.label), value=_field(p.value), limit=rachana(p.limit),
        opts={k: _opt(k, v) for k, v in p.opts.items()}, pivot=pivot(p.pivot))


def kind(k):
    """A taxonomy Kind (tools/packgen/banking/risk_model.py) in the names its documents carry."""
    return dataclasses.replace(
        k, id_field=name(k.id_field), strip=[(s[0], rachana(s[1]), *s[2:]) for s in k.strip], panels=[panel(p) for p in k.panels],
        links={name(f): v for f, v in k.links.items()}, badge=rachana(k.badge), live={path(f): v for f, v in k.live.items()},
        with_=rachana(k.with_))
