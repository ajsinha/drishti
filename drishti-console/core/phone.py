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

"""The phone display (docs/architecture/MOBILE_DISPLAY.md), the console side.

Every panel of an entity page gets a **phone tier**: ``must`` (shown in full, first), ``glance`` (shown in a compact form: a few rows, a
sparkline, the first figures), ``more`` (not sent; a row under *More on this page*, loaded when tapped) or ``desk`` (not on a phone;
named under *On desktop*). The tier is configured, never coded, resolved per panel with the most specific layer winning:

1. the panel kind's default (:data:`KIND_DEFAULTS`, overridable in ``ui.phone.kinds``);
2. the fragment's ``doc.phone`` and 3. the Sutra's ``phone:`` (both carried by the server in the ViewModel's ``phone``);
4. the site (``ui.phone.rules``), the pack (``ui.phone.packs``) and the edition or lens (its ``phone.rules``), each of which may also cap
   (``max: glance``): a cap wins over every layer, the strictest cap of all;
5. the person's own pins: decided for later (MOBILE_DISPLAY.md section 11), not in this build.

The browser says what it is (``X-Drishti-Device`` on a request, or the ``drishti-device`` cookie static/js/phone.js keeps from the screen
width); the console never guesses from the user agent. A phone gets the must and glance panels only (glance ones cut on the console
before they are sent), so the phone downloads and draws a fraction of the page; a tablet gets must, glance and more, with desk panels
behind *More on this page*. ``drishti-phone-all=1`` (*Show everything*) gives the whole page for the session.
"""
from __future__ import annotations

import copy
import fnmatch
import re
from typing import Any

TIERS = ("must", "glance", "more", "desk")
DEVICES = ("phone", "tablet", "desk")
HEADER = "X-Drishti-Device"
COOKIE = "drishti-device"
ALL_COOKIE = "drishti-phone-all"

# Layer 1: the tier of a panel kind (MOBILE_DISPLAY.md section 4, decided 2026-10-31: tables and charts glance).
KIND_DEFAULTS = {
    "kv": "must", "status": "must", "metric": "must", "gauge": "must",
    "table": "glance", "ladder": "glance", "line": "glance", "area": "glance", "hbar": "glance", "candlestick": "glance",
    "waterfall": "glance", "histogram": "glance", "timeline": "glance", "links": "glance", "map": "glance", "tabs": "glance",
    "scatter": "glance",
    "provenance": "more", "markdown": "more", "surface": "more", "graph": "more",
    "pivot": "desk",
}
GLANCE_ROWS = 5            # a glance table, bar list, timeline or link list: its first five rows
GLANCE_FIGURES = 6         # a glance key-figure group: its first six figures
SPARK_W, SPARK_H = 120, 28


def device(request) -> str:
    """The device class this request is for: the ``X-Drishti-Device`` header (an embedding page or the API), else the cookie the page keeps
    from the screen width, else ``desk`` (a first visit draws the desktop page; phone.js then sets the cookie and draws the phone one)."""
    for v in (request.headers.get(HEADER), request.cookies.get(COOKIE)):
        v = (v or "").strip().lower()
        if v in DEVICES:
            return v
    return "desk"


def settings(request) -> dict:
    s = request.app.state.settings.get("ui.phone")
    return s if isinstance(s, dict) else {}


def enabled(request) -> bool:
    v = settings(request).get("enabled", True)
    return str(v).strip().lower() not in ("false", "0", "no", "off")


def show_all(request) -> bool:
    return request.cookies.get(ALL_COOKIE) == "1"


def everything(request) -> bool:
    """Whether a phone or tablet reader chose *Show everything* for the session (the page then offers *Phone view*)."""
    return enabled(request) and show_all(request) and device(request) != "desk"


def _rules(raw) -> list[dict]:
    return [r for r in (raw or []) if isinstance(r, dict) and (r.get("tier") in TIERS or r.get("max") in TIERS)]


def _glob(pattern, value) -> bool:
    return pattern is None or fnmatch.fnmatchcase(str(value or "").lower(), str(pattern).lower())


def _matches(rule: dict, p: dict, ctx: dict) -> bool:
    """Whether a site, pack or edition rule applies to this panel: every key it gives matches (globs, case-blind)."""
    return (_glob(rule.get("panel"), p.get("id")) and _glob(rule.get("kind"), p.get("kind")) and _glob(rule.get("title"), p.get("title"))
            and _glob(rule.get("sutra"), ctx.get("sutra")) and _glob(rule.get("entity"), ctx.get("entity")))


