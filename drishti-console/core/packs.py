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

"""The console side of domain packs: asked of the server, never read from a folder (docs/architecture/THIN_CONSOLE_DESIGN.md 3.3, 3.4).

The person's packs come from their answer, ``GET /api/v1/me/console`` (which packs are assigned and active, and what the console draws
from each: examples, monitors, alerts, mnemonics, form counts, kinds, what Calc offers). The larger contributions of a pack (workspace
starters, Help guides and contextual help, the facts file, the README's sources) come from ``GET /api/v1/packs/{pack}/console``, kept per
server and pack while its digest holds (they are the pack's, not a person's), and the Help guides' files from the server into this
console's temp folder by their SHA-256, as the modules' console parts are.

A person's answer is kept per server and person (``scoped``) for ``ui.user_cache_seconds`` (10 s), revalidated with its ``stamp``, and read
again at once on every worker after a change (core/user_stamps.py). While the server does not answer, a person's last answer is kept for
six times that and the server-away notice says so; with none, the console shows no pack. It never falls back to a folder or to "every pack
the installation has".
"""
from __future__ import annotations

import copy
import hashlib
import logging
import time
from pathlib import Path

from core.servers import current as current_server, scoped
from core.user_stamps import EVERYONE, NONE as NO_STAMPS

log = logging.getLogger(__name__)

#: how many times ``ui.user_cache_seconds`` a person's last answer is kept while the server does not answer
AWAY_FACTOR = 6


