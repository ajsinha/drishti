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

"""Rupaka phase 1 (docs/architecture/RUPAKA.md section 5): the BI pages (home, catalogue, workspaces, an item) and the same-origin JSON the page script uses.

The pages are a shell: they carry no data. The script (``static/js/bi.js``) asks ``/bi/api/...``, which passes each call on to the server's ``/api/v1/bi/...``
with the signed-in user's identity; the server decides what that person may see and do and answers with its problem code when not. The console decides
nothing about access except what to show (the BI menu needs the ``bi`` power)."""
from __future__ import annotations

import json
import re
from urllib.parse import quote

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse, RedirectResponse, Response

from core.backend import BackendError
from core.csrf import json_body
from routes.common import ident, render

router = APIRouter(include_in_schema=False)

# the server paths the page script may reach: nothing else is passed on
_ALLOWED = ("me", "home", "catalogue", "settings", "workspaces", "items", "datasets", "model", "engine", "reports", "bundle", "boards", "python", "ai", "imports", "mq", "models", "pack-datasets", "expr", "sql", "layer-datasets", "lineage", "themes", "runs")
_NO_BODY = {"GET", "DELETE"}
# A query (a visual's, a model query, SQL) may run on the server up to drishti.bi.query.timeout (30 s): the console waits that long and a little
# more, not its usual 5 s, so a query a loaded server answers in 6 s is drawn, not refused with DRS-1004 while the server still works on it.
QUERY_WAIT_SECONDS = 35.0
STATEMENT_WAIT_SECONDS = 70.0


def _query_wait(head: str, path: str) -> dict:
    """The longer wait for a query, as the keyword the backend takes (nothing for any other call)."""
    if path.endswith("/statement/preview"):                     # a statement asks each of its bands, then lays the pages out (2.6.0)
        return {"wait": STATEMENT_WAIT_SECONDS}
    return {"wait": QUERY_WAIT_SECONDS} if path.endswith("/query") or head in ("mq", "sql") else {}


# what a refusal carries for the page: the model query a 410 names, the problems with their fixes; and for BI runs (BI_RUNS.md section 6) the estimate and the
# token of a run that asks to be confirmed (409 DRS-9082), the estimate of a selection that needs a background run (409 DRS-9089), the run itself
_KEPT = ("mq", "problems", "path", "fix", "at", "estimate", "confirmToken", "run", "runId", "tier", "reuse")


def _problem(e: BackendError) -> JSONResponse:
    extra = {k: e.extra[k] for k in _KEPT if isinstance(e.extra, dict) and k in e.extra}
    headers = {"Retry-After": str(e.retry_after)} if getattr(e, "retry_after", None) else None      # DRS-9081: a full run queue says when to try again
    return JSONResponse({"code": e.code, "detail": e.detail, **extra}, status_code=e.status, headers=headers)


# BI runs (BI_RUNS.md section 6.1): the page asks with ``Prefer: respond-async, wait=<seconds>``; the console passes it on and waits that long and 5 s more
# (never the 35 s of a synchronous query), so a 202 with the run reaches the page as soon as the server stops waiting inline.
MAX_INLINE_WAIT = 10.0
_WAIT = re.compile(r"wait\s*=\s*([0-9]+(?:\.[0-9]+)?)", re.I)


def async_prefer(request: Request) -> tuple[str, float] | None:
    """``(the Prefer header to pass on, the console's wait)`` when the page asked for an asynchronous answer, else None."""
    prefer = request.headers.get("prefer") or ""
    if "respond-async" not in prefer.lower():
        return None
    m = _WAIT.search(prefer)
    wait = min(MAX_INLINE_WAIT, float(m.group(1))) if m else 2.0
    return f"respond-async, wait={wait:g}", wait + 5.0


def _page(request: Request, template: str, page: str, **context):
    """A BI page, or the refusal that explains what is missing."""
    me = getattr(request.state, "bi_me", None) or {}
    if not me.get("enabled"):
        return render(request, "bi/denied.html", status_code=403, screen="bi", bi_page=page)
    return render(request, template, screen="bi", bi_page=page, **context)


