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

"""The phone display's routes (docs/guides/PACK_DEVELOPER_GUIDE.md section 6, core/phone.py): the plan an entity page is drawn with on a
phone or tablet, and one panel of the page on its own (a *More on this page* row tapped, a *Show all*, or a live tick of a compact panel),
bound as the viewer with the page's state (its controls, its Sutra, its date)."""
from __future__ import annotations

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse

from core import controls, phone
from core.backend import BackendError
from routes import api_routes, designer_routes
from routes.common import ident, packs

router = APIRouter()


async def plan_for(request: Request, vm: dict, panels: list[dict], embed: bool) -> dict | None:
    """The phone or tablet plan for this page, or None: a desktop, an embedded pane, the phone display off (``ui.phone.enabled``), or
    *Show everything* chosen for the session."""
    if embed or not phone.enabled(request) or phone.show_all(request):
        return None
    dev = phone.device(request)
    if dev == "desk":
        return None
    try:
        pack_list = await packs(request)
    except Exception:  # noqa: BLE001 - the packs are only a layer of rules: without them the site's and edition's still apply
        pack_list = []
    return phone.plan(request, vm, panels, dev, pack_list)


def everything(request: Request, embed: bool) -> bool:
    """Whether a phone or tablet reader chose *Show everything* (the page then offers *Phone view*)."""
    return not embed and phone.enabled(request) and phone.show_all(request) and phone.device(request) != "desk"


@router.get("/v/{kind}/{id_:path}/phone/{panel}")
async def phone_panel(request: Request, kind: str, id_: str, panel: str):
    """One panel of the page as ready HTML: whole (``full=1``: a *More* row or *Show all*), or in its compact form (``compact=1``: a live
    tick of a glance panel). Bound as the viewer, with the page's controls and Sutra; a panel the viewer may not see is not found."""
    more = {}
    ctl = controls.from_query(request.query_params)
    if ctl:
        more["controls"] = ctl
    chosen = (request.query_params.get("sutra") or "").strip()
    if chosen:
        more["sutra"] = chosen
    more.update(await designer_routes.design_kw(request, kind))
    try:
        vm = await request.app.state.backend.view(kind, id_, ident(request), **more)
    except BackendError as e:
        return JSONResponse({"code": e.code, "detail": e.detail}, status_code=e.page_status)
    panels = vm.get("panels") or []
    found = next((p for p in panels if str(p.get("id")) == panel), None)
    if found is None:
        return JSONResponse({"code": "DRS-1001", "detail": f"no panel '{panel}' on this page"}, status_code=404)
    if request.query_params.get("compact") == "1":
        try:
            pack_list = await packs(request)
        except Exception:  # noqa: BLE001
            pack_list = []
        tiers = phone.resolve(vm, panels, phone.layers(request, vm, pack_list), phone.settings(request).get("kinds")
                              if isinstance(phone.settings(request).get("kinds"), dict) else None)
        t = tiers.get(panel) or {}
        if t.get("tier") == "glance":
            found = phone.compact(found, t)
    html = api_routes._panel_html(request, found, ref={"kind": kind, "id": id_})
    return JSONResponse({"id": panel, "kind": found.get("kind"), "title": found.get("title") or "", "html": html},
                        headers={"Cache-Control": "no-store"})
