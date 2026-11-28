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

"""Drishti console entry point.

    python drishti-console/run_drishti_web.py [--settings FILE] [--profile demo] [--server.port=17480] [--backend.url=http://...]

Run bare (no argument at all, no DRISHTI_BACKEND_URL: an IDE's "Run current file"), it is the launcher **Drishti: run console**: the
settings file ~/drishti-envs/dev.ini (when there) with the demo profile, and the development secrets where none is set (the ones
``drishti.py start all --demo`` gives the server: tools/launch/devsecrets.py), each said in one line. A Python without the console's
libraries stops at once with the setup line (start installs nothing).
"""
from __future__ import annotations

import importlib.util
import json
import secrets
import os
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
#: the libraries without which the console cannot start (import name: distribution); the full list is checked by drishti.py setup
NEEDS = {"fastapi": "fastapi", "uvicorn": "uvicorn", "httpx": "httpx", "yaml": "pyyaml", "jinja2": "jinja2", "drishti_lake": "drishti-lake"}
def setup_line() -> str:
    """The setup command, pasteable on this system (core/settings_ini.py setup_line, the one console copy of procs.command()'s rule)."""
    from core import settings_ini
    return settings_ini.setup_line()


def missing_libraries(find=importlib.util.find_spec) -> list[str]:
    """The distributions of :data:`NEEDS` this Python cannot import."""
    return [dist for mod, dist in NEEDS.items() if find(mod) is None]


def check_libraries() -> None:
    """Stops at once, with the setup line, when this Python lacks a library the console needs (never a traceback minutes later)."""
    lacking = missing_libraries()
    if lacking:
        raise SystemExit(f"[FAIL] the console's Python: {sys.executable} lacks {', '.join(lacking)} (Drishti installs nothing). Fix: {pip_line()}")


def pip_line() -> str:
    """The command the owner may run himself (Drishti never installs a library), as setup prints it: from drishti-console, offline from
    ~/drishti-wheels when it holds wheels, else from the package index."""
    q = lambda w: f'"{w}"' if " " in w else w                      # noqa: E731
    py = f"py -{sys.version_info[0]}.{sys.version_info[1]}" if os.name == "nt" else q(sys.executable)
    pack = Path(os.environ.get("USERPROFILE" if os.name == "nt" else "HOME") or Path.home()) / "drishti-wheels"
    offline = f"--no-index --find-links {q(str(pack))} " if pack.is_dir() and any(pack.rglob("*.whl")) else ""
    lock = "..\\config\\compute\\locks\\python.lock" if os.name == "nt" else "../config/compute/locks/python.lock"
    return (f"cd {q(str(HERE))}   then   {py} -m pip install {offline}-r requirements.txt   then   {py} -m pip install {offline}-r {lock}"
            f"   (or {setup_line()}, which checks every library)")


if __name__ == "__main__":
    check_libraries()

import uvicorn  # noqa: E402

from core.app import create_app  # noqa: E402
from core.config import load_settings  # noqa: E402


ARGV = "DRISHTI_CONSOLE_ARGV"


def factory():
    """One worker's app (server.workers > 1): the settings from the launcher's arguments, passed through the environment."""
    settings = load_settings(HERE / "config", json.loads(os.environ.get(ARGV, "[]")))
    from core import production
    production.enforce(settings)
    app = create_app(settings)
    app.state.tmp_owner = True                    # each worker removes its own temp folder when it stops (core/tmpdir.py)
    return app


def temp_under_data(settings) -> None:
    """The console's temp folder under <data dir>/tmp/console (the server's is <data dir>/tmp/server), unless one is configured: never the system temp folder,
    which can be a small in-memory file system. A console of one distribution (--distribution=risk) takes <data dir>/tmp/console-risk, so two
    consoles of one data folder never share one. Set here (a real run), not in create_app (tests make apps too)."""
    if str(settings.get("tmp_dir", "") or "").strip() or os.environ.get("DRISHTI_CONSOLE_TMP_DIR") or os.environ.get("DRISHTI_TMP_DIR"):
        return
    data = os.environ.get("DRISHTI_DATA_DIR") or str(HERE.parent / "data")
    dist = str(settings.get("distribution", "") or "").strip().lower()
    name = "console-" + dist if dist and dist != "all" and dist.replace("-", "").isalnum() else "console"
    os.environ["DRISHTI_CONSOLE_TMP_DIR"] = str(Path(data) / "tmp" / name)


