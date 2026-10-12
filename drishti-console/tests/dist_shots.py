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

"""Makes the pictures and the captured output of docs/guides/DISTRIBUTION.md and of the export and subscription chapter of BI_TUTORIAL.md from the real stack (not a test:
run it by hand).

    DRISHTI_BI_REPORT_SERVER_PORT=18950 DRISHTI_BI_REPORT_CONSOLE_PORT=17950 \
        drishti-console/.venv/bin/python drishti-console/tests/dist_shots.py <pictures dir> <captures.json>

The stack is tests/dist_stack.py (the sample report "Trades overview", rows and a masked column per person, a local fake SMTP; no mail leaves the machine). Pictures are
desktop (1600 px: the top bar needs it) and the phone (390 px). Everything the pictures and captures show is real: the files are the server's, the mails are what the fake
SMTP received."""
from __future__ import annotations

import json
import re
import sys
import tempfile
import time
import zipfile
from pathlib import Path

from playwright.sync_api import sync_playwright

import bi_report_stack
from bi_report_helpers import open_report, settled, sign_in
from bi_report_stack import CONSOLE_URL
from dist_stack import REPORT, DistStack
from fake_smtp import FakeSmtp


def text_of(entry) -> str:
    part = entry["message"].get_body(preferencelist=("plain",))
    return part.get_content() if part else ""


def mail_picture(browser, html: str, path: Path) -> None:
    ctx = browser.new_context(viewport={"width": 900, "height": 700})
    page = ctx.new_page()
    page.set_content(html)
    page.locator("body").screenshot(path=str(path))
    ctx.close()


