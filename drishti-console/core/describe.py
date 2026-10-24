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

"""The Sutra designer's Describe mode (SCREEN_DESIGNER.md section 31): a screen typed the way a command is typed, read by a DETERMINISTIC parser (no AI).

    TRD BOOK-RATES-2 · click a trade → cash flows + CPTY limits · refresh 30s

Clauses are separated by ``·`` (or ``|`` or ``;``). The first names what the screen is about: a command mnemonic of the enabled packs (the
command line's table: ``TRD``, ``CPTY``, ``PTRD``...) or a kind's plain name, then an id. ``click a <row> → A + B`` opens A and B for the
clicked row (``->``, ``=>`` and ``shows`` work too; ``below``, ``beside``, ``under the row`` or ``as tabs`` at the end says where);
``refresh Ns`` (or ``Nm``) sets ``refresh:``: the page fetches its panels again on that interval (SUTRA_NEXT.md 9). Every clause becomes one token of
"Understood as"; one the parser is unsure of carries the choices to fix it with, and anything it cannot read says so in plain words."""
from __future__ import annotations

import re
from dataclasses import dataclass, field

import yaml

SEPARATORS = re.compile(r"\s*[·|;]\s*")
ARROW = re.compile(r"\s*(?:→|->|=>|\bshows?\b|\bopens?\b)\s*", re.I)
CLICK = re.compile(r"^(?:click|clicking|pick|picking|select|selecting|tap)\s+(?:on\s+)?(?:a|an|the|one|any)?\s*(?P<row>[\w -]+?)\s*$", re.I)
REFRESH = re.compile(r"^(?:refresh|refreshed|update|reload)(?:\s+every)?\s+(?P<n>\d+)\s*(?P<unit>s|sec|secs|seconds?|m|min|mins|minutes?)?$", re.I)
PLACES = {"expand-row": ("under the row", "under row", "below the row", "in the row", "inline"),
          "detail-below": ("below", "underneath", "at the bottom"),
          "detail-right": ("beside", "on the right", "right", "to the right", "the right column", "in the right column", "aside"),
          "tabs": ("as tabs", "in tabs", "tabs", "tabbed")}
PLACE_WORDS = {"expand-row": "under the clicked row", "detail-below": "below", "detail-right": "in the right column", "tabs": "as tabs"}

# A list a subject's page holds: the kinds whose record is "the trades of" something, and the words for their owner.
TRADE_LISTS = {"portfolio-trades": ("book", "BOOK-"), "counterparty-trades": ("counterparty", "CP-"), "legal-entity-trades": ("legal entity", "LE-"),
               "index-trades": ("index", "IDX-")}
KIND_WORDS = {"trade": "trade", "trades": "trade", "deal": "trade", "deals": "trade", "book": "portfolio-trades", "books": "portfolio-trades",
              "portfolio": "portfolio-trades", "counterparty": "counterparty", "cpty": "counterparty", "curve": "ir-curve", "equity": "equity"}


@dataclass
class Show:
    """Something a click can open: its plain name, the words that name it, and the panel YAML (``child``: under the clicked panel;
    otherwise a panel of the screen that listens)."""
    key: str
    label: str
    words: tuple
    child: bool
    needs_row: str = "trade"


SHOWS = [
    Show("cash-flows", "Cash flows", ("cash flows", "cashflows", "cash-flows", "flows", "payments", "cash flow ladder"), True),
    Show("trade", "The trade's details", ("trade", "trade details", "trade's details", "details", "the trade", "fields", "deal"), True),
    Show("counterparty", "Counterparty", ("cpty", "counterparty", "the counterparty", "cpty details", "counterparty details"), False),
    Show("limits", "Counterparty limits", ("cpty limits", "counterparty limits", "credit limit", "credit limits", "cpty limit", "limit usage",
                                           "limits utilisation", "utilisation"), False),
]
LOOSE = {"limits": "limits", "limit": "limits", "cash": "cash-flows", "flow": "cash-flows", "risk": "limits", "credit": "limits"}


