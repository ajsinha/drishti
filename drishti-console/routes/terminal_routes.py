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

from core import about_index, asof, collab as collab_core, compare_side, controls, live_results, screen_grid
from core.settings import changed_at
from core.backend import BackendError
from core.csrf import BodyError, json_body
from routes import api_routes, designer_routes, history_routes, phone_routes
from core.edition import ADMIN_PAGES, mnemonics, words as reserved_words
from routes.common import ident, packs, render
from core import distribution as _dist
from core import modules as _modules
from core import plugins as _plugins
from routes.pivot_routes import redirect_for as pivot_redirect

router = APIRouter(include_in_schema=False)



@router.get("/t")
async def home(request: Request, error: str | None = None):
    current = await packs(request)
    ed = getattr(request.state, "edition", None) or request.app.state.editions.instance
    examples = [(cmd, what, p["title"]) for p in current for cmd, what in p["examples"] if ed.command_shown(str(cmd).split()[0])]
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
FUNC_WORD = "FUNC"        # a reserved command word: the command directory, Functions (routes/functions_routes.py)
WHAT_WORD = "WHAT"        # a reserved command word: the Knowledge pages and their glossary (routes/help_routes.py, docs/knowledge/README.md)
DATAMAP_WORD = "DATAMAP"  # a reserved command word: the data map, the packs' kinds and joins (routes/datamap_routes.py)
ADMIN_WORD = "ADMIN"       # a reserved command word: the Admin pages by command (a lens whose bar draws no Admin menu: Drishti Trading)
EXPORT_WORDS = ("EXPORT", "DATA")   # reserved command words: Export data, every dataset and a day's download (routes/data_routes.py); DATA is the older alias
BI_WORDS = ("BI", "RUPAKA")   # reserved command words: the BI page, RUPAKA being the older alias (a pack cannot define a mnemonic with either name)


