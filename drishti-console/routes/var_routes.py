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

"""The VaR explorer (docs/guides/VAR_GUIDE.md section 3): ``VAR DESK RATES <GO>`` opens
``/var?scope=DESK:RATES``. The page is a shell; web/static/js/var-explorer.js fills its panels from the same-origin JSON below, which
asks the server's VaR engine (/api/v1/var) with the user's own identity, so entitlements by node and masks apply as on every screen.

Command words after VAR (``VAR BANK``, ``VAR LE LE-NY``, ``VAR DIV MARKETS``, ``VAR DESK RATES``, ``VAR BOOK BOOK-RATES-1``,
``VAR POS <position>``, ``VAR TRD <trade>``, ``VAR CPTY <counterparty>``, ``VAR PORT <portfolio>``, ``VAR SCOPE <basket or portfolio>``,
``VAR CAPTURE <id>``) are read here, each optionally followed by ``IN <currency>`` (``VAR DESK DESK-RATES IN EUR``: the page in that
reporting currency, docs/guides/VAR_GUIDE.md section 15.11; the page's link carries it as ``ccy=EUR``) and by the lens (2.9.7, section
18.6): a horizon ``10D`` (or ``1D``) with a method ``SUM`` or ``SQRT`` (none: the setting's), a VaR confidence ``95``, ``97.5``, ``99``,
``99.5`` and an ES confidence ``ES 97.5``, in any order (``VAR BANK 10D SUM 97.5``; the link carries ``h=10d&m=sum&q=0.975&esq=0.975``). The stored ``var`` and ``var-node`` kinds were retired in 2.8.0 (docs/guides/VAR_GUIDE.md section 21):
an old id (``VAR VAR-COMM``, ``/v/var/VAR-COMM``, ``/v/var-node/VN-MGMT~DESK-RATES``) opens the explorer at the matching scope."""
from __future__ import annotations

import re
from urllib.parse import quote

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse, RedirectResponse, Response

from core import arrow_answer
from core.backend import BackendError
from core.csrf import json_body
from routes.common import ident, render

router = APIRouter(include_in_schema=False)

#: the words after VAR that open the explorer, and the scope word each means
VAR_WORDS = {"BANK": "BANK", "LE": "LE", "DIV": "DIV", "DESK": "DESK", "BOOK": "BOOK", "NODE": "NODE", "POS": "POS", "TRD": "TRD",
             "TRADE": "TRD", "CPTY": "CPTY", "COUNTERPARTY": "CPTY", "PORT": "PORT", "PORTFOLIO": "PORT", "BASKET": "BASKET",
             "WHERE": "WHERE", "SCOPE": "SCOPE", "CAPTURE": "CAPTURE"}
#: the retired stored kinds whose ids and links open the explorer
RETIRED_KINDS = ("var", "var-node")


def retired_scope(old_id: str) -> str | None:
    """The scope an id of the retired stored VaR opens: ``VAR-<x>`` the desk ``DESK:<x>`` (``VAR-COMM``: ``DESK:COMM``, the node
    ``MGMT~DESK-COMM``), ``VN-<node>`` that node; None for anything else."""
    i = (old_id or "").strip()
    if i.upper().startswith("VAR-") and len(i) > 4:
        return "DESK:" + i[4:].upper()
    if i.upper().startswith("VN-") and len(i) > 3:
        return "NODE:" + i[3:]
    return None


def retired_link(kind: str, old_id: str) -> str | None:
    """Where ``/v/<kind>/<id>`` of a retired kind goes: the explorer at the id's scope (the bank when it names none), with a notice."""
    if kind not in RETIRED_KINDS:
        return None
    scope = retired_scope(old_id) or "BANK"
    return f"/var?scope={quote(scope)}&from={quote(old_id)}"


#: a reporting currency's ISO code, as ``IN <currency>`` at the end of a VAR command names it
CCY = re.compile(r"^[A-Za-z]{3}$")


def split_currency(words: list[str]) -> tuple[list[str], str]:
    """``[VAR, DESK, DESK-RATES, IN, EUR]`` -> (``[VAR, DESK, DESK-RATES]``, ``EUR``): a trailing ``IN <currency>`` taken off the words;
    none -> (the words, "")."""
    if len(words) >= 3 and words[-2].upper() == "IN" and CCY.match(words[-1]):
        return words[:-2], words[-1].upper()
    return words, ""


