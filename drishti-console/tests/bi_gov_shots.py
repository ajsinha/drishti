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

"""Makes the pictures and the captured output of docs/guides/BI_GUIDE.md, BI_GUIDE.md and BI_TUTORIAL.md (chapter 17) from the real stack (not a test: run it by hand).

    drishti-console/.venv/bin/python drishti-console/tests/bi_gov_shots.py <pictures dir> <captures.json>

Ports 18992 (server) and 17992 (console), a scratch directory, a 120-trade file lake. Pictures are desktop (1600 px: the top bar needs it) and the phone (390 px)."""
import json
import re
import sys
import tempfile
from pathlib import Path

from playwright.sync_api import sync_playwright

import bi_gov_stack
from bi_gov_stack import CONSOLE_URL, PASSWORD, call


def sign_in(browser, user, **ctx):
    context = browser.new_context(**{"viewport": {"width": 1600, "height": 1000}, "accept_downloads": True, **ctx})
    page = context.new_page()
    page.goto(CONSOLE_URL + "/login?next=/bi")
    page.fill("#lu", user)
    page.fill("#lp", PASSWORD)
    page.click("button[type=submit]")
    page.wait_for_url("**/bi")
    return page


def open_item(page, item_id):
    page.goto(CONSOLE_URL + "/bi/i/" + item_id)
    page.wait_for_function("() => document.querySelector('[data-meta]').textContent.includes('owner')")
    page.wait_for_timeout(900)


def edit_body(page, change, message):
    page.get_by_role("button", name="Edit", exact=True).click()
    box = page.locator("[data-edit] textarea[name=body]")
    box.fill(change(box.input_value()))
    page.fill("[data-edit] input[name=message]", message)
    page.click("[data-edit] button[type=submit]")
    page.wait_for_function("() => document.querySelector('[data-msg]').textContent.includes('Draft saved')")
    page.wait_for_timeout(700)


def shot(page, selector, path):
    """A region of the whole page at its natural layout (the top bar is sticky: an element screenshot would show it over the region)."""
    page.evaluate("window.scrollTo(0, 0)")
    bb = page.locator(selector).first.bounding_box()
    page.screenshot(path=str(path), full_page=True, clip={"x": bb["x"], "y": bb["y"], "width": bb["width"], "height": bb["height"]})


