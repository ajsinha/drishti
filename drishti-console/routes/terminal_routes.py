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

"""The terminal: home, command dispatch (<GO>) and entity views."""
from __future__ import annotations

import asyncio
import re
from urllib.parse import parse_qs, quote

from fastapi import APIRouter, Query, Request
from fastapi.responses import JSONResponse, RedirectResponse

from core import about_index, asof, collab as collab_core, compare_side, controls, screen_grid
from core.settings import changed_at
from core.backend import BackendError
from core.csrf import BodyError, json_body
from routes import api_routes, designer_routes, history_routes
from routes.common import ident, packs, render

router = APIRouter(include_in_schema=False)



@router.get("/t")
async def home(request: Request, error: str | None = None):
    current = await packs(request)
    examples = [(cmd, what, p["title"]) for p in current for cmd, what in p["examples"]]
    pinned = (getattr(request.state, "settings", None) or {}).get("pinned") or []
    return render(request, "terminal/home.html", examples=examples, packs=current, error=error, pinned=pinned)


@router.post("/pin/{kind}/{id_:path}")
async def pin(request: Request, kind: str, id_: str):
    """Pins an entity to the terminal home, or unpins it (W21)."""
    me = ident(request)
    s = await request.app.state.user_settings.get(request.app.state.backend, me, changed_at(request))
    pins = [p for p in s.get("pinned") or [] if not (p.get("kind") == kind and p.get("id") == id_)]
    if len(pins) == len(s.get("pinned") or []):
        pins = ([{"kind": kind, "id": id_}] + pins)[:20]
    try:
        await request.app.state.backend.patch_settings({"pinned": pins}, me)
    except BackendError:
        pass
    request.app.state.user_settings.forget(me.user if me else "", request)
    return RedirectResponse(f"/v/{quote(kind)}/{quote(id_)}", status_code=303)


_SEARCH = re.compile(r"(?is)^\s*\S+\s+(where|order\s+by|limit)\s+.+")


FORM_WORD = "FORM"        # a reserved command word: query by form (routes/form_routes.py)
BI_WORDS = ("BI", "RUPAKA")   # reserved command words: the BI page, RUPAKA being the older alias (a pack cannot define a mnemonic with either name)


async def rupaka(request: Request, rest: str):
    """``BI <GO>`` (or RUPAKA) the BI home; ``BI WORKSPACE`` my workspace; ``BI RUNS`` my runs; ``BI CATALOGUE`` the catalogue; ``BI <report-or-board>`` a published report
    by id or title (one match opens it, several open the catalogue searched for what was typed). Any case."""
    if not request.app.state.bi_on or not (getattr(request.state, "bi_me", None) or {}).get("enabled"):
        why = ("Rupaka (business intelligence) is not available to you: it needs the bi power on one of your roles, and BI switched on."
               if request.app.state.bi_on else "Rupaka (business intelligence) is switched off in this console (bi.enabled).")
        return RedirectResponse(f"/t?error={quote(why)}", status_code=303)
    word = rest.upper()
    if not rest:
        return RedirectResponse("/bi", status_code=303)
    if word in ("WORKSPACE", "WORKSPACES"):
        return RedirectResponse("/bi/w/me", status_code=303)
    if word in ("RUNS", "RUN"):                                       # BI RUNS <GO>: my runs (docs/architecture/BI_RUNS.md section 7.1)
        return RedirectResponse("/bi/w/me?tab=runs", status_code=303)
    if word in ("CATALOGUE", "CATALOG"):
        return RedirectResponse("/bi/catalogue", status_code=303)
    if word in ("SETTINGS",):
        return RedirectResponse("/admin/bi", status_code=303)
    try:
        found = (await request.app.state.backend.bi("GET", "/catalogue", ident(request), q=rest)).get("items") or []
    except BackendError:
        found = []
    exact = [i for i in found if i["id"].lower() == rest.lower() or i["title"].lower() == rest.lower()]
    pick = exact[0] if exact else found[0] if len(found) == 1 else None
    if pick:
        where = "r" if pick.get("kind") == "report" else "i"             # a report opens drawn; every other item opens as its page
        return RedirectResponse(f"/bi/{where}/{quote(pick['id'], safe=':')}", status_code=303)
    return RedirectResponse(f"/bi/catalogue?q={quote(rest)}", status_code=303)


