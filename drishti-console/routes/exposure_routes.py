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

"""Exposure commands and the retired stored exposure profile (docs/contracts/EXP.md sections 9 and 14). ``EXP <scope> <GO>`` opens the
computed kind ``exposure-result`` of the counterparty-risk pack (the server's exposure engine, as the viewer): ``EXP NSET:NS-ALDERSHOT-FRA``
as typed, and the spaced forms ``EXP NSET NS-ALDERSHOT-FRA``, ``EXP CPTY CP-ALDERSHOT``, ``EXP GROUP GRP-ALDERSHOT``, ``EXP LE LE-NY``,
``EXP TRD <trade>`` made into the scope grammar. The stored ``exposure-profile`` kind was retired in 2.9.0: an old id (``EXP
EXP-ALDERSHOT-FRA``) and an old link (``/v/exposure-profile/EXP-ALDERSHOT-FRA``) open the same netting set's computed profile,
``NSET:NS-ALDERSHOT-FRA``."""
from __future__ import annotations

from urllib.parse import quote

from fastapi.responses import RedirectResponse

KIND = "exposure-result"
#: the words after EXP that name a scope, and the scope word each means
EXP_WORDS = {"NSET": "NSET", "NETTING-SET": "NSET", "CPTY": "CPTY", "COUNTERPARTY": "CPTY", "GROUP": "GROUP", "GRP": "GROUP", "LE": "LE",
             "DESK": "DESK", "BOOK": "BOOK", "TRD": "TRD", "TRADE": "TRD"}
RETIRED_KINDS = ("exposure-profile",)


def retired_scope(old_id: str) -> str | None:
    """The scope an id of the retired stored exposure profile opens: ``EXP-<cp>-<le>`` the netting set ``NSET:NS-<cp>-<le>``."""
    i = (old_id or "").strip()
    if i.upper().startswith("EXP-") and len(i) > 4:
        return "NSET:NS-" + i[4:].upper()
    return None


def _link(scope: str, old: str | None = None) -> str:
    return f"/v/{KIND}/{quote(scope, safe=':')}" + (f"?from={quote(old)}" if old else "")


def retired_link(kind: str, old_id: str) -> str | None:
    """Where ``/v/<kind>/<id>`` of the retired kind goes: the computed profile of its netting set (the bank's when it names none)."""
    if kind not in RETIRED_KINDS:
        return None
    return _link(retired_scope(old_id) or "BANK", old_id)


def exp_command(words: list[str]) -> str | None:
    """Where ``EXP <words>`` goes when it needs more than the plain command (a spaced scope, a retired id), or None."""
    if len(words) < 2 or words[0].upper() != "EXP":
        return None
    if len(words) == 2:
        scope = retired_scope(words[1])
        return _link(scope, words[1]) if scope else None
    word = words[1].upper()
    if word not in EXP_WORDS or ":" in words[1]:
        return None
    rest = ",".join(w.strip(",") for w in words[2:] if w.strip(",")) if EXP_WORDS[word] in ("NSET", "CPTY", "TRD") else "".join(words[2:])
    return _link(f"{EXP_WORDS[word]}:{rest}")


def redirect_for(typed: str) -> RedirectResponse | None:
    """The terminal's ``/go`` asks here: an exposure command with a spaced scope or a retired id, or None."""
    where = exp_command(typed.split())
    return RedirectResponse(where, status_code=303) if where else None
