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

"""A real scratch stack for the governance browser tests and the documentation pictures (Rupaka phase 4): the built server jar (security on, the trading pack) on :18992,
the console on :17992 with sign-in, a small file lake behind the connector ``trading-store`` (120 trades over two days, never millions), and the people and content
publishing needs: a workspace *Rates Desk*, a published and certified dataset *Trades model*, a published and certified report *Trades report* on it, and an unpublished
dataset *Positions model*. Everything lives in a scratch directory; ports in use are never touched (the stack refuses to start).

Users (password ``long-enough-pass-1``): ann writes (bi, bi-author), bob writes and approves (bi, bi-author, approve), cat reads (bi), sue stewards (bi, bi-steward),
nobi has no BI power; root is an administrator (every BI power). ann and bob are in Rates Desk (admin, member), cat is a viewer there; sue is not."""
from __future__ import annotations

import glob
import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.request
from pathlib import Path

from bi_report_stack import CONNECTOR, write_lake
from bi_stack import FORBIDDEN, listening, wait

ROOT = Path(__file__).resolve().parents[2]
CONSOLE = ROOT / "drishti-console"
SERVER_PORT = int(os.environ.get("DRISHTI_BI_GOV_SERVER_PORT", 18992))
CONSOLE_PORT = int(os.environ.get("DRISHTI_BI_GOV_CONSOLE_PORT", 17992))
SERVER = f"http://127.0.0.1:{SERVER_PORT}"
CONSOLE_URL = f"http://127.0.0.1:{CONSOLE_PORT}"
SECRET = "bi-gov-scratch-secret-0123456789abcdef01"
PASSWORD = "long-enough-pass-1"
ROLES = {"bi-reader": (["bi"], False), "bi-writer": (["bi", "bi-author"], False), "bi-reviewer": (["bi", "bi-author"], True), "bi-steward": (["bi", "bi-steward"], False), "bi-none": ([], False)}
USERS = {"ann": "bi-writer", "bob": "bi-reviewer", "cat": "bi-reader", "sue": "bi-steward", "nobi": "bi-none", "root": "admin"}

POSITIONS = """dataset: positions-model
title: Positions model
tables:
  trade:
    source: { connector: trading-store, kind: trade }
    mode: cached
    columns:
      desk: string
      book: string
      notional: decimal
measures:
  Notional: { expr: "sum(trade.notional)", format: amount0 }
  Trades: { expr: "count(trade)" }
"""

REPORT = """rachana: 1
report: trades-report
dataset: gov-trades
title: Trades report
pages:
  - id: overview
    title: Overview
    visuals:
      - { id: notional, type: card, title: Notional, at: { x: 0, y: 0, w: 3, h: 2 }, wells: { values: [Notional] } }
      - { id: by-desk, type: bar, title: Notional by desk, at: { x: 3, y: 0, w: 5, h: 4 }, wells: { axis: [trade.desk], values: [Notional] }, alt: Bars of notional by desk }
      - { id: rows, type: table, title: Desk and book, at: { x: 0, y: 4, w: 8, h: 4 }, wells: { columns: [trade.desk, trade.book, MTM] } }
"""


def call(method: str, path: str, user: str, roles: list, body=None):
    """The server's API as ``user`` on this stack's port. Returns (status, json)."""
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
    return call(method, path, "drishti-dev-admin", ["admin"], body)


def dataset_body() -> str:
    text = (ROOT / "config" / "bi" / "datasets" / "trades-basic.dataset.yaml").read_text()
    text = text.replace("dataset: trades-basic", "dataset: gov-trades").replace("title: Trades, the basics", "title: Trades model")
    text = re.sub(r"^(sample: true|tags: \[sample.*\]|endorsement: promoted).*\n", "", text, flags=re.M)
    text = re.sub(r"^\s*#.*\n", "", text, flags=re.M)                  # the sample's commentary stays in the sample; a desk's own model is shorter
    return re.sub(r"[ ]+# .*$", "", text, flags=re.M)


