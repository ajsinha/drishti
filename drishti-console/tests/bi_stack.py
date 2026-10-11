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

"""A real scratch stack for the BI browser tests and the documentation pictures: the built server jar (security on, the trading pack) on :18983, the
console on :17983 with sign-in, and the people and content Rupaka phase 1 needs (roles with BI powers, five users, a shared workspace with published
items, a shipped file). Everything lives in a scratch directory; ports that are already in use are never touched (the stack refuses to start).

Users (password ``long-enough-pass-1``): ann writes (bi, bi-author), bob reviews (bi, bi-author, approve), cat reads (bi), sue stewards (bi, bi-steward),
nobi has no BI power; the built-in admin ``drishti-dev-admin`` holds everything."""
from __future__ import annotations

import glob
import json
import os
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CONSOLE = ROOT / "drishti-console"
SERVER_PORT = int(os.environ.get("DRISHTI_BI_SERVER_PORT", 18983))
CONSOLE_PORT = int(os.environ.get("DRISHTI_BI_CONSOLE_PORT", 17983))
SERVER = f"http://127.0.0.1:{SERVER_PORT}"
CONSOLE_URL = f"http://127.0.0.1:{CONSOLE_PORT}"
SECRET = "bi-scratch-secret-0123456789abcdef0123"
PASSWORD = "long-enough-pass-1"
FORBIDDEN = {5432, 9092, 5672, 61616, 6379, 2181, 18480, 17480}

SHIPPED = {"reports/liquidity-daily.report.yaml": "title: Liquidity daily\ndescription: Shipped with the site, read-only\nowner: treasury\ntags: [liquidity, daily]\nendorsement: promoted\npages: []\n",
           "datasets/trades.dataset.yaml": "title: Trades (shipped)\ndescription: The trades table, shipped\nowner: treasury\ntags: [trades]\ntables: []\n"}


def listening(port: int) -> bool:
    with socket.socket() as s:
        s.settimeout(0.3)
        return s.connect_ex(("127.0.0.1", port)) == 0


def wait(url: str, seconds: float = 120) -> None:
    end = time.time() + seconds
    while time.time() < end:
        try:
            urllib.request.urlopen(url, timeout=2).read()
            return
        except urllib.error.HTTPError:
            return
        except Exception:  # noqa: BLE001 - not up yet
            time.sleep(0.4)
    raise RuntimeError(f"{url} did not come up")


def call(method: str, path: str, user: str, roles: list, body=None):
    """The server's API as ``user`` (the stack's secret signs the token, as the console's does). Returns (status, json)."""
    sys.path.insert(0, str(CONSOLE))
    from core.auth import mint_token

    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(SERVER + "/api/v1" + path, data, {"Authorization": "Bearer " + mint_token(user, roles, SECRET, 300), "Content-Type": "application/json"}, method=method)
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            raw = r.read()
            return r.status, (json.loads(raw) if raw else {})
    except urllib.error.HTTPError as e:
        raw = e.read()
        return e.code, (json.loads(raw) if raw else {})


def admin(method: str, path: str, body=None):
    return call(method, path, "drishti-dev-admin", ["admin"], body)


ROLES = {"bi-reader": ["bi"], "bi-writer": ["bi", "bi-author"], "bi-reviewer": ["bi", "bi-author"], "bi-steward": ["bi", "bi-steward"], "bi-none": []}
USERS = {"ann": "bi-writer", "bob": "bi-reviewer", "cat": "bi-reader", "sue": "bi-steward", "nobi": "bi-none"}


