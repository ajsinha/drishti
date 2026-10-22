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

"""UI Board phase P4 (docs/architecture/UI_BOARD.md section 10): the console's Boards pages (My boards, the board viewer, the Board Designer and
the chip editor) and the same-origin calls their scripts make.

The pages are shells: ``static/js/ui-board-*.js`` ask ``/ui/api/...``, which passes each call on to the server's ``/api/v1/ui/...`` as the signed-in
user (the server decides every power, mask and limit). Documents go as sent (YAML or JSON), with ``If-Match`` and ``If-None-Match`` passed on and the
``ETag`` passed back, so a stale save is the server's ``409 DRS-5057``. The render streams (``GET /ui/boards/{name}/render`` and ``POST /ui/render``)
are relayed event by event; a refusal before the stream (the board check's ``422 DRS-2071`` with its problems) is answered as JSON. Two calls are
the console's own: ``/ui/api-yaml/dump`` and ``/ui/api-yaml/load``, the Designer's YAML tab (one document, two editors)."""
from __future__ import annotations

import json
import re
from pathlib import Path
from urllib.parse import quote

import yaml
from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.responses import JSONResponse, RedirectResponse, Response, StreamingResponse

from core.backend import BackendError
from core.csrf import json_body
from core.yaml_roundtrip import merge
from routes.common import ident, render

router = APIRouter(include_in_schema=False)

# the server paths the page scripts may reach below /api/v1/ui: nothing else is passed on
_ALLOWED = ("boards", "chips", "renders", "render", "candidates")
_NAME = re.compile(r"^[a-z0-9][a-z0-9-]{0,62}$")
_KEPT = ("problems", "retryAfter")
BOARD_ORDER = ("rachana", "board", "version", "title", "description", "owner", "workspace", "params", "chips", "traces", "layout")
CHIP_ORDER = ("rachana", "chip", "version", "title", "category", "tags", "compatible-with", "manual", "pins", "limits", "body")
WAIT = 35.0
MAX_DOC = 256 * 1024                       # the console's own cap on a document it relays; the server's limits (64 KB by default) answer first


def _problem(e: BackendError) -> JSONResponse:
    extra = {k: e.extra[k] for k in _KEPT if isinstance(e.extra, dict) and k in e.extra}
    headers = {"Retry-After": str(e.retry_after)} if getattr(e, "retry_after", None) else None
    return JSONResponse({"code": e.code, "detail": e.detail, **extra}, status_code=e.status, headers=headers)


def _where(request: Request) -> dict:
    """The board's address beside its name (P2): ``owner`` (a personal board shared with you) or ``workspace``."""
    q = request.query_params
    return {k: q.get(k) for k in ("owner", "workspace") if q.get(k)}


# ---- pages ------------------------------------------------------------------------------------------------------------------------------------
# One designer (the product owner, 2.7.0): the Board Designer is the Build designer's Board mode, under /build like the screen designer, with a
# Chip mode beside it (P4b builds the chip designer there). "Boards" is Build's one list of boards and designs (/build). The viewer is /ui/b/<name>.

def _enabled(request: Request) -> None:
    """The pages below answer only with ``ui.boards`` on (DRISHTI_UI_BOARDS, off by default); off, they are the console's 404 page saying so."""
    if not getattr(request.app.state, "ui_boards", False):
        raise HTTPException(404, "Boards and chips are not enabled on this console (ui.boards). Build -> Designer opens the screen designer.")


_ON = [Depends(_enabled)]


def _q(request: Request) -> str:
    return ("?" + request.url.query) if request.url.query else ""


@router.get("/ui", dependencies=_ON)
async def boards_page(request: Request):
    """The Boards list is Build's list of boards and designs."""
    return RedirectResponse("/build" + _q(request), status_code=307)


@router.get("/ui/new", dependencies=_ON)
@router.get("/ui/design/{name}", dependencies=_ON)
async def old_design_page(request: Request, name: str = ""):
    return RedirectResponse(("/build/board/" + quote(name, safe="") if name else "/build/board/new") + _q(request), status_code=307)