@router.get("/go")
async def go(request: Request, q: str = ""):
    typed = q.replace("<GO>", "").replace("<go>", "").strip()
    if typed.split(None, 1)[:1] and typed.split(None, 1)[0].upper() in BI_WORDS:        # reserved (RUPAKA.md section 4), whatever packs define
        return await rupaka(request, typed.split(None, 1)[1].strip() if " " in typed else "")
    words = q.replace("<GO>", "").replace("<go>", "").split()
    if words and words[0].upper() == FORM_WORD:                    # reserved too: FORM <pack> [kind] opens the pack's search form
        if len(words) == 1:
            return RedirectResponse("/forms", status_code=303)
        return RedirectResponse(f"/form/{quote(words[1])}" + (f"?kind={quote(words[2])}" if len(words) > 2 else ""), status_code=303)
    if _SEARCH.match(q.replace("<GO>", "")):
        return RedirectResponse(f"/s?q={quote(q.replace('<GO>', '').strip())}", status_code=303)
    try:
        r = await request.app.state.backend.command(q, ident(request))
    except BackendError as e:
        text = q.replace("<GO>", "").strip()
        if e.code in ("DRS-4021", "DRS-4022"):  # a range after an entity that cannot be read (TRD 1000001 -5): say so, with what to type
            return RedirectResponse(f"/t?error={quote(f'{e.code} {e.detail}')}", status_code=303)
        if len(text.split()) >= 2:          # not a command: perhaps plain words ("live trades over 5m"); show what they mean
            return RedirectResponse(f"/s?words={quote(text)}", status_code=303)
        return RedirectResponse(f"/t?error={quote(e.detail)}", status_code=303)
    if r.get("pack"):                       # a pack's code typed alone (MKT): its overview
        return RedirectResponse(f"/p/{quote(r['pack'])}", status_code=303)
    hist = (r.get("history") or {}).get("query")   # TRD 1000015 30: the entity over time (HISTORY_QUERIES.md section 4.3)
    if not r.get("ref"):                    # several (or no) entities: a pick list, as on a Bloomberg terminal
        return RedirectResponse(f"/s?q={quote(r.get('list') or q)}" + (f"&{hist}" if hist else ""), status_code=303)
    if hist:
        return RedirectResponse(f"/v/{r['ref']['kind']}/{quote(r['ref']['id'])}/history?{hist}", status_code=303)
    return RedirectResponse(f"/v/{r['ref']['kind']}/{quote(r['ref']['id'])}", status_code=303)


@router.get("/impact/{kind}/{id_:path}")
async def impact(request: Request, kind: str, id_: str):
    try:
        data = await request.app.state.backend.impact(kind, id_, ident(request))
    except BackendError as e:
        return render(request, "terminal/missing.html", status_code=e.page_status, kind=kind, id=id_, error=e)
    return render(request, "terminal/impact.html", kind=kind, id=id_, data=data, screen="impact")


def _shown(v) -> str:
    """A diff value as text: numbers with separators (up to six decimals), lists and objects compactly."""
    if v is None:
        return "—"
    if isinstance(v, bool):
        return "true" if v else "false"
    if isinstance(v, (int, float)):
        text = f"{v:,.6f}".rstrip("0").rstrip(".") if isinstance(v, float) else f"{v:,}"
        return text.replace("-", "−")
    if isinstance(v, (list, dict)):
        return "[]" if v == [] else str(v)[:80]
    return str(v)


def _delta(d) -> str:
    if d is None:
        return ""
    text = f"{d:+,.6f}".rstrip("0").rstrip(".")
    return text.replace("-", "−")