def main(out: Path, captures: Path) -> None:
    out.mkdir(parents=True, exist_ok=True)
    got = {}
    ann_roles, sue_roles, root_roles = ["bi-writer"], ["bi-steward"], ["admin"]
    with tempfile.TemporaryDirectory() as tmp:
        stack = bi_gov_stack.Stack(Path(tmp)).start()
        ids = stack.ids
        try:
            with sync_playwright() as p:
                browser = p.chromium.launch()

                # ---- versions: two saves, the diff, a restore ------------------------------------------------------------------------
                ann = sign_in(browser, "ann")
                open_item(ann, ids["positions"])
                edit_body(ann, lambda t: t.replace("      book: string\n", "      book: string\n      currency: string\n"), "add the currency column")
                edit_body(ann, lambda t: t.replace("  Trades: { expr", "  Deals: { expr"), "rename the count measure")
                ann.select_option("[data-diff-from]", "r1")
                ann.select_option("[data-diff-to]", "draft")
                ann.click("[data-diff-form] button[type=submit]")
                ann.wait_for_selector("[data-diff-out] table.bi-diff")
                ann.wait_for_timeout(400)
                shot(ann, "[data-sec-history]", out / "history-diff.png")
                got["history"] = call("GET", f"/bi/items/{ids['positions']}/history", "ann", ann_roles)[1]
                got["diff"] = call("GET", f"/bi/items/{ids['positions']}/diff?from=r1&to=draft", "ann", ann_roles)[1]
                ann.locator('[data-history] tr[data-seq="2"] button:has-text("Restore as draft")').click()
                ann.wait_for_function("() => document.querySelector('[data-msg]').textContent.includes('is the draft again')")
                ann.wait_for_timeout(600)
                got["history_after_restore"] = call("GET", f"/bi/items/{ids['positions']}/history", "ann", ann_roles)[1]

                # ---- review ----------------------------------------------------------------------------------------------------------
                ann.fill(".bi-note", "ready for a look")
                ann.get_by_role("button", name="Propose for review").click()
                ann.wait_for_function("() => document.querySelector('[data-msg]').textContent.includes('Proposed')")
                sue = sign_in(browser, "sue")
                open_item(sue, ids["positions"])
                sue.wait_for_selector("[data-review-body] table.bi-grid")
                sue.wait_for_timeout(500)
                shot(sue, "[data-sec-review]", out / "review.png")
                got["review"] = call("GET", f"/bi/items/{ids['positions']}/review", "sue", sue_roles)[1]
                sue.fill(".bi-note", "looks right")
                sue.get_by_role("button", name="Approve and publish").click()
                sue.wait_for_function("() => document.querySelector('[data-msg]').textContent.includes('Approved')")
                sue.wait_for_timeout(500)
                got["inbox_ann"] = call("GET", "/me/inbox?limit=5", "ann", ann_roles)[1]
                got["inbox_sue_before"] = None

                # ---- the catalogue shows who is certified -----------------------------------------------------------------------------
                cat = sign_in(browser, "cat")
                cat.goto(CONSOLE_URL + "/bi/catalogue")
                cat.wait_for_selector(".bi-card")
                cat.wait_for_timeout(600)
                shot(cat, ".bi-canvas", out / "catalogue-certified.png")

                # ---- lineage ----------------------------------------------------------------------------------------------------------
                open_item(ann, ids["model"])
                shot(ann, "[data-sec-lineage]", out / "lineage.png")
                got["lineage_dataset"] = call("GET", f"/bi/items/{ids['model']}/lineage", "ann", ann_roles)[1]
                got["lineage_report"] = call("GET", f"/bi/items/{ids['report']}/lineage", "ann", ann_roles)[1]
                got["impact_hypothetical"] = call("GET", f"/bi/items/{ids['model']}/impact?remove=trade.book", "ann", ann_roles)[1]

                # ---- impact: a refused publish -----------------------------------------------------------------------------------------
                edit_body(ann, lambda t: re.sub(r"^      book: .*\n", "", t, flags=re.M).replace("Desk and book: [trade.desk, trade.book]", "Desk: [trade.desk]"), "drop the book column")
                got["impact_draft"] = call("GET", f"/bi/items/{ids['model']}/impact", "ann", ann_roles)[1]
                ann.get_by_role("button", name="Publish", exact=True).click()
                ann.wait_for_selector("[data-force]:not([hidden])")
                ann.wait_for_timeout(600)
                shot(ann, ".bi-canvas", out / "impact-refused.png")
                got["refusal"] = call("POST", f"/bi/items/{ids['model']}/publish", "ann", ann_roles, {"note": "drop book"})[1]
                got["refusal_force_by_author"] = call("POST", f"/bi/items/{ids['model']}/publish", "ann", ann_roles, {"note": "drop book", "force": True})[1]

                # ---- the admin forces it; certification falls ----------------------------------------------------------------------------
                root = sign_in(browser, "root")
                open_item(root, ids["model"])
                root.get_by_role("button", name="Publish", exact=True).click()
                root.wait_for_selector("[data-force] button")
                root.wait_for_timeout(400)
                shot(root, ".bi-canvas", out / "force-offer.png")
                root.get_by_role("button", name="Publish anyway (bi-admin)").click()
                root.wait_for_selector("[data-warnings]:not([hidden])")
                root.wait_for_timeout(700)
                shot(root, ".bi-canvas", out / "forced-warnings.png")
                got["inbox_sue"] = call("GET", "/me/inbox?limit=5", "sue", sue_roles)[1]
                got["history_model"] = call("GET", f"/bi/items/{ids['model']}/history", "ann", ann_roles)[1]["events"]

                # ---- environments --------------------------------------------------------------------------------------------------------
                ann.goto(CONSOLE_URL + "/bi/environments")
                ann.wait_for_selector("[data-ws-select] option", state="attached")
                ann.select_option("[data-ws-select]", "rates-desk")
                with ann.expect_download() as d:
                    ann.click("[data-export] button[type=submit]")
                bundle_path = Path(tmp) / "rates.bi-bundle.json"
                d.value.save_as(bundle_path)
                bundle = json.loads(bundle_path.read_text())
                got["bundle_head"] = {k: bundle[k] for k in ("format", "formatVersion", "createdAt", "createdBy", "checksum")}
                got["bundle_items"] = [{"id": i["id"], "kind": i["kind"], "title": i["title"], "owner": i["owner"], "workspace": i["workspace"], "sample": i["sample"],
                                        "endorsement": i["endorsement"], "status": i["status"], "publishedVersion": i["publishedVersion"], "versions": [v["version"] for v in i["versions"]],
                                        "sha256": i["sha256"][:16] + "..."} for i in bundle["items"]]
                call("PUT", f"/bi/items/{ids['report']}", "ann", ann_roles, {"description": "changed after the export", "body": bi_gov_stack.REPORT.replace("Notional by desk", "Notional per desk"), "message": "retitle"})
                admin = sign_in(browser, "root")
                admin.goto(CONSOLE_URL + "/bi/environments")
                admin.set_input_files("[data-file]", str(bundle_path))
                admin.wait_for_timeout(500)
                admin.click("[data-dry]")
                admin.wait_for_selector("[data-plan] tbody tr")
                admin.wait_for_timeout(600)
                shot(admin, ".bi-canvas", out / "environments-conflict.png")
                got["import_dry_run"] = call("POST", "/bi/bundle/import", "root", root_roles, {"bundle": bundle, "dryRun": True})[1]
                admin.check("[data-import] [name=overwrite]")
                admin.click("[data-dry]")
                admin.wait_for_selector("[data-plan] tbody tr[data-action=update]")
                admin.wait_for_timeout(500)
                shot(admin, ".bi-canvas", out / "environments-ready.png")

                # ---- the phone --------------------------------------------------------------------------------------------------------------
                ph = sign_in(browser, "ann", viewport={"width": 390, "height": 844}, has_touch=True, is_mobile=True, device_scale_factor=2)
                open_item(ph, ids["positions"])
                ph.evaluate("document.querySelector('[data-sec-history]').scrollIntoView()")
                ph.wait_for_timeout(400)
                ph.screenshot(path=str(out / "phone-history.png"))
                browser.close()
        finally:
            stack.close()
    captures.write_text(json.dumps(got, indent=2, default=str))
    print("pictures:", sorted(f.name for f in out.glob("*.png")))


if __name__ == "__main__":
    main(Path(sys.argv[1]), Path(sys.argv[2]))