@router.get("/ui/chip-editor", dependencies=_ON)
async def old_chip_page(request: Request):
    return RedirectResponse("/build/chip/new", status_code=307)


@router.get("/ui/b/{name}", dependencies=_ON)
async def view_page(request: Request, name: str):
    """The board viewer on a saved board (``?owner=`` or ``?workspace=``, ``?device=phone``)."""
    if not _NAME.match(name):
        return render(request, "ui/view.html", status_code=404, screen="ui-boards", board="", where={}, missing=True)
    return render(request, "ui/view.html", screen="ui-boards", board=name, where=_where(request))


@router.get("/build/board/start", dependencies=_ON)
async def guided_page(request: Request):
    """New board, guided (concept C): what data, what to show, arrange, save; a live preview beside the steps; the result opens in the designer."""
    return render(request, "ui/new_board.html", screen="ui-new-board", where=_where(request))


def _plural(word: str) -> str:
    return word if word.endswith("s") else (word[:-1] + "ies" if word.endswith("y") and word[-2:-1] not in "aeiou" else word + "s")


@router.get("/build/board-api/sources", dependencies=_ON)
async def board_sources(request: Request):
    """The data a new board can start from, in words, from the kinds of the person's active packs (their overviews): "One trade" (the entity by
    id), "Trades" (the kind's search) and "Trades of one book" for each of the kind's key columns. Each: ``{id, title, hint, pack, kind, shape:
    one|many, supply, params}``; a parameter with ``ask`` is asked for in words when the source is chosen."""
    backend, me, out = request.app.state.backend, ident(request), []
    try:
        packs = await backend.packs(me)
    except BackendError:
        packs = []
    for p in packs or []:
        if not isinstance(p, dict) or not p.get("name") or p.get("active") is False:
            continue
        try:
            ov = await backend.pack_overview(p["name"], me)
        except BackendError:
            continue
        pack = str(ov.get("name") or p["name"])
        for k in (ov or {}).get("kinds") or []:
            kind = str(k.get("kind") or "")
            if not kind:
                continue
            one = str(k.get("label") or kind.replace("-", " ")).lower()
            many, example = _plural(one), k.get("example")
            out.append({"id": f"{pack}/{kind}:many", "title": many[:1].upper() + many[1:], "hint": f"Every {one}, searched and sorted", "pack": pack, "kind": kind,
                        "shape": "many", "supply": {"connector": f"{pack}/{kind}-search"}, "params": {}})
            for col in list(k.get("columns") or [])[:3]:
                c = str(col).replace("_", " ").replace("-", " ").lower()
                param = re.sub(r"[^a-z0-9]+", "-", c).strip("-") or "key"
                out.append({"id": f"{pack}/{kind}:by:{param}", "title": f"{many[:1].upper() + many[1:]} of one {c}", "hint": f"The {many} of the {c} you name",
                            "pack": pack, "kind": kind, "shape": "many", "supply": {"connector": f"{pack}/{kind}-search", "query": {str(col): f"%{{board.params.{param}}}"}},
                            "params": {param: {"type": "id", "required": True, "ask": f"Which {c}?"}}})
            out.append({"id": f"{pack}/{kind}:one", "title": f"One {one}", "hint": f"A single {one}, by its id" + (f", e.g. {example}" if example else ""),
                        "pack": pack, "kind": kind, "shape": "one", "supply": {"connector": f"{pack}/{kind}", "query": {"id": "%{board.params.id}"}},
                        "params": {"id": {"type": "id", "of": f"{pack}/{kind}", "required": True, "ask": f"Which {one}?", **({"default": example, "example": example} if example else {})}}})
    return JSONResponse({"sources": out})


@router.get("/build/board/new", dependencies=_ON)
@router.get("/build/board/{name}", dependencies=_ON)
async def design_page(request: Request, name: str = ""):
    """The designer in Board mode: a new board (``?design=<id>`` starts from a screen design, converted by P5's converter), or a saved one."""
    name = "" if name == "new" else name                 # FastAPI may match /new as {name}
    if name and not _NAME.match(name):
        return render(request, "ui/design.html", status_code=404, screen="ui-designer", board="", where={}, mode="board", chip="", design="")
    return render(request, "ui/design.html", screen="ui-designer", board=name, where=_where(request), mode="board", chip="",
                  design=str(request.query_params.get("design") or "")[:80], example=str(request.query_params.get("example") or "")[:80])