@router.get("/p/{name}")
async def pack_overview(request: Request, name: str):
    """A pack at a glance: every kind you may open, its mnemonic, how many there are, an example, the key fields; then what its
    README says (the summary and the Data structures of the kinds you may open: the server leaves the others out)."""
    try:
        data, readme = await asyncio.gather(request.app.state.backend.pack_overview(name, ident(request)), _pack_readme(request, name))
    except BackendError as e:
        return render(request, "terminal/missing.html", status_code=e.page_status, kind="pack", id=name, error=e)
    return render(request, "terminal/pack.html", p=data, readme=readme, screen="pack")


_README_SECTION = re.compile(r"(?ms)^## Data structures[ \t]*\n(.*?)(?=^## |\Z)")


async def _pack_readme(request: Request, name: str) -> dict:
    """The README's summary and its Data structures section as HTML; none (no README, an older server) leaves the section out, never the page."""
    try:
        r = await request.app.state.backend.pack_readme(name, ident(request))
    except Exception:                            # noqa: BLE001
        return {}
    if not isinstance(r, dict):
        return {}
    meta = r.get("meta") or {}
    found = _README_SECTION.search(r.get("markdown") or "")
    html_ = ""
    if found and found.group(1).strip():
        from core import readmes
        from routes.common import library
        html_ = readmes.render(found.group(1), readmes.source_of(request.app.state.docs_dir, "pack", meta.get("pack") or name), await library(request))
    return {"summary": meta.get("summary") or "", "html": html_, "sources": meta.get("sources") or []} if (meta.get("summary") or html_) else {}


@router.get("/s")
async def search(request: Request, q: str = "", vs: str = "", words: str = ""):
    phrase = None
    if words.strip():                       # plain words: show the query they make, and let the person run it
        try:
            phrase = await request.app.state.backend.phrase(words.strip(), ident(request))
        except BackendError as e:
            if e.code in ("DRS-4021", "DRS-4022"):   # a bare id with a range that is none (1000015 -5): what to type, as on /go (QA1-203)
                return RedirectResponse(f"/t?error={quote(f'{e.code} {e.detail}')}", status_code=303)
            phrase = {"problem": e.detail, "steps": [], "ignored": []}
        if phrase.get("command") and not phrase.get("ignored") and not q.strip():   # trade 1000015 last month: read fully, so open it
            return RedirectResponse(f"/go?q={quote(phrase['command'])}", status_code=303)
    """Structured search (W17): entities by field values, e.g. TRD where mtm > 1m and currency = 'EUR' order by mtm desc."""
    data, error = None, None
    if q.strip():
        limit = (getattr(request.state, "settings", None) or {}).get("searchLimit") or 100
        asked = q if re.search(r"(?i)\slimit\s+\S+\s*$", q) else f"{q.rstrip()} limit {limit}"   # the user's default size
        try:
            if vs:     # the same search on another business date, side by side: each number with its change
                data = await request.app.state.backend.search_compare(asked, vs, "", ident(request))
            else:
                data = await request.app.state.backend.search(asked, ident(request))
        except BackendError as e:
            error = e
    hq = history_routes.history_query(request.query_params)   # a pick list of a history command: each row opens its history
    rows = []
    if data:
        for r in data.get("rows", []):
            cells = []
            for c in data.get("columns", []):
                v = r["values"].get(c)
                if vs and isinstance(v, dict):            # compared: the later value, and its change
                    to, delta = v.get("to"), v.get("delta")
                    num = isinstance(to, (int, float)) and not isinstance(to, bool)
                    text = _shown(to) + (f"  ({_delta(delta)})" if delta not in (None, 0, 0.0) else "")
                    cells.append((text, num))
                else:
                    cells.append((_shown(v), isinstance(v, (int, float)) and not isinstance(v, bool)))
            rows.append({"ref": r["ref"], "title": r.get("title") or r["ref"]["id"], "cells": cells, "status": r.get("status")})
    titled = any(r["title"] != r["ref"]["id"] for r in rows)       # a title that only repeats the id is left out
    chart_rows = search_chart_rows(data, q, titled) if data and rows and not vs else None
    pivot_saved = None
    if data and data.get("pivot") and not vs:                      # the kind's pack opts its results into a Pivot tab
        state = await request.app.state.pivots.state(request.app.state.backend, ident(request))
        pivot_saved = request.app.state.pivots.saved_search(state, data.get("kind", ""))
    return render(request, "terminal/search.html", q=q, vs=vs, words=words, phrase=phrase, data=data, rows=rows, error=error, titled=titled,
                  screen="search", pivot_saved=pivot_saved, hq=hq, chart_rows=chart_rows)


