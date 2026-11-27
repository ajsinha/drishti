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

"""``drishti.py node drain|undrain|status|wait-ready``: one server's drain before a stop and its readiness (docs/guides/CLUSTER_GUIDE.md
sections 4.6, 9.1 and 10.1), the same on Linux, macOS and Windows. The server drains itself when it is stopped (SIGTERM, a service stop,
docker stop: ``drishti.node.drain.on-stop``); these commands are for an operator or a script that drains ahead of a stop.

    drishti.py node drain [--wait SECONDS]     drain; wait until it is safe to stop (exit 0), or the wait ends (exit 3)
    drishti.py node undrain                    take work again
    drishti.py node status                     the node's readiness and drain (JSON)
    drishti.py node wait-ready [--wait S]      wait until /actuator/health/readiness answers UP (exit 0) or the wait ends (exit 3)

The server: --server, else DRISHTI_NODE_URL, else the settings files' (console.backend.url, a profile's server.port), else
http://localhost:18480. The token: DRISHTI_DRAIN_TOKEN, else the file DRISHTI_DRAIN_TOKEN_FILE names, else DRISHTI_TOKEN (an
administrator's personal API token with the node:drain scope; none is needed with sign-in off); never printed."""
from __future__ import annotations

import json
import os
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))
import procs  # noqa: E402
import javacmd  # noqa: E402
import settingsfile  # noqa: E402


def say(text: str) -> None:
    print(f"drishti-node: {text}", file=sys.stderr, flush=True)


def server(a, env=None) -> str:
    env = os.environ if env is None else env
    url = (getattr(a, "server", None) or env.get("DRISHTI_NODE_URL") or settingsfile.server_url(javacmd.settings()[1], "localhost")
           or f"http://localhost:{env.get('DRISHTI_PORT') or procs.DEFAULT_SERVER_PORT}")
    return url.rstrip("/")


def token(env=None) -> str:
    env = os.environ if env is None else env
    if (env.get("DRISHTI_DRAIN_TOKEN") or "").strip():
        return env["DRISHTI_DRAIN_TOKEN"].strip()
    f = (env.get("DRISHTI_DRAIN_TOKEN_FILE") or "").strip()
    if f and Path(f).is_file():
        return "".join(Path(f).read_text(encoding="utf-8").split())
    return (env.get("DRISHTI_TOKEN") or "").strip()


def http(url: str, method: str, path: str, body: dict | None = None, tok: str = "") -> tuple[int, str]:
    """(status, body); status 0 when the server does not answer."""
    headers = {"Authorization": "Bearer " + tok} if tok else {"X-Drishti-No-Token": "1"}
    data = None
    if body is not None:
        data, headers["Content-Type"] = json.dumps(body).encode(), "application/json"
    try:
        with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(urllib.request.Request(url + path, data=data, method=method, headers=headers), timeout=10) as r:
            return r.status, r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")
    except (OSError, ValueError):
        return 0, ""


def safe(answer: str) -> bool:
    """The node's own safeToStop (each part's comes after it)."""
    try:
        return bool(json.loads(answer).get("safeToStop"))
    except (ValueError, AttributeError):
        return False


def drain(a, env=None, clock=time.monotonic, sleep=time.sleep) -> int:
    url, tok = server(a, env), token(env)
    wait = int(a.wait if a.wait is not None else (env or os.environ).get("DRISHTI_DRAIN_WAIT") or 90)
    status, answer = http(url, "POST", "/api/v1/admin/node/drain", {"on": True}, tok)
    if not 200 <= status < 300:
        say(f"the drain was refused ({status}) or {url} did not answer: stopping without it")
        return 3
    say(f"draining {url} (forwarded work and shards go to other members), waiting up to {wait}s for safeToStop")
    end = clock() + wait
    while True:
        if safe(answer):
            say("safe to stop")
            return 0
        if clock() >= end:
            say(f"not safe to stop after {wait}s: stopping anyway (the shards' replicas answer; their next owners build them)")
            return 3
        sleep(2)
        status, answer = http(url, "GET", "/api/v1/admin/node", None, tok)


def main(a) -> int:
    url = server(a)
    if a.cmd == "drain":
        return drain(a)
    if a.cmd == "undrain":
        status, _ = http(url, "POST", "/api/v1/admin/node/drain", {"on": False}, token())
        if 200 <= status < 300:
            say("undrained: takes work again")
            return 0
        say(f"the undrain was refused ({status}) or {url} did not answer")
        return 1
    if a.cmd == "status":
        status, answer = http(url, "GET", "/api/v1/admin/node", None, token())
        print(answer)
        return 0 if 200 <= status < 300 else 1
    if a.cmd == "wait-ready":
        end = time.monotonic() + int(a.wait if a.wait is not None else 600)
        while True:
            if http(url, "GET", "/actuator/health/readiness")[0] == 200:
                say("ready")
                return 0
            if time.monotonic() >= end:
                say(f"not ready after {a.wait if a.wait is not None else 600}s")
                return 3
            time.sleep(3)
    return 2


def register(groups) -> None:
    import argparse
    n = groups.add_parser("node", help="node drain|undrain|status|wait-ready: one server's drain before a stop and its readiness (CLUSTER_GUIDE.md 4.6)",
                          description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    n.add_argument("cmd", choices=("drain", "undrain", "status", "wait-ready"))
    n.add_argument("--server", metavar="URL", help="the server (default DRISHTI_NODE_URL, else the settings files', else http://localhost:18480)")
    n.add_argument("--wait", type=int, metavar="SECONDS", help="drain: how long to wait for safeToStop (default DRISHTI_DRAIN_WAIT, else 90); wait-ready: default 600")
    n.set_defaults(func=lambda a, extra: main(a))
