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

"""Regulatory VaR's three screens in a real browser (docs/guides/VAR_GUIDE.md sections 18.10, 18.11 and 18.14), over the market-risk
pack's committed VaR data and desk P&L built into a scratch lake (lake demo --as-of data --only market-risk/<the VaR kinds>,desk-pnl),
served by its Delta connector with the demo connector off, sign-in on and ``drishti.regulatory.synthetic-history`` on; the administrator
seeds the synthetic official history (``POST /api/v1/var/official/synthetic-history?days=259``) once the server has captured the newest
date. Three people sign in: a maker, a checker and an approver.

- ``VAR BANK BACKTEST <GO>`` opens the backtest: the zone, the multiplier, the counts at 99% and 97.5% and the exceptions are the API's
  answer; a desk's row draws that desk's backtest in place; the confidence chips;
- ``VAR BANK STATS FY2026 <GO>`` opens the period statistics: period end, average, high and low (with their dates), the record count and
  the missing records are the API's;
- ``VAR REPORT annual-report-var FY2026 <GO>`` opens the report: Generate freezes run v1; its table, its checks (each status and title
  as the API's), a cell's lineage; the maker's narrative and approval, the maker refused the checker's step (four eyes), the checker
  sending the run back, the maker, the checker and the approver signing it in order, the files released;
- each screen paints without a page error, a script error in the console or a server error, fits a 390 px phone without sideways
  scroll, and F1 opens its own section of the VaR guide.

``DRISHTI_VAR_REG_LAKE`` names a lake built before (the same --only) to reuse while developing. Skipped when Playwright, Chromium, the
built server jar or a JDK is missing."""
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

import pytest

from core.auth import mint_token
from test_var_explorer_browser import ROOT, VAR_DATA
from risk_elements import chart, chart_ready, grid_ready, grid_rows
from test_workbench_browser import browser, wait  # noqa: F401 - fixtures and helpers
from wb_live import BROWSER_WAIT_MS, TOKEN_SECRET, _stack

ADMIN = ("drishti-dev-admin", "drishti-dev-admin123")
PASSWORD = "long-enough-pass-1"
PEOPLE = {"mara": "Mara Maker", "chen": "Chen Checker", "aria": "Aria Approver"}
REPORT = "annual-report-var"
PERIOD = "FY2026"
PHONE = {"width": 390, "height": 844}


def _server(st, method, path, seconds=60):
    """The server itself, as the administrator (a token minted with the stack's shared secret): (status, JSON)."""
    req = urllib.request.Request(st.server + path, method=method, data=b"" if method == "POST" else None,
                                 headers={"Authorization": "Bearer " + mint_token(ADMIN[0], ["admin"], TOKEN_SECRET, 300)})
    try:
        with urllib.request.urlopen(req, timeout=seconds) as r:
            return r.status, json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b"{}")


@pytest.fixture(scope="module")
def stack(tmp_path_factory):
    lake = Path(os.environ["DRISHTI_VAR_REG_LAKE"]) if os.environ.get("DRISHTI_VAR_REG_LAKE") else None
    if lake is None or not (lake / "risk" / "desk-pnl").is_dir():
        work = tmp_path_factory.mktemp("var-reg-lake")
        lake = lake or work / "delta"
        r = subprocess.run([sys.executable, str(ROOT / "tools" / "drishti.py"), "lake", "demo", "--lake", str(lake), "--data-dir", str(work / "data"),
                            "--connectors-dir", str(work / "connectors"), "--as-of", "data", "--only", VAR_DATA + ",market-risk/desk-pnl",
                            "--no-signal", "-q", "--engine", "deltalake"], capture_output=True, text=True, cwd=ROOT, timeout=1500)   # the Python writer, in this process: auto starts a JVM for every table and business day
        if r.returncode != 0 or not (lake / "risk" / "pnl-vector").is_dir():
            pytest.skip("could not build the VaR demo lake: " + (r.stderr or r.stdout)[-400:])
    assert (lake / "risk" / "desk-pnl").is_dir(), "the lake has no desk-pnl table: " + ", ".join(sorted(p.name for p in (lake / "risk").iterdir()))
    for st in _stack(tmp_path_factory, {"DRISHTI_DELTA_ROOT": str(lake), "DRISHTI_DEMO_ENABLED": "false", "DRISHTI_OFFLINE": "true",
                                        "DRISHTI_SECURITY_ENABLED": "true", "DRISHTI_TOKEN_SECRET": TOKEN_SECRET,
                                        "DRISHTI_REGULATORY_SYNTHETICHISTORY": "true",
                                        "DRISHTI_VAR_SCENARIO_WINDOW": "all"}, auth=True, heap="1536m"):   # the synthetic history is seeded from the days beyond a 250-day window: the whole set
        # 1.5 GB: a year of official VaR history, its backtests by desk and three report runs held 89-97% of a 768 MB heap after G1's own
        # full compactions, so busy protection rightly kept new sign-ins waiting 45 s (DRS-9501) and a sign-in here timed out (Drill A)
        # the engine answers once the Delta connector is up; the official record of the newest date is captured at start, in the
        # background; the synthetic history is seeded before it (refused with 404 until that record exists)
        deadline = time.monotonic() + 600
        while True:
            status, out = _server(st, "POST", "/api/v1/var/official/synthetic-history?days=259", seconds=300)
            if status == 200:
                break
            if time.monotonic() > deadline or status not in (404, 503):
                pytest.fail(f"could not seed the synthetic official history: {status} {out}")
            time.sleep(2)
        print("\nseeded:", {k: v for k, v in out.items() if not isinstance(v, (list, dict))})
        yield st