def _chart_value(v):
    """A search value as the chart reads it: numbers, text and booleans as they are, a list or an object as its shown text."""
    if v is None or isinstance(v, (int, float, str)):
        return v
    return _shown(v)


def search_chart_rows(data: dict, q: str, titled: bool) -> dict:
    """The raw values of a search's shown rows for Chart any table (TABLE_CHARTS.md section 8.4, the search adapter): the id, the
    title when shown, then each column, in the table's order; masked values stay the server's mask. ``pivot`` lists the fields
    the kind's pack offers to the search pivot (Chart all N), when it does."""
    cols = data.get("columns", [])
    labels = data.get("labels") or {}
    first = data.get("mnemonic") or data.get("kind") or "Id"
    fields = [{"name": first, "label": first, "id": True, "kind": data.get("kind")}]
    if titled:
        fields.append({"name": "Title", "label": "Title", "type": "text"})
    seen = {f["name"] for f in fields}
    for c in cols:                                   # named by their labels, as the table heads them (unique); the path for the pivot
        name = labels.get(c) or c
        while name in seen:
            name += " "
        seen.add(name)
        fields.append({"name": name, "label": labels.get(c) or c, "path": c})
    out = []
    for r in data.get("rows", []):
        line = [r["ref"]["id"]] + ([r.get("title") or r["ref"]["id"]] if titled else [])
        line += [_chart_value(r["values"].get(c)) for c in cols]
        out.append(line)
    pivot = data.get("pivot") or {}
    return {"kind": data.get("kind"), "q": q, "matched": data.get("matched"), "fields": fields, "rows": out,
            "pivot": [f.get("name") if isinstance(f, dict) else f for f in (pivot.get("fields") or [])] if isinstance(pivot, dict) else []}


@router.post("/s/watch")
async def watch_search(request: Request):
    """Saves a search's first 50 results as a monitor: the results, watched live."""
    form = parse_qs((await request.body()).decode("utf-8", "replace"))   # a plain form post: no multipart dependency
    q = form.get("q", [""])[0]
    name = re.sub(r"[^A-Za-z0-9 ._-]", "", form.get("name", [""])[0])[:64].strip() or "Search results"
    backend = request.app.state.backend
    try:
        data = await backend.search(q, ident(request))
        entities = [{"kind": r["ref"]["kind"], "id": r["ref"]["id"]} for r in data.get("rows", [])[:50]]
        if not entities:
            return RedirectResponse(f"/s?q={quote(q)}", status_code=303)
        await backend.mine("PUT", f"/monitors/{quote(name)}", ident(request), {"entities": entities})
    except BackendError as e:
        return render(request, "terminal/search.html", q=q, data=None, rows=[], error=e, titled=False, screen="search")
    return RedirectResponse(f"/m/{quote(name)}", status_code=303)


