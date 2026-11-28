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

"""The role Home's cards (docs/architecture/ROLE_HOME_SUTRAS.md, "Your Home"): which cards a person is offered, in which order, and the
ViewModel the Drishti Elements painter draws from them.

Nothing here computes a risk figure or draws a widget. A card is a declaration (a module's ``home: role-cards:`` in its console
``plugin.yaml``, or the common ones of ``config/home-cards.yaml``) over a *home table* the server serves for the person (rows already under
their row rules and masks); this module orders the declarations for the person, applies their own pins, hidden cards and order, and writes
the result as a ViewModel (contract 1.2: panels, a layout picture, a phone order, panel actions). Everything per person is a function
argument: there is no module-level state, so nothing one person arranges can reach another.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from pathlib import Path
from zoneinfo import ZoneInfo

import yaml

MAX_PINS = 8
SNOOZE_HOURS = 4
KINDS = ("metric", "bars", "table")
LAYOUTS = ("calm", "cockpit")
CONFIG = Path(__file__).resolve().parent.parent / "config" / "home-cards.yaml"


@dataclass(frozen=True)
class Card:
    """One card a module (or the console) offers. ``rank`` is the weight per role Home (``*``: for any Home); a card with no weight for the
    person's Homes is left out unless it has a ``*`` weight."""
    id: str
    title: str
    module: str
    kind: str = "table"
    table: str = ""
    pick: dict = field(default_factory=dict, hash=False)     # row filter: column -> value (a metric card is one row of a table)
    columns: tuple = ()                                      # table: ({label, col, fmt?})
    name: str = ""                                           # bars: the name column, the value column, the limit column
    value: str = ""
    limit: str = ""
    fmt: str = "compact"
    worse: str = "up"                                        # which way is bad news: up, down or none (the delta's tone)
    open: str = ""                                           # the command the card's "Open" runs
    explain: dict = field(default_factory=dict, hash=False)  # the Explain request of the card's figure (the existing Explain opens it)
    rank: dict = field(default_factory=dict, hash=False)
    limit_rows: int = 6
    light: tuple = ()                                        # a traffic light on the figure: (green up to, amber up to), red above
    empty: str = "Nothing to show yet"                      # the words of a table with no rows
    always: bool = False                                     # drawn with its own empty words when the table has no rows

    def weight(self, homes: list[str]) -> int | None:
        w = [self.rank[h] for h in homes if h in self.rank]
        if w:
            return max(w)
        return self.rank.get("*")


def _card(raw: dict, module: str) -> Card | None:
    if not isinstance(raw, dict) or not raw.get("id") or not raw.get("title"):
        return None
    kind = raw.get("kind") if raw.get("kind") in KINDS else "table"
    cols = tuple({"label": str(c.get("label") or c.get("col")), "col": str(c.get("col")), "fmt": str(c.get("fmt") or ""), "link": str(c.get("link") or "")}
                 for c in raw.get("columns") or [] if isinstance(c, dict) and c.get("col"))
    return Card(id=str(raw["id"]), title=str(raw["title"]), module=module, kind=kind, table=str(raw.get("table") or ""), pick=dict(raw.get("pick") or {}),
                columns=cols, name=str(raw.get("name") or ""), value=str(raw.get("value") or ""), limit=str(raw.get("limit") or ""),
                fmt=str(raw.get("fmt") or "compact"), worse=str(raw.get("worse") or "up"), open=str(raw.get("open") or ""),
                explain=dict(raw.get("explain") or {}), rank={str(k): int(v) for k, v in (raw.get("rank") or {}).items()},
                limit_rows=int(raw.get("rows") or 6), light=tuple(raw.get("light") or ()), always=bool(raw.get("always")), empty=str(raw.get("empty") or "Nothing to show yet"))


