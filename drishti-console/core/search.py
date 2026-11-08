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

"""One search (2.9.11.e): every type-ahead and search box of the console asks this service, through one endpoint (``GET /api/search``).

It finds five kinds of thing and groups them, best group first:

========== ======================================================================= ===========================================================
group      what                                                                    who decides what a person sees
========== ======================================================================= ===========================================================
entity     trades, netting sets, counterparties, any entity of a pack               the server's type-ahead (``/command/suggest``): entitlements,
                                                                                    masks, the edition's kinds, the engines the role opens
command    mnemonics and the reserved words (FORM, WHAT, BI, FUNC, DATAMAP, ...)    the same, plus the edition's (or lens's) words
screen     the pages of the top bar's menus                                         the menus' ``requires:``, the edition's and lens's menus
help       the guides and tutorials of Help                                         Help's own rule (:func:`routes.help_routes.visible`)
build      what a person may build on: published reports and boards, their designs the BI catalogue and the designs the server lists for them
========== ======================================================================= ===========================================================

``where`` chooses the sources: ``command`` (the command line: entities and commands in the server's order, the reserved words' own
type-ahead first, exactly what it always offered), ``help`` (Help's search box) and ``all`` (the global search box). A source that fails
is left out (``failed`` names it): a search never fails for one source."""
from __future__ import annotations

import asyncio
import re
import time
from urllib.parse import quote

from fastapi import Request

from core.backend import BackendError

GROUPS = (("entity", "Entities"), ("command", "Commands"), ("screen", "Screens"), ("help", "Help pages"), ("build", "Things you can build"))
WHERE = ("command", "help", "all")
PER_GROUP = {"entity": 8, "command": 6, "screen": 6, "help": 6, "build": 6}


def _ident(request: Request):
    return getattr(request.state, "identity", None)


# ---- ranking ------------------------------------------------------------------------------------------------------------------------
def rank(q: str, title: str, subtitle: str = "") -> int:
    """How well ``title`` (then ``subtitle``) answers ``q``: 100 the same, 80 it starts with it, 60 it holds it at the start of a word, 40 it holds it,
    20 every word of the query is somewhere in title and subtitle, 0 no match."""
    want = " ".join(q.lower().split())
    if not want:
        return 0
    t = (title or "").lower()
    if t == want:
        return 100
    if t.startswith(want):
        return 80
    if re.search(r"(?:^|[\s·:/,()_&-])" + re.escape(want), t):
        return 60
    if want in t:
        return 40
    both = t + " " + (subtitle or "").lower()
    return 20 if all(w in both for w in want.split()) else 0


# ---- the command line's own words (the reserved words' type-ahead) -------------------------------------------------------------------
async def what_words(request: Request, words: str) -> list:
    """``WHAT``'s type-ahead: the Knowledge home, then the glossary's terms that start with the words (each opens its entry), and for
    words that are no term a search of the Knowledge pages."""
    from core.knowledge import HOME
    from routes.common import library

    lib = await library(request)
    folder = (request.app.state.docs_dir / "knowledge").resolve()
    out = [] if words else [{"mnemonic": "WHAT", "title": "Knowledge: what the words of your data mean",
                             "subtitle": "WHAT <GO> opens the Knowledge home; WHAT <term> its glossary entry (WHAT PFE, WHAT netting set)",
                             "complete": "WHAT ", "href": f"/help/{lib.by_file.get(folder / HOME, '')}", "type": "what"}]
    for e in request.app.state.glossary.starting(words):
        slug = lib.by_file.get(folder / e.page)
        if slug:
            out.append({"mnemonic": "WHAT", "title": e.term + (f" ({e.name})" if e.name else ""), "subtitle": f"{e.title} · {e.meaning}",
                        "complete": f"WHAT {e.term}", "href": f"/help/{slug}#{e.anchor}", "type": "what"})
    if words and not out:
        out.append({"mnemonic": "WHAT", "title": f"Search the Knowledge pages for {words}", "subtitle": "No term of the glossary is called that",
                    "complete": f"WHAT {words}", "href": f"/what?q={quote(words)}", "type": "what"})
    return out