def seed() -> dict:
    """Roles, users, a shared workspace 'rates-desk' (ann admin, bob member, cat viewer) with published, certified and draft items."""
    for role, bi in ROLES.items():
        s, r = admin("PUT", f"/admin/role-definitions/{role}", {"kinds": ["*"], "approve": role == "bi-reviewer", "bi": bi})
        assert s == 200, r
    for user, role in USERS.items():
        s, r = admin("POST", "/admin/users", {"username": user, "displayName": user.capitalize(), "email": f"{user}@desk.test", "roles": [role], "packs": ["trading"],
                                            "password": PASSWORD, "mustChangePassword": False})
        assert s in (200, 201), r
    ann = lambda m, p, b=None: call(m, p, "ann", ["bi-writer"], b)          # noqa: E731
    sue = lambda m, p, b=None: call(m, p, "sue", ["bi-steward"], b)         # noqa: E731
    s, ws = ann("POST", "/bi/workspaces", {"name": "Rates Desk", "description": "Rates trading: P&L, exposure and liquidity"})
    assert s == 201, ws
    ann("PUT", "/bi/workspaces/rates-desk/members/bob", {"role": "member"})
    ann("PUT", "/bi/workspaces/rates-desk/members/cat", {"role": "viewer"})
    ids = {}
    for key, kind, title, desc, tags, body, publish, audience in [
        ("pnl", "report", "Desk P&L", "Daily P&L by book and trader, with the day's movers", ["pnl", "daily"], "report: desk-pnl\ndataset: trades-basic\npages:\n  - id: overview\n    title: Overview\n    visuals: []\n", True, ["bi-reader", "bi-steward"]),
        ("exposure", "board", "Counterparty exposure", "Live exposure by counterparty against limits", ["exposure", "credit"], "board:\n  source: counterparty\n", True, ["bi-reader"]),
        ("trades", "dataset", "Trades dataset", "The trades table with desk and book relationships", ["trades"], "tables:\n  - trade\n", False, []),
        ("risk", "report", "Risk ladder", "DV01 ladder by bucket", ["risk", "dv01"], "report: risk-ladder\ndataset: trades-basic\npages:\n  - id: ladder\n    title: Ladder\n    visuals: []\n", True, ["bi-steward"])]:
        s, it = ann("POST", "/bi/workspaces/rates-desk/items", {"kind": kind, "title": title, "description": desc, "tags": tags, "body": body, "audienceRoles": audience})
        assert s == 201, it
        ids[key] = it["id"]
        if publish:
            s, r = ann("POST", f"/bi/items/{it['id']}/publish", {"note": "first version"})
            assert s == 200, r
    s, r = sue("POST", f"/bi/items/{ids['pnl']}/endorse", {"level": "certified"})
    assert s == 200, r
    s, r = ann("POST", f"/bi/items/{ids['exposure']}/endorse", {"level": "promoted"})
    assert s == 200, r
    return ids


class Stack:
    def __init__(self, tmp: Path):
        self.tmp, self.procs, self.ids = tmp, {}, {}

    def start(self) -> "Stack":
        for port in (SERVER_PORT, CONSOLE_PORT):
            assert port not in FORBIDDEN and not listening(port), f"port {port} is in use: stop whatever listens there"
        jars = glob.glob(str(ROOT / "drishti-server/target/drishti-server-*-exec.jar"))
        java = os.environ.get("JAVA_HOME", "/usr/lib/jvm/java-21-openjdk-amd64") + "/bin/java"
        if not jars or not Path(java).exists():
            raise FileNotFoundError("needs the built server: ./mvnw -o -q package -DskipTests -pl drishti-server -am")
        work = self.tmp / "server"
        (work / "config" / "bi").mkdir(parents=True)
        for rel, text in SHIPPED.items():
            (work / "config" / "bi" / rel).parent.mkdir(parents=True, exist_ok=True)
            (work / "config" / "bi" / rel).write_text(text)
        env = dict(os.environ, DRISHTI_PACKS="trading", DRISHTI_PACKS_DIR=str(ROOT / "config" / "packs"), DRISHTI_SECURITY_ENABLED="true", DRISHTI_TOKEN_SECRET=SECRET,
                   DRISHTI_BI_DIR=str(work / "config" / "bi"))
        self.procs["server"] = subprocess.Popen([java, "-Xmx768m", "-jar", jars[0], f"--server.port={SERVER_PORT}", "--drishti.security.registered-users-only=false"], cwd=work,
                                                env=env, stdout=open(self.tmp / "server.log", "ab"), stderr=subprocess.STDOUT)
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