class Packs:
    def __init__(self, settings, stamps=NO_STAMPS, files_dir: Path | None = None):
        """``files_dir``: where the packs' Help files are kept by digest (default: ``pack-files`` in the console's temp folder)."""
        self.settings = settings
        self._cache: dict[str, tuple[float, list, list, float, str]] = {}   # person -> (monotonic time, packs, switcher, wall time, stamp)
        self._console: dict[tuple[str, str], dict] = {}                     # (server, pack) -> its contributions (shared: the pack's)
        self._decisions: dict[str, dict] = {}                               # person -> the server's decisions about them (step S4)
        self._files_dir = files_dir
        self._older: set[str] = set()                                       # servers that do not give the person's answer
        # every worker of the console sees a change at once (core/user_stamps.py); other consoles within ``ttl`` (QA3A-02)
        self.stamps = stamps
        self.ttl = float(settings.get("ui.user_cache_seconds", 10) or 0)

    async def current(self, backend, ident=None) -> list[dict]:
        """The person's active packs."""
        return (await self._fetch(backend, ident))[0]

    async def assigned(self, backend, ident=None) -> list[dict]:
        """Every pack assigned to the person, each with an ``active`` flag (for the switcher)."""
        return (await self._fetch(backend, ident))[1]

    async def decisions(self, backend, ident=None) -> dict:
        """The server's decisions about the person (their answer's ``engines-opened``, ``grants`` and ``modules``: the modules loaded and
        not, the lenses open to them, the functions they may not use; THIN_CONSOLE_DESIGN.md 3.3, step S4):
        what the console draws by, never works out. Empty while the server has not answered (nothing is opened in its place)."""
        await self._fetch(backend, ident)
        return dict(self._decisions.get(scoped(getattr(ident, "user", "")), {}))

    def forget(self, ident=None) -> None:
        key = scoped(getattr(ident, "user", ""))
        self._cache.pop(key, None)
        self._decisions.pop(key, None)
        self.stamps.touch(key)

    def forget_all(self) -> None:
        """An administrator switched a pack on or off: every person's packs change (on every worker)."""
        self._cache.clear()
        self._decisions.clear()
        self.stamps.touch(EVERYONE)

    def notice(self) -> str:
        """The words every page shows when the request's server gives no person's answer (older than this console), else ""."""
        from core.server_facts import OLDER
        return OLDER if current_server() in self._older else ""

    async def _fetch(self, backend, ident):
        key = scoped(getattr(ident, "user", ""))
        now = time.monotonic()
        hit = self._cache.get(key)
        if hit and now - hit[0] < self.ttl and self.stamps.fresh(key, hit[3]):
            return hit[1], hit[2]
        began = time.time()
        try:
            status, answer = await backend.me_console(ident, hit[4] if hit and self.stamps.fresh(key, hit[3]) else None)
        except Exception as e:  # noqa: BLE001 - any failure: the person's last answer for a while, else no pack (never a folder)
            if getattr(e, "status", None) == 404:            # the server has no /me/console: it is older than this console, said in words
                self._older.add(current_server())
                log.warning("the server %s has no /api/v1/me/console: it is older than this console; upgrade the servers first", current_server())
            if hit and now - hit[0] < self.ttl * AWAY_FACTOR:
                return hit[1], hit[2]
            log.info("the person's packs could not be asked (%s): none shown until the server answers", type(e).__name__)
            return [], []
        self._older.discard(current_server())
        if status == 304 and hit:
            self._cache[key] = (now, hit[1], hit[2], began, hit[4])
            return hit[1], hit[2]
        mods = (answer or {}).get("modules")
        self._decisions[key] = {"engines-opened": [str(x) for x in (answer or {}).get("engines-opened") or []],
                                "grants": [str(x) for x in (answer or {}).get("grants") or []],
                                "modules": mods if isinstance(mods, dict) else None}
        rows = [r for r in (answer or {}).get("packs") or [] if isinstance(r, dict) and r.get("name")]
        made = [await self._pack(backend, ident, r) for r in rows]
        packs = [p for p, r in zip(made, rows) if r.get("active", True)]
        switcher = [{**p, "active": bool(r.get("active", True))} for p, r in zip(made, rows) if r.get("assigned", True)]
        self._cache[key] = (now, packs, switcher, began, str((answer or {}).get("stamp") or ""))
        return packs, switcher

    async def _pack(self, backend, ident, row: dict) -> dict:
        """One pack as the pages use it: the person's row, and the pack's contributions (asked once per digest, shared by everyone)."""
        name = str(row["name"])
        out = {"name": name, "title": row.get("title") or name, "version": row.get("version") or "", "description": row.get("description") or "",
               "examples": list(row.get("examples") or []), "monitors": dict(row.get("monitors") or {}), "alerts": list(row.get("alerts") or []),
               "kinds": list(row.get("kinds") or []), "mnemonics": {str(k).upper(): str(v) for k, v in (row.get("mnemonics") or {}).items()},
               "forms": {str(k): int(v) for k, v in (row.get("forms") or {}).items()},
               "python": row.get("python") or {"enabled": False, "snippets": []},
               "workspaces": {}, "guides": [], "contextual": {}, "facts": None, "sources": []}
        digest = str((row.get("console") or {}).get("digest") or "")
        contributions = await self._contributions(backend, ident, name, digest)
        out.update(copy.deepcopy(contributions))
        return out

    async def _contributions(self, backend, ident, name: str, digest: str) -> dict:
        """The pack's workspace starters, guides (each with its file in this console's temp folder), contextual help, facts and sources."""
        key = (current_server(), name)
        held = self._console.get(key)
        if held is not None and digest and held["digest"] == digest:
            return held["value"]
        try:
            status, answer = await backend.pack_console(name, ident, held["digest"] if held else None)
        except Exception as e:  # noqa: BLE001 - the last contributions stay; with none the pack shows without them
            log.info("pack %s: its console answer could not be read (%s)", name, type(e).__name__)
            return held["value"] if held else {}
        if status == 304 and held is not None:
            return held["value"]
        if not isinstance(answer, dict):
            return held["value"] if held else {}
        files = await self._files(backend, ident, name, answer.get("files") or [])
        help_ = answer.get("help") or {}
        guides = [{**g, "path": files[str(g["file"])], "pack": name} for g in help_.get("guides") or []
                  if isinstance(g, dict) and g.get("slug") and str(g.get("file")) in files]
        value = {"workspaces": dict(answer.get("workspaces") or {}), "guides": guides, "contextual": dict(help_.get("contextual") or {}),
                 "facts": answer.get("facts"), "sources": [str(s) for s in answer.get("sources") or []], "consoleDigest": str(answer.get("digest") or "")}
        self._console[key] = {"digest": str(answer.get("digest") or digest), "value": value}
        return value

    async def _files(self, backend, ident, name: str, listed: list) -> dict[str, Path]:
        """Each listed file kept in this console's temp folder under its SHA-256 (fetched once per content), by its path in the pack;
        a file that does not arrive or does not match its digest is left out."""
        out: dict[str, Path] = {}
        for f in listed:
            rel, sha = str((f or {}).get("path") or ""), str((f or {}).get("sha") or "")
            if not rel or not sha or rel.startswith("/") or ".." in Path(rel).parts:
                continue
            target = self._root() / name / sha[:16] / rel
            if not target.is_file():
                try:
                    data = await backend.pack_console_file(name, rel, ident)
                except Exception as e:  # noqa: BLE001 - one missing file hides one guide, never the page
                    log.info("pack %s: %s could not be fetched (%s)", name, rel, type(e).__name__)
                    continue
                if not isinstance(data, (bytes, bytearray)) or hashlib.sha256(data).hexdigest() != sha:
                    log.warning("pack %s: %s did not match its digest; left out", name, rel)
                    continue
                target.parent.mkdir(parents=True, exist_ok=True)
                part = target.with_name(target.name + ".part")
                part.write_bytes(bytes(data))
                part.replace(target)
            out[rel] = target
        return out

    def _root(self) -> Path:
        if self._files_dir is None:
            from core import tmpdir
            self._files_dir = tmpdir.sub_dir("pack-files")
        return self._files_dir


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
