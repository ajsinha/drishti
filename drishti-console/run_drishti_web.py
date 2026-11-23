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

    python drishti-console/run_drishti_web.py [--server.port=17480] [--backend.url=http://...]
"""
from __future__ import annotations

import json
import secrets
import os
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import uvicorn  # noqa: E402

from core.app import create_app  # noqa: E402
from core.config import load_settings  # noqa: E402


ARGV = "DRISHTI_CONSOLE_ARGV"


def factory():
    """One worker's app (server.workers > 1): the settings from the launcher's arguments, passed through the environment."""
    settings = load_settings(HERE / "config", json.loads(os.environ.get(ARGV, "[]")))
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


def warn_other_home(argv: list[str]) -> str | None:
    """A WARNING when the --settings file names a drishti.home other than this checkout (an old settings file from an earlier copy)."""
    from core import settings_ini
    named = next((argv[i + 1] for i, a in enumerate(argv[:-1]) if a == "--settings"), None) \
        or next((a.split("=", 1)[1] for a in argv if a.startswith("--settings=")), None)
    if not named:
        return None
    home = (settings_ini.read_all(named).get("drishti.home") or "").strip()
    here = HERE.parent
    if not home:
        return None
    norm = lambda p: os.path.normcase(os.path.normpath(str(Path(p).expanduser().absolute()))).rstrip("\\/")
    try:
        if Path(home).exists() and os.path.samefile(home, here):
            return None
    except OSError:
        pass
    if norm(home) == norm(here):
        return None
    return (f"WARNING: your settings file names drishti.home={home}, but this console is in {here}: its data comes from {home}."
            f" If that is not what you want, rename the settings file (dev.ini) and run python tools/drishti.py setup again.")


def main() -> None:
    warning = warn_other_home(sys.argv[1:])
    if warning:
        print(warning, file=sys.stderr)
    settings = load_settings(HERE / "config", sys.argv[1:])
    from core.launcher_stop import MAIN_PID_ENV
    os.environ[MAIN_PID_ENV] = str(os.getpid())   # the launcher's stop signals this process, whichever worker takes the request (core/launcher_stop.py)
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