def layers(request, vm: dict, pack_list: list | None = None) -> list[tuple[str, list[dict]]]:
    """Layer 4, most general first: the site's rules, the rules of the pack that owns the entity's kind, the edition's (or the person's
    lens's) rules."""
    s = settings(request)
    out = [("site", _rules(s.get("rules")))]
    kind = (vm.get("ref") or {}).get("kind")
    packs = s.get("packs") if isinstance(s.get("packs"), dict) else {}
    for p in pack_list or []:
        if isinstance(p, dict) and kind in (p.get("kinds") or []) and p.get("name") in packs:
            out.append(("pack", _rules(packs[p["name"]])))
    edition = getattr(request.state, "edition", None) or getattr(getattr(request.app.state, "editions", None), "instance", None)
    spec = getattr(edition, "spec", None) or {}
    ph = spec.get("phone") if isinstance(spec.get("phone"), dict) else {}
    out.append(("edition", _rules(ph.get("rules"))))
    return out


def resolve(vm: dict, panels: list[dict], extra: list[tuple[str, list[dict]]] | None = None, kinds: dict | None = None) -> dict[str, dict]:
    """Each panel's tier with the layer that set it and its compact options: ``{id: {tier, from, capped?, …options}}``."""
    carried = vm.get("phone") if isinstance(vm.get("phone"), dict) else {}
    declared = carried.get("panels") if isinstance(carried.get("panels"), dict) else {}
    default = carried.get("default") if carried.get("default") in TIERS else None
    ctx = {"sutra": (vm.get("provenance") or {}).get("sutra") or "", "entity": (vm.get("ref") or {}).get("kind") or ""}
    kind_tiers = {**KIND_DEFAULTS, **{k: v for k, v in (kinds or {}).items() if v in TIERS}}
    out: dict[str, dict] = {}
    for p in panels:
        pid = str(p.get("id") or "")
        got = {"tier": kind_tiers.get(p.get("kind"), "glance"), "from": "kind"}
        own = declared.get(pid) if isinstance(declared.get(pid), dict) else None
        if own and own.get("tier") in TIERS:
            got = {k: v for k, v in own.items()}
            got["from"] = "fragment" if own.get("from") == "fragment" else "sutra"
        elif default:                                           # the screen's default: for the panels the Sutra gives no tier
            got = {"tier": default, "from": "sutra"}
        cap = None
        for layer, rules in extra or []:
            for r in rules:
                if not _matches(r, p, ctx):
                    continue
                if r.get("tier") in TIERS:
                    got = {**{k: v for k, v in got.items() if k not in ("tier", "from")}, "tier": r["tier"], "from": layer}
                if r.get("max") in TIERS and (cap is None or TIERS.index(r["max"]) > TIERS.index(cap[0])):
                    cap = (r["max"], layer)
        if cap and TIERS.index(got["tier"]) < TIERS.index(cap[0]):
            got = {**got, "tier": cap[0], "from": cap[1], "capped": True}
        out[pid] = got
    if panels and not any(t["tier"] == "must" for t in out.values()):
        first = str(panels[0].get("id") or "")
        out[first] = {**out[first], "tier": "must", "from": "rule"}        # a page always has a must panel (the first one)
    return out


def plan(request, vm: dict, panels: list[dict], dev: str, pack_list: list | None = None) -> dict:
    """The phone or tablet page: the panels to draw (glance ones compacted on a phone), the *More on this page* rows, the *On desktop* rows,
    the summary line and why each panel is where it is."""
    tiers = resolve(vm, panels, layers(request, vm, pack_list), settings(request).get("kinds") if isinstance(settings(request).get("kinds"), dict) else None)
    carried = vm.get("phone") if isinstance(vm.get("phone"), dict) else {}
    order = [str(x) for x in carried.get("order") or (vm.get("layout") or {}).get("phone") or []]
    rank = {pid: i for i, pid in enumerate(order)}
    shown, more, desk = [], [], []
    for i, p in enumerate(panels):
        t = tiers.get(str(p.get("id") or ""), {"tier": "glance"})
        tier = t["tier"]
        stub = {"id": p.get("id"), "title": p.get("title") or p.get("id"), "kind": p.get("kind"), "tier": tier, "from": t.get("from")}
        if p.get("hidden"):
            continue
        if tier == "must" or (tier == "glance" and dev == "tablet") or (tier == "more" and dev == "tablet"):
            shown.append((TIERS.index(tier) if dev == "phone" else 0, rank.get(p.get("id"), 1000 + i), i, dict(p, phone=stub)))
        elif tier == "glance":
            shown.append((1, rank.get(p.get("id"), 1000 + i), i, compact(p, t)))
        elif tier == "more" or dev == "tablet":
            more.append(stub)
        else:
            desk.append(stub)
    shown.sort(key=lambda r: (r[0], r[1], r[2]))
    return {"device": dev, "shown": [r[3] for r in shown], "more": more, "desk": desk, "summary": carried.get("summary") or "",
            "why": [{"id": p.get("id"), "title": p.get("title") or p.get("id"), **tiers.get(str(p.get("id") or ""), {})} for p in panels]}