@router.get("/bi")
async def home(request: Request):
    return _page(request, "bi/home.html", "home")


@router.get("/bi/catalogue")
async def catalogue(request: Request):
    return _page(request, "bi/catalogue.html", "catalogue")


@router.get("/bi/workspaces")
async def workspaces(request: Request):
    return _page(request, "bi/workspaces.html", "workspaces")


@router.get("/bi/w/{workspace}")
async def workspace(request: Request, workspace: str):
    return _page(request, "bi/workspace.html", "workspace", workspace=workspace)


@router.get("/bi/i/{item:path}")
async def item(request: Request, item: str):
    return _page(request, "bi/item.html", "item", item=item)


@router.get("/bi/environments")
async def environments(request: Request):
    """Environments: export a workspace as a bundle, import one (dry run first). Drawn by ``bi-env.js`` (phase 4)."""
    return _page(request, "bi/environments.html", "environments")


@router.get("/bi/datasets")
async def datasets(request: Request):
    return _page(request, "bi/datasets.html", "datasets")


@router.get("/bi/d/{item:path}")
async def dataset(request: Request, item: str):
    """One 2.1 dataset (a live sample until wave W3): its model (tables, relationships, measures, row rules) and a query preview, drawn by ``bi-dataset.js``.
    A ``rachana: 2`` dataset has its own page since 2.2 (grain, keys, columns with meanings, time mode, security): an old link is sent there."""
    import re
    from fastapi.responses import RedirectResponse
    try:
        got = await request.app.state.backend.bi("GET", "/layer-datasets/" + quote(item, safe=":"), ident(request))
        text = str((got or {}).get("yaml") or "")
        name = re.search(r"^dataset:\s*([A-Za-z0-9_.-]+)", text, flags=re.M)
        if re.search(r"^rachana:\s*2\b", text, flags=re.M) and name:
            return RedirectResponse("/bi/ds/" + quote(str(got.get("id") or item), safe=":"), status_code=307)
    except BackendError:
        pass                                                        # not a dataset of the model layer (or not readable): the 2.1 page says what it can
    return _page(request, "bi/dataset.html", "datasets", item=item)


@router.get("/bi/r/{item}")
@router.get("/bi/r/{item}/{page}")
async def report(request: Request, item: str, page: str = ""):
    """A report, drawn by ``bi-report.js``: its pages are tabs, its visuals ask the server for their data one query at a time (phase 3). Every report page has
    the strict policy: no WebAssembly (wave B, BI_DESIGN.md P3: the servers compute, the browser draws)."""
    return _page(request, "bi/report.html", "report", item=item, report_page=page)


def _board_as_report(item: str) -> str:
    """Since 2.4.0 a board is a report with board pages (R7): a shipped board file is the report of its name, a stored board keeps its id."""
    return "file:report:" + item[len("file:board:"):] if item.startswith("file:board:") else item


@router.get("/bi/b/{item}")
async def board(request: Request, item: str):
    """A 2.3 board's address: the report viewer draws its board pages (tiles, kiosk, rotation), so this sends people there, ``?kiosk=1`` and the page kept."""
    query = ("?" + request.url.query) if request.url.query else ""
    return RedirectResponse("/bi/r/" + quote(_board_as_report(item), safe=":") + query, status_code=307)


@router.get("/bi/designer")
async def designer(request: Request):
    """The designer's start: your report drafts, and a new report on a dataset."""
    return _page(request, "bi/designer.html", "designer")


@router.get("/bi/design/board/{draft:path}")
async def design_board(request: Request, draft: str):
    """A 2.3 board designer's address: a board is designed as a report with board pages (2.4.0), in the report designer."""
    return RedirectResponse("/bi/design/" + quote(_board_as_report(draft), safe=":"), status_code=307)


