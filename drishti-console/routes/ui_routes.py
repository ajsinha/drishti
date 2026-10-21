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
from urllib.parse import quote

import yaml
from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse, RedirectResponse, Response, StreamingResponse

from core.backend import BackendError
from core.csrf import json_body
from core.yaml_roundtrip import merge
from routes.common import ident, render

router = APIRouter(include_in_schema=False)

# the server paths the page scripts may reach below /api/v1/ui: nothing else is passed on
_ALLOWED = ("boards", "chips", "renders", "render")
_NAME = re.compile(r"^[a-z0-9][a-z0-9-]{0,62}$")
_KEPT = ("problems", "retryAfter")
BOARD_ORDER = ("rachana", "board", "version", "title", "description", "owner", "workspace", "params", "chips", "traces", "layout")
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

def _q(request: Request) -> str:
    return ("?" + request.url.query) if request.url.query else ""


@router.get("/ui")
async def boards_page(request: Request):
    """The Boards list is Build's list of boards and designs."""
    return RedirectResponse("/build" + _q(request), status_code=307)


@router.get("/ui/new")
@router.get("/ui/design/{name}")
async def old_design_page(request: Request, name: str = ""):
    return RedirectResponse(("/build/board/" + quote(name, safe="") if name else "/build/board/new") + _q(request), status_code=307)


@router.get("/ui/chip-editor")
async def old_chip_page(request: Request):
    return RedirectResponse("/build/chip/new", status_code=307)


@router.get("/ui/b/{name}")
async def view_page(request: Request, name: str):
    """The board viewer on a saved board (``?owner=`` or ``?workspace=``, ``?device=phone``)."""
    if not _NAME.match(name):
        return render(request, "ui/view.html", status_code=404, screen="ui-boards", board="", where={}, missing=True)
    return render(request, "ui/view.html", screen="ui-boards", board=name, where=_where(request))


@router.get("/build/board/new")
@router.get("/build/board/{name}")
async def design_page(request: Request, name: str = ""):
    """The designer in Board mode: a new board (``?design=<id>`` starts from a screen design through the adapter), or a saved one."""
    if name and not _NAME.match(name):
        return render(request, "ui/design.html", status_code=404, screen="ui-designer", board="", where={}, mode="board", chip="", design="")
    return render(request, "ui/design.html", screen="ui-designer", board=name, where=_where(request), mode="board", chip="",
                  design=str(request.query_params.get("design") or "")[:80])


@router.get("/build/chip/new")
@router.get("/build/chip/{name}")
async def chip_page(request: Request, name: str = ""):
    """The designer in Chip mode, the place P4b's chip designer fills: for now a chip's document, its draft and its examples on the test bench."""
    return render(request, "ui/design.html", screen="ui-chip-designer", board="", where={}, mode="chip", chip=name if _NAME.match(name or "") else "", design="")


# ---- existing designs as boards, and back (the thin adapter until P5's converter) ----------------------------------------------------------

@router.get("/build/board-api/from-design/{id_}")
async def from_design(request: Request, id_: str):
    """A screen design's Sutra as a board document of panel-* chips (core/sutra_board.py), opened in Board mode unsaved."""
    from core.sutra_board import sutra_to_board
    try:
        design = await request.app.state.backend.designs("GET", f"/{quote(id_, safe='')}", ident(request))
    except BackendError as e:
        return _problem(e)
    doc, notes = sutra_to_board(str(design.get("sutra") or ""), name=str(design.get("name") or ""), kind=str(design.get("kind") or ""))
    return JSONResponse({"document": doc, "notes": notes, "design": id_})


@router.post("/build/board-api/to-sutra")
async def to_sutra(request: Request):
    """Export Sutra: a board of panel-* chips written as a Sutra, so pack authors keep working until 3.0 (core/sutra_board.py)."""
    from core.sutra_board import board_to_sutra
    body = await json_body(request, limit=MAX_DOC)
    doc = body.get("document")
    if not isinstance(doc, dict):
        return JSONResponse({"code": "DRS-2070", "detail": "document is an object"}, status_code=400)
    text, notes = board_to_sutra(doc, kind=str(body.get("kind") or ""))
    return JSONResponse({"yaml": text, "notes": notes})


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
