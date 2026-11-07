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

"""The core's object types on the Build home (core/build_registry.py): the generic things anyone builds, whatever the domain.

Data: connectors, lakes, tables and models, Python tables. Screens: Sutras, packs, the UI kit. Analysis: reports and boards, pivot views,
functions. Work: my drafts, waiting for review, recently changed. Each source asks the server as the signed-in person, so the server
decides what they may see; one server answer that serves two types (the tables editor's objects) is asked once per request.
"""
from __future__ import annotations

import time
from datetime import datetime
from urllib.parse import quote, urlencode

from core.backend import BackendError
from core.build_registry import Item, ObjectType, register
from routes.common import ident


def _ms(value) -> int:
    """Epoch milliseconds from a number (s or ms) or an ISO time; 0 when unknown."""
    if isinstance(value, (int, float)) and value > 0:
        return int(value if value > 1e11 else value * 1000)
    if isinstance(value, str) and value:
        try:
            return int(datetime.fromisoformat(value.replace("Z", "+00:00")).timestamp() * 1000)
        except ValueError:
            return 0
    return 0


async def _once(request, key: str, call):
    """One server answer per request, shared by the types that list parts of it (kept on the request, never between people)."""
    memo = getattr(request.state, "build_answers", None)
    if memo is None:
        memo = request.state.build_answers = {}
    if key not in memo:
        memo[key] = await call()
    return memo[key]


def _rows(answer, *keys) -> list:
    if isinstance(answer, list):
        return answer
    for k in keys:
        if isinstance(answer, dict) and isinstance(answer.get(k), list):
            return answer[k]
    return []


# ---- Data -----------------------------------------------------------------------------------------------------------------------

async def connectors(request) -> list[Item]:
    answer = await request.app.state.backend.admin("GET", "/connectors", ident(request))
    return [Item("connectors", str(c.get("name")), str(c.get("plugin") or c.get("type") or "connector"),
                 "on" if c.get("enabled", True) else "off", f"/admin/connectors/{quote(str(c.get('name')), safe='')}",
                 changed=_ms(c.get("changed") or c.get("updated")), by=str(c.get("changedBy") or ""))
            for c in _rows(answer, "connectors") if c.get("name")]


async def lakes(request) -> list[Item]:
    answer = await request.app.state.backend.admin("GET", "/lakes/files", ident(request))
    out = []
    for lake in _rows(answer, "lakes"):
        name = str(lake.get("name") or lake.get("lake") or "")
        if name:
            out.append(Item("lakes", name, str(lake.get("format") or lake.get("kind") or "lake"), str(lake.get("status") or ""),
                            "/admin/lakes", uses=tuple(f"connectors:{c}" for c in lake.get("connectors") or [])))
    return out


async def _objects(request) -> dict:
    return await _once(request, "tables", lambda: request.app.state.backend._send("GET", "/tables/editor/objects", ident(request)))


def _table_item(type_id: str, o: dict) -> Item:
    kind = str(o.get("kind") or "table")
    q = {"kind": kind, "item": o["item"]} if o.get("item") else {"kind": kind, "name": o.get("name")}
    status = str(o.get("status") or "")
    if o.get("version"):
        status = f"{status} v{o['version']}".strip()
    uses = (f"connectors:{o['connector']}",) if o.get("connector") else ()
    form = str(o.get("form") or "")
    label = "semantic model" if kind == "model" else "Python table" if form == "python" else f"{form} table" if form else "table"
    return Item(type_id, str(o.get("name")), label, status, "/build/tables?" + urlencode(q), uses=uses,
                draft=status.startswith("draft"), review=status.startswith(("proposed", "in review")))


async def tables(request) -> list[Item]:
    got = await _objects(request)
    return [_table_item("tables", o) for o in _rows(got, "tables") + _rows(got, "models") if o.get("name")]


async def python_tables(request) -> list[Item]:
    return [_table_item("python-tables", o) for o in _rows(await _objects(request), "python") if o.get("name")]


# ---- Screens --------------------------------------------------------------------------------------------------------------------