#: a confidence as a command names it: 95, 97.5, 99, 99.5 (a percentage), 0.99 (a fraction), with or without %
CONF = re.compile(r"^(?:(?:[5-9]\d(?:\.\d{1,3})?)|(?:0\.\d{1,4}))%?$")
#: the words of a scope that need an argument after them (VAR TRD 99 is the trade 99, never a confidence)
_NEEDS_ARG = {"LE", "DIV", "DESK", "BOOK", "NODE", "POS", "TRD", "CPTY", "PORT", "BASKET", "WHERE", "SCOPE", "CAPTURE"}


def _fraction(token: str) -> str:
    """``97.5`` -> ``0.975``, ``0.99`` -> ``0.99``."""
    v = float(token.rstrip("%"))
    return f"{v / 100 if v > 1 else v:g}"


def split_lens(words: list[str]) -> tuple[list[str], dict]:
    """The lens at the end of a VAR command (2.9.7, VAR_GUIDE.md section 18.6), taken off the words, in any order: ``10D``/``1D``, ``SUM``/
    ``SQRT``, a confidence (``95``, ``97.5``), ``ES <confidence>``, and ``IN <currency>``. Returns the words left and the link's parameters
    (``h``, ``m``, ``q``, ``esq``, ``ccy``)."""
    out: dict = {}
    w = list(words)
    kind = VAR_WORDS.get(w[1].upper(), "") if len(w) > 1 and w[0].upper() == "VAR" else ""
    least = 2 if kind == "BANK" else 3 if kind in _NEEDS_ARG else 10 ** 6   # what must stay: VAR BANK, or VAR <word> <argument>
    while len(w) > least:
        last, up = w[-1], w[-1].upper()
        two = len(w) - 2 >= least
        if two and w[-2].upper() == "IN" and CCY.match(last) and "ccy" not in out:
            out["ccy"] = up
            w = w[:-2]
        elif two and w[-2].upper() == "ES" and CONF.match(last) and "esq" not in out:
            out["esq"] = _fraction(last)
            w = w[:-2]
        elif up in ("10D", "1D") and "h" not in out:
            out["h"] = up.lower()
            w = w[:-1]
        elif up in ("SUM", "SQRT", "SQRT10") and "m" not in out:
            out["m"] = "sum" if up == "SUM" else "sqrt10"
            w = w[:-1]
        elif CONF.match(last) and "q" not in out:
            out["q"] = _fraction(last)
            w = w[:-1]
        else:
            break
    if "m" in out and "h" not in out:
        out["h"] = "10d"                                       # a method names the 10-day horizon
    if out.get("h") == "1d":
        out.pop("h")
        out.pop("m", None)
    return w, out


def var_command(words: list[str], user: str) -> str | None:
    """Where ``VAR <word> <rest> [10D [SUM|SQRT]] [95] [ES 97.5] [IN <currency>]`` goes (``VAR VAR-COMM``: a retired id, its desk), or
    None when it is not an explorer command. ``IN EUR`` adds ``ccy=EUR`` to the page's link; the lens ``h``, ``m``, ``q``, ``esq``."""
    if words and words[-1].upper() == "<GO>":
        words = words[:-1]
    words, ccy = split_currency(words)
    words, lens = split_lens(words)
    ccy = ccy or lens.pop("ccy", "")
    where = _var_where(words, user)
    if where:
        for k in ("h", "m", "q", "esq"):
            if lens.get(k):
                where += ("&" if "?" in where else "?") + k + "=" + lens[k]
    if where and ccy:
        where += ("&" if "?" in where else "?") + "ccy=" + ccy
    return where


def var_suggestions(q: str) -> list[dict]:
    """The command line's type-ahead for a VAR command (2.9.7): once a scope is typed, its horizons, methods and confidences
    (``VAR BANK 10D``, ``VAR BANK 10D SUM``, ``VAR BANK 10D SQRT``, ``VAR BANK 95``, ``VAR BANK ES 97.5``); nothing before a scope."""
    text = (q or "").replace("<GO>", "").strip()
    words = text.split()
    if len(words) < 2 or words[0].upper() != "VAR":
        return []
    base, lens = split_lens(words)
    kind = VAR_WORDS.get(base[1].upper(), "") if len(base) > 1 else ""
    if not kind or (kind != "BANK" and len(base) < 3) or kind == "CAPTURE":
        return []
    stem = " ".join(base)
    out = []
    picks = [("10D", "10-day VaR by the default method (a setting: √10 unless changed)"), ("10D SUM", "10-day VaR: the sum of 10 consecutive "
             "1-day scenario P&Ls (500 overlapping windows of 509)"), ("10D SQRT", "10-day VaR: the 1-day figures × √10"),
             ("95", "VaR at 95% (the 26th worst of 509)"), ("97.5", "VaR at 97.5%"), ("99.5", "VaR at 99.5%"), ("ES 97.5", "ES at 97.5% (the default)")]
    typed_tail = " ".join(words[len(base):]).upper()
    for tail, what in picks:
        if lens and not tail.upper().startswith(typed_tail):
            continue
        cmd = f"{stem} {tail}"
        out.append({"mnemonic": "VAR", "title": cmd, "subtitle": what, "complete": cmd, "href": "/go?q=" + quote(cmd), "type": "var"})
    return out[:7]


