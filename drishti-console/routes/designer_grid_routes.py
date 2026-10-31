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

"""The Sutra designer's Grid mode (RACHANA_REFERENCE.md › Screen designer and the Build workbench, section 31), behind ``ui.designer``: the screen as a spreadsheet of cells holding
panels as formulas, over the SAME draft Design the other modes edit (``core/designer_grid.py`` translates), and the person's default
designer mode (``designerMode`` in ``/me/settings``)."""
from __future__ import annotations

import re
from urllib.parse import quote

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse, RedirectResponse

from core import designer, designer_grid, designer_outline
from core.backend import BackendError
from routes.common import ident, render
from routes.designer_routes import MODES, _body, _draft, _kit, _new_draft, _off, _problem, _say, default_mode, enabled

router = APIRouter(prefix="/designer", include_in_schema=False)


@router.post("/mode")
async def set_mode(request: Request):
    """Body ``{mode}``: the designer mode this person opens first (Customize on a page opens it; Build → Designer goes to it)."""
    if not enabled(request):
        return _off()
    body = await _body(request)
    if isinstance(body, JSONResponse):
        return body
    mode = str(body.get("mode") or "")
    if mode not in {m[0] for m in MODES}:
        return _say("pick one of Customize, Describe, Outline and Grid")
    try:
        await request.app.state.backend.patch_settings({"designerMode": mode}, ident(request))
    except BackendError as e:
        return _problem(e)
    return {"mode": mode}


@router.get("/grid")
async def grid_page(request: Request, kind: str = "", id: str = "", design: str = ""):
    """Grid mode: the screen as a spreadsheet beside its live preview. ``?kind=&id=`` opens a draft of that page's Sutra; ``?design=``
    an existing draft (from Customize, Describe or Outline)."""
    if not enabled(request):
        return RedirectResponse("/build/new", status_code=303)
    ctx = {"modes": MODES, "mode": "grid", "screen": "build", "kind": kind, "id": id, "default_mode": await default_mode(request)}
    if kind and id and not re.fullmatch(r"[\w-]{1,64}", design or ""):
        try:
            draft = await _new_draft(request, kind, id)
        except designer.DesignerError as e:
            return render(request, "designer/grid.html", problem=str(e), draft="", **ctx)
        except BackendError as e:
            return render(request, "designer/grid.html", problem=e.detail, draft="", **ctx)
        return RedirectResponse(f"/designer/grid?kind={quote(kind)}&id={quote(id)}&design={quote(draft)}", status_code=303)
    return render(request, "designer/grid.html", draft=design, problem="", **ctx)


@router.get("/grid/state/{draft}")
async def grid_state(request: Request, draft: str):
    """The draft as cells (each with its formulas, what they keep, the panels a click opens) and what the formula bar suggests."""
    if not enabled(request):
        return _off()
    try:
        full, doc, _ = await _draft(request, draft)
    except designer.DesignerError as e:
        return _say(str(e))
    except BackendError as e:
        return _problem(e)
    g = designer_grid.grid(doc)
    return {"grid": g, "suggest": designer_grid.suggestions(doc, g, await _kit(request)), "yaml": full.get("sutra") or "",
            "name": full.get("name") or "", "places": designer.PLACE_LABELS}


def _step(doc: dict, keep: dict, body: dict, kit_refs: list) -> None:
    what = str(body.get("op") or "")
    if what == "formula":
        designer_grid.set_formula(doc, str(body.get("cell") or ""), str(body.get("text") or "")[:4000], kit_refs)
    elif what == "merge":
        designer_grid.merge(doc, str(body.get("from") or ""), str(body.get("to") or ""))
    elif what == "split":
        designer_grid.split(doc, str(body.get("cell") or ""))
    elif what in ("insert", "delete"):
        getattr(designer_grid, what)(doc, str(body.get("axis") or ""), body.get("at", 0))
    elif what == "move":
        designer_grid.move(doc, str(body.get("from") or ""), str(body.get("to") or ""))
    elif what == "make-grid":
        designer_grid.make_grid(doc)
    elif what == "widths":
        designer_grid.set_widths(doc, body.get("widths"))
    elif what == "nest":
        designer_outline.nest(doc, keep, body.get("index"), str(body.get("under") or ""), str(body.get("place") or "detail-below"))
    elif what == "unnest":
        designer_outline.unnest(doc, keep, str(body.get("parent") or ""))
    else:
        raise designer.DesignerError("unknown step")


@router.post("/grid/op/{draft}")
async def grid_op(request: Request, draft: str):
    """One Grid step (``formula`` on a cell or a child cell, ``merge``, ``split``, ``insert``, ``delete``, ``move``, ``make-grid``,
    ``widths``, ``nest``, ``unnest``): the page is drawn with it before it is kept; a step the server refuses is undone and said in plain
    words. A formula that cannot be read is said with the part to fix (``start``, ``end``) and changes nothing."""
    if not enabled(request):
        return _off()
    body = await _body(request)
    if isinstance(body, JSONResponse):
        return body
    backend, me = request.app.state.backend, ident(request)
    kind, id_ = str(body.get("kind") or ""), str(body.get("id") or "")
    try:
        full, doc, keep = await _draft(request, draft)
        _step(doc, keep, body, [f.get("ref", "") for f in await _kit(request) if f.get("ref")] if body.get("op") == "formula" else [])
        await backend.designs("PATCH", f"/{draft}", me, {"sutra": designer.dump(doc), "notes": designer.notes_with(full.get("notes") or "", keep)})
        if kind and id_:
            try:
                await backend.view(kind, id_, me, design=draft)
            except BackendError as e:
                await backend.designs("PATCH", f"/{draft}", me, {"sutra": full.get("sutra") or "", "notes": full.get("notes") or ""})
                return _say(f"That change would break the page, so it was not made: {e.detail}")
    except designer_grid.FormulaError as e:
        return JSONResponse({"code": "DRS-5001", "detail": str(e), "start": e.start, "end": e.end}, status_code=400)
    except designer.DesignerError as e:
        return _say(str(e))
    except (TypeError, ValueError):
        return _say("that step is missing something: reload the page")
    except BackendError as e:
        return _problem(e)
    return {"ok": True}
