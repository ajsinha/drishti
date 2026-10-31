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

"""The scenario matrix (2.9.7, docs/guides/VAR_GUIDE.md section 3.9; the approved mock-up scenario-matrix-B): ``SMAT <id> [N] <GO>``
opens ``/smat?id=<id>&days=N``, one trade, position or VaR node's scenario P&L (rows, by historical day) across its last N business
dates (columns, 20 when N is not given; at most ``drishti.var.smat.max-days`` on the server, 60). The page is a shell:
web/static/js/scenario-matrix.js fills it from ``/api/smat/matrix``, which asks the server's VaR engine (``/api/v1/var/matrix``) as the
person, so their nodes, roles and masks apply; the rows travel as Arrow when the page has Arrow JS (509 rows by up to 60 dates), JSON
otherwise. ``/api/smat/matrix.csv`` and ``/api/smat/matrix.arrow`` are the page's exports.

The word is an edition's (``commands.words.SMAT``: on in Drishti Risk and Drishti Trading, off in Drishti Market and Drishti World):
off, the command and every path here answer ``DRS-4023`` (core/edition_gate.py), and its links are not offered (``smat_on()``).

Commands: ``SMAT T-1001``, ``SMAT T-1001 30``, ``SMAT POS-BOOK-1~SEC-A``, ``SMAT DESK:RATES 20``, the spaced forms ``SMAT DESK RATES``,
``SMAT BOOK BOOK-RATES-1 30``, ``SMAT POS <position>``, ``SMAT TRD <trade>``, ``SMAT BANK``, and a P&L vector's or scenario PV's id
(``SMAT PNL-T-1001-HS-509``), each optionally ending ``IN <currency>``."""
from __future__ import annotations

import csv
import io
import re
from urllib.parse import quote, urlencode

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse, RedirectResponse, Response

from core import arrow_answer
from core.backend import BackendError
from routes.common import ident, render

router = APIRouter(include_in_schema=False)

WORD = "SMAT"
#: the scope words after SMAT that name a node or an entity, and the scope word each means (as after VAR)
SCOPE_WORDS = {"BANK": "BANK", "LE": "LE", "DIV": "DIV", "DESK": "DESK", "BOOK": "BOOK", "NODE": "NODE", "POS": "POS", "TRD": "TRD",
               "TRADE": "TRD", "PORT": "PORT", "PORTFOLIO": "PORT"}
#: N when the command does not give it (the server's default too)
DEFAULT_DAYS = 20
CCY = re.compile(r"^[A-Za-z]{3}$")
#: the parameters the page passes on to the server (the pickers to come add confidence, es-confidence, window and horizon)
PASSED = ("id", "days", "ccy", "by", "scenario", "confidence", "es-confidence", "window", "horizon", "asOf")


def smat_command(words: list[str]) -> str | None:
    """Where ``SMAT <words>`` goes (``/smat?id=...&days=N[&ccy=EUR]``), or None when the words are not a scenario-matrix command."""
    if words and words[-1].upper() == "<GO>":
        words = words[:-1]
    if not words or words[0].upper() != WORD:
        return None
    rest = list(words[1:])
    ccy = ""
    if len(rest) >= 3 and rest[-2].upper() == "IN" and CCY.match(rest[-1]):
        ccy, rest = rest[-1].upper(), rest[:-2]
    days = ""
    if len(rest) >= 2 and rest[-1].isdigit():
        days, rest = rest[-1], rest[:-1]
    if not rest:
        return "/smat"
    head = rest[0].upper()
    if len(rest) == 1:
        target = "BANK" if head == "BANK" else rest[0]
    elif head in ("POS", "TRD", "TRADE"):
        target = " ".join(rest[1:])
    elif head in SCOPE_WORDS and ":" not in rest[0]:
        target = f"{SCOPE_WORDS[head]}:" + "-".join(rest[1:])
    else:
        target = " ".join(rest)
    q = {"id": target}
    if days:
        q["days"] = days
    if ccy:
        q["ccy"] = ccy
    return "/smat?" + urlencode(q)


def redirect_for(typed: str) -> RedirectResponse | None:
    """The terminal's ``/go`` asks here (after the edition's word check): a scenario-matrix command, or None."""
    where = smat_command(typed.split())
    return RedirectResponse(where, status_code=303) if where else None


def link(target: str, days: int | None = None) -> str:
    """The page's address for a trade, position or scope (the Scenario matrix links of the entity pages)."""
    return "/smat?id=" + quote(target, safe=":~") + (f"&days={days}" if days else "")


@router.get("/smat")
async def page(request: Request, id: str = "", days: int = 0, ccy: str = ""):
    """The matrix's shell; its table, chart and details load in place (web/static/js/scenario-matrix.js)."""
    n = days if days > 0 else DEFAULT_DAYS
    return render(request, "terminal/smat.html", target=id.strip(), days=n, ccy=ccy.upper() if CCY.match(ccy or "") else "", screen="smat",
                  command_text=f"{WORD} {id.strip()} {n}".strip() if id.strip() else WORD)