async def rupaka(request: Request, rest: str):
    """``BI <GO>`` (or RUPAKA) the BI home; ``BI WORKSPACE`` my workspace; ``BI RUNS`` my runs; ``BI CATALOGUE`` the catalogue; ``BI <report-or-board>`` a published report
    by id or title (one match opens it, several open the catalogue searched for what was typed). Any case."""
    if not request.app.state.bi_on or not (getattr(request.state, "bi_me", None) or {}).get("enabled"):
        why = ("Rupaka (business intelligence) is not available to you: it needs the bi power on one of your roles, and BI switched on."
               if request.app.state.bi_on else "Rupaka (business intelligence) is switched off in this console (bi.enabled).")
        return RedirectResponse(f"/t?error={quote(why)}", status_code=303)
    word = rest.upper()
    if not rest:                                                      # BI's own home is the one Build home
        return RedirectResponse("/build", status_code=303)
    if word in ("WORKSPACE", "WORKSPACES"):
        return RedirectResponse("/bi/w/me", status_code=303)
    if word in ("RUNS", "RUN"):                                       # BI RUNS <GO>: my runs (docs/architecture/ARCHITECTURE.md §28 › BI runs: every BI query as a bounded, visible job (built in 2.6.7), section 7.1)
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
    ed = getattr(request.state, "edition", None) or request.app.state.editions.instance
    head = typed.split(None, 1)[0].upper() if typed else ""
    if (gone := _modules.typed_refusal(typed)) is not None:        # a function whose module is off, switched off or not the person's
        return RedirectResponse(f"/t?error={quote(gone)}", status_code=303)   # (ACCESS_MODEL.md): "VRES is not available to you: ..."
    if (hosted := request.app.state.module_parts.word(head)) is not None and hosted[1].page and _modules.module_on(hosted[0].id):
        part, route = hosted                                       # a hosted module's composed page (KERNEL_AND_MODULES.md 9.10): its route
        names = re.findall(r"{(\w+)}", route.path)
        return RedirectResponse(route.url(part.id, **dict(zip(names, typed.split()[1:]))), status_code=303)
    from core import engines as _engines
    if (off := _engines.typed_engine(typed)) is not None:          # FRSA with FRTB-SA running nowhere: said in the server's words (DRS-1020)
        return RedirectResponse(f"/t?error={quote(_engines.refusal(off))}", status_code=303)
    from core import menus as _menus
    if (closed := _menus.engine_refused(request, typed)) is not None:   # VAR for a role that does not open VaR: said in words, not run
        return RedirectResponse(f"/t?error={quote(_menus.refusal_for_role(closed))}", status_code=303)
    eds = request.app.state.editions
    if getattr(ed, "distribution", False) and head and not ed.typed_shown(typed) and (
            head in reserved_words() or head in _dist.known_commands(eds.packs_dir, eds.modules_dir, eds.server_modules)):
        title = ed.outer.title if getattr(ed, "outer", None) is not None and not ed.outer.typed_shown(typed) else ed.title
        return RedirectResponse(f"/t?error={quote(f'DRS-4023 {head} is not in {title}: FUNC lists what you can use')}", status_code=303)
    if head in reserved_words() and head not in mnemonics() and not ed.word_on(head):    # a word the edition (or lens) switches off: said, not run (EXP is also a mnemonic)
        return RedirectResponse(f"/t?error={quote(f'DRS-4023 {head} is not part of {ed.title}: FUNC lists what you can use')}", status_code=303)
    if typed.split(None, 1)[:1] and typed.split(None, 1)[0].upper() in BI_WORDS:        # reserved (ARCHITECTURE.md §28 › Rūpaka: the original design and every phase as built, section 4), whatever packs define
        return await rupaka(request, typed.split(None, 1)[1].strip() if " " in typed else "")
    words = q.replace("<GO>", "").replace("<go>", "").split()
    if words and words[0].upper() == FORM_WORD:                    # reserved too: FORM <pack> [kind] opens the pack's search form
        if len(words) == 1:
            return RedirectResponse("/forms", status_code=303)
        return RedirectResponse(f"/form/{quote(words[1])}" + (f"?kind={quote(words[2])}" if len(words) > 2 else ""), status_code=303)
    if words and words[0].upper() == FUNC_WORD:                    # reserved too: FUNC the command directory; FUNC <words> it filtered
        rest = " ".join(words[1:])
        return RedirectResponse("/func" + (f"?q={quote(rest)}" if rest else ""), status_code=303)
    if words and words[0].upper() == WHAT_WORD:                    # reserved too: WHAT the Knowledge home; WHAT <term> its glossary entry, else a search
        rest = " ".join(words[1:])
        return RedirectResponse("/what" + (f"?q={quote(rest)}" if rest else ""), status_code=303)
    if words and words[0].upper() == DATAMAP_WORD:                 # reserved too: DATAMAP the data map; DATAMAP <domain|kind> it focused
        rest = " ".join(words[1:])
        return RedirectResponse("/datamap" + (f"?q={quote(rest)}" if rest else ""), status_code=303)
    if words and words[0].upper() == ADMIN_WORD:                   # reserved too: ADMIN the first Admin page kept; ADMIN <page> that page
        return admin_redirect(request, ed, words[1:])
    if words and words[0].upper() in EXPORT_WORDS:                 # reserved too: EXPORT (or DATA) Export data; EXPORT <dataset> [date] it on that dataset
        rest = " ".join(words[1:])
        return RedirectResponse("/data" + (f"?q={quote(rest)}" if rest else ""), status_code=303)
    me = getattr(request.state, "identity", None)
    if (to_module := _plugins.redirect(ed, typed, (me.user if me else "") or "")) is not None:   # a module's command (VAR DESK RATES, SMAT ...)
        return to_module
    if ed.word_on("PIVOT") and (to_pivot := pivot_redirect(typed)) is not None:        # PIVOT [table | @owner/name]: the pivot screen (pivot_routes.py)
        return to_pivot
    if _SEARCH.match(q.replace("<GO>", "")):
        return RedirectResponse(f"/s?q={quote(q.replace('<GO>', '').strip())}", status_code=303)
    try:
        r = await request.app.state.backend.command(q, ident(request))
    except BackendError as e:
        text = q.replace("<GO>", "").strip()
        if e.code == "DRS-1016":            # a command an administrator turned off (Admin -> Functions): "not active", whatever was typed after it
            return render(request, "terminal/missing.html", status_code=403, kind=(e.extra or {}).get("kind", ""), id=text, error=e, embed=False)
        if e.code in ("DRS-4021", "DRS-4022"):  # a range after an entity that cannot be read (TRD 1000001 -5): say so, with what to type
            return RedirectResponse(f"/t?error={quote(f'{e.code} {e.detail}')}", status_code=303)
        if len(text.split()) >= 2:          # not a command: perhaps plain words ("live trades over 5m"); show what they mean
            return RedirectResponse(f"/s?words={quote(text)}", status_code=303)
        return RedirectResponse(f"/t?error={quote(e.detail)}", status_code=303)
    if r.get("pack"):                       # a pack's code typed alone (MKT): its overview
        return RedirectResponse(f"/p/{quote(r['pack'])}", status_code=303)
    hist = (r.get("history") or {}).get("query")   # TRD 1000015 30: the entity over time (DATA_ARCHITECTURE.md › History queries: an entity over time, section 4.3)
    if not r.get("ref") and r.get("matched") == 0 and not hist and len(q.replace("<GO>", "").split()) == 2:
        if (alias := await _one_suggestion(request, q.replace("<GO>", "").strip())) is not None:
            return RedirectResponse(f"/v/{alias['kind']}/{quote(alias['id'])}", status_code=303)   # FUND VTSAX: the fund whose share class it is
    if not r.get("ref"):                    # several (or no) entities: a pick list, as on a Bloomberg terminal
        return RedirectResponse(f"/s?q={quote(r.get('list') or q)}" + (f"&{hist}" if hist else ""), status_code=303)
    if hist:
        return RedirectResponse(f"/v/{r['ref']['kind']}/{quote(r['ref']['id'])}/history?{hist}", status_code=303)
    return RedirectResponse(f"/v/{r['ref']['kind']}/{quote(r['ref']['id'])}", status_code=303)


