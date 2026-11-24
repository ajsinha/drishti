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

"""``drishti.py start console`` and ``stop console``: the Drishti console (the web front end), pointed at a server or at the entry nodes of
a cluster. Its Python is DRISHTI_PYTHON, else ``drishti.python`` of --settings, else the Python running drishti.py. Start
never makes an environment and never installs anything (setup prepares, start only starts): a Python lacking the console's libraries is
refused with the ``drishti.py setup`` line that prepares it."""
from __future__ import annotations

import os
import shutil
import sys
from pathlib import Path

import procs
from procs import LaunchError, say, warn
import javacmd  # noqa: E402 - tools/ is on the path through procs
import serverstart
import settingsfile  # noqa: E402 - tools/ is on the path through procs


def setup_line(settings: str | None = None) -> str:
    """The one line that prepares the console's Python (start never does: setup prepares, start only starts)."""
    return procs.setup_line(settings)


def console_python(env: dict, keys: dict, home: Path, settings: str | None = None, verify=None) -> str:
    """The console's Python: :func:`procs.tools_python` (DRISHTI_PYTHON, drishti.python, else this one). Never makes an environment
    and never installs anything: a Python that is not there, or lacks the console's libraries (pylibs.verify), is a LaunchError naming
    what is missing and the setup line that fixes it."""
    import pylibs
    chosen, why = procs.tools_python(env, keys)
    if not (Path(chosen).exists() or shutil.which(chosen)):
        raise LaunchError(procs.fail("the console's Python", f"{chosen} (from {why}) is not there", setup_line(settings)))
    bad = verify(chosen, home) if verify else pylibs.verify(chosen, home, lock=False)     # the console's own requirements
    if bad:
        raise LaunchError(procs.fail("the console's Python", f"{chosen} (from {why}) lacks libraries the console needs (start installs nothing):\n  "
                                     + "\n  ".join(bad) + "\n", setup_line(settings)))
    return chosen


def prepared_python(a, env: dict | None = None, keys: dict | None = None, home: Path | None = None) -> str:
    """The console's prepared Python for the options of ``start console`` (``start all`` asks before the server starts); never makes one."""
    if getattr(a, "python", None) or getattr(a, "reinstall", False):
        raise LaunchError("start never makes a Python environment and installs nothing: run start with the Python you run Drishti with, "
                          "or name it in drishti.python of your settings file; " + procs.setup_line() + " checks its libraries", 2)
    env = dict(os.environ if env is None else env)
    if keys is None or home is None:
        if getattr(a, "env_file", None):
            procs.load_env_file(a.env_file, env, quiet=True)
        keys = serverstart.profiled(a)[1]
        home = javacmd.launch_roots(getattr(a, "home", None), getattr(a, "data_dir", None), env, keys)["home"][0]
    return console_python(env, keys, home, javacmd.settings()[0])


