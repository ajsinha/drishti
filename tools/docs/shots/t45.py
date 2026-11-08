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

"""Pictures of tutorial 16, Part A, your own data in a new lake (docs/tutorials/16-load-your-own-data-into-a-new-lake.md), written to
docs/tutorials/img/t45/, in the light theme. The pictures follow the tutorial's steps, which this module runs itself, in order, on a
scratch stack of its own (start_servers below): a working folder with its own copy of config/ and a demo lake of the trades and the
reference data built into it (`lake demo --only trades/,core/`), sign-in off, the data inbox on. Nothing is written to the repository's
config/ or data/:

    DRISHTI_SHOTS_WORK=<an empty folder> DRISHTI_SHOTS_SERVER_PORT=<port> DRISHTI_SHOTS_CONSOLE_PORT=<port> \\
        drishti-console/.venv/bin/python tools/docs/screenshots.py --guide t45

It writes config/lakes/risklake.yaml and config/dataload.yaml in the copy, makes the synthetic day with
docs/tutorials/data/risklake/make_day.py, drops it with full .ready files, loads it, points trade-endur and risk-store at risklake,
restarts the server and opens a trade. Every command's output is printed (the tutorial quotes it). Run the pictures all together and
once: each step moves files on."""
from __future__ import annotations

import atexit
import os
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path

from _tut import OUT, command, light
from shotlib import CONSOLE_PORT, PACKS, ROOT, SERVER_PORT, Ctx, java_cmd, listening, out_dir, register

SERVER_ENV = {"DRISHTI_SECURITY_ENABLED": "false", "DRISHTI_DATALOAD_ENABLED": "true"}   # runs on its own (--guide t45)
shot = register("t45", OUT / "t45")
WIDE, HIGH = 1440, 900
DAY = "2026-11-06"
PY = ROOT / "drishti-console" / ".venv" / "bin" / "python"
PY = PY if PY.exists() else Path(sys.executable)   # a worktree has no venv of its own: the interpreter running the script
PROCS: list = []             # the scratch server and console; restart() replaces the server in place, so the driver stops the new one

LAKE = """lake: risklake                    # the file's name; its folder is data/delta/risklake (no root: needed)
format: delta
defaults:
  load-mode: replace              # a load replaces each business date it holds
  compression: zstd
tables:
  trading.trade_endur:
    kind: trade
    partition-by: [PRODUCT_TYPE]  # after business_date; for a million trades a day, use none (guide, section 9)
    promote: [TRADE_ID, TRADE_VERSION, SOURCE_SYSTEM, TRADE_STATUS, PRODUCT_TYPE, PRODUCT_GROUP, DESK, BOOK, PORTFOLIO, COUNTERPARTY_ID,
      LEGAL_ENTITY, TRADER_ID, BUY_SELL_CODE, CURRENCY, QUANTITY, NOTIONAL, TRADE_DATE, MATURITY_DATE]
    id: TRADE_ID
    latest-by: UPDATED_AT
    sort-by: [DESK, BOOK, id]
    file-rows: 250000
    row-group-rows: 1000
  risk.scenario-pv:
    promote: [VALUE_TYPE, GRAIN, TRADE_ID, POSITION_ID, CURRENCY, SOURCE_SYSTEM, SECURITY_ID, LEGAL_ENTITY, DIVISION, DESK, TT_BOOK,
      LIMIT_LETTER, PORTFOLIO, PRODUCT_GROUP, PRODUCT_TYPE, DIRECTION, QUANTITY, BASE_PV, VALUATION_DATE, SCENARIO_SET_ID, BATCH_ID, RUN_KIND,
      COMPUTED_AT, STATUS]
    vectors: [SCENARIO_PV]
    id: SCENARIO_PV_ID
    date: BUSINESS_DATE
    latest-by: COMPUTED_AT
    sort-by: [DESK, TT_BOOK, id]
    file-size-mb: 256
    row-group-mb: 64
  risk.risk-hierarchy:
    promote: [NODE_ID, HIERARCHY, LEVEL, PARENT_ID, NAME]
    id: NODE_ID
    date: BUSINESS_DATE
"""
RULES = """dataload:
  rules:
    - match: "trade_endur.*.jsonl.gz"
      pack: trading
      kind: trade
      connector: trade-endur          # the table that connector's entry names: trading/trade_endur
      lake: risklake
      date-from: name                 # the name must carry the business date
    - match: "scenario_pv.*.jsonl.gz"
      pack: market-risk
      kind: scenario-pv
      lake: risklake
      date-field: BUSINESS_DATE
    - match: "risk_hierarchy.*"
      pack: market-risk
      kind: risk-hierarchy
      lake: risklake
  unmatched: fail
"""
TRADE_ENDUR = """plugin: delta
kinds: [trade]
settings:
  lake: risklake                  # was: demo
  lake-tables: trading.trade_endur
  union: true                     # as the pack's template: read with calypso and murex as one store
  source-name: trade-endur
"""
RISK_STORE = """plugin: delta
enabled: true
kinds: [scenario-pv, risk-hierarchy]
settings:
  lake: risklake
  lake-tables: risk.scenario-pv,risk.risk-hierarchy
  refresh-seconds: 30
"""


