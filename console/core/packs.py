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


class Packs:
    def __init__(self, settings, console_dir: Path):
        d = Path(settings.get("packs.dir", "../packs"))
        self.dir = d if d.is_absolute() else (console_dir / d).resolve()
        self.fallback = [p.strip() for p in str(settings.get("packs.enabled", "finance")).split(",") if p.strip()]
        self._cache: tuple[float, list] | None = None

    async def current(self, backend, ident=None) -> list[dict]:
        now = time.monotonic()
        if self._cache and now - self._cache[0] < 60:
            return self._cache[1]
        try:
            names = [p["name"] for p in await backend.packs(ident)]
        except Exception:  # noqa: BLE001 - any backend failure means "use the configured list"
            names = self.fallback
        packs = [p for p in (self._load(n) for n in names) if p]
        self._cache = (now, packs)
        return packs

    def _load(self, name: str) -> dict | None:
        pdir = (self.dir / name).resolve()
        manifest = pdir / "pack.yaml"
        if not str(pdir).startswith(str(self.dir)) or not manifest.exists():
            return None
        m = yaml.safe_load(manifest.read_text(encoding="utf-8")) or {}
        console = m.get("console", {}) or {}
        out = {"name": name, "title": m.get("title", name), "version": m.get("version", ""), "description": m.get("description", ""),
               "dir": pdir, "examples": console.get("examples", []), "workspaces": {}, "guides": [], "contextual": {}}
        if console.get("workspaces") and (pdir / console["workspaces"]).exists():
            out["workspaces"] = (yaml.safe_load((pdir / console["workspaces"]).read_text()) or {}).get("templates", {}) or {}
        if console.get("help") and (pdir / console["help"]).exists():
            h = yaml.safe_load((pdir / console["help"]).read_text()) or {}
            out["guides"] = [{**g, "path": pdir / g["file"], "pack": name} for g in h.get("guides", [])]
            out["contextual"] = h.get("contextual", {}) or {}
        return out