def sign_in(browser, base, user, password, viewport=None):
    ctx = browser.new_context(viewport=viewport or {"width": 1600, "height": 1000})
    pg = ctx.new_page()
    pg.errors, pg.console, pg.network = [], [], []
    pg.on("pageerror", lambda e: pg.errors.append(str(e)))
    # a refused sign-off answers 409 and a report with no run yet 404, by design: the browser logs each as "Failed to load resource";
    # any other console error is a script's, and any 5xx the server's
    pg.on("console", lambda m: m.type == "error" and "Failed to load resource" not in m.text and pg.console.append(m.text))
    pg.on("response", lambda r: r.status >= 500 and pg.network.append(f"HTTP {r.status}: {r.url}"))
    pg.set_default_timeout(BROWSER_WAIT_MS)
    pg.goto(base + "/login")
    pg.fill("input[name=user]", user)
    pg.fill("input[name=password]", password)
    pg.click("form button[type=submit], form input[type=submit]")
    pg.wait_for_url(lambda u: "/login" not in u)
    return pg


@pytest.fixture(scope="module")
def people(browser, stack):
    """The administrator makes the maker, the checker and the approver (the approver role sees every node: regulatory VaR needs it)."""
    base = str(stack)
    admin = sign_in(browser, base, *ADMIN)
    for name, display in PEOPLE.items():
        r = admin.request.post(base + "/admin/api/users", data=json.dumps({"username": name, "displayName": display, "roles": ["approver"],
                                                                           "packs": ["trading", "market-risk"], "enabled": True,
                                                                           "password": PASSWORD, "mustChangePassword": False}),
                               headers={"Content-Type": "application/json"})
        assert r.status == 201, r.text()
    admin.context.close()
    yield list(PEOPLE)


def get(pg, base, path):
    r = pg.request.get(base + path)
    assert r.ok, f"{path}: {r.status} {r.text()[:300]}"
    return r.json()


def clean(pg):
    assert pg.errors == [] and pg.console == [] and pg.network == [], (pg.errors, pg.console, pg.network)


def go(pg, base, command):
    pg.goto(base + "/go?q=" + command.replace(" ", "+").replace("<", "%3C").replace(">", "%3E"))


def mln(v):
    return f"{v / 1e6:.2f}m"


def no_sideways_scroll(pg):
    assert pg.evaluate("document.documentElement.scrollWidth <= window.innerWidth + 1"), \
        pg.evaluate("[document.documentElement.scrollWidth, window.innerWidth]")


def f1_opens(pg, anchor):
    pg.locator("h1").first.click()
    pg.keyboard.press("F1")
    pg.wait_for_url("**/help/**")
    assert "regulatory-var-reporting" in pg.url and pg.url.endswith("#" + anchor), pg.url


# ---------------------------------------------------------------------------------------------------------------------------------- backtest

def bt_painted(pg, node):
    wait(pg, "(() => { const r = document.querySelector('[data-bt-parts]'); return r && r.dataset.selected === %r"
             " && document.querySelector('[data-bt-zone]').textContent !== '—'; })()" % node)
    wait(pg, grid_ready("[data-bt-parts]"))


