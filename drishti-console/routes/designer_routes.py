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

"""The Sutra designer (SCREEN_DESIGNER.md section 31), behind ``ui.designer`` (off by default): one shell with modes over the SAME Sutra YAML, a
user's own Design. **Customize** edits the page in place (a scratch draft Design while customising; Done keeps it as a Design and sets
the user's flag "my version of this page" for the kind, ``/me/pages`` on the server, which then draws that page for that user only:
``?design=<id>``); **Describe** builds a screen from a command-style line read by a deterministic parser. Thin: the server keeps the Designs, checks the Sutra and draws every view as the viewer."""
from __future__ import annotations

import re
import time
from urllib.parse import quote

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse, RedirectResponse

from core import describe, designer, designer_links
from core.backend import BackendError
from core.csrf import BodyError, json_body
from routes.common import ident, render

router = APIRouter(prefix="/designer", include_in_schema=False)
MODES = [("customize", "Customize", True), ("describe", "Describe", True), ("outline", "Outline", False), ("grid", "Grid", False)]
_ALIASES: dict = {"at": 0.0, "table": {}, "cat": {}}
_MIGRATED: set = set()                                # users whose "My page · <kind>" Designs were turned into flags


def enabled(request: Request) -> bool:
    return bool(request.app.state.settings.get("ui.designer", False))


def view_context(request: Request, mine: str | None) -> dict:
    """What an entity page needs of the designer: whether it is offered, the Design drawing the page, and whether it is being customised."""
    if not enabled(request):
        return {"on": False}
    q = request.query_params
    customizing = bool(mine) and (q.get("customize") or "") == mine
    return {"on": True, "design": mine or "", "customizing": customizing, "own": bool(mine) and not customizing and not q.get("design")}


def _off() -> JSONResponse:
    return JSONResponse({"code": "DRS-5004", "detail": "the Sutra designer is switched off (ui.designer)"}, status_code=404)


def _problem(e: BackendError) -> JSONResponse:
    return JSONResponse({"code": e.code, "detail": e.detail}, status_code=e.status if 400 <= e.status < 600 else 502)


def _say(detail: str, status: int = 400) -> JSONResponse:
    return JSONResponse({"code": "DRS-5001", "detail": detail}, status_code=status)


async def my_pages(request: Request) -> dict:
    """The user's "my version of this page" flags (``{kind: design id}``, ``/me/pages`` on the server). The first time a console sees a
    user, their Designs named "My page · <kind>" (how 2.7 kept them) become flags: once, and only for kinds with no flag yet."""
    backend, me = request.app.state.backend, ident(request)
    try:
        pages = await backend._send("GET", "/me/pages", me)
    except BackendError:
        return {}
    pages = dict(pages) if isinstance(pages, dict) else {}
    user = getattr(me, "user", "")
    if user and user not in _MIGRATED:
        _MIGRATED.add(user)
        try:
            got = await backend.designs("GET", "", me)
            for d in got.get("designs") or []:
                name = str(d.get("name") or "")
                if name.startswith(designer.PAGE_PREFIX) and not d.get("scratch"):
                    kind = name[len(designer.PAGE_PREFIX):].strip()
                    if re.fullmatch(r"[a-z0-9-]{1,40}", kind) and kind not in pages:
                        pages = await backend._send("PUT", f"/me/pages/{quote(kind)}", me, json={"design": d["id"]}) or pages
        except BackendError:
            _MIGRATED.discard(user)                    # try again next time
    return pages


async def set_page(request: Request, kind: str, design: str | None) -> None:
    path = f"/me/pages/{quote(kind)}"
    if design:
        await request.app.state.backend._send("PUT", path, ident(request), json={"design": design})
    else:
        await request.app.state.backend._send("DELETE", path, ident(request))


async def page_design(request: Request, kind: str) -> dict | None:
    """The user's own page for a kind (the Design their flag names, whatever it is called now), or None. A flag whose Design is gone is
    cleared."""
    did = (await my_pages(request)).get(kind)
    if not did:
        return None
    try:
        d = await request.app.state.backend.designs("GET", f"/{did}", ident(request))
    except BackendError as e:
        if e.status == 404:
            try:
                await set_page(request, kind, None)
            except BackendError:
                pass
        return None
    return {"id": did, "name": d.get("name"), "shared": d.get("shared")}


