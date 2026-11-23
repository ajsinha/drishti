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
a cluster. Its Python is DRISHTI_PYTHON, else ``drishti.python`` of --settings, else drishti-console/.venv (made on first use: ``drishti.py setup`` makes it too)."""
from __future__ import annotations

import os
import shutil
import subprocess
import sys
from pathlib import Path

import procs
from procs import LaunchError, say, warn
import javacmd  # noqa: E402 - tools/ is on the path through procs
import serverstart
import settingsfile  # noqa: E402 - tools/ is on the path through procs


def make_venv(home: Path, base_python: str, reinstall: bool = False, install: bool = True, run=subprocess.run, wheels: Path | None = None) -> Path:
    """drishti-console/.venv with the libraries installed (the Lake wheel first, then drishti-console/requirements.txt; uv when it is
    installed, else venv and pip); its Python. An existing environment is kept (``reinstall`` removes it first)."""
    import pylibs
    venv = home / "drishti-console" / ".venv"
    py = procs.venv_python(home)
    if reinstall and venv.is_dir():
        shutil.rmtree(venv)
    if py.exists():
        return py
    say(f"making the console's Python environment in {venv} ...")
    uv = None if wheels else shutil.which("uv")
    try:
        r = run([uv, "venv", str(venv)] if uv else [base_python, "-m", "venv", str(venv)])
    except OSError as e:
        raise LaunchError(f"{base_python}: {e.strerror}: install Python 3.11 or newer (or pass --python)") from None
    if r.returncode:
        raise LaunchError("the venv could not be made: install Python 3.11 or newer (or pass --python), then try again")
    if install and uv and not wheels:       # offline wheels go through pip (--no-index --find-links)
        wheel = pylibs.lake_wheel(home)
        if wheel is None:
            raise LaunchError(f"the pinned Lake wheel is not in {home / 'lib' / 'wheels'}: see lib/lake-wheel.pin")
        tls = ["--native-tls"] if procs.windows() else []
        for tail in ([str(wheel)], ["-r", str(pylibs.plain_requirements(home / "drishti-console" / "requirements.txt"))]):
            if run([uv, "pip", "install", *tls, "--python", str(py), *tail]).returncode:
                raise LaunchError(f"uv pip install {tail[-1]} failed: read uv's message above")
    elif install:
        pylibs.install(str(py), home, run=run, wheels=wheels)
    return py


def console_python(env: dict, keys: dict, home: Path, python: str | None = None, reinstall: bool = False, venv=None) -> str:
    """The console's Python: DRISHTI_PYTHON, else the settings' drishti.python (setup --system writes it where no .venv is allowed),
    else drishti-console/.venv, made or checked here (--python and --reinstall ask for the .venv)."""
    chosen = (env.get("DRISHTI_PYTHON") or "").strip()
    if not chosen and not (python or reinstall):
        chosen = (keys.get("drishti.python") or "").strip()
    if chosen:
        return chosen
    return str((venv or make_venv)(home, python or sys.executable, reinstall))


def start(a, env: dict | None = None) -> int:
    """Starts the console from the options of ``drishti.py start console``; the exit code."""
    env = dict(os.environ if env is None else env)
    if a.env_file:
        procs.load_env_file(a.env_file, env)
    settings, keys = serverstart.profiled(a)          # --demo: config/profiles/demo.ini after the settings files (sign-in on, plain-HTTP cookie)
    roots = javacmd.launch_roots(getattr(a, "home", None), a.data_dir, env, keys)
    home, data = roots["home"][0], roots["data"][0]
    data.mkdir(parents=True, exist_ok=True)
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
        raise LaunchError(f"port {port} is in use by another process: stop it, or pick another with --port", 3)

    py = console_python(env, keys, home, a.python, a.reinstall)
    if not (Path(py).exists() or shutil.which(py)):
        raise LaunchError(f"no Python at {py}")

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
        warn(f"DEVELOPMENT secrets in use (NOT FOR PRODUCTION): {' '.join(dev)}. Put your own in an --env-file for anything shared.")
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
