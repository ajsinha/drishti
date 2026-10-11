"""Makes the pictures and the captured output of the alerts section of docs/guides/DISTRIBUTION.md, the BI tutorial chapter on alerts, BOARDS.md and the API guide from the
real stack (not a test: run it by hand).

    DRISHTI_BI_REPORT_SERVER_PORT=18951 DRISHTI_BI_REPORT_CONSOLE_PORT=17951 \
        drishti-console/.venv/bin/python drishti-console/tests/alerts_shots.py <pictures dir> <captures.json>

The stack is tests/alerts_stack.py (the sample report "Trades overview", rows and a masked column per person, a board with one tile that notifies, a local fake SMTP; no
mail leaves the machine). Pictures are desktop (1600 px: the top bar needs it) and the phone (390 px). Everything shown is real: the alert, its history and the inbox rows
are the server's, the mails are what the fake SMTP received."""
from __future__ import annotations

import json
import sys
import tempfile
import time
from pathlib import Path

from playwright.sync_api import sync_playwright

import bi_report_stack
from alerts_stack import REPORT, AlertsStack
from bi_report_helpers import open_report, settled, sign_in
from bi_report_stack import CONSOLE_URL
from fake_smtp import FakeSmtp


def mail_picture(browser, html: str, path: Path) -> None:
    ctx = browser.new_context(viewport={"width": 900, "height": 500})
    page = ctx.new_page()
    page.set_content(html)
    page.locator("body").screenshot(path=str(path))
    ctx.close()


def text_of(entry) -> str:
    part = entry["message"].get_body(preferencelist=("plain",))
    return part.get_content() if part else ""


