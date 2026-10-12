"""Wave W1 of the model layer in Chromium (docs/architecture/BI_MODEL_SPEC.md section 9), over the real server and console on scratch ports 18918 and
17918 (security on, the trading and banking-core packs, a small file lake of trades and desks): a pack-generated dataset is listed and browsable (grain,
keys, meanings, time mode); a new model takes it and the desks, the pack reference is suggested and accepted in one click; a measure gets its default
from the column; a role restricted on trades but not on desks raises the safeguard beside the Security tab and is resolved with one click; the model is
saved; a report started from it reads the model in its Fields pane; and the model query's answer equals the old dataset query over the same rows.

Skipped when Playwright, Chromium or the built server jar is missing, or a scratch port is in use. Never run with pytest -n (the ports are fixed)."""
from __future__ import annotations

import glob
import json
import os
import subprocess
import sys
import urllib.error
import urllib.request
from pathlib import Path

import pytest

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")
from playwright.sync_api import expect, sync_playwright  # noqa: E402

from bi_stack import FORBIDDEN, listening, wait  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
CONSOLE = ROOT / "drishti-console"
SERVER_PORT, CONSOLE_PORT = 18918, 17918
SERVER, CONSOLE_URL = f"http://127.0.0.1:{SERVER_PORT}", f"http://127.0.0.1:{CONSOLE_PORT}"
SECRET = "w1-model-scratch-secret-0123456789abcdef"
PASSWORD = "long-enough-pass-1"
DESKS = [("DESK-FX", "Foreign exchange"), ("DESK-FI", "Fixed income"), ("DESK-EQD", "Equity derivatives")]
CONNECTOR = """plugin: file
kinds: [trade, desk]
settings:
  root: {root}
  rescan-seconds: 3600
  layout:
    trade:
      columns: [tradeId, desk, book, productType, currency, status, notional, mtm, pnl1d, tradeDate]
    desk:
      columns: [deskId, name, head]
"""


def api(method: str, path: str, user: str, roles: list, body=None):
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


def admin(method, path, body=None):
    return api(method, path, "drishti-dev-admin", ["admin"], body)


def write_lake(root: Path, days=("2026-10-07", "2026-10-08"), rows=60) -> None:
    for day in days:
        folder = root / day
        folder.mkdir(parents=True, exist_ok=True)
        with open(folder / "trade.jsonl", "w") as f:
            for i in range(rows):
                tid = f"BBG-{day.replace('-', '')[4:]}{i:03d}"
                f.write(json.dumps({"id": tid, "tradeId": tid, "desk": DESKS[i % 3][0], "book": f"BOOK-{i % 3}", "productType": ["FX_FORWARD", "GOVT_BOND", "EQ_OPTION"][i % 3],
                                    "currency": ["USD", "EUR", "GBP"][i % 3], "status": "Live", "notional": 1_000_000.0 * (1 + i % 7), "mtm": ((i % 9) - 4) * 25_000,
                                    "pnl1d": ((i % 5) - 2) * 4_000, "tradeDate": day}) + "\n")
        with open(folder / "desk.jsonl", "w") as f:
            for desk, name in DESKS:
                f.write(json.dumps({"id": desk, "deskId": desk, "name": name, "head": "H. " + name.split()[0]}) + "\n")


class Stack:
    def __init__(self, tmp: Path):
        self.tmp, self.procs = tmp, {}

    def start(self) -> "Stack":
        try:
            for port in (SERVER_PORT, CONSOLE_PORT):
                assert port not in FORBIDDEN and not listening(port), f"port {port} is in use"
            jars = sorted(glob.glob(str(ROOT / "drishti-server/target/drishti-server-*-exec.jar")), key=os.path.getmtime, reverse=True)
            java = os.environ.get("JAVA_HOME", "/usr/lib/jvm/java-21-openjdk-amd64") + "/bin/java"
            if not jars or not Path(java).exists():
                raise FileNotFoundError("needs the built server: ./mvnw -o -q package -DskipTests -pl drishti-server -am")
            work = self.tmp / "server"
            (work / "config" / "bi" / "datasets").mkdir(parents=True)
            (work / "config" / "bi" / "datasets" / "trades-basic.dataset.yaml").write_text((ROOT / "config/bi/datasets/trades-basic.dataset.yaml").read_text())
            lake = self.tmp / "lake"
            write_lake(lake)
            (work / "config" / "connectors").mkdir(parents=True)
            (work / "config" / "connectors" / "trading-store.yaml").write_text(CONNECTOR.format(root=lake))
            env = dict(os.environ, DRISHTI_PACKS_DIR=str(ROOT / "config" / "packs"), DRISHTI_PACKS="trading,banking-core", DRISHTI_SECURITY_ENABLED="true",
                       DRISHTI_TOKEN_SECRET=SECRET, DRISHTI_BI_DIR=str(work / "config" / "bi"), DRISHTI_DATA_DIR=str(work / "data"))
            self.procs["server"] = subprocess.Popen([java, "-Xmx768m", "-jar", jars[0], f"--server.port={SERVER_PORT}", "--drishti.security.registered-users-only=false",
                                                     f"--drishti.bi.query.store-dir={self.tmp / 'store'}"], cwd=work, env=env, stdout=open(self.tmp / "server.log", "ab"),
                                                    stderr=subprocess.STDOUT)
            wait(SERVER + "/actuator/health", 150)
            for role, bi, raw in [("bi-writer", ["bi", "bi-author"], True), ("desk-head", ["bi"], True)]:
                s, r = admin("PUT", f"/admin/role-definitions/{role}", {"kinds": ["*"], "raw": raw, "bi": bi})
                assert s == 200, r
            for user, role, desk in [("wes", "bi-writer", ""), ("hana", "desk-head", "DESK-FX")]:
                s, r = admin("POST", "/admin/users", {"username": user, "displayName": user.capitalize(), "email": f"{user}@desk.test", "desk": desk, "roles": [role],
                                                    "packs": ["trading", "banking-core"], "password": PASSWORD, "mustChangePassword": False})
                assert s in (200, 201, 409), r
            cenv = dict(os.environ, DRISHTI_AUTH_ENABLED="true", DRISHTI_SESSION_SECRET="s" * 40, DRISHTI_TOKEN_SECRET=SECRET, DRISHTI_SECURE_COOKIE="false",
                        DRISHTI_SESSION_RECHECK_SECONDS="1")
            self.procs["console"] = subprocess.Popen([sys.executable, str(CONSOLE / "run_drishti_web.py"), f"--server.port={CONSOLE_PORT}", f"--backend.url={SERVER}"],
                                                     env=cenv, stdout=open(self.tmp / "console.log", "ab"), stderr=subprocess.STDOUT)
            wait(CONSOLE_URL + "/healthz")
            return self
        except BaseException:
            self.close()
            raise

    def close(self) -> None:
        for p in self.procs.values():
            p.terminate()
        for p in self.procs.values():
            try:
                p.wait(20)
            except subprocess.TimeoutExpired:
                p.kill()