async def sutras(request) -> list[Item]:
    out = []
    for s in _rows(await request.app.state.backend.sutras(ident(request)), "sutras"):
        name = str(s.get("name") or "")
        if name:
            v = s.get("latest") or s.get("version")
            out.append(Item("sutras", name, f"{s.get('kind') or 'screen'} screen", f"live v{v}" if v else "live",
                            "/studio?" + urlencode({"sutra": f"{name}@{v}" if v else name}),
                            uses=tuple(f"packs:{p}" for p in [s.get("pack")] if p)))
    return out


async def packs(request) -> list[Item]:
    me = ident(request)
    out = []
    for p in _rows(await request.app.state.backend.packs(me), "packs"):
        name = str(p.get("name") or "")
        if name:
            out.append(Item("packs", name, "pack", ("on" if p.get("active", True) else "off") + (f" v{p['version']}" if p.get("version") else ""),
                            f"/admin/packs/{quote(name, safe='')}" if me.is_admin else f"/help/{quote(name, safe='')}"))
    return out


async def ui_kit(request) -> list[Item]:
    answer = await request.app.state.backend.ui_kit(ident(request))
    return [Item("ui-kit", str(f.get("name")), str(f.get("kind") or "fragment"), "shipped",
                 "/help/rachana-reference#fragments-and-the-ui-kit")
            for f in _rows(answer, "fragments", "items") if isinstance(f, dict) and f.get("name")]


# ---- Analysis -------------------------------------------------------------------------------------------------------------------

async def reports(request) -> list[Item]:
    answer = await request.app.state.backend.bi("GET", "/catalogue", ident(request), sort="date")
    out = []
    for r in _rows(answer, "items"):
        if not r.get("id"):
            continue
        model = r.get("model") or r.get("dataset")
        status = str(r.get("status") or ("published" if r.get("publishedAt") else "draft"))
        if r.get("endorsement") and r["endorsement"] != "none":
            status += f" · {r['endorsement']}"
        out.append(Item("reports", str(r.get("title") or r.get("id")), str(r.get("kind") or "report"), status,
                        f"/bi/r/{quote(str(r['id']), safe='')}", uses=(f"tables:{model}",) if model else (),
                        changed=_ms(r.get("publishedAt") or r.get("updated")), by=str(r.get("owner") or "")))
    return out


async def pivot_views(request) -> list[Item]:
    answer = await request.app.state.backend.pivot("GET", "/views", ident(request))
    out = []
    for v in _rows(answer, "views"):
        vid = str(v.get("id") or (f"{v.get('owner')}/{v.get('name')}" if v.get("name") else ""))
        if vid:
            out.append(Item("pivot-views", str(v.get("name") or vid), "pivot view", "shared" if v.get("sharedWith") else "saved",
                            "/pivot?" + urlencode({"view": vid}), uses=tuple(f"tables:{s}" for s in [v.get("source")] if s),
                            changed=_ms(v.get("saved") or v.get("updated")), by=str(v.get("owner") or "")))
    return out


async def functions(request) -> list[Item]:
    answer = await request.app.state.backend.pivot("GET", "/functions", ident(request))
    out = []
    for f in _rows(answer, "functions"):
        if f.get("name"):
            status = str(f.get("status") or "")
            out.append(Item("functions", str(f.get("name")), f"{f.get('language') or 'formula'} function", status, "/pivot#functions",
                            changed=_ms(f.get("updated") or f.get("saved")), by=str(f.get("owner") or ""),
                            draft=status == "draft", review=status in ("proposed", "pending")))
    return out


# ---- Work -----------------------------------------------------------------------------------------------------------------------

async def designs(request) -> list[Item]:
    """My drafts: the person's screen designs (the server keeps them; nobody else's are ever listed), newest first."""
    try:
        data = await request.app.state.backend.designs("GET", "", ident(request))
    except BackendError as e:
        request.state.build_design_error = e                # the page says why (and answers its status), as My designs did
        raise
    request.state.build_designs = data                     # the drafts list draws the designs with their own actions (rename, ...)
    return [Item("drafts", str(d.get("name") or "Untitled"), "screen design", str(d.get("status") or "draft"), f"/build/d/{d['id']}",
                 uses=tuple(f"sutras:{b.split('@')[0]}" for b in [d.get("base")] if b), changed=_ms(d.get("updated")), by="you", draft=True,
                 extra={"design": d})
            for d in sorted(_rows(data, "designs"), key=lambda d: d.get("updated", 0), reverse=True) if d.get("id")]


