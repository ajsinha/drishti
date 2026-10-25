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

"""The worker's sandbox and limits (docs/architecture/PYTHON_COMPUTE.md sections 7.5 and 11).

- An audit hook (PEP 578) refuses sockets, new processes (``subprocess``, ``os.system``, ``os.exec*``, ``os.fork``) and any write, rename,
  removal or new folder outside the request's scratch folder. A hook cannot be removed once installed: in fork mode it is installed in the
  request's child only (the parent must still fork); in warm mode once, after the engines are imported, with the scratch folder switched
  per request.
- Limits: on POSIX the fork child gets ``RLIMIT_AS`` (memory) and ``RLIMIT_CPU`` (CPU seconds); a warm worker (Windows) watches its own
  working set every 250 ms and exits with :data:`EXIT_MEMORY` above the limit. The supervisor also enforces the wall time from outside.
"""
from __future__ import annotations

import os
import sys
import threading

EXIT_MEMORY = 86

_WRITE_FLAGS = os.O_WRONLY | os.O_RDWR | os.O_CREAT | os.O_APPEND | os.O_TRUNC
_PROCESS_EVENTS = ("subprocess.Popen", "os.system", "os.exec", "os.posix_spawn", "os.spawn", "os.fork", "os.forkpty", "os.startfile",
                   "pty.spawn")
_PATH_EVENTS = {"os.remove": 0, "os.rmdir": 0, "os.mkdir": 0, "os.rename": (0, 1), "os.replace": (0, 1), "os.truncate": 0,
                "os.chmod": 0, "os.chown": 0, "os.link": (0, 1), "os.symlink": (0, 1), "shutil.rmtree": 0, "shutil.copyfile": 1,
                "shutil.move": (0, 1), "os.utime": 0}


class Sandbox:
    """The rules of the worker's sandbox, with the scratch folder of the request being run (None between requests: nothing may be
    written then)."""

    def __init__(self):
        self.scratch: str | None = None
        self.refused: str | None = None
        self.installed = False

    def allowed_path(self, path) -> bool:
        if isinstance(path, int):
            return True  # an open file descriptor: its opening was checked
        try:
            p = os.path.realpath(os.fsdecode(path))
        except (TypeError, ValueError):
            return False
        if p == os.path.realpath(os.devnull):
            return True
        root = self.scratch
        if root is None:
            return False
        root = os.path.realpath(root)
        return p == root or p.startswith(root + os.sep)

    def _refuse(self, what: str):
        self.refused = what
        raise PermissionError(f"the compute sandbox refuses {what}")

    def hook(self, event: str, args) -> None:
        if event.startswith("socket.") and event not in ("socket.gethostname",):
            self._refuse("network access (" + event + ")")
        if event.startswith(_PROCESS_EVENTS):
            self._refuse("starting processes (" + event + ")")
        if event == "open":
            path, mode, flags = (tuple(args) + (None, None, None))[:3]
            writes = (isinstance(mode, str) and any(c in mode for c in "wax+")) or (isinstance(flags, int) and flags & _WRITE_FLAGS)
            if writes and not self.allowed_path(path):
                self._refuse(f"writing outside the scratch folder ({path})")
        elif event in _PATH_EVENTS:
            idx = _PATH_EVENTS[event]
            for i in (idx if isinstance(idx, tuple) else (idx,)):
                if i < len(args) and not self.allowed_path(args[i]):
                    self._refuse(f"changing files outside the scratch folder ({event} {args[i]})")

    def install(self) -> None:
        if not self.installed:
            sys.addaudithook(self.hook)
            self.installed = True


def set_limits(memory: int, cpu_seconds: int) -> None:
    """POSIX limits for the process (the fork child): address space and CPU time. Nothing on Windows (the watchdog covers memory)."""
    try:
        import resource  # noqa: WPS433 (POSIX only)
    except ImportError:
        return
    if memory > 0:
        resource.setrlimit(resource.RLIMIT_AS, (memory, memory))
    if cpu_seconds > 0:
        resource.setrlimit(resource.RLIMIT_CPU, (cpu_seconds, cpu_seconds + 5))


def resident_bytes() -> int:
    """The process's working set now (0 when it cannot be read)."""
    if sys.platform.startswith("linux"):
        try:
            with open("/proc/self/statm", "rb") as f:
                return int(f.read().split()[1]) * os.sysconf("SC_PAGE_SIZE")
        except (OSError, ValueError, IndexError):
            return 0
    if os.name == "nt":
        import ctypes  # noqa: WPS433 (Windows: psapi through the standard library, no extra package)
        from ctypes import wintypes

        class _Counters(ctypes.Structure):
            _fields_ = [("cb", wintypes.DWORD), ("PageFaultCount", wintypes.DWORD), ("PeakWorkingSetSize", ctypes.c_size_t),
                        ("WorkingSetSize", ctypes.c_size_t), ("QuotaPeakPagedPoolUsage", ctypes.c_size_t),
                        ("QuotaPagedPoolUsage", ctypes.c_size_t), ("QuotaPeakNonPagedPoolUsage", ctypes.c_size_t),
                        ("QuotaNonPagedPoolUsage", ctypes.c_size_t), ("PagefileUsage", ctypes.c_size_t),
                        ("PeakPagefileUsage", ctypes.c_size_t)]
        c = _Counters()
        c.cb = ctypes.sizeof(c)
        handle = ctypes.windll.kernel32.GetCurrentProcess()
        if ctypes.windll.psapi.GetProcessMemoryInfo(handle, ctypes.byref(c), c.cb):
            return int(c.WorkingSetSize)
        return 0
    try:
        import resource  # noqa: WPS433
        return resource.getrusage(resource.RUSAGE_SELF).ru_maxrss * 1024
    except (ImportError, OSError):
        return 0


def start_watchdog(memory: int, interval: float = 0.25) -> None:
    """A daemon thread that ends the process with EXIT_MEMORY when its working set passes ``memory`` (warm workers)."""
    if memory <= 0:
        return

    def watch():
        ev = threading.Event()
        while not ev.wait(interval):
            used = resident_bytes()
            if used > memory:
                sys.stderr.write(f"drishti-compute: memory limit passed ({used} > {memory} bytes); the worker stops\n")
                sys.stderr.flush()
                os._exit(EXIT_MEMORY)

    threading.Thread(target=watch, name="drishti-memory-watchdog", daemon=True).start()
