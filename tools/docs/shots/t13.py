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

"""Pictures of tutorial 13, Govern BI (docs/tutorials/13-govern-bi.md), written to docs/tutorials/img/t13/.

Three people: ann writes (bi, bi-author), sue is a steward (bi, bi-steward) and the development administrator. A dataset of the model layer and a model over it
(2.2): the model is saved twice, compared, restored, proposed, reviewed and published; a report is built on it; both are certified; the model's lineage is read;
dropping a column a report uses shows in the model's Contract tab as a new major version with the report named, and the dataset's publish is refused
(DRS-9126); the workspace is exported as a bundle and the bundle imported as a dry run. Renames of a version 1 dataset, rolling an import back and the lineage
past the connector follow.

The pictures run in order (the first makes what the others use), as ann, sue and the administrator in turn. The captured API answers are printed as they are read."""
from __future__ import annotations

import json
import os
import re
from pathlib import Path

from _bi import bi, clean_everywhere
from _tut import ADMIN, OUT, PASSWORD, ROOT, SERVER_ENV, call, go, login, page_shot  # noqa: F401
from shotlib import Ctx, register

shot = register("t13", OUT / "t13")
DATA = ROOT / "docs" / "tutorials" / "data"
WS = "rates-desk"
MODEL, REPORT, DATASET = "Trades model", "Trades report", "Rates trades"
STATE: dict = {}


def people(c: Ctx):
    """ann (author) and sue (steward) with their BI powers, made by the administrator; harmless when they exist."""
    login(c)
    for role, powers in (("bi-writer", ["bi", "bi-author"]), ("bi-steward", ["bi", "bi-steward"])):
        call(c, "POST", "/admin/api/roles/" + role, {"kinds": ["*"], "raw": True, "approve": False, "bi": powers, "description": role})
    for user, role in (("ann", "bi-writer"), ("sue", "bi-steward")):
        call(c, "POST", "/admin/api/users", {"username": user, "displayName": user.capitalize(), "email": f"{user}@desk.example", "roles": [role], "password": PASSWORD, "mustChangePassword": False})


def as_(c: Ctx, user: str):
    login(c, user)


def item_id(c: Ctx, title: str) -> str:
    cat = bi(c, "GET", "/catalogue")
    rows = cat.get("items", []) if isinstance(cat, dict) else cat
    ws = bi(c, "GET", f"/workspaces/{WS}/items")
    rows = rows + (ws if isinstance(ws, list) else ws.get("items", []))
    return next(i["id"] for i in rows if i["title"] == title and not i.get("shipped"))


def open_item(c: Ctx, id_: str):
    go(c, "/bi/i/" + id_, wait="[data-meta]", settle=1200)
    c.page.wait_for_function("() => document.querySelector('[data-meta]').textContent.includes('owner')")
    c.page.wait_for_timeout(800)


def edit_body(c: Ctx, change, message: str):
    c.page.get_by_role("button", name="Edit", exact=True).click()
    box = c.page.locator("[data-edit] textarea[name=body]")
    box.fill(change(box.input_value()))
    c.page.fill("[data-edit] input[name=message]", message)
    c.page.click("[data-edit] button[type=submit]")
    c.page.wait_for_function("() => document.querySelector('[data-msg]').textContent.includes('Draft saved')")
    c.page.wait_for_timeout(700)


def region(c: Ctx, name: str, selector: str, pad: int = 10):
    c.page.evaluate("window.scrollTo(0, 0)")
    c.page.locator(selector).first.scroll_into_view_if_needed()
    c.page.evaluate("(s) => { const e = document.querySelector(s); window.scrollTo(0, Math.max(0, e.getBoundingClientRect().top + window.scrollY - 70)); }", selector)
    c.page.wait_for_timeout(400)
    b = c.page.locator(selector).first.bounding_box()
    c.save(name, clip={"x": max(0, b["x"] - pad), "y": max(0, b["y"] - pad), "width": min(1280, b["width"] + 2 * pad), "height": min(900 - max(0, b["y"] - pad), b["height"] + 2 * pad)})


