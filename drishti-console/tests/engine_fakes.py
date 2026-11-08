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

"""The tests' stand-in for the core client's one engine call (``BackendClient.engine``, DOMAIN_MODULES.md C7): a test writes its fake per
engine, as the engine sees the path (``var(method, "/scope", ident, **params)``), and this hands each call of the module's API
(``/risk/var/scope``) to it. ``patch`` puts the call on a real backend; ``method`` is the call for a fake backend class
(``engine = engine_fakes.method``)."""
from __future__ import annotations

#: (the module's prefix after /api/v1, the fake's name, what the fake's path starts with)
ROUTES = (("/risk/var", "var", ""), ("/risk/exposure", "exposure", ""), ("/risk/mr", "pivot", "/mr"), ("/risk/simm", "simm", "/simm"),
          ("/risk/frtb", "frtb", "/frtb"), ("/risk/sensitivities", "frtb", "/sensitivities"), ("/risk/risk-factors", "frtb", "/risk-factors"))


def _route(path: str):
    for prefix, name, keep in ROUTES:
        if path == prefix or path.startswith(prefix + "/") or path.startswith(prefix + "?"):
            return name, keep + path[len(prefix):]
    raise AssertionError(f"no fake engine for {path}")


async def _call(fns: dict, method_: str, path: str, ident, body=None, raw=False, accept="", timeout=None, **params):
    name, sub = _route(path)
    fn = fns.get(name)
    if fn is None:
        raise AssertionError(f"the test gave no fake {name} for {path}")
    kw = dict(params)
    if body is not None:
        kw["body"] = body
    if raw:
        kw["raw"] = True
    if accept:
        kw["accept"] = accept
    return await fn(method_, sub, ident, **kw)


def patch(monkeypatch, backend, **fns) -> None:
    """``backend.engine`` handing each module path to the fake of its engine (``var=``, ``exposure=``, ``frtb=``, ``simm=``, ``pivot=``)."""
    async def engine(method_, path, ident, /, body=None, raw=False, accept="", timeout=None, **params):
        return await _call(fns, method_, path, ident, body, raw, accept, timeout, **params)
    monkeypatch.setattr(backend, "engine", engine, raising=False)


async def method(self, method_, path, ident, /, body=None, raw=False, accept="", timeout=None, **params):
    """The engine call of a fake backend class: each module path to the class's own ``var``, ``exposure``, ``frtb``, ``simm`` or ``pivot``."""
    fns = {n: getattr(self, n) for n in ("var", "exposure", "frtb", "simm", "pivot") if hasattr(self, n)}
    return await _call(fns, method_, path, ident, body, raw, accept, timeout, **params)