@router.get("/compare/{kind}/{id_:path}")
async def compare(request: Request, kind: str, id_: str, from_: str = Query("", alias="from"), to: str = "",
                  fromKnownAt: str = "", only: str = ""):
    """History (W16): what changed in an entity between two business dates, or since what was known at a time."""
    backend = request.app.state.backend
    info = request.state.business_date or {}
    params = {"from": from_, "to": to}
    if fromKnownAt:
        params["fromKnownAt"] = asof.to_instant(fromKnownAt, info.get("zone") or "America/New_York") or ""
    try:
        data = await backend.history_diff(kind, id_, ident(request), **params)
    except BackendError as e:
        if e.status == 404 or e.code in ("DRS-4003", "DRS-1001"):
            return render(request, "terminal/compare.html", kind=kind, id=id_, data=None, error=e, form=params, screen="compare",
                          from_known=fromKnownAt)
        return render(request, "terminal/missing.html", status_code=e.page_status, kind=kind, id=id_, error=e)
    rows = [dict(c, before_text=_shown(c.get("before")), after_text=_shown(c.get("after")), delta_text=_delta(c.get("delta")), where=compare_side.where(c.get("path", "")))
            for c in data.get("changes", []) if not only or c.get("kind") == only]
    side = []
    a_date, b_date = (data.get("from") or {}).get("businessDate"), (data.get("to") or {}).get("businessDate")
    if a_date and b_date and not fromKnownAt:          # the screens of the two dates side by side (a known-at restatement has one date)
        try:
            side = compare_side.sections(await backend.view_on(kind, id_, ident(request), a_date), await backend.view_on(kind, id_, ident(request), b_date))
            deep = compare_side.nested(rows) if side else None       # the changes inside the lists (cash flows, resets, events) too
            if deep:
                side.append(deep)
        except BackendError:
            side = []                                  # the change list still answers
    return render(request, "terminal/compare.html", kind=kind, id=id_, data=data, rows=rows, error=None, only=only, screen="compare", side=side,
                  form={"from": from_ or (data.get("from") or {}).get("businessDate") or "", "to": to or (data.get("to") or {}).get("businessDate") or ""},
                  from_known=fromKnownAt)


@router.api_route("/me/arrange/{sutra}", methods=["GET", "PUT", "DELETE"])
async def my_arrangement(request: Request, sutra: str):
    """The reader's own arrangement of a grid screen (SUTRA_NEXT.md 13), kept by the server per reader and Sutra: GET it, PUT it whole
    (the split's columns, what is folded, panel moves), DELETE it ("Reset layout")."""
    if not re.fullmatch(r"[a-z0-9][a-z0-9-]{0,63}", sutra or ""):
        return JSONResponse({"code": "DRS-5001", "detail": "which screen?"}, status_code=400)
    body = None
    if request.method == "PUT":
        try:
            body = await json_body(request, limit=32768)
        except BodyError as e:
            return JSONResponse({"code": e.code, "detail": e.detail}, status_code=e.status)
    try:
        got = await request.app.state.backend._send(request.method, f"/me/arrange/{quote(sutra)}", ident(request),
                                                    **({"json": body} if body is not None else {}))
    except BackendError as e:
        return JSONResponse({"code": e.code, "detail": e.detail}, status_code=e.status if 400 <= e.status < 600 else 502)
    return JSONResponse(got if isinstance(got, dict) else {})


@router.get("/v/{kind}/{id_:path}/about")
async def about(request: Request, kind: str, id_: str, generation: int = 0):
    """The body of the About this page drawer (an HTML fragment), asked for when the drawer first opens, never with the view."""
    me = ident(request)
    extra = {}                                       # the language of the pack's text: ?locale=, else the user's setting, else the browser's
    loc = request.query_params.get("locale") or (await request.app.state.user_settings.get(request.app.state.backend, me)).get("locale")
    if loc:
        extra["locale"] = loc
    if request.headers.get("accept-language"):
        extra["accept_language"] = request.headers["accept-language"]
    try:
        ex = await request.app.state.backend.explain(kind, id_, me, generation or None, **extra)
    except BackendError as e:
        return render(request, "terminal/_about.html", status_code=e.page_status, ex=None, error=e, kind=kind, id=id_)
    return render(request, "terminal/_about.html", ex=ex, error=None, kind=kind, id=id_, index=about_index.build(ex))


