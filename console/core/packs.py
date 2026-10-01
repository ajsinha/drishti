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

"""The console side of domain packs: which packs are enabled (asked of the server, the single source of truth)
and what each contributes to the console: example commands, workspace starters and help guides, read from
``packs/<name>/``. Cached for a minute; falls back to ``packs.enabled`` when the server is unreachable."""
from __future__ import annotations

import time
from pathlib import Path

import yaml
from core.servers import scoped


class Packs:
    def __init__(self, settings, console_dir: Path):
        d = Path(settings.get("packs.dir", "../packs"))
        self.dir = d if d.is_absolute() else (console_dir / d).resolve()
        self.fallback = [p.strip() for p in str(settings.get("packs.enabled", "finance")).split(",") if p.strip()]
        self._cache: dict[str, tuple[float, list, list]] = {}

    async def current(self, backend, ident=None) -> list[dict]:
        """The user's active packs."""
        return (await self._fetch(backend, ident))[0]

    async def assigned(self, backend, ident=None) -> list[dict]:
        """Every pack assigned to the user, each with an ``active`` flag (for the switcher)."""
        return (await self._fetch(backend, ident))[1]

    def forget(self, ident=None) -> None:
        self._cache.pop(scoped(getattr(ident, "user", "")), None)

    def forget_all(self) -> None:
        """An admin switched a pack on or off: every user's packs change."""
        self._cache.clear()

    async def _fetch(self, backend, ident):
        key = scoped(getattr(ident, "user", ""))
        now = time.monotonic()
        hit = self._cache.get(key)
        if hit and now - hit[0] < 60:
            return hit[1], hit[2]
        try:
            rows = await backend.packs(ident)
            served = {r["name"]: r for r in rows}
            active = [r["name"] for r in rows if r.get("active", True)]
            assigned = [(r["name"], bool(r.get("active", True))) for r in rows if r.get("assigned", True)]
            fresh = True
        except Exception:  # noqa: BLE001 - any backend failure means "use the configured list"
            if hit:                          # the server is away for a moment (restarting): keep what we knew
                return hit[1], hit[2]
            active, assigned = self.fallback, [(n, True) for n in self.fallback]
            served = {}
            fresh = False
        packs = [self._served(p, served) for p in (self._load(n) for n in active) if p]
        switcher = [{**p, "active": a} for p, a in ((self._load(n), a) for n, a in assigned) if p]
        if fresh:                            # a fallback is never cached, so the real list returns at the next request
            self._cache[key] = (now, packs, switcher)
        return packs, switcher

    @staticmethod
    def _served(pack: dict, served: dict) -> dict:
        """What the server says of a pack beside its files: the kinds it owns and what it offers Calc (``python``: enabled,
        snippets from pack.yaml and the pack's python/ folder, read by the server's pack model)."""
        row = served.get(pack["name"]) or {}
        return {**pack, "kinds": row.get("kinds") or pack.get("kinds") or [],
                "python": row.get("python") or {"enabled": False, "snippets": []}}

    def _load(self, name: str) -> dict | None:
        pdir = (self.dir / name).resolve()
        manifest = pdir / "pack.yaml"
        if not str(pdir).startswith(str(self.dir)) or not manifest.exists():
            return None
        m = yaml.safe_load(manifest.read_text(encoding="utf-8")) or {}
        console = m.get("console", {}) or {}
        out = {"name": name, "title": m.get("title", name), "version": m.get("version", ""), "description": m.get("description", ""),
               "dir": pdir, "examples": console.get("examples", []), "workspaces": {}, "guides": [], "contextual": {},
               "monitors": console.get("monitors", {}) or {}, "alerts": m.get("alerts", []) or [], "kinds": m.get("kinds", []) or []}
        if console.get("workspaces") and (pdir / console["workspaces"]).exists():
            out["workspaces"] = (yaml.safe_load((pdir / console["workspaces"]).read_text()) or {}).get("templates", {}) or {}
        if console.get("help") and (pdir / console["help"]).exists():
            h = yaml.safe_load((pdir / console["help"]).read_text()) or {}
            out["guides"] = [{**g, "path": pdir / g["file"], "pack": name} for g in h.get("guides", [])]
            out["contextual"] = h.get("contextual", {}) or {}
        return out
