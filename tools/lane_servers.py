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

"""Lane servers: ONE server (and console) per test lane for every pytest worker of it (docs/architecture/TEST_HARNESS.md, "Lane servers").

A test lane used to start a server, a console and a data folder per pytest-xdist worker (4-6 GB each). Here the first process that asks
for a server of a given set-up (its key) starts it, under a file lock in the lane's folder, and writes ``state.json`` (pids, URLs, its
folder); every other process attaches to it. A process attaches once, by a file named after its pid under ``attached/``, and lets go when
it exits. The processes run in sessions of their own (a worker that crashes never takes them down for the others), so a watchdog
(``python lane_servers.py watch <dir>``, one per server) reaps them: once no attached process is alive and the lane's holder (the drill's
lane shell, ``DRISHTI_LANE_HOLDER_PID``) is gone, it stops the server and the console and removes the state. No orphan servers.

``DRISHTI_LANE_DIR``: the lane's folder (the drill sets one per lane, so the lane's serial group, a second pytest run, attaches to the very
server the parallel run used); unset, the caller's default (the test run's shared folder). ``DRISHTI_LANE_SERVERS=0`` turns lane servers
off (every caller starts its own, as before).

``python tools/lane_servers.py stop <lane dir>``: stops every server of the lane (the drill, at the lane's end)."""
from __future__ import annotations

import atexit
import contextlib
import hashlib
import json
import os
import signal
import subprocess
import sys
import time
from pathlib import Path
from typing import Callable

ENV_DIR, ENV_HOLDER, ENV_ON = "DRISHTI_LANE_DIR", "DRISHTI_LANE_HOLDER_PID", "DRISHTI_LANE_SERVERS"
POLL_SECONDS = 2.0
_HELD: set[Path] = set()


def enabled() -> bool:
    """Lane servers are on unless DRISHTI_LANE_SERVERS=0."""
    return os.environ.get(ENV_ON, "1") != "0"


def lane_dir(default: Path) -> Path:
    """The lane's folder: DRISHTI_LANE_DIR, else ``default``."""
    return Path(os.environ.get(ENV_DIR) or default)


def key_of(*parts) -> str:
    """A short stable name for a server's set-up (the same on every worker)."""
    return hashlib.sha1(json.dumps(parts, sort_keys=True, default=str).encode()).hexdigest()[:12]


def alive(pid: int | None) -> bool:
    """True while the process runs (a zombie counts as gone)."""
    if not pid:
        return False
    try:
        os.kill(int(pid), 0)
    except (OSError, ValueError):
        return False
    with contextlib.suppress(OSError):
        for line in Path(f"/proc/{pid}/status").read_text().splitlines():
            if line.startswith("State:"):
                return "Z" not in line
    return True


@contextlib.contextmanager
def _locked(d: Path):
    d.mkdir(parents=True, exist_ok=True)
    with open(d / "lock", "a+") as fh:
        try:
            import fcntl
            fcntl.flock(fh, fcntl.LOCK_EX)
        except ImportError:                                  # Windows
            import msvcrt
            while True:
                try:
                    msvcrt.locking(fh.fileno(), msvcrt.LK_LOCK, 1)
                    break
                except OSError:
                    time.sleep(0.2)   # wait-ok: polling a file lock another process holds (Windows)
        yield


def _read(d: Path) -> dict | None:
    with contextlib.suppress(OSError, ValueError):
        return json.loads((d / "state.json").read_text(encoding="utf-8"))
    return None


def _write(d: Path, state: dict) -> None:
    tmp = d / "state.tmp"
    tmp.write_text(json.dumps(state), encoding="utf-8")
    tmp.replace(d / "state.json")


def _kill(pids: list[int]) -> None:
    """SIGTERM to each process's group (each runs in a session of its own), then SIGKILL to what is left after 5 s."""
    for sig, wait in ((signal.SIGTERM, 5.0), (getattr(signal, "SIGKILL", signal.SIGTERM), 5.0)):
        live = [p for p in pids if alive(p)]
        for p in live:
            with contextlib.suppress(OSError):
                if hasattr(os, "killpg"):
                    os.killpg(p, sig)
                else:
                    os.kill(p, sig)
        deadline = time.monotonic() + wait
        while any(alive(p) for p in live) and time.monotonic() < deadline:
            time.sleep(0.1)   # wait-ok: waiting for processes to exit
            for p in live:     # reap our own children so they do not stay zombies
                with contextlib.suppress(ChildProcessError, OSError):
                    os.waitpid(p, os.WNOHANG)


