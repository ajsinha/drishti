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

"""Admin → Configuration (core/config_editor.py): the page, a proposal, and the decisions on proposals (the server's four-eyes queue,
``/api/v1/admin/settings/proposals``). Administrators only; nothing is written live: an approval writes the change."""
from __future__ import annotations

from pathlib import Path
from urllib.parse import quote

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse

from core import config_editor
from core.config import local_file
from core.backend import BackendError
from core.csrf import BodyError, json_body
from core.settings_page import console_effective, console_keys, server_effective
from routes.common import ident, library, render

router = APIRouter(prefix="/admin", include_in_schema=False)
CONSOLE_DIR = Path(__file__).resolve().parent.parent
VERBS = ("approve", "reject", "withdraw")


def _problem(e: BackendError) -> JSONResponse:
    return JSONResponse({"code": e.code, "detail": e.detail}, status_code=e.status)


def _local_file(request: Request) -> Path:
    """This console's own settings file (a test points the app at its own)."""
    return getattr(request.app.state, "console_local_file", None) or local_file(CONSOLE_DIR / "config")


@router.get("/configuration")
async def page(request: Request, q: str = "", tier: str = "everyday", area: str = ""):
    """Every setting of the tier chosen (everyday first; ``tier=all`` every tier), searchable, with its value in effect, and the changes
    waiting for a second administrator."""
    me = ident(request)
    if not me.is_admin:
        return render(request, "admin/forbidden.html", status_code=403)
    lib = await library(request)
    doc = lib.render("configuration") if "configuration" in lib.guides else {"html": ""}
    every = config_editor.rows(doc.get("html") or "")
    server_error = None
    try:
        server = server_effective(await request.app.state.backend.admin("GET", "/settings", me))
    except BackendError as e:
        server, server_error = {}, e
    try:   # which of this console's own keys are secrets: the server's rule (keys only, never a value)
        secrets = set((await request.app.state.backend.admin("POST", "/settings/secret-keys", me,
                                                             {"keys": console_keys(CONSOLE_DIR / "config")})).get("secret") or [])
    except BackendError as e:
        secrets, server_error = set(console_keys(CONSOLE_DIR / "config")), server_error or e   # not known: nothing of the console's is shown
    console = console_effective(request.app.state.settings, CONSOLE_DIR / "config", secrets=secrets)
    try:   # the server's launch roots and every other path setting, absolute, each with where it came from ("Resolved paths")
        paths = await request.app.state.backend.admin("GET", "/settings/paths", me)
    except BackendError as e:
        paths, server_error = {}, server_error or e
    try:
        proposals = (await request.app.state.backend.admin("GET", "/settings/proposals", me)).get("proposals") or []
    except BackendError as e:
        proposals, server_error = [], server_error or e
    tier = tier if tier in {t for t, _ in config_editor.TIERS} | {"all"} else "everyday"
    picked = config_editor.pick(every, q, "" if tier == "all" else tier, area)
    counts = {t: sum(1 for r in every if r["tier"] == t) for t, _ in config_editor.TIERS}
    areas = list(dict.fromkeys((r["area"], r["area_title"]) for r in every))
    shown = [config_editor.with_effect(r, server, console) for r in picked[:config_editor.PAGE_ROWS]]
    waiting = [p for p in proposals if p.get("state") == "proposed"]
    return render(request, "admin/configuration.html", rows=shown, matched=len(picked), total=len(every), counts=counts, tiers=config_editor.TIERS,
                  tier=tier, q=q, area=area, areas=areas, waiting=waiting, decided=[p for p in proposals if p.get("state") != "proposed"][:20],
                  server_error=server_error, viewer=me.user, page_rows=config_editor.PAGE_ROWS, screen="admin",
                  path_roots=paths.get("roots") or [], path_settings=paths.get("anchored") or [])


@router.post("/configuration/propose")
async def propose(request: Request):
    """Body ``{key, area, value, was, reason}``: a change proposed for a second administrator (the server refuses secrets)."""
    if not ident(request).is_admin:
        return JSONResponse({"code": "DRS-5002", "detail": "administrators only"}, status_code=403)
    try:
        body = await json_body(request, limit=16384)
    except BodyError as e:
        return JSONResponse({"code": e.code, "detail": e.detail}, status_code=e.status)
    if not isinstance(body, dict):
        return JSONResponse({"code": "DRS-5001", "detail": "a JSON object is expected"}, status_code=400)   # a secret: the server refuses it
    sent = {k: str(body.get(k) or "") for k in ("key", "area", "value", "was", "reason")}
    try:
        return await request.app.state.backend.admin("POST", "/settings/proposals", ident(request), sent)
    except BackendError as e:
        return _problem(e)


@router.post("/configuration/proposals/{pid}/{verb}")
async def decide(request: Request, pid: str, verb: str):
    """Approve, reject or withdraw a proposal (four eyes: the server refuses your own). An approved console key is written here, into
    this console's application.local.yaml, then the server is told it was applied."""
    me = ident(request)
    if not me.is_admin or verb not in VERBS:
        return JSONResponse({"code": "DRS-5002", "detail": "administrators only"}, status_code=403 if not me.is_admin else 404)
    try:
        body = await json_body(request, limit=4096)
    except BodyError:
        body = {}
    comment = str((body or {}).get("comment") or "") if isinstance(body, dict) else ""
    try:
        got = await request.app.state.backend.admin("POST", f"/settings/proposals/{quote(pid, safe='')}/{verb}", me,
                                                    {"comment": comment} if verb != "withdraw" else {})
        if verb == "approve" and got.get("area") == "console" and not got.get("applied"):
            config_editor.write_console_key(_local_file(request), str(got["key"]), str(got.get("value") or ""),
                                            f"{got['key']} by {got.get('proposedBy')}, approved by {me.user} ({got.get('id')})")
            got = await request.app.state.backend.admin("POST", f"/settings/proposals/{quote(pid, safe='')}/applied", me, {})
    except BackendError as e:
        return _problem(e)
    except (OSError, ValueError) as e:
        return JSONResponse({"code": "DRS-5001", "detail": f"approved, but this console could not write its file: {e}"}, status_code=500)
    return got