def common_cards(path: Path = CONFIG) -> tuple[list[Card], dict]:
    """The console's own cards (data loads and the like) and the cockpit Homes: ``config/home-cards.yaml``."""
    try:
        doc = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    except (OSError, yaml.YAMLError):
        doc = {}
    return [c for c in (_card(x, "") for x in doc.get("cards") or []) if c], {str(k): str(v) for k, v in (doc.get("layouts") or {}).items()}


def catalogue(parts: list[dict], common: list[Card]) -> list[Card]:
    """Every card on offer: the common ones, then each loaded module's (``parts``: ``{module, role_cards}`` of the module parts)."""
    out = list(common)
    seen = {c.id for c in out}
    for p in parts:
        for raw in p.get("role_cards") or []:
            c = _card(raw, str(p.get("module") or ""))
            if c and c.id not in seen:
                seen.add(c.id)
                out.append(c)
    return out


def layout_for(homes: list[str], declared: dict) -> str:
    """Calm for most roles; Cockpit when any Home the person has is declared a cockpit Home (``layouts: {risk-manager: cockpit}``)."""
    return "cockpit" if any(declared.get(h) == "cockpit" for h in homes) else "calm"


def ranked(cards: list[Card], homes: list[str], prefs: dict) -> list[Card]:
    """The person's cards, best first: the role ranking (the best weight over every Home the person has, so several roles are ranked
    together), then their own pins on top and their own order among the rest; hidden ones removed."""
    hidden = set(prefs.get("hidden") or [])
    pins = [p for p in prefs.get("pins") or [] if isinstance(p, str)]
    own = [p for p in prefs.get("order") or [] if isinstance(p, str)]
    scored = [(c, c.weight(homes)) for c in cards]
    scored = [(c, w) for c, w in scored if w is not None and c.id not in hidden]
    scored.sort(key=lambda cw: (-cw[1], cw[0].id))
    byid = {c.id: c for c, _ in scored}
    order: list[str] = [p for p in pins if p in byid]
    order += [o for o in own if o in byid and o not in order]
    order += [c.id for c, _ in scored if c.id not in order]
    return [byid[i] for i in order]


def hidden_cards(cards: list[Card], homes: list[str], prefs: dict) -> list[Card]:
    """The cards the person hid that their Homes still offer (so the page can bring them back)."""
    hid = set(prefs.get("hidden") or [])
    return [c for c in cards if c.id in hid and c.weight(homes) is not None]


# ---- per-person preferences -----------------------------------------------------------------------------------------------------

def clean(prefs) -> dict:
    """What the server stores as ``homeCards``: lists of card ids and the snooze times, nothing else."""
    p = prefs if isinstance(prefs, dict) else {}
    out = {k: [x for x in p.get(k) or [] if isinstance(x, str)] for k in ("pins", "hidden", "order")}
    out["snooze"] = {k: v for k, v in (p.get("snooze") or {}).items() if isinstance(k, str) and isinstance(v, str)}
    if isinstance(p.get("lastVisit"), str):
        out["lastVisit"] = p["lastVisit"]
    return out


def apply(prefs: dict, action: str, card: str = "", now: datetime | None = None, keys: list[str] | None = None, visible: list[str] | None = None) -> dict:
    """The person's preferences after one action (a new dict; the argument is not changed): pin, unpin, hide, show, up, down, snooze, reset.
    ``visible`` is the order the person sees (what up and down move within)."""
    p = clean(prefs)
    now = now or datetime.now(timezone.utc)
    if action == "reset":
        return {"pins": [], "hidden": [], "order": [], "snooze": {}, **({"lastVisit": p["lastVisit"]} if "lastVisit" in p else {})}
    if action == "snooze":
        until = (now + timedelta(hours=SNOOZE_HOURS)).strftime("%Y-%m-%dT%H:%M:%SZ")
        for k in keys or []:
            p["snooze"][k] = until
        return p
    if action == "visited":
        p["lastVisit"] = now.strftime("%Y-%m-%dT%H:%M:%SZ")
        return p
    if action == "pin":
        p["pins"] = [x for x in p["pins"] if x != card] + [card]
        p["pins"] = p["pins"][-MAX_PINS:]
    elif action == "unpin":
        p["pins"] = [x for x in p["pins"] if x != card]
    elif action == "hide":
        p["hidden"] = [x for x in p["hidden"] if x != card] + [card]
        p["pins"] = [x for x in p["pins"] if x != card]
    elif action == "show":
        p["hidden"] = [x for x in p["hidden"] if x != card]
    elif action in ("up", "down"):
        seq = [x for x in (visible or []) if x not in p["hidden"]]
        within = [x for x in seq if (x in p["pins"]) == (card in p["pins"])]   # a pinned card moves among the pinned, any other among the rest
        if card in within:
            i = within.index(card)
            j = i - 1 if action == "up" else i + 1
            if 0 <= j < len(within):
                within[i], within[j] = within[j], within[i]
            if card in p["pins"]:
                p["pins"] = within
            else:
                p["order"] = within
    return p


