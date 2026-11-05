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
``/var?scope=DESK:RATES``. The page is a shell; web/static/js/drishti_var-explorer.js fills its panels from the same-origin JSON below, which
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
    """The lens at the end of a VAR command (VAR_GUIDE.md sections 18.6 and 18.8), taken off the words, in any order: ``10D``/``1D``,
    ``SUM``/``SQRT``, a confidence (``95``, ``97.5``), ``ES <confidence>``, ``SVAR`` (stressed VaR, on the date's stress scenario set) and
    ``IN <currency>``. Returns the words left and the link's parameters (``h``, ``m``, ``q``, ``esq``, ``set``, ``ccy``)."""
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
        elif up in ("SVAR", "STRESSED") and "set" not in out:
            out["set"] = "stressed"
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
    """Where ``VAR <word> <rest> [SVAR] [10D [SUM|SQRT]] [95] [ES 97.5] [IN <currency>]`` goes (``VAR VAR-COMM``: a retired id, its desk),
    or None when it is not an explorer command. ``IN EUR`` adds ``ccy=EUR`` to the page's link; the lens ``h``, ``m``, ``q``, ``esq``,
    ``set``."""
    if words and words[-1].upper() == "<GO>":
        words = words[:-1]
    if (why := explain_command(words, user)) is not None:     # VAR EXPLAIN <scope> [from] [to]: the page's Why it moved tab (2.9.10)
        return why
    from routes.var_regulatory_routes import regulatory_command      # VAR BANK BACKTEST, VAR BANK STATS FY2026, VAR REPORT <report> <period>
    if (reg := regulatory_command(words)) is not None:
        return reg
    words, ccy = split_currency(words)
    words, lens = split_lens(words)
    ccy = ccy or lens.pop("ccy", "")
    where = _var_where(words, user)
    if where:
        for k in ("h", "m", "q", "esq", "set"):
            if lens.get(k):
                where += ("&" if "?" in where else "?") + k + "=" + lens[k]
    if where and ccy:
        where += ("&" if "?" in where else "?") + "ccy=" + ccy
    return where


#: a business date in a command (VAR EXPLAIN BANK 2026-09-29 2026-09-30)
DAY = re.compile(r"^\d{4}-\d{2}-\d{2}$")


def explain_command(words: list[str], user: str) -> str | None:
    """Where ``VAR EXPLAIN <scope> [from] [to]`` (or ``VAR <scope> EXPLAIN [from] [to]``) goes: the VaR page at the scope on its
    **Why it moved** tab (VAR_GUIDE.md section 3.11), the dates in the link (``efrom``, ``eto``); no scope: the bank. None when the
    command does not ask for the explanation."""
    w = [x for x in words if x.upper() != "<GO>"]
    if len(w) < 2 or w[0].upper() != "VAR":
        return None
    dates = []
    while len(w) > 2 and DAY.match(w[-1]) and len(dates) < 2:
        dates.insert(0, w.pop())
    up = [x.upper() for x in w]
    if up[1] == "EXPLAIN":
        rest = w[2:]
    elif up[-1] == "EXPLAIN":
        rest = w[1:-1]
    else:
        return None
    if len(rest) == 1 and ":" in rest[0]:
        where = f"/var?scope={quote(rest[0])}"
    else:
        where = _var_where(["VAR"] + (rest or ["BANK"]), user)
    if not where:
        return None
    where += "&tab=why"
    if dates:
        where += "&efrom=" + dates[0] + ("&eto=" + dates[1] if len(dates) > 1 else "")
    return where