@router.get("/bi/design/theme/{draft:path}")
async def design_theme(request: Request, draft: str):
    """The theme designer in BI Studio (2.4.0): a theme item's colours, type and format defaults per kind of visual, with a preview; ``new`` makes one."""
    return _page(request, "bi/design_theme.html", "theme-designer", item=draft)


@router.get("/bi/design/{draft}")
async def design(request: Request, draft: str):
    """The report designer on one draft (a report item you may edit): the canvas, Fields, Visualizations, Filters, pages, YAML, preview and Check."""
    return _page(request, "bi/design.html", "designer", item=draft)


@router.get("/bi/m/{item:path}")
async def model_view(request: Request, item: str):
    """The Model view of a dataset item: tables as boxes, relationships as lines, the YAML beside them (two views of one draft)."""
    return _page(request, "bi/model.html", "datasets", item=item)


@router.get("/admin/bi/usage")
async def usage_page(request: Request, limit: int = 2000):
    """Admin → BI usage (wave B): what BI is asked, by whom and how it is answered (core/bi_usage.py), from the audit log, the engine's counters and the console's
    Python runner. BI administrators; the audit log itself is the system administrator's, so a bi-admin who is not one sees the engine and the runner only."""
    from core.bi_usage import summarise

    me = getattr(request.state, "bi_me", None) or {}
    if "bi-admin" not in (me.get("powers") or []):
        return render(request, "admin/forbidden.html", status_code=403)
    usage, engine, why = None, None, ""
    if ident(request).is_admin:
        try:
            events = await request.app.state.backend.admin("GET", "/audit", ident(request), limit=max(100, min(limit, 10000)))
            usage = summarise(events if isinstance(events, list) else (events or {}).get("events") or [])
        except BackendError as e:
            why = e.detail
    else:
        why = "the audit log is shown to system administrators"
    try:
        engine = await request.app.state.backend.bi("GET", "/engine", ident(request))
    except BackendError:
        engine = None
    return render(request, "bi/usage.html", screen="admin", usage=usage, engine=engine, why=why, runner=request.app.state.pyrunner.status())


@router.get("/admin/bi")
async def settings_page(request: Request):
    me = getattr(request.state, "bi_me", None) or {}
    if "bi-admin" not in (me.get("powers") or []):
        return render(request, "admin/forbidden.html", status_code=403)
    files = None
    if ident(request).is_admin:                    # the shipped files and their safe uninstall are the system administrator's (Admin → Artifacts)
        try:
            listing = await request.app.state.backend.admin("GET", "/artifacts", ident(request))
            files = [a for a in listing.get("artifacts", []) if a.get("kind") == "bi"]
        except BackendError:
            files = None
    return render(request, "bi/settings.html", screen="admin", bi_files=files)


@router.get("/bi/api/runs/events")
async def run_events(request: Request):
    """The person's runs as they change (BI_RUNS.md section 3.5): the server's SSE ``GET /api/v1/bi/runs/events`` relayed, ``hello`` then ``run`` events and
    the ``status`` heartbeat. The Runs tab, a run card and the rail's count listen to it; one stream per browser tab."""
    from fastapi.responses import StreamingResponse
    backend, me = request.app.state.backend, ident(request)

    async def events():
        try:
            async for event, data in backend.sse("/bi/runs/events", me):
                if await request.is_disconnected():
                    break
                yield f"event: {event}\ndata: {data}\n\n"
        except BackendError as e:
            yield f"event: gone\ndata: {json.dumps({'code': e.code, 'detail': e.detail})}\n\n"

    return StreamingResponse(events(), media_type="text/event-stream", headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})


@router.post("/bi/api/runs/{run_id}/export")
async def run_export(request: Request, run_id: str):
    """A run's result as a file (CSV, Excel, Parquet, PDF), made by the server from the stored result: the bytes and their name passed through unchanged."""
    body = await json_body(request)
    try:
        content, headers = await request.app.state.backend.bi_file("POST", f"/runs/{quote(run_id, safe='')}/export", ident(request), body, wait=QUERY_WAIT_SECONDS)
    except BackendError as e:
        return _problem(e)
    kept = {k: v for k, v in headers.items() if k.lower() == "content-disposition"}
    kept["Cache-Control"] = "no-store"
    return Response(content, media_type=headers.get("content-type", "application/octet-stream"), headers=kept)


