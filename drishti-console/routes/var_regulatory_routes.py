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

"""Regulatory VaR's screens (docs/guides/VAR_GUIDE.md sections 18.10 to 18.12; the approved mock-ups backtesting A and report-signoff A):

* ``/var/backtesting?scope=BANK``: the backtest of a node (``VAR BANK BACKTEST``, ``VAR DESK RATES BACKTEST``): the chart of the 250
  days, the zone and multiplier, the bank and its desks, the exceptions with the desks that drove them;
* ``/var/statistics?scope=BANK&period=FY2026``: a node's period statistics (``VAR BANK STATS FY2026``);
* ``/var/reports`` and ``/var/reports/<report>?period=FY2026``: the report runs and their review and sign-off
  (``VAR REPORT annual-report-var FY2026``).

Each page is a shell: web/static/js/var-backtesting.js and var-reports.js fill it from the same-origin JSON below, which asks the
server (``/api/v1/var/backtesting``, ``/statistics``, ``/reports``) as the person. Behind the edition's ``VAR`` word, as the explorer."""
from __future__ import annotations

import re
from urllib.parse import quote

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse, Response

from core.backend import BackendError
from core.csrf import json_body
from routes.common import ident, render

router = APIRouter(include_in_schema=False)

#: a report's id (its file name) and a period (FY2026, FY2026Q1, 2026-01-01..2026-03-31)
REPORT = re.compile(r"^[a-z0-9][a-z0-9-]{0,63}$")
PERIOD = re.compile(r"^(?:FY\d{4}(?:Q[1-4])?|\d{4}-\d{2}-\d{2}\.\.\d{4}-\d{2}-\d{2})$", re.I)
FORMATS = {"xlsx": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "csv": "text/csv", "pdf": "application/pdf"}


def regulatory_command(words: list[str]) -> str | None:
    """``VAR <scope words> BACKTEST`` -> ``/var/backtesting?scope=...``; ``VAR <scope words> STATS [period]`` -> ``/var/statistics?...``;
    ``VAR REPORT[S] [report] [period]`` -> ``/var/reports[/<report>?period=...]``; None for any other VAR command."""
    w = [x for x in words if x.upper() != "<GO>"]
    if len(w) < 2 or w[0].upper() != "VAR":
        return None
    head = w[1].upper()
    if head in ("REPORT", "REPORTS", "RPT"):
        if len(w) == 2:
            return "/var/reports"
        rep = w[2].lower()
        if not REPORT.match(rep):
            return None
        period = w[3] if len(w) > 3 and PERIOD.match(w[3]) else ""
        return f"/var/reports/{rep}" + (f"?period={quote(period.upper() if period.upper().startswith('FY') else period)}" if period else "")
    ups = [x.upper() for x in w]
    for word, page in (("BACKTEST", "backtesting"), ("BACKTESTING", "backtesting"), ("STATS", "statistics"), ("STATISTICS", "statistics")):
        if word in ups[2:] or (word in ups[1:2] and len(w) >= 2):
            i = ups.index(word, 1)
            scope_words, rest = w[1:i], w[i + 1:]
            scope = _scope(scope_words)
            if scope is None:
                return None
            where = f"/var/{page}?scope={quote(scope)}"
            if page == "statistics" and rest and PERIOD.match(rest[0]):
                p = rest[0]
                where += "&period=" + quote(p.upper() if p.upper().startswith("FY") else p)
            return where
    return None


def _scope(words: list[str]) -> str | None:
    """``[BANK]`` -> BANK, ``[DESK, RATES]`` -> DESK:RATES, ``[NODE, MGMT~DIV-MARKETS]`` -> NODE:..., ``[]`` -> BANK."""
    if not words or (len(words) == 1 and words[0].upper() == "BANK"):
        return "BANK"
    if len(words) == 1 and ":" in words[0]:
        return words[0]
    kind = words[0].upper()
    if kind in ("LE", "DIV", "DESK", "NODE") and len(words) >= 2:
        return f"{kind}:{'-'.join(words[1:]) if kind != 'NODE' else words[1]}"
    return None


def _problem(e: BackendError) -> JSONResponse:
    return JSONResponse({"code": e.code, "detail": e.detail}, status_code=e.status)


@router.get("/var/backtesting")
async def backtesting_page(request: Request, scope: str = "BANK", asOf: str = ""):  # noqa: N803 (the link's word)
    return render(request, "terminal/var_backtesting.html", scope=scope or "BANK", as_of=asOf, screen="var",
                  command_text=f"VAR {scope.replace(':', ' ') if scope != 'BANK' else 'BANK'} BACKTEST")