@router.post("/v/{kind}/{id_:path}/ask")
async def ask(request: Request, kind: str, id_: str):
    """Ask about this page: a thin proxy to the server, which holds the credentials and builds the prompt. JSON in and out; a problem
    keeps the server's code (DRS-4007/4008/4009) so the drawer can say what happened. The page's own explanation never depends on it."""
    try:
        body = await json_body(request, limit=4096)
    except BodyError as e:
        return JSONResponse({"code": e.code, "detail": e.detail}, status_code=e.status)
    try:
        out = await request.app.state.backend.ask(kind, id_, ident(request), str(body.get("question", "")), request.query_params.get("locale"),
                                                  request.headers.get("accept-language"))
    except BackendError as e:
        return JSONResponse({"code": e.code, "detail": e.detail}, status_code=e.page_status)
    return JSONResponse({"answer": out.get("answer", ""), "sources": out.get("sources", [])})


@router.get("/v/{kind}/{id_:path}/panels")
async def view_panels(request: Request, kind: str, id_: str, panels: str = ""):
    """Some panels of a view as ready HTML, built with the controls' values (``?panels=cashflows,legs&ctl.leg=L2``): what the page asks
    for when a control changes, so only the panels that read it move. Also the strip and the controls (their options and values may
    have moved: a cascading child). ``panels=-`` builds none. The server builds only the named panels."""
    ctl = controls.from_query(request.query_params)
    ids = [p for p in panels.split(",") if p and p != "-"] if panels else None
    try:
        chosen = request.query_params.get("sutra")
        mine = await designer_routes.design_for(request, kind)          # the Sutra designer: the reader's own page or draft
        vm = await request.app.state.backend.view(kind, id_, ident(request), controls=ctl, panels=ids, **({"sutra": chosen} if chosen else {}),
                                                  **({"design": mine} if mine else {}))
    except BackendError as e:
        return JSONResponse({"code": e.code, "detail": e.detail}, status_code=e.page_status)
    layout = await request.app.state.layouts.context(request, vm, False)
    if vm.get("layout"):
        layout["panels"] = vm.get("panels") or []      # a grid screen: the Sutra's sizes (screen-grid.js keeps the reader's)
    shown, _pivots = await request.app.state.pivots.view(request, vm, layout["panels"])
    env = request.app.state.templates.env
    parts = env.get_template("_macros/view.html").module
    out = [{"id": p["id"], "kind": p["kind"], "title": p.get("title") or "", "html": api_routes._panel_html(request, p, ref={"kind": kind, "id": id_}),
            **({"provenance": p["provenance"]} if p.get("provenance") else {})} for p in shown]   # a panel's own source: its freshness (SUTRA_NEXT.md 9)
    prov = vm.get("provenance") or {}
    return JSONResponse({"panels": out, "strip": str(parts.strip(vm)).strip(), "controls": vm.get("controls") or [],
                         "timings": vm.get("timings"),
                         "provenance": {k: prov.get(k) for k in ("source", "fetchedAt", "updatedAt", "staleAfter", "stale", "live", "generation")}})


def _wired_html(request: Request, kind: str, id_: str, panel: dict) -> dict:
    """A panel of a Sutra Next answer as ready HTML (the macro of first paint), with its id, kind and title."""
    return {"id": panel["id"], "kind": panel["kind"], "title": panel.get("title") or "",
            "html": api_routes._panel_html(request, panel, ref={"kind": kind, "id": id_})}


def _children_html(request: Request, kind: str, id_: str, groups: list) -> list:
    return [{**{k: g.get(k) for k in ("parent", "group", "place", "show", "hidden")},
             "panels": [_wired_html(request, kind, id_, p) for p in (g.get("panels") or [])]} for g in (groups or [])]