def main(out: Path, captures: Path) -> None:
    out.mkdir(parents=True, exist_ok=True)
    got: dict = {}
    smtp = FakeSmtp().start()
    with tempfile.TemporaryDirectory() as tmp:
        tmp = Path(tmp)
        stack = DistStack(tmp, smtp.port).start()
        try:
            with sync_playwright() as p:
                browser = p.chromium.launch()
                page = sign_in(browser, "wes")
                open_report(page, REPORT)
                settled(page)
                # the export bar on the report
                page.locator(".bi-report-bar").first.screenshot(path=str(out / "report-export-bar.png"))
                # files: Excel, PDF, PNG of the page, CSV of one visual
                files = {}
                for fmt in ("xlsx", "pdf", "png"):
                    with page.expect_download() as d:
                        page.click(f'[data-export-fmt="{fmt}"]')
                    target = tmp / d.value.suggested_filename
                    d.value.save_as(target)
                    files[fmt] = target
                    got[f"{fmt}_name"] = d.value.suggested_filename
                    got[f"{fmt}_bytes"] = target.stat().st_size
                (out / "export-page.png").write_bytes(files["png"].read_bytes())
                with zipfile.ZipFile(files["xlsx"]) as z:
                    wb = z.read("xl/workbook.xml").decode()
                    got["xlsx_sheets"] = re.findall(r'<sheet name="([^"]*)"', wb)
                    got["xlsx_first_rows"] = {}
                    for i, name in enumerate(got["xlsx_sheets"], 1):
                        xml = z.read(f"xl/worksheets/sheet{i}.xml").decode()
                        got["xlsx_first_rows"][name] = re.findall(r"<c r=\"([A-Z]+\d+)\"[^>]*?(?: s=\"(\d)\")?[^>]*>(?:<v>([^<]*)</v>|<is><t[^>]*>([^<]*)</t></is>)", xml)[:12]
                pdf = files["pdf"].read_bytes()
                got["pdf_pages"] = int(re.search(rb"/Count (\d+)", pdf).group(1))
                page.select_option("[data-export-scope]", "by-desk")
                with page.expect_download() as d:
                    page.click('[data-export-fmt="csv"]')
                target = tmp / "visual.csv"
                d.value.save_as(target)
                got["csv_visual"] = target.read_text(encoding="utf-8-sig")
                got["csv_visual_name"] = d.value.suggested_filename
                page.select_option("[data-export-scope]", "")

                # subscribe
                page.click("[data-subscribe]")
                page.wait_for_url("**/reports/subscribe**")
                page.fill('input[name="name"]', "Morning desk pack")
                page.fill('input[name="recipients"]', "rita, hana, ann")
                page.click("[data-when] summary")
                page.fill('input[name="schedule"]', "business-days 07:30")
                page.locator(".adm").first.screenshot(path=str(out / "subscribe-form.png"))
                page.click("[data-subscribe-form] button[type=submit]")
                page.wait_for_url("**/reports?msg=**")
                page.get_by_role("button", name="Run now").click()
                page.wait_for_selector(".adm-msg")
                got["run_message"] = page.inner_text(".adm-msg")
                deadline = time.time() + 20
                while time.time() < deadline and not all(smtp.to(f"{u}@desk.test") for u in ("rita", "hana", "ann")):
                    time.sleep(0.1)
                page.locator('tr[data-row-name="Morning desk pack"] summary').click()
                page.locator("#subscriptions").screenshot(path=str(out / "subscriptions-list.png"))

                # a second one that mails a PDF, so the list holds two kinds of delivery
                for who in ("rita", "hana", "ann"):
                    entry = smtp.to(f"{who}@desk.test")[0]
                    msg = entry["message"]
                    got[f"mail_{who}"] = {"subject": msg["Subject"], "to": msg["To"], "from": msg["From"], "message_id": msg["Message-ID"], "auto_submitted": msg["Auto-Submitted"],
                                          "text": text_of(entry)}
                    mail_picture(browser, FakeSmtp.html(entry), out / f"mail-{who}.png")
                smtp.messages.clear()
                page.goto(CONSOLE_URL + "/reports/subscribe?type=report&id=" + REPORT + "&page=overview&title=PDF+pack")
                page.fill('input[name="recipients"]', "rita")
                page.select_option('select[name="format"]', "pdf")
                page.click("[data-when] summary")
                page.fill('input[name="schedule"]', "monthly last 18:00 business")
                page.click("[data-subscribe-form] button[type=submit]")
                page.wait_for_url("**/reports?msg=**")
                page.locator('tr[data-row-name="PDF pack"]').get_by_role("button", name="Run now").click()
                page.wait_for_selector(".adm-msg")
                deadline = time.time() + 20
                while time.time() < deadline and not smtp.to("rita@desk.test"):
                    time.sleep(0.1)
                entry = smtp.to("rita@desk.test")[0]
                got["pdf_mail"] = {"subject": entry["message"]["Subject"], "attachments": [(n, len(b)) for n, b in FakeSmtp.attachments(entry)], "text": text_of(entry)}

                # the Sutra view: the Export menu
                page.goto(CONSOLE_URL + "/v/trade/BBG-1008000")
                page.wait_for_selector("[data-export-menu]", timeout=30000)
                page.click("[data-export-menu] button")
                bb = page.locator(".vhead").first.bounding_box()
                page.screenshot(path=str(out / "view-export-menu.png"), clip={"x": 0, "y": max(0, bb["y"] - 4), "width": 1600, "height": bb["height"] + 190})
                for fmt in ("xlsx", "pdf"):
                    if fmt == "pdf":
                        page.click("[data-export-menu] button")
                    with page.expect_download() as d:
                        page.click(f'[data-export-fmt="{fmt}"]')
                    target = tmp / d.value.suggested_filename
                    d.value.save_as(target)
                    got[f"view_{fmt}_name"] = d.value.suggested_filename
                    got[f"view_{fmt}_bytes"] = target.stat().st_size
                    if fmt == "xlsx":
                        with zipfile.ZipFile(target) as z:
                            got["view_xlsx_sheets"] = re.findall(r'<sheet name="([^"]*)"', z.read("xl/workbook.xml").decode())
                page.context.close()

                # a phone
                phone = sign_in(browser, "wes", next_path="/reports", viewport={"width": 390, "height": 800})
                phone.goto(CONSOLE_URL + "/reports")
                phone.screenshot(path=str(out / "list-phone-390.png"), full_page=True)
                phone.context.close()
                browser.close()
        finally:
            stack.close()
            smtp.stop()
    captures.write_text(json.dumps(got, indent=2, default=str))
    print("pictures:", sorted(p.name for p in out.iterdir()))


if __name__ == "__main__":
    assert bi_report_stack.SERVER_PORT == 18950 and bi_report_stack.CONSOLE_PORT == 17950, "run with DRISHTI_BI_REPORT_SERVER_PORT=18950 DRISHTI_BI_REPORT_CONSOLE_PORT=17950"
    main(Path(sys.argv[1]), Path(sys.argv[2]))