def admin_redirect(request: Request, ed, rest: list[str]):
    """``ADMIN`` (the first Admin page the edition keeps) or ``ADMIN <page>`` (``ADMIN USERS``, ``ADMIN LOADS``), for an administrator; the
    Admin pages themselves check again. Anyone else, or a page the edition does not keep, is told so on the command line."""
    me = getattr(request.state, "identity", None)
    if me is None or not me.is_admin:
        return RedirectResponse(f"/t?error={quote('ADMIN is for administrators')}", status_code=303)
    kept = [p for p in ADMIN_PAGES if ed.admin_page_shown(p)]
    page = rest[0].lower() if rest else (kept[0] if kept else "")
    if page not in kept:
        return RedirectResponse(f"/t?error={quote(f'DRS-4023 no Admin page {page!r} in {ed.title}: ADMIN ' + ', '.join(kept).upper())}", status_code=303)
    return RedirectResponse(f"/admin/{page}", status_code=303)


async def _one_suggestion(request: Request, typed: str) -> dict | None:
    """A mnemonic and a word no id starts with (FUND VTSAX, a share class of the fund VTI): the type-ahead's one entity of that
    mnemonic whose title or subtitle names the word as a whole word, or None (then the pick list says nothing matched)."""
    head, word = typed.split(None, 1)
    try:
        found = await request.app.state.backend.suggest(typed, ident(request))
    except Exception:                            # noqa: BLE001  (an older server, a refusal: the pick list as before)
        return None
    hits = [s for s in found or [] if s.get("type") == "entity" and (s.get("mnemonic") or "").upper() == head.upper()
            and re.search(rf"(?<![\w-]){re.escape(word)}(?![\w-])", f"{s.get('title', '')} {s.get('subtitle', '')}", re.I)]
    return hits[0] if len(hits) == 1 else None


