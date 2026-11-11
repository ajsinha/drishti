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

"""The worker's sandbox and limits (docs/guides/NOTEBOOK_GUIDE.md sections 7.5 and 11).

- An audit hook (PEP 578) refuses sockets, new processes (``subprocess``, ``os.system``, ``os.exec*``, ``os.fork``) and any write, rename,
  removal or new folder outside the request's scratch folder. A hook cannot be removed once installed: in fork mode it is installed in the
  request's child only (the parent must still fork); in warm mode once, after the engines are imported, with the scratch folder switched
  per request.
- The strict sandbox (``--isolate strict``, Linux only, for code people write): the worker leaves the network at start (a user and a
  network namespace of its own, with no interface: no connection to anything, whatever library tries), and each request's fork child is
  held by the kernel (Landlock) to reading the Python installation, the system libraries and its scratch folder, and to writing its
  scratch folder only, before any of the request's code runs. Where either is missing the worker does not start, and says why.
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


# ---- the strict sandbox (Linux): a network namespace for the worker, a Landlock rule per request -------------------------------------

_SYS_LANDLOCK_CREATE_RULESET, _SYS_LANDLOCK_ADD_RULE, _SYS_LANDLOCK_RESTRICT_SELF = 444, 445, 446   # one number on every Linux architecture
_LANDLOCK_CREATE_RULESET_VERSION = 1
_RULE_PATH_BENEATH = 1
_PR_SET_NO_NEW_PRIVS = 38
_FS_EXECUTE, _FS_WRITE_FILE, _FS_READ_FILE, _FS_READ_DIR = 1 << 0, 1 << 1, 1 << 2, 1 << 3
_FS_FILE_ONLY = _FS_EXECUTE | _FS_WRITE_FILE | _FS_READ_FILE | (1 << 14) | (1 << 15)   # the rights a rule on a file (not a folder) may carry
#: Folders and files a request may read besides its scratch folder: system libraries, time zones, and what the interpreter and numeric
#: libraries read about the machine. Python's own folders and the module path are added at run time.
READ_ROOTS = ("/usr/lib", "/lib", "/lib64", "/usr/lib64", "/usr/local/lib", "/usr/share/zoneinfo", "/etc/localtime", "/etc/ld.so.cache",
              "/proc/self", "/proc/meminfo", "/proc/cpuinfo", "/proc/stat", "/sys/devices/system/cpu", "/sys/fs/cgroup", "/dev/null",
              "/dev/urandom", "/dev/zero", "/dev/random")


def _libc():
    import ctypes  # noqa: WPS433 (the worker's own use, before any request's code; a request may not import it)
    return ctypes, ctypes.CDLL(None, use_errno=True)


def landlock_abi() -> int:
    """The Landlock ABI the kernel offers (0: none)."""
    if not sys.platform.startswith("linux"):
        return 0
    ctypes, libc = _libc()
    v = libc.syscall(ctypes.c_long(_SYS_LANDLOCK_CREATE_RULESET), None, ctypes.c_size_t(0), ctypes.c_uint32(_LANDLOCK_CREATE_RULESET_VERSION))
    return int(v) if v > 0 else 0


def strict_unavailable(mode: str) -> str | None:
    """Why the strict sandbox cannot be had here, or None."""
    if not sys.platform.startswith("linux"):
        return f"it needs Linux (a network namespace and Landlock); this is {sys.platform}"
    if mode != "fork":
        return "it needs fork mode (a fresh child per request)"
    abi = landlock_abi()
    if abi < 1:
        return "the kernel offers no Landlock (Linux 5.13 or later, with Landlock enabled)"
    return None


def _unshare(flags: int) -> None:
    if hasattr(os, "unshare"):
        os.unshare(flags)
        return
    ctypes, libc = _libc()
    if libc.unshare(ctypes.c_int(flags)) != 0:
        e = ctypes.get_errno()
        raise OSError(e, os.strerror(e))


def leave_network() -> str:
    """Moves this process into a user namespace and a network namespace of its own (no interface but a loopback that is down): from here
    on nothing it or its children run can reach a network. Its own user and group are mapped to themselves, so files keep their owner."""
    uid, gid = os.getuid(), os.getgid()
    _unshare(0x10000000 | 0x40000000)                                   # CLONE_NEWUSER | CLONE_NEWNET
    with open("/proc/self/setgroups", "w", encoding="ascii") as f:
        f.write("deny")
    with open("/proc/self/uid_map", "w", encoding="ascii") as f:
        f.write(f"{uid} {uid} 1")
    with open("/proc/self/gid_map", "w", encoding="ascii") as f:
        f.write(f"{gid} {gid} 1")
    import socket  # noqa: WPS433 (the check that the namespace holds)
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("192.0.2.1", 9))
        raise OSError("a connection was still possible after leaving the network")
    except OSError as e:
        if "still possible" in str(e):
            raise
    finally:
        s.close()
    return "namespace"


def restrict_files(scratch: str, extra_read: tuple = ()) -> int:
    """Holds this process (a request's fork child) and its children to reading the Python installation, the module path, the system
    libraries and ``scratch``, and to writing ``scratch`` only (Landlock; TCP too, from ABI 4). Returns the ABI used; raises OSError."""
    ctypes, libc = _libc()
    abi = landlock_abi()
    if abi < 1:
        raise OSError("the kernel offers no Landlock")
    fs = (1 << 13) - 1                                                  # execute .. make symlink (ABI 1)
    if abi >= 2:
        fs |= 1 << 13                                                   # refer
    if abi >= 3:
        fs |= 1 << 14                                                   # truncate
    if abi >= 5:
        fs |= 1 << 15                                                   # ioctl on devices
    net = 0b11 if abi >= 4 else 0                                       # bind and connect TCP: no rule, so none
    scoped = 0b11 if abi >= 6 else 0                                    # abstract unix sockets and signals outside the sandbox

    class _Ruleset(ctypes.Structure):
        _fields_ = [("fs", ctypes.c_uint64), ("net", ctypes.c_uint64), ("scoped", ctypes.c_uint64)]

    class _PathBeneath(ctypes.Structure):
        _pack_ = 1
        _fields_ = [("allowed", ctypes.c_uint64), ("fd", ctypes.c_int32)]

    attr = _Ruleset(fs, net, scoped)
    size = 8 if abi < 4 else 16 if abi < 6 else 24
    rs = libc.syscall(ctypes.c_long(_SYS_LANDLOCK_CREATE_RULESET), ctypes.byref(attr), ctypes.c_size_t(size), ctypes.c_uint32(0))
    if rs < 0:
        e = ctypes.get_errno()
        raise OSError(e, "landlock_create_ruleset: " + os.strerror(e))
    try:
        read = _FS_EXECUTE | _FS_READ_FILE | _FS_READ_DIR
        roots = {os.path.realpath(p) for p in sys.path if p and os.path.isabs(p)}
        roots |= {os.path.realpath(p) for p in (sys.prefix, sys.base_prefix, sys.exec_prefix, sys.base_exec_prefix)}
        roots |= {os.path.realpath(os.path.dirname(sys.executable))}
        rules = [(p, read) for p in sorted(roots | set(READ_ROOTS) | set(extra_read))] + [(os.path.realpath(scratch), fs)]
        for path, rights in rules:
            try:
                fd = os.open(path, os.O_PATH | os.O_CLOEXEC)
            except OSError:
                continue                                                # a root this machine does not have
            try:
                if not os.path.isdir(path):
                    rights &= _FS_FILE_ONLY
                rule = _PathBeneath(rights & fs, fd)
                if libc.syscall(ctypes.c_long(_SYS_LANDLOCK_ADD_RULE), ctypes.c_int(rs), ctypes.c_int(_RULE_PATH_BENEATH), ctypes.byref(rule),
                                ctypes.c_uint32(0)) < 0:
                    e = ctypes.get_errno()
                    raise OSError(e, f"landlock_add_rule {path}: " + os.strerror(e))
            finally:
                os.close(fd)
        if libc.prctl(ctypes.c_int(_PR_SET_NO_NEW_PRIVS), ctypes.c_ulong(1), ctypes.c_ulong(0), ctypes.c_ulong(0), ctypes.c_ulong(0)) != 0:
            e = ctypes.get_errno()
            raise OSError(e, "no_new_privs: " + os.strerror(e))
        if libc.syscall(ctypes.c_long(_SYS_LANDLOCK_RESTRICT_SELF), ctypes.c_int(rs), ctypes.c_uint32(0)) < 0:
            e = ctypes.get_errno()
            raise OSError(e, "landlock_restrict_self: " + os.strerror(e))
    finally:
        os.close(rs)
    return abi