def _var_where(words: list[str], user: str) -> str | None:
    if not words or words[0].upper() != "VAR":
        return None
    if len(words) == 1:
        return "/var"
    word = words[1].upper()
    if word not in VAR_WORDS:
        scope = retired_scope(words[1]) if len(words) == 2 else None
        return f"/var?scope={quote(scope)}&from={quote(words[1])}" if scope else None
    rest = " ".join(words[2:]).strip()
    kind = VAR_WORDS[word]
    if kind == "BANK":
        return "/var?scope=BANK"
    if kind == "CAPTURE":
        return f"/var?capture={quote(rest)}" if rest else "/var"
    if not rest:
        return None
    if kind == "SCOPE":                       # a saved basket (mine: its name; another's: owner/name), a portfolio, or scope text
        if ":" in rest or " " in rest:
            scope = rest
        elif rest.upper().startswith("PF-"):
            scope = "PORT:" + rest.upper()
        else:
            scope = "BASKET:" + (rest if "/" in rest else f"{user}/{rest}")
    elif kind in ("POS", "TRD", "CPTY", "WHERE", "BASKET"):
        scope = f"{kind}:{rest}"
    else:
        scope = f"{kind}:{rest.replace(' ', '-')}"
    return f"/var?scope={quote(scope)}"


def _problem(e: BackendError) -> JSONResponse:
    return JSONResponse({"code": e.code, "detail": e.detail}, status_code=e.status)


#: a hierarchy's id (2.9.5: any configured one, LL; the page's selector lists the date's from the engine)
HIERARCHY = re.compile(r"^[A-Z][A-Z0-9_]{0,31}$")


def hierarchy_of(text: str) -> str:
    """The hierarchy a link asks for, upper case: any well-formed id (the engine says when the date has none: the page then shows MGMT,
    with a note); MGMT for anything else."""
    h = (text or "").strip().upper()
    return h if HIERARCHY.match(h) else "MGMT"


#: the lens's words in a link (2.9.7): h=1d|10d, m=sqrt10|sum, q and esq confidences as fractions
_H = re.compile(r"^(1d|10d)$")
_M = re.compile(r"^(sqrt10|sum)$")
_Q = re.compile(r"^0\.\d{1,4}$")


def lens_of(request: Request, h: str, m: str, q: str, esq: str) -> dict:
    """The page's lens: the link's (``h``, ``m``, ``q``, ``esq``), else the one the person chose last (their settings' ``varView``), each
    checked; the engine refuses what is not offered, in words."""
    saved = (getattr(request.state, "settings", None) or {}).get("varView") or {}
    linked = any((h, m, q, esq))
    def pick(v, saved_v, rx):
        v = (v or "").strip().lower()
        if v and rx.match(v):
            return v
        s = "" if linked else str(saved_v if saved_v is not None else "")
        return s if s and rx.match(s) else ""
    return {"h": pick(h, saved.get("h"), _H), "m": pick(m, saved.get("m"), _M), "q": pick(q, saved.get("q"), _Q),
            "esq": pick(esq, saved.get("esq"), _Q)}


@router.get("/var")
async def page(request: Request, scope: str = "", capture: str = "", hierarchy: str = "MGMT", ccy: str = "", h: str = "", m: str = "",
               q: str = "", esq: str = ""):
    """The explorer's shell; its panels load in place (no full-page reload when drilling). ``ccy`` is the page's reporting currency
    (none: the one chosen earlier in the session, else USD); ``hierarchy`` the tree shown (MGMT, LEGAL, LL: any configured one); ``h``,
    ``m``, ``q``, ``esq`` the lens (2.9.7: horizon, 10-day method, confidences; none: the person's last choice, else the default)."""
    me = getattr(request.state, "identity", None)
    return render(request, "terminal/var.html", scope=scope or "BANK", capture=capture, hierarchy=hierarchy_of(hierarchy),
                  user=(me.user if me else "") or "", screen="var", ccy=ccy.upper() if CCY.match(ccy or "") else "",
                  lens=lens_of(request, h, m, q, esq))