@dataclass
class Token:
    """One clause as understood: its plain words, what it is, and (when unsure) the choices that fix it."""
    clause: str
    text: str
    role: str                       # subject | click | show | refresh | error
    value: str = ""
    unsure: bool = False
    choices: list = field(default_factory=list)
    message: str = ""

    def as_dict(self) -> dict:
        return {"clause": self.clause, "text": self.text, "role": self.role, "value": self.value, "unsure": self.unsure,
                "choices": self.choices, "message": self.message}


@dataclass
class Parsed:
    tokens: list
    kind: str = ""
    id: str = ""
    row: str = ""
    shows: list = field(default_factory=list)          # [(Show, place)]
    refresh: int = 0

    @property
    def ok(self) -> bool:
        return bool(self.kind and self.id) and not any(t.role == "error" for t in self.tokens)

    @property
    def messages(self) -> list:
        return [t.message for t in self.tokens if t.message]


def aliases_of(packs: list) -> dict:
    """The command line's mnemonic table from the enabled packs (``{TRD: {kind, label}}``), whatever shape a pack lists it in."""
    out: dict = {}
    for p in packs or []:
        mn = p.get("mnemonics") if isinstance(p, dict) else None
        if isinstance(mn, dict):
            for code, v in mn.items():
                if isinstance(v, dict) and v.get("kind"):
                    out[str(code).upper()] = {"kind": v["kind"], "label": v.get("label") or v["kind"]}
        for k in (p.get("kinds") or []) if isinstance(p, dict) else []:
            if isinstance(k, dict) and k.get("mnemonic") and k.get("kind"):
                out.setdefault(str(k["mnemonic"]).upper(), {"kind": k["kind"], "label": k.get("label") or k["kind"]})
    return out


def _plain(kind: str) -> str:
    return kind.replace("-", " ")


def _subject(clause: str, aliases: dict) -> tuple[Token, str, str]:
    words = clause.split()
    if not words:
        return Token(clause, "nothing", "error", message="Say what the screen is about first, e.g. TRD BOOK-RATES-2"), "", ""
    head, rest = words[0], words[1:]
    code = head.upper()
    kind = (aliases.get(code) or {}).get("kind") or KIND_WORDS.get(head.lower(), "")
    if not kind:
        if len(words) == 1 and re.match(r"^[A-Z]+-[\w-]+$", head):          # a bare id: its prefix says what it is
            for k, (_, prefix) in TRADE_LISTS.items():
                if head.upper().startswith(prefix):
                    return Token(clause, f"Trades of {TRADE_LISTS[k][0]} {head}", "subject", k, unsure=True,
                                 choices=[f"TRD {head}", f"CPTY {head}"], message=f"I read {head} as a {TRADE_LISTS[k][0]}: is that right?"), k, head
        known = ", ".join(sorted(aliases)[:12]) or "TRD, CPTY"
        return Token(clause, clause, "error", message=f"I do not know '{head}'. Start with a command word such as {known}."), "", ""
    if not rest:
        return Token(clause, f"{_plain(kind).capitalize()}: which one?", "error",
                     message=f"Say which {_plain(kind)}: {head} followed by its id, e.g. {head} BOOK-RATES-2"), "", ""
    ident = rest[0]
    if kind == "trade":                                                   # TRD BOOK-…: the trades OF a book, counterparty or legal entity
        for k, (owner, prefix) in TRADE_LISTS.items():
            if ident.upper().startswith(prefix):
                return Token(clause, f"Trades of {owner} {ident}", "subject", k), k, ident
        if not re.match(r"^(MX|CLY)-\d+$", ident.upper()):
            return Token(clause, f"Trade {ident}", "subject", "trade", unsure=True,
                         choices=[f"PTRD {ident}", f"CTRD {ident}"],
                         message=f"{ident} does not look like a trade id: did you mean the trades of a book or a counterparty?"), "trade", ident
    label = (aliases.get(code) or {}).get("label") or _plain(kind).capitalize()
    return Token(clause, f"{label} {ident}", "subject", kind), kind, ident


def _place(text: str) -> tuple[str, str]:
    low = text.lower().strip()
    for place, words in PLACES.items():
        for w in sorted(words, key=len, reverse=True):
            if low.endswith(" " + w) or low == w:
                return low[: len(low) - len(w)].strip(), place
    return low, ""