async def design_for(request: Request, kind: str) -> str | None:
    """The Design that draws this page for this user: the draft being customised (``?customize=``), one named (``?design=``), else
    their own page for the kind; ``?pack=1`` shows the pack's page. None when the designer is off."""
    if not enabled(request):
        return None
    q = request.query_params
    for key in ("customize", "design"):
        v = (q.get(key) or "").strip()
        if re.fullmatch(r"[\w-]{1,64}", v):
            return v
    if q.get("pack") or q.get("sutra"):
        return None
    return (await my_pages(request)).get(kind) or None


async def design_kw(request: Request, kind: str) -> dict:
    """``{"design": id}`` for a backend call when a Design draws the page, else nothing (an older backend takes no such keyword)."""
    d = await design_for(request, kind)
    return {"design": d} if d else {}


async def _body(request: Request, limit: int = 65536) -> dict | JSONResponse:
    try:
        return await json_body(request, limit=limit)
    except BodyError as e:
        return JSONResponse({"code": e.code, "detail": e.detail}, status_code=e.status)


async def _record(request: Request, kind: str, id_: str):
    """The record as the viewer may read it (the server's raw answer is ``{ref, provenance, data}``: the data)."""
    try:
        got = await request.app.state.backend.raw(kind, id_, ident(request))
    except BackendError:
        return None                                   # no raw right: the choices come from what the page shows
    return got["data"] if isinstance(got, dict) and isinstance(got.get("data"), dict) and "ref" in got else got


async def _kit(request: Request) -> list:
    try:
        got = await request.app.state.backend.ui_kit(ident(request))
    except BackendError:
        return []
    return got.get("fragments") or []


async def _packs(request: Request) -> None:
    """The enabled packs' overviews and links, asked of the server once a minute: the command line's mnemonics and the linked records."""
    if time.monotonic() - _ALIASES["at"] < 60 and _ALIASES["table"]:
        return
    backend, me, overviews = request.app.state.backend, ident(request), {}
    try:
        listed = [p for p in await backend.packs(me) if p.get("active", True)]
        for p in listed:
            try:
                overviews[p["name"]] = await backend.pack_overview(p["name"], me)
            except BackendError:
                continue
    except BackendError:
        return
    _ALIASES.update(at=time.monotonic(), table=describe.aliases_of(list(overviews.values())), cat=designer_links.catalog(listed, overviews))


async def aliases(request: Request) -> dict:
    """The command line's mnemonic table of the enabled packs (TRD, CPTY, PTRD...)."""
    await _packs(request)
    return _ALIASES["table"]


async def link_catalog(request: Request) -> dict:
    await _packs(request)
    return _ALIASES["cat"] or {"links": {}, "pack_of": {}, "labels": {}, "columns": {}}


async def _shows(request: Request, record, rows: str, cat: dict) -> list:
    """What a click on a row of this table can show: the row's details and the records linked from the row (one sample of each linked
    kind is read for the lists inside it, at most three)."""
    row = designer_links.first_row(record, rows)
    samples: dict = {}
    found = designer_links.targets(row, cat)[:3]
    for f, kind, _ in found:
        if kind not in samples:
            samples[kind] = await _record(request, kind, str(row[f]))
    others = {k: v for k, v in samples.items() if not any(k == t[1] and t[0].endswith("_ID") for t in found)}   # not the row's own record
    for kind, value in designer_links.onward(others, cat)[:4]:       # what the linked records name in turn: a counterparty's credit limit
        samples[kind] = await _record(request, kind, value)
    return designer_links.choices(row, cat, samples)


def _pack_sutra(vm: dict) -> tuple[str, int] | None:
    m = re.search(r"Sutra (\S+) v(\d+)", (vm.get("provenance") or {}).get("layout") or "")
    return (m.group(1), int(m.group(2))) if m else None


# ---- Customize --------------------------------------------------------------------------------------------------------------------