def start(a, env: dict | None = None) -> int:
    """Starts the console from the options of ``drishti.py start console``; the exit code."""
    env = dict(os.environ if env is None else env)
    if a.env_file:
        procs.load_env_file(a.env_file, env)
    settings, keys = serverstart.profiled(a)          # --demo: config/profiles/demo.ini after the settings files (sign-in on, plain-HTTP cookie)
    roots = javacmd.launch_roots(getattr(a, "home", None), a.data_dir, env, keys)
    home, data = roots["home"][0], roots["data"][0]
    py = prepared_python(a, env, keys, home)          # before anything is made: an unprepared Python is refused with the setup line
    serverstart.require_set_up(data, roots["data"][1])  # start makes no data root: only setup does
    env["DRISHTI_DATA_DIR"] = str(data)
    env["DRISHTI_HOME"] = str(home)
    port = int(a.port or env.get("DRISHTI_CONSOLE_PORT") or (keys.get("console.server.port") or "").strip() or procs.DEFAULT_CONSOLE_PORT)
    stem = f"console-{a.name}" if a.name else "console"
    pid_file, log = data / "run" / f"{stem}.pid", data / "logs" / f"{stem}.log"
    if a.distribution:
        env["DRISHTI_DISTRIBUTION"] = a.distribution
    dist = env.get("DRISHTI_DISTRIBUTION") or ""
    if a.name:
        env.setdefault("DRISHTI_CONSOLE_TMP_DIR", str(data / "tmp" / stem))   # its own temp folder beside the others

    pid, fields = procs.read_pid(pid_file)
    if procs.alive(pid, procs.CONSOLE_MARK):
        say(f"{stem}: already running (pid {pid}, port {fields.get('port', '?')}); drishti.py stop console{' --name ' + a.name if a.name else ''} stops it")
        return 3
    if pid_file.exists():
        pid_file.unlink()
        say(f"{stem}: stale {pid_file} removed (pid {pid or '?'} is not running)")
    if procs.port_in_use(port):
        raise LaunchError(procs.fail(f"port {port}", "it is in use by another process",
                                     procs.command("stop", "console", *(["--name", a.name] if a.name else [])) + ", or pick another with --port"), 3)


    dev = []
    if a.demo:   # sign-in and the cookie are the profile's [console.auth]; only the development secrets come from here
        dev = serverstart.dev_secrets(env, ["DRISHTI_TOKEN_SECRET", "DRISHTI_SESSION_SECRET"])

    host = procs.lan_ip() or "localhost"
    # the server: --server, else DRISHTI_SERVER_URL (or DRISHTI_SERVER), else the settings files' (console.backend.url, a profile's server.port)
    servers = (",".join(a.server or []) or env.get("DRISHTI_SERVER_URL") or env.get("DRISHTI_SERVER")
               or settingsfile.server_url(keys, host) or "")
    if not servers:
        servers = f"http://{host}:{procs.DEFAULT_SERVER_PORT}"
        warn(f"no --server (or DRISHTI_SERVER_URL): using this machine's {servers}")
    listed, first = [], ""
    for s in [x.strip() for x in servers.split(",") if x.strip()]:
        s = (s if s.startswith(("http://", "https://")) else "http://" + s).rstrip("/")
        h = s.split("://", 1)[1].split("/", 1)[0].rsplit(":", 1)[0].strip("[]")
        if procs.loopback_or_wildcard(h):
            warn(f"{s} is a loopback address: in a cluster give the entry node's real address, as the members advertise it")
        listed.append(s)
        if not first and procs.http_ok(s + "/actuator/health"):
            first = s
    if not first:
        first = listed[0]
        warn(f"no server answers yet at {','.join(listed)}: the console starts and keeps trying")
    env.update(DRISHTI_BACKEND_URLS=",".join(listed), DRISHTI_BACKEND_URL=first, DRISHTI_CONSOLE_PORT=str(port))

    url, health = f"http://{host}:{port}", f"http://127.0.0.1:{port}/healthz"
    say(f"Drishti console on port {port}, given {','.join(listed)} (using {first} first), data {data}")
    for line in serverstart.roots_line(roots):
        say(line)
    if dev:
        warn(serverstart.devsecrets.warning(dev))
    (data / "run").mkdir(parents=True, exist_ok=True)
    cmd = [py, str(home / "drishti-console" / "run_drishti_web.py"), *(["--settings", settings] if settings else []), f"--server.port={port}"]
    # the launcher's stop key: the console answers POST /launcher/stop with it (core/launcher_stop.py), the same stop on every OS
    fields = dict(port=port, url=url, health=health, distribution=dist or "from-server", **{procs.STOP_FIELD: procs.stop_key(env)})
    if a.background:
        proc = procs.spawn(cmd, home, env, log=log)
        procs.write_pid(pid_file, proc.pid, **fields)
        say(f"{stem}: started (pid {proc.pid}); log {log}; waiting up to {a.wait} s for {health}")
        if procs.wait_health(health, a.wait, proc):
            say(f"{stem}: up on {url}")
            return 0
        if proc.poll() is not None:
            pid_file.unlink(missing_ok=True)
        say(f"{stem}: did not answer in {a.wait} s: see {log}")
        return 1
    proc = procs.spawn(cmd, home, env)
    procs.write_pid(pid_file, proc.pid, **fields)

    def up():
        import threading
        threading.Thread(target=lambda: procs.wait_health(health, a.wait, proc) and say(f"{stem}: up on {url}"), daemon=True).start()
    return procs.run_foreground(proc, pid_file, up)


def stop(a, env: dict | None = None) -> int:
    """``drishti.py stop console``: by the pid file only (never by name)."""
    env = dict(os.environ if env is None else env)
    if a.env_file:
        procs.load_env_file(a.env_file, env, quiet=True)
    data = javacmd.launch_roots(None, a.data_dir, env, javacmd.settings()[1])["data"][0]
    stem = f"console-{a.name}" if a.name else "console"
    procs.stop_pid_file(data / "run" / f"{stem}.pid", stem, procs.CONSOLE_MARK, a.timeout)
    return 0