def _show(phrase: str) -> tuple[Show | None, bool]:
    """The show a phrase names, and whether that was a guess (one loose word)."""
    p = re.sub(r"\s+", " ", phrase.lower().strip(" .,"))
    p = re.sub(r"^(?:the|its|their|a|an)\s+", "", p)
    for s in SHOWS:
        if p in s.words or p == s.label.lower():
            return s, False
    for word in p.split():
        if word in LOOSE:
            return next(s for s in SHOWS if s.key == LOOSE[word]), True
    return None, False


def parse(line: str, aliases: dict) -> Parsed:
    """The line as tokens, the subject, the clicked row's noun, the shows with their places and the refresh."""
    clauses = [c for c in SEPARATORS.split((line or "").strip()) if c.strip()]
    if not clauses:
        return Parsed([Token("", "nothing yet", "error", message="Describe the screen, e.g. TRD BOOK-RATES-2 · click a trade → cash flows")])
    out = Parsed([])
    tok, out.kind, out.id = _subject(clauses[0], aliases)
    out.tokens.append(tok)
    for clause in clauses[1:]:
        m = REFRESH.match(clause.strip())
        if m:
            n = int(m.group("n")) * (60 if (m.group("unit") or "s").lower().startswith("m") else 1)
            if not 1 <= n <= 86400:
                out.tokens.append(Token(clause, clause, "error", message="Refresh between 1 second and a day"))
                continue
            out.refresh = n
            out.tokens.append(Token(clause, f"refresh every {n} s", "refresh", str(n),
                                    message="" if n >= 5 else "Refreshing more often than every 5 seconds is heavy for everyone"))
            continue
        parts = ARROW.split(clause, maxsplit=1)
        click = CLICK.match(parts[0].strip()) if len(parts) == 2 else None
        if not click:
            out.tokens.append(Token(clause, clause, "error", message=f"I did not understand '{clause}'. Try: click a trade → cash flows"))
            continue
        row = click.group("row").strip().lower().rstrip("s") or "row"
        if out.kind not in TRADE_LISTS:
            out.tokens.append(Token(clause, clause, "error",
                                    message=f"A {_plain(out.kind) or 'screen'} page has no list of trades to click: start with TRD and a book, e.g. TRD BOOK-RATES-2"))
            continue
        if row not in ("trade", "row", "deal", "line"):
            out.tokens.append(Token(clause, f"click a {row}", "error", message=f"This screen lists trades: say 'click a trade', not '{row}'"))
            continue
        out.row = "trade"
        rest, place = _place(parts[1])
        for phrase in [p for p in re.split(r"\s*(?:\+|,|\band\b|&)\s*", rest) if p.strip()]:
            phrase, own = _place(phrase)
            s, guessed = _show(phrase)
            if s is None:
                out.tokens.append(Token(phrase, phrase, "error", unsure=True, choices=[x.label for x in SHOWS],
                                        message=f"I do not know how to show '{phrase}' for a clicked trade: pick one of the choices"))
                continue
            where = own or place or ("expand-row" if s.key == "cash-flows" else "detail-right")
            if not s.child:
                where = "detail-right"                                   # a listener panel sits in the right column
            out.shows.append((s, where))
            out.tokens.append(Token(phrase, f"click a row → {s.label}" + ("" if where == "detail-right" and not s.child else f" ({PLACE_WORDS[where]})"),
                                    "show", s.key, unsure=guessed, choices=[x.label for x in SHOWS] if guessed else [],
                                    message=f"I read '{phrase}' as {s.label}" if guessed else ""))
    return out


def _with_picker(kind: str) -> dict:
    return {"id": "trades", "title": "Trades", "rows": "$.TRADES", "event": "open", "key": "TRADE_ID", "key-label": "Trade", "ref": "COUNTERPARTY",
            "a": "PRODUCT_TYPE", "a-label": "Product", "b": "COUNTERPARTY", "b-label": "Counterparty", "amount": "NOTIONAL",
            "amount-label": "Notional", "date": "MATURITY_DATE", "date-label": "Maturity", "empty": "No trades", "code": "TRD"}