@router.post("/customize")
async def customize(request: Request):
    """Body ``{kind, id}``: a scratch draft of this page's Sutra (the user's own page if they keep one, else the pack's), to edit in place."""
    if not enabled(request):
        return _off()
    body = await _body(request)
    if isinstance(body, JSONResponse):
        return body
    kind, id_ = str(body.get("kind") or ""), str(body.get("id") or "")
    backend, me = request.app.state.backend, ident(request)
    try:
        mine = await page_design(request, kind)
        if mine:
            full = await backend.designs("GET", f"/{mine['id']}", me)
            text, notes = full.get("sutra") or "", full.get("notes") or ""
        else:
            vm = await backend.view(kind, id_, me)
            at = _pack_sutra(vm)
            if not at:
                return _say("This page is laid out by inference alone: there is no Sutra to customise yet. Try Describe instead.")
            text, notes = await backend.sutra_source(at[0], at[1], me), ""
        doc = designer.rename(designer.load(text), kind)
        draft = await backend.designs("POST", "", me, {"name": "", "kind": kind, "sutra": designer.dump(doc), "notes": notes})
    except designer.DesignerError as e:
        return _say(str(e))
    except BackendError as e:
        return _problem(e)
    return {"draft": draft["id"], "url": f"/v/{quote(kind)}/{quote(id_)}?customize={draft['id']}"}


async def _draft(request: Request, draft: str) -> tuple[dict, dict, dict]:
    full = await request.app.state.backend.designs("GET", f"/{draft}", ident(request))
    return full, designer.load(full.get("sutra") or ""), designer.notes_of(full.get("notes") or "")


@router.get("/state/{draft}")
async def state(request: Request, draft: str, kind: str = "", id: str = ""):
    """What Customize shows: the page's parts (each with the panel ids it draws), the tables and their columns, the hidden parts, what the
    kit suggests for this record, and whether the user already keeps their own page."""
    if not enabled(request):
        return _off()
    backend, me = request.app.state.backend, ident(request)
    try:
        full, doc, keep = await _draft(request, draft)
        uses = {u["item"]: u for u in (await backend.designs("GET", f"/{draft}/blocks", me)).get("uses") or [] if "." not in u.get("at", ".")}
    except (BackendError, designer.DesignerError) as e:
        return _problem(e) if isinstance(e, BackendError) else _say(str(e))
    record = await _record(request, kind, id)
    cat = await link_catalog(request)
    parts, tables = [], []
    for i, e in enumerate(doc["panels"]):
        brought = (uses.get(i) or {}).get("panels") or []
        ids = [b["id"] for b in brought if b.get("id")] or [str(e.get("id") or "")]
        part = {"index": i, "ids": ids, "title": designer.entry_title(e, brought), "used": bool(e.get("use")),
                "tables": [b["id"] for b in brought if b.get("kind") == "table"] if e.get("use") else []}
        if not e.get("use") and e.get("kind") == "table" and isinstance(e.get("columns"), list):
            chosen = [designer.bound_field(c.get("bind")) for c in e["columns"] if isinstance(c, dict)]
            fields = designer.rows_fields(record, e.get("rows"))
            part["table"] = {"id": e.get("id"), "chosen": [f for f in chosen if f],
                             "fields": list(dict.fromkeys([f for f in chosen if f] + fields))}
            tables.append({"id": e.get("id"), "title": part["title"], "fields": part["table"]["fields"],
                           "click": bool(e.get("children")), "place": ((e.get("children") or [{}])[0] or {}).get("place", ""),
                           "shows": await _shows(request, record, e.get("rows"), cat)})
        parts.append(part)
    for t in tables:                                  # an existing panel of the page, moved under the table
        t["shows"] += [{"label": f"the panel “{p['title']}” (moved here)", "spec": {"how": "panel", "index": p["index"]}}
                       for p in parts if not (p.get("table") or {}).get("id") == t["id"]]
    mine = await page_design(request, kind)
    return {"parts": parts, "tables": tables, "hidden": [{"index": i, "title": h.get("title")} for i, h in enumerate(keep.get("hidden") or [])],
            "kit": designer.search(await _kit(request), "", record), "places": designer.PLACE_LABELS,
            "mine": {"id": mine["id"], "shared": mine.get("shared")} if mine else None, "yaml": full.get("sutra") or ""}