#: GET endpoints of the engine the page may ask for, and the parameters each passes on
_GETS = {"scope": ("scope",), "hints": ("q", "limit"), "scope/path": ("scope", "page", "size"), "scope/history": ("scope",),
         "scope/contributions": ("scope", "by", "scenario"), "tree": ("hierarchy",), "baskets": (), "captures": ("scope",),
         "compare": ("a", "b"), "status": (), "official": (), "scope/currencies": ("scope",), "currencies": (),
         "intraday/streams": ()}
#: the answers that take a lens (2.9.7, VAR_GUIDE.md section 18.6), and its parameters as the engine names them
LENS_PATHS = ("scope", "scope/path", "scope/history", "scope/contributions", "scope/currencies", "tree")
LENS_PARAMS = ("horizon", "method", "confidence", "esConfidence")
#: the answers a browser may take as Arrow (2.9.7, measured: DATA_FORMATS.md section 6), with where each one's table is; the P&L path
#: stays JSON (509 numbers and the page around them: Arrow was no smaller and no faster)
ARROW_TABLES = {"scope/contributions": ("records", "rows")}
#: an administrator's "apply now" of a PV topic's waiting window (the var-pv-stream connector, VAR_GUIDE.md section 8.4)
APPLY_NOW = re.compile(r"^intraday/streams/[A-Za-z0-9_.-]{1,80}/apply$")


@router.get("/api/var/{path:path}")
async def api_get(request: Request, path: str):
    q = request.query_params
    try:
        if path.startswith("captures/") and path.endswith(("/csv", "/jsonl")):     # a capture's CSV or JSON Lines (2.9.4), as the server made it
            body, headers = await request.app.state.backend.var("GET", "/" + path, ident(request), raw=True, part=q.get("part", ""))
            return Response(body, media_type="text/csv" if path.endswith("/csv") else "application/jsonl",
                            headers={"Content-Disposition": headers.get("content-disposition", "attachment")})
        if path.startswith("captures/") or path.startswith("baskets/"):
            return await request.app.state.backend.var("GET", "/" + path, ident(request))
        if path not in _GETS:
            return JSONResponse({"code": "DRS-1001", "detail": f"no VaR endpoint {path}"}, status_code=404)
        params = {k: q.get(k, "") for k in _GETS[path]}
        for k in ("asOf", "ccy"):                         # the business date and the reporting currency, on every answer
            if q.get(k):
                params[k] = q.get(k)
        if path in LENS_PATHS:                            # 2.9.7: the horizon, the 10-day method and the confidences
            for k in LENS_PARAMS:
                if q.get(k):
                    params[k] = q.get(k)
        if path in ARROW_TABLES and arrow_answer.wanted(request.headers.get("accept")):   # 2.9.7: the table as Arrow (DATA_FORMATS.md section 6)
            content, headers = await request.app.state.backend.var("GET", "/" + path, ident(request), raw=True, accept=arrow_answer.MEDIA, **params)
            return arrow_answer.pass_on(content, headers, *ARROW_TABLES[path])
        return await request.app.state.backend.var("GET", "/" + path, ident(request), **params)
    except BackendError as e:
        return _problem(e)


@router.post("/api/var/{path:path}")
async def api_post(request: Request, path: str):
    if APPLY_NOW.match(path):
        try:
            return await request.app.state.backend.var("POST", "/" + path, ident(request))
        except BackendError as e:
            return _problem(e)
    if path not in ("baskets", "captures", "what-if"):
        return JSONResponse({"code": "DRS-1001", "detail": f"no VaR endpoint {path}"}, status_code=404)
    try:
        extra = {"ccy": request.query_params["ccy"]} if request.query_params.get("ccy") else {}    # a capture or a what-if in that currency
        return await request.app.state.backend.var("POST", "/" + path, ident(request), body=await json_body(request),
                                                   asOf=request.query_params.get("asOf", ""), **extra)
    except BackendError as e:
        return _problem(e)


@router.delete("/api/var/baskets/{owner}/{name}")
async def api_delete_basket(request: Request, owner: str, name: str):
    try:
        await request.app.state.backend.var("DELETE", f"/baskets/{quote(owner)}/{quote(name)}", ident(request))
    except BackendError as e:
        return _problem(e)
    return Response(status_code=204)


def redirect_for(typed: str, user: str) -> RedirectResponse | None:
    """The terminal's ``/go`` asks here first: a VaR explorer command, or None."""
    where = var_command(typed.split(), user)
    return RedirectResponse(where, status_code=303) if where else None