def var_suggestions(q: str) -> list[dict]:
    """The command line's type-ahead for a VAR command (2.9.7): once a scope is typed, its horizons, methods and confidences
    (``VAR BANK 10D``, ``VAR BANK 10D SUM``, ``VAR BANK 10D SQRT``, ``VAR BANK 95``, ``VAR BANK ES 97.5``); nothing before a scope."""
    text = (q or "").replace("<GO>", "").strip()
    words = text.split()
    if len(words) < 2 or words[0].upper() not in ("VAR", "PVAR"):
        return []
    head = words[0].upper()                                       # PVAR: the same scope and lens words, in parametric mode
    words = ["VAR"] + words[1:]
    base, lens = split_lens(words)
    kind = VAR_WORDS.get(base[1].upper(), "") if len(base) > 1 else ""
    if not kind or (kind != "BANK" and len(base) < 3) or kind == "CAPTURE":
        return []
    stem = " ".join([head] + base[1:])
    out = []
    picks = [("10D", "10-day VaR by the default method (a setting: √10 unless changed)"), ("10D SUM", "10-day VaR: the sum of 10 consecutive "
             "1-day scenario P&Ls (500 overlapping windows of 509)"), ("10D SQRT", "10-day VaR: the 1-day figures × √10"),
             ("95", "VaR at 95% (the 26th worst of 509)"), ("97.5", "VaR at 97.5%"), ("99.5", "VaR at 99.5%"), ("ES 97.5", "ES at 97.5% (the default)"),
             ("SVAR", "stressed VaR: today's positions under the date's 12-month stress window"),
             ("SVAR 10D SUM", "stressed 10-day VaR: the sum of 10 consecutive stress days"),
             ("EXPLAIN", "why it moved: the change since the previous business date in causes that add up exactly")]
    typed_tail = " ".join(words[len(base):]).upper()
    for tail, what in picks:
        if lens and not tail.upper().startswith(typed_tail) or head == "PVAR" and tail == "EXPLAIN":
            continue
        cmd = f"{stem} {tail}"
        out.append({"mnemonic": head, "title": cmd, "subtitle": what, "complete": cmd, "href": "/go?q=" + quote(cmd), "type": "var"})
    return out[:10]


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
    from core.busy import problem as busy_problem

    return busy_problem(e, e.status)                               # a busy refusal keeps its Retry-After and words (busy protection)


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
#: the scenario set (VAR_GUIDE.md section 18.8): normal, or stressed (the date's stress scenario set)
_SET = re.compile(r"^(normal|stressed)$")


def lens_of(request: Request, h: str, m: str, q: str, esq: str, set_: str = "") -> dict:
    """The page's lens: the link's (``h``, ``m``, ``q``, ``esq``, ``set``), else the one the person chose last (their settings'
    ``varView``), each checked; the engine refuses what is not offered, in words."""
    saved = (getattr(request.state, "settings", None) or {}).get("varView") or {}
    linked = any((h, m, q, esq, set_))
    def pick(v, saved_v, rx):
        v = (v or "").strip().lower()
        if v and rx.match(v):
            return v
        s = "" if linked else str(saved_v if saved_v is not None else "")
        return s if s and rx.match(s) else ""
    return {"h": pick(h, saved.get("h"), _H), "m": pick(m, saved.get("m"), _M), "q": pick(q, saved.get("q"), _Q),
            "esq": pick(esq, saved.get("esq"), _Q), "set": pick(set_, saved.get("set"), _SET)}


#: the VaR page's tabs a link may open (tab=why: Why it moved, 2.9.10) and the views of Why it moved
TABS = ("path", "hist", "contrib", "captures", "ccy", "why")
EXPLAIN_VIEWS = ("waterfall", "matrix", "sentences")


def explain_view(request: Request, view: str) -> str:
    """The Why it moved view: the link's (``view=``), else the one the person chose last (their settings' ``varExplainView``), else
    Waterfall."""
    v = (view or "").strip().lower()
    if v in EXPLAIN_VIEWS:
        return v
    saved = str((getattr(request.state, "settings", None) or {}).get("varExplainView") or "")
    return saved if saved in EXPLAIN_VIEWS else "waterfall"


@router.get("/var")
async def page(request: Request, scope: str = "", capture: str = "", hierarchy: str = "MGMT", ccy: str = "", h: str = "", m: str = "",
               q: str = "", esq: str = "", set: str = "", tab: str = "", view: str = "", pm: str = ""):  # noqa: A002 (the link's word)
    """The explorer's shell; its panels load in place (no full-page reload when drilling). ``ccy`` is the page's reporting currency
    (none: the one chosen earlier in the session, else USD); ``hierarchy`` the tree shown (MGMT, LEGAL, LL: any configured one); ``h``,
    ``m``, ``q``, ``esq``, ``set`` the lens (horizon, 10-day method, confidences, Normal or Stressed; none: the person's last choice, else
    the default)."""
    me = getattr(request.state, "identity", None)
    return render(request, "terminal/var.html", scope=scope or "BANK", capture=capture, hierarchy=hierarchy_of(hierarchy),
                  user=(me.user if me else "") or "", admin=bool(me and me.is_admin), screen="var", ccy=ccy.upper() if CCY.match(ccy or "") else "",
                  lens=lens_of(request, h, m, q, esq, set), tab=tab if tab in TABS else "path", explain_view=explain_view(request, view),
                  parametric_mode=pm == "1")