def awake(prefs: dict, key: str, now: datetime) -> bool:
    """Whether an item or the strip is not snoozed at ``now``."""
    until = (prefs.get("snooze") or {}).get(key)
    try:
        return not until or datetime.fromisoformat(until.replace("Z", "+00:00")) <= now
    except ValueError:
        return True


# ---- text -----------------------------------------------------------------------------------------------------------------------

def fmt(v, how: str = "compact") -> str:
    """A figure as the person reads it (the server's number, only written in words here)."""
    if v is None or v == "":
        return "-"
    if isinstance(v, bool) or not isinstance(v, (int, float)):
        return str(v)
    a = abs(v)
    if how == "pct0":
        return f"{v * 100:.0f}%"
    if how == "amount0":
        return f"{v:,.0f}"
    if how == "text":
        return str(v)
    for div, suffix in ((1e9, "B"), (1e6, "M"), (1e3, "K")):
        if a >= div:
            scaled = v / div
            return f"{scaled:.0f}{suffix}" if abs(scaled) >= 100 else f"{scaled:.1f}{suffix}" if abs(scaled) >= 10 else f"{scaled:.2f}{suffix}"
    return f"{v:,.0f}" if float(v).is_integer() else f"{v:,.2f}"


def greeting(name: str, zone: str | None = None, now: datetime | None = None) -> str:
    """Good morning, afternoon or evening, by the person's own clock (their ``clockZone`` setting, else UTC)."""
    try:
        tz = ZoneInfo(zone) if zone else timezone.utc
    except Exception:  # noqa: BLE001 - an unknown zone is UTC
        tz = timezone.utc
    hour = (now or datetime.now(timezone.utc)).astimezone(tz).hour
    part = "morning" if hour < 12 else "afternoon" if hour < 17 else "evening"
    return f"Good {part}, {name}" if name else f"Good {part}"


# ---- the ViewModel --------------------------------------------------------------------------------------------------------------

def _cell(text, tone: str = "", link: dict | None = None, **more) -> dict:
    c = {"text": str(text)}
    if tone:
        c["tone"] = tone
    if link:
        c["link"] = link
    c.update(more)
    return c


def _go(command: str) -> dict:
    """A link the page resolves: a typed command (opened as ``/go``)."""
    return {"kind": "command", "id": command, "mnemonic": None}


def _href(path: str) -> dict:
    return {"kind": "href", "id": path, "mnemonic": None}


def _action(id_: str, label: str, action: str, card: str = "", tone: str = "", **with_) -> dict:
    a = {"id": id_, "label": label, "effects": [{"do": "host", "action": action, "with": {"card": card, **with_}}]}
    if tone:
        a["tone"] = tone
    return a


def _link(col: dict, row: dict) -> dict | None:
    """A cell's link, when its column says so: ``entity`` (the row's KIND and ID) or ``command`` (the cell's own text is a command)."""
    if col.get("link") == "entity" and row.get("KIND") and row.get("ID"):
        return {"kind": str(row["KIND"]), "id": str(row["ID"]), "mnemonic": None}
    if col.get("link") == "command" and row.get(col["col"]):
        return _go(str(row[col["col"]]))
    return None