@router.get("/impact/{kind}/{id_:path}")
async def impact(request: Request, kind: str, id_: str):
    try:
        data = await request.app.state.backend.impact(kind, id_, ident(request))
    except BackendError as e:
        if e.page_status == 404 and kind == "trade":       # a link to a trade of a trading system switched off: say so, plainly (200)
            try:
                off = (await request.app.state.backend._get(f"/views/switched-off/{kind}", ident(request))).get("excludes") or []
            except BackendError:
                off = []
            if off:
                return render(request, "terminal/source_off.html", kind=kind, id=id_, systems=off, embed=bool(embed))
        return render(request, "terminal/missing.html", status_code=e.page_status, kind=kind, id=id_, error=e)
    return render(request, "terminal/impact.html", kind=kind, id=id_, data=data, screen="impact")


_shown, _delta = live_results.shown, live_results.delta   # values as text (core/live_results.py, one place for every result table)


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
    data, error, page = None, None, None
    if q.strip():
        limit = (getattr(request.state, "settings", None) or {}).get("searchLimit") or 100
        asked = q if re.search(r"(?i)\slimit\s+\S+\s*$", q) else f"{q.rstrip()} limit {limit}"   # the user's default size
        try:
            if vs:     # the same search on another business date, side by side: each number with its change (a comparison, not a set)
                data = await request.app.state.backend.search_compare(asked, vs, "", ident(request))
            elif _BARE.match(q.strip()):   # a bare mnemonic (POS <GO>): the kind's own live result set, a page at a time
                page = _page_of(request, limit)
                data = live_results.kind_page(await request.app.state.backend.live_kind(q.strip(), ident(request), **page))
            else:      # a search's matches are a live result set (static without a live binding, ticking with one)
                data = await live_results.run(request.app.state.backend, asked, ident(request))
        except BackendError as e:
            error = e
    hq = history_routes.history_query(request.query_params)   # a pick list of a history command: each row opens its history
    rows = live_results.table_rows(data, compared=bool(vs))
    titled = live_results.titled(rows)
    grid_spec = live_results.grid_spec(data, titled) if data and rows and not vs else None
    compared_columns, compared_rows = live_results.compared_rows(data, vs) if data and rows and vs else ([], [])
    return render(request, "terminal/search.html", q=q, vs=vs, words=words, phrase=phrase, data=data, rows=rows, error=error, titled=titled,
                  screen="search", hq=hq, grid_spec=grid_spec, compared_columns=compared_columns, compared_rows=compared_rows,
                  pages=live_results.pages(data, request.url.path, request.query_params))


_BARE = re.compile(r"^[A-Za-z][A-Za-z0-9_-]{0,31}$")      # one word, no condition, ids or limit: the mnemonic alone


