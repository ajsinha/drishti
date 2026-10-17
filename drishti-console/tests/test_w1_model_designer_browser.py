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

"""Wave W1 of the model layer in Chromium (docs/architecture/BI_MODEL_SPEC.md section 9), over the real server and console on scratch ports 18918 and
17918 (security on, the trading and core packs, a small file lake of trades and desks): a pack-generated dataset is listed and browsable (grain,
keys, meanings, time mode); a new model takes it and the desks, the pack reference is suggested and accepted in one click; a measure gets its default
from the column; a role restricted on trades but not on desks raises the safeguard beside the Security tab and is resolved with one click; the model is
saved; a report started from it reads the model in its Fields pane; and the model query's answer equals the old dataset query over the same rows.

Skipped when Playwright, Chromium or the built server jar is missing, or a scratch port is in use. Each pytest-xdist worker adds 100 to the ports (tests/scratch_ports.py)."""
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

import pytest

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")
from playwright.sync_api import expect, sync_playwright  # noqa: E402

from bi_stack import FORBIDDEN, listening, wait  # noqa: E402
from scratch_ports import worker_ports  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
CONSOLE = ROOT / "drishti-console"
PORTS = worker_ports(18918, 17918)       # a file that reuses this stack names its own PORTS; the stack fixture switches to the running file's
SERVER_PORT, CONSOLE_PORT = PORTS
SERVER, CONSOLE_URL = f"http://127.0.0.1:{SERVER_PORT}", f"http://127.0.0.1:{CONSOLE_PORT}"


def use_ports(ports) -> None:
    """Points this module's stack and helpers at the given (server, console) ports: the file being run, never whichever file was imported last."""
    global SERVER_PORT, CONSOLE_PORT, SERVER, CONSOLE_URL
    SERVER_PORT, CONSOLE_PORT = ports
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
            from bi_samples import copy_samples
            copy_samples(work / "config" / "bi", ["trades-basic"])         # the shipped sample: the model trades-basic and the dataset it reads
            lake = self.tmp / "lake"
            write_lake(lake)
            (work / "config" / "connectors").mkdir(parents=True)
            (work / "config" / "connectors" / "trading-store.yaml").write_text(CONNECTOR.format(root=lake))
            env = dict(os.environ, DRISHTI_PACKS_DIR=str(ROOT / "config" / "packs"), DRISHTI_PACKS="trading,core", DRISHTI_SECURITY_ENABLED="true",
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
                                                    "packs": ["trading", "core"], "password": PASSWORD, "mustChangePassword": False})
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


@pytest.fixture(scope="module")
def stack(request, tmp_path_factory):
    use_ports(getattr(request.module, "PORTS", PORTS))
    for port in (SERVER_PORT, CONSOLE_PORT):
        if listening(port):
            pytest.skip(f"port {port} is in use: stop whatever listens there")
    try:
        s = Stack(tmp_path_factory.mktemp("w1model")).start()
    except FileNotFoundError as e:
        pytest.skip(str(e))
    yield s
    s.close()


@pytest.fixture(scope="module")
def browser(stack):
    with sync_playwright() as p:
        try:
            b = p.chromium.launch()
        except Exception as e:  # noqa: BLE001
            pytest.skip(f"needs Playwright's Chromium: {str(e)[:100]}")
        yield b
        b.close()


VIOLATIONS = "window.__csp = []; document.addEventListener('securitypolicyviolation', e => window.__csp.push(e.violatedDirective + ' ' + e.blockedURI));"


def sign_in(browser, user, width=1400):
    context = browser.new_context(viewport={"width": width, "height": 900})
    context.add_init_script(VIOLATIONS)
    page = context.new_page()
    page.errors = []
    page.on("pageerror", lambda e: page.errors.append(str(e)))
    page.goto(CONSOLE_URL + "/login?next=/bi/models")
    page.fill("#lu", user)
    page.fill("#lp", PASSWORD)
    page.click("button[type=submit]")
    page.wait_for_url("**/bi/models")
    return page


def clean(page):
    assert page.evaluate("window.__csp") == [], "a policy violation"
    assert page.errors == [], page.errors


def fetch(page, method, path, body=None):
    return page.evaluate("""([m, p, b]) => fetch(p, {method: m, credentials: 'same-origin', headers: {'Content-Type': 'application/json', Accept: 'application/json'},
                              body: b === null ? undefined : JSON.stringify(b)}).then(r => r.json().then(j => ({status: r.status, body: j})))""", [method, path, body])