@router.get("/bi/run/{run_id}")
async def open_run(request: Request, run_id: str):
    """Where a run opens (the inbox's notice, Open in the Runs tab): a report's run on its report with ``?run=<id>`` (the visual drawn from the stored result), any
    other in the Runs tab with its drawer open. A run the person may not see opens the Runs tab, which says so."""
    me = getattr(request.state, "bi_me", None) or {}
    if not me.get("enabled"):
        return render(request, "bi/denied.html", status_code=403, screen="bi", bi_page="workspace")
    fallback = "/bi/w/me?tab=runs&run=" + quote(run_id, safe="")
    try:
        run = await request.app.state.backend.bi("GET", "/runs/" + quote(run_id, safe=""), ident(request))
    except BackendError:
        return RedirectResponse(fallback, status_code=303)
    href = str(((run or {}).get("source") or {}).get("href") or "")
    # a report's or a board's page with ?run=, never a full URL; the server's href of an MQ or SQL run (/bi/runs/<id>, the result grid of BI_RUNS.md 7.3)
    # is not a console page yet, so such a run opens in the Runs tab with its drawer (BIR-02)
    ok = href.startswith(("/bi/r/", "/bi/b/")) and "//" not in href
    return RedirectResponse(href if ok else fallback, status_code=303)


@router.get("/admin/bi/runs")
async def runs_admin(request: Request):
    """Admin → BI → Runs (BI_RUNS.md section 17.2): the gate now, what runs and waits, the result store, how honest the estimates are, how often the inline wait
    was enough. Drawn by ``bi-runs-admin.js`` from ``GET /api/v1/bi/admin/runs``; bi-admin only. State, sizes and nodes, never a value."""
    me = getattr(request.state, "bi_me", None) or {}
    if "bi-admin" not in (me.get("powers") or []):
        return render(request, "admin/forbidden.html", status_code=403)
    return render(request, "bi/runs_admin.html", screen="admin")


@router.get("/bi/api/admin/runs")
@router.post("/bi/api/admin/runs/{run_id}/cancel")
async def runs_admin_api(request: Request, run_id: str = ""):
    """The two administrator calls of Admin → BI → Runs, passed on as the signed-in user (the server checks bi-admin)."""
    params = {k: v for k, v in request.query_params.items() if k in ("node", "state", "user")}
    path = "/admin/runs" + (f"/{quote(run_id, safe='')}/cancel" if run_id else "")
    try:
        answer = await request.app.state.backend.bi(request.method, path, ident(request), {} if run_id else None, **params)
    except BackendError as e:
        return _problem(e)
    return JSONResponse(answer, status_code=202 if run_id else 200, headers={"Cache-Control": "no-store"})


@router.post("/bi/api/admin/runs/suggestions/{suggestion_id}/{verb}")
async def runs_suggestion(request: Request, suggestion_id: str, verb: str):
    """Accept or dismiss a rollup suggestion of Admin → BI → Runs (BI_RUNS.md section 8.3a), passed on as the signed-in user (the server checks bi-admin):
    accepting adds the drafted rollup to the model's draft, for review."""
    if verb not in ("accept", "dismiss"):
        return JSONResponse({"code": "DRS-9001", "detail": "no such BI path"}, status_code=404)
    try:
        answer = await request.app.state.backend.bi("POST", f"/admin/runs/suggestions/{quote(suggestion_id, safe='')}/{verb}", ident(request), {})
    except BackendError as e:
        return _problem(e)
    return JSONResponse(answer, headers={"Cache-Control": "no-store"})


