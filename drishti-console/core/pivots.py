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

"""Saved pivots, the console side (USER_GUIDE.md, The Pivot tab). A Pivot tab exists only where a Sutra (pivot: on a table
or ladder) or a pack (pivot: beside a kind's columns:) opts in; the server says so in the view or the search result. What
the console adds is the user's own arrangement of each tab, kept by the server per (Sutra, panel) and per kind, so the tab
opens as they left it, and whether they may promote one (an author, with Studio saving on). Asked of the server once a
minute per user, and forgotten whenever they save, reset or promote one."""
from __future__ import annotations

import time

from core.servers import scoped

ARRANGEMENT = ("rows", "columns", "values", "filters", "heat", "chart")


def arrangement(saved: dict | None) -> dict | None:
    """Only the keys the Pivot tab reads, from what the server keeps (scope, Sutra, panel, kind and time are left out)."""
    if not isinstance(saved, dict):
        return None
    return {k: saved[k] for k in ARRANGEMENT if k in saved}


class Pivots:
    """The user's saved pivots."""

    TTL = 60.0

    def __init__(self):
        self._kept: dict[str, tuple[float, dict]] = {}

    async def state(self, backend, ident) -> dict:
        """``enabled``, ``promote``, ``review`` and ``pivots`` (each still valid against its Sutra or pack). An older server,
        or one away for a moment: nothing saved, nothing to promote (the tabs still work)."""
        none = {"enabled": True, "promote": False, "review": True, "pivots": []}
        if ident is None:
            return none
        key = scoped(ident.user)
        hit = self._kept.get(key)
        if hit and time.monotonic() - hit[0] < self.TTL:
            return hit[1]
        try:
            data = await backend.pivots(ident)
        except Exception:  # noqa: BLE001 - the tabs work without saved arrangements
            return none
        self._kept[key] = (time.monotonic(), data)
        return data

    def forget(self, user: str) -> None:
        self._kept.pop(scoped(user), None)

    @staticmethod
    def saved_panel(state: dict, sutra: str | None, panel: str) -> dict | None:
        if not sutra:
            return None
        hit = next((p for p in state.get("pivots") or [] if p.get("scope") == "panel" and p.get("sutra") == sutra and p.get("panel") == panel), None)
        return arrangement(hit)

    @staticmethod
    def saved_search(state: dict, kind: str) -> dict | None:
        hit = next((p for p in state.get("pivots") or [] if p.get("scope") == "search" and p.get("kind") == kind), None)
        return arrangement(hit)

    async def view(self, request, vm: dict, panels: list[dict]) -> tuple[list[dict], dict]:
        """The view's panels, each table that offers a pivot with the user's saved arrangement (``pivotSaved``), and what
        the page needs to know: whether the user may promote a pivot and whether that goes through review."""
        if not any(isinstance((p.get("data") or {}), dict) and (p.get("data") or {}).get("pivot") for p in panels):
            return panels, {"promote": False, "review": True}
        state = await self.state(request.app.state.backend, getattr(request.state, "identity", None))
        sutra = (vm.get("provenance") or {}).get("sutra")
        out = []
        for p in panels:
            saved = self.saved_panel(state, sutra, p.get("id", "")) if (p.get("data") or {}).get("pivot") else None
            out.append({**p, "pivotSaved": saved} if saved else p)
        return out, {"promote": bool(state.get("promote")) and bool(sutra), "review": bool(state.get("review", True))}