def yaml_tab(page) -> str:
    page.click('[data-tab="yaml"]')
    return page.evaluate("document.querySelector('.CodeMirror') ? document.querySelector('.CodeMirror').CodeMirror.getValue() : document.querySelector('[data-yaml]').value")


def test_pack_dataset_to_model_to_report(browser):
    page = sign_in(browser, "wes")
    # 1. the pack-generated datasets are listed, read-only and certified; one opens with its grain, keys, meanings and time mode
    expect(page.locator('[data-models-root][data-ready="yes"]')).to_have_count(1, timeout=30000)
    page.click('[data-pack-group="trading"] summary')                    # the packs' datasets are grouped by pack, collapsed
    card = page.locator('[data-layer-card="pack.trading.trade"]')
    expect(card).to_be_visible()
    expect(card).to_contain_text("certified")
    card.locator("h3 a").click()
    expect(page.locator('[data-layer-dataset][data-ready="yes"]')).to_have_count(1, timeout=20000)
    expect(page.locator('[data-fact="grain"]')).to_contain_text("One row per trade per business date")
    expect(page.locator('[data-fact="keys"]')).to_contain_text("id")
    expect(page.locator('[data-fact="time"]')).to_contain_text("snapshot")
    expect(page.locator('[data-column="desk"]')).to_contain_text("desk →")
    expect(page.locator('[data-column="notional"]')).to_contain_text("notional")
    expect(page.locator("[data-yaml]")).to_contain_text("dataset: pack.trading.trade")
    clean(page)

    # 2. a new model over trades and desks: the desks dataset is inferred a dimension (trades reference it)
    page.goto(CONSOLE_URL + "/bi/models")
    expect(page.locator('[data-models-root][data-ready="yes"]')).to_have_count(1, timeout=30000)
    page.click("[data-new-model]")
    page.fill("[data-nm-title]", "Trades and desks")
    page.check('[data-nm-pick="pack.trading.trade"]')
    page.check('[data-nm-pick="pack.core.desk"]')
    page.click("[data-nm-go]")
    page.wait_for_url("**/bi/md/**")
    expect(page.locator('[data-md-root][data-ready="yes"]')).to_have_count(1, timeout=30000)
    expect(page.locator('[data-role-of="desk"]')).to_have_value("dimension")
    expect(page.locator('[data-role-of="trade"]')).to_have_value("fact")

    # 3. the pack reference is suggested; one click accepts it
    sugg = page.locator('[data-suggestion="trade.desk->desk.id"]')
    expect(sugg).to_be_visible(timeout=15000)
    sugg.locator("[data-accept]").click()
    expect(page.locator('[data-rel="trade.desk → desk.id"]')).to_be_visible(timeout=15000)
    expect(page.locator('[data-suggestion="trade.desk->desk.id"]')).to_have_count(0)
    page.click('[data-rel-open="0"]')
    expect(page.locator("[data-insp-card]")).to_have_value("many-to-one")

    # 4. a measure from a column gets its default (sum, additive) from the dataset
    page.click('[data-tab="measures"]')
    page.select_option("[data-nm-alias]", "trade")
    page.select_option("[data-nm-column]", "mtm")
    page.click("[data-nm-add]")
    row = page.locator('[data-measure="Sum of mtm"]')
    expect(row).to_be_visible(timeout=15000)
    expect(row.locator('[data-m-expr="Sum of mtm"]')).to_have_value("sum(trade.mtm)")
    expect(row.locator('[data-m-additive="Sum of mtm"]')).to_have_value("yes")

    # 5. security: a desk-head rule on trades raises the safeguard on desks; one click resolves it
    page.click('[data-tab="security"]')
    page.select_option("[data-rule-alias]", "trade")
    page.fill("[data-rule-roles]", "desk-head")
    page.select_option("[data-rule-field]", "trade.desk")
    page.click("[data-rule-add]")
    expect(page.locator('[data-exposure="desk:desk-head"]')).to_be_visible(timeout=15000)
    expect(page.locator("[data-count-security]")).to_be_visible()
    page.click('[data-safeguard-all="desk:desk-head"]')
    expect(page.locator("[data-safeguard-clear]")).to_be_visible(timeout=15000)
    expect(page.locator('[data-allowed="desk:desk-head"]')).to_be_visible()
    page.fill("[data-rule-roles]", "bi-writer")                       # a reader with no matching rule gets no rows: the author sees every trade
    page.select_option("[data-rule-kind]", "all")
    page.click("[data-rule-add]")
    expect(page.locator('[data-rule="trade:1"]')).to_contain_text("True", timeout=15000)
    expect(page.locator("[data-safeguard-clear]")).to_be_visible(timeout=15000)
    text = yaml_tab(page)                                             # the designer wrote the YAML; nobody typed it
    assert "trade.desk in user.desks" in text and "may-see-all:" in text and "suggested: {from: pack-reference}" in text, text

    # 6. save (checked clean), then start a report on the model: its Fields pane reads the model, its visuals ask the model
    page.click("[data-save]")
    expect(page.locator("[data-state]")).to_have_text("Saved draft", timeout=15000)
    expect(page.locator("[data-valid]")).to_have_text(re.compile(r"^Valid(, \d+ warnings?)?$"))      # valid; a warning pinned on the canvas is counted
    name = page.evaluate("window.drishtiModel.doc.model")
    assert name.startswith("trades-and-desks"), name
    page.click("[data-report]")
    page.click("[data-ask-go]")
    page.wait_for_url("**/bi/design/**", timeout=20000)
    expect(page.locator('[data-model-header="model"]')).to_be_visible(timeout=30000)
    expect(page.locator('[data-field="Sum of mtm"]')).to_be_visible()
    expect(page.locator('[data-field="desk.name"]')).to_be_visible()
    page.drag_and_drop('[data-field="Sum of mtm"]', "[data-canvas-grid]", target_position={"x": 80, "y": 40})
    page.drag_and_drop('[data-field="trade.desk"]', ".bi-dvis")
    page.locator(".bi-dvis").first.click()
    expect(page.locator("[data-visual-query-text]")).to_contain_text(f"model: {name}@1", timeout=15000)
    expect(page.locator(".bi-dvis").first).to_contain_text("3 rows", timeout=15000)           # three desks, drawn from the model query

    # 7. the model query's answer equals the shipped sample model's over the same rows; the 2.1 dataset query is gone (410, naming the model query to send)
    new = fetch(page, "POST", "/bi/api/mq", {"mq": 1, "model": f"{name}@1", "select": {"fields": ["trade.desk"], "measures": ["Sum of mtm"]}})
    sample = fetch(page, "POST", "/bi/api/mq", {"mq": 1, "model": "trades-basic@1", "select": {"fields": ["trade.desk"], "measures": ["MTM"]}})
    assert new["status"] == 200 and sample["status"] == 200, (new, sample)
    assert sorted(map(tuple, new["body"]["rows"])) == sorted(map(tuple, sample["body"]["rows"]))
    old = fetch(page, "POST", "/bi/api/datasets/file:dataset:trades-basic/query", {"fields": ["trade.desk"], "measures": ["MTM"]})
    assert old["status"] == 410 and old["body"]["mq"]["model"] == "trades-basic@1", old
    clean(page)
    page.context.close()