def show(label: str, value):
    print(f"CAPTURE {label}: " + json.dumps(value, default=str)[:3000])


@shot("01-bi-settings.jpg", also=("02-workspace.jpg", "03-history-diff.jpg", "04-propose.jpg"))
def authoring(c: Ctx):
    people(c)
    login(c, height=900)
    clean_everywhere(c, REPORT, MODEL, DATASET, workspace_names=("Rates desk",))
    go(c, "/admin/bi", wait="table", settle=1500)
    c.page.locator("tr", has_text="bi-writer").first.evaluate("e => e.scrollIntoView({block: 'center'})")
    c.page.wait_for_timeout(500)
    c.save("01-bi-settings.jpg")
    as_(c, "ann")
    c.page.goto(c.base + "/bi/workspaces")
    c.page.wait_for_selector("input[name=name]")
    c.page.fill("input[name=name]", "Rates desk")
    c.page.fill("input[name=description]", "Rates trading: models and reports")
    c.page.check("input[type=checkbox]")
    c.save("02-workspace.jpg", clip={"x": 240, "y": 56, "width": 760, "height": 600})
    c.page.click("text=Create workspace")
    c.page.wait_for_timeout(1500)
    bi(c, "PUT", f"/workspaces/{WS}/members/sue", {"role": "member"})
    audience = ["bi-steward", "bi-writer", "viewer"]
    ds = bi(c, "POST", f"/workspaces/{WS}/items", {"kind": "dataset", "title": DATASET, "description": "The rates desk's trades", "body": (DATA / "rates-trades.dataset.yaml").read_text(),
                                                    "message": "first cut", "tags": ["rates"], "audienceRoles": audience})
    STATE["dataset"] = ds["id"]
    publish_as_sue(c, ds["id"])                                        # the dataset first: proposed by ann, approved by sue (not shown)
    as_(c, "ann")
    made = bi(c, "POST", f"/workspaces/{WS}/items", {"kind": "model", "title": MODEL, "description": "Trades by desk and book, for the rates desk",
                                                      "body": (DATA / "trades-model.model.yaml").read_text(), "message": "first cut", "tags": ["rates"],
                                                      "audienceRoles": audience})
    STATE["model"] = made["id"]
    open_item(c, made["id"])
    edit_body(c, lambda t: t.replace("hierarchies:\n", "fields:\n  trade.PORTFOLIO: { display: Book, description: The trading book }\nhierarchies:\n"), "name and describe the book")
    edit_body(c, lambda t: t.replace("  Trades:   { expr", "  Deals:    { expr"), "rename the count measure")
    c.page.select_option("[data-diff-from]", "r1")
    c.page.select_option("[data-diff-to]", "draft")
    c.page.click("[data-diff-form] button[type=submit]")
    c.page.wait_for_selector("[data-diff-out] table.bi-diff")
    c.page.wait_for_timeout(400)
    region(c, "03-history-diff.jpg", "[data-sec-history]")
    show("history", bi(c, "GET", f"/items/{made['id']}/history"))
    show("diff", bi(c, "GET", f"/items/{made['id']}/diff?from=r1&to=draft"))
    c.page.locator('[data-history] tr[data-seq="2"] button:has-text("Restore as draft")').click()
    c.page.wait_for_function("() => document.querySelector('[data-msg]').textContent.includes('is the draft again')")
    c.page.wait_for_timeout(600)
    show("history_after_restore", bi(c, "GET", f"/items/{made['id']}/history"))
    c.page.fill(".bi-note", "ready for a look")
    c.page.get_by_role("button", name="Propose for review").click()
    c.page.wait_for_function("() => document.querySelector('[data-msg]').textContent.includes('Proposed')")
    c.page.wait_for_timeout(600)
    region(c, "04-propose.jpg", ".bi-canvas", pad=0)
    show("ann_approve_own", call(c, "POST", f"/bi/api/items/{made['id']}/approve", {"note": "me"}))


