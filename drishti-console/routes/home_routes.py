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

"""Public pages: the landing page."""
from __future__ import annotations

from urllib.parse import unquote

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse

from core.nextpath import safe_next
from core.packs import samples
from routes.common import packs, render

router = APIRouter(include_in_schema=False)

GENERIC = "<MNEMONIC> <ID> <GO>"


@router.get("/")
async def landing(request: Request):
    """The public landing page. Its example commands come from the packs switched on, never from a pack that may be
    absent (UX-05); with no examples it shows the command's form instead of an entity."""
    settings = request.app.state.settings
    current = samples(await packs(request))
    commands = [s["cmd"] + " <GO>" for s in current][: int(settings.get("ui.landing_examples", 4) or 4)] or [GENERIC]
    known = {s["cmd"].upper() for s in current}
    showcase = [{**s, "cmd": s.get("cmd") if str(s.get("cmd", "")).upper() in known else ""} for s in settings.get("ui.showcase") or []]
    back = None
    if getattr(request.state, "identity", None) is not None:            # signed in: offer the way back to where the logo was clicked
        target = safe_next(request.query_params.get("from"), default="")
        if target and target.split("?", 1)[0] not in ("/", "/login"):
            back = {"href": target, "label": back_label(target)}
    return render(request, "landing.html", showcase=showcase, commands=commands, back=back)


def back_label(path: str) -> str:
    """A short name for the page a signed-in visitor came from: the entity id of a view, else the page's first path part."""
    parts = [p for p in path.split("?", 1)[0].split("/") if p]
    if len(parts) >= 3 and parts[0] in ("v", "share"):
        return unquote(parts[-1]) if parts[0] == "v" else "the shared view"
    names = {"t": "the terminal", "build": "Build", "admin": "Admin", "help": "Help", "inbox": "your inbox", "w": "your workspace",
             "account": "your account", "alerts": "alerts", "monitors": "monitors", "compare": "Compare"}
    return names.get(parts[0], "where you were") if parts else "where you were"


@router.get("/healthz")
def healthz(request: Request):
    """Liveness: the console process answers. Cheap on purpose; a restart would not fix a server that is down. Says too
    whether Calc's Python runtime is installed (it is optional: views work without it)."""
    runtime = request.app.state.calc.runtime
    return {"status": "UP", "pythonRuntime": runtime.version if runtime.installed else "not installed: run tools/fetch-pyodide.sh"}


@router.get("/readyz")
async def readyz(request: Request):
    """Readiness: the console can reach the server (route traffic here only then). 503 while it cannot."""
    try:
        await request.app.state.backend.business_date(request.app.state.auth.service())
    except Exception as e:  # noqa: BLE001 - any failure means not ready
        return JSONResponse({"status": "DOWN", "server": "unreachable", "detail": str(getattr(e, "detail", e))[:200]}, status_code=503)
    return {"status": "UP", "server": "reachable"}
