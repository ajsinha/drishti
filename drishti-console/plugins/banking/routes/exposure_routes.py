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

"""The exposure explorer (docs/guides/EXPOSURE_GUIDE.md section 3): ``EXP <scope> <GO>`` opens ``/exposure?scope=<scope>``. The page
is a shell; plugins/banking/static/js/drishti_exposure-explorer.js fills it from the same-origin JSON below, which asks the server's exposure engine
(/api/v1/risk/exposure) as the user, so entitlements by counterparty, group and legal entity and the name mask apply as on every screen.

Commands: ``EXP`` (the bank), ``EXP NSET:NS-ALDERSHOT-FRA`` as typed, and the spaced forms ``EXP NSET NS-ALDERSHOT-FRA``, ``EXP CPTY
CP-ALDERSHOT``, ``EXP GROUP GRP-ALDERSHOT``, ``EXP LE LE-NY``, ``EXP TRD <trade>`` made into the scope grammar; any scope text
(``EXP CPTY:CP-X - NSET:NS-X-FRA``). The computed kind ``exposure-result`` stays an ordinary kind (``/v/exposure-result/<scope>``,
the API, the CLI); the explorer is its main screen. The stored ``exposure-profile`` kind was retired in 2.9.0: an old id (``EXP
EXP-ALDERSHOT-FRA``) and an old link (``/v/exposure-profile/EXP-ALDERSHOT-FRA``) open the same netting set in the explorer, with a
notice. ``PREDEAL <counterparty> LIKE <trade> <notional>`` opens Counterparties → Pre-deal check
(``/predeal``, EXPOSURE_GUIDE.md section 18.9), which asks the server's pre-deal service; the explorer's old Pre-deal tab is gone and its
address (``/exposure?scope=...&tab=predeal``) opens the check."""
from __future__ import annotations

from urllib.parse import quote

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse, RedirectResponse, Response

from core.backend import BackendError
from core.csrf import json_body
from routes.common import ident, render
from plugins.banking import risk_api
from core import plugins as _plugins

router = APIRouter(include_in_schema=False)

KIND = "exposure-result"
#: the words after EXP that name a scope, and the scope word each means
EXP_WORDS = {"NSET": "NSET", "NETTING-SET": "NSET", "CPTY": "CPTY", "COUNTERPARTY": "CPTY", "GROUP": "GROUP", "GRP": "GROUP", "LE": "LE",
             "DESK": "DESK", "BOOK": "BOOK", "TRD": "TRD", "TRADE": "TRD", "BANK": "BANK"}
RETIRED_KINDS = ("exposure-profile",)


def retired_scope(old_id: str) -> str | None:
    """The scope an id of the retired stored exposure profile opens: ``EXP-<cp>-<le>`` the netting set ``NSET:NS-<cp>-<le>``."""
    i = (old_id or "").strip()
    if i.upper().startswith("EXP-") and len(i) > 4:
        return "NSET:NS-" + i[4:].upper()
    return None


def explorer(scope: str, old: str | None = None) -> str:
    """The explorer's address for a scope (``from``: the retired id it was opened by)."""
    return f"/exposure?scope={quote(scope, safe=':')}" + (f"&from={quote(old)}" if old else "")


def retired_link(kind: str, old_id: str) -> str | None:
    """Where ``/v/<kind>/<id>`` of the retired kind goes: the explorer at its netting set (the bank when it names none)."""
    if kind not in RETIRED_KINDS:
        return None
    return explorer(retired_scope(old_id) or "BANK", old_id)


def exp_command(words: list[str]) -> str | None:
    """Where ``EXP <words>`` goes: the explorer at the scope it means, or None when the words are not an exposure command."""
    if not words or words[0].upper() != "EXP":
        return None
    if len(words) == 1:
        return explorer("BANK")
    if len(words) == 2:
        old = retired_scope(words[1])
        if old:
            return explorer(old, words[1])
    word = words[1].upper()
    if word in EXP_WORDS and ":" not in words[1]:
        kind = EXP_WORDS[word]
        if kind == "BANK" or len(words) == 2:
            return explorer("BANK") if kind == "BANK" else None
        rest = ",".join(w.strip(",") for w in words[2:] if w.strip(",")) if kind in ("NSET", "CPTY", "TRD") else "".join(words[2:])
        return explorer(f"{kind}:{rest}")
    if len(words) == 2 and ":" not in words[1]:                  # a bare id: EXP NS-ALDERSHOT-FRA, EXP CP-ALDERSHOT
        for prefix, word in (("NS-", "NSET"), ("CP-", "CPTY"), ("GRP-", "GROUP"), ("LE-", "LE")):
            if words[1].upper().startswith(prefix):
                return explorer(f"{word}:{words[1].upper()}")
    return explorer(" ".join(words[1:]))


def _problem(e: BackendError) -> JSONResponse:
    from core.busy import problem as busy_problem

    return busy_problem(e, e.status)                               # a busy refusal keeps its Retry-After and words (busy protection)


def predeal_page(command: str = "", counterparty: str = "", netting_set: str = "") -> str:
    """The address of Counterparties → Pre-deal check: a typed command, or the counterparty and the netting set to start from."""
    q = [("command", command), ("counterparty", counterparty), ("nettingSet", netting_set)]
    rest = "&".join(f"{k}={quote(v, safe=':')}" for k, v in q if v)
    return "/predeal" + ("?" + rest if rest else "")