@shot("05-review.jpg", also=("06-approved.jpg", "07-ann-inbox.jpg"))
def review(c: Ctx):
    as_(c, "sue")
    sue_inbox = bi(c, "GET", "/me/inbox?limit=5")
    show("sue_inbox", sue_inbox)
    model = STATE.get("model") or item_id(c, MODEL)
    open_item(c, model)
    c.page.wait_for_function("() => /Check passed|Check found/.test((document.querySelector('[data-review-body]') || {}).textContent || '')", timeout=30000)
    c.page.wait_for_timeout(500)
    region(c, "05-review.jpg", "[data-sec-review]")
    show("review", bi(c, "GET", f"/items/{model}/review"))
    c.page.fill(".bi-note", "looks right")
    c.page.get_by_role("button", name="Approve and publish").click()
    c.page.wait_for_function("() => document.querySelector('[data-msg]').textContent.includes('Approved')")
    c.page.wait_for_timeout(700)
    region(c, "06-approved.jpg", ".bi-canvas", pad=0)
    as_(c, "ann")
    show("ann_inbox", bi(c, "GET", "/me/inbox?limit=5"))
    go(c, "/inbox", wait=".inbox", settle=1500)
    c.save("07-ann-inbox.jpg", clip={"x": 0, "y": 56, "width": 1280, "height": 400})
    # the report on the model: proposed by ann, approved by sue (not shown), published
    made = bi(c, "POST", f"/workspaces/{WS}/items", {"kind": "report", "title": REPORT, "description": "Notional and MTM by desk and book", "body": (DATA / "trades-report.report.yaml").read_text(),
                                                      "message": "first cut", "tags": ["rates"], "audienceRoles": ["bi-steward", "bi-writer", "viewer"]})
    STATE["report"] = made["id"]
    bi(c, "POST", f"/items/{made['id']}/propose", {"note": "the report on the model"})
    as_(c, "sue")
    bi(c, "POST", f"/items/{made['id']}/approve", {"note": "good"})


@shot("08-certify.jpg", also=("09-catalogue-certified.jpg", "10-lineage.jpg"))
def certify(c: Ctx):
    as_(c, "sue")
    model = STATE.get("model") or item_id(c, MODEL)
    report = STATE.get("report") or item_id(c, REPORT)
    open_item(c, model)
    region(c, "08-certify.jpg", "[data-actions]", pad=14)
    if c.page.get_by_role("button", name="Certify").count():          # a second run finds it certified already
        c.page.get_by_role("button", name="Certify").click()
    c.page.wait_for_timeout(1500)
    bi(c, "POST", f"/items/{report}/endorse", {"level": "certified"})
    go(c, "/bi/catalogue", wait=".bi-card", settle=1500)
    c.save("09-catalogue-certified.jpg", clip={"x": 0, "y": 56, "width": 1280, "height": 700})
    as_(c, "ann")
    open_item(c, model)
    region(c, "10-lineage.jpg", "[data-sec-lineage]")
    show("lineage_model", bi(c, "GET", "/lineage/trades-model@1"))
    show("consumers", bi(c, "GET", "/models/trades-model@1/consumers"))
    show("lineage_report", bi(c, "GET", f"/items/{report}/lineage"))
    show("impact_on_book", bi(c, "GET", "/models/trades-model@1/impact?target=trade.PORTFOLIO"))


