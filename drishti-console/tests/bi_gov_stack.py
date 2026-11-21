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

from bi_report_stack import CONNECTOR, TRADE_CONNECTOR, write_lake
from bi_stack import FORBIDDEN, listening, wait
from java_launch import java_cmd, server_jars
from scratch_ports import worker_port

ROOT = Path(__file__).resolve().parents[2]
CONSOLE = ROOT / "drishti-console"
# Each pytest-xdist worker collects every test file first, so the first file's setdefault of the ports wins on every worker:
# without an offset two workers would start (or reuse) one server on the same ports. gw0 keeps the ports, gw1 adds 100, …
SERVER_PORT = worker_port(int(os.environ.get("DRISHTI_BI_GOV_SERVER_PORT", 18992)))
CONSOLE_PORT = worker_port(int(os.environ.get("DRISHTI_BI_GOV_CONSOLE_PORT", 17992)))
SERVER = f"http://127.0.0.1:{SERVER_PORT}"
CONSOLE_URL = f"http://127.0.0.1:{CONSOLE_PORT}"
SECRET = "bi-gov-scratch-secret-0123456789abcdef01"
PASSWORD = "long-enough-pass-1"
ROLES = {"bi-reader": (["bi"], False), "bi-writer": (["bi", "bi-author"], False), "bi-reviewer": (["bi", "bi-author"], True), "bi-steward": (["bi", "bi-steward"], False), "bi-none": ([], False)}
USERS = {"ann": "bi-writer", "bob": "bi-reviewer", "cat": "bi-reader", "sue": "bi-steward", "nobi": "bi-none", "root": "admin"}

POSITIONS_TABLE = """rachana: 3
table: bi.positions_model_trade
version: 1.0.0
title: "Positions model: trade"
from: { kind: trade, connector: trading-store }
columns:
  desk:     { path: "$.desk", type: string }
  book:     { path: "$.book", type: string }
  notional: { path: "$.notional", type: decimal }
materialize: on-load
"""

POSITIONS_MODEL = """rachana: 3
model: positions-model
version: 1.0.0
title: Positions model
tables:
  trade: { table: bi.positions_model_trade, role: fact }
measures:
  Notional: { expr: sum(trade.notional), format: amount0 }
  Trades:   { expr: count(trade) }
"""

REPORT = """rachana: 2
report: trades-report
version: 1
title: Trades report
model: gov-trades@1
pages:
  - id: overview
    title: Overview
    visuals:
      - { id: notional, type: card, title: Notional, at: { x: 0, y: 0, w: 3, h: 2 }, wells: { values: [Notional] } }
      - { id: by-desk, type: bar, title: Notional by desk, at: { x: 3, y: 0, w: 5, h: 4 }, wells: { axis: [trade.DESK], values: [Notional] }, alt: Bars of notional by desk }
      - { id: rows, type: table, title: Desk and book, at: { x: 0, y: 4, w: 8, h: 4 }, wells: { columns: [trade.DESK, trade.PORTFOLIO, MTM] } }
"""


def _shipped_trades(kind: str) -> str:
    """The shipped trades-basic table or model (config/tables/banking), as this desk's own: renamed gov-trades, without the sample marks."""
    name = "trades_basic_trade.table.yaml" if kind == "table" else "trades-basic.model.yaml"
    text = (ROOT / "config" / "tables" / "banking" / name).read_text()
    text = text.replace("banking.trades_basic_trade", "bi.gov_trades_trade").replace("was: trades-basic-trade\n", "").replace("model: trades-basic", "model: gov-trades")
    text = text.replace('title: "Trades, the basics: trade"', "title: Trades table").replace('title: "Trades, the basics"', "title: Trades model")
    text = re.sub(r"^(sample: true|tags: \[sample.*\]|endorsement: promoted).*\n", "", text, flags=re.M)
    text = re.sub(r"^\s*#.*\n", "", text, flags=re.M)
    return re.sub(r"[ ]+# .*$", "", text, flags=re.M)


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