async def form_words(request: Request, pack: str | None, kind: str | None, spaced: bool) -> list:
    """``FORM <pack> <kind>``'s type-ahead: only packs the person may open, and only kinds they may open in that pack."""
    from routes.common import packs
    me = _ident(request)
    if kind is None and not (pack and spaced):          # still typing the pack's name
        want = (pack or "").lower()
        return [{"mnemonic": "FORM", "title": p["title"], "subtitle": f"search {p['name']} with a form", "complete": f"FORM {p['name']} ",
                 "href": f"/form/{p['name']}", "type": "form"}
                for p in await packs(request) if p.get("active", True) and p.get("kinds") and (not want or want in p["name"].lower() or want in p["title"].lower())]
    try:
        info = await request.app.state.backend.form(pack or "", "", me)
    except BackendError:
        return []
    want = (kind or "").lower()
    return [{"mnemonic": "FORM", "title": k["label"], "subtitle": f"{info['title']} · {k['kind']}", "complete": f"FORM {info['pack']} {k['kind']}",
             "href": f"/form/{info['pack']}?kind={k['kind']}", "type": "form"}
            for k in info["kinds"] if not want or want in k["kind"].lower() or want in k["label"].lower()]


RUPAKA = "RUPAKA"
BI_ENTRIES = (("", "Build home", "Everything you build, BI's reports, boards, tables and models among it", "/build"), ("WORKSPACE", "My workspace", "Your drafts and the workspaces you belong to", "/bi/w/me"),
              ("CATALOGUE", "Catalogue", "Every published report and board you may open", "/bi/catalogue"))


async def _bi_open(request: Request) -> bool:
    me = _ident(request)
    return bool(me is not None and request.app.state.bi_on and (await request.app.state.bi_me.get(request.app.state.backend, me)).get("enabled"))


async def catalogue(request: Request, want: str, limit: int = 8) -> list:
    """Published reports and boards whose id or title matches, as the BI catalogue lists them for this person (it decides who sees what)."""
    if not want or not await _bi_open(request):
        return []
    try:
        return (await request.app.state.backend.bi("GET", "/catalogue", _ident(request), q=want, limit=limit)).get("items") or []
    except BackendError:
        return []


async def rupaka_words(request: Request, word: str, rest: str | None) -> list:
    """``BI`` (alias ``RUPAKA``)'s type-ahead: its three pages, then published reports whose id or title matches, only for people who hold the bi power."""
    if not await _bi_open(request):
        return []
    want = (rest or "").strip().lower()
    kw = (word or RUPAKA).upper()                # echo the keyword as typed: BI or its alias RUPAKA
    out = [{"mnemonic": kw, "title": title, "subtitle": sub, "complete": f"{kw} {key}".strip(), "href": href, "type": "bi"}
           for key, title, sub, href in BI_ENTRIES if rest is None or not want or key.lower().startswith(want)]
    if rest is not None and want:
        out += [{"mnemonic": kw, "title": i["title"], "subtitle": f"{i['kind']} · {i['workspaceName']} · {i['owner']}", "complete": f"{kw} {i['title']}",
                 "href": f"/bi/i/{i['id']}", "type": "bi"} for i in await catalogue(request, want)]
    return out