#: GET endpoints of the engine the page may ask for, and the parameters each passes on
_GETS = {"scope": ("scope", "parametric"), "hints": ("q", "limit"), "scope/path": ("scope", "page", "size", "parametric"), "scope/history": ("scope",),
         "scope/contributions": ("scope", "by", "scenario", "parametric", "matrix"), "tree": ("hierarchy", "parametric"), "baskets": (), "captures": ("scope",),
         "compare": ("a", "b"), "status": (), "official": (), "scope/currencies": ("scope",), "currencies": (),
         "intraday/streams": (), "basket/sheet": ("scope",),         # a scope laid into the basket sheet (a basket, a portfolio's copy)
         # the stress window selection (VAR_GUIDE.md section 18.9): the analysis of a node and the frozen proposals
         "stress-window": ("scope", "hierarchy", "length", "confidence"), "stress-window/proposals": ("limit",),
         # why it moved (VAR_GUIDE.md section 3.11): the explanation between two business dates and its Matrix view
         "explain": ("scope", "from", "to", "confidence"), "explain/matrix": ("scope", "from", "to", "confidence")}
#: the answers that take a lens (2.9.7, VAR_GUIDE.md section 18.6), and its parameters as the engine names them
LENS_PATHS = ("scope", "scope/path", "scope/history", "scope/contributions", "scope/currencies", "tree")
LENS_PARAMS = ("horizon", "method", "confidence", "esConfidence", "set")
#: the answers a browser may take as Arrow (2.9.7, measured: DATA_GUIDE.md › Data formats: JSON Lines is the standard, section 6), with where each one's table is; the P&L path
#: stays JSON (509 numbers and the page around them: Arrow was no smaller and no faster)
ARROW_TABLES = {"scope/contributions": ("records", "rows")}
#: an administrator's "apply now" of a PV topic's waiting window (the var-pv-stream connector, VAR_GUIDE.md section 8.4)
APPLY_NOW = re.compile(r"^intraday/streams/[A-Za-z0-9_.-]{1,80}/apply$")
#: a stress window proposal's decision (approve or reject: the server refuses the proposer)
DECISION = re.compile(r"^stress-window/proposals/SWP-[0-9]{1,12}/decision$")
PROPOSAL = re.compile(r"^stress-window/proposals/SWP-[0-9]{1,12}$")


@router.get("/api/var/explain.xlsx")
async def explain_workbook(request: Request):
    """Why it moved as a workbook of formulas (VAR_GUIDE.md section 3.11), streamed from the server as the person."""
    from routes.play_routes import _stream
    q = request.query_params
    p = {k: q[k] for k in ("scope", "from", "to", "confidence") if q.get(k)}
    if not p.get("scope"):
        return JSONResponse({"code": "DRS-5001", "detail": "give a VaR scope"}, status_code=400)
    return await _stream(request, "/var/explain.xlsx", p, "var-explain.xlsx")


@router.get("/api/var/{path:path}")
async def api_get(request: Request, path: str):
    q = request.query_params
    try:
        if path.startswith("captures/") and path.endswith(("/csv", "/jsonl")):     # a capture's CSV or JSON Lines (2.9.4), as the server made it
            body, headers = await request.app.state.backend.var("GET", "/" + path, ident(request), raw=True, part=q.get("part", ""))
            return Response(body, media_type="text/csv" if path.endswith("/csv") else "application/jsonl",
                            headers={"Content-Disposition": headers.get("content-disposition", "attachment")})
        if path.startswith("captures/") or path.startswith("baskets/") or PROPOSAL.match(path):
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
        if path in ARROW_TABLES and arrow_answer.wanted(request.headers.get("accept")):   # 2.9.7: the table as Arrow (DATA_GUIDE.md › Data formats: JSON Lines is the standard, section 6)
            content, headers = await request.app.state.backend.var("GET", "/" + path, ident(request), raw=True, accept=arrow_answer.MEDIA, **params)
            return arrow_answer.pass_on(content, headers, *ARROW_TABLES[path])
        return await request.app.state.backend.var("GET", "/" + path, ident(request), **params)
    except BackendError as e:
        return _problem(e)