def seed(tmp: Path) -> dict:
    """Roles, users and the content described at the top; returns the item ids."""
    for role, (bi, approve) in ROLES.items():
        s, r = admin("PUT", f"/admin/role-definitions/{role}", {"kinds": ["*"], "raw": True, "approve": approve, "bi": bi})
        assert s == 200, r
    for user, role in USERS.items():
        s, r = admin("POST", "/admin/users", {"username": user, "displayName": user.capitalize(), "email": f"{user}@desk.test", "roles": [role], "packs": ["trading", "market-risk"], "password": PASSWORD,
                                            "mustChangePassword": False})
        assert s in (200, 201, 409), r                                # 409: already seeded (a server the previous file left running)
    ann = lambda m, p, b=None: call(m, p, "ann", ["bi-writer"], b)          # noqa: E731
    sue = lambda m, p, b=None: call(m, p, "sue", ["bi-steward"], b)         # noqa: E731
    s, ws = ann("POST", "/bi/workspaces", {"name": "Rates Desk", "description": "Rates trading: models and reports"})
    assert s == 201, ws
    ann("PUT", "/bi/workspaces/rates-desk/members/bob", {"role": "member"})
    ann("PUT", "/bi/workspaces/rates-desk/members/cat", {"role": "viewer"})
    # BI reads the table layer: the desk's own tables, models and a report
    texts = {"table": _shipped_trades("table"), "model": _shipped_trades("model")}
    ids = {}
    audience = ["bi-steward", "bi-reader", "bi-writer", "bi-reviewer"]
    plan = [("table", "table", "Trades table", texts["table"], True),
            ("positions-table", "table", "Positions table", POSITIONS_TABLE, True),
            ("model", "model", "Trades model", texts["model"], True),
            ("report", "report", "Trades report", REPORT, True),
            ("positions", "model", "Positions model", POSITIONS_MODEL, False)]
    for key, kind, title, body, publish in plan:
        s, it = ann("POST", "/bi/workspaces/rates-desk/items", {"kind": kind, "title": title, "description": f"{title} for the desk", "body": body, "message": "first cut",
                                                                "tags": ["rates"], "audienceRoles": audience})
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
        jars = server_jars()
        java = os.environ.get("JAVA_HOME", "/usr/lib/jvm/java-21-openjdk-amd64") + "/bin/java"
        if not jars or not Path(java).exists():
            raise FileNotFoundError("needs the built server: ./mvnw -o -q package -DskipTests -pl :drishti-app -am")
        work = self.tmp / "server"
        (work / "config" / "bi").mkdir(parents=True)
        lake = self.tmp / "lake"
        write_lake(lake)
        (work / "config" / "connectors").mkdir(parents=True)
        (work / "config" / "connectors" / "trading-store.yaml").write_text(TRADE_CONNECTOR.format(root=lake / "trade"))
        (work / "config" / "connectors" / "risk-store.yaml").write_text(CONNECTOR.format(root=lake / "risk"))          # the valuations the 2.1 sample reads (2.3.0)
        env = dict(os.environ, DRISHTI_PACKS="trading,market-risk", DRISHTI_PACKS_DIR=str(ROOT / "config" / "packs"), DRISHTI_SECURITY_ENABLED="true", DRISHTI_TOKEN_SECRET=SECRET,
                   DRISHTI_BI_DIR=str(work / "config" / "bi"), DRISHTI_DATA_DIR=str(work / "data"))
        self.procs["server"] = subprocess.Popen(java_cmd(java, ["-Xmx768m", "-jar", jars[0], f"--server.port={SERVER_PORT}", "--drishti.security.registered-users-only=false",
                                                 f"--drishti.bi.query.store-dir={self.tmp / 'store'}"], tmp=self.tmp / "jvm-tmp", cwd=work, env=env), cwd=work, env=env, stdout=open(self.tmp / "server.log", "ab"), stderr=subprocess.STDOUT)
        wait(SERVER + "/actuator/health", proc=self.procs["server"])
        self.ids = seed(self.tmp / "migrate")
        cenv = dict(os.environ, DRISHTI_AUTH_ENABLED="true", DRISHTI_SESSION_SECRET="s" * 40, DRISHTI_TOKEN_SECRET=SECRET, DRISHTI_SECURE_COOKIE="false",
                    DRISHTI_SESSION_RECHECK_SECONDS="1")
        self.procs["console"] = subprocess.Popen([sys.executable, str(CONSOLE / "run_drishti_web.py"), f"--server.port={CONSOLE_PORT}", f"--backend.url={SERVER}"], env=cenv,
                                                 stdout=open(self.tmp / "console.log", "ab"), stderr=subprocess.STDOUT)
        wait(CONSOLE_URL + "/healthz", proc=self.procs["console"])
        return self

    def close(self) -> None:
        for p in self.procs.values():
            p.terminate()
        for p in self.procs.values():
            try:
                p.wait(20)
            except subprocess.TimeoutExpired:
                p.kill()
