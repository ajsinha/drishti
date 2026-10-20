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
"""When a user's settings, packs, layouts, pivots or session last changed, shared by every worker process of a console.

A console serves its port from several worker processes (``server.workers``), each with its own short-lived copies of what the server
says about a user: their settings, packs, view layouts, saved pivots, BI powers and the check of their session. A change made through one
worker used to forget that worker's copy only, so the next pages served by the others showed the old choice for up to a minute (QA3A-02,
03, 04, 06: the pack switcher, a saved layout, a disabled user, the second browser of the same user).

Now the worker that makes a change writes a small stamp file for the user under the console's data folder (``<console data>/user-stamps``),
and every worker looks at it (one ``stat``) before it uses a copy: a copy read before the stamp is read again. One file per user (its name
a hash of the user and server), and one for everyone (an administrator switching a pack on or off). Consoles that share no data folder
(several machines behind one address) do not see each other's stamps: their copies are kept for at most ``ui.user_cache_seconds`` (10).
"""
from __future__ import annotations

import hashlib
import os
import time
import uuid
from pathlib import Path

#: the stamp every user's copies are also checked against (an administrator changed what everyone sees)
EVERYONE = "\x00everyone"


class UserStamps:
    """The stamps of one console's data folder (made at the first change); ``folder`` None keeps nothing: copies then live for their TTL."""

    def __init__(self, folder: Path | str | None):
        self.folder: Path | None = Path(folder) if folder else None
        self._seen: dict[str, tuple[tuple, float]] = {}       # file -> (its stat signature, the time it holds)

    @staticmethod
    def _name(key: str) -> str:
        return hashlib.sha256(key.encode("utf-8")).hexdigest()[:32] + ".stamp"

    def touch(self, key: str) -> float:
        """Says that what the console keeps for ``key`` (a scoped user name, or EVERYONE) changed now; returns the time written."""
        now = time.time()
        if self.folder is None:
            return now
        target = self.folder / self._name(key)
        tmp = self.folder / f"{target.stem}-{os.getpid()}-{uuid.uuid4().hex[:8]}.part"
        try:
            self.folder.mkdir(parents=True, exist_ok=True)
            tmp.write_text(f"{now:.6f}", encoding="ascii")
            for attempt in range(5):                        # Windows: a reader holding the old file for a moment
                try:
                    os.replace(tmp, target)
                    break
                except PermissionError:
                    time.sleep(0.01 * (attempt + 1))
            else:
                target.write_text(f"{now:.6f}", encoding="ascii")
        except OSError:
            pass
        finally:
            try:
                tmp.unlink(missing_ok=True)
            except OSError:
                pass
        return now

    def _read(self, key: str) -> float:
        if self.folder is None:
            return 0.0
        name = self._name(key)
        path = self.folder / name
        try:
            st = os.stat(path)
        except OSError:
            return 0.0
        sig = (st.st_ino, st.st_mtime_ns, st.st_size)
        seen = self._seen.get(name)
        if seen and seen[0] == sig:
            return seen[1]
        try:
            value = float(path.read_text(encoding="ascii").strip() or 0)
        except (OSError, ValueError):
            value = st.st_mtime
        if len(self._seen) > 50_000:
            self._seen.clear()
        self._seen[name] = (sig, value)
        return value

    def changed_at(self, key: str) -> float:
        """When what the console keeps for ``key`` last changed (0 when never): its own stamp or everyone's, the later."""
        return max(self._read(key), self._read(EVERYONE))

    def fresh(self, key: str, read_at: float) -> bool:
        """True when a copy for ``key`` read (begun) at wall time ``read_at`` is newer than its last change."""
        return read_at > self.changed_at(key)


#: no shared folder: every copy is fresh until its TTL (tests, a console without a data folder)
NONE = UserStamps(None)