def test_the_backtest_shows_the_engines_zone_multiplier_and_exceptions(stack, people, browser):
    base = str(stack)
    pg = sign_in(browser, base, "mara", PASSWORD)
    go(pg, base, "VAR BANK BACKTEST <GO>")
    pg.wait_for_url("**/var/backtesting?scope=BANK*")
    d = get(pg, base, "/api/var/backtesting?scope=BANK")
    bt_painted(pg, d["node"])
    print(f"\nbacktest BANK: {d['days']} days to {d['to']}, zone {d['zone']}, multiplier {d['multiplier']}, "
          f"counts {[(c['confidence'], c.get('actual'), c.get('hypothetical')) for c in d['counts']]}, synthetic {d.get('synthetic')}")
    assert d["zone"] in ("green", "amber", "red") and d["days"] > 0
    assert pg.locator("[data-bt-zone]").inner_text().lower() == d["zone"]
    assert pg.locator(f'[data-lamp="{d["zone"]}"]').evaluate("e => e.classList.contains('on')")
    assert pg.locator("[data-bt-mult]").inner_text() == f"{d['multiplier']:.2f}"
    # the counts: per confidence a heading row, then actual, hypothetical and risk-theoretical
    rows = grid_rows(pg, "[data-bt-counts]")
    assert len(rows) == 4 * len(d["counts"])
    for i, c in enumerate(d["counts"]):
        assert rows[4 * i + 1]["cells"][1] == str(c["actual"])
        assert rows[4 * i + 2]["cells"][1] == str(c["hypothetical"])
    # the exceptions grid: one row per exception of the answer, each its date and its drivers
    exc = grid_rows(pg, "[data-bt-exc]")
    assert len(exc) == len(d["exceptions"])
    for i, e in enumerate(d["exceptions"]):
        assert exc[i]["cells"][0] == e["date"]
        assert exc[i]["cells"][7] == ", ".join(e.get("drivers") or [])
    # the exceptions listed are those at the first confidence: the actual ones are the count the zone reads
    assert sum(1 for e in d["exceptions"] if e["which"] in ("actual", "both")) == d["counts"][0]["actual"]
    assert sum(1 for e in d["exceptions"] if e["which"] in ("hypothetical", "both")) == d["counts"][0]["hypothetical"]
    # the chart (risk.backtest): a point a tested day, the actual exceptions at 99% marked, the card the server's zone and count
    wait(pg, chart_ready("[data-bt-chart]"))
    ch = chart(pg, "[data-bt-chart]")
    assert ch["kind"] == "risk.backtest" and ch["series"][0]["n"] == len(d["rows"])
    marks99 = ch["figures"]["counted"]
    assert marks99 == sum(1 for r in d["rows"] if r.get("var990") is not None and r["actual"] < -r["var990"])
    assert ch["figures"]["exceptions"] == d["counts"][0]["actual"] and ch["figures"]["zone"] == d["zone"]
    assert ch["badge"]["text"].startswith(f"{d['zone']} · {d['counts'][0]['actual']} exceptions")
    pg.locator('[data-bt-q="1"]').click()                                   # 97.5%: more exceptions, the same page
    wait(pg, "document.querySelector('[data-bt-chart]').dataset.var === 'var975'")
    ch = chart(pg, "[data-bt-chart]")
    assert ch["figures"]["counted"] == sum(1 for r in d["rows"] if r.get("var975") is not None and r["actual"] < -r["var975"])
    assert ch["figures"]["counted"] >= marks99
    assert ch["figures"]["exceptions"] == d["counts"][1]["actual"]
    # the bank and its desks: a row each part; a desk's row draws its own backtest in place
    assert len(grid_rows(pg, "[data-bt-parts]")) == len(d["parts"])
    desk = next(p for p in d["parts"] if p.get("level") == "desk" and p.get("zone"))
    pg.evaluate("window.__marker = 1")
    pg.locator(f'[data-bt-parts] .dg-row[data-node="{desk["node"]}"] .dg-cell').nth(1).click()
    bt_painted(pg, desk["node"])
    dd = get(pg, base, "/api/var/backtesting?scope=" + "NODE:" + desk["node"])
    assert pg.locator("[data-bt-zone]").inner_text().lower() == dd["zone"] == desk["zone"]
    assert pg.locator("[data-bt-mult]").inner_text() == f"{dd['multiplier']:.2f}"
    assert len(grid_rows(pg, "[data-bt-exc]")) == len(dd["exceptions"])
    assert pg.evaluate("window.__marker") == 1                              # in place: no page load
    clean(pg)
    f1_opens(pg, "46-backtesting")
    pg.context.close()

    ph = sign_in(browser, base, "mara", PASSWORD, viewport=PHONE)
    ph.goto(base + "/var/backtesting?scope=BANK")
    bt_painted(ph, d["node"])
    no_sideways_scroll(ph)
    assert ph.locator("[data-bt-zone]").is_visible() and ph.locator("[data-bt-chart]").is_hidden()    # on a phone: the zone card only
    clean(ph)
    ph.context.close()