def log_config() -> dict:
    """uvicorn's logging with a timestamp on every line, and the console's own loggers (``drishti.*`` and the rest) through the same
    handler at INFO (2.9.7, CLUSTER_GUIDE.md 6.4: the soak audit found the console's log without times)."""
    import copy
    from uvicorn.config import LOGGING_CONFIG
    cfg = copy.deepcopy(LOGGING_CONFIG)
    for f in cfg.get("formatters", {}).values():
        fmt = f.get("fmt", "%(levelprefix)s %(message)s")
        if "%(asctime)s" not in fmt:
            f["fmt"] = "%(asctime)s " + fmt
        f["datefmt"] = "%Y-%m-%dT%H:%M:%S%z"
    cfg.setdefault("loggers", {})[""] = {"handlers": ["default"], "level": "INFO"}
    return cfg


def foreign_settings(argv: list[str]) -> str | None:
    """The --settings file when it names another drishti.home (an old settings file of an earlier copy, passed by a launcher's fixed
    path): None when it is this checkout's (core.settings_ini.names_home, the tools' rule too)."""
    from core import settings_ini
    named = next((argv[i + 1] for i, a in enumerate(argv[:-1]) if a == "--settings"), None) \
        or next((a.split("=", 1)[1] for a in argv if a.startswith("--settings=")), None)
    if not named:
        return None
    entries = settings_ini.read_all(named)
    return None if settings_ini.names_home(entries, HERE.parent) else named


def drop_settings(argv: list[str]) -> list[str]:
    """``argv`` without its --settings: another checkout's file gives this console no roots (FIRST_RUN_ROBUSTNESS.md R6)."""
    out, skip = [], False
    for a in argv:
        if skip:
            skip = False
        elif a == "--settings":
            skip = True
        elif not a.startswith("--settings="):
            out.append(a)
    return out