def drafts(have: dict) -> list[Item]:
    own = list(have.get("drafts") or [])
    return own + [it for k, items in have.items() if k != "drafts" for it in items if it.draft]


async def proposals(request) -> list[Item]:
    answer = await request.app.state.backend.proposals(ident(request), status="proposed")
    return [Item("review", str(p.get("name") or p.get("sutra") or p.get("id")), "screen proposal", "waiting for review",
                 f"/build/reviews/{quote(str(p.get('id')), safe='')}", changed=_ms(p.get("proposedAt") or p.get("created")),
                 by=str(p.get("proposedBy") or p.get("author") or ""), review=True)
            for p in _rows(answer, "proposals") if p.get("id")]


def waiting(have: dict) -> list[Item]:
    return list(have.get("review") or []) + [it for k, items in have.items() if k != "review" for it in items if it.review]


def recent(have: dict) -> list[Item]:
    week = (time.time() - 14 * 86400) * 1000
    every = [it for k, items in have.items() if k not in ("review",) for it in items if it.changed >= week]
    return sorted(every, key=lambda i: i.changed, reverse=True)[:25]


for _t in (
    ObjectType("connectors", "data", "Connectors", "plug", "bring", requires=("admin", "admin-page:connectors"), new="/admin/connectors#new",
               new_label="Connector", source=connectors, blurb="Where each pack reads its data"),
    ObjectType("lakes", "data", "Lakes", "layers", "bring", requires=("admin", "admin-page:lakes", "feature:lakes"), new="/admin/lakes",
               new_label="Load a file into a lake", source=lakes, blurb="Delta and Iceberg tables, loaded and maintained"),
    ObjectType("tables", "data", "Tables & models", "table", "shape", requires=("bi", "word:BI"), new="/build/tables?new=table",
               new_label="Table or model", source=tables, blurb="Virtual tables and semantic models"),
    ObjectType("python-tables", "data", "Python tables", "filetype-py", "bring", requires=("bi", "word:BI"), new="/build/tables?new=python",
               new_label="Python table", source=python_tables, blurb="Tables a Python step makes"),
    ObjectType("sutras", "screens", "Sutras", "window", "show", requires=("feature:build",), new="/build/new", new_label="Screen",
               source=sutras, blurb="Screens: how an entity is drawn"),
    ObjectType("packs", "screens", "Packs", "box-seam", "package", requires=("feature:build",), new="/build/pack/new", new_label="Pack",
               source=packs, blurb="A domain: its kinds, screens and data"),
    ObjectType("ui-kit", "screens", "UI kit", "palette", "package", requires=("feature:build",), source=ui_kit,
               blurb="Fragments every screen may use"),
    ObjectType("reports", "analysis", "Reports & boards", "file-earmark-bar-graph", "show", requires=("bi", "word:BI"), new="/bi/designer",
               new_label="Report or board", source=reports, blurb="BI reports and boards"),
    ObjectType("pivot-views", "analysis", "Pivot views", "grid-3x3-gap", "show", requires=("word:PIVOT",), new="/pivot", new_label="Pivot view",
               source=pivot_views, blurb="Saved pivots of any table"),
    ObjectType("functions", "analysis", "Functions", "braces", "package", requires=("word:PIVOT",), new="/pivot#functions",
               new_label="Function", source=functions, blurb="Your own formula, SQL and Python functions"),
    ObjectType("drafts", "work", "My drafts", "pencil", source=designs, derived=drafts, blurb="Everything of yours not yet live"),
    ObjectType("review", "work", "Waiting for review", "patch-check", source=proposals, derived=waiting,
               blurb="Proposals waiting for an approver"),
    ObjectType("recent", "work", "Recently changed", "clock-history", derived=recent, blurb="What changed in the last two weeks"),
):
    register(_t)
