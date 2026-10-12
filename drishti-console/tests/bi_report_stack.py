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

"""A real scratch stack for the report browser tests and the documentation pictures (Rupaka phase 3): the built server jar (security on, the trading and
counterparty-risk packs) on :18990, the console on :17990 with sign-in, and the three sample datasets and the three sample reports of ``config/bi``. The data is
a Delta lake made by ``tools/packgen/banking/make_data.py --lake`` (``DRISHTI_BI_DELTA_ROOT``; a few thousand rows, never millions), or, without one, a small
lake of JSON-lines files read through a file connector named ``trading-store`` that serves the first sample (``trades-overview``) alone. Everything lives in a
scratch directory; ports in use are never touched (the stack refuses to start).

Users (password ``long-enough-pass-1``): wes writes (bi, bi-author), rita is risk (bi, raw), hana is a desk head (bi, raw, desk DESK-FX), ann is an analyst
(bi, no raw: masked columns read the mask), nobi has no BI power; the built-in admin ``drishti-dev-admin`` holds everything."""
from __future__ import annotations

import glob
import json
import os
import shutil
import subprocess
import sys
import time
from pathlib import Path

from bi_stack import FORBIDDEN, call, listening, wait  # noqa: F401  (call is re-created below for this stack's port)

ROOT = Path(__file__).resolve().parents[2]
CONSOLE = ROOT / "drishti-console"
SERVER_PORT = int(os.environ.get("DRISHTI_BI_REPORT_SERVER_PORT", 18990))
CONSOLE_PORT = int(os.environ.get("DRISHTI_BI_REPORT_CONSOLE_PORT", 17990))
SERVER = f"http://127.0.0.1:{SERVER_PORT}"
CONSOLE_URL = f"http://127.0.0.1:{CONSOLE_PORT}"
SECRET = "bi-report-scratch-secret-0123456789abcdef"
PASSWORD = "long-enough-pass-1"
DESKS = ["DESK-FX", "DESK-FI", "DESK-EQD"]
BOOKS = ["BOOK-FX-1", "BOOK-FI-1", "BOOK-EQ-1"]


def make_lake(target: Path, days: int = 3) -> str | None:
    """A small Delta lake (a few thousand rows, never millions) made with ``tools/packgen/banking/make_data.py --lake``, which the cross-pack and the cashflow samples
    read. ``DRISHTI_BI_DELTA_ROOT`` names one that exists; else it is made with ``uv run --with deltalake ...`` (needs uv and its cache); None when neither works."""
    have = os.environ.get("DRISHTI_BI_DELTA_ROOT")
    if have:
        return have
    uv = shutil.which("uv")
    if not uv:
        return None
    done = subprocess.run([uv, "run", "--offline", "--with", "deltalake", "--with", "pyarrow", "--with", "pyyaml", "python", str(ROOT / "tools/packgen/banking/make_data.py"), "--lake",
                           str(target), "--days", str(days)], capture_output=True, text=True, timeout=600, cwd=ROOT)
    return str(target) if done.returncode == 0 and (target / "trading").exists() else None


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


def trade(day: str, i: int) -> dict:
    n = 1000 + i
    return {"id": f"BBG-{day.replace('-', '')[4:]}{i:03d}", "tradeId": f"BBG-{day.replace('-', '')[4:]}{i:03d}", "desk": DESKS[i % 3], "book": BOOKS[i % 3],
            "productType": ["FX_FORWARD", "GOVT_BOND", "EQ_OPTION"][i % 3], "currency": ["USD", "EUR", "GBP"][i % 3], "status": "Live", "notional": 1_000_000.0 * (1 + i % 7),
            "mtm": ((i % 9) - 4) * 25_000, "pnl1d": ((i % 5) - 2) * 4_000, "tradeDate": day, "n": n}


def write_lake(root: Path, days=("2026-10-07", "2026-10-08"), rows=60) -> None:
    for day in days:
        folder = root / day
        folder.mkdir(parents=True, exist_ok=True)
        with open(folder / "trade.jsonl", "w") as f:
            for i in range(rows):
                f.write(json.dumps(trade(day, i)) + "\n")


CONNECTOR = """plugin: file
kinds: [trade]
settings:
  root: {root}
  rescan-seconds: 3600
  layout:
    trade:
      columns: [tradeId, desk, book, productType, currency, status, notional, mtm, pnl1d, tradeDate]
"""


