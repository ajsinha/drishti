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

Processes are tracked by pid files under the data folder (``<data>/run/server[-<node>].pid``, ``<data>/run/console[-<name>].pid``, mode
600): the first line the process id, then ``key=value`` lines (port, url, health, data, distribution, stop). A process is stopped ONLY
through its pid file and only when the process with that id is still OURS: its full command line (``/proc/<pid>/cmdline`` or ``ps``; on
Windows ``Get-CimInstance Win32_Process`` through PowerShell, else ``wmic``) holds the mark (``-Ddrishti.data.dir=`` for a server, with
the pid file's data folder; ``run_drishti_web.py`` for a console), so a reused process id never stops another JVM; never by name or pattern.

The stop is the same on every operating system: the process is asked over HTTP, ``POST http://127.0.0.1:<port>/launcher/stop`` with the
key the launcher made at start (``DRISHTI_STOP_KEY``, kept in the pid file as ``stop=``), and shuts down gracefully by itself; after the
timeout it is forced (SIGKILL; Windows ``taskkill /F /PID``). On POSIX a process the key does not reach (started before the key existed) is
sent SIGTERM; on Windows, where a background process has no console and no window (``CREATE_NEW_PROCESS_GROUP``, ``CREATE_NO_WINDOW``)
and ``taskkill`` without ``/F`` cannot reach it, such a process is forced at once."""
from __future__ import annotations

import os
import secrets
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
SERVER_MARK = "-Ddrishti.data.dir="          # every server the launcher starts names its data folder on its command line
CONSOLE_MARK = "run_drishti_web.py"
#: the launcher's stop (docs/DEPLOYMENT_AND_QA_GUIDE.md section 7.1): the key's variable, the pid file's field, the path and the header
STOP_ENV = "DRISHTI_STOP_KEY"
STOP_FIELD = "stop"
STOP_PATH = "/launcher/stop"
STOP_HEADER = "X-Drishti-Stop-Key"
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
    """The pid file, readable by its owner only (it holds the stop key): mode 600; on Windows an ACL of this user alone."""
    p = Path(file)
    p.parent.mkdir(parents=True, exist_ok=True)
    old = os.umask(0o077)
    try:
        p.write_text(f"{pid}\n" + "".join(f"{k}={v}\n" for k, v in fields.items()), encoding="utf-8")
    finally:
        os.umask(old)
    try:
        p.chmod(0o600)
    except OSError:
        pass
    if windows():                                    # chmod is not an ACL there: this user only, nothing inherited (icacls ships with Windows)
        user = os.environ.get("USERNAME") or ""
        if user:
            subprocess.run(["icacls", str(p), "/inheritance:r", "/grant:r", f"{user}:F"], capture_output=True, check=False)


def stop_key(env: dict) -> str:
    """A fresh key for the launcher's stop, put into the process's environment (``DRISHTI_STOP_KEY``); the caller keeps it in the pid file."""
    key = secrets.token_urlsafe(32)
    env[STOP_ENV] = key
    return key


def ask_to_stop(fields: dict) -> bool:
    """Asks the process of a pid file to stop itself over HTTP (``POST /launcher/stop`` on 127.0.0.1 with the key of the pid file); True when
    it took the request (202)."""
    key, port = fields.get(STOP_FIELD), fields.get("port")
    if not key or not port:
        return False
    req = urllib.request.Request(f"http://127.0.0.1:{int(port)}{STOP_PATH}", data=b"", method="POST", headers={STOP_HEADER: key})
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            return r.status == 202
    except Exception:  # noqa: BLE001 - refused, not answering, gone: not asked
        return False


def _windows_command_line(pid: int) -> str | None:
    """The full command line of a Windows process (PowerShell's Get-CimInstance, else wmic; both ship with Windows), or None when there is
    no such process or it cannot be read (then it is never taken for ours)."""
    queries = (["powershell", "-NoProfile", "-NonInteractive", "-Command",
                f"(Get-CimInstance Win32_Process -Filter 'ProcessId={int(pid)}').CommandLine"],
               ["wmic", "process", "where", f"ProcessId={int(pid)}", "get", "CommandLine", "/value"])
    for q in queries:
        try:
            r = subprocess.run(q, capture_output=True, text=True, timeout=30)
        except (OSError, subprocess.SubprocessError):
            continue                                     # not on this machine: the next way
        if r.returncode != 0:
            continue
        out = r.stdout.strip()
        if q[0] == "wmic":
            out = out.partition("CommandLine=")[2].strip() if "CommandLine=" in out else ""
        return out or None
    return None


def _command_line(pid: int) -> str | None:
    """The process's full command line, or None when no such process runs (or it cannot be read)."""
    if windows():
        return _windows_command_line(pid)
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


def running(pid) -> bool:
    """Whether a process of that id exists at all (cheap: POSIX signal 0, Windows tasklist); identity is :func:`alive`'s job."""
    if not pid:
        return False
    if windows():
        try:
            out = subprocess.run(["tasklist", "/FI", f"PID eq {int(pid)}", "/FO", "CSV", "/NH"], capture_output=True, text=True, timeout=15).stdout
        except (OSError, subprocess.SubprocessError):
            return False
        return any(f'"{int(pid)}"' in line for line in out.splitlines())
    try:
        os.kill(int(pid), 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    return True


def alive(pid, mark: str, also: str = "") -> bool:
    """The process runs and is OURS: its full command line holds ``mark`` (and ``also``, e.g. the pid file's data folder), on every operating
    system, so a reused process id (another JVM, another Python) is never taken for ours."""
    if not pid:
        return False
    cmd = _command_line(int(pid))
    return cmd is not None and mark in cmd and (not also or also in cmd)


def terminate(pid: int, force: bool = False) -> bool:
    """Signals the one process: SIGTERM, or SIGKILL with ``force``; on Windows only the forced stop exists (``taskkill /F /PID``: a background
    process has no console or window a plain ``taskkill`` could reach; it is asked over HTTP instead, :func:`ask_to_stop`). Whether a
    signal was sent."""
    if windows():
        if force:
            subprocess.run(["taskkill", "/F", "/PID", str(pid)], capture_output=True, check=False)
        return force
    try:
        os.kill(pid, signal.SIGKILL if force else signal.SIGTERM)
    except ProcessLookupError:
        pass
    return True


def stop_pid_file(file, name: str, mark: str, seconds: int) -> None:
    """Stops the one process of a pid file, when it is ours: asked over HTTP first (every operating system; POSIX: SIGTERM when the key does
    not reach it), forced after ``seconds``; then the pid file is removed. Never by name."""
    file = Path(file)
    if not file.is_file():
        say(f"{name}: not running (no {file})")
        return
    pid, fields = read_pid(file)
    also = f"{SERVER_MARK}{fields['data']}" if mark == SERVER_MARK and fields.get("data") else ""
    if not alive(pid, mark, also):
        file.unlink(missing_ok=True)
        say(f"{name}: not running (pid {pid or '?'} is gone or is not Drishti's); stale {file} removed")
        return
    asked = ask_to_stop(fields) or terminate(pid)
    deadline = time.monotonic() + (seconds if asked else 0)
    while time.monotonic() < deadline and running(pid):
        time.sleep(0.5)  # wait-ok: the process drains and closes
    if running(pid):
        terminate(pid, force=True)
        time.sleep(1)  # wait-ok: the forced stop lands
        say(f"{name}: stopped by force {'after ' + str(seconds) + ' s' if asked else '(it has no stop key: started by an older launcher)'} (pid {pid})")
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
        if not windows():                # Windows: the process shares this console and had the Ctrl+C itself
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