@router.get("/build/chip/new", dependencies=_ON)
@router.get("/build/chip/{name}", dependencies=_ON)
async def chip_page(request: Request, name: str = ""):
    """The designer in Chip mode (P4b): the pin table, the body editor, the manual form, the test bench and the release steps of one chip."""
    name = "" if name == "new" else name
    return render(request, "ui/design.html", screen="ui-chip-designer", board="", where={}, mode="chip", chip=name if _NAME.match(name or "") else "", design="",
                  example=str(request.query_params.get("example") or "")[:80])


# ---- existing designs as boards, and back: P5's converter on the server (UI_BOARD.md 12.4, 12.6) ---------------------------------------------

async def _convert(request: Request, path: str, body: dict):
    content = json.dumps(body).encode()
    _, answer, _ = await request.app.state.backend.ui("POST", path, ident(request), content, "application/json", {}, wait=WAIT)
    return answer if isinstance(answer, dict) else {}


@router.get("/build/board-api/from-design/{id_}", dependencies=_ON)
async def from_design(request: Request, id_: str):
    """A screen design's Sutra converted into a board by P5's converter (``POST /api/v1/ui/convert/sutra``): the seed chips where a panel fits one,
    board-local chips (with their bindings written over the record's pins) where none does, the header chip and its control traces. Opened in
    Board mode unsaved; the notes name the board-local chips (Chip Store candidates) and any check finding."""
    try:
        design = await request.app.state.backend.designs("GET", f"/{quote(id_, safe='')}", ident(request))
        got = await _convert(request, "/convert/sutra", {"sutra": str(design.get("sutra") or ""), "kind": design.get("kind") or None})
    except BackendError as e:
        return _problem(e)
    locals_ = [_local(x) for x in got.get("locals") or [] if isinstance(x, dict)]
    notes = [f"{p.get('rule')} {p.get('path')}: {p.get('message')}" for p in got.get("problems") or []]
    if got.get("reason"):
        notes.append(str(got["reason"]))
    return JSONResponse({"document": got.get("document") or {}, "status": got.get("status"), "locals": locals_, "notes": notes, "design": id_,
                         "params": await _sample_params(request, id_, design, got.get("document") or {})})


_NEAR = re.compile(r"no (?:Chip Store|seed) chip \(([^)]*)\)\s*(.*)")


def _local(x: dict) -> dict:
    """A board-local chip as the designer lists it: {chip, from, near (the nearest Chip Store chips), why}."""
    reason = str(x.get("reason") or "no seed chip fits")
    m = _NEAR.match(reason)
    near = [n.strip() for n in m.group(1).split(",") if n.strip()] if m else []
    return {"chip": x.get("chip"), "from": x.get("from"), "near": near, "why": (m.group(2) if m else reason) or reason}


async def _sample_params(request: Request, id_: str, design: dict, board: dict) -> dict:
    """The board parameter ``id`` prefilled from the design's first sample (a stored entity's id, or the id field of a brought document), so a
    converted entity page shows data at once. Empty when the board has no ``id`` parameter or no sample names one."""
    if "id" not in (board.get("params") or {}):
        return {}
    for s in design.get("samples") or []:
        if not isinstance(s, dict):
            continue
        if isinstance(s.get("ref"), dict) and s["ref"].get("id"):
            return {"id": str(s["ref"]["id"])}
        try:
            doc = await request.app.state.backend.designs("GET", f"/{quote(id_, safe='')}/samples/document", ident(request), name=s.get("name"))
        except BackendError:
            continue
        kind = str(design.get("kind") or "").upper().replace("-", "_")
        if isinstance(doc, dict):
            for k in ([f"{kind}_ID"] if kind else []) + ["ID", "id"] + [k for k in doc if k.upper().endswith("_ID")]:
                if isinstance(doc.get(k), (str, int)) and str(doc[k]):
                    return {"id": str(doc[k])}
    return {}