def work() -> Path:
    return Path(os.environ["DRISHTI_SHOTS_WORK"]).resolve()


def inbox() -> Path:
    return work() / "data" / "dataload" / "inbox"


def env() -> dict:
    """The command line's and the loaders' environment: the scratch server, its lake files, data and connector folders."""
    w = work()
    return {**os.environ, "DRISHTI_SERVER": f"http://127.0.0.1:{SERVER_PORT}", "DRISHTI_LAKES_DIR": str(w / "config" / "lakes"),
            "DRISHTI_DATA_DIR": str(w / "data"), "DRISHTI_CONNECTORS_DIR": str(w / "config" / "connectors")}


def cli(*args: str) -> str:
    """drishti.py in the scratch working folder (as the tutorial runs it, from the server's working folder); prints and returns its output."""
    r = subprocess.run([str(PY), str(ROOT / "tools" / "drishti.py"), *args], capture_output=True, text=True, cwd=work(), env=env(), timeout=600)
    out = (r.stdout + r.stderr).rstrip().replace(str(work()) + "/", "")
    print("  $ drishti.py " + " ".join(args) + "\n" + "\n".join("    " + x for x in out.splitlines()))
    return out


def _server():
    jar = sorted((ROOT / "drishti-server" / "target").glob("drishti-server-*-exec.jar"))[-1]
    java = os.environ.get("JAVA_HOME", "/usr/lib/jvm/java-21-openjdk-amd64") + "/bin/java"
    w = work()
    e = {**os.environ, "DRISHTI_PORT": str(SERVER_PORT), "DRISHTI_PACKS": PACKS, "DRISHTI_SUTRAS": str(w / "sutras"),
         "DRISHTI_PACKS_INSTALLED": str(w / "data" / "packs" / "installed"), **SERVER_ENV}
    log = (w / "server.log").open("a")
    return subprocess.Popen(java_cmd(java, ["-Xmx3g", "-jar", str(jar)], cwd=w, env=e), cwd=w, env=e, stdout=log, stderr=subprocess.STDOUT)


def _up(seconds: int = 240) -> None:
    end = time.time() + seconds
    while time.time() < end:
        if listening(SERVER_PORT) and listening(CONSOLE_PORT):
            try:
                if subprocess.run([str(PY), str(ROOT / "tools" / "drishti.py"), "dataload", "status"], capture_output=True, env=env(),
                                  cwd=work(), timeout=60).returncode == 0:
                    return
            except subprocess.TimeoutExpired:
                pass
        time.sleep(2)
    sys.exit("the scratch server did not start; see " + str(work() / "server.log"))


def start_servers(w: Path) -> list:
    """The scratch stack: a copy of config/ (the tutorial edits it), the demo lake of trades and reference data, server and console.
    A run starts where the tutorial starts: no risklake, no rules, the packs' own connector files, an empty inbox."""
    # everything of an earlier run goes (the server's state too: its connector records, load history), but the demo lake, slow to build
    shutil.rmtree(w / "config", ignore_errors=True)
    shutil.copytree(ROOT / "config", w / "config", ignore=shutil.ignore_patterns("connectors"))
    (w / "config" / "dataload.yaml").write_text("dataload:\n  rules: []\n  unmatched: fail\n")
    for d in [p for p in (w / "data").glob("*") if p.name != "delta"] + [p for p in (w / "data" / "delta").glob("*") if p.name != "demo"] + [w / "sutras"]:
        shutil.rmtree(d, ignore_errors=True) if d.is_dir() else d.unlink()
    (w / "sutras").mkdir(exist_ok=True)
    if not (w / "data" / "delta" / "demo").exists():
        os.environ["DRISHTI_SHOTS_WORK"] = str(w)
        cli("lake", "demo", "--only", "trades/,core/", "--exposure", "committed", "--no-signal", "-q")
    PROCS.append(_server())
    try:
        _console(w)
        _up()
    except BaseException:
        for proc in PROCS:
            _stop(proc)
        raise
    return PROCS


def _console(w: Path) -> None:
    cenv = dict(os.environ, DRISHTI_BACKEND_URL=f"http://127.0.0.1:{SERVER_PORT}", DRISHTI_CONSOLE_PORT=str(CONSOLE_PORT),
                DRISHTI_PACKS_INSTALLED=str(w / "data" / "packs" / "installed"), **SERVER_ENV)
    PROCS.append(subprocess.Popen([str(PY), str(ROOT / "drishti-console" / "run_drishti_web.py")], cwd=ROOT, env=cenv,
                                  stdout=(w / "console.log").open("w"), stderr=subprocess.STDOUT))