@router.post("/api/var/basket/sheet.xlsx")
async def basket_workbook(request: Request):
    """The basket sheet as a workbook (VAR_GUIDE.md section 3.5): its columns as laid out and the cells that need a word, made by the
    server as the person (it checks every cell again)."""
    q = request.query_params
    extra = {k: q[k] for k in ("asOf", "ccy") if q.get(k)}
    sheet = await json_body(request)                      # a body that is not a JSON object is a 400/415 before the server is asked
    try:
        body, headers = await request.app.state.backend.var("POST", "/basket/sheet.xlsx", ident(request), body=sheet, raw=True, **extra)
    except BackendError as e:
        return _problem(e)
    return Response(body, media_type="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    headers={"Content-Disposition": headers.get("content-disposition", 'attachment; filename="basket.xlsx"')})


@router.post("/api/var/{path:path}")
async def api_post(request: Request, path: str):
    if APPLY_NOW.match(path):
        try:
            return await request.app.state.backend.var("POST", "/" + path, ident(request))
        except BackendError as e:
            return _problem(e)
    if DECISION.match(path) or path == "stress-window/proposals":
        try:
            return await request.app.state.backend.var("POST", "/" + path, ident(request), body=await json_body(request))
        except BackendError as e:
            return _problem(e)
    if path not in ("baskets", "captures", "what-if", "basket/sheet"):    # basket/sheet: the sheet's checks and figures (VAR_GUIDE.md 3.5)
        return JSONResponse({"code": "DRS-1001", "detail": f"no VaR endpoint {path}"}, status_code=404)
    try:
        extra = {"ccy": request.query_params["ccy"]} if request.query_params.get("ccy") else {}    # a capture or a what-if in that currency
        return await request.app.state.backend.var("POST", "/" + path, ident(request), body=await json_body(request),
                                                   asOf=request.query_params.get("asOf", ""), **extra)
    except BackendError as e:
        return _problem(e)


@router.delete("/api/var/baskets/{owner}/{name}")
async def api_delete_basket(request: Request, owner: str, name: str):
    """Deletes a saved basket: the owner's own, or anyone's for an administrator (the server decides). ``reason`` (optional) goes into
    the audit entry and the owner's inbox; ``version`` (optional) is the version the confirm dialog showed (a basket changed since: 409)."""
    q = request.query_params
    extra = {k: q[k][:500] for k in ("reason", "version") if q.get(k)}
    try:
        await request.app.state.backend.var("DELETE", f"/baskets/{quote(owner, safe='')}/{quote(name, safe='')}", ident(request), **extra)
    except BackendError as e:
        return _problem(e)
    return Response(status_code=204)


def pvar_command(words: list[str], user: str) -> str | None:
    """Where ``PVAR <scope> [lens]`` goes (docs/architecture/PARAMETRIC_VAR.md, VAR_GUIDE.md section 3.12): the VaR explorer in parametric
    toggle on (``pm=1``: the parametric tiles, the tree's Parametric and Gap columns, the normal curve), the scope and the lens read as
    ``VAR`` reads them; ``PVAR`` alone: the bank. An alias of ``VAR``: no screen of its own. None when the words are not a PVAR command."""
    w = [x for x in words if x.upper() != "<GO>"]
    if not w or w[0].upper() != "PVAR":
        return None
    where = var_command(["VAR"] + (w[1:] or ["BANK"]), user)
    if not where:
        return None
    return where + ("&" if "?" in where else "?") + "pm=1"


def redirect_for(typed: str, user: str) -> RedirectResponse | None:
    """The terminal's ``/go`` asks here first: a VaR explorer command, its parametric mode (``PVAR``) or a stress window command
    (``SWIN``), or None."""
    where = swin_command(typed.split()) or pvar_command(typed.split(), user) or var_command(typed.split(), user)
    return RedirectResponse(where, status_code=303) if where else None


# ---------------------------------------------------------------------------------------------------------- the stress window selection
#: the command word of the stress window selection (VAR_GUIDE.md section 18.9): SWIN BANK, SWIN DESK DESK-RATES, SWIN DESK:DESK-RATES
SWIN_WORD = "SWIN"


def swin_command(words: list[str]) -> str | None:
    """Where ``SWIN [<scope>] [LEGAL]`` goes: the stress window selection of a node (``SWIN`` alone: the bank), or None when the words are
    not a SWIN command. ``SWIN DESK DESK-RATES`` and ``SWIN DESK:DESK-RATES`` are the same node; a trailing ``LEGAL`` (or ``MGMT``)
    names the hierarchy."""
    if words and words[-1].upper() == "<GO>":
        words = words[:-1]
    if not words or words[0].upper() != SWIN_WORD:
        return None
    rest = [w for w in words[1:]]
    hierarchy = ""
    if rest and rest[-1].upper() in ("MGMT", "LEGAL"):
        hierarchy = rest.pop().upper()
    if not rest:
        scope = "BANK"
    elif len(rest) == 1:
        scope = rest[0].upper()
    else:
        scope = f"{rest[0].upper()}:{'-'.join(rest[1:]).upper()}"
    return f"/var/stress-window?scope={quote(scope)}" + (f"&hierarchy={hierarchy}" if hierarchy else "")


@router.get("/var/stress-window")
async def stress_window_page(request: Request, scope: str = "BANK", hierarchy: str = "MGMT", length: str = ""):
    """The stress window selection (VAR_GUIDE.md section 18.9): a shell that drishti_stress-window.js fills from /api/var/stress-window."""
    me = getattr(request.state, "identity", None)
    n = int(length) if length.isdigit() else 0
    return render(request, "terminal/stress_window.html", scope=(scope or "BANK").strip()[:120], hierarchy=hierarchy_of(hierarchy),
                  length=n, user=(me.user if me else "") or "", screen="var-stress-window",
                  command_text=f"SWIN {(scope or 'BANK').strip()}")