async def command_line(request: Request, q: str, limit: int | None) -> list:
    """The command line's type-ahead as it always was: a reserved word's own suggestions, else the server's (entities and commands)."""
    ed = getattr(request.state, "edition", None) or request.app.state.editions.instance     # the words an edition (or lens) keeps
    text = q.replace("<GO>", "")

    def word(rx: str):
        return re.match(rx, text, re.I | re.S)

    bi = word(r"^\s*(bi|rupaka)(?:\s+(.*))?$")
    if bi and ed.word_on(bi.group(1)):                                                     # the reserved word RUPAKA: the BI pages and the reports you may open
        return await rupaka_words(request, bi.group(1), bi.group(2))
    keyword = []
    if word(r"^\s*(b|bi|r|ru|rup|rupa|rupak)\s*$") and ed.word_on("BI" if q.strip().lower().startswith("b") else RUPAKA) \
            and await rupaka_words(request, "", None):    # the first letters of it: offer the keyword
        kw = "BI" if q.strip().lower().startswith("b") else RUPAKA
        keyword = [{"mnemonic": kw, "title": "BI: business intelligence (Rupaka)", "subtitle": f"{kw} <GO> opens the Build home; {kw} CATALOGUE, {kw} WORKSPACE, {kw} <report>",
                    "complete": f"{kw} ", "href": "/build", "type": "bi"}]
    m = word(r"^\s*func(?:\s+(.*))?$")
    if m and ed.word_on("FUNC"):                                                   # FUNC: the command directory, filtered by what follows
        w = (m.group(1) or "").strip()
        return [{"mnemonic": "FUNC", "title": "Functions: every command you can use" + (f", matching {w}" if w else ""),
                 "subtitle": "FUNC <GO> opens the list; FUNC <words> opens it filtered (a command, a word of what it does, a pack)",
                 "complete": f"FUNC {w}".strip(), "href": "/func" + (f"?q={quote(w)}" if w else ""), "type": "func"}]
    m = word(r"^\s*datamap(?:\s+(.*))?$")
    if m and ed.word_on("DATAMAP"):                                                   # DATAMAP: the data map, focused on what follows
        w = (m.group(1) or "").strip()
        return [{"mnemonic": "DATAMAP", "title": "Data map: every kind of data, how it joins, how much there is" + (f", at {w}" if w else ""),
                 "subtitle": "DATAMAP <GO> opens the map; DATAMAP <domain or kind> opens it focused (DATAMAP trade, DATAMAP counterparty)",
                 "complete": f"DATAMAP {w}".strip(), "href": "/datamap" + (f"?q={quote(w)}" if w else ""), "type": "datamap"}]
    m = word(r"^\s*(?:export|data)(?:\s+(.*))?$")
    if m and ed.word_on("EXPORT"):                                                   # EXPORT (DATA, its older alias): Export data
        w = (m.group(1) or "").strip()
        return [{"mnemonic": "EXPORT", "title": "Export data: every dataset, its dates; choose columns and filters, download a day" + (f", of {w}" if w else ""),
                 "subtitle": "EXPORT <GO> lists the datasets; EXPORT <dataset> [date] opens one (EXPORT trade-valuation 2026-09-30); download only, as .jsonl.gz; DATA works too",
                 "complete": f"EXPORT {w}".strip(), "href": "/data" + (f"?q={quote(w)}" if w else ""), "type": "data"}]
    from core import plugins
    own = plugins.suggest(request, ed, text)                                       # a module's word (SMAT, PLAY): its own suggestions
    if own is not None:
        return own
    m = word(r"^\s*what(?:\s+(.*))?$")
    if m and ed.word_on("WHAT"):                                                   # WHAT: the Knowledge pages, then the glossary's terms
        return await what_words(request, (m.group(1) or "").strip())
    m = re.match(r"(?i)^\s*form(?:\s+(\S*))?(?:\s+(\S*))?\s*$", text)
    if m and ed.word_on("FORM"):                           # FORM: packs you may open, then their kinds
        return await form_words(request, m.group(1), m.group(2), q.endswith(" "))
    keyword = plugins.hint_lines(ed, q) + keyword                                  # a module's lines first (a VAR command's horizons)
    from core import modules
    return modules.allowed(keyword + await request.app.state.backend.suggest(q, _ident(request), limit))   # a function off: not offered