def test_a_report_straight_from_a_dataset_reads_its_implicit_model_in_the_designer_and_the_viewer(browser):
    page = sign_in(browser, "wes")
    page.goto(CONSOLE_URL + "/bi/ds/pack.trading.trade")
    expect(page.locator('[data-layer-dataset][data-ready="yes"]')).to_have_count(1, timeout=20000)
    page.click("[data-use]")
    page.click("[data-ask-go]")
    page.wait_for_url("**/bi/design/**", timeout=20000)
    expect(page.locator('[data-model-header="implicit"]')).to_contain_text("Implicit model of Trade", timeout=30000)
    expect(page.locator("[data-make-model]")).to_be_visible()
    expect(page.locator('[data-field="desk.name"]')).to_be_visible()                  # the pack dimension came along, one hop
    page.drag_and_drop('[data-field="Sum of mtm"]', "[data-canvas-grid]", target_position={"x": 80, "y": 40})
    page.drag_and_drop('[data-field="desk.name"]', ".bi-dvis")
    expect(page.locator(".bi-dvis").first).to_contain_text("3 rows", timeout=15000)
    page.click("[data-save]")
    expect(page.locator(".bi-near").first).to_contain_text("Saved", timeout=15000)
    report = page.url.rsplit("/", 1)[-1]
    page.goto(CONSOLE_URL + "/bi/r/" + report)                                          # the viewer asks the same model query
    expect(page.locator(".bi-vis").first).to_be_visible(timeout=20000)
    expect(page.locator(".bi-vis canvas, .bi-vis svg").first).to_be_visible(timeout=15000)
    expect(page.locator(".bi-vis-error")).to_have_count(0)
    clean(page)
    page.context.close()
