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

"""Pictures of tutorial 11, BI that runs itself (docs/tutorials/11-bi-that-runs-itself.md), written to docs/tutorials/img/t11/.

A dataset of the model layer that rebuilds itself every business morning (its page's Refresh), a subscription to a report (what Run now says without a mail
server, and with one: the pictures marked "mail" need the server started with spring.mail.host pointing at a catcher), an alert on a number of a report and on a
measure of a model, the four export formats, and expected values: a report's tests that stop a publish, and a model's own tests in the model designer."""
from __future__ import annotations

import base64
import urllib.parse

from _bi import bi, clean_everywhere, clean_items, open_report, settled
from _tut import ROOT, OUT, elem_shot, SERVER_ENV, VERA, call, go, login, people  # noqa: F401
from shotlib import Ctx, register

shot = register("t11", OUT / "t11")
DATA = ROOT / "docs" / "tutorials" / "data"
DATASET_TITLE = "Trades refreshed daily"
MODEL_TITLE = "Trades daily"
WORKSPACE = "economics-desk"
WORLD_REPORT = "file:report:world-economics"
OVERVIEW = "file:report:trades-overview"


def ensure_workspace(c: Ctx):
    if not any(w["id"] == WORKSPACE for w in bi(c, "GET", "/workspaces")):
        bi(c, "POST", "/workspaces", {"name": "Economics desk", "description": "Reports on the world economy"})


def published(c: Ctx, title: str, kind: str, body: str, note: str) -> str:
    """The published item of that title in the workspace (made once; found again on later runs)."""
    cat = bi(c, "GET", "/catalogue")
    for it in (cat.get("items", []) if isinstance(cat, dict) else cat):
        if it["title"] == title and not it.get("shipped"):
            return it["id"]
    ensure_workspace(c)
    made = bi(c, "POST", f"/workspaces/{WORKSPACE}/items", {"kind": kind, "title": title, "body": body})
    bi(c, "POST", f"/items/{made['id']}/publish", {"note": note})
    return made["id"]


def scheduled_dataset(c: Ctx) -> str:
    """The published dataset that refreshes itself, and the model over it (the model reads the dataset by name)."""
    ds = published(c, DATASET_TITLE, "dataset", (DATA / "trades-daily.dataset.yaml").read_text(), "refreshes itself on business mornings")
    published(c, MODEL_TITLE, "model", (DATA / "trades-daily.model.yaml").read_text(), "the measures over the dataset")
    return ds


@shot("18-excel-chart.jpg")
def excel_chart(c: Ctx):
    """The Excel export of one visual of the Overview page, opened in LibreOffice: the data sheet and its native chart (needs soffice and pdftoppm; skipped without them)."""
    import shutil
    import subprocess
    import tempfile
    from pathlib import Path

    if not (shutil.which("soffice") and shutil.which("pdftoppm") and shutil.which("convert")):
        print("  (skipped: needs soffice, pdftoppm and convert)")
        return
    login(c, height=900)
    open_report(c, OVERVIEW)
    c.page.select_option("[data-export-scope]", "by-desk")
    with c.page.expect_download() as d:
        c.page.click('[data-export-fmt="xlsx"]')
    work = Path(tempfile.mkdtemp(prefix="drishti-t11-"))
    try:
        xlsx = work / "chart.xlsx"
        d.value.save_as(xlsx)
        subprocess.run(["soffice", "--headless", f"-env:UserInstallation=file://{work / 'profile'}", "--convert-to", "pdf", "--outdir", str(work), str(xlsx)], check=True, capture_output=True, timeout=180)
        subprocess.run(["pdftoppm", "-r", "70", "-png", "-f", "2", "-l", "2", str(work / "chart.pdf"), str(work / "page")], check=True, timeout=60)
        page = sorted(work.glob("page-*.png"))[0]
        out = OUT / "t11"
        out.mkdir(parents=True, exist_ok=True)
        subprocess.run(["convert", str(page), "-trim", "-bordercolor", "white", "-border", "14", "-quality", "84", str(out / "18-excel-chart.jpg")], check=True, timeout=60)
        print("  wrote", (out / "18-excel-chart.jpg").relative_to(ROOT))
    finally:
        shutil.rmtree(work, ignore_errors=True)


