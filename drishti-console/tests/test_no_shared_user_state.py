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
"""No module of the console keeps state one person could read of another (docs/admin/USER_MANAGEMENT.md section 6). A module-level dict,
list or set in core/ or routes/ is either a constant table (an UPPERCASE name the module never changes after import: no item set, no
append, add, update, pop, clear...) or listed below with the reason it holds nothing of a person's, or is keyed by the person, server, roles
and as-of (core/user_cache.py). A new one fails here until it is moved to the app's state, keyed by core.user_cache, or listed with its
reason. ``contextvars.Context()`` is made only by the snapshot helper, core/request_context.py."""
from __future__ import annotations

import ast
from pathlib import Path

import pytest

from conftest import CONSOLE

pytestmark = pytest.mark.static   # a quick file check: the drill's gate runs it (TEST_HARNESS.md, "Quick file checks")

DIRS = ("core", "routes")
MUTABLE_CALLS = {"dict", "list", "set", "defaultdict", "OrderedDict", "deque", "Counter", "WeakValueDictionary", "WeakKeyDictionary", "LRU"}
CHANGES = {"append", "add", "update", "pop", "popitem", "clear", "setdefault", "extend", "insert", "remove", "discard", "move_to_end",
           "appendleft", "__setitem__", "sort"}

#: module-level mutable state, each with why it holds nothing one person could read of another
ALLOWED = {
    "core/menus.py:_cache": "the parsed sitemap.yaml by file stamp: configuration, the same for everyone",
    "core/external_lib.py:_paths": "folders added to sys.path for the console's own libraries: configuration",
    "core/channel_relay.py:HANDLERS": "relay operation to its handler function, registered at import by the routes (code, not data); "
                                      "what a relay holds is per app (app.state.relay)",
    "routes/embed_routes.py:_cache": "the embed loader's script text by version: static assets, the same for everyone",
    "routes/designer_routes.py:_LOCKS": "one asyncio.Lock per draft id (a person's own draft; a lock holds no data)",
    "core/trading_landing.py:_FLIGHT": "reads running now, by core.user_cache.user_key (server, user, roles, as-of): never served across people",
    "core/trading_landing.py:_CACHE": "an LRU keyed by core.user_cache.user_key (server, user, roles, as-of and known-at, lens)",
    "core/market_landing.py:_CACHE": "an LRU keyed by core.user_cache.cache_key (made by routes/edition_routes.py) or user_key",
    "core/ai/llm/registry.py:_PROVIDERS": "the provider classes registered at import (code, not data)",
    "core/ai/providers/__init__.py:FACTORIES": "the provider factories registered at import (code, not data)",
}


def _names(node) -> list[str]:
    return [t.id for t in (node.targets if isinstance(node, ast.Assign) else [node.target]) if isinstance(t, ast.Name)]


def _mutable(value) -> bool:
    if isinstance(value, (ast.Dict, ast.List, ast.Set, ast.DictComp, ast.ListComp, ast.SetComp)):
        return True
    if isinstance(value, ast.Call):
        fn = value.func
        return (fn.id if isinstance(fn, ast.Name) else fn.attr if isinstance(fn, ast.Attribute) else "") in MUTABLE_CALLS
    return False


def _changed(tree, name: str) -> bool:
    """True when the module changes ``name`` anywhere after import: an item set or deleted, a changing method called, a global rebound."""
    for n in ast.walk(tree):
        if isinstance(n, (ast.Assign, ast.AugAssign, ast.AnnAssign, ast.Delete)):
            for t in (n.targets if isinstance(n, (ast.Assign, ast.Delete)) else [n.target]):
                if isinstance(t, ast.Subscript) and isinstance(t.value, ast.Name) and t.value.id == name:
                    return True
        if isinstance(n, ast.Call) and isinstance(n.func, ast.Attribute) and n.func.attr in CHANGES:
            if isinstance(n.func.value, ast.Name) and n.func.value.id == name:
                return True
        if isinstance(n, ast.Global) and name in n.names:
            return True
    return False


def _module_state():
    for d in DIRS:
        for p in sorted((CONSOLE / d).rglob("*.py")):
            rel = p.relative_to(CONSOLE).as_posix()
            tree = ast.parse(p.read_text(encoding="utf-8"))
            for node in tree.body:
                if not isinstance(node, (ast.Assign, ast.AnnAssign)) or node.value is None or not _mutable(node.value):
                    continue
                for name in _names(node):
                    yield rel, name, tree, node.lineno


def test_every_module_level_mutable_is_a_constant_or_listed_with_its_reason():
    unlisted, constants = [], 0
    for rel, name, tree, line in _module_state():
        key = f"{rel}:{name}"
        if name == "__all__" or key in ALLOWED:
            continue
        if name.isupper() and not _changed(tree, name):
            constants += 1                                               # a constant table: read, never changed
            continue
        unlisted.append(f"{rel}:{line} {name}" + (" (an UPPERCASE name the module changes)" if name.isupper() else ""))
    assert not unlisted, ("module-level state that may hold a person's data: move it to the app's state keyed by core/user_cache.py, or "
                          "list it in ALLOWED with the reason it holds nothing of a person's:\n  " + "\n  ".join(unlisted))
    assert constants > 50                                                # the scan reads the console, not an empty folder


def test_every_listed_entry_still_exists():
    present = {f"{rel}:{name}" for rel, name, _, _ in _module_state()}
    assert not [k for k in ALLOWED if k not in present], "an ALLOWED entry no longer names module state: remove it"


#: the places that make a fresh contextvars.Context() (a context with nothing of the request's), each with its reason
FRESH_CONTEXTS = {
    "core/request_context.py": "the one snapshot helper (context(snap)): a fresh context holding only a request's snapshot, for the live "
                               "channel's subscriptions and work handed to another worker",
}


def test_a_fresh_context_is_made_only_in_the_listed_places():
    found = []
    for d in DIRS:
        for p in sorted((CONSOLE / d).rglob("*.py")):
            rel = p.relative_to(CONSOLE).as_posix()
            for n in ast.walk(ast.parse(p.read_text(encoding="utf-8"))):
                if isinstance(n, ast.Call) and isinstance(n.func, ast.Attribute) and n.func.attr == "Context" \
                        and isinstance(n.func.value, ast.Name) and n.func.value.id == "contextvars" and rel not in FRESH_CONTEXTS:
                    found.append(f"{rel}:{n.lineno}")
    assert not found, "a contextvars.Context() outside the snapshot helper: " + ", ".join(found)