def _params(request: Request) -> dict:
    q = request.query_params
    return {k: q.get(k) for k in PASSED if q.get(k)}


def _problem(e: BackendError) -> JSONResponse:
    return JSONResponse({"code": e.code, "detail": e.detail}, status_code=e.status if 400 <= (e.status or 0) < 600 else 502)


@router.get("/api/smat/matrix")
async def matrix(request: Request):
    """The matrix as the person sees it: Arrow when the browser asks for it (``Accept: application/vnd.apache.arrow.stream``), JSON
    otherwise."""
    p = _params(request)
    if not p.get("id"):
        return JSONResponse({"code": "DRS-5001", "detail": "give a trade, a position or a VaR scope: id=T-1001, id=DESK:RATES"}, status_code=400)
    try:
        if arrow_answer.wanted(request.headers.get("accept")):
            content, headers = await request.app.state.backend.var("GET", "/matrix", ident(request), raw=True, accept=arrow_answer.MEDIA, **p)
            return arrow_answer.pass_on(content, headers, "records", "rows")
        return JSONResponse(await request.app.state.backend.var("GET", "/matrix", ident(request), **p), headers={"Cache-Control": "no-store"})
    except BackendError as e:
        return _problem(e)


def _filename(answer: dict, ext: str) -> str:
    stem = re.sub(r"[^A-Za-z0-9_.~-]+", "-", str(answer.get("scope") or answer.get("id") or "matrix")).strip("-")
    dates = answer.get("dates") or []
    last = dates[-1].get("date", "") if dates else ""
    return f"scenario-matrix-{stem}-{last}.{ext}"


def to_csv(answer: dict) -> str:
    """The matrix as CSV: a row per scenario (or trade), a column per date, then the summary rows (VaR, ES, base PV) and each date's
    source and gap (a gap's cells are empty and its reason is said, never zeros)."""
    dates = [d.get("date") for d in answer.get("dates") or []]
    by_trade = answer.get("by") == "trade"
    first = "trade" if by_trade else "scenario"
    out = io.StringIO()
    w = csv.writer(out, lineterminator="\n")
    w.writerow([first] + dates)
    for r in answer.get("rows") or []:
        w.writerow([r.get(first)] + ["" if r.get(d) is None else r.get(d) for d in dates])
    labels = answer.get("labels") or {}
    meta = answer.get("dates") or []
    if by_trade:
        w.writerow(["total"] + ["" if m.get("total") is None else m.get("total") for m in meta])
    else:
        for key, label in (("var", labels.get("var", "VaR")), ("es", labels.get("es", "ES")), ("basePv", labels.get("basePv", "Base PV"))):
            w.writerow([label] + ["" if m.get(key) is None else m.get(key) for m in meta])
        w.writerow(["VaR rank (k)"] + ["" if m.get("k") is None else m.get("k") for m in meta])
    w.writerow(["source"] + [m.get("source") or "" for m in meta])
    w.writerow(["gap"] + [m.get("gap") or "" for m in meta])
    return out.getvalue()


@router.get("/api/smat/matrix.csv")
async def matrix_csv(request: Request):
    p = _params(request)
    try:
        answer = await request.app.state.backend.var("GET", "/matrix", ident(request), **p)
    except BackendError as e:
        return _problem(e)
    return Response(to_csv(answer), media_type="text/csv; charset=utf-8",
                    headers={"Content-Disposition": f'attachment; filename="{_filename(answer, "csv")}"', "Cache-Control": "no-store"})


@router.get("/api/smat/matrix.arrow")
async def matrix_arrow(request: Request):
    """The matrix as an Arrow IPC stream file (the rows the record batch, the rest in its metadata, as the page reads it)."""
    p = _params(request)
    try:
        answer = await request.app.state.backend.var("GET", "/matrix", ident(request), **p)
    except BackendError as e:
        return _problem(e)
    content = arrow_answer.encode(answer, "records", "rows")
    if content is None:
        return JSONResponse({"code": "DRS-5001", "detail": "this matrix cannot be written as Arrow here (pyarrow is missing)"}, status_code=501)
    return Response(content, media_type=arrow_answer.MEDIA,
                    headers={"Content-Disposition": f'attachment; filename="{_filename(answer, "arrow")}"', "Cache-Control": "no-store"})


#: the entity pages that offer the link, and how the page's record names the thing the matrix is of
LINKED_KINDS = ("trade", "position", "pnl-vector", "scenario-pv")


def entity_target(kind: str, id_: str) -> str | None:
    """The id the matrix of an entity page reads (a P&L vector's or scenario PV's own id: the server finds its trade or position), or
    None for a kind that offers no matrix."""
    return id_ if kind in LINKED_KINDS and id_ else None
