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
and what each contributes to the console: example commands, workspace starters and help guides, read from the pack's folder: the one
the server's row names (``dir``), else where the one resolver finds it (core/resolution.py: the site, the modules that are on, the
kernel). Cached per user for ``ui.user_cache_seconds`` (10 s) and read again at once on every worker after a change
(core/user_stamps.py); falls back to the resolver's packs when the server is unreachable."""
from __future__ import annotations

import copy
import logging
import time
from pathlib import Path

import yaml
from core import resolution
from core.servers import scoped
from core.user_stamps import EVERYONE, NONE as NO_STAMPS

log = logging.getLogger(__name__)


class Packs:
    def __init__(self, settings, console_dir: Path, stamps=NO_STAMPS):
        self.settings = settings
        self.console_dir = console_dir
        wanted = str(settings.get("packs.enabled", "default"))
        self._wanted = [p.strip() for p in wanted.split(",") if p.strip() and p.strip() != "default"]
        # a pack's files parsed once while they are unchanged: pack name -> (the files' signature, what _read made of them). Parsing
        # every enabled pack's YAML took about a second of the event loop per page whenever the per-user cache was cold
        self._parsed: dict[str, tuple[tuple, dict]] = {}
        self._cache: dict[str, tuple[float, list, list, float]] = {}    # user -> (monotonic time, packs, switcher, wall time read)
        # every worker of the console sees a change at once (core/user_stamps.py); other consoles within ``ttl`` (QA3A-02)
        self.stamps = stamps
        self.ttl = float(settings.get("ui.user_cache_seconds", 10) or 0)

    def resolution(self) -> resolution.Resolution:
        """Where this console's packs are (its settings and the environment; read again when a layer's files change)."""
        return resolution.current(self.settings, self.console_dir)

    @property
    def dir(self) -> Path:
        """The site's packs folder."""
        return self.resolution().layout.site

    @property
    def installed(self) -> Path | None:
        """The site's installed packs (deployed from Admin or a registry): the site layer's first folder."""
        return self.resolution().layout.installed

    @property
    def fallback(self) -> list[str]:
        """The packs while no server answers: ``packs.enabled`` when it names packs, else every pack the resolver loads."""
        return self._wanted or [p.name for p in self.resolution().packs.loaded()]

    async def current(self, backend, ident=None) -> list[dict]:
        """The user's active packs."""
        return (await self._fetch(backend, ident))[0]

    async def assigned(self, backend, ident=None) -> list[dict]:
        """Every pack assigned to the user, each with an ``active`` flag (for the switcher)."""
        return (await self._fetch(backend, ident))[1]

    def forget(self, ident=None) -> None:
        key = scoped(getattr(ident, "user", ""))
        self._cache.pop(key, None)
        self.stamps.touch(key)

    def forget_all(self) -> None:
        """An admin switched a pack on or off: every user's packs change (on every worker)."""
        self._cache.clear()
        self.stamps.touch(EVERYONE)

    async def _fetch(self, backend, ident):
        key = scoped(getattr(ident, "user", ""))
        now = time.monotonic()
        hit = self._cache.get(key)
        if hit and now - hit[0] < self.ttl and self.stamps.fresh(key, hit[3]):
            return hit[1], hit[2]
        began = time.time()
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
        packs = [self._served(p, served) for p in (self._load(n, served.get(n)) for n in active) if p]
        switcher = [{**p, "active": a} for p, a in ((self._load(n, served.get(n)), a) for n, a in assigned) if p]
        if fresh:                            # a fallback is never cached, so the real list returns at the next request
            self._cache[key] = (now, packs, switcher, began)
        return packs, switcher

    @staticmethod
    def _served(pack: dict, served: dict) -> dict:
        """What the server says of a pack beside its files: the kinds it owns and what it offers Calc (``python``: enabled,
        snippets from pack.yaml and the pack's python/ folder, read by the server's pack model)."""
        row = served.get(pack["name"]) or {}
        return {**pack, "kinds": row.get("kinds") or pack.get("kinds") or [],
                "python": row.get("python") or {"enabled": False, "snippets": []}}

    def _load(self, name: str, row: dict | None = None) -> dict | None:
        """A pack's console files: from the folder the server's row names (``dir``, the server's resolution), else from where the
        resolver finds it on this console (a module's pack is in its module's folder, never under config/packs)."""
        given = Path(str(row["dir"])) if isinstance(row, dict) and row.get("dir") else None
        pdir = given if given is not None and (given / "pack.yaml").is_file() else self.resolution().packs.dir(name)
        if pdir is None:
            return None
        manifest = pdir / "pack.yaml"
        if not manifest.is_file():
            return None
        hit = self._parsed.get(name)
        if hit is None or hit[0] != self._signature(pdir, hit[1]):
            value = self._read(name, pdir, manifest)
            console = value.pop("_files")
            hit = (self._signature(pdir, console), console, value)
            self._parsed[name] = hit
        return copy.deepcopy(hit[2])                     # each caller its own copy, as when every call parsed the files

    @staticmethod
    def _signature(pdir: Path, files: list[str]) -> tuple:
        """The pack folder and the modification time and size of its pack.yaml and of the console files it names (workspaces, help):
        a changed file reads the pack again."""
        out = [str(pdir)]
        for rel in ["pack.yaml", *files]:
            try:
                st = (pdir / rel).stat()
                out.append((rel, st.st_mtime_ns, st.st_size))
            except OSError:
                out.append((rel, None))
        return tuple(out)

    @staticmethod
    def _read(name: str, pdir: Path, manifest: Path) -> dict:
        m = yaml.safe_load(manifest.read_text(encoding="utf-8")) or {}
        console = m.get("console", {}) or {}
        out = {"name": name, "title": m.get("title", name), "version": m.get("version", ""), "description": m.get("description", ""),
               "dir": pdir, "examples": console.get("examples", []), "workspaces": {}, "guides": [], "contextual": {},
               "monitors": console.get("monitors", {}) or {}, "alerts": m.get("alerts", []) or [], "kinds": m.get("kinds", []) or [],
               "mnemonics": {str(code).upper(): str((d or {}).get("kind", "")) for code, d in (m.get("mnemonics") or {}).items()
                             if isinstance(d, dict) and d.get("kind")}}
        out["forms"] = {str(k): len(v or []) for k, v in (m.get("form") or {}).items() if isinstance(v, list)}   # kind -> declared fields (query by form)
        if console.get("workspaces") and (pdir / console["workspaces"]).exists():
            out["workspaces"] = (yaml.safe_load((pdir / console["workspaces"]).read_text()) or {}).get("templates", {}) or {}
        if console.get("help") and (pdir / console["help"]).exists():
            h = yaml.safe_load((pdir / console["help"]).read_text()) or {}
            out["guides"] = [{**g, "path": pdir / g["file"], "pack": name} for g in h.get("guides", [])]
            out["contextual"] = h.get("contextual", {}) or {}
        out["_files"] = [str(console[k]) for k in ("workspaces", "help") if console.get(k)]
        return out


def samples(packs: list[dict]) -> list[dict]:
    """The example commands of the given packs (their ``console.examples``) whose mnemonic one of them defines, as
    ``{cmd, what, kind, id, pack}`` in pack order. Pages that show an example entity (Studio's first preview, the landing
    page) take it from here, so they name only entities of packs that are installed and switched on."""
    kinds: dict[str, str] = {}
    for p in packs:
        for code, kind in (p.get("mnemonics") or {}).items():
            kinds.setdefault(code, kind)
    out = []
    for p in packs:
        for ex in p.get("examples") or []:
            if not isinstance(ex, (list, tuple)) or not ex:
                continue
            parts = str(ex[0]).split()
            if len(parts) == 2 and parts[0].upper() in kinds:
                out.append({"cmd": str(ex[0]), "what": str(ex[1]) if len(ex) > 1 else "", "kind": kinds[parts[0].upper()],
                            "id": parts[1], "pack": p.get("title", p.get("name", ""))})
    return out