def _page_of(request: Request, size: int) -> dict:
    """The page of a kind's set the address asks for: ``offset``, ``sort``, ``desc`` (a bare mnemonic's table pages on the server)."""
    qp = request.query_params
    offset = int(qp["offset"]) if qp.get("offset", "").isdigit() else 0
    return {"offset": offset, "limit": int(size), "sort": qp.get("sort", ""), "descending": qp.get("desc") in ("1", "true", "on")}



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
    """The reader's own arrangement of a grid screen (RACHANA_REFERENCE.md 13), kept by the server per reader and Sutra: GET it, PUT it whole
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
async def about(request: Request, kind: str, id_: str, generation: int = 0, format: str = ""):  # noqa: A002 - the query parameter's name
    """The body of the About this page drawer (an HTML fragment), asked for when the drawer first opens, never with the view.
    ``?format=json``: the explanation itself, ``{explain, index}`` (or ``{error: {code, detail}}``), for the pages the client-side renderer
    draws: it draws the body from it (EMBED_GUIDE.md section 12)."""
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
        if format == "json":
            return JSONResponse({"error": {"code": e.code, "detail": e.detail}}, status_code=e.page_status)
        return render(request, "terminal/_about.html", status_code=e.page_status, ex=None, error=e, kind=kind, id=id_)
    if format == "json":
        return JSONResponse({"explain": ex, "index": about_index.build(ex)})
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
        layout["panels"] = vm.get("panels") or []      # a grid screen: the Sutra's sizes (drishti_screen-grid.js keeps the reader's)
    shown = layout["panels"]
    env = request.app.state.templates.env
    parts = env.get_template("_macros/view.html").module
    # each panel as HTML (the macros, the page's) and as its JSON (what an embed's renderer draws: EMBED_GUIDE.md)
    out = [{"id": p["id"], "kind": p["kind"], "title": p.get("title") or "", "html": api_routes._panel_html(request, p, ref={"kind": kind, "id": id_}), "panel": p,
            **({"provenance": p["provenance"]} if p.get("provenance") else {})} for p in shown]   # a panel's own source: its freshness (RACHANA_REFERENCE.md 9)
    prov = vm.get("provenance") or {}
    return JSONResponse({"panels": out, "strip": str(parts.strip(vm)).strip(), "stripCells": vm.get("strip") or [], "controls": vm.get("controls") or [],
                         "timings": vm.get("timings"),
                         "provenance": {k: prov.get(k) for k in ("source", "fetchedAt", "updatedAt", "staleAfter", "stale", "live", "generation")}})


def _wired_html(request: Request, kind: str, id_: str, panel: dict) -> dict:
    """A panel of a Sutra Next answer as ready HTML (the macro of first paint) and as its JSON (what the client-side renderer draws), with
    its id, kind and title."""
    return {"id": panel["id"], "kind": panel["kind"], "title": panel.get("title") or "",
            "html": api_routes._panel_html(request, panel, ref={"kind": kind, "id": id_}), "panel": panel}


def _children_html(request: Request, kind: str, id_: str, groups: list) -> list:
    return [{**{k: g.get(k) for k in ("parent", "group", "place", "show", "hidden")},
             "panels": [_wired_html(request, kind, id_, p) for p in (g.get("panels") or [])]} for g in (groups or [])]


def _selection_problem(e: BackendError, tokens: list) -> JSONResponse:
    """A click's or children's refusal. When the selections cannot be used any more (the server that held them went away, or they expired),
    the answer says so in words with ``reset``: the page forgets them and the reader picks again (one server per screen, RACHANA_REFERENCE.md 7)."""
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
    """A click on a row or bar of a Sutra Next screen (RACHANA_REFERENCE.md 7), relayed to the server, which evaluates it as the reader; the
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
    always or is opened (RACHANA_REFERENCE.md 8)."""
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
    if (retired := _plugins.retired_link(kind, id_)) is not None:   # a module's retired stored record (VAR-COMM, EXP-X-FRA): where it is now
        return RedirectResponse(retired, status_code=303)
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
    grid_mine = await _grid_layout(request, vm, layout)                             # a Sutra with layout: (RACHANA_REFERENCE.md 13)
    panels = layout["panels"]
    phone = await phone_routes.plan_for(request, vm, panels, bool(embed))   # a phone or tablet (docs/guides/BUILD_GUIDE.md): its tiers
    if phone and phone["device"] == "tablet":                       # a tablet keeps the page's layout, less its desk panels (under More)
        keep = {p.get("id") for p in phone["shown"]}
        panels = [p for p in panels if p.get("id") in keep]
    grid = screen_grid.arrange(vm.get("layout"), panels, grid_mine) if vm.get("layout") and not (phone and phone["device"] == "phone") else None
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
                  pin=pin, pin_gen=gen or None, chosen_sutra=chosen, pin_via="history" if request.query_params.get("via") == "history" else None, ctl_query=controls.to_query(ctl), shared=shared, calc=calc, layout=layout, hidden_ids=[p["id"] for p in layout["panels"] if p.get("hidden")],
                  designer=designer_routes.view_context(request, mine), grid=grid, phone=phone,
                  phone_everything=phone_routes.everything(request, bool(embed)))


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