def _rows(card: Card, rows: list[dict]) -> list[dict]:
    if card.pick:
        rows = [r for r in rows if all(str(r.get(k)) == str(v) for k, v in card.pick.items())]
    return rows


def _tone_use(use: float | None) -> str:
    return "" if use is None else "bad" if use >= 0.95 else "warn" if use >= 0.8 else "ok"


def panel(card: Card, rows: list[dict], *, pinned: bool, first: bool, last: bool) -> dict | None:
    """One card as a panel (``None``: nothing to show and the card is not ``always``). The panel's actions are the person's own: pin, move,
    hide, and the card's Open and Explain."""
    rows = _rows(card, rows)
    if not rows and not card.always:
        return None
    acts = []
    if card.explain:
        acts.append(_action(f"{card.id}-explain", "Explain", "explain", card.id, **{"request": card.explain}))
    if card.open:
        acts.append(_action(f"{card.id}-open", "Open", "open", card.id, command=card.open))
    acts.append(_action(f"{card.id}-pin", "Unpin" if pinned else "Pin", "unpin" if pinned else "pin", card.id))
    if not first:
        acts.append(_action(f"{card.id}-up", "Up", "up", card.id))
    if not last:
        acts.append(_action(f"{card.id}-down", "Down", "down", card.id))
    acts.append(_action(f"{card.id}-hide", "Hide", "hide", card.id))
    p = {"id": card.id, "kind": "hbar" if card.kind == "bars" else card.kind, "title": card.title, "actions": acts, "area": card.id}
    if card.kind == "metric":
        r = rows[0]
        v, d = r.get(card.value or "VALUE"), r.get("DELTA")
        tone = ""
        if isinstance(d, (int, float)) and d and card.worse != "none":
            tone = "warn" if (d > 0) == (card.worse == "up") else "ok"
        light = ("ok" if v <= card.light[0] else "warn" if v <= card.light[1] else "bad") if len(card.light) == 2 and isinstance(v, (int, float)) else ""
        p["data"] = {"value": _cell(fmt(v, card.fmt), light, emphasis=True),
                     "delta": _cell(("+" if d > 0 else "") + fmt(d, card.fmt), tone) if isinstance(d, (int, float)) and d else None,
                     "unit": None, "caption": str(r.get("CAPTION") or "") or None}
    elif card.kind == "bars":
        bars = []
        for r in rows[:card.limit_rows]:
            v, lim = r.get(card.value), r.get(card.limit)
            use = v / lim if isinstance(v, (int, float)) and isinstance(lim, (int, float)) and lim else None
            text = fmt(v, card.fmt) + (f" of {fmt(lim, card.fmt)} ({use * 100:.0f}%)" if use is not None else "")
            bars.append({"label": str(r.get(card.name)), "value": use if use is not None else (v if isinstance(v, (int, float)) else 0), "text": text,
                         "tone": _tone_use(use) or None})
        p["data"] = {"bars": bars}
    else:
        cols = card.columns or tuple({"label": k, "col": k, "fmt": ""} for k in (rows[0].keys() if rows else ()))
        p["data"] = {"columns": [c["label"] for c in cols],
                     "numeric": [any(isinstance(r.get(c["col"]), (int, float)) for r in rows) for c in cols],
                     "rows": [{"cells": [_cell(fmt(r.get(c["col"]), c["fmt"] or ("compact" if isinstance(r.get(c["col"]), (int, float)) else "text")), link=_link(c, r))
                                         for c in cols], "highlight": False, "path": None} for r in rows[:card.limit_rows]],
                     "search": False, "emptyLabel": card.empty}
    return p


def _unit(card: Card, layout: str) -> int:
    """A card's width in the layout's grid: Calm is six columns, Cockpit twelve."""
    if layout == "cockpit":
        return 4 if card.kind in ("metric", "bars") else 6
    return 3