@shot("01-refresh.jpg", also=("02-refresh-now.jpg",))
def refresh(c: Ctx):
    login(c, height=900)
    clean_everywhere(c, MODEL_TITLE, DATASET_TITLE, workspace_names=("Economics desk",))      # a fresh dataset: it has not run yet
    ds = scheduled_dataset(c)
    go(c, "/bi/ds/" + ds, wait="[data-layer-dataset][data-ready=yes]", settle=1500)
    c.page.wait_for_selector("[data-refresh] tbody tr", timeout=20000)
    c.page.wait_for_timeout(500)
    elem_shot(c, "01-refresh.jpg", "[data-refresh-sec]", pad=14)
    c.page.click("[data-refresh-now]")
    c.page.wait_for_function("() => /Refreshed|Refresh /.test(document.querySelector('[data-refresh-msg]').textContent)", timeout=60000)
    c.page.wait_for_timeout(1500)
    elem_shot(c, "02-refresh-now.jpg", "[data-refresh-sec]", pad=14)


@shot("03-subscribe-form.jpg", also=("04-subscription-saved.jpg", "05-run-no-smtp.jpg", "06-history-no-smtp.jpg"))
def subscribe_no_mail(c: Ctx):
    people(c)
    login(c, height=900)
    for name in ("Morning economy pack",):
        c.page.request.post(c.base + "/reports/" + urllib.parse.quote(name) + "/delete")
    open_report(c, WORLD_REPORT, wait_map=True)
    c.page.click("[data-subscribe]")
    c.page.wait_for_selector("[data-subscribe-form]")
    f = c.page.locator("[data-subscribe-form]")
    f.locator('input[name="name"]').fill("Morning economy pack")
    # When: every business day at 07:30 is the form's own default (the schedule builder writes business-days 07:30)
    f.locator('input[name="recipients"]').fill(VERA)
    f.locator('select[name="format"]').select_option("xlsx")
    c.page.wait_for_timeout(500)
    c.save("03-subscribe-form.jpg", clip={"x": 0, "y": 56, "width": 1280, "height": 800})
    f.locator("button[type=submit]").click()
    c.page.wait_for_load_state()
    c.page.wait_for_timeout(1500)
    c.save("04-subscription-saved.jpg", clip={"x": 0, "y": 56, "width": 1280, "height": 640})
    c.page.locator('tr[data-row-name="Morning economy pack"] form[action$="/run"] button').click()
    c.page.wait_for_load_state()
    c.page.wait_for_timeout(3500)
    c.save("05-run-no-smtp.jpg", clip={"x": 0, "y": 56, "width": 1280, "height": 640})
    c.page.locator("details.dist-hist summary").first.click()
    c.page.wait_for_timeout(1200)
    c.save("06-history-no-smtp.jpg", clip={"x": 0, "y": 56, "width": 1280, "height": 700})


def clean_alerts(c: Ctx, *names: str):
    for n in names:
        c.page.request.post(c.base + "/alerts/measure/" + urllib.parse.quote(n) + "/delete")


@shot("07-alert-me.jpg", also=("08-alert-form.jpg", "09-alerts-list.jpg", "10-alert-history.jpg", "13-inbox-alert.jpg"))
def alerts(c: Ctx):
    login(c, height=900)
    clean_alerts(c, "MTM floor", "Notional ceiling", "FX desk MTM floor")
    open_report(c, OVERVIEW, "overview")
    card = c.page.locator('[data-visual="mtm"]')
    card.scroll_into_view_if_needed()
    c.page.evaluate("window.scrollTo(0, 150)")
    c.page.wait_for_timeout(300)
    b = card.bounding_box()
    c.save("07-alert-me.jpg", clip={"x": max(0, b["x"] - 20), "y": max(0, b["y"] - 20), "width": b["width"] + 40, "height": b["height"] + 40})
    card.locator('[data-act="alert-me"]').click()
    c.page.wait_for_url("**/alerts/new**")
    c.page.fill('input[name="name"]', "MTM floor")
    c.page.select_option('select[name="op"]', "below")
    c.page.fill('input[name="value"]', "3000000000")
    c.page.fill('input[name="hysteresis"]', "10000000")
    c.page.select_option('select[name="trigger_mode"]', "after-load")
    c.page.click("[data-check-now]")
    c.page.wait_for_function("() => document.querySelector('[data-check-out]').textContent.includes('Right now')")
    c.page.wait_for_timeout(500)
    c.page.evaluate("window.scrollTo(0, 0)")
    c.save("08-alert-form.jpg", clip={"x": 0, "y": 56, "width": 1280, "height": 844})
    c.page.click("[data-alert-form] button[type=submit]")
    c.page.wait_for_url("**/alerts?msg=**")
    c.page.wait_for_timeout(2500)
    c.page.reload()
    c.page.wait_for_timeout(1500)
    c.save("09-alerts-list.jpg", clip={"x": 0, "y": 56, "width": 1280, "height": 700})
    go(c, "/alerts/measure/MTM%20floor", wait="[data-history]", settle=1200)
    elem_shot(c, "10-alert-history.jpg", "[data-history]", pad=12)
    go(c, "/inbox?tab=alert", wait=".inbox", settle=1500)
    c.save("13-inbox-alert.jpg", clip={"x": 0, "y": 56, "width": 1280, "height": 480})


