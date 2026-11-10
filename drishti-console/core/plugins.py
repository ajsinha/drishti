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

"""The core's hooks a module's console code fills (docs/architecture/DOMAIN_MODULES.md section 5, the cycles C1 to C6): the core names
no module's code; a module registers what the core asks of it, and the core asks the registry.

* **routers** (C1): :func:`include` includes every module's routers, as its ``plugins/<module>/plugin.yaml`` lists them, and imports the
  files that register (``registers:``). A module's routes are always included; while the module is off they answer 404 (core/modules.py).
* **commands** (C2): a typed command's page (``VAR DESK RATES`` -> the VaR explorer), asked in registration order with the words the
  edition must keep (:func:`command`, :func:`redirect`); a retired stored record's link (:func:`retired`, :func:`retired_link`).
* **type-ahead** (C3): a word's own suggestions (:func:`type_ahead`, :func:`suggest`) and lines put before the server's (:func:`hints`).
* **presets** (C5): a Pivot preset's word grammar (``MCUBE BANK BY DESK``) as a partial view (:func:`preset`, :func:`preset_parser`).
* **landings** (C6): who sees which landing (:func:`landing_rule`, :func:`landing_by_rules`).

The workbook streamer (C4) is core/workbook_stream.py; the engines' typed client calls (C7) are the module's own, over the core client's
one generic call (``BackendClient.engine``). Every hook holds code, never a person's data (session isolation).
"""
from __future__ import annotations

import importlib
import logging
import re
from dataclasses import dataclass
from typing import Any, Callable

LOG = logging.getLogger("drishti.console.plugins")


@dataclass(frozen=True)
class _Command:
    fn: Callable[..., Any]
    needs: tuple[str, ...]
    user: bool


@dataclass(frozen=True)
class _TypeAhead:
    rx: re.Pattern
    needs: tuple[str, ...]
    fn: Callable[..., Any]


_commands: list[_Command] = []
_retired: list[Callable[[str, str], str | None]] = []
_type_ahead: list[_TypeAhead] = []
_hints: list[_TypeAhead] = []
_presets: dict[str, Callable[[list[str]], dict]] = {}
_landing_rules: list[Callable[..., str | None]] = []


def _once(registry: list, item) -> None:
    if item not in registry:                       # a module imported twice (a test's second app) registers once
        registry.append(item)


# ---- C1: the routers ---------------------------------------------------------------------------------------------------------------
def load() -> list[str]:
    """Imports every module's registering files (``registers:``: each fills its hooks once, on import); the module ids."""
    from core import modules

    for p in modules.plugins():
        for name in p.registers:
            importlib.import_module(name)
    return [p.id for p in modules.plugins()]


def include(app) -> list[str]:
    """Includes every module's routers in the order its plugin.yaml lists them, after its registering files; the module ids."""
    from core import modules

    done = load()
    for p in modules.plugins():
        for name in p.routers:
            app.include_router(importlib.import_module(name).router)
    return done


# ---- C2: commands and retired links ------------------------------------------------------------------------------------------------
def command(fn: Callable[..., Any], needs: tuple[str, ...] = (), user: bool = False) -> None:
    """A typed command's page: ``fn(typed)`` (``fn(typed, user)`` with ``user``) answers a response or None; asked only while the edition
    keeps every word of ``needs``."""
    _once(_commands, _Command(fn, tuple(needs), user))


def redirect(ed, typed: str, user: str):
    """The first registered command that answers the typed text, or None."""
    for c in _commands:
        if all(ed.word_on(w) for w in c.needs):
            out = c.fn(typed, user) if c.user else c.fn(typed)
            if out is not None:
                return out
    return None


def retired(fn: Callable[[str, str], str | None]) -> None:
    """A retired stored record's link: ``fn(kind, id)`` answers where it lives now, or None."""
    _once(_retired, fn)


def retired_link(kind: str, id_: str) -> str | None:
    for fn in _retired:
        if (to := fn(kind, id_)) is not None:
            return to
    return None


# ---- C3: the type-ahead ------------------------------------------------------------------------------------------------------------
def type_ahead(pattern: str, fn: Callable[..., Any], needs: tuple[str, ...] = ()) -> None:
    """A word's own suggestions: when the typed text matches ``pattern`` (case-insensitive) and the edition keeps ``needs``,
    ``fn(request, match)`` answers the whole list."""
    _once(_type_ahead, _TypeAhead(re.compile(pattern, re.I | re.S), tuple(needs), fn))


def suggest(request, ed, text: str):
    """The registered suggestions of the typed text, or None when no module's word matches."""
    for t in _type_ahead:
        m = t.rx.match(text)
        if m and all(ed.word_on(w) for w in t.needs):
            return t.fn(request, m)
    return None


