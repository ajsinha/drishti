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

"""The data map, ``DATAMAP <GO>`` (docs/guides/USER_GUIDE.md "The data map"): what the page draws, made from what the server infers.

The server's ``GET /api/v1/datamap`` gives the user's packs, their kinds with counts from the lake, and the joins the data contracts
declare, each checked against the data (how many of the ids a join holds name an entity that exists). This module adds what the console
knows: the commands that open each kind (the command registry, ``/api/v1/functions``), the links to its data contract, its pack's guide
and its Knowledge entry (only links the help library resolves), the domains (config/datamap.yaml, presentation only: a pack it does not
name is a domain of its own), the findings (joins whose ids mostly name nothing) and an automatic layout: domains as boxes on shelves,
each kind a node in its domain's grid, the most joined first. Nothing is hand-placed.
"""
from __future__ import annotations

import math
import re
from pathlib import Path
from urllib.parse import quote

import yaml

WORD = "DATAMAP"           # a reserved command word (routes/terminal_routes.py, the server's PackLoader.RESERVED_WORDS)
NODE_W, NODE_H, GAP, PAD, HEAD = 150, 50, 14, 14, 30     # the layout's unit sizes, in SVG pixels
CANVAS_W = 1180            # domains are packed on shelves this wide (the canvas scrolls inside its box on a phone)


def load_config(path: Path) -> dict:
    """config/datamap.yaml; an absent or broken file is no configuration (everything has a default)."""
    try:
        data = yaml.safe_load(Path(path).read_text(encoding="utf-8")) or {}
    except (OSError, yaml.YAMLError):
        data = {}
    return data if isinstance(data, dict) else {}