@shot("21-alert-model.jpg")
def alert_on_a_model(c: Ctx):
    """Alerts, New: Watch a measure of a model, with a filter, below a limit, checked now (not saved)."""
    login(c, height=900)
    scheduled_dataset(c)
    go(c, "/alerts/new", wait="[data-alert-form]", settle=1200)
    c.page.select_option('select[name="source_type"]', "model")
    c.page.wait_for_timeout(500)
    c.page.select_option('select[name="model_ref"]', "trades-daily@1")
    c.page.wait_for_timeout(1200)
    c.page.select_option('select[name="model_measure"]', "MTM")
    c.page.click("[data-al-addfilter]")
    row = c.page.locator("[data-al-filter]").first
    row.locator("select").first.select_option("trade.DESK")
    row.locator("input").fill("DESK-FX")
    c.page.fill('input[name="name"]', "FX desk MTM floor")
    c.page.select_option('select[name="op"]', "below")
    c.page.fill('input[name="value"]', "-10000000")
    c.page.click("[data-check-now]")
    c.page.wait_for_function("() => document.querySelector('[data-check-out]').textContent.includes('Right now')", timeout=30000)
    c.page.wait_for_timeout(500)
    c.page.evaluate("window.scrollTo(0, 0)")
    c.save("21-alert-model.jpg", clip={"x": 0, "y": 56, "width": 1280, "height": 844})


@shot("11-export-bar.jpg", also=("12-export-png.jpg",))
def exports(c: Ctx):
    login(c, height=900)
    open_report(c, OVERVIEW, "overview")
    c.page.evaluate("window.scrollTo(0, 0)")
    c.save("11-export-bar.jpg", clip={"x": 240, "y": 230, "width": 1040, "height": 150})
    url = f"/dist/bi/{urllib.parse.quote(OVERVIEW, safe='')}.png?page=overview"
    png = c.page.request.get(c.base + url).body()
    shown = c.page
    shown.goto("about:blank")
    shown.set_viewport_size({"width": 1280, "height": 900})
    shown.set_content('<body style="margin:0;background:#0b1020"><img style="max-width:100%" src="data:image/png;base64,' + base64.b64encode(png).decode() + '"></body>')
    shown.wait_for_timeout(800)
    from shotlib import out_dir
    shown.screenshot(path=str(out_dir("t11") / "12-export-png.jpg"), type="jpeg", quality=84)
    print("  wrote 12-export-png.jpg (a PNG of", len(png), "bytes)")


@shot("14-run-with-smtp.jpg", also=("15-history-with-smtp.jpg",))
def subscribe_with_mail(c: Ctx):
    """Needs the server started with spring.mail.host pointing at a mail catcher (the tutorial shows how); without it this picture says no SMTP host again."""
    people(c)
    login(c, height=900)
    go(c, "/reports", wait="tr[data-row-name='Morning economy pack']", settle=800)
    c.page.locator('tr[data-row-name="Morning economy pack"] form[action$="/run"] button').click()
    c.page.wait_for_load_state()
    c.page.wait_for_timeout(4000)
    c.save("14-run-with-smtp.jpg", clip={"x": 0, "y": 56, "width": 1280, "height": 560})
    c.page.locator("details.dist-hist summary").first.click()
    c.page.wait_for_timeout(1200)
    c.save("15-history-with-smtp.jpg", clip={"x": 0, "y": 56, "width": 1280, "height": 640})


TESTED_TITLE = "Desk check"