def seed(packs: list) -> None:
    for role, bi, raw in [("risk", ["bi"], True), ("bi-writer", ["bi", "bi-author"], True), ("analyst", ["bi"], False), ("desk-head", ["bi"], True), ("bi-none", [], False)]:
        s, r = admin("PUT", f"/admin/role-definitions/{role}", {"kinds": ["*"], "raw": raw, "bi": bi})
        assert s == 200, r
    for user, role, desk in [("wes", "bi-writer", ""), ("rita", "risk", ""), ("hana", "desk-head", "DESK-FX"), ("ann", "analyst", ""), ("nobi", "bi-none", "")]:
        s, r = admin("POST", "/admin/users", {"username": user, "displayName": user.capitalize(), "email": f"{user}@desk.test", "desk": desk, "roles": [role],
                                            "packs": packs, "password": PASSWORD, "mustChangePassword": False})
        assert s in (200, 201, 409), r                                # 409: already seeded (a server the previous file left running)


class ReportStack:
    def __init__(self, tmp: Path, delta_root: str | None = None):
        self.tmp, self.procs, self.delta_root = tmp, {}, delta_root or os.environ.get("DRISHTI_BI_DELTA_ROOT")
        self.server_args: list = []                               # extra command-line settings for the server (a test adds its SMTP host here)

    def prepare(self, work: Path) -> None:
        """Called with the server's scratch directory after the sample content is copied in and before the server starts: a test changes the content here."""

    def start(self) -> "ReportStack":
        try:
            return self._start()
        except BaseException:
            self.close()                                          # never leave a half-started stack listening
            raise

    def _start(self) -> "ReportStack":
        for port in (SERVER_PORT, CONSOLE_PORT):
            assert port not in FORBIDDEN and not listening(port), f"port {port} is in use: stop whatever listens there"
        jars = sorted(glob.glob(str(ROOT / "drishti-server/target/drishti-server-*-exec.jar")), key=os.path.getmtime, reverse=True)  # newest build first: target may hold older versions
        java = os.environ.get("JAVA_HOME", "/usr/lib/jvm/java-21-openjdk-amd64") + "/bin/java"
        if not jars or not Path(java).exists():
            raise FileNotFoundError("needs the built server: ./mvnw -o -q package -DskipTests -pl drishti-server -am")
        work = self.tmp / "server"
        bi = work / "config" / "bi" / "datasets"
        bi.mkdir(parents=True)
        wanted = ["trades-basic"] if not self.delta_root else ["trades-basic", "trading-risk", "trading-cashflows"]
        for name in wanted:
            shutil.copy(ROOT / "config" / "bi" / "datasets" / f"{name}.dataset.yaml", bi)
        reports = work / "config" / "bi" / "reports"
        reports.mkdir(parents=True)
        for name in ["trades-overview"] if not self.delta_root else ["trades-overview", "counterparty-risk", "cashflow-ladder"]:
            shutil.copy(ROOT / "config" / "bi" / "reports" / f"{name}.report.yaml", reports)
        self.prepare(work)
        env = dict(os.environ, DRISHTI_PACKS_DIR=str(ROOT / "config" / "packs"), DRISHTI_SECURITY_ENABLED="true", DRISHTI_TOKEN_SECRET=SECRET,
                   DRISHTI_BI_DIR=str(work / "config" / "bi"), DRISHTI_DATA_DIR=str(work / "data"))
        if self.delta_root:
            env["DRISHTI_PACKS"] = "trading,counterparty-risk"
            env["DRISHTI_DELTA_ROOT"] = self.delta_root
        else:
            env["DRISHTI_PACKS"] = "trading"
            lake = self.tmp / "lake"
            write_lake(lake)
            (work / "config" / "connectors").mkdir(parents=True)
            (work / "config" / "connectors" / "trading-store.yaml").write_text(CONNECTOR.format(root=lake))
        self.procs["server"] = subprocess.Popen([java, "-Xmx768m", "-jar", jars[0], f"--server.port={SERVER_PORT}", "--drishti.security.registered-users-only=false",
                                                 f"--drishti.bi.query.store-dir={self.tmp / 'store'}", *self.server_args], cwd=work, env=env, stdout=open(self.tmp / "server.log", "ab"), stderr=subprocess.STDOUT)
        wait(SERVER + "/actuator/health", 150)
        seed(['trading', 'counterparty-risk'] if self.delta_root else ['trading'])
        cenv = dict(os.environ, DRISHTI_AUTH_ENABLED="true", DRISHTI_SESSION_SECRET="s" * 40, DRISHTI_TOKEN_SECRET=SECRET, DRISHTI_SECURE_COOKIE="false",
                    DRISHTI_SESSION_RECHECK_SECONDS="1")
        self.procs["console"] = subprocess.Popen([sys.executable, str(CONSOLE / "run_drishti_web.py"), f"--server.port={CONSOLE_PORT}", f"--backend.url={SERVER}"], env=cenv,
                                                 stdout=open(self.tmp / "console.log", "ab"), stderr=subprocess.STDOUT)
        wait(CONSOLE_URL + "/healthz")
        return self

    def close(self) -> None:
        for p in self.procs.values():
            p.terminate()
        for p in self.procs.values():
            try:
                p.wait(20)
            except subprocess.TimeoutExpired:
                p.kill()
