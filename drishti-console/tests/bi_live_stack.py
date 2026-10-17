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

"""A real scratch stack for the streaming BI browser tests and the documentation pictures (Rupaka phase 5): the built server jar (security on, the trading and
counterparty-risk packs, the demo source ticking every 200 ms) on :18995, the console on :17995 with sign-in, and the shipped live content of ``config/bi``: the
datasets ``live-trading`` and ``live-exposure``, the reports ``live-trading-desk`` and ``live-exposure`` and the board ``desk-wall``. The live tables follow the
trades and netting sets the demo ticker moves (750 trades; never millions). Everything lives in a scratch directory; ports in use are never touched (the stack
refuses to start).

Users (password ``long-enough-pass-1``): rita is risk (bi, raw), ann is an analyst (bi, no raw: masked columns read the mask), wes writes (bi, bi-author), nobi has
no BI power; the built-in admin ``drishti-dev-admin`` holds everything."""
from __future__ import annotations

import glob
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

from bi_stack import FORBIDDEN, listening, wait

ROOT = Path(__file__).resolve().parents[2]
CONSOLE = ROOT / "drishti-console"
# Each pytest-xdist worker collects every test file first, so the first file's setdefault of the ports wins on every worker:
# without an offset two workers would start (or reuse) one server on the same ports. gw0 keeps the ports, gw1 adds 100, …
_WORKER = int((os.environ.get("PYTEST_XDIST_WORKER") or "gw0")[2:] or 0)
SERVER_PORT = int(os.environ.get("DRISHTI_BI_LIVE_SERVER_PORT", 18995)) + 100 * _WORKER
CONSOLE_PORT = int(os.environ.get("DRISHTI_BI_LIVE_CONSOLE_PORT", 17995)) + 100 * _WORKER
SERVER = f"http://127.0.0.1:{SERVER_PORT}"
CONSOLE_URL = f"http://127.0.0.1:{CONSOLE_PORT}"
SECRET = "bi-live-scratch-secret-0123456789abcdef"
PASSWORD = "long-enough-pass-1"
REPORT = "file:report:live-trading-desk"
EXPOSURE = "file:report:live-exposure"
BOARD = "file:board:desk-wall"
LIVE_FILES = {"datasets": ["live-trading", "live-exposure"], "reports": ["live-trading-desk", "live-exposure"], "boards": ["desk-wall"]}
ROLES = {"live-risk": (["bi"], True), "live-analyst": (["bi"], False), "live-writer": (["bi", "bi-author"], True), "live-none": ([], False)}
USERS = {"rita": "live-risk", "ann": "live-analyst", "wes": "live-writer", "nobi": "live-none"}


def api(method: str, path: str, user: str, roles: list, body=None):
    """The server's API as ``user`` on this stack's port. Returns (status, json)."""
    import urllib.error
    import urllib.request

    sys.path.insert(0, str(CONSOLE))
    from core.auth import mint_token

    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(SERVER + "/api/v1" + path, data, {"Authorization": "Bearer " + mint_token(user, roles, SECRET, 300), "Content-Type": "application/json"}, method=method)
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            raw = r.read()
            return r.status, (json.loads(raw) if raw else {})
    except urllib.error.HTTPError as e:
        raw = e.read()
        return e.code, (json.loads(raw) if raw else {})


def admin(method: str, path: str, body=None):
    return api(method, path, "drishti-dev-admin", ["admin"], body)


def seed() -> None:
    for role, (bi, raw) in ROLES.items():
        s, r = admin("PUT", f"/admin/role-definitions/{role}", {"kinds": ["*"], "raw": raw, "bi": bi})
        assert s == 200, r
    for user, role in USERS.items():
        s, r = admin("POST", "/admin/users", {"username": user, "displayName": user.capitalize(), "email": f"{user}@desk.test", "roles": [role],
                                            "packs": ["trading", "counterparty-risk", "market-risk"], "password": PASSWORD, "mustChangePassword": False})
        assert s in (200, 201, 409), r                                # 409: already seeded (a server the previous file left running)


class LiveStack:
    def __init__(self, tmp: Path, extra_args: list[str] | None = None):
        self.tmp, self.procs, self.extra = tmp, {}, extra_args or []

    def start(self) -> "LiveStack":
        try:
            return self._start()
        except BaseException:
            self.close()                                          # never leave a half-started stack listening
            raise

    def _start(self) -> "LiveStack":
        for port in (SERVER_PORT, CONSOLE_PORT):
            assert port not in FORBIDDEN and not listening(port), f"port {port} is in use: stop whatever listens there"
        jars = sorted(glob.glob(str(ROOT / "drishti-server/target/drishti-server-*-exec.jar")), key=os.path.getmtime, reverse=True)  # newest build first: target may hold older versions
        java = os.environ.get("JAVA_HOME", "/usr/lib/jvm/java-21-openjdk-amd64") + "/bin/java"
        if not jars or not Path(java).exists():
            raise FileNotFoundError("needs the built server: ./mvnw -o -q package -DskipTests -pl drishti-server -am")
        work = self.tmp / "server"
        for kind, names in LIVE_FILES.items():
            folder = work / "config" / "bi" / kind
            folder.mkdir(parents=True)
            for name in names:
                suffix = {"datasets": "dataset", "reports": "report", "boards": "board"}[kind]
                shutil.copy(ROOT / "config" / "bi" / kind / f"{name}.{suffix}.yaml", folder)
        env = dict(os.environ, DRISHTI_PACKS_DIR=str(ROOT / "config" / "packs"), DRISHTI_PACKS="trading,counterparty-risk,market-risk", DRISHTI_SECURITY_ENABLED="true",
                   DRISHTI_TOKEN_SECRET=SECRET, DRISHTI_BI_DIR=str(work / "config" / "bi"), DRISHTI_DATA_DIR=str(work / "data"))
        self.procs["server"] = subprocess.Popen([java, "-Xmx768m", "-jar", jars[0], f"--server.port={SERVER_PORT}", "--drishti.security.registered-users-only=false",
                                                 "--drishti.sources.plugins.demo.settings.tick-ms=200", f"--drishti.bi.query.store-dir={self.tmp / 'store'}", *self.extra],
                                                cwd=work, env=env, stdout=open(self.tmp / "server.log", "ab"), stderr=subprocess.STDOUT)
        wait(SERVER + "/actuator/health", 150)
        seed()
        self._start_console()
        return self

    def _start_console(self) -> None:
        cenv = dict(os.environ, DRISHTI_AUTH_ENABLED="true", DRISHTI_SESSION_SECRET="s" * 40, DRISHTI_TOKEN_SECRET=SECRET, DRISHTI_SECURE_COOKIE="false",
                    DRISHTI_SESSION_RECHECK_SECONDS="1")
        self.procs["console"] = subprocess.Popen([sys.executable, str(CONSOLE / "run_drishti_web.py"), f"--server.port={CONSOLE_PORT}", f"--backend.url={SERVER}"], env=cenv,
                                                 stdout=open(self.tmp / "console.log", "ab"), stderr=subprocess.STDOUT)
        wait(CONSOLE_URL + "/healthz")

    def stop_console(self) -> None:
        """Ends the console (not the server): every open stream of every browser drops, as when the console is restarted."""
        p = self.procs.pop("console")
        p.terminate()
        try:
            p.wait(20)
        except subprocess.TimeoutExpired:
            p.kill()

    def start_console(self) -> None:
        self._start_console()

    def close(self) -> None:
        for p in self.procs.values():
            p.terminate()
        for p in self.procs.values():
            try:
                p.wait(20)
            except subprocess.TimeoutExpired:
                p.kill()