# ---- pages beyond Sutra pages (MOBILE_DISPLAY.md section 8): the VaR and exposure pages and the edition landings ------------------

# Each page's parts with their default tier and their name in *More on this page*. The site sets its own in ui.phone.pages.<page>, an
# edition in its phone.pages.<page> (a landing also in home.phone); either may cap with max (a part never above it).
PAGES: dict[str, dict[str, tuple[str, str]]] = {
    "var": {"tiles": ("must", "Headline VaR, ES and limit use"), "method": ("must", "How VaR is computed"),
            "path-dist": ("glance", "Distribution of scenario P&L"), "path-tail": ("more", "Worst 25 scenarios"),
            "path-series": ("more", "P&L by scenario date"), "path-table": ("more", "The P&L path, scenario by scenario"),
            "hist": ("more", "VaR / ES history"), "contrib": ("more", "Contributions"), "captures": ("more", "Captured results"),
            "ccy": ("more", "In other currencies"), "tree": ("more", "Scopes"), "prov": ("more", "How this number was built"),
            "whatif": ("desk", "What-if"), "basket": ("desk", "Construct your basket")},
    "exposure": {"tiles": ("must", "CE, EPE and peak PFE"), "chart": ("glance", "Profile"), "tabs": ("more", "Netting sets, trades and limits"),
                 "tree": ("more", "Scopes"), "prov": ("more", "How this number was built"), "fan": ("desk", "Paths")},
    "landing": {"hero": ("must", "Command line"), "tiles": ("must", "Headline figures"), "quick": ("more", "Saved baskets, watch list, learn"),
                "card-1": ("must", "First card"), "table": ("glance", "Tables"), "chart": ("more", "Charts"), "map": ("more", "Maps"),
                "feed": ("glance", "Today")},
}


def page_parts(request, page: str) -> dict | None:
    """The tiers of a page's parts for this request, or None on a desktop (and with the phone display off or *Show everything*):
    ``{"device", "tiers": {part: tier}, "labels": {part: name}, "more": [parts], "desk": [parts]}``."""
    if page not in PAGES or not enabled(request) or show_all(request):
        return None
    dev = device(request)
    if dev == "desk":
        return None
    tiers = {k: v[0] for k, v in PAGES[page].items()}
    labels = {k: v[1] for k, v in PAGES[page].items()}
    caps: dict[str, str] = {}
    edition = getattr(request.state, "edition", None) or getattr(getattr(request.app.state, "editions", None), "instance", None)
    spec = getattr(edition, "spec", None) or {}
    ed_phone = spec.get("phone") if isinstance(spec.get("phone"), dict) else {}
    layers = [(settings(request).get("pages") or {}).get(page), (ed_phone.get("pages") or {}).get(page)]
    if page == "landing":
        layers.append((spec.get("home") or {}).get("phone") if isinstance(spec.get("home"), dict) else None)
    for layer in layers:
        for part, v in (layer or {}).items() if isinstance(layer, dict) else ():
            if isinstance(v, str) and v in TIERS:
                tiers[part] = v
            elif isinstance(v, dict):
                if v.get("tier") in TIERS:
                    tiers[part] = v["tier"]
                if v.get("max") in TIERS and TIERS.index(v["max"]) > TIERS.index(caps.get(part, "must")):
                    caps[part] = v["max"]
                if v.get("label"):
                    labels[part] = str(v["label"])
    for part, cap in caps.items():
        if TIERS.index(tiers.get(part, "glance")) < TIERS.index(cap):
            tiers[part] = cap
    if dev == "tablet":                                     # a tablet shows must, glance and more; desk parts wait under More
        tiers = {k: ("more" if v == "desk" else ("must" if v == "more" else v)) for k, v in tiers.items()}
    return {"device": dev, "tiers": tiers, "labels": labels,
            "more": [k for k, v in tiers.items() if v == "more"], "desk": [k for k, v in tiers.items() if v == "desk"]}


