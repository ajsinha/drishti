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

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse

from routes.common import render

router = APIRouter(include_in_schema=False)

SHOWCASE = [
    {"slug": "irs", "title": "Interest rate swap", "cmd": "TRD IRS-48213", "sutra": "irs-vanilla v3"},
    {"slug": "fx-swap", "title": "FX swap", "cmd": "TRD FXS-20931", "sutra": "fx-swap v2"},
    {"slug": "commodity-future", "title": "Commodity future", "cmd": "TRD CFT-77120", "sutra": "listed-future v1"},
    {"slug": "netting-set", "title": "Netting set", "cmd": "NSET NS-NORTH-01", "sutra": "netting-set v1"},
]


@router.get("/")
def landing(request: Request):
    return render(request, "landing.html", showcase=SHOWCASE)


@router.get("/healthz")
def healthz():
    """Liveness: the console process answers. Cheap on purpose; a restart would not fix a server that is down."""
    return {"status": "UP"}


@router.get("/readyz")
async def readyz(request: Request):
    """Readiness: the console can reach the server (route traffic here only then). 503 while it cannot."""
    try:
        await request.app.state.backend.business_date()
    except Exception as e:  # noqa: BLE001 - any failure means not ready
        return JSONResponse({"status": "DOWN", "server": "unreachable", "detail": str(getattr(e, "detail", e))[:200]}, status_code=503)
    return {"status": "UP", "server": "reachable"}