# ------------------------------------------------------------------------------------------------------------------------------- statistics

def test_the_explorer_links_to_the_backtest_of_the_scope_in_view(stack, people, browser):
    """VaR -> Backtest: the explorer's Backtest link opens the backtest of the scope (and date) in view, not always the bank's."""
    base = str(stack)
    pg = sign_in(browser, base, "mara", PASSWORD)
    pg.goto(base + "/var?scope=DESK:RATES")
    wait(pg, "!!document.querySelector('[data-vx-backtest]') && !!window.Drishti && !!window.Drishti.VarExplorer")
    link = pg.locator("[data-vx-backtest]")
    assert link.is_visible() and "Backtest" in link.inner_text()
    link.click()
    pg.wait_for_url("**/var/backtesting?scope=DESK*")
    assert "scope=DESK%3ARATES" in pg.url or "scope=DESK:RATES" in pg.url, pg.url
    d = get(pg, base, "/api/var/backtesting?scope=DESK:RATES")
    bt_painted(pg, d["node"])
    assert not pg.errors, pg.errors
    pg.context.close()


def test_the_period_statistics_are_the_engines(stack, people, browser):
    base = str(stack)
    pg = sign_in(browser, base, "chen", PASSWORD)
    go(pg, base, f"VAR BANK STATS {PERIOD} <GO>")
    pg.wait_for_url(f"**/var/statistics?scope=BANK&period={PERIOD}*")
    s = get(pg, base, f"/api/var/statistics?scope=BANK&period={PERIOD}&measure=var")
    print(f"\nstatistics BANK {PERIOD}: {s.get('records')} records, end {s['periodEnd']}, average {s['average']}, high {s['high']}, "
          f"low {s['low']}, missing {len(s.get('missing') or [])}, synthetic {s.get('synthetic')}")
    assert s["records"] > 1
    wait(pg, "document.querySelectorAll('[data-st-tiles] .rg-tile').length >= 4")
    tiles = {t.locator(".rg-k").inner_text().lower(): (t.locator(".rg-big").inner_text(), t.locator(".rg-s").inner_text())
             for t in pg.locator("[data-st-tiles] .rg-tile").all()}
    assert tiles["period end"] == (mln(s["periodEnd"]["value"]), f"{s['node']} {s['periodEnd']['date']}")
    assert tiles["average"] == (mln(s["average"]), f"{s['records']} official records")
    assert tiles["high"] == (mln(s["high"]["value"]), s["high"]["date"])
    assert tiles["low"] == (mln(s["low"]["value"]), s["low"]["date"])
    assert s["low"]["value"] <= s["average"] <= s["high"]["value"]
    meta = pg.locator("[data-st-meta]").inner_text()
    assert PERIOD in meta and s["from"] in meta and s["to"] in meta
    if s.get("synthetic"):
        assert f"{s['synthetic']} synthetic demo records" in meta                # the screens say so
    missing = pg.locator("[data-st-missing]").inner_text()
    assert missing == ("Missing official records: " + ", ".join(s["missing"]) if s.get("missing") else "No official record missing in the period.")
    clean(pg)
    f1_opens(pg, "44-period-statistics")
    pg.context.close()

    ph = sign_in(browser, base, "chen", PASSWORD, viewport=PHONE)
    ph.goto(f"{base}/var/statistics?scope=BANK&period={PERIOD}")
    wait(ph, "document.querySelectorAll('[data-st-tiles] .rg-tile').length >= 4")
    no_sideways_scroll(ph)
    clean(ph)
    ph.context.close()


# ---------------------------------------------------------------------------------------------------------------------------------- reports

def drawn(pg, version, label):
    """The run of a version drawn with its sign button reading ``label`` (``Sign as Checker in Approvals``; ``Signed`` once signed)."""
    wait(pg, "document.querySelector('[data-rp-meta]').textContent.indexOf('run v%d,') === 0"
             " && document.querySelector('[data-rp-approve]').textContent === %s" % (version, json.dumps(label)))