@router.get("/bi/api/catalogue-tabs")
async def catalogue_tabs(request: Request):
    """The catalogue for one of its tabs (Reports · Boards · Models · Datasets · Python · All) with every tab's count (core/bi_catalogue_tabs.py)."""
    from core.bi_catalogue_tabs import tabbed
    try:
        answer = await tabbed(request.app.state.backend, ident(request), dict(request.query_params))
    except BackendError as e:
        return _problem(e)
    return JSONResponse(answer, headers={"Cache-Control": "no-store"})


@router.api_route("/bi/api/{path:path}", methods=["GET", "POST", "PUT", "DELETE"])
async def proxy(request: Request, path: str):
    """One call of the page script, passed on to the server under the caller's identity (the server enforces everything)."""
    head = path.split("/", 1)[0]
    if head not in _ALLOWED or ".." in path:
        return JSONResponse({"code": "DRS-9001", "detail": "no such BI path"}, status_code=404)
    body = None if request.method in _NO_BODY else await json_body(request)
    if request.method == "POST" and head == "items" and path.rsplit("/", 1)[-1] in ("publish", "approve"):
        from routes.python_routes import publish_gate
        refused = await publish_gate(request, path, body)          # a Python item whose own tests fail is not published (a bi-admin may force it)
        if refused is not None:
            return refused
    params = {k: v for k, v in request.query_params.items()}
    if request.method == "POST" and head == "reports" and path.endswith("/query") and params.get("format") == "arrow":
        try:                                                  # a Perspective visual's rows: the server's Arrow bytes, passed through unchanged
            content, headers = await request.app.state.backend.bi_arrow("/" + path, ident(request), body, wait=QUERY_WAIT_SECONDS, **params)
        except BackendError as e:
            return _problem(e)
        kept = {k: v for k, v in headers.items() if k.lower() in ("x-bi-rows", "x-bi-masked", "x-bi-live")}
        kept["Cache-Control"] = "no-store"
        return Response(content, media_type="application/vnd.apache.arrow.stream", headers=kept)
    asked = async_prefer(request) if request.method == "POST" else None
    if asked is not None:                                    # a question asked as a run: 200 the answer in time, 202 the run (BI_RUNS.md section 6)
        try:
            status, answer = await request.app.state.backend.bi_async("POST", "/" + path, ident(request), body, asked[0], wait=asked[1], **params)
        except BackendError as e:
            return _problem(e)
        return Response(status_code=204) if answer is None else JSONResponse(answer, status_code=status, headers={"Cache-Control": "no-store"})
    try:
        answer = await request.app.state.backend.bi(request.method, "/" + path, ident(request), body, **_query_wait(head, path), **params)
    except BackendError as e:
        return _problem(e)
    if request.method != "GET":
        request.app.state.bi_me.forget(ident(request).user)         # a change may alter Recent or the powers shown in the menu
    return Response(status_code=204) if answer is None else JSONResponse(answer, headers={"Cache-Control": "no-store"})


@router.put("/bi/api-roles/{name}")
async def role_powers(request: Request, name: str):
    """Admin, BI settings: set the BI powers of a role the administrator defines (the server's role-definition API; administrators only)."""
    body = await json_body(request)
    powers = body.get("bi")
    if not isinstance(powers, list):
        return JSONResponse({"code": "DRS-9003", "detail": "bi is a list of powers"}, status_code=422)
    backend, me = request.app.state.backend, ident(request)
    try:
        role = await backend.admin("GET", f"/role-definitions/{name}", me)
        keep = {k: role.get(k) for k in ("description", "kinds", "raw", "author", "approve", "admin", "calc", "layout", "collaborate", "compliance")}
        saved = await backend.admin("PUT", f"/role-definitions/{name}", me, {**keep, "bi": [str(p) for p in powers]})
    except BackendError as e:
        return _problem(e)
    request.app.state.bi_me.forget_all()                # every holder of the role, on every worker, at once (QA3B-06)
    return JSONResponse({"name": saved["name"], "bi": sorted(saved.get("bi") or [])})