def _selection_problem(e: BackendError, tokens: list) -> JSONResponse:
    """A click's or children's refusal. When the selections cannot be used any more (the server that held them went away, or they expired),
    the answer says so in words with ``reset``: the page forgets them and the reader picks again (one server per screen, SUTRA_NEXT.md 7)."""
    from core.cluster import token_node
    body = {"code": e.code, "detail": e.detail}
    if e.code == "DRS-5052" and "selections were reset" in (e.detail or ""):
        node = next((token_node(t) for t in tokens if token_node(t)), "")
        body.update(reset=True, message=f"Your selections were reset because server {node or 'that held them'} went away. Pick again.")
    elif e.code == "DRS-5002" and tokens:
        body.update(reset=True, message="Your selections were reset: they expired, or the server that held them restarted. Pick again.")
    return JSONResponse(body, status_code=e.page_status)


@router.post("/v/{kind}/{id_:path}/events")
async def view_event(request: Request, kind: str, id_: str):
    """A click on a row or bar of a Sutra Next screen (SUTRA_NEXT.md 7), relayed to the server, which evaluates it as the reader; the
    listening panels and the clicked panel's children come back as ready HTML. Earlier selections travel as the server's tokens."""
    try:
        body = await json_body(request, limit=16384)
    except BodyError as e:
        return JSONResponse({"code": e.code, "detail": e.detail}, status_code=e.status)
    sels = [str(s) for s in (body.get("selections") or []) if isinstance(s, str)][:32]
    try:
        row = int(body.get("row"))
    except (TypeError, ValueError):
        return JSONResponse({"code": "DRS-5001", "detail": "a click names its row"}, status_code=400)
    click = {"panel": str(body.get("panel") or ""), "event": str(body.get("event") or ""), "row": row, "selections": sels}
    try:
        r = await request.app.state.backend.view_event(kind, id_, ident(request), click, controls=controls.from_query(request.query_params),
                                                       sutra=request.query_params.get("sutra") or None,
                                                       **(await designer_routes.design_kw(request, kind)))
    except BackendError as e:
        return _selection_problem(e, sels)
    return JSONResponse({"selection": r.get("selection") or {}, "cleared": r.get("cleared") or [],
                         "panels": [_wired_html(request, kind, id_, p) for p in (r.get("panels") or [])],
                         "children": _children_html(request, kind, id_, r.get("children"))})


@router.get("/v/{kind}/{id_:path}/children/{panel}")
async def view_children(request: Request, kind: str, id_: str, panel: str):
    """The children of one panel (``?sel=<token>`` for each selection held), as ready HTML: what the page asks for when a group shows
    always or is opened (SUTRA_NEXT.md 8)."""
    sels = request.query_params.getlist("sel")[:32]
    try:
        groups = await request.app.state.backend.view_children(kind, id_, ident(request), panel, sels,
                                                               controls=controls.from_query(request.query_params),
                                                               sutra=request.query_params.get("sutra") or None,
                                                               **(await designer_routes.design_kw(request, kind)))
    except BackendError as e:
        return _selection_problem(e, sels)
    return JSONResponse({"children": _children_html(request, kind, id_, groups)})