def _from_scope(scope: str) -> tuple[str, str]:
    """The counterparty and netting set an explorer scope names (``CPTY:CP-X``, ``NSET:NS-X``), for the old Pre-deal tab's address."""
    s = (scope or "").strip()
    if s.upper().startswith("CPTY:"):
        return s[5:].split(",")[0], ""
    if s.upper().startswith("NSET:"):
        return "", s[5:].split(",")[0]
    return "", ""


@router.get("/exposure")
async def page(request: Request, scope: str = "", capture: str = "", tab: str = ""):
    """The explorer's shell; its panels load in place (no full-page reload when drilling). The old Pre-deal tab's address opens the check."""
    if tab.lower() in ("predeal", "pre-deal"):
        cp, ns = _from_scope(scope)
        return RedirectResponse(predeal_page(counterparty=cp, netting_set=ns), status_code=303)
    me = getattr(request.state, "identity", None)
    return render(request, "terminal/exposure.html", scope=scope or "BANK", capture=capture, user=(me.user if me else "") or "",
                  screen="exposure")


#: GET endpoints of the engine the page may ask for, and the parameters each passes on
_GETS = {"scope": ("scope", "q", "collateral"), "scope/profile": ("scope", "q", "collateral", "dates", "format"),
         "scope/distribution": ("scope", "q", "collateral", "date", "bins", "bands"),
         "scope/contributions": ("scope", "q", "collateral", "by", "figure", "date", "alone", "profile"), "tree": ("q",),
         "captures": ("scope",), "compare": ("a", "b"), "status": (), "official": (), "retention": ()}


@router.get("/api/exposure/{path:path}")
async def api_get(request: Request, path: str):
    q = request.query_params
    try:
        # a download: a capture's CSV or JSON Lines, or the profile as either (format=csv|jsonl), passed through as the server made it
        fmt = path.rsplit("/", 1)[-1] if path.startswith("captures/") and path.endswith(("/csv", "/jsonl")) else q.get("format") if path == "scope/profile" else None
        csv = fmt in ("csv", "jsonl")
        if path.startswith("captures/") and not csv:
            return await risk_api.exposure(request.app.state.backend, "GET", "/" + path, ident(request))
        if path not in _GETS and not csv:
            return JSONResponse({"code": "DRS-1001", "detail": f"no exposure endpoint {path}"}, status_code=404)
        params = {k: q.get(k, "") for k in _GETS.get(path, ())}
        if q.get("asOf"):
            params["asOf"] = q.get("asOf")
        if csv:
            body, headers = await risk_api.exposure(request.app.state.backend, "GET", "/" + path, ident(request), raw=True, **params)
            return Response(body, media_type="text/csv" if fmt == "csv" else "application/jsonl",
                            headers={"Content-Disposition": headers.get("content-disposition", "attachment")})
        return await risk_api.exposure(request.app.state.backend, "GET", "/" + path, ident(request), **params)
    except BackendError as e:
        return _problem(e)


@router.get("/predeal")
async def predeal(request: Request, command: str = "", counterparty: str = "", nettingSet: str = ""):  # noqa: N803 - the API's name
    """Counterparties → Pre-deal check: a shell; plugins/banking/static/js/drishti_exposure-predeal.js asks the server's pre-deal service
    (``POST /api/exposure/predeal``) and draws its answer on Drishti Elements; every number is the server's."""
    return render(request, "terminal/predeal.html", command=command, counterparty=counterparty.upper(), netting_set=nettingSet.upper(),
                  as_of=request.query_params.get("asOf", ""), screen="predeal")


@router.post("/api/exposure/{path:path}")
async def api_post(request: Request, path: str):
    if path not in ("captures", "predeal"):
        return JSONResponse({"code": "DRS-1001", "detail": f"no exposure endpoint {path}"}, status_code=404)
    q = request.query_params
    try:
        return await risk_api.exposure(request.app.state.backend, "POST", "/" + path, ident(request), body=await json_body(request),
                                                        **({"asOf": q.get("asOf")} if q.get("asOf") else {}))
    except BackendError as e:
        return _problem(e)


def redirect_for(typed: str) -> RedirectResponse | None:
    """The terminal's ``/go`` asks here: an exposure command (the explorer), or None."""
    where = exp_command(typed.split())
    return RedirectResponse(where, status_code=303) if where else None


def predeal_redirect(typed: str) -> RedirectResponse | None:
    """``PREDEAL …`` typed: Counterparties → Pre-deal check, the command made at once when it names more than the word."""
    words = typed.replace("<GO>", "").replace("<go>", "").split()
    if not words or words[0].upper() != "PREDEAL":
        return None
    return RedirectResponse(predeal_page(command=" ".join(words) if len(words) > 1 else ""), status_code=303)


# ---- the core's hooks (core/plugins.py, DOMAIN_MODULES.md C2) ----------------------------------------------------------------------
_plugins.command(predeal_redirect, needs=("EXP",))                            # PREDEAL ...: the pre-deal check
_plugins.command(redirect_for, needs=("EXP",))                                # EXP NSET NS-X, EXP EXP-X (retired): exposure-result
_plugins.retired(retired_link)                                                # a retired stored exposure profile (EXP-X-FRA): NSET:NS-X-FRA