@router.post("/build/board-api/to-sutra", dependencies=_ON)
async def to_sutra(request: Request):
    """Export Sutra: the board painted back into a Sutra by P5's ``BoardSutra`` (``POST /api/v1/ui/convert/board``), so pack authors keep shipping
    Sutras until 3.0. Only a board of one record (a parameter ``id`` of a kind) has one; otherwise ``yaml`` is null and the notes say why."""
    body = await json_body(request, limit=MAX_DOC)
    doc = body.get("document")
    if not isinstance(doc, dict):
        return JSONResponse({"code": "DRS-2070", "detail": "document is an object"}, status_code=400)
    try:
        got = await _convert(request, "/convert/board", {"document": doc})
    except BackendError as e:
        return _problem(e)
    return JSONResponse({"yaml": got.get("yaml"), "notes": got.get("notes") or []})


# ---- board and chip examples (Build → Designer → New, config/ui-examples.yaml) --------------------------------------------------------------

EXAMPLES_FILE = Path(__file__).resolve().parent.parent / "config" / "ui-examples.yaml"


def ui_examples() -> list[dict]:
    """The board and chip examples: ``[{name, kind, title, summary, ...}]`` (read on each call: a small file)."""
    try:
        got = yaml.safe_load(EXAMPLES_FILE.read_text()) or {}
    except (OSError, yaml.YAMLError):
        return []
    return [e for e in got.get("examples") or [] if isinstance(e, dict) and e.get("name") and e.get("kind") in ("board", "chip")]


@router.get("/build/board-api/examples", dependencies=_ON)
async def examples_list(request: Request):
    return JSONResponse([{k: e.get(k) for k in ("name", "kind", "title", "summary")} for e in ui_examples()])


@router.get("/build/board-api/example/{name}", dependencies=_ON)
async def example_open(request: Request, name: str):
    """One example as YOUR unsaved copy: ``{name, kind, document, notes}``. A ``from:`` chip is copied as ``<name>-mine`` version 1; a ``sutra:``
    page is read from the server and converted by P5's converter, as you."""
    ex = next((e for e in ui_examples() if e["name"] == name), None)
    if ex is None:
        return JSONResponse({"code": "DRS-9001", "detail": f"no example {name}"}, status_code=404)
    backend, me, notes = request.app.state.backend, ident(request), []
    try:
        if ex.get("document"):
            doc = json.loads(json.dumps(ex["document"]))
        elif ex.get("from"):
            ref = str(ex["from"])
            chip, _, version = ref.partition("@")
            _, got, _ = await backend.ui("GET", f"/chips/{quote(chip, safe='')}/{quote(version, safe='')}", me)
            doc = json.loads(json.dumps((got or {}).get("document") or {}))
            doc.update(chip=f"{chip}-mine", version=1)
            doc.pop("compatible-with", None)
            notes.append(f"a copy of {ref}: change it, then Save draft")
        elif ex.get("sutra"):
            s = ex["sutra"]
            text = await backend.sutra_source(str(s.get("name")), int(s.get("version") or 1), me)
            got = await _convert(request, "/convert/sutra", {"sutra": text})
            doc = got.get("document") or {}
            notes += [f"{x['chip']} is a board-local chip: a Chip Store candidate" for x in got.get("locals") or []]
        else:
            return JSONResponse({"code": "DRS-2070", "detail": f"the example {name} has no document"}, status_code=500)
    except BackendError as e:
        return _problem(e)
    return JSONResponse({"name": name, "kind": ex["kind"], "title": ex.get("title"), "document": doc, "notes": notes})


# ---- the YAML tab -------------------------------------------------------------------------------------------------------------------------------

def _write_key(key, value, out: list) -> None:
    out.extend(yaml.safe_dump({key: value}, sort_keys=False, allow_unicode=True, width=140).rstrip("\n").split("\n"))


def _plain(doc: dict) -> str:
    return yaml.safe_dump(doc, sort_keys=False, allow_unicode=True, width=140)