def _child(s: Show) -> dict:
    src = {"pack": "trading", "kind": "trade", "id": "@parent.open.id"}
    if s.key == "cash-flows":
        return {"source": src, "panels": [{"use": "cashflow-ladder@1", "with": {"id": "flows", "title": "Cash flows of ${@parent.open.id}",
                                                                              "rows": "$src.EVENTS", "date": "EVENT_DATE", "type": "EVENT_TYPE", "amount": "AMOUNT"}}]}
    return {"source": src, "panels": [{"use": "field-list@1", "with": {"id": "trade-fields", "title": "Trade ${@parent.open.id}", "rows": "$src"}}]}


def _listeners(s: Show) -> list:
    wait = "Pick a trade to see its counterparty" if s.key == "counterparty" else "Pick a trade to see its counterparty's limits"
    if s.key == "counterparty":
        return [{"id": "counterparty", "kind": "kv", "title": "Counterparty", "area": "right", "waits": wait,
                 "source": {"pack": "core", "kind": "counterparty", "id": "@trades.open.ref"},
                 "columns": [{"label": "Name", "bind": "$src.NAME"}, {"label": "Rating", "bind": "$src.RATING"},
                             {"label": "Country", "bind": "$src.COUNTRY"}]}]
    return [{"id": "limit", "kind": "kv", "title": "Counterparty limits", "area": "right", "waits": wait,
             "source": {"pack": "counterparty-risk", "kind": "credit-limit", "where": "@.COUNTERPARTY == @trades.open.ref", "limit": 1},
             "columns": [{"label": "Counterparty", "bind": "$src[0].COUNTERPARTY"}, {"label": "Limit", "bind": "$src[0].LIMIT", "fmt": "compact"},
                         {"label": "Used", "bind": "$src[0].USED", "fmt": "compact"},
                         {"label": "Utilisation", "bind": "$src[0].UTILISATION", "fmt": "pct0"},
                         {"label": "Status", "bind": "$src[0].STATUS", "tone": "status"}]}]


def slug(text: str) -> str:
    s = re.sub(r"[^a-z0-9]+", "-", (text or "").lower()).strip("-")[:40].strip("-")
    return s if len(s) >= 2 else "my-screen"


def sutra(p: Parsed, line: str, name: str = "") -> dict:
    """The Sutra the parse describes, as a document (a fragment for each part; the YAML is :func:`dump`'s)."""
    doc: dict = {"rachana": 1, "sutra": slug(name or f"my-{p.kind}-{p.id}"), "version": 1, "description": line.strip()[:300],
                 "match": {"kind": p.kind}}
    if p.refresh:
        doc["refresh"] = p.refresh
    if p.kind in TRADE_LISTS:
        owner = TRADE_LISTS[p.kind][0]
        doc["title"] = {"pill": f"Trades of {owner}", "id": "$.PORTFOLIO" if p.kind == "portfolio-trades" else "$.id"}
        doc["strip"] = [{"label": "Trades", "bind": "$.TRADE_COUNT", "emphasis": True}, {"label": "Notional", "bind": "$.NOTIONAL", "fmt": "compact"}]
        picker: dict = {"use": "row-picker@1", "with": _with_picker(p.kind)}
        kids, panels = [], []
        for s, where in p.shows:
            if s.child:
                kids.append({"place": where, "waits": f"Pick a trade to see its {s.label.lower()}", **_child(s)})
            elif not any(x.get("id") == s.key or x.get("id") == ("limit" if s.key == "limits" else s.key) for x in panels):
                panels += _listeners(s)
        if kids:
            picker["children"] = kids
        doc["panels"] = [picker, *panels, {"use": "data-provenance@1"}]
    else:
        doc["title"] = {"pill": _plain(p.kind).capitalize(), "id": "$.id"}
        doc["panels"] = [{"use": "field-list@1", "with": {"id": "fields", "title": "Details"}}, {"use": "linked-entities@1"},
                         {"use": "data-provenance@1"}]
    return doc


HEADER = "# My screen, written by the Sutra designer (Describe). Edit it in Build, or describe it again.\n"


def dump(doc: dict) -> str:
    return HEADER + yaml.safe_dump(doc, sort_keys=False, allow_unicode=True, width=140)
