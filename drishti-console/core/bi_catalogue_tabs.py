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

"""The BI catalogue split by kind (``/bi/catalogue?kind=reports``): Reports · Boards · Models · Datasets · Python · All, each with its count.

The server's ``GET /api/v1/bi/catalogue`` answers one kind or every kind; the tabs need a count for each and a Python tab that holds two kinds (Python
items, which include Transforms, and notebooks). So the console asks once for every item that matches the search and filters (no kind) and once for
the boards (a board is its own kind or a report with board pages, which only the server can tell), then sorts each item into its one tab. The server's
order (certified first, then the chosen sort) is kept inside each tab. Nothing here decides access: the server has already left out what the person
may not see."""
from __future__ import annotations

# (tab key, label, the item kinds it holds); "all" holds every kind. A board kind or a report with board pages is under Boards, not Reports.
TABS = (("reports", "Reports", ("report",)), ("boards", "Boards", ("board",)), ("models", "Models", ("model",)),
        ("datasets", "Datasets", ("dataset",)), ("python", "Python", ("python", "notebook", "transform")), ("all", "All", ()))
DEFAULT_TAB = "reports"
_KIND_TAB = {kind: key for key, _label, kinds in TABS for kind in kinds}
# the singular kind names older links use (?kind=report, ?kind=python) still land on their tab
_ALIASES = {"report": "reports", "board": "boards", "model": "models", "dataset": "datasets", "notebook": "python", "transform": "python", "transforms": "python"}


def tab_key(asked: str | None) -> str:
    """The tab a ``kind=`` value names; anything else (missing, unknown) is the default tab."""
    k = (asked or "").strip().lower()
    k = _ALIASES.get(k, k)
    return k if k in {t[0] for t in TABS} else DEFAULT_TAB


def tab_of(item: dict, boards: set) -> str:
    """The one tab an item belongs to ("" for a kind the console does not know yet: it is shown under All only)."""
    if item.get("id") in boards:
        return "boards"
    return _KIND_TAB.get(str(item.get("kind") or ""), "")


def split(answer: dict, boards: set, tab: str) -> dict:
    """The server's catalogue answer (every kind) cut to one tab, with every tab's count and, for All, the items grouped by kind."""
    items = answer.get("items") or []
    by_tab: dict[str, list] = {key: [] for key, _l, _k in TABS}
    other = []
    for it in items:
        where = tab_of(it, boards)
        (by_tab[where] if where else other).append(it)
    by_tab["all"] = list(items)
    shown = by_tab[tab]
    out = {k: v for k, v in answer.items() if k not in ("items", "total")}
    out["tab"] = tab
    out["tabs"] = [{"key": key, "label": label, "count": len(by_tab[key])} for key, label, _k in TABS]
    out["items"] = shown
    out["total"] = len(shown)
    out["more"] = int(answer.get("total") or 0) > len(items)          # the server's list limit cut the answer: the counts are of what it sent
    if tab == "all":
        out["groups"] = [{"key": key, "label": label, "items": by_tab[key]} for key, label, _k in TABS if key != "all" and by_tab[key]]
        if other:
            out["groups"].append({"key": "other", "label": "Other", "items": other})
    return out


async def tabbed(backend, me, params: dict) -> dict:
    """``GET /bi/api/catalogue-tabs``: the catalogue for one tab. ``params`` are the page's (q, workspace, owner, endorsement, tag, fresh, sort, kind)."""
    tab = tab_key(params.get("kind"))
    ask = {k: v for k, v in params.items() if k != "kind" and v}
    answer = await backend.bi("GET", "/catalogue", me, **ask)
    boards = await backend.bi("GET", "/catalogue", me, **dict(ask, kind="board"))
    return split(answer, {it.get("id") for it in (boards.get("items") or [])}, tab)