def devsecrets():
    """tools/launch/devsecrets.py of this install (the one place the development secrets are spelled), or None when it is not here."""
    path = HERE.parent / "tools" / "launch" / "devsecrets.py"
    if not path.is_file():
        return None
    spec = importlib.util.spec_from_file_location("drishti_devsecrets", path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def bare_defaults(argv: list[str], env: dict, home: Path | None = None) -> tuple[list[str], list[str]]:
    """A bare run (no argument, no DRISHTI_BACKEND_URL(S)) is the launcher Drishti: run console: ``--settings ~/drishti-envs/dev.ini``
    (when there) ``--profile demo``, and the development secrets into ``env`` where none is set. (the arguments, the lines to print);
    any other run is left as it is."""
    if argv or (env.get("DRISHTI_BACKEND_URL") or env.get("DRISHTI_BACKEND_URLS") or "").strip():
        return argv, []
    ini = (home or Path.home()) / "drishti-envs" / "dev.ini"
    said = []
    from core import settings_ini
    if ini.is_file() and not settings_ini.names_home(settings_ini.read_all(str(ini)), HERE.parent):
        said.append(f"[WARN] {ini} belongs to another checkout: not used (Fix: {setup_line()})")
        argv = ["--profile", "demo"]
    elif ini.is_file():
        argv = ["--settings", str(ini), "--profile", "demo"]
        said.append(f"no arguments: the settings file {ini} with the demo profile (as the launcher Drishti: run console)")
    else:
        argv = ["--profile", "demo"]
        said.append(f"no arguments and no settings file at {ini} ({setup_line()} writes it): the demo profile only")
    mod = devsecrets()
    if mod is not None:
        used = mod.apply(env, mod.CONSOLE)
        if used:
            said.append("WARNING: " + mod.warning(used))
    return argv, said


def trust_roots(argv: list[str], environ, scratch: str | None = None) -> list[str]:
    """The tools' trust rule for a console run directly (an IDE's launcher; FIRST_RUN_ROBUSTNESS.md R5): every DRISHTI_* folder variable
    (settings_ini.ROOT_VARS) naming a folder outside this checkout is removed from ``environ``, unless it is the folder the accepted
    settings file of ``argv`` names, or under ``scratch`` (the system temp folder: a test's run). ``drishti.py start`` (it sets
    DRISHTI_STOP_KEY) has applied the rule already and passes its own resolved roots: left alone. The [WARN] lines to print."""
    from core import settings_ini
    if (environ.get("DRISHTI_STOP_KEY") or "").strip():
        return []
    named = settings_ini.pop_option(list(argv))[1]
    entries = settings_ini.read_all(named) if named else {}
    return settings_ini.drop_foreign_vars(environ, HERE.parent, entries, scratch)


def api_refusal(settings) -> str | None:
    """The one line when a server this console is pointed at (``backend.url``, or each of ``backend.urls``) answers with an API version outside
    the range this console supports (core/api_compat.py); None when all are compatible or away (start order does not matter: the backend
    watch checks again at every probe). ``backend.require_compatible_api: false`` skips the check."""
    if str(settings.get("backend.require_compatible_api", True)).lower() in ("false", "0", "no", "off"):
        return None
    from core import api_compat
    raw = settings.get("backend.urls") or ""
    urls = [u.strip() for u in (raw if isinstance(raw, list) else str(raw).split(",")) if u and str(u).strip()] or [str(settings.get("backend.url") or "")]
    for url in urls:
        found = api_compat.preflight(url) if url else None
        if found:
            return found
    return None


def main() -> None:
    import tempfile
    from core import settings_ini
    try:
        argv, said = bare_defaults(sys.argv[1:], os.environ)
        for line in said:
            print(line, file=sys.stderr)
        sys.argv[1:] = argv
        other = foreign_settings(sys.argv[1:])
        if other:                                      # another checkout's file: warned, and none of its roots followed (R6)
            print(f"[WARN] {other} belongs to another checkout: not used (Fix: {setup_line()})", file=sys.stderr)
            sys.argv[1:] = drop_settings(sys.argv[1:])
        for line in trust_roots(sys.argv[1:], os.environ, tempfile.gettempdir()):
            print(line, file=sys.stderr)
    except settings_ini.SettingsError as e:            # e.g. the launcher's ~/drishti-envs/dev.ini before setup: one line, no traceback
        raise SystemExit(settings_ini.fail(e)) from None
    settings = load_settings(HERE / "config", sys.argv[1:])
    from core import production
    production.enforce(settings)                  # the production profile refuses an unsafe start, one line per setting (core/production.py)
    from core.launcher_stop import MAIN_PID_ENV
    os.environ[MAIN_PID_ENV] = str(os.getpid())   # the launcher's stop signals this process, whichever worker takes the request (core/launcher_stop.py)
    refused = api_refusal(settings)
    if refused:                                    # a server this console cannot speak to: one line, no port opened (core/api_compat.py)
        raise SystemExit(f"[FAIL] {refused}")
    temp_under_data(settings)
    workers = max(1, int(settings.get("server.workers") or 1))
    # a stop closes the live streams after this long: a worker waits for its open connections, and a live page's stream never ends by itself
    grace = max(1, int(settings.get("server.shutdown_seconds") or 5))
    if workers > 1:
        # several processes share the port: one Python process uses one core (about 40 pages a second measured); nothing a
        # request needs lives in one process only (sessions are checked with the server, CSRF reads the Origin header)
        os.environ[ARGV] = json.dumps(sys.argv[1:])
        if not os.environ.get("DRISHTI_CONSOLE_RELAY_KEY"):        # the workers pass live-channel calls to each other (core/channel_relay.py)
            os.environ["DRISHTI_CONSOLE_RELAY_KEY"] = secrets.token_urlsafe(24)
            os.environ["DRISHTI_CONSOLE_RELAY_KEY_MADE"] = "1"      # this host's own key: across hosts every console needs the same one
        uvicorn.run("run_drishti_web:factory", factory=True, workers=workers, app_dir=str(HERE), host=settings.get("server.host"),
                    port=int(settings.get("server.port")), log_level="info", access_log=False, timeout_graceful_shutdown=grace,
                    log_config=log_config())
        return
    app = create_app(settings)
    app.state.tmp_owner = True                    # this process removes its temp folder when the server stops (core/tmpdir.py)
    uvicorn.run(app, host=settings.get("server.host"), port=int(settings.get("server.port")),
                log_level="info", access_log=False, timeout_graceful_shutdown=grace, log_config=log_config())


if __name__ == "__main__":
    main()