def seed() -> dict:
    """Roles, users and the content described at the top; returns the item ids."""
    for role, (bi, approve) in ROLES.items():
        s, r = admin("PUT", f"/admin/role-definitions/{role}", {"kinds": ["*"], "raw": True, "approve": approve, "bi": bi})
        assert s == 200, r
    for user, role in USERS.items():
        s, r = admin("POST", "/admin/users", {"username": user, "displayName": user.capitalize(), "email": f"{user}@desk.test", "roles": [role], "packs": ["trading"], "password": PASSWORD,
                                            "mustChangePassword": False})
        assert s in (200, 201), r
    ann = lambda m, p, b=None: call(m, p, "ann", ["bi-writer"], b)          # noqa: E731
    sue = lambda m, p, b=None: call(m, p, "sue", ["bi-steward"], b)         # noqa: E731
    s, ws = ann("POST", "/bi/workspaces", {"name": "Rates Desk", "description": "Rates trading: models and reports"})
    assert s == 201, ws
    ann("PUT", "/bi/workspaces/rates-desk/members/bob", {"role": "member"})
    ann("PUT", "/bi/workspaces/rates-desk/members/cat", {"role": "viewer"})
    ids = {}
    for key, kind, title, body, publish in (("model", "dataset", "Trades model", dataset_body(), True), ("report", "report", "Trades report", REPORT, True), ("positions", "dataset", "Positions model", POSITIONS, False)):
        s, it = ann("POST", "/bi/workspaces/rates-desk/items", {"kind": kind, "title": title, "description": f"{title} for the desk", "body": body, "message": "first cut", "tags": ["rates"]})
        assert s == 201, it
        ids[key] = it["id"]
        if publish:
            s, r = ann("POST", f"/bi/items/{it['id']}/publish", {"note": "first version"})
            assert s == 200, r
    for key in ("model", "report"):
        s, r = sue("POST", f"/bi/items/{ids[key]}/endorse", {"level": "certified"})
        assert s == 200, r
    return ids


class Stack:
    def __init__(self, tmp: Path):
        self.tmp, self.procs, self.ids = tmp, {}, {}

    def start(self) -> "Stack":
        try:
            return self._start()
        except BaseException:
            self.close()                                          # never leave a half-started stack listening
            raise

    def _start(self) -> "Stack":
        for port in (SERVER_PORT, CONSOLE_PORT):
            assert port not in FORBIDDEN and not listening(port), f"port {port} is in use: stop whatever listens there"
        jars = glob.glob(str(ROOT / "drishti-server/target/drishti-server-*-exec.jar"))
        java = os.environ.get("JAVA_HOME", "/usr/lib/jvm/java-21-openjdk-amd64") + "/bin/java"
        if not jars or not Path(java).exists():
            raise FileNotFoundError("needs the built server: ./mvnw -o -q package -DskipTests -pl drishti-server -am")
        work = self.tmp / "server"
        (work / "config" / "bi").mkdir(parents=True)
        lake = self.tmp / "lake"
        write_lake(lake)
        (work / "config" / "connectors").mkdir(parents=True)
        (work / "config" / "connectors" / "trading-store.yaml").write_text(CONNECTOR.format(root=lake))
        env = dict(os.environ, DRISHTI_PACKS="trading", DRISHTI_PACKS_DIR=str(ROOT / "config" / "packs"), DRISHTI_SECURITY_ENABLED="true", DRISHTI_TOKEN_SECRET=SECRET,
                   DRISHTI_BI_DIR=str(work / "config" / "bi"), DRISHTI_DATA_DIR=str(work / "data"))
        self.procs["server"] = subprocess.Popen([java, "-Xmx768m", "-jar", jars[0], f"--server.port={SERVER_PORT}", "--drishti.security.registered-users-only=false",
                                                 f"--drishti.bi.query.store-dir={self.tmp / 'store'}"], cwd=work, env=env, stdout=open(self.tmp / "server.log", "ab"), stderr=subprocess.STDOUT)
        wait(SERVER + "/actuator/health", 150)
        self.ids = seed()
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