@shot("11-contract-change.jpg", also=("12-dataset-refused.jpg",))
def impact(c: Ctx):
    """Dropping a column a published report uses: the model's Contract tab names the change, the report and the version it needs; the dataset's publish is
    refused until its version says so (DRS-9126). Nothing is saved in the model."""
    as_(c, "ann")
    model = STATE.get("model") or item_id(c, MODEL)
    go(c, "/bi/md/" + model, wait="[data-md-root][data-ready=yes]", settle=1500)
    c.page.click('[data-tab="contract"]')
    c.page.wait_for_selector("[data-c-exposes]", timeout=20000)
    c.page.select_option("[data-c-exposes]", "listed")                 # listing starts from everything: untick what to take away
    c.page.wait_for_selector('[data-c-pick="trade.PORTFOLIO"]:checked', timeout=20000)
    c.page.uncheck('[data-c-pick="trade.PORTFOLIO"]')
    c.page.wait_for_selector("[data-contract-changes] li", timeout=30000)
    c.page.wait_for_timeout(800)
    page_shot(c, "11-contract-change.jpg", c.page.evaluate("() => { const b = document.querySelector('[data-contract-pane]').getBoundingClientRect(); return {x: b.x, y: b.y + window.scrollY, width: b.width, height: Math.min(b.height, 640)}; }"), pad=8)
    # the dataset: the column goes, and the server says the version must say so
    ds = STATE.get("dataset") or item_id(c, DATASET)
    body = (DATA / "rates-trades.dataset.yaml").read_text().replace("  PORTFOLIO:    { type: string }\n", "")
    bi(c, "PUT", f"/items/{ds}", {"body": body, "message": "drop the book column"})
    bi(c, "POST", f"/items/{ds}/propose", {"note": "we no longer split by book"})
    as_(c, "sue")
    open_item(c, ds)
    c.page.get_by_role("button", name="Approve and publish").click()
    c.page.wait_for_function("() => /DRS-9126/.test(document.querySelector('[data-msg]').textContent)", timeout=30000)
    c.page.wait_for_timeout(600)
    region(c, "12-dataset-refused.jpg", ".bi-canvas", pad=0)
    show("refusal", call(c, "POST", f"/bi/api/items/{ds}/approve", {"note": "x"}))
    as_(c, "ann")
    bi(c, "POST", f"/items/{ds}/withdraw", {})
    bi(c, "PUT", f"/items/{ds}", {"body": (DATA / "rates-trades.dataset.yaml").read_text(), "message": "keep the book column"})


@shot("14-environments-export.jpg", also=("15-environments-conflict.jpg", "16-environments-ready.jpg"))
def environments(c: Ctx):
    as_(c, "ann")
    report = STATE.get("report") or item_id(c, REPORT)
    c.page.goto(c.base + "/bi/environments")
    c.page.wait_for_selector("[data-ws-select] option", state="attached")
    c.page.select_option("[data-ws-select]", WS)
    c.page.wait_for_timeout(500)
    c.save("14-environments-export.jpg", clip={"x": 0, "y": 56, "width": 1280, "height": 744})
    with c.page.expect_download() as d:
        c.page.click("[data-export] button[type=submit]")
    path = Path(os.environ.get("DRISHTI_SHOTS_WORK") or "/tmp") / "rates.bi-bundle.json"
    d.value.save_as(path)
    bundle = json.loads(path.read_text())
    show("bundle_head", {k: bundle[k] for k in ("format", "formatVersion", "createdAt", "createdBy", "checksum")})
    show("bundle_items", [{"title": i["title"], "kind": i["kind"], "owner": i["owner"], "status": i["status"], "publishedVersion": i["publishedVersion"], "versions": [v["version"] for v in i["versions"]],
                           "endorsement": i["endorsement"]} for i in bundle["items"]])
    body = (DATA / "trades-report.report.yaml").read_text().replace("Notional by desk", "Notional per desk")
    bi(c, "PUT", f"/items/{report}", {"description": "changed after the export", "body": body, "message": "retitle"})
    as_(c, "sue")
    c.page.goto(c.base + "/bi/environments")
    c.page.set_input_files("[data-file]", str(path))
    c.page.wait_for_timeout(600)
    c.page.click("[data-dry]")
    c.page.wait_for_selector("[data-plan] tbody tr")
    c.page.wait_for_timeout(700)
    region(c, "15-environments-conflict.jpg", "[data-plan]", pad=14)
    show("import_dry_run", bi(c, "POST", "/bundle/import", {"bundle": bundle, "dryRun": True}))
    c.page.check("[data-import] [name=overwrite]")
    c.page.click("[data-dry]")
    c.page.wait_for_selector("[data-plan] tbody tr[data-action=update]")
    c.page.wait_for_timeout(600)
    region(c, "16-environments-ready.jpg", "[data-plan]", pad=14)
    path.unlink()


