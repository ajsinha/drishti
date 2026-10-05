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

"""Personal layouts, the console side (USER_GUIDE.md, Layout mode). A user's arrangement of a Sutra's panels (order,
column, width in columns of a 12-column grid, height in grid rows, hidden) is kept by the server per user and Sutra;
the console applies it when it draws a view for that user, so the screen, workspace panes and printing all follow it,
while the server's view (and every API client, live update and cache) stays the Sutra's own. Whether the user may
customise layouts, and their layouts, are asked of the server once a minute per user, and forgotten when they save."""
from __future__ import annotations

import time

from core.servers import scoped

GRID = 12          # columns of the grid each column of a view is divided into
MAX_HEIGHT = 24    # grid rows a panel may be given


class Layouts:
    """The user's layouts, and applying one to a view's panels."""

    TTL = 60.0

    def __init__(self, settings):
        self.enabled = bool(settings.get("layouts.enabled", True))
        self.min_span = max(1, min(12, int(settings.get("layouts.min_span", 3))))      # narrowest width layout mode allows
        self._kept: dict[str, tuple[float, dict]] = {}

    async def state(self, backend, ident) -> dict:
        """The server's answer for this user: ``allowed``, ``promote``, ``review`` and ``layouts`` (each read against its
        Sutra as it is now). An older server, or one away for a moment: layouts are not offered."""
        if ident is None or not self.enabled:
            return {"allowed": False, "enabled": False, "layouts": []}
        key = scoped(ident.user)
        hit = self._kept.get(key)
        if hit and time.monotonic() - hit[0] < self.TTL:
            return hit[1]
        try:
            data = await backend.layouts(ident)
        except Exception:  # noqa: BLE001 - not offered when the server cannot say
            return {"allowed": False, "enabled": False, "layouts": []}
        self._kept[key] = (time.monotonic(), data)
        return data

    def forget(self, user: str) -> None:
        self._kept.pop(scoped(user), None)

    @staticmethod
    def find(state: dict, sutra: str | None, kind: str) -> dict | None:
        """The user's layout for a Sutra and kind, if they keep one."""
        if not sutra:
            return None
        return next((x for x in state.get("layouts") or [] if x.get("sutra") == sutra and x.get("kind") == kind), None)

    @staticmethod
    def apply(panels: list[dict], layout: dict | None) -> list[dict]:
        """The view's panels in the user's order, each with its column (``area``), ``span`` (None: the whole column),
        ``height`` (None: as tall as its content) and ``hidden``. Without a layout: the Sutra's. Entries for panels the
        view does not have are ignored; panels the layout does not list follow, as the Sutra places them."""
        def size(v, top):
            return v if isinstance(v, int) and not isinstance(v, bool) and 1 <= v <= top else None

        def placed(p: dict, e: dict | None) -> dict:
            src = e if e is not None else p
            span = size(src.get("span"), GRID)
            area = src.get("area") if src.get("area") in ("main", "right") else p.get("area") or "main"
            return {**p, "area": area, "span": None if span in (None, GRID) else span,
                    "height": size(src.get("height"), MAX_HEIGHT), "hidden": bool(e and e.get("hidden"))}

        by_id = {p.get("id"): p for p in panels}
        out, seen = [], set()
        for e in (layout or {}).get("panels") or []:
            pid = e.get("id")
            if pid in by_id and pid not in seen:
                seen.add(pid)
                out.append(placed(by_id[pid], e))
        out.extend(placed(p, None) for p in panels if p.get("id") not in seen)
        if out and all(p["hidden"] for p in out):          # a layout never hides everything
            out = [{**p, "hidden": False} for p in out]
        return out

    @staticmethod
    def sutra_layout(panels: list[dict]) -> list[dict]:
        """The Sutra's own arrangement of the view's panels: what Reset in layout mode goes back to."""
        return [{"id": p.get("id"), "area": p.get("area") or "main", "span": p.get("span"), "height": p.get("height")} for p in panels]

    async def context(self, request, vm: dict, embed: bool) -> dict:
        """What a view needs: its panels arranged, and whether layout mode is offered (and if not, why)."""
        sutra = (vm.get("provenance") or {}).get("sutra")
        kind = (vm.get("ref") or {}).get("kind", "")
        state = await self.state(request.app.state.backend, getattr(request.state, "identity", None))
        mine = self.find(state, sutra, kind) if state.get("allowed") else None
        panels = self.apply(vm.get("panels") or [], mine)
        why = ""
        if not state.get("allowed"):
            why = ("You do not have access: customising layouts needs a role with layout (ask an administrator)"
                   if state.get("enabled", True) else "Layouts are switched off on this server")
        elif not sutra:
            why = "This view is laid out by inference alone: there is no Sutra whose layout you could arrange"
        return {"panels": panels, "offered": self.enabled and not embed and state.get("enabled", True) is not False,
                "allowed": bool(state.get("allowed")) and bool(sutra), "why": why, "sutra": sutra or "", "kind": kind,
                "mine": mine is not None, "promote": bool(state.get("promote")) and bool(sutra), "review": bool(state.get("review")),
                "base": self.sutra_layout(vm.get("panels") or []), "min_span": self.min_span}