# ---- the other sources -----------------------------------------------------------------------------------------------------------------
async def help_pages(request: Request, q: str, section: str = "", limit: int = 20, category: str = "") -> list:
    """Help's guides and tutorials whose text holds every word of ``q``, best first, only those the person sees (Help's own rule); with
    ``section`` that section's listed pages, with ``category`` that category's (the Knowledge pages for WHAT). Each hit keeps its guide
    and snippet for the Help results page."""
    from routes.common import library
    from routes.help_routes import visible

    words = [w for w in re.split(r"\W+", q.lower()) if len(w) > 1]
    if not words:
        return []
    lib = await library(request)
    sid = lib.section_id(section) if section else ""
    hits = []
    pages = [g for g in lib.guides.values()
             if (g.listed or "qa" not in g.path.parts)                # the QA records (history) are reachable by link, not search
             and not (sid and (g.category != sid or not g.listed) or category and g.category != category)]
    await asyncio.to_thread(lambda: [lib.render(g.slug) for g in pages])   # a page not yet rendered: off the event loop (Library.warm)
    for g in pages:
        low_title = g.title.lower()
        text = lib.render(g.slug)["text"]
        low = text.lower()
        if not all(w in low or w in low_title for w in words):
            continue
        if not visible(request, g):
            continue
        score = sum(low.count(w) for w in words) + sum(10 for w in words if w in low_title)
        i = min((low.find(w) for w in words if low.find(w) >= 0), default=0)
        hits.append({"guide": g, "score": score, "snippet": text[max(0, i - 80): i + 160].strip()})
    hits.sort(key=lambda h: -h["score"])
    return hits[:limit]


def _help_items(q: str, hits: list) -> list:
    out = []
    for h in hits:
        g = h["guide"]
        out.append({"group": "help", "type": "help", "title": g.title, "subtitle": g.summary or h["snippet"][:140], "href": f"/help/{g.slug}",
                    "score": max(rank(q, g.title), 10) + min(h["score"], 9)})
    return out


async def screens(request: Request, q: str) -> list:
    """The pages of the top bar's menus that this person sees (the same rule that draws the menus), matching ``q``."""
    from core import menus
    from routes.common import packs

    me = _ident(request)
    bi_me = {}
    if me is not None and request.app.state.bi_on:
        try:
            bi_me = await request.app.state.bi_me.get(request.app.state.backend, me)
        except BackendError:
            bi_me = {}
    try:
        switcher = await request.app.state.packs.assigned(request.app.state.backend, me) if me is not None else []
    except Exception:  # noqa: BLE001 - the pack switcher is a convenience
        switcher = await packs(request)
    out, seen = [], set()
    for label, _icon, columns in menus.top_bar(request, {"me": me, "bi_me": bi_me, "pack_switcher": switcher}):
        for column, items in columns:
            for href, _i, title, desc, _match, cmd in items:
                if not href or href == "#" or href in seen:
                    continue
                score = max(rank(q, str(title), f"{desc} {label} {column} {cmd}"), 0)
                if score:
                    seen.add(href)
                    out.append({"group": "screen", "type": "screen", "title": str(title), "subtitle": f"{label} › {column}" + (f" · {desc}" if desc else ""),
                                "href": href, "mnemonic": cmd.split()[0] if cmd else "", "score": score})
    return out


async def builds(request: Request, q: str) -> list:
    """What the person may build on: published reports and boards the BI catalogue lists for them, and their own designs."""
    out = [{"group": "build", "type": "bi", "title": i.get("title") or i.get("id"), "subtitle": f"{i.get('kind', '')} · {i.get('workspaceName', '')} · {i.get('owner', '')}",
            "href": f"/bi/i/{i['id']}", "score": max(rank(q, str(i.get("title") or ""), str(i.get("id") or "")), 30)} for i in await catalogue(request, q.strip(), 6)]
    me = _ident(request)
    if me is not None:
        try:
            mine = (await request.app.state.backend.designs("GET", "", me)).get("designs") or []
        except BackendError:
            mine = []
        for d in mine:
            score = rank(q, str(d.get("name") or ""), str(d.get("kind") or ""))
            if score:
                out.append({"group": "build", "type": "design", "title": d.get("name") or "Untitled", "subtitle": f"my design · {d.get('kind', '')}",
                            "href": f"/build/d/{d['id']}", "score": score})
    return out