def _stop(proc) -> None:
    if proc.poll() is not None:
        return
    proc.terminate()
    try:
        proc.wait(timeout=60)
    except subprocess.TimeoutExpired:              # a JVM can hang in its shutdown hooks: never leave a scratch server behind
        proc.kill()
        proc.wait(timeout=30)


atexit.register(lambda: [_stop(p) for p in PROCS])


def restart() -> None:
    """Stops the scratch server and starts it again (the connectors are read at start); the console stays."""
    _stop(PROCS[0])
    while listening(SERVER_PORT):
        time.sleep(1)
    PROCS[0] = _server()
    _up()


def idle(seconds: int = 600) -> None:
    """Waits until no cycle is running."""
    end = time.time() + seconds
    while time.time() < end:
        head = cli("dataload", "status").split("last cycles:")[0]
        if "loading" not in head and "a cycle is running" not in head:
            return
        time.sleep(3)


def page(c: Ctx, path: str, wait_ms: int = 2000) -> None:
    light(c)
    c.page.set_viewport_size({"width": WIDE, "height": HIGH})
    c.page.goto(c.base + path)
    c.page.wait_for_timeout(wait_ms)


def scrub(c: Ctx) -> None:
    """The scratch working folder's absolute path out of the page's text: the reader's server shows its own folder there."""
    c.page.evaluate("""(w) => { const t = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT); let n;
        while ((n = t.nextNode())) { if (n.nodeValue.includes(w)) { n.nodeValue = n.nodeValue.split(w).join(''); } } }""", str(work()) + "/")


def save(c: Ctx, name: str) -> None:
    scrub(c)
    c.save(name)


def at(c: Ctx, name: str, js_target: str, above: int = 80) -> None:
    """A window-sized picture from just above an element (cut from the whole page), `js_target` an expression giving the element."""
    scrub(c)
    y = c.page.evaluate("() => { const e = %s; return e ? e.getBoundingClientRect().top + window.scrollY : 0; }" % js_target)
    c.hush()
    d = out_dir(c.guide)
    d.mkdir(parents=True, exist_ok=True)
    c.page.screenshot(path=str(d / name), type="jpeg", quality=84, full_page=True, clip={"x": 0, "y": max(0, y - above), "width": WIDE, "height": HIGH})
    print("  wrote", (d / name).relative_to(ROOT))


def before(c: Ctx, _name: str) -> None:
    light(c)
    c.page.request.fetch(c.base + "/api/settings", method="PATCH", data='{"theme": "light"}', headers={"Content-Type": "application/json"})


@shot("01-lake-file.jpg")
def lake_file(c: Ctx):
    """Step 1: the lake file, then lake show and Admin -> Lakes."""
    (work() / "config" / "lakes" / "risklake.yaml").write_text(LAKE)
    cli("lake", "show")
    page(c, "/admin/lakes")
    c.page.evaluate("""() => { document.querySelectorAll('details.lk-files').forEach(d => {
        d.open = /risklake/.test(d.querySelector('summary').textContent); }); }""")
    c.page.wait_for_timeout(500)
    at(c, "01-lake-file.jpg", "document.getElementById('lkFiles')", above=20)


@shot("02-rules.jpg")
def rules(c: Ctx):
    """Step 2: the routing rules, read at the next cycle."""
    (work() / "config" / "dataload.yaml").write_text(RULES)
    cli("dataload", "run")                                         # an empty cycle makes inbox/ and ledger/, and reads the rules
    time.sleep(3)
    idle()
    page(c, "/admin/loads/inbox")
    c.until("(() => { const h = document.getElementById('ib-rules-h'); return h && h.getBoundingClientRect().height > 0; })()", 60)
    at(c, "02-rules.jpg", "document.getElementById('ib-rules-h')")


@shot("03-waiting.jpg")
def waiting(c: Ctx):
    """Steps 3 and 4: the day made, dropped with its three .ready files: two ready, one held."""
    drop = work() / "data" / "tmp" / "drop"
    r = subprocess.run([str(PY), str(ROOT / "docs" / "tutorials" / "data" / "risklake" / "make_day.py"), "--out", "data/tmp/drop", "--date", DAY],
                       capture_output=True, text=True, cwd=work(), check=True)
    print(r.stdout)
    sums = {m.group(1): (m.group(2), m.group(3)) for m in re.finditer(r"drop/(\S+)\s+rows: (\d+)\s+sha256: (\w+)", r.stdout)}
    for f in sorted(drop.iterdir()):
        shutil.copy(f, inbox() / f.name)
    trades, pvs = f"trade_endur.{DAY}.jsonl.gz", f"scenario_pv.{DAY}.jsonl.gz"
    (inbox() / (trades + ".ready")).write_text(f"lake: risklake\nmode: replace\nsha256: {sums[trades][1]}\nrows: {sums[trades][0]}\n")
    (inbox() / (pvs + ".ready")).write_text(f"lake: risklake\npack: market-risk\nkind: scenario-pv\nsha256: {sums[pvs][1]}\n"
                                            f"rows: {sums[pvs][0]}\nhold: true\n")
    (inbox() / f"risk_hierarchy.{DAY}.ready").write_text("lake: risklake\nrows: 26\n")
    time.sleep(40)                                                 # stable-seconds: 30 as shipped
    cli("dataload", "status")
    page(c, "/admin/loads/inbox")
    save(c, "03-waiting.jpg")