@router.get("/v/{kind}/{id_:path}")
async def view(request: Request, kind: str, id_: str, embed: int = 0, gen: int = 0, share: str = ""):
    hist = history_routes.history_link(kind, id_, request.query_params)
    if hist:                                        # /v/trade/1000015?days=30, the short form of a history link
        return RedirectResponse(hist, status_code=303)
    sid = collab_core.set_share(share)              # a view opened through a share's link tells the server so (its access log)
    pin = None                                      # how a pinned link (?asOf=&knownAt=&gen=) was honoured, for the banner
    ctl = controls.from_query(request.query_params)  # ?ctl.leg=L2: the controls' values, so a link reproduces what was seen
    more = {"controls": ctl} if ctl else {}
    chosen = (request.query_params.get("sutra") or "").strip()     # ?sutra=book-desk: another Sutra of the kind draws the view
    if chosen:
        more["sutra"] = chosen
    mine = await designer_routes.design_for(request, kind)          # the Sutra designer (ui.designer): the reader's own page, or the draft
    if mine:
        more["design"] = mine
    try:
        try:
            vm = await request.app.state.backend.view(kind, id_, ident(request), **more)
        except BackendError as e:
            if not (getattr(request.state, "pinned", False) and asof.known_at() and e.code == "DRS-1007"):
                raise
            asof.set_known(None)                    # a store with no earlier versions: ask again for the date, say so
            request.state.known_at = None
            vm = await request.app.state.backend.view(kind, id_, ident(request), **more)
            pin = "retry"
    except BackendError as e:
        return render(request, "terminal/missing.html", status_code=e.page_status,
                      kind=kind, id=id_, error=e, embed=bool(embed))
    layout = await request.app.state.layouts.context(request, vm, bool(embed))      # the user's own arrangement, if any
    grid_mine = await _grid_layout(request, vm, layout)                             # a Sutra with layout: (SUTRA_NEXT.md 13)
    panels, pivots = await request.app.state.pivots.view(request, vm, layout["panels"])   # each Pivot tab as the user saved it
    grid = screen_grid.arrange(vm.get("layout"), panels, grid_mine) if vm.get("layout") else None
    main = [p for p in panels if p.get("area") != "right"]
    right = [p for p in panels if p.get("area") == "right"]
    if getattr(request.state, "pinned", False):
        now = (vm.get("provenance") or {}).get("generation")
        pin = pin or ("changed" if gen and now is not None and int(now) != gen else "asit")
    shared = None
    if sid and not embed:                           # who sent it, when, and their note, for the banner (the server renders the note for this reader)
        try:
            shared = await (getattr(request.app.state, "collab", None) or collab_core.Collab(request.app.state.backend)).share(sid, ident(request))
        except BackendError:
            shared = None                           # the banner is a courtesy: never fail the view for it
    calc = {"offered": False} if embed else await request.app.state.calc.context(request, await packs(request), vm["ref"]["kind"])
    return render(request, "terminal/view.html", vm=vm, main=main, right=right, embed=bool(embed), share_url=share_url(request, kind, id_, (vm.get("provenance") or {}).get("generation"), ctl),
                  pin=pin, pin_gen=gen or None, chosen_sutra=chosen, pin_via="history" if request.query_params.get("via") == "history" else None, ctl_query=controls.to_query(ctl), shared=shared, calc=calc, layout=layout, pivots=pivots, hidden_ids=[p["id"] for p in layout["panels"] if p.get("hidden")],
                  designer=designer_routes.view_context(request, mine), grid=grid)


async def _grid_layout(request: Request, vm: dict, layout: dict) -> dict | None:
    """For a view whose Sutra has ``layout:``: the grid replaces layout mode (Alt+L), so the panels are the Sutra's, and the reader's own
    arrangement of this screen is read (``/me/arrange/<sutra>``: the split, what is folded, their panel moves). None for a flowing view."""
    if not vm.get("layout"):
        return None
    layout["panels"] = vm.get("panels") or []
    layout["offered"] = False
    sutra = (vm.get("provenance") or {}).get("sutra") or ""
    if not sutra or ident(request) is None:
        return {}
    try:
        got = await request.app.state.backend._send("GET", f"/me/arrange/{quote(sutra)}", ident(request))
    except BackendError:
        return {}                                   # an older server, or one away: the author's layout
    return got if isinstance(got, dict) else {}


def share_url(request: Request, kind: str, id_: str, generation=None, ctl: dict | None = None) -> str:
    """A link that opens this view as the sender sees it: live, or pinned to the same business date, "known at" instant
    and generation as request parameters (they survive sign-in and never touch the recipient's saved date)."""
    base = str(request.base_url).rstrip("/")
    path = f"/v/{quote(kind)}/{quote(id_)}"
    extra = controls.to_query(ctl) if ctl else ""      # the controls' values travel with the link
    if asof.current() == "live":
        return base + path + ("?" + extra if extra else "")
    known = asof.known_at()
    return (f"{base}{path}?asOf={asof.current()}" + (f"&knownAt={quote(known)}" if known else "")
            + (f"&gen={int(generation)}" if generation is not None and str(generation).isdigit() else "") + (f"&{extra}" if extra else ""))