# ---- the compact forms (MOBILE_DISPLAY.md section 5.3) ---------------------------------------------------------------------------

_NUM = re.compile(r"^\(?([-+−]?)\s*[$€£¥]?\s*([0-9][0-9,]*\.?[0-9]*)\s*(k|m|mm|bn|b|t|%)?\)?$", re.I)
_SCALE = {"k": 1e3, "m": 1e6, "mm": 1e6, "bn": 1e9, "b": 1e9, "t": 1e12, "%": 1.0}


def number(text) -> float | None:
    """A cell's text read as a number ("41.2m", "(1,234)", "−3.5%"), or None: cells carry text, so a phone sort reads it back."""
    s = str(text or "").strip().replace(" ", "").replace(" ", "")
    m = _NUM.match(s)
    if not m:
        return None
    v = float(m.group(2).replace(",", "") or 0) * _SCALE.get((m.group(3) or "").lower(), 1.0)
    return -v if m.group(1) in ("-", "−") or (s.startswith("(") and s.endswith(")")) else v


def compact(panel: dict, tier: dict) -> dict:
    """A glance panel cut to its compact form, with ``phone`` saying what was left out (the page's *Show all* fetches the whole panel)."""
    p = copy.deepcopy(panel)
    p["height"] = None                                                 # a compact panel is as tall as its few rows, one to a row
    p["span"] = None
    kind = p.get("kind")
    data = p.get("data") if isinstance(p.get("data"), dict) else None
    info: dict[str, Any] = {"id": p.get("id"), "title": p.get("title") or p.get("id"), "kind": kind, "tier": "glance", "from": tier.get("from"),
                            "compact": True}
    rows = int(tier.get("rows") or GLANCE_ROWS)
    if data is None:
        p["phone"] = info
        return p
    if kind in ("table", "ladder") and isinstance(data.get("rows"), list):
        info["total"] = len(data["rows"])
        cols = list(data.get("columns") or [])
        rs = data["rows"]
        sort = str(tier.get("sort") or "")
        if sort:
            key, desc = (sort[1:], True) if sort.startswith("-") else (sort, False)
            if key in cols:
                j = cols.index(key)
                val = lambda r: number(((r.get("cells") or [{}] * (j + 1))[j] or {}).get("text")) if j < len(r.get("cells") or []) else None  # noqa: E731
                rs = sorted(rs, key=lambda r: (val(r) is None, -(val(r) or 0) if desc else (val(r) or 0)))
        rs = rs[:rows]
        keep = [i for i, c in enumerate(cols) if c in (tier.get("columns") or [])] or list(range(len(cols)))
        if len(keep) < len(cols):
            num = list(data.get("numeric") or [])
            data["columns"] = [cols[i] for i in keep]
            data["numeric"] = [num[i] if i < len(num) else False for i in keep]
            rs = [dict(r, cells=[(r.get("cells") or [])[i] for i in keep if i < len(r.get("cells") or [])], children=None) for r in rs]
            data["total"] = None
            info["cut"] = True
        data["rows"] = [dict(r, children=None) for r in rs]
        for k in ("pivot", "levels", "more"):
            data.pop(k, None)
        data["search"] = False
        info["shown"] = len(data["rows"])
    elif kind == "hbar" and isinstance(data.get("bars"), list):
        info["total"] = len(data["bars"])
        data["bars"] = data["bars"][:rows]
        info["shown"] = len(data["bars"])
    elif kind in ("timeline",) and isinstance(data.get("events"), list):
        info["total"] = len(data["events"])
        data["events"] = data["events"][:rows]
        data["more"] = 0
        info["shown"] = len(data["events"])
    elif kind == "links" and isinstance(data.get("links"), list):
        info["total"] = len(data["links"])
        data["links"] = data["links"][:rows]
        info["shown"] = len(data["links"])
    elif kind == "waterfall" and isinstance(data.get("steps"), list) and tier.get("rows"):
        info["total"] = len(data["steps"])
        data["steps"] = data["steps"][:rows]
        info["shown"] = len(data["steps"])
    elif kind in ("kv", "status") and isinstance(data.get("fields"), list):
        n = int(tier.get("figures") or GLANCE_FIGURES)
        info["total"] = len(data["fields"])
        data["fields"] = data["fields"][:n]
        info["shown"] = len(data["fields"])
    elif kind == "tabs" and isinstance(data.get("tabs"), list) and data["tabs"]:
        want = tier.get("tab")
        tabs = data["tabs"]
        pick = tabs[want] if isinstance(want, int) and 0 <= want < len(tabs) else next(
            (t for t in tabs if isinstance(want, str) and str(t.get("title") or "").lower() == want.lower()), tabs[0])
        info["total"] = len(tabs)
        data["tabs"] = [pick]
        info["shown"] = 1
        info["unit"] = "tabs"
    elif kind in ("line", "area", "candlestick"):
        info["spark"] = spark(kind, data, tier.get("series"))
        if info["spark"]:
            info["unit"] = "chart"
    elif kind == "map":
        items = ((data.get("shade") or {}).get("items") or []) if isinstance(data.get("shade"), dict) else []
        items = sorted([i for i in items if isinstance(i, dict)], key=lambda i: -abs(float(i.get("value") or 0)))
        info["list"] = [{"label": i.get("code"), "text": i.get("text") or i.get("value")} for i in items[:rows]]
        if not info["list"] and data.get("highlight"):                 # a map that marks one place: the place, named
            info["list"] = [{"label": data.get("highlight"), "text": data.get("region") or ""}]
        if not info["list"]:
            info.pop("list")
            info["compact"] = False                                     # nothing to list: the map itself
        info["total"] = len(items)
        info["unit"] = "map"
    cut = isinstance(info.get("shown"), int) and isinstance(info.get("total"), int) and info["shown"] < info["total"]
    info["compact"] = bool(info.get("spark") or info.get("list") or cut or info.pop("cut", False))  # Show all only where something was left out
    p["phone"] = info
    return p