def picture(blocks: list[tuple[str, int]], width: int) -> list[str]:
    """Rows of the layout picture: each block ``(panel id, columns)`` flows left to right, a row is filled to ``width`` (the last block of a
    row takes what is left, a lone block the whole row)."""
    rows, row, used = [], [], 0
    for pid, w in blocks:
        w = min(w, width)
        if used + w > width and row:
            rows.append(row)
            row, used = [], 0
        row.append([pid, w])
        used += w
    if row:
        rows.append(row)
    out = []
    for r in rows:
        extra = width - sum(w for _, w in r)
        r[-1][1] += extra
        out.append(" ".join(" ".join([pid] * w) for pid, w in r))
    return out


def build_view(*, title: str, role: str, layout: str, strip: list[dict], needs: list[dict], digest: dict | None, cards: list[tuple[Card, dict | None]],
               hidden: list[Card], pins: list[str], business_date: str | None, stale: bool) -> dict:
    """The Home as a ViewModel. ``cards`` is the person's ranked cards with their rows already read (``(card, panel)``: a card whose panel is
    ``None`` is left out). ``needs`` holds the Needs-you rows (empty: the strip is not in the document at all)."""
    width = 12 if layout == "cockpit" else 6
    panels: list[dict] = []
    blocks: list[tuple[str, int]] = []
    if needs:
        panels.append({"id": "needs", "kind": "table", "title": "Needs you", "area": "needs",
                       "data": {"columns": ["What", "Detail"], "numeric": [False, False],
                                "rows": [{"cells": [_cell(n["what"], n.get("tone", ""), n.get("link")), _cell(n.get("detail", ""))], "highlight": n.get("tone") == "bad",
                                          "path": None} for n in needs], "search": False},
                       "actions": [_action("needs-snooze", f"Snooze for {SNOOZE_HOURS} hours", "snooze", "", keys=[n["key"] for n in needs])]})
        blocks.append(("needs", width))
    shown = [(c, p) for c, p in cards if p is not None]
    if layout == "cockpit":                                   # the figures first, as one dense row, then the working cards
        shown.sort(key=lambda cp: (cp[0].id not in pins, cp[0].kind != "metric"))      # (a pinned card stays on top of the figures)
    for c, p in shown:
        panels.append(p)
        blocks.append((c.id, _unit(c, layout)))
    if digest:
        panels.append(digest)
        blocks.append(("digest", width))
    if hidden or pins:
        rows = [{"cells": [_cell(c.title), _cell("hidden")], "highlight": False, "path": None} for c in hidden]
        panels.append({"id": "arrange", "kind": "table", "title": "Your Home", "area": "arrange",
                       "data": {"columns": ["Card", "State"], "numeric": [False, False], "rows": rows, "search": False,
                                "emptyLabel": "Every card is shown. Pin, move or hide a card from its own buttons."},
                       "actions": [_action(f"show-{c.id}", f"Show {c.title}", "show", c.id) for c in hidden]
                       + [_action("reset", "Reset my Home", "reset", "", confirm="Put the cards back in the order your role starts with?")]})
        blocks.append(("arrange", width))
    phone = [p["id"] for p in panels]
    return {"ref": {"kind": "home", "id": role}, "title": {"pill": role, "id": title, "with": None}, "strip": strip, "panels": panels, "keys": [],
            "provenance": {"layout": layout, "fingerprint": None, "source": "role Home", "generation": 0, "fetchedAt": None, "live": False,
                           "businessDate": business_date, "updatedAt": None, "staleAfter": None, "stale": False, "sutra": None, "masked": 0,
                           "maskedPanels": None},
            "timings": {}, "contract": "1.2",
            "layout": {"areas": picture(blocks, width), "columns": [1] * width, "phone": phone, "place": {p["id"]: {"area": p["id"]} for p in panels}, "density": "compact" if layout == "cockpit" else "comfortable"}}