@shot("16-tests.jpg", also=("17-publish-refused.jpg", "20-model-tests.jpg"))
def expected_values(c: Ctx):
    """Expected values (tests:) on a report of the model: run by the designer's Check, and a publish refused while one fails; then the model's own tests in the
    model designer's Contract tab."""
    login(c, height=900)
    clean_everywhere(c, TESTED_TITLE)
    scheduled_dataset(c)
    ensure_workspace(c)
    made = bi(c, "POST", f"/workspaces/{WORKSPACE}/items", {"kind": "report", "title": TESTED_TITLE, "body": (DATA / "desk-check.report.yaml").read_text()})
    go(c, "/bi/design/" + made["id"], wait="[data-design-root][data-ready=yes]", settle=1500)
    c.page.click("[data-check]")                                         # Check runs the report's tests too
    c.page.wait_for_selector("[data-check-box] [data-test]", timeout=60000)
    c.page.wait_for_timeout(800)
    elem_shot(c, "16-tests.jpg", "[data-check-box]", pad=14)
    c.page.goto(c.base + "/bi/i/" + made["id"])
    c.page.wait_for_selector("h1")
    c.page.wait_for_timeout(1200)
    c.page.get_by_role("button", name="Publish", exact=True).click()
    c.page.wait_for_selector(".bi-bad", timeout=30000)
    print("publish answers:", c.page.locator(".bi-bad").first.inner_text())
    c.page.wait_for_timeout(500)
    c.save("17-publish-refused.jpg", clip={"x": 0, "y": 56, "width": 1280, "height": 480})
    clean_everywhere(c, TESTED_TITLE)
    # the model's own tests: a Drishti SQL question and the figure it must give, run from the model designer
    model = next(i for i in bi(c, "GET", f"/workspaces/{WORKSPACE}/items") if i["title"] == MODEL_TITLE)
    body = (DATA / "trades-daily.model.yaml").read_text() + (
        "tests:\n  - { name: The book holds 15000 trades, sql: \"SELECT MEASURE('Trades') FROM trade\", expect: 15000 }\n"
        "  - { name: Every desk has trades, sql: \"SELECT COUNT(DISTINCT trade.DESK) FROM trade\", expect: { min: 1 } }\n")
    bi(c, "PUT", "/items/" + model["id"], {"body": body, "message": "the figures it must give"})
    go(c, "/bi/md/" + model["id"], wait="[data-md-root][data-ready=yes]", settle=1500)
    c.page.click('[data-tab="contract"]')
    c.page.wait_for_selector("[data-model-tests] [data-tests-run]", timeout=20000)
    c.page.click("[data-model-tests] [data-tests-run]")
    c.page.wait_for_selector("[data-model-tests] [data-tests-table] tbody tr", timeout=60000)
    c.page.wait_for_timeout(600)
    elem_shot(c, "20-model-tests.jpg", "[data-model-tests]", pad=14)
    bi(c, "PUT", "/items/" + model["id"], {"body": (DATA / "trades-daily.model.yaml").read_text(), "message": "back to the tutorial's text"})


UNSCHEDULED = "Trades without a schedule"


@shot("19-set-schedule.jpg")
def set_schedule(c: Ctx):
    """A dataset with no schedule: the Refresh section says so and an author sets one with two controls, no YAML."""
    login(c, height=900)
    clean_items(c, UNSCHEDULED)
    body = (DATA / "trades-daily.dataset.yaml").read_text().replace('refresh: "business-days 06:30"', "refresh: { on-load: true }").replace("Trades refreshed daily", UNSCHEDULED)
    body = body.replace("dataset: trades-daily", "dataset: trades-unscheduled")
    made = bi(c, "POST", "/workspaces/me/items", {"kind": "dataset", "title": UNSCHEDULED, "body": body})
    go(c, "/bi/ds/" + made["id"], wait="[data-layer-dataset][data-ready=yes]", settle=1500)
    c.page.wait_for_selector("[data-set-schedule]:not([hidden])", timeout=20000)
    c.page.select_option('[data-set-schedule] [name="kind"]', "business-days")
    c.page.fill('[data-set-schedule] [name="time"]', "06:30")
    c.page.wait_for_timeout(500)
    elem_shot(c, "19-set-schedule.jpg", "[data-refresh-sec]", pad=14)
    clean_items(c, UNSCHEDULED)
