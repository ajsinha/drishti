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

import re
from urllib.parse import parse_qs, quote

from fastapi import APIRouter, Query, Request
from fastapi.responses import JSONResponse, RedirectResponse

from core import about_index, asof, collab as collab_core
from core.backend import BackendError
from core.csrf import BodyError, json_body
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
    s = await request.app.state.user_settings.get(request.app.state.backend, me)
    pins = [p for p in s.get("pinned") or [] if not (p.get("kind") == kind and p.get("id") == id_)]
    if len(pins) == len(s.get("pinned") or []):
        pins = ([{"kind": kind, "id": id_}] + pins)[:20]
    try:
        await request.app.state.backend.patch_settings({"pinned": pins}, me)
    except BackendError:
        pass
    request.app.state.user_settings.forget(me.user if me else "")
    return RedirectResponse(f"/v/{quote(kind)}/{quote(id_)}", status_code=303)


_SEARCH = re.compile(r"(?is)^\s*\S+\s+(where|order\s+by|limit)\s+.+")


@router.get("/go")
async def go(request: Request, q: str = ""):
    if _SEARCH.match(q.replace("<GO>", "")):
        return RedirectResponse(f"/s?q={quote(q.replace('<GO>', '').strip())}", status_code=303)
    try:
        r = await request.app.state.backend.command(q, ident(request))
    except BackendError as e:
        text = q.replace("<GO>", "").strip()
        if len(text.split()) >= 2:          # not a command: perhaps plain words ("live trades over 5m"); show what they mean
            return RedirectResponse(f"/s?words={quote(text)}", status_code=303)
        return RedirectResponse(f"/t?error={quote(e.detail)}", status_code=303)
    if r.get("pack"):                       # a pack's code typed alone (MKT): its overview
        return RedirectResponse(f"/p/{quote(r['pack'])}", status_code=303)
    if not r.get("ref"):                    # several (or no) entities: a pick list, as on a Bloomberg terminal
        return RedirectResponse(f"/s?q={quote(r.get('list') or q)}", status_code=303)
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
    """A pack at a glance: every kind you may open, its mnemonic, how many there are, an example, the key fields."""
    try:
        data = await request.app.state.backend.pack_overview(name, ident(request))
    except BackendError as e:
        return render(request, "terminal/missing.html", status_code=e.page_status, kind="pack", id=name, error=e)
    return render(request, "terminal/pack.html", p=data, screen="pack")


@router.get("/s")
async def search(request: Request, q: str = "", vs: str = "", words: str = ""):
    phrase = None
    if words.strip():                       # plain words: show the query they make, and let the person run it
        try:
            phrase = await request.app.state.backend.phrase(words.strip(), ident(request))
        except BackendError as e:
            phrase = {"problem": e.detail, "steps": [], "ignored": []}
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
    pivot_saved = None
    if data and data.get("pivot") and not vs:                      # the kind's pack opts its results into a Pivot tab
        state = await request.app.state.pivots.state(request.app.state.backend, ident(request))
        pivot_saved = request.app.state.pivots.saved_search(state, data.get("kind", ""))
    return render(request, "terminal/search.html", q=q, vs=vs, words=words, phrase=phrase, data=data, rows=rows, error=error, titled=titled,
                  screen="search", pivot_saved=pivot_saved)


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
    rows = [dict(c, before_text=_shown(c.get("before")), after_text=_shown(c.get("after")), delta_text=_delta(c.get("delta")))
            for c in data.get("changes", []) if not only or c.get("kind") == only]
    return render(request, "terminal/compare.html", kind=kind, id=id_, data=data, rows=rows, error=None, only=only, screen="compare",
                  form={"from": from_ or (data.get("from") or {}).get("businessDate") or "", "to": to or (data.get("to") or {}).get("businessDate") or ""},
                  from_known=fromKnownAt)


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


@router.get("/v/{kind}/{id_:path}")
async def view(request: Request, kind: str, id_: str, embed: int = 0, gen: int = 0, share: str = ""):
    sid = collab_core.set_share(share)              # a view opened through a share's link tells the server so (its access log)
    pin = None                                      # how a pinned link (?asOf=&knownAt=&gen=) was honoured, for the banner
    try:
        try:
            vm = await request.app.state.backend.view(kind, id_, ident(request))
        except BackendError as e:
            if not (getattr(request.state, "pinned", False) and asof.known_at() and e.code == "DRS-1007"):
                raise
            asof.set_known(None)                    # a store with no earlier versions: ask again for the date, say so
            request.state.known_at = None
            vm = await request.app.state.backend.view(kind, id_, ident(request))
            pin = "retry"
    except BackendError as e:
        return render(request, "terminal/missing.html", status_code=e.page_status,
                      kind=kind, id=id_, error=e, embed=bool(embed))
    layout = await request.app.state.layouts.context(request, vm, bool(embed))      # the user's own arrangement, if any
    panels, pivots = await request.app.state.pivots.view(request, vm, layout["panels"])   # each Pivot tab as the user saved it
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
    return render(request, "terminal/view.html", vm=vm, main=main, right=right, embed=bool(embed), share_url=share_url(request, kind, id_, (vm.get("provenance") or {}).get("generation")),
                  pin=pin, pin_gen=gen or None, shared=shared, calc=calc, layout=layout, pivots=pivots, hidden_ids=[p["id"] for p in layout["panels"] if p.get("hidden")])


def share_url(request: Request, kind: str, id_: str, generation=None) -> str:
    """A link that opens this view as the sender sees it: live, or pinned to the same business date, "known at" instant
    and generation as request parameters (they survive sign-in and never touch the recipient's saved date)."""
    base = str(request.base_url).rstrip("/")
    path = f"/v/{quote(kind)}/{quote(id_)}"
    if asof.current() == "live":
        return base + path
    known = asof.known_at()
    return (f"{base}{path}?asOf={asof.current()}" + (f"&knownAt={quote(known)}" if known else "")
            + (f"&gen={int(generation)}" if generation is not None and str(generation).isdigit() else ""))