def spark(kind: str, data: dict, series=None) -> dict | None:
    """A line, area or candlestick chart as a sparkline: one or two series as SVG points, the last value and the change over the period."""
    lines = []
    if kind == "candlestick":
        cs = [c for c in data.get("candles") or [] if isinstance(c, dict) and isinstance(c.get("close"), (int, float))]
        if cs:
            lines.append({"label": "Close", "values": [float(c["close"]) for c in cs], "last": data.get("last"), "change": data.get("change"),
                          "tone": data.get("tone")})
    else:
        want = [str(s) for s in (series or [])]
        ss = [s for s in data.get("series") or [] if isinstance(s, dict)]
        picked = [s for s in ss if s.get("label") in want] if want else ss[:1]
        for s in (picked or ss[:1])[:2]:
            vals = [float(v) for v in s.get("values") or [] if isinstance(v, (int, float))]
            if vals:
                lines.append({"label": s.get("label") or "", "values": vals})
    if not lines:
        return None
    lo = min(min(line["values"]) for line in lines)
    hi = max(max(line["values"]) for line in lines)
    span = (hi - lo) or 1.0
    out = []
    for n, line in enumerate(lines):
        vals = line["values"]
        step = SPARK_W / max(len(vals) - 1, 1)
        pts = " ".join(f"{i * step:.1f},{SPARK_H - 2 - (v - lo) / span * (SPARK_H - 4):.1f}" for i, v in enumerate(vals))
        first, last = vals[0], vals[-1]
        crosses = first == 0 or (first < 0) != (last < 0)           # a percentage across zero says nothing: the change itself
        change = line.get("change") or (("+" if last >= first else "") + _short(last - first) if crosses
                                        else f"{(last - first) / abs(first) * 100:+.1f}%")
        out.append({"label": line["label"] if len(lines) > 1 or line["label"] == "Close" else "", "points": pts, "last": line.get("last") or _short(last), "change": change,
                    "tone": line.get("tone") or ("pos" if last >= first else "neg"), "second": n > 0})
    return {"lines": out, "w": SPARK_W, "h": SPARK_H, "x0": (data.get("x") or [""])[0] if isinstance(data.get("x"), list) and data.get("x") else "",
            "x1": (data.get("x") or [""])[-1] if isinstance(data.get("x"), list) and data.get("x") else ""}


def _short(v: float) -> str:
    a = abs(v)
    for unit, f in (("bn", 1e9), ("m", 1e6), ("k", 1e3)):
        if a >= f:
            return f"{v / f:.1f}{unit}"
    return f"{v:,.2f}" if a < 100 else f"{v:,.0f}"