def hints(pattern: str, fn: Callable[[str], list], needs: tuple[str, ...] = ()) -> None:
    """Lines put before the server's suggestions (a VAR command's horizons and confidences once a scope is typed): ``fn(q)``."""
    _once(_hints, _TypeAhead(re.compile(pattern, re.I | re.S), tuple(needs), fn))


def hint_lines(ed, q: str) -> list:
    out: list = []
    for t in _hints:
        if t.rx.match(q) and all(ed.word_on(w) for w in t.needs):
            out += t.fn(q)
    return out


# ---- C5: the Pivot's presets -------------------------------------------------------------------------------------------------------
def preset(lens: str, parse: Callable[[list[str]], dict]) -> None:
    """A Pivot preset's word grammar, by the preset's lens (``mr``): its words after the command as a partial view."""
    _presets[lens] = parse


def preset_parser(lens: str | None) -> Callable[[list[str]], dict] | None:
    return _presets.get(str(lens or ""))


# ---- C6: the landings --------------------------------------------------------------------------------------------------------------
def landing_rule(fn: Callable[..., str | None]) -> None:
    """Who sees which landing beyond the core's rules: ``fn(request, ed, offered, me, mine)`` answers a landing id or None."""
    _once(_landing_rules, fn)


def landing_by_rules(request, ed, offered: list[str], me, mine: dict) -> str | None:
    for fn in _landing_rules:
        if (to := fn(request, ed, offered, me, mine)) in offered:
            return to
    return None


# ---- C8: reserved words, console commands, phone pages, connector actions ---------------------------------------------------------------
@dataclass(frozen=True)
class _Word:
    word: str
    paths: tuple[str, ...]
    mnemonic: bool


_words: list[_Word] = []
_console_commands: list[tuple] = []
_phone_pages: dict[str, dict] = {}
_connector_actions: dict[str, list[dict]] = {}


def word(name: str, paths: tuple[str, ...] = (), mnemonic: bool = False) -> None:
    """A module's reserved command word and the console pages it opens (the edition switch and the 404 follow it). ``mnemonic``: it is
    also a pack's mnemonic, so an edition without it still runs it as the pack's (and a landing does not refuse it)."""
    _once(_words, _Word(name, tuple(paths), mnemonic))


def words() -> list[_Word]:
    load()
    return list(_words)


def console_command(word_: str, pack: str, label: str, what: str, example: str, kind: str, needs: tuple[str, ...] = ()) -> None:
    """A console command FUNC lists with a pack's, shown when the person's edition or lens has its first word and ``needs``."""
    _once(_console_commands, (word_, pack, label, what, example, kind, tuple(needs)))


_figure_explainers: list = []


def figure_explainer(rule: dict) -> None:
    """A module's figures that carry the one Explain › (drishti-insight's Explain.link): ``rule`` = {kind, pages: {page kind: scope word},
    paths: {strip path suffix: measure}, columns: {column: measure}, scope: [scope columns], date: date column}. The core draws the
    control from the rules alone (a Sutra page's strip, a BI visual of one row), never naming a module's measure."""
    _once(_figure_explainers, rule)


def figure_explainers() -> list[dict]:
    load()
    return list(_figure_explainers)


def figure_explain(vm, c) -> dict | None:
    """The Explain request of a Sutra page's strip cell ``c`` by the modules' rules, or None."""
    ref, path = (vm or {}).get("ref") or {}, (c or {}).get("path") or ""
    for r in figure_explainers():
        word = (r.get("pages") or {}).get(ref.get("kind"))
        for suffix, measure in (r.get("paths") or {}).items():
            if word and path.endswith(suffix):
                return {"kind": r["kind"], "measure": measure, "scope": f"{word}:{ref.get('id')}"}
    return None


def console_commands() -> list[tuple]:
    load()
    return list(_console_commands)


def phone_page(name: str, parts: dict) -> None:
    """A page's parts for the phone display (core/phone.py): part to (default tier, name in *More on this page*)."""
    _phone_pages[name] = parts


def phone_pages() -> dict[str, dict]:
    load()
    return dict(_phone_pages)


def connector_action(plugin: str, label: str, aria: str, title: str, url: str) -> None:
    """A button on Admin -> Connectors for a running connector of ``plugin``: POSTs ``url`` (``{name}``: the connector's) as the user."""
    _once(_connector_actions.setdefault(plugin, []), {"label": label, "aria": aria, "title": title, "url": url})


def connector_actions(plugin: str) -> list[dict]:
    load()
    return list(_connector_actions.get(str(plugin or ""), []))