def _slug(text: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", str(text).lower()).strip("-")


def count_text(k: dict) -> str:
    """What a node says under its name: the count (``20,000+`` when the listing stopped), *computed* for a measure computed on demand,
    *not read* when the lake could not say."""
    if k.get("computed"):
        return "computed"
    if k.get("count") is None:
        return "not read" if k.get("failed") else "counting…"
    return f"{k['count']:,}" + ("+" if k.get("more") else "")


def coverage_text(j: dict) -> str:
    """A join's check in words: ``15,000 of 15,000 ids match (100%)``; *computed on demand*; *not checked: why*; *checking…*."""
    state = j.get("state")
    if state == "computed":
        return "computed on demand: nothing stored to check"
    if state == "unchecked":
        return "not checked: " + (j.get("why") or "the data could not be read")
    if state != "checked":
        return "checking…"
    if not j.get("values"):
        return f"no row of {j.get('rows', 0):,} carries it"
    pct = 100.0 * j["matched"] / j["values"]
    share = "100%" if j["matched"] == j["values"] else f"{math.floor(pct * 10) / 10:g}%"
    return (f"{j['matched']:,} of {j['values']:,} ids match ({share})" + (f"; {j['filled']:,} of {j['rows']:,} rows carry it" if j["filled"] < j["rows"] else "")
            + (" · a sample" if j.get("partial") else ""))


def _domains(config: dict, packs: list[dict]) -> tuple[list[dict], dict]:
    """The configured domains that hold a pack of the map, in their order, then one domain per other pack, in pack order."""
    names = [p["name"] for p in packs]
    titles = {p["name"]: p.get("title") or p["name"] for p in packs}
    out, of_pack = [], {}
    for d in config.get("domains") or []:
        if not isinstance(d, dict):
            continue
        held = [n for n in (d.get("packs") or []) if n in names and n not in of_pack]
        if held:
            dom = {"id": _slug(d.get("id") or d.get("title") or held[0]), "title": str(d.get("title") or titles[held[0]]), "packs": held}
            out.append(dom)
            of_pack.update({n: dom["id"] for n in held})
    for n in names:
        if n not in of_pack:
            out.append({"id": _slug(n), "title": titles[n], "packs": [n]})
            of_pack[n] = _slug(n)
    return out, of_pack


def _layout(domains: list[dict], kinds: list[dict], joins: list[dict]) -> tuple[int, int]:
    """Positions every domain box and kind node: a domain's kinds in a grid of one to three columns, the most joined first; domains packed
    left to right on shelves of CANVAS_W. Deterministic: the same packs and contracts give the same picture."""
    degree: dict[str, int] = {}
    for j in joins:
        degree[j["from"]] = degree.get(j["from"], 0) + 1
        degree[j["to"]] = degree.get(j["to"], 0) + 1
    by_domain: dict[str, list[dict]] = {}
    for k in kinds:
        k["degree"] = degree.get(k["kind"], 0)
        by_domain.setdefault(k["domain"], []).append(k)
    x = y = shelf = 0
    width = 0
    for d in domains:
        mine = sorted(by_domain.get(d["id"], []), key=lambda k: (-k["degree"], k["computed"], k["label"].lower()))
        cols = 1 if len(mine) <= 3 else 2 if len(mine) <= 8 else 3
        rows = max(1, math.ceil(len(mine) / cols))
        w = cols * NODE_W + (cols - 1) * GAP + 2 * PAD
        h = HEAD + rows * NODE_H + (rows - 1) * GAP + PAD
        if x and x + w > CANVAS_W:
            x, y, shelf = 0, y + shelf + 24, 0
        d["box"] = {"x": x + 10, "y": y + 10, "w": w, "h": h}
        for i, k in enumerate(mine):
            k["x"] = x + 10 + PAD + (i % cols) * (NODE_W + GAP)
            k["y"] = y + 10 + HEAD + (i // cols) * (NODE_H + GAP)
            k["w"], k["h"] = NODE_W, NODE_H
        x += w + 24
        shelf = max(shelf, h)
        width = max(width, x)
    return width + 10, y + shelf + 30


def _contract_anchor(path: Path, kind: str) -> str:
    """The anchor of the kind's section in its pack's generated contract page (``## TRD: Trade (`trade`)``), or ""."""
    from core.guides import github_slug
    try:
        for line in path.read_text(encoding="utf-8").splitlines():
            if line.startswith("## ") and f"(`{kind}`)" in line:
                return github_slug(line[3:])
    except OSError:
        pass
    return ""


def _sources(pack_dir) -> list[str]:
    """Where a pack's data comes from: the names of its README's ``sources`` (the README standard)."""
    try:
        text = (Path(pack_dir) / "README.md").read_text(encoding="utf-8")
    except (OSError, TypeError):
        return []
    m = re.search(r"(?ms)^---\s*\n(.*?)^---\s*$", text)
    try:
        meta = yaml.safe_load(m.group(1)) if m else {}
    except yaml.YAMLError:
        meta = {}
    return [str(s.get("name")) for s in (meta or {}).get("sources") or [] if isinstance(s, dict) and s.get("name")]


def build(server: dict, functions: dict, config: dict, packs: list[dict], lib=None, glossary=None, knowledge: dict | None = None,
          docs_dir: Path | None = None) -> dict:
    """The page's model: domains, kinds and joins with their words, links and positions, and the findings. ``server`` is the server's
    data map, ``functions`` its command directory for the user, ``packs`` the console's enabled packs (guides, forms, folder)."""
    knowledge = knowledge or {}
    computed_extra = set(config.get("computed") or [])
    low = float(config.get("low-coverage", 0.95) or 0.95)
    by_file = getattr(lib, "by_file", {}) or {}
    guides = getattr(lib, "guides", {}) or {}
    console_packs = {p["name"]: p for p in packs}
    commands = {m.get("kind"): m for p in (functions or {}).get("packs") or [] for m in p.get("commands") or []}
    server_packs = server.get("packs") or []
    domains, of_pack = _domains(config, server_packs)
    pack_title = {p["name"]: p.get("title") or p["name"] for p in server_packs}

    def help_link(path: Path, anchor: str = "") -> str:
        slug = by_file.get(Path(path).resolve()) if path else None
        return f"/help/{slug}" + (f"#{anchor}" if anchor else "") if slug else ""

    def pack_guide(name: str) -> dict | None:
        for g in (console_packs.get(name) or {}).get("guides") or []:
            if g.get("slug") in guides:
                return {"href": f"/help/{g['slug']}", "title": g.get("title") or "Pack guide"}
        return None

    kinds = []
    for k in server.get("kinds") or []:
        k = dict(k)
        k["computed"] = bool(k.get("computed")) or k["kind"] in computed_extra
        k["domain"] = of_pack.get(k["pack"], _slug(k["pack"]))
        k["label"] = k.get("label") or k.get("title") or k["kind"]
        k["countText"] = count_text(k)
        cmd = commands.get(k["kind"])
        forms = (console_packs.get(k["pack"]) or {}).get("forms") or {}
        go = []
        if cmd and (cmd.get("example") or "").strip():
            go.append(cmd["example"].strip())
        elif cmd and k.get("example"):
            go.append(f"{cmd['code']} {k['example']}")
        if k["kind"] in forms:
            go.append(f"FORM {k['pack']} {k['kind']}")
        k["commands"] = [{"text": t, "href": "/go?q=" + quote(t)} for t in go]
        k["on"] = cmd is not None                             # the command is on and the user may open it
        k["open"] = k["commands"][0]["href"] if k["commands"] else (f"/form/{quote(k['pack'])}?kind={quote(k['kind'])}" if k["kind"] in forms else "")
        links = []
        if docs_dir:
            own = Path(docs_dir) / "contracts" / f"{k.get('code') or ''}.md"
            page = Path(docs_dir) / "contracts" / "packs" / f"{k['pack']}.md"
            href = help_link(own) if k.get("code") and own.is_file() else ""
            href = href or (help_link(page, _contract_anchor(page, k["kind"])) if page.is_file() else "")
            if href:
                links.append({"label": "Data contract", "href": href})
        g = pack_guide(k["pack"])
        if g:
            links.append({"label": g["title"], "href": g["href"]})
        if knowledge.get(k["kind"]):
            links.append({"label": f"WHAT {k['label'].lower()}", "href": f"/help/{knowledge[k['kind']]}", "what": True})
        elif glossary is not None:
            e = glossary.find(k["label"]) or glossary.find(k["kind"].replace("-", " "))
            if e is not None:
                links.append({"label": f"WHAT {e.term}", "href": "/what?q=" + quote(e.term), "what": True})
        k["links"] = links
        kinds.append(k)
    known = {k["kind"]: k for k in kinds}
    joins, findings = [], []
    for j in server.get("joins") or []:
        if j.get("from") not in known or j.get("to") not in known:
            continue
        j = dict(j)
        j["text"] = coverage_text(j)
        j["low"] = j.get("state") == "checked" and bool(j.get("values")) and j["matched"] / j["values"] < low
        j["cross"] = known[j["from"]]["domain"] != known[j["to"]]["domain"]
        if j["low"]:
            findings.append({"from": j["from"], "field": j["field"], "to": j["to"],
                             "text": f"{known[j['from']]['label']}.{j['field']} → {known[j['to']]['label']}: {j['text']}"})
        joins.append(j)
    width, height = _layout(domains, kinds, joins)
    lines: dict[tuple, dict] = {}                       # one line per pair of kinds, whatever the number of fields joining them
    for j in joins:
        a, b = known[j["from"]], known[j["to"]]
        line = lines.setdefault((j["from"], j["to"]), {"from": j["from"], "to": j["to"], "fields": [], "low": False, "cross": j["cross"],
                                                       "x1": a["x"] + NODE_W // 2, "y1": a["y"] + NODE_H // 2,
                                                       "x2": b["x"] + NODE_W // 2, "y2": b["y"] + NODE_H // 2})
        line["fields"].append(j["field"])
        line["low"] = line["low"] or j["low"]
    for d in domains:
        mine = [k for k in kinds if k["domain"] == d["id"]]
        stored = sorted((k for k in mine if not k["computed"] and k.get("count")), key=lambda k: -k["count"])
        d["summary"] = " · ".join(f"{k['label']} {k['countText']}" for k in stored[:3]) or "computed on demand"
        d["kinds"] = [k["kind"] for k in mine]
        d["packTitles"] = [pack_title.get(n, n) for n in d["packs"]]
        d["sources"] = list(dict.fromkeys(s for n in d["packs"] for s in _sources((console_packs.get(n) or {}).get("dir"))))
        crosses = {}
        for j in joins:
            a, b = known[j["from"]]["domain"], known[j["to"]]["domain"]
            if j["cross"] and d["id"] in (a, b):
                other = b if a == d["id"] else a
                crosses.setdefault(other, []).append(j["field"])
        titles = {x["id"]: x["title"] for x in domains}
        d["crosses"] = [{"id": o, "title": titles.get(o, o), "fields": sorted(set(f))} for o, f in crosses.items()]
        links = []
        page = Path(docs_dir) / "contracts" / "packs" / f"{d['packs'][0]}.md" if docs_dir else None
        if page is not None and page.is_file() and help_link(page):
            links.append({"label": "Data contracts", "href": help_link(page)})
        g = pack_guide(d["packs"][0])
        if g:
            links.append({"label": g["title"], "href": g["href"]})
        know = next((knowledge[k["kind"]].split("#")[0] for k in mine if knowledge.get(k["kind"])), "")
        if know and know in guides:
            links.append({"label": "Knowledge: " + guides[know].title, "href": f"/help/{know}"})
        d["links"] = links
    return {"businessDate": server.get("businessDate"), "state": server.get("state") or "ready", "domains": domains, "kinds": kinds,
            "joins": joins, "lines": list(lines.values()), "findings": findings, "width": width, "height": height, "low": low,
            "counted": sum(1 for k in kinds if k.get("count") is not None), "packs": len(server_packs)}


def resolve(model: dict, words: str) -> dict:
    """What ``DATAMAP <words>`` focuses: a kind (its name, mnemonic or label), else a domain (its id, title or a pack's name), else a kind
    or domain whose name starts with the words; ``{}`` when nothing matches (the whole map, with the words in Find)."""
    w = " ".join(str(words or "").lower().replace("-", " ").split())
    if not w:
        return {}
    kinds, domains = model.get("kinds") or [], model.get("domains") or []
    norm = lambda s: " ".join(str(s or "").lower().replace("-", " ").split())   # noqa: E731
    for k in kinds:
        if w in (norm(k["kind"]), norm(k.get("code")), norm(k.get("label"))):
            return {"kind": k["kind"], "domain": k["domain"]}
    for d in domains:
        if w in (norm(d["id"]), norm(d["title"]), *(norm(p) for p in d["packs"])):
            return {"domain": d["id"]}
    for k in kinds:
        if norm(k.get("label")).startswith(w) or norm(k["kind"]).startswith(w):
            return {"kind": k["kind"], "domain": k["domain"]}
    for d in domains:
        if norm(d["title"]).startswith(w):
            return {"domain": d["id"]}
    return {}