@router.post("/ui/api-yaml/dump")
async def yaml_dump(request: Request):
    """``{document, yaml?}`` -> ``{yaml}``: the canvas written over the text the person had, keeping their comments and order where it can."""
    body = await json_body(request, limit=MAX_DOC)
    doc = body.get("document")
    if not isinstance(doc, dict):
        return JSONResponse({"code": "DRS-2070", "detail": "document is an object"}, status_code=400)
    if body.get("kind") == "chip":                 # Chip mode's YAML tab: the chip's own key order, the pins merged by name
        return JSONResponse({"yaml": merge(str(body.get("yaml") or ""), doc, CHIP_ORDER, _write_key, ("pins",), _plain)})
    return JSONResponse({"yaml": merge(str(body.get("yaml") or ""), doc, BOARD_ORDER, _write_key, ("chips",), _plain)})


@router.post("/ui/api-yaml/load")
async def yaml_load(request: Request):
    """``{yaml}`` -> ``{document}``, or ``400 DRS-2070`` with the line and column of the first mistake (the text view checks on pause)."""
    body = await json_body(request, limit=MAX_DOC)
    try:
        doc = yaml.safe_load(str(body.get("yaml") or ""))
    except yaml.YAMLError as e:
        mark = getattr(e, "problem_mark", None)
        at = {"line": mark.line + 1, "column": mark.column + 1} if mark else {}
        return JSONResponse({"code": "DRS-2070", "detail": f"not YAML: {getattr(e, 'problem', None) or e}", **at}, status_code=400)
    if not isinstance(doc, dict):
        return JSONResponse({"code": "DRS-2070", "detail": "a board document is a map (board:, chips:, traces:, layout:)"}, status_code=400)
    return JSONResponse({"document": json.loads(json.dumps(doc, default=str))})


# ---- supplier attributes for the mapping sheet ---------------------------------------------------------------------------------------------------

_FORM_TYPES = {"id": "id", "amount": "measure", "number": "measure", "integer": "measure", "decimal": "measure", "date": "date", "datetime": "date",
               "text": "dimension", "enum": "dimension", "bool": "dimension"}


def _model_attributes(model: dict) -> list[dict]:
    """A BI model's fields as the person may read them: measures as ``measure``, columns as ``id``, ``date`` or ``dimension`` by their type."""
    out = []
    for m in model.get("measures") or []:
        name = m.get("name") if isinstance(m, dict) else str(m)
        if name:
            out.append({"name": name, "type": "measure", "label": (m.get("label") if isinstance(m, dict) else None) or name})
    tables = model.get("tables") or []
    for t in tables.values() if isinstance(tables, dict) else tables:
        if not isinstance(t, dict):
            continue
        for c in t.get("columns") or []:
            if not isinstance(c, dict) or not c.get("name"):
                continue
            typ = str(c.get("type") or "").lower()
            kind = "date" if "date" in typ or "time" in typ else ("id" if c.get("key") or str(c["name"]).upper().endswith("_ID") else "dimension")
            out.append({"name": f"{t.get('name')}.{c['name']}" if t.get("name") else c["name"], "type": kind, "label": c.get("label") or c["name"]})
    return out


@router.get("/ui/api-suppliers")
async def supplier_attributes(request: Request):
    """The attributes a supplier offers the person, with the pin type each may feed, for the mapping sheet: a pack's kind from its search form
    (``connector=<pack>/<kind>`` or ``<kind>-search``, ``live=<pack>/<kind>``), a BI model's measures and columns (``model=``). ``attributes: null`` when
    the console cannot tell (the sheet then takes typed names; the board check and the render have the last word)."""
    q = request.query_params
    backend, me = request.app.state.backend, ident(request)
    try:
        if q.get("model"):
            model = await backend.bi("GET", "/models/" + quote(q["model"], safe=":"), me)
            return JSONResponse({"attributes": _model_attributes(model if isinstance(model, dict) else {}) or None})
        ref = q.get("connector") or q.get("live") or ""
        if "/" not in ref:
            return JSONResponse({"attributes": None})
        pack, kind = ref.split("/", 1)
        kind = kind[:-len("-search")] if kind.endswith("-search") else kind
        got = await backend.form(pack, kind, me)
        fields = ((got or {}).get("form") or {}).get("fields") or []
        attrs = [{"name": f["path"], "label": f.get("label") or f["path"], "type": _FORM_TYPES.get(str(f.get("type")), "dimension")}
                 for f in fields if isinstance(f, dict) and f.get("path") and not f.get("hidden")]
        return JSONResponse({"attributes": attrs or None})
    except BackendError:
        return JSONResponse({"attributes": None})


