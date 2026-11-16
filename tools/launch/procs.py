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

"""What ``drishti.py start``, ``stop`` and ``status`` share: the env-file, this machine's real address, pid files, health, starting a
process in the background and stopping one by its pid file. Standard library only; Linux, macOS and Windows alike.

Processes are tracked by pid files under the data folder (``<data>/run/server[-<node>].pid``, ``<data>/run/console[-<name>].pid``): the
first line the process id, then ``key=value`` lines (port, url, health, data, distribution). A process is stopped ONLY through its pid file
and only when the process with that id is still Drishti's (its command line holds the mark: ``-jar`` for a server, ``run_drishti_web.py``
for a console; on Windows its image is java or python): never by name or pattern. Windows: no fork, a background process gets its own
process group (``CREATE_NEW_PROCESS_GROUP``) and no window; a stop asks with ``taskkill /PID`` and forces with ``taskkill /F /PID`` after
the timeout (both programs ship with Windows)."""
from __future__ import annotations

import os
import signal
import socket
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

TOOLS = Path(__file__).resolve().parents[1]
ROOT = TOOLS.parent
if str(TOOLS) not in sys.path:
    sys.path.insert(0, str(TOOLS))
import hostaddr  # noqa: E402

DEFAULT_SERVER_PORT = 18480
DEFAULT_CONSOLE_PORT = 17480
SERVER_MARK = "-jar"
CONSOLE_MARK = "run_drishti_web.py"
#: Windows process creation flags (subprocess has them only on Windows)
CREATE_NEW_PROCESS_GROUP = 0x00000200
CREATE_NO_WINDOW = 0x08000000


class LaunchError(Exception):
    """A failure said in one line; ``code``: 1 failed, 2 a wrong option, 3 already running or the port in use."""

    def __init__(self, message: str, code: int = 1):
        super().__init__(message)
        self.code = code


def say(text: str) -> None:
    print(text, flush=True)


def warn(text: str) -> None:
    print(f"warning: {text}", file=sys.stderr, flush=True)


def windows() -> bool:
    return os.name == "nt"


# ---- the env-file -----------------------------------------------------------------------------------------------------------------------
def read_env_file(path) -> dict:
    """KEY=VALUE lines (blank lines, # comments and a leading ``export `` allowed; one pair of quotes around the value removed). Lines that
    are not a variable assignment are skipped with a warning. The values are never printed."""
    p = Path(path)
    if not p.is_file():
        raise LaunchError(f"no env-file {p}", 2)
    out = {}
    for raw in p.read_text(encoding="utf-8").splitlines():
        line = raw.rstrip("\r").strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("export "):
            line = line[len("export "):]
        if "=" not in line:
            warn(f"{p}: a line without KEY=VALUE was skipped")
            continue
        key, value = line.split("=", 1)
        key = key.strip()
        if not key or key[0].isdigit() or not all(c.isalnum() or c == "_" for c in key):
            warn(f"{p}: '{key}' is not a variable name; skipped")
            continue
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
            value = value[1:-1]
        out[key] = value
    return out


def load_env_file(path, env: dict, quiet: bool = False) -> None:
    """Puts an env-file's settings into ``env`` (the environment of the process to start)."""
    values = read_env_file(path)
    env.update(values)
    if not quiet:
        say(f"env-file {path}: {len(values)} setting(s) read (values not shown)")


# ---- addresses, ports, health ------------------------------------------------------------------------------------------------------------
def lan_ip() -> str:
    """This machine's primary IPv4 address (tools/hostaddr.py), never a loopback or wildcard one; "" when it has none."""
    ip = hostaddr.primary_ipv4() or ""
    return "" if hostaddr.loopback_or_wildcard(ip) else ip


def loopback_or_wildcard(host: str) -> bool:
    return not host or hostaddr.loopback_or_wildcard(host) or host.startswith("169.254.")


def advertise_host(given: str = "") -> str:
    """``given`` (--advertise) or this machine's real address; a loopback or wildcard one is refused."""
    if given:
        if loopback_or_wildcard(given):
            raise LaunchError(f"--advertise {given} is a loopback or wildcard address: members advertise an address other machines can call"
                              + (f" ({lan_ip()})" if lan_ip() else ""), 2)
        return given
    ip = lan_ip()
    if not ip:
        raise LaunchError("this machine has no address but loopback: give the one to advertise with --advertise <ip>")
    return ip


def http_ok(url: str, timeout: float = 3) -> bool:
    try:
        with urllib.request.urlopen(url, timeout=timeout) as r:
            return 200 <= r.status < 300
    except Exception:  # noqa: BLE001 - any failure is "not answering"
        return False


def port_in_use(port: int) -> bool:
    with socket.socket() as s:
        s.settimeout(0.5)
        return s.connect_ex(("127.0.0.1", int(port))) == 0


# ---- pid files ---------------------------------------------------------------------------------------------------------------------------
def read_pid(file) -> tuple:
    """(the process id or None, the key=value fields) of a pid file; (None, {}) when there is none."""
    try:
        lines = Path(file).read_text(encoding="utf-8").splitlines()
    except OSError:
        return None, {}
    digits = "".join(c for c in (lines[0] if lines else "") if c.isdigit())
    fields = dict(x.split("=", 1) for x in lines[1:] if "=" in x)
    return (int(digits) if digits else None), fields


def write_pid(file, pid: int, **fields) -> None:
    p = Path(file)
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(f"{pid}\n" + "".join(f"{k}={v}\n" for k, v in fields.items()), encoding="utf-8")