@router.get("/kit/{draft}")
async def kit(request: Request, draft: str, q: str = "", kind: str = "", id: str = ""):
    if not enabled(request):
        return _off()
    return {"results": designer.search(await _kit(request), q, await _record(request, kind, id))}


@router.post("/op/{draft}")
async def op(request: Request, draft: str):
    """One Customize step on the draft (``move``, ``hide``, ``show``, ``unpack``, ``columns``, ``click``, ``unclick``, ``add``); the page is
    drawn with it before it is kept, and a step the server refuses is undone and said in plain words."""
    if not enabled(request):
        return _off()
    body = await _body(request)
    if isinstance(body, JSONResponse):
        return body
    backend, me = request.app.state.backend, ident(request)
    kind, id_, what = str(body.get("kind") or ""), str(body.get("id") or ""), str(body.get("op") or "")
    try:
        full, doc, keep = await _draft(request, draft)
        if what == "move":
            designer.move(doc, body.get("index"), body.get("to", 0))
        elif what == "hide":
            designer.hide(doc, keep, body.get("index"), str(body.get("title") or ""))
        elif what == "show":
            designer.show(doc, keep, body.get("hidden"))
        elif what == "unpack":
            uses = (await backend.designs("GET", f"/{draft}/blocks", me)).get("uses") or []
            use = next((u for u in uses if u.get("item") == int(body.get("index", -1)) and "." not in u.get("at", ".")), None)
            designer.unpack(doc, body.get("index"), (use or {}).get("panels") or [])
        elif what == "columns":
            designer.columns(doc, str(body.get("panel") or ""), list(body.get("fields") or []))
        elif what == "click":
            p = designer.find_panel(doc, str(body.get("panel") or "")) or {}
            fields = [designer.bound_field(c.get("bind")) for c in p.get("columns") or [] if isinstance(c, dict)]
            designer.click(doc, str(body.get("panel") or ""), str(body.get("place") or ""), [f for f in fields if f], designer.plain_title(p.get("title")),
                           body.get("show"), keep)
        elif what == "unclick":
            designer.unclick(doc, str(body.get("panel") or ""), keep)
        elif what == "add":
            designer.add(doc, str(body.get("ref") or ""), body.get("with") or {}, body.get("at"))
        else:
            return _say("unknown step")
        await backend.designs("PATCH", f"/{draft}", me, {"sutra": designer.dump(doc), "notes": designer.notes_with(full.get("notes") or "", keep)})
        try:
            await backend.view(kind, id_, me, design=draft)
        except BackendError as e:
            await backend.designs("PATCH", f"/{draft}", me, {"sutra": full.get("sutra") or "", "notes": full.get("notes") or ""})
            return _say(f"That change would break the page, so it was not made: {e.detail}")
    except designer.DesignerError as e:
        return _say(str(e))
    except (TypeError, ValueError):
        return _say("that step is missing something: reload the page")
    except BackendError as e:
        return _problem(e)
    return {"ok": True}


@router.post("/done/{draft}")
async def done(request: Request, draft: str):
    """Keeps the draft as the user's page for the kind (replacing the one they kept, whatever it is called now) and sets the flag; the
    draft is forgotten."""
    if not enabled(request):
        return _off()
    body = await _body(request)
    if isinstance(body, JSONResponse):
        return body
    kind, backend, me = str(body.get("kind") or ""), request.app.state.backend, ident(request)
    try:
        full = await backend.designs("GET", f"/{draft}", me)
        mine = await page_design(request, kind)
        if mine:
            await backend.designs("PATCH", f"/{mine['id']}", me, {"sutra": full.get("sutra") or "", "notes": full.get("notes") or ""})
            await backend.designs("DELETE", f"/{draft}", me)
            kept = mine["id"]
        else:
            await backend.designs("PATCH", f"/{draft}", me, {"name": designer.page_name(kind)})
            kept = draft
            await set_page(request, kind, kept)
    except BackendError as e:
        return _problem(e)
    return {"design": kept}