def main(out: Path, captures: Path) -> None:
    out.mkdir(parents=True, exist_ok=True)
    got: dict = {}
    smtp = FakeSmtp().start()
    with tempfile.TemporaryDirectory() as tmp:
        stack = AlertsStack(Path(tmp), smtp.port).start()
        try:
            with sync_playwright() as p:
                browser = p.chromium.launch()
                page = sign_in(browser, "rita", viewport={"width": 1600, "height": 1500})
                open_report(page, REPORT)
                settled(page)
                page.locator('[data-visual="mtm"]').screenshot(path=str(out / "report-alert-me.png"))

                # the MTM floor, from the card
                page.locator('[data-visual="mtm"] [data-act="alert-me"]').click()
                page.wait_for_url("**/alerts/new**")
                page.fill('input[name="name"]', "MTM floor")
                page.select_option('select[name="op"]', "below")
                page.fill('input[name="value"]', "-300000")
                page.fill('input[name="hysteresis"]', "25000")
                page.select_option('select[name="trigger_mode"]', "after-load")
                page.check('input[name="d_email"]')
                page.click("[data-check-now]")
                page.wait_for_function("() => document.querySelector('[data-check-out]').textContent.includes('Right now')")
                got["check_now_text"] = page.inner_text("[data-check-out]")
                page.locator(".adm").first.screenshot(path=str(out / "alert-form.png"))
                page.click("[data-alert-form] button[type=submit]")
                page.wait_for_url("**/alerts?msg=**")

                # a change alert on a dataset measure
                page.goto(CONSOLE_URL + "/alerts/new?source=dataset&dataset=file:dataset:trades-basic&measure=Trades&title=Trade+count")
                page.fill('input[name="name"]', "Trade count moves")
                page.select_option('select[name="condition_type"]', "change")
                page.select_option('select[name="change_op"]', "moves")
                page.fill('input[name="change_value"]', "10")
                page.select_option('select[name="change_mode"]', "pct")
                page.select_option('select[name="against"]', "previous-run")
                page.select_option('select[name="trigger_mode"]', "after-load")
                page.click("[data-alert-form] button[type=submit]")
                page.wait_for_url("**/alerts?msg=**")

                # a field of a Sutra entity, from the number on a view
                page.goto(CONSOLE_URL + "/v/trade/BBG-1008000")
                page.wait_for_selector('dd[data-path="$.mtm"]', timeout=30000)
                page.locator('dd[data-path="$.mtm"]').click()
                page.wait_for_selector("dialog[open] [data-alert-me]")
                page.locator("dialog[open]").screenshot(path=str(out / "view-alert-me.png"))
                page.click("dialog[open] [data-alert-me]")
                page.wait_for_url("**/alerts/new?source=field**")
                got["field_form_url"] = page.url.replace(CONSOLE_URL, "")
                page.fill('input[name="name"]', "MTM of BBG-1008000")
                page.select_option('select[name="op"]', "below")
                page.fill('input[name="value"]', "-500000")
                page.click("[data-alert-form] button[type=submit]")
                page.wait_for_url("**/alerts?msg=**")
                page.locator("[data-measure-alerts]").screenshot(path=str(out / "alerts-armed.png"))

                # the ETL adds losing trades and announces the load
                stack.add_trades()
                time.sleep(2.5)
                got["load_announce"] = stack.announce()[1]
                deadline = time.time() + 40
                while time.time() < deadline:
                    page.goto(CONSOLE_URL + "/inbox?tab=alert")
                    if page.locator(".inbox-row").count() >= 2:
                        break
                    time.sleep(1)
                page.locator(".inbox").screenshot(path=str(out / "inbox-alerts.png"))
                got["inbox_rows"] = [r.inner_text().replace("\n", " ").strip() for r in page.locator(".inbox-row").all()]
                page.goto(CONSOLE_URL + "/alerts")
                page.locator("[data-measure-alerts]").screenshot(path=str(out / "alerts-active.png"))
                page.goto(CONSOLE_URL + "/alerts/measure/MTM%20floor")
                page.locator("[data-history]").screenshot(path=str(out / "alert-history.png"))

                # the server's own words
                roles = ["risk"]
                got["api_alert"] = bi_report_stack.api("GET", "/me/measure-alerts/MTM%20floor", "rita", roles)[1]
                got["api_inbox"] = bi_report_stack.api("GET", "/me/inbox?type=alert", "rita", roles)[1]
                got["api_preview"] = bi_report_stack.api("POST", "/me/measure-alerts/preview", "rita", roles, {
                    "source": {"type": "visual", "report": REPORT, "page": "overview", "visual": "mtm"}, "condition": {"type": "threshold", "op": "below", "value": -300000},
                    "trigger": {"mode": "after-load"}})[1]
                got["api_refused"] = bi_report_stack.api("PUT", "/me/measure-alerts/Bad", "rita", roles, {
                    "source": {"type": "visual", "report": REPORT, "visual": "mtm"}, "condition": {"type": "threshold", "op": "over", "value": 1}, "trigger": {"mode": "schedule"}})
                got["api_boards"] = bi_report_stack.admin("GET", "/admin/measure-alerts/boards")[1]

                # mails: the alert, and the board tile, each with the figure the person may see
                deadline = time.time() + 20
                while time.time() < deadline and len(smtp.to("rita@desk.test")) < 2:
                    time.sleep(0.2)
                mails = []
                for entry in smtp.to("rita@desk.test"):
                    mails.append({"subject": entry["message"]["Subject"], "to": entry["message"]["To"], "text": text_of(entry)})
                got["mails"] = mails
                alert_mail = next((m for m in smtp.to("rita@desk.test") if "MTM floor" in (m["message"]["Subject"] or "")), None)
                if alert_mail:
                    mail_picture(browser, FakeSmtp.html(alert_mail), out / "alert-mail.png")
                page.context.close()

                # ann: her MTM is masked, so the same alert never judges it and nothing is sent
                ann = sign_in(browser, "ann", viewport={"width": 1600, "height": 1500})
                open_report(ann, REPORT)
                settled(ann)
                ann.locator('[data-visual="mtm"] [data-act="alert-me"]').click()
                ann.wait_for_url("**/alerts/new**")
                ann.fill('input[name="name"]', "MTM floor")
                ann.select_option('select[name="op"]', "below")
                ann.fill('input[name="value"]', "-300000")
                ann.select_option('select[name="trigger_mode"]', "after-load")
                ann.click("[data-check-now]")
                ann.wait_for_function("() => document.querySelector('[data-check-out]').textContent.length > 12")
                got["masked_check_text"] = ann.inner_text("[data-check-out]")
                ann.locator("[data-alert-form] .al-actions").locator("xpath=..").screenshot(path=str(out / "alert-masked.png"))
                ann.click("[data-alert-form] button[type=submit]")
                ann.wait_for_url("**/alerts?msg=**")
                time.sleep(2)
                ann.goto(CONSOLE_URL + "/alerts/measure/MTM%20floor")
                ann.locator("[data-history]").screenshot(path=str(out / "alert-history-masked.png"))
                got["masked_history"] = bi_report_stack.api("GET", "/me/measure-alerts/MTM%20floor", "ann", ["analyst"])[1].get("history")
                ann.context.close()

                # a phone
                phone = sign_in(browser, "rita", next_path="/alerts", viewport={"width": 390, "height": 800})
                phone.goto(CONSOLE_URL + "/alerts")
                phone.screenshot(path=str(out / "alerts-phone-390.png"), full_page=True)
                phone.goto(CONSOLE_URL + "/alerts/new?source=field&kind=trade&id=BBG-1008000&field=%24.mtm")
                phone.screenshot(path=str(out / "alert-form-phone-390.png"), full_page=True)
                phone.context.close()

                # an administrator: everyone's alerts and the board tiles that notify
                try:
                    admin = sign_in(browser, "drishti-dev-admin", next_path="/alerts/all") if False else None
                except Exception:  # noqa: BLE001
                    admin = None
                if admin is None:
                    ctx = browser.new_context(viewport={"width": 1600, "height": 900})
                    admin = ctx.new_page()
                    admin.goto(CONSOLE_URL + "/login?next=/alerts/all")
                    admin.fill("#lu", "drishti-dev-admin")
                    admin.fill("#lp", "drishti-dev-admin123")
                    admin.click("button[type=submit]")
                    try:
                        admin.wait_for_url("**/alerts/all", timeout=8000)
                        admin.locator(".adm").first.screenshot(path=str(out / "alerts-all.png"))
                    except Exception:  # noqa: BLE001
                        got["admin_picture"] = "skipped: the dev admin must change its password first"
                    ctx.close()
                browser.close()
        finally:
            stack.close()
            smtp.stop()
    captures.write_text(json.dumps(got, indent=2, default=str))
    print("pictures:", sorted(p.name for p in out.iterdir()))


if __name__ == "__main__":
    assert bi_report_stack.SERVER_PORT == 18951 and bi_report_stack.CONSOLE_PORT == 17951, "run with DRISHTI_BI_REPORT_SERVER_PORT=18951 DRISHTI_BI_REPORT_CONSOLE_PORT=17951"
    main(Path(sys.argv[1]), Path(sys.argv[2]))