@shot("17-phone-history.jpg")
def phone(c: Ctx):
    as_(c, "ann")
    model = STATE.get("model") or item_id(c, MODEL)
    c.page.set_viewport_size({"width": 390, "height": 844})
    c.page.goto(c.base + "/bi/i/" + model)
    c.page.wait_for_function("() => document.querySelector('[data-meta]').textContent.includes('owner')")
    c.page.evaluate("document.querySelector('[data-sec-history]').scrollIntoView()")
    c.page.wait_for_timeout(600)
    c.save("17-phone-history.jpg")


# ---- wave B: renames, rolling an import back, lineage past the connector ------------------------------------------------------------

ORDERS = ("Orders desk", "Orders report")
ORDERS_DS = """rachana: 1
dataset: orders-desk
title: Orders desk
description: Orders by desk and book, for the rates desk
tables:
  orders:
    source: { kind: trade }
    columns:
      desk: string
      book: { path: book.name, type: string }
      notional: { path: notional, type: decimal }
measures:
  Orders: { expr: "count(orders)" }
  Gross: { expr: "sum(orders.notional)" }
"""
ORDERS_REPORT = """rachana: 1
report: orders-report
title: Orders report
dataset: {dataset}
pages:
  - id: main
    title: Main
    visuals:
      - { id: gross, type: card, title: Gross, at: { x: 0, y: 0, w: 3, h: 2 }, wells: { values: [Gross] } }
      - { id: by-book, type: bar, title: Orders by book, at: { x: 3, y: 0, w: 6, h: 4 }, wells: { axis: [orders.book], values: [Orders] } }
"""


def rates_desk(c: Ctx):
    """The workspace of this tutorial (review before publishing, sue a member), made if an earlier run of the other pictures did not."""
    as_(c, "ann")
    if not any(w["id"] == WS for w in bi(c, "GET", "/workspaces")):
        bi(c, "POST", "/workspaces", {"name": "Rates desk", "description": "Rates trading: models and reports", "reviewRequired": True})
    bi(c, "PUT", f"/workspaces/{WS}/members/sue", {"role": "member"})


def publish_as_sue(c: Ctx, item: str):
    as_(c, "ann")
    bi(c, "POST", f"/items/{item}/propose", {"note": "for review"})
    as_(c, "sue")
    bi(c, "POST", f"/items/{item}/approve", {"note": "good"})


@shot("20-rename-impact.jpg", also=("21-renamed-report.jpg",))
def rename(c: Ctx):
    people(c)
    login(c)
    for title in ORDERS:
        for it in bi(c, "GET", "/catalogue").get("items", []):
            if it["title"] == title and not it.get("shipped"):
                bi(c, "DELETE", "/items/" + it["id"])
    rates_desk(c)
    as_(c, "ann")
    audience = ["bi-steward", "bi-writer", "viewer"]
    ds = bi(c, "POST", f"/workspaces/{WS}/items", {"kind": "dataset", "title": ORDERS[0], "body": ORDERS_DS, "audienceRoles": audience})["id"]
    rep = bi(c, "POST", f"/workspaces/{WS}/items", {"kind": "report", "title": ORDERS[1], "body": ORDERS_REPORT.replace("{dataset}", ds), "audienceRoles": audience})["id"]
    STATE["orders_ds"], STATE["orders_report"] = ds, rep
    publish_as_sue(c, ds)
    publish_as_sue(c, rep)
    as_(c, "ann")
    renamed = ORDERS_DS.replace("      book: { path: book.name, type: string }", "      desk_book: { path: book.name, type: string }").replace("  Gross:", "  Notional total:")
    bi(c, "PUT", f"/items/{ds}", {"body": renamed, "message": "rename book and Gross"})
    bi(c, "POST", f"/items/{ds}/propose", {"note": "renamed two names"})
    show("rename_impact", bi(c, "GET", f"/items/{ds}/impact"))
    as_(c, "sue")
    open_item(c, ds)
    c.page.wait_for_selector("[data-review-body] [data-renamable]")
    c.page.wait_for_timeout(600)
    head = c.page.locator("[data-review-body] h3:has-text('What it does to reports')")
    head.evaluate("e => window.scrollTo(0, Math.max(0, e.getBoundingClientRect().top + window.scrollY - 70))")
    c.page.wait_for_timeout(400)
    top = head.bounding_box()
    bottom = c.page.get_by_role("button", name="Approve and update the reports").bounding_box()
    y0 = max(0, top["y"] - 12)
    c.save("20-rename-impact.jpg", clip={"x": max(0, top["x"] - 12), "y": y0, "width": 1010, "height": min(900 - y0, bottom["y"] + bottom["height"] + 12 - y0)})
    c.page.get_by_role("button", name="Approve and update the reports").click()
    c.page.wait_for_function("() => document.querySelector('[data-msg]').textContent.includes('now use the new ones')", timeout=30000)
    c.page.wait_for_timeout(800)
    show("report_after", bi(c, "GET", f"/items/{rep}")["body"])
    as_(c, "ann")
    open_item(c, rep)
    c.page.wait_for_timeout(600)
    region(c, "21-renamed-report.jpg", "[data-sec-history]")