@router.post("/cancel/{draft}")
async def cancel(request: Request, draft: str):
    if not enabled(request):
        return _off()
    try:
        await request.app.state.backend.designs("DELETE", f"/{draft}", ident(request))
    except BackendError:
        pass                                          # gone already: nothing to forget
    return {"ok": True}


@router.post("/reset")
async def reset(request: Request):
    """Body ``{kind}``: forgets the user's own page for the kind; the pack's page draws it again."""
    if not enabled(request):
        return _off()
    body = await _body(request)
    if isinstance(body, JSONResponse):
        return body
    kind = str(body.get("kind") or "")
    mine = await page_design(request, kind)
    if mine:
        try:
            await set_page(request, kind, None)
            await request.app.state.backend.designs("DELETE", f"/{mine['id']}", ident(request))
        except BackendError as e:
            return _problem(e)
    return {"ok": True, "removed": bool(mine)}


# ---- Describe ---------------------------------------------------------------------------------------------------------------------

@router.get("")
async def shell(request: Request):
    return RedirectResponse("/designer/new", status_code=303)


@router.get("/new")
async def new(request: Request, q: str = ""):
    """Build → New screen: Describe mode."""
    if not enabled(request):
        return RedirectResponse("/build/new", status_code=303)
    return render(request, "designer/describe.html", modes=MODES, mode="describe", line=q[:500], screen="build",
                  example="TRD BOOK-RATES-2 · click a trade → cash flows + CPTY limits · refresh 30s")


@router.post("/describe")
async def describe_line(request: Request):
    """Body ``{text, draft}``: the line understood (tokens, messages), and when it is whole the Sutra, kept in a scratch draft Design that the
    live result draws (``/v/<kind>/<id>?design=<draft>``)."""
    if not enabled(request):
        return _off()
    body = await _body(request)
    if isinstance(body, JSONResponse):
        return body
    text = str(body.get("text") or "")[:500]
    p = describe.parse(text, await aliases(request))
    out = {"ok": p.ok, "tokens": [t.as_dict() for t in p.tokens], "messages": p.messages, "refresh": p.refresh,
           "refreshNote": "Refresh is kept in the Sutra (refresh:); pages act on it from a later release." if p.refresh else ""}
    if not p.ok:
        return out
    doc = describe.sutra(p, text)
    yaml_text = describe.dump(doc)
    backend, me = request.app.state.backend, ident(request)
    draft = str(body.get("draft") or "")
    try:
        if re.fullmatch(r"[\w-]{1,64}", draft):
            try:
                await backend.designs("PATCH", f"/{draft}", me, {"sutra": yaml_text, "kind": p.kind})
            except BackendError:
                draft = ""
        if not draft:
            draft = (await backend.designs("POST", "", me, {"name": "", "kind": p.kind, "sutra": yaml_text}))["id"]
        await backend.view(p.kind, p.id, me, design=draft)
    except BackendError as e:
        out.update(ok=False, messages=[*p.messages, f"Drishti could not draw it: {e.detail}"])
        return out
    out.update(yaml=yaml_text, draft=draft, kind=p.kind, id=p.id, preview=f"/v/{quote(p.kind)}/{quote(p.id)}?design={draft}&embed=1",
               open=f"/v/{quote(p.kind)}/{quote(p.id)}?design={draft}")
    return out


@router.post("/describe/save/{draft}")
async def describe_save(request: Request, draft: str):
    """Body ``{name}``: keeps the described screen in the user's workspace as a named Design (Build lists it; personal until shared)."""
    if not enabled(request):
        return _off()
    body = await _body(request)
    if isinstance(body, JSONResponse):
        return body
    name = re.sub(r"\s+", " ", str(body.get("name") or "")).strip()[:100] or "My screen"
    try:
        got = await request.app.state.backend.designs("PATCH", f"/{draft}", ident(request), {"name": name})
    except BackendError as e:
        return _problem(e)
    return {"design": got.get("id", draft), "name": name, "build": f"/build/d/{got.get('id', draft)}"}
