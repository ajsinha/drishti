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

"""Functions (``/func``, ``FUNC <GO>``; docs/admin/COMMANDS.md): the command directory, under Views, for everyone. A user sees every
command they can use (the server's ``/api/v1/functions``: only commands that are on, of their packs and roles), grouped by pack, with what
each does and its example; the command opens with that example, the pack opens its search form (``FORM <pack>``). An administrator sees the
same page with everything Admin → Commands had (the server's ``/api/v1/admin/commands``): commands that are off (struck through, never a
link), the Everyone switch per command and per pack, Review changes with a reason, the history and each server's version. The switches'
JSON stays in routes/admin_commands_routes.py; the server alone decides who may change them."""
from __future__ import annotations

import copy
from urllib.parse import quote

from fastapi import APIRouter, Request

from core.backend import BackendError
from routes.common import ident, render

router = APIRouter(include_in_schema=False)

FUNC_WORD = "FUNC"        # a reserved command word (routes/terminal_routes.py): FUNC opens this page, FUNC <words> opens it filtered


def _words(q: str) -> list[str]:
    return [w for w in (q or "").lower().split() if w]


def _decorate(data: dict, q: str) -> dict:
    """Each command's link (its example on the command line, else its kind's search form; none when it is off), the text the filter reads
    (code, label, description, kind, the pack's name and title) and whether ``q`` hides it: every word must appear. A pack whose commands
    are all hidden is hidden."""
    words = _words(q)
    shown = 0
    for p in data.get("packs") or []:
        p["form"] = "/form/" + quote(p["name"])
        p["shown"] = 0
        for m in p.get("commands") or []:
            m.setdefault("on", True)
            example = (m.get("example") or "").strip()
            m["href"] = None if not (m["on"] and p.get("on", True)) else (f"/go?q={quote(example)}" if example else f"/form/{quote(p['name'])}?kind={quote(m['kind'])}")
            m["go"] = example or f"FORM {p['name']} {m['kind']}"
            m["text"] = " ".join(str(x or "") for x in (m["code"], m.get("label"), m.get("description"), m.get("kind"), p["name"], p.get("title"))).lower()
            m["hidden"] = any(w not in m["text"] for w in words)
            if not m["hidden"]:
                p["shown"] += 1
        shown += p["shown"]
    data["shown"] = shown
    return data


@router.get("/func")
async def functions(request: Request, q: str = ""):
    me = ident(request)
    backend = request.app.state.backend
    admin = bool(me and me.is_admin)
    try:
        data = await (backend.admin("GET", "/commands", me) if admin else backend.functions(me))
    except BackendError as e:
        return render(request, "terminal/missing.html", status_code=e.page_status, kind="functions", id="", error=e, embed=False)
    data = _decorate(copy.deepcopy(data), q.strip())
    if not admin:
        data["total"] = sum(len(p.get("commands") or []) for p in data.get("packs") or [])
    return render(request, "terminal/functions.html", c=data, admin=admin, q=q.strip(), command_text=(f"{FUNC_WORD} {q.strip()}".strip()))