async def _server(request: Request, q: str, limit: int | None) -> list:
    """The server's entities and commands (its order is its ranking; at least 30, since the server matched them, maybe on a field not shown),
    each tagged entity (it names an id) or command."""
    from core import modules
    items = modules.allowed(await request.app.state.backend.suggest(q, _ident(request), limit))   # a function off is not found either
    out = []
    for n, s in enumerate(items if isinstance(items, list) else []):
        s = dict(s)
        s["group"] = "entity" if s.get("id") and s.get("type") != "range" else "command"
        s["score"] = max(rank(q, str(s.get("mnemonic") or s.get("id") or ""), str(s.get("title") or "")), rank(q, str(s.get("id") or s.get("title") or "")), 30) - n
        out.append(s)
    return out


# ---- the service ------------------------------------------------------------------------------------------------------------------------
def _grouped(items: list) -> list:
    """The groups that found something, best first (by their best result; equal ones in the order of :data:`GROUPS`), each best first."""
    order = {g: n for n, (g, _l) in enumerate(GROUPS)}
    by = {}
    for s in items:
        by.setdefault(s["group"], []).append(s)
    groups = []
    for g, label in GROUPS:
        found = sorted(by.get(g, []), key=lambda s: -s["score"])[:PER_GROUP[g]]
        if found:
            groups.append({"group": g, "label": label, "items": found})
    groups.sort(key=lambda gr: (-gr["items"][0]["score"], order[gr["group"]]))
    return groups


async def search(request: Request, q: str, where: str = "all", limit: int | None = None) -> dict:
    """The one search: ``{query, where, groups: [{group, label, items}], items, failed, ms}``. ``items`` is the groups' results in the order
    shown (the command line reads it as its list); every result has ``group``, ``title``, ``subtitle`` and ``href`` or ``kind`` and ``id``."""
    from core import menus

    started = time.perf_counter()
    q = (q or "")[:200]
    failed: list[str] = []
    if where == "command":
        if menus.engine_refused(request, q):
            items = []
        else:
            items = menus.suggestions_opened(request, await command_line(request, q, limit))
            items = [dict(s, group=s.get("group") or ("entity" if s.get("id") and s.get("type") != "range" else "command")) for s in items]
        groups = []
        for s in items:                                               # the command line keeps its order: groups as they come
            if not groups or groups[-1]["group"] != s["group"]:
                groups.append({"group": s["group"], "label": dict(GROUPS)[s["group"]], "items": []})
            groups[-1]["items"].append(s)
        return {"query": q, "where": where, "groups": groups, "items": items, "failed": failed, "ms": _ms(started)}
    if where == "help":
        tasks = {"help": help_pages(request, q, limit=limit or 8)}
    else:
        tasks = {"server": _server(request, q, limit or 12) if q.strip() and not menus.engine_refused(request, q) else _none(),
                 "screen": screens(request, q), "help": help_pages(request, q, limit=PER_GROUP["help"]), "build": builds(request, q)}
    answers = await asyncio.gather(*tasks.values(), return_exceptions=True)
    items: list = []
    for name, got in zip(tasks, answers):
        if isinstance(got, BaseException):
            failed.append(name)
            continue
        if name == "help":
            got = _help_items(q, got)
        elif name == "server":
            got = menus.suggestions_opened(request, got)
        items += got
    groups = _grouped(items) if q.strip() else []
    return {"query": q, "where": where, "groups": groups, "items": [s for g in groups for s in g["items"]], "failed": failed, "ms": _ms(started)}


async def _none() -> list:
    return []


def _ms(started: float) -> float:
    return round((time.perf_counter() - started) * 1000, 1)