@router.get("/var/statistics")
async def statistics_page(request: Request, scope: str = "BANK", period: str = "", measure: str = "var"):
    return render(request, "terminal/var_statistics.html", scope=scope or "BANK", period=period if PERIOD.match(period or "") else "",
                  measure=measure if measure in ("var", "es", "svar", "ses") else "var", screen="var",
                  command_text=f"VAR {scope.replace(':', ' ') if scope != 'BANK' else 'BANK'} STATS {period}".strip())


@router.get("/var/reports")
async def reports_page(request: Request):
    return render(request, "terminal/var_reports.html", report="", period="", screen="var", command_text="VAR REPORTS")


@router.get("/var/reports/{report}")
async def report_page(request: Request, report: str, period: str = "", version: str = ""):
    if not REPORT.match(report):
        return JSONResponse({"code": "DRS-1001", "detail": f"no report {report}"}, status_code=404)
    me = getattr(request.state, "identity", None)
    return render(request, "terminal/var_reports.html", report=report, period=period if PERIOD.match(period or "") else "",
                  version=version if version.isdigit() else "", user=(me.user if me else "") or "", screen="var",
                  command_text=f"VAR REPORT {report} {period}".strip())


@router.get("/api/var/backtesting")
async def api_backtesting(request: Request):
    q = request.query_params
    try:
        if q.get("format") == "xlsx":
            body, headers = await request.app.state.backend.var("GET", "/backtesting", ident(request), raw=True, scope=q.get("scope", ""),
                                                                format="xlsx", asOf=q.get("asOf", ""))
            return Response(body, media_type=FORMATS["xlsx"], headers={"Content-Disposition": headers.get("content-disposition", "attachment")})
        return await request.app.state.backend.var("GET", "/backtesting", ident(request), scope=q.get("scope", ""), asOf=q.get("asOf", ""))
    except BackendError as e:
        return _problem(e)


@router.get("/api/var/statistics")
async def api_statistics(request: Request):
    q = request.query_params
    try:
        return await request.app.state.backend.var("GET", "/statistics", ident(request), scope=q.get("scope", ""), period=q.get("period", ""),
                                                   measure=q.get("measure", "var"))
    except BackendError as e:
        return _problem(e)


@router.get("/api/var/reports")
async def api_reports(request: Request):
    try:
        return await request.app.state.backend.var("GET", "/reports", ident(request))
    except BackendError as e:
        return _problem(e)


def _checked(report: str, period: str) -> JSONResponse | None:
    if not REPORT.match(report or "") or not PERIOD.match(period or ""):
        return JSONResponse({"code": "DRS-5001", "detail": "a report id and a period (FY2026, FY2026Q1, 2026-01-01..2026-03-31)"}, status_code=400)
    return None


@router.post("/api/var/reports/{report}/runs")
async def api_generate(request: Request, report: str):
    period = request.query_params.get("period", "")
    if (bad := _checked(report, period or "FY2000")) is not None:
        return bad
    try:
        return await request.app.state.backend.var("POST", f"/reports/{report}/runs", ident(request), period=period)
    except BackendError as e:
        return _problem(e)


@router.get("/api/var/reports/{report}/runs/{period}")
async def api_run(request: Request, report: str, period: str):
    if (bad := _checked(report, period)) is not None:
        return bad
    try:
        return await request.app.state.backend.var("GET", f"/reports/{report}/runs/{quote(period)}", ident(request),
                                                    version=request.query_params.get("version", ""))
    except BackendError as e:
        return _problem(e)


@router.post("/api/var/reports/{report}/runs/{period}/signoff")
async def api_signoff(request: Request, report: str, period: str):
    if (bad := _checked(report, period)) is not None:
        return bad
    body = await json_body(request)
    keep = {k: body.get(k) for k in ("version", "action", "step", "comment", "narrative") if body.get(k) is not None}
    for k in ("comment", "narrative"):
        if isinstance(keep.get(k), str):
            keep[k] = keep[k][:4000]
    try:
        return await request.app.state.backend.var("POST", f"/reports/{report}/runs/{quote(period)}/signoff", ident(request), body=keep)
    except BackendError as e:
        return _problem(e)


@router.get("/api/var/reports/{report}/runs/{period}/file")
async def api_file(request: Request, report: str, period: str):
    if (bad := _checked(report, period)) is not None:
        return bad
    q = request.query_params
    fmt = q.get("format", "xlsx")
    if fmt not in FORMATS:
        return JSONResponse({"code": "DRS-5001", "detail": "a file is xlsx, csv or pdf"}, status_code=400)
    try:
        body, headers = await request.app.state.backend.var("GET", f"/reports/{report}/runs/{quote(period)}/file", ident(request), raw=True,
                                                            format=fmt, version=q.get("version", ""), draft=q.get("draft", ""))
    except BackendError as e:
        return _problem(e)
    return Response(body, media_type=FORMATS[fmt], headers={"Content-Disposition": headers.get("content-disposition", "attachment")})