@shot("04-loaded.jpg")
def loaded(c: Ctx):
    """Step 5: Load now; Last cycles with the warning: loaded into risklake, but no connector reads it."""
    page(c, "/admin/loads/inbox")
    c.page.locator("[data-inbox-run]").first.click()                # Load now, as a person would
    time.sleep(3)
    idle()
    cli("dataload", "status")
    page(c, "/admin/loads/inbox", 2500)
    at(c, "04-loaded.jpg", "document.querySelector('[data-inbox-board]')", above=16)


@shot("05-loads.jpg")
def loads(c: Ctx):
    """Step 5: the held file released and loaded; Admin -> Data loads -> Loads: the loads seen by pack."""
    pvs = inbox() / f"scenario_pv.{DAY}.jsonl.gz.ready"
    pvs.write_text("\n".join(x for x in pvs.read_text().splitlines() if not x.startswith("hold:")) + "\n")
    time.sleep(40)
    cli("dataload", "run")
    time.sleep(3)
    idle()
    cli("dataload", "status")
    print("  ls trade_endur/business_date=" + DAY + ":", sorted(p.name for p in (work() / "data" / "delta" / "risklake" / "trading" / "trade_endur"
                                                                         / f"business_date={DAY}").iterdir()))
    print("  outbox:", sorted(str(p.relative_to(work() / "data" / "dataload")) for p in (work() / "data" / "dataload" / "outbox").rglob("*") if p.is_file()))
    page(c, "/admin/loads", 2500)
    save(c, "05-loads.jpg")


@shot("06-connector.jpg")
def connector(c: Ctx):
    """Step 6: trade-endur and risk-store pointed at risklake, lake check, a restart; the connector's own page."""
    d = work() / "config" / "connectors"
    (d / "trade-endur.yaml").write_text(TRADE_ENDUR)
    (d / "risk-store.yaml").write_text(RISK_STORE)
    cli("lake", "check")
    restart()
    page(c, "/admin/connectors/trade-endur", 3000)
    at(c, "06-connector.jpg", "document.getElementById('cdKinds')", above=24)       # its kinds, packs, settings in effect and file


@shot("07-trade.jpg")
def trade(c: Ctx):
    """Step 6: TRD 7000001 <GO>, the synthetic trade from risklake."""
    page(c, "/", 2500)
    command(c, "TRD 7000001")
    c.page.wait_for_timeout(5000)
    save(c, "07-trade.jpg")
    command(c, "TRD 1000001")                                      # a demo ENDUR trade: no longer found (the tutorial says so)
    c.page.wait_for_timeout(5000)
    print("  TRD 1000001:", c.page.url, "|", " ".join(c.page.locator("main, body").first.inner_text().split())[:300])


@shot("08-duplicate.jpg")
def duplicate(c: Ctx):
    """Step 7: the trades dropped again with an empty .ready: a duplicate, not loaded again; then a load checked for undo with
    lake undo --dry-run on risklake's folder, and dataload undo through the server (the lake found in the ledger: risklake)."""
    trades = f"trade_endur.{DAY}.jsonl.gz"
    shutil.copy(work() / "data" / "tmp" / "drop" / trades, inbox() / trades)
    (inbox() / (trades + ".ready")).touch()
    time.sleep(40)
    cli("dataload", "run")
    time.sleep(3)
    idle()
    cli("dataload", "status")
    reports = sorted((work() / "data" / "dataload" / "outbox").rglob(f"risk_hierarchy.{DAY}*.loaded.json"))
    m = re.search(r"\d{8}T\d{6}\.\d{3}-\d+\.\d+", reports[0].read_text()) if reports else None
    load_id = m.group(0) if m else ""
    print("  load id:", load_id)
    cli("lake", "undo", load_id, "--lake", "data/delta/risklake", "--dry-run")
    cli("dataload", "undo", load_id)
    page(c, "/admin/loads/inbox", 2500)
    at(c, "08-duplicate.jpg", "document.querySelector('[data-inbox-board]')", above=16)
