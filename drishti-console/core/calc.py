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

"""Calc, the console side (PYTHON_CALC.md): Python run in the browser, in Pyodide (CPython on WebAssembly) inside a Web
Worker. The console serves the runtime from its own origin, under ``/pyodide/<version>/`` (installed by
``tools/fetch-pyodide.sh``; never a CDN), decides whether a view offers Calc (a pack that owns the view's kind enables
it in ``pack.yaml``, and the user's roles include ``calc``), and lists the snippets packs ship for a kind. The code
itself never runs on the console or the server."""
from __future__ import annotations

import time
from pathlib import Path

from core.servers import scoped

STAMP = ".drishti-pyodide"
NOT_INSTALLED = "Python runtime not installed: run tools/fetch-pyodide.sh"


class Runtime:
    """The Pyodide folder: whether it is installed, which version, how big. Read once; the console restarts to change it."""

    def __init__(self, folder: Path):
        self.folder = folder
        stamp = folder / STAMP
        info = {}
        if stamp.exists() and (folder / "pyodide.mjs").exists() and (folder / "pyodide.asm.wasm").exists():
            for line in stamp.read_text(encoding="utf-8").splitlines():
                key, _, value = line.partition("=")
                info[key.strip()] = value.strip()
        self.version = info.get("version", "")
        self.packages = info.get("packages", "").split()
        self.installed = bool(self.version)
        self._size = None

    @property
    def base(self) -> str:
        """Where the browser loads it from: versioned, so it is cached for good and an upgrade is fetched at once."""
        return f"/pyodide/{self.version}/" if self.installed else ""

    @property
    def size_mb(self) -> float:
        if self._size is None:
            self._size = sum(f.stat().st_size for f in self.folder.iterdir() if f.is_file()) if self.installed else 0
        return round(self._size / 1e6, 1)

    def describe(self) -> dict:
        if not self.installed:
            return {"installed": False, "message": NOT_INSTALLED}
        return {"installed": True, "version": self.version, "sizeMb": self.size_mb, "packages": self.packages, "base": self.base}


class Calc:
    """Whether a view offers Calc to a user, and the snippets packs ship for its kind. Per-user permission is asked of
    the server (``GET /calc/settings``) and kept for a minute, like the user's packs."""

    TTL = 60.0

    def __init__(self, settings, web: Path):
        self.enabled = bool(settings.get("calc.enabled", True))
        self.runtime = Runtime(web / "static" / "vendor" / "pyodide")
        self._allowed: dict[str, tuple[float, dict]] = {}

    async def settings(self, backend, ident) -> dict:
        """The server's answer for this user: ``allowed`` (a role with calc, and Calc switched on) and its limits."""
        if ident is None:
            return {"allowed": False}
        key = scoped(ident.user)
        hit = self._allowed.get(key)
        if hit and time.monotonic() - hit[0] < self.TTL:
            return hit[1]
        try:
            data = await backend.calc_settings(ident)
        except Exception:  # noqa: BLE001 - an older server, or one away for a moment: Calc is not offered
            return {"allowed": False}
        self._allowed[key] = (time.monotonic(), data)
        return data

    @staticmethod
    def owner(packs: list[dict], kind: str) -> dict | None:
        """The active pack that owns the kind."""
        return next((p for p in packs if kind in (p.get("kinds") or [])), None)

    def offered(self, packs: list[dict], kind: str) -> bool:
        """Calc is offered on a kind's views when the pack that owns the kind enables it."""
        p = self.owner(packs, kind)
        return self.enabled and p is not None and bool((p.get("python") or {}).get("enabled"))

    @staticmethod
    def snippets(packs: list[dict], kind: str) -> list[dict]:
        """Every snippet the active packs that enable Calc ship for the kind: those naming it, and those naming no kind
        from the pack that owns it."""
        out = []
        for p in packs:
            py = p.get("python") or {}
            if not py.get("enabled"):
                continue
            for s in py.get("snippets") or []:
                kinds = s.get("kinds") or []
                if kind in kinds or (not kinds and kind in (p.get("kinds") or [])):
                    out.append({"title": s.get("title", ""), "description": s.get("description", ""), "code": s.get("code", ""),
                                "pack": p.get("title") or p.get("name"), "file": s.get("file", "")})
        return out

    async def context(self, request, packs: list[dict], kind: str) -> dict:
        """What a view's template needs: offered (the pack enables it), allowed (the user's roles), the runtime."""
        if not self.offered(packs, kind):
            return {"offered": False}
        s = await self.settings(request.app.state.backend, getattr(request.state, "identity", None))
        return {"offered": True, "allowed": bool(s.get("allowed")), "runtime": self.runtime.describe(),
                "maxColumnRows": s.get("maxColumnRows"), "pack": (self.owner(packs, kind) or {}).get("title", "")}

    def forget(self, user: str) -> None:
        self._allowed.pop(scoped(user), None)