# ---- the render streams -------------------------------------------------------------------------------------------------------------------------

async def _relay(request: Request, method: str, path: str, body: dict | None, params: dict):
    """Opens the server's stream; a refusal before it is JSON, then every event is passed on as it comes (and the server's stream closed when the
    browser goes)."""
    backend, me, opened = request.app.state.backend, ident(request), []
    stream = backend.ui_events(method, path, me, body=body, params=params, opened=opened)
    try:
        first = await stream.__anext__()
    except StopAsyncIteration:
        return JSONResponse({"code": "DRS-2079", "detail": "the render ended before it began"}, status_code=502)
    except BackendError as e:
        return _problem(e)

    async def events():
        try:
            pair = first
            while True:
                event, data = pair
                yield f"event: {event}\ndata: {data}\n\n"
                if await request.is_disconnected():
                    break
                pair = await stream.__anext__()
        except StopAsyncIteration:
            pass
        except BackendError as e:
            yield f"event: ended\ndata: {json.dumps({'code': e.code, 'message': e.detail})}\n\n"
        finally:
            for r in opened:
                await r.aclose()
            await stream.aclose()

    return StreamingResponse(events(), media_type="text/event-stream", headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})


@router.get("/ui/api/boards/{name}/render")
async def render_saved(request: Request, name: str):
    params = {k: v for k, v in request.query_params.items() if k in ("owner", "workspace", "device", "stream") or k.startswith("params.")}
    return await _relay(request, "GET", f"/boards/{quote(name, safe='')}/render", None, params)


@router.post("/ui/api/render")
async def render_dynamic(request: Request):
    """An unsaved board (the Designer's live data): ``{board, params, device, stream?}`` as the server takes it."""
    body = await json_body(request, limit=MAX_DOC)
    return await _relay(request, "POST", "/render", body, {})


# ---- everything else: passed on ---------------------------------------------------------------------------------------------------------------

@router.api_route("/ui/api/{path:path}", methods=["GET", "POST", "PUT", "DELETE"])
async def proxy(request: Request, path: str):
    """One call of a Boards page, passed on as the caller. A body goes as sent (a board in YAML or JSON); ``If-Match`` and ``If-None-Match`` go on and
    the ``ETag`` comes back."""
    head = path.split("/", 1)[0]
    if head not in _ALLOWED or ".." in path or "//" in path:
        return JSONResponse({"code": "DRS-9001", "detail": "no such UI path"}, status_code=404)
    content, ctype = None, None
    if request.method in ("POST", "PUT"):
        content = await request.body()
        if len(content) > MAX_DOC:
            return JSONResponse({"code": "DRS-2075", "detail": f"the document is over {MAX_DOC // 1024} KB"}, status_code=413)
        ctype = (request.headers.get("content-type") or "application/json").split(";")[0]
    headers = {k: request.headers[k] for k in ("if-match", "if-none-match") if k in request.headers}
    params = dict(request.query_params)
    try:
        status, answer, etag = await request.app.state.backend.ui(request.method, "/" + path, ident(request), content, ctype, headers,
                                                                 wait=WAIT, **params)
    except BackendError as e:
        return _problem(e)
    out = {"Cache-Control": "no-store"}
    if etag:
        out["ETag"] = etag
    if answer is None:
        return Response(status_code=204 if status in (200, 204) else status, headers=out)
    if isinstance(answer, str):
        return Response(answer, status_code=status, media_type="text/plain", headers=out)
    return JSONResponse(answer, status_code=status, headers=out)