def sign_label(step):
    return "Sign as " + step + " in Approvals"


def decide(pg, act, comment=""):
    """The next step decided in the one Approvals queue: the report's sign button opens the run's item there; approve or reject (send it
    back) with a comment; back on the report page. Returns what the queue said."""
    back = pg.url
    pg.locator("[data-rp-approve]").click()
    pg.wait_for_url("**/approvals/var-report/**")
    pg.wait_for_selector("[data-ap-item]:not([hidden])")
    if pg.locator("[data-ap-act=%s]" % act).is_hidden():           # the queue offers no such decision to this person (four eyes)
        pg.goto(back)
        return None
    pg.locator("[data-ap-comment]").fill(comment)
    pg.locator("[data-ap-act=%s]" % act).click()
    wait(pg, "document.querySelector('[data-ap-said]').textContent !== ''")
    said = pg.locator("[data-ap-said]").inner_text()
    pg.goto(back)
    return said


def approve(pg, comment=""):
    return decide(pg, "approve", comment)


def test_a_report_run_is_generated_checked_and_signed_by_three_people(stack, people, browser):
    base = str(stack)
    mara = sign_in(browser, base, "mara", PASSWORD)
    go(mara, base, f"VAR REPORT {REPORT} {PERIOD} <GO>")
    mara.wait_for_url(f"**/var/reports/{REPORT}?period={PERIOD}*")
    pack = get(mara, base, "/api/var/reports")
    wait(mara, "document.querySelectorAll('[data-rp-pack] .dg-row').length === %d" % len(pack["reports"]))
    row = mara.locator("[data-rp-pack] .dg-row.rg-sel")
    assert REPORT in row.inner_text() and "not generated" in row.inner_text()

    # Generate freezes run v1 and opens it
    row.locator("button.dg-cell-btn").click()
    mara.wait_for_url(f"**/var/reports/{REPORT}?period={PERIOD}&version=1")
    run = get(mara, base, f"/api/var/reports/{REPORT}/runs/{PERIOD}?version=1")
    assert run["version"] == 1 and run["signoff"]["next"] == "maker"
    summary = run["checksSummary"]
    print(f"\nreport {REPORT} {PERIOD} v1: checks {summary}: " + "; ".join(f"{c['status']} {c.get('title') or c.get('check')}" for c in run["checks"]))
    wait(mara, "document.querySelector('[data-rp-meta]').textContent.indexOf('run v1,') === 0")
    # the checks as the API's: a row each, its status and its title
    wait(mara, grid_ready("[data-rp-checks]"))
    checks = grid_rows(mara, "[data-rp-checks]")
    assert len(checks) == len(run["checks"]) > 0
    for i, c in enumerate(run["checks"]):
        assert checks[i]["cells"][0] == c["status"]
        assert checks[i]["cells"][1] == (c.get("title") or c.get("check"))
    assert mara.locator("[data-rp-checks-sub]").inner_text().startswith(f"{len(run['checks'])} · {summary['pass']} pass, {summary['note']} need a note, "
                                                                       f"{summary['fail']} fail")
    # the table as it will be filed: a row a definition row; the total's period end is the official figure; a cell's lineage
    table = grid_rows(mara, "[data-rp-table]")
    assert len(table) == len(run["rows"])
    total = next(r for r in run["rows"] if r.get("total"))
    end = total["cells"]["period-end"]["value"]
    shown = f"{round(end / 1000 if run.get('units') == 'thousands' else end):,}"
    assert shown in next(t for t in table if t["data"]["line"].get("total"))["cells"]
    mara.locator("[data-rp-table] .dg-row.rg-total .dg-cell.rg-cell").first.click()
    mara.locator("[data-rp-lin-card]").wait_for()
    lin = mara.locator("[data-rp-lineage]").inner_text()
    assert "Official record" in lin and total["cells"]["period-end"]["lineage"]["node"] in lin
    assert mara.locator("[data-rp-lin-links] a").first.get_attribute("href").startswith("/var?scope=NODE%3A")
    # before the last approval the files are drafts
    assert all("(draft)" in a.inner_text() for a in mara.locator("[data-rp-files] a").all())
    assert mara.locator("[data-rp-approve]").inner_text() == sign_label("Maker")             # each step is signed in the Approvals queue
    CRO = sign_label((run.get("stepTitles") or {}).get("approver") or "Approver")             # the definition names the approver's step

    if summary["fail"]:
        # a failed check blocks every approval, said in words (the demo's data should not get here: the checks are printed above)
        assert "failed" in approve(mara, "reviewed")
        clean(mara)
        pytest.fail(f"a check of the demo's run failed, so the sign-off chain could not be walked: {run['checks']}")

    # the maker writes the narrative and signs (with a note, which a check that needs one asks for)
    text = "Trading VaR stayed within appetite; the movement is explained by the rates desk."
    mara.locator("[data-rp-narrative]").fill(text)
    mara.locator("[data-rp-save-narrative]").click()
    deadline = time.monotonic() + BROWSER_WAIT_MS / 1000
    while get(mara, base, f"/api/var/reports/{REPORT}/runs/{PERIOD}?version=1")["signoff"].get("narrative") != text:
        assert time.monotonic() < deadline, "the narrative was not saved: " + mara.locator("[data-rp-notice]").inner_text()
        mara.wait_for_timeout(200)
    assert approve(mara, "Movement reviewed against the desks' positions.") == "Approved."
    drawn(mara, 1, sign_label("Checker"))
    assert "mara" in mara.locator("[data-rp-steps]").inner_text()
    # four eyes: the maker may not sign the checker's step too (the queue offers her no Approve; nothing is signed)
    assert approve(mara, "") is None
    drawn(mara, 1, sign_label("Checker"))
    assert get(mara, base, f"/api/var/reports/{REPORT}/runs/{PERIOD}?version=1")["signoff"]["next"] == "checker"
    assert mara.locator("[data-rp-narrative]").evaluate("e => e.readOnly")    # past the maker: the narrative is closed
    clean(mara)
    f1_opens(mara, "6-controls-definitions-checks-frozen-runs-and-sign-off")
    mara.context.close()

    # the checker sends it back (with a comment), which clears the steps; the maker signs again; the checker signs
    chen = sign_in(browser, base, "chen", PASSWORD)
    chen.goto(f"{base}/var/reports/{REPORT}?period={PERIOD}&version=1")
    drawn(chen, 1, sign_label("Checker"))
    assert decide(chen, "reject", "Please name the desk in the narrative.") != ""              # sent back: the steps clear
    drawn(chen, 1, sign_label("Maker"))
    mara = sign_in(browser, base, "mara", PASSWORD)
    mara.goto(f"{base}/var/reports/{REPORT}?period={PERIOD}&version=1")
    drawn(mara, 1, sign_label("Maker"))
    assert approve(mara, "Desk named: rates.") == "Approved."
    drawn(mara, 1, sign_label("Checker"))
    clean(mara)
    mara.context.close()
    chen.reload()
    drawn(chen, 1, sign_label("Checker"))
    assert approve(chen, "Checked.") == "Approved."
    drawn(chen, 1, CRO)
    clean(chen)
    chen.context.close()

    # the approver (the definition's CRO) signs last: the run is signed, the files released
    aria = sign_in(browser, base, "aria", PASSWORD)
    aria.goto(f"{base}/var/reports/{REPORT}?period={PERIOD}&version=1")
    drawn(aria, 1, CRO)
    assert approve(aria, "Approved.") == "Approved."
    drawn(aria, 1, "Signed")
    assert aria.locator("[data-rp-approve]").get_attribute("aria-disabled") == "true"
    files = aria.locator("[data-rp-files] a")
    assert [a.inner_text() for a in files.all()] == run["outputs"]               # released: no longer drafts
    done = get(aria, base, f"/api/var/reports/{REPORT}/runs/{PERIOD}?version=1")
    assert done["signoff"]["state"] == "signed"
    assert [(s["step"], s["who"]) for s in done["signoff"]["steps"]] == [("maker", "mara"), ("checker", "chen"), ("approver", "aria")]
    steps = aria.locator("[data-rp-steps]").inner_text()
    assert all(w in steps for w in ("mara", "chen", "aria"))
    r = aria.request.get(base + files.first.get_attribute("href"))
    assert r.ok and len(r.body()) > 100
    wait(aria, "Array.from(document.querySelectorAll('[data-rp-pack] .dg-row.rg-sel .dg-cell')).some((t) => t.textContent === 'signed')")
    clean(aria)
    aria.context.close()

    ph = sign_in(browser, base, "aria", PASSWORD, viewport=PHONE)
    ph.goto(f"{base}/var/reports/{REPORT}?period={PERIOD}&version=1")
    drawn(ph, 1, "Signed")
    no_sideways_scroll(ph)
    clean(ph)
    ph.context.close()