@shot("22-imports-rollback.jpg", also=("23-rolled-back.jpg",))
def rollback(c: Ctx):
    rep = STATE.get("orders_report") or item_id(c, ORDERS[1])
    as_(c, "ann")
    bundle = bi(c, "GET", f"/bundle?items={rep}&dependencies=false")
    body = bi(c, "GET", f"/items/{rep}")["body"]
    bi(c, "PUT", f"/items/{rep}", {"body": body.replace("Orders by book", "Orders per book"), "message": "retitle a visual"})
    as_(c, "sue")
    done = bi(c, "POST", "/bundle/import", {"bundle": bundle, "dryRun": False, "overwrite": True})
    show("import", {k: done.get(k) for k in ("applied", "counts", "importId")})
    c.page.goto(c.base + "/bi/environments")
    c.page.wait_for_selector("[data-imports] tbody tr")
    c.page.wait_for_timeout(600)
    region(c, "22-imports-rollback.jpg", "[data-imports-sec]")
    show("imports", bi(c, "GET", "/imports"))
    c.page.locator(f"[data-rollback='{done['importId']}']").click()
    c.page.wait_for_function("() => document.querySelector('[data-msg]').textContent.includes('Rolled back')")
    c.page.wait_for_timeout(900)
    region(c, "23-rolled-back.jpg", "[data-imports-sec]")


@shot("24-lineage-source.jpg", also=("25-lineage-source-reader.jpg",))
def lineage_source(c: Ctx):
    login(c)
    call(c, "PUT", "/admin/connectors/api/orders-lake", {"plugin": "file", "kinds": ["trade"], "settings": {"root": "/srv/lake/orders"}})
    rates_desk(c)
    as_(c, "ann")
    for it in bi(c, "GET", "/catalogue").get("items", []):
        if it["title"] == "Lake orders" and not it.get("shipped"):
            bi(c, "DELETE", "/items/" + it["id"])
    body = ("rachana: 2\nexpressions: 2\ndataset: lake-orders\nversion: 1.0.0\ntitle: Lake orders\ndescription: Orders read from the order lake\n"
            "source: { connector: orders-lake, kind: trade }\ncolumns:\n  code: { type: string }\n")
    ds = bi(c, "POST", f"/workspaces/{WS}/items", {"kind": "dataset", "title": "Lake orders", "body": body, "audienceRoles": ["bi-steward", "bi-writer", "viewer"]})["id"]
    publish_as_sue(c, ds)
    login(c)
    open_item(c, ds)
    c.page.wait_for_selector("[data-upstream] [data-source]")
    region(c, "24-lineage-source.jpg", "[data-sec-lineage]")
    show("lineage_admin", bi(c, "GET", f"/items/{ds}/lineage")["upstream"])
    as_(c, "ann")
    open_item(c, ds)
    c.page.wait_for_selector("[data-upstream] [data-source]")
    region(c, "25-lineage-source-reader.jpg", "[data-sec-lineage]")
    show("lineage_reader", bi(c, "GET", f"/items/{ds}/lineage")["upstream"])