def _command_line(pid: int) -> str | None:
    """The process's command line (POSIX), its image name (Windows), or None when no such process runs."""
    if windows():
        try:
            out = subprocess.run(["tasklist", "/FI", f"PID eq {pid}", "/FO", "CSV", "/NH"], capture_output=True, text=True, timeout=15).stdout
        except (OSError, subprocess.SubprocessError):
            return None
        line = next((x for x in out.splitlines() if x.startswith('"')), "")
        parts = [c.strip('"') for c in line.split('","')]
        return parts[0] if len(parts) > 1 and parts[1].strip('"') == str(pid) else None
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return None
    except PermissionError:
        pass
    try:
        return Path(f"/proc/{pid}/cmdline").read_bytes().replace(b"\0", b" ").decode("utf-8", "replace")
    except OSError:
        try:
            out = subprocess.run(["ps", "-p", str(pid), "-o", "command="], capture_output=True, text=True, timeout=15).stdout
            return out or None
        except (OSError, subprocess.SubprocessError):
            return None


def alive(pid, mark: str) -> bool:
    """The process runs and is Drishti's: its command line holds ``mark`` (Windows: its image is java for a server, python for a console),
    so a reused process id is never taken for ours."""
    if not pid:
        return False
    cmd = _command_line(int(pid))
    if cmd is None:
        return False
    if windows():
        return ("java" if mark == SERVER_MARK else "python") in cmd.lower()
    return mark in cmd


def terminate(pid: int, force: bool = False) -> None:
    """Asks the one process to stop (SIGTERM; Windows ``taskkill /PID``), or forces it (SIGKILL; ``taskkill /F /PID``)."""
    if windows():
        subprocess.run(["taskkill", *(["/F"] if force else []), "/PID", str(pid)], capture_output=True, check=False)
        return
    try:
        os.kill(pid, signal.SIGKILL if force else signal.SIGTERM)
    except ProcessLookupError:
        pass


def stop_pid_file(file, name: str, mark: str, seconds: int) -> None:
    """Stops the one process of a pid file: asked first, forced after ``seconds``; then the pid file is removed. Never by name."""
    file = Path(file)
    if not file.is_file():
        say(f"{name}: not running (no {file})")
        return
    pid, _ = read_pid(file)
    if not alive(pid, mark):
        file.unlink(missing_ok=True)
        say(f"{name}: not running (pid {pid or '?'} is gone or is not Drishti's); stale {file} removed")
        return
    terminate(pid)
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline and alive(pid, mark):
        time.sleep(0.5)  # wait-ok: the process drains and closes
    if alive(pid, mark):
        terminate(pid, force=True)
        time.sleep(1)  # wait-ok: the forced stop lands
        say(f"{name}: stopped by force after {seconds} s (pid {pid})")
    else:
        say(f"{name}: stopped (pid {pid})")
    file.unlink(missing_ok=True)


def wait_health(url: str, seconds: float, proc: subprocess.Popen | None = None) -> bool:
    """Waits until ``url`` answers; False when the process ends first or the time is up."""
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if proc is not None and proc.poll() is not None:
            return False
        if http_ok(url):
            return True
        time.sleep(2)  # wait-ok: the process is starting
    return False


# ---- starting a process ------------------------------------------------------------------------------------------------------------------
def spawn(cmd: list, cwd, env: dict, log=None) -> subprocess.Popen:
    """Starts ``cmd``. With ``log`` (background): detached from this terminal, output appended to the log, its own process group (POSIX: a
    new session; Windows: CREATE_NEW_PROCESS_GROUP and no window). Without: in the foreground, sharing this terminal."""
    kw: dict = {"cwd": str(cwd), "env": env}
    if log is None:
        return subprocess.Popen(cmd, **kw)
    Path(log).parent.mkdir(parents=True, exist_ok=True)
    out = open(log, "ab")  # noqa: SIM115 - handed to the child, closed here once it is started
    try:
        if windows():
            kw["creationflags"] = CREATE_NEW_PROCESS_GROUP | CREATE_NO_WINDOW
        else:
            kw["start_new_session"] = True
        return subprocess.Popen(cmd, stdin=subprocess.DEVNULL, stdout=out, stderr=subprocess.STDOUT, **kw)
    finally:
        out.close()


def run_foreground(proc: subprocess.Popen, pid_file: Path, on_up=None) -> int:
    """Waits for a foreground process; Ctrl+C asks it to stop and waits for it. The pid file is removed when it ends."""
    try:
        if on_up:
            on_up()
        return proc.wait()
    except KeyboardInterrupt:
        terminate(proc.pid)
        try:
            return proc.wait()
        except KeyboardInterrupt:
            terminate(proc.pid, force=True)
            return proc.wait()
    finally:
        Path(pid_file).unlink(missing_ok=True)


# ---- Python ------------------------------------------------------------------------------------------------------------------------------
def venv_python(root: Path = ROOT) -> Path:
    """drishti-console/.venv's Python (``Scripts\\python.exe`` on Windows)."""
    venv = Path(root) / "drishti-console" / ".venv"
    return venv / ("Scripts/python.exe" if windows() else "bin/python")


def tools_python(env=None) -> str:
    """The Python to run repository tools with: DRISHTI_PYTHON, else the console's environment, else this one."""
    env = os.environ if env is None else env
    if (env.get("DRISHTI_PYTHON") or "").strip():
        return env["DRISHTI_PYTHON"].strip()
    v = venv_python()
    return str(v) if v.exists() else sys.executable
