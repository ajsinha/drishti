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

"""Compute engines in the console (docs/guides/ADMIN_GUIDE.md section 5.2): an engine that runs on no server of the cluster
disappears everywhere: its command words, menu items, Help pages, Home cards, pages and playgrounds. What runs is learned from the server's
about answer (``engines``: ``[{id, title, here, cluster}]``, polled by core/backend_watch.py with the edition); what each engine brings to
the screen is the engine catalogue (config/engines.yaml, the server's engines/catalogue.yaml byte for byte, and each module's
plugins/<module>/engines.yaml, its jar's META-INF/drishti/engines.yaml). Until the server has said
(an older server, no answer yet) nothing is hidden. One helper, :func:`engine_available`, answers for every place; the others are built on it.
"""
from __future__ import annotations

import logging
from contextvars import ContextVar
from pathlib import Path

import yaml

LOG = logging.getLogger("drishti.console.engines")
CATALOGUE = Path(__file__).resolve().parent.parent / "config" / "engines.yaml"

_entries: list[dict] | None = None


class State:
    """What one console app has learned from its server: each engine's id -> runs somewhere in the cluster (``available``; None: not
    learned, nothing hidden) and -> runs on this server (``here``). One per app (its :class:`core.edition.Editions`), never shared: a
    process that holds several apps (the browser tests: a real stack's console beside stand-in ones) keeps each app's own answer."""

    __slots__ = ("available", "here")

    def __init__(self) -> None:
        self.available: dict[str, bool] | None = None
        self.here: dict[str, bool] = {}


_default = State()                                  # the newest app's: what code outside a request reads (one app per process in production)
_current: ContextVar[State | None] = ContextVar("drishti_console_engines", default=None)   # the app serving this request (core/app.py)


def new_state() -> State:
    """A new app's state; it becomes the process default (the newest app, as the edition is), a request reads its own app's."""
    global _default
    _default = State()
    return _default


def use(state: State | None):
    """Requests of the app whose state this is read it (set by core/app.py around every request); returns the token to reset."""
    return _current.set(state)


def reset_use(token) -> None:
    """Undoes :func:`use` at the end of the request."""
    _current.reset(token)


def state() -> State:
    """The state this code reads: the app serving the current request, else the newest app's."""
    s = _current.get()
    return s if s is not None else _default


def entries() -> list[dict]:
    """The catalogue's engines, in its order (read once): each module's own (``plugins/<module>/engines.yaml``, its jar's
    META-INF/drishti/engines.yaml byte for byte) first, then the core's (config/engines.yaml)."""
    global _entries
    if _entries is None:
        from core import modules

        out: list[dict] = []
        for f in [modules.PLUGINS / p.id / "engines.yaml" for p in modules.plugins()] + [CATALOGUE]:
            if not f.is_file():
                continue
            try:
                out += list((yaml.safe_load(f.read_text(encoding="utf-8")) or {}).get("engines") or [])
            except (OSError, yaml.YAMLError) as e:
                LOG.warning("engines: cannot read %s (%s); its engines are not hidden", f, e)
        _entries = out
    return _entries


def entry(engine_id: str) -> dict | None:
    return next((e for e in entries() if e.get("id") == engine_id), None)


def reset(into: State | None = None) -> None:
    """Forget what the server said (a new console process, a test's new app): nothing hidden until it says again."""
    s = into if into is not None else state()
    s.available = None
    s.here = {}


def learn(engines, into: State | None = None) -> bool:
    """The about answer's ``engines`` into an app's state (``into``: the app's own; else the current one); True when what is available
    changed. ``None`` (an older server): nothing is hidden."""
    s = into if into is not None else state()
    if engines is None:
        changed = s.available is not None
        reset(s)
        return changed
    avail = {str(e.get("id")): bool(e.get("cluster", e.get("here"))) for e in engines if isinstance(e, dict) and e.get("id")}
    here = {str(e.get("id")): bool(e.get("here")) for e in engines if isinstance(e, dict) and e.get("id")}
    changed = avail != s.available
    if changed and s.available is not None:
        LOG.warning("engines: now available %s; not available %s", sorted(k for k, v in avail.items() if v), sorted(k for k, v in avail.items() if not v))
    s.available, s.here = avail, here
    return changed


def engine_available(engine_id: str) -> bool:
    """Whether the engine runs on this server or on any server of the cluster (True until the server has said)."""
    s = state()
    if s.available is None:
        return True
    return s.available.get(engine_id, True)


def engine_here(engine_id: str) -> bool:
    return state().here.get(engine_id, engine_available(engine_id))


def off() -> list[dict]:
    """The catalogue entries of the engines available nowhere."""
    return [e for e in entries() if not engine_available(str(e.get("id")))]


def title(engine_id: str) -> str:
    e = entry(engine_id)
    return str((e or {}).get("title") or engine_id)


def _words(e: dict) -> list[str]:
    return [str(w).upper() for w in e.get("words") or []]


def word_hidden(word: str) -> bool:
    """A command word (FRSA, VAR) that belongs to an engine available nowhere. A two-word entry (PLAY VAR) hides only the phrase."""
    w = str(word or "").strip().upper()
    return bool(w) and any(w in _words(e) for e in off())


def typed_engine(text: str) -> dict | None:
    """The engine, available nowhere, whose words start the typed command (``PLAY FRTB EXAMPLE`` -> frtb-sa), or None."""
    return engine_of(text, off())


def engine_of(text: str, among: list[dict] | None = None) -> dict | None:
    """The engine (of ``among``, default every engine of the catalogue) whose command words start the typed text (``PLAY VAR BANK`` ->
    var, ``EXP CPTY`` -> exposure), or None."""
    parts = str(text or "").replace("<GO>", "").replace("<go>", "").upper().split()
    if not parts:
        return None
    for e in entries() if among is None else among:
        for w in _words(e):
            ws = w.split()
            if parts[:len(ws)] == ws:
                return e
    return None


def refusal(e: dict) -> str:
    """The words of an engine available nowhere, as the server says them (DRS-1020)."""
    return (f"DRS-1020 {e.get('title') or e.get('id')} is not running on this server, and no other server of this cluster runs it. "
            "An administrator can turn it on in Admin → Engines.")


def menu_hidden(item_id: str) -> bool:
    return any(item_id in (e.get("menus") or []) for e in off())


def help_hidden(slug: str) -> bool:
    return any(slug in (e.get("help") or []) for e in off())


def tile_hidden(title_: str) -> bool:
    return any(title_ in (e.get("tiles") or []) for e in off())


def lens_hidden(name: str) -> bool:
    return any(name in (e.get("lenses") or []) for e in off())


def path_engine(path: str) -> dict | None:
    """The engine available nowhere whose console page or API the path is (the longest prefix of any engine wins, so
    a longer prefix belongs to the engine that registers it); ``=<path>`` is that path only."""
    best, length = None, -1
    for e in entries():
        for p in e.get("console") or []:
            p = str(p)
            exact = p.startswith("=")
            p = p.lstrip("=")
            hit = path == p.rstrip("/") or path == p if exact else (path == p.rstrip("/") or path.startswith(p if p.endswith("/") else p + "/"))
            if hit and len(p) > length:
                best, length = e, len(p)
    return best if best is not None and not engine_available(str(best.get("id"))) else None