def _live_attached(d: Path) -> list[int]:
    out = []
    for f in (d / "attached").glob("*"):
        pid = int(f.name) if f.name.isdigit() else 0
        if alive(pid):
            out.append(pid)
        else:
            f.unlink(missing_ok=True)                        # a crashed worker's attachment
    return out


def _stop_locked(d: Path, state: dict | None) -> None:
    if state:
        _kill([p for p in state.get("pids", []) if p])
        wd = state.get("watchdog")
        if wd and wd != os.getpid():
            _kill([wd])
    (d / "state.json").unlink(missing_ok=True)


def attach(base: Path, key: str, start: Callable[[Path], dict], healthy: Callable[[dict], bool] | None = None) -> dict:
    """The lane's server ``key``: started by ``start(work)`` when none runs (or the one there is dead or unhealthy), else the running one.
    ``start`` starts its processes in sessions of their own and returns the state (``pids``: every process to stop; anything else the
    caller needs: URLs, ports). This process stays attached until it exits."""
    d = base / key
    with _locked(d):
        state = _read(d)
        if state and not (all(alive(p) for p in state.get("pids", [])) and (healthy is None or healthy(state))):
            _stop_locked(d, state)                           # a dead or hung server of an earlier run: replaced
            state = None
        if state is None:
            n = len(list(d.glob("work-*")))
            work = d / f"work-{n}"
            work.mkdir(parents=True)
            state = start(work)
            state.update(work=str(work), holder=int(os.environ.get(ENV_HOLDER) or 0), started_by=os.getpid(), key=key)
            _write(d, state)
            wd = subprocess.Popen([sys.executable, str(Path(__file__).resolve()), "watch", str(d)], stdin=subprocess.DEVNULL,
                                  stdout=subprocess.DEVNULL, stderr=(d / "watchdog.log").open("a"), start_new_session=True)
            state["watchdog"] = wd.pid
            _write(d, state)
        (d / "attached").mkdir(exist_ok=True)
        (d / "attached" / str(os.getpid())).touch()
    if d not in _HELD:
        _HELD.add(d)
    return state


def release(d: Path) -> None:
    """This process lets go of the lane server in ``d``; the last one out (with no holder alive) stops it."""
    _HELD.discard(d)
    if not d.is_dir():
        return
    with _locked(d):
        (d / "attached" / str(os.getpid())).unlink(missing_ok=True)
        state = _read(d)
        if state and not _live_attached(d) and not alive(state.get("holder")):
            _stop_locked(d, state)


@atexit.register
def _release_all() -> None:
    for d in list(_HELD):
        with contextlib.suppress(Exception):
            release(d)


def watch(d: Path) -> None:
    """The reaper: stops the server once nothing attached is alive and the holder is gone, or cleans up once the server died."""
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))
    idle = 0
    while True:
        time.sleep(POLL_SECONDS)   # wait-ok: the reaper's poll
        with _locked(d):
            state = _read(d)
            if not state or state.get("watchdog") not in (None, os.getpid()):
                return                                       # stopped, or replaced by a newer server with its own watchdog
            if not all(alive(p) for p in state.get("pids", [])):
                _stop_locked(d, state)                       # the server or console died: the rest stopped, the next asker starts anew
                return
            busy = _live_attached(d) or alive(state.get("holder"))
            idle = 0 if busy else idle + 1
            if idle >= 2:
                _stop_locked(d, state)
                return


def stop_all(base: Path) -> int:
    """Stops every lane server under ``base``; returns how many it stopped."""
    n = 0
    for d in sorted(p.parent for p in base.glob("*/state.json")):
        with _locked(d):
            state = _read(d)
            if state:
                _stop_locked(d, state)
                n += 1
    return n


def main(argv: list[str]) -> int:
    if len(argv) == 2 and argv[0] == "watch":
        watch(Path(argv[1]))
        return 0
    if len(argv) == 2 and argv[0] == "stop":
        print(f"lane servers: {stop_all(Path(argv[1]))} stopped under {argv[1]}")
        return 0
    if len(argv) == 2 and argv[0] == "list":
        for p in sorted(Path(argv[1]).glob("*/state.json")):
            s = json.loads(p.read_text(encoding="utf-8"))
            print(p.parent.name, s.get("pids"), s.get("url", ""), "attached:", len(_live_attached(p.parent)))
        return 0
    print("usage: lane_servers.py watch|stop|list <lane dir>", file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
