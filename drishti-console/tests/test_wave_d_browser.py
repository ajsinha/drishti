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

"""Wave D in Chromium, over the real server and console (tests/dist_stack.py: security on, a masked dataset, a local fake SMTP) with the MOCK provider and two fake HTTP
servers, one for a webhook and one for a text-message gateway. Set the ports with DRISHTI_BI_REPORT_SERVER_PORT and DRISHTI_BI_REPORT_CONSOLE_PORT (18949 and 17949 here);
never run with pytest -n.

wes subscribes rita, hana and ann with a written summary, a webhook and texts; Run now: the fake SMTP holds each person's own mail (the summary on top for the two whom the
AI policy opens ``subscription-narrative`` to, none for hana, and the history says why), the webhook server holds ONE signed post that holds no figure, the text gateway
holds a text for the people who gave a number and none holds a figure. The Excel export has native charts for a person who sees every column, and none for a column that is
masked. In the designer Suggest visuals proposes checked visuals, Add puts one on the page (and Undo takes it back), and Explain in plain words explains the Check panel;
on the Model view the dataset helper's items are accepted one by one into the YAML. With DRISHTI_SHOTS set the documentation pictures are written.

Skipped when Playwright, Chromium or the built server jar is missing, or a scratch port is in use."""
from __future__ import annotations

import hashlib
import hmac
import json
import os
import re
import shutil
import subprocess
import threading
import time
import zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

os.environ.setdefault("DRISHTI_BI_REPORT_SERVER_PORT", "18949")
os.environ.setdefault("DRISHTI_BI_REPORT_CONSOLE_PORT", "17949")

import pytest  # noqa: E402

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")
from playwright.sync_api import expect, sync_playwright  # noqa: E402

import bi_report_stack  # noqa: E402
from bi_report_helpers import open_report, settled, sign_in  # noqa: E402
from bi_report_stack import CONSOLE_URL, ROOT, api  # noqa: E402
from dist_stack import REPORT, DistStack  # noqa: E402
from fake_smtp import FakeSmtp  # noqa: E402

SECRET = "wave-d-hook-secret"
SUMMARY = "Notional is spread across the three desks and looks steady today."
MOCK = """type: mock
models:
  - name: m
mock:
  rules:
    - match: "TASK: summarise\\nREPORT"
      reply: "%s"
    - match: "TASK: suggest-visuals"
      reply: |
        rachana: 1
        report: suggestions
        version: 1
        title: Suggestions
        model: trades-basic@1
        pages:
          - id: main
            title: Main
            visuals:
              - { id: by-desk, type: bar, title: Notional by desk, at: { x: 0, y: 0, w: 6, h: 4 }, wells: { axis: [trade.desk], values: [Notional] }, alt: Which desks carry the notional }
              - { id: desk-filter, type: slicer, title: Desk, at: { x: 6, y: 0, w: 3, h: 4 }, wells: { field: [trade.desk] }, alt: Filter the whole page to one desk }
              - { id: total-card, type: card, title: Total notional, at: { x: 9, y: 0, w: 3, h: 2 }, wells: { values: [Notional] }, alt: The total at a glance }
    - match: "TASK: explain-checks"
      reply: '{"items":[{"n":1,"explain":"The bar chart is asked to plot a field the dataset does not have, so it cannot draw.","fix":"Use a field of the dataset such as trade.desk."},{"n":2,"explain":"A measure named here is not in the dataset.","fix":"Pick one of Notional, MTM, Trades or Average MTM per trade."}]}'
    - match: "TASK: dataset-helper"
      reply: '{"relationships":[{"from":"trade.desk","to":"trade.book","why":"same table"}],"measures":[{"name":"Average notional","expr":"avg(trade.notional)","description":"Mean notional of one trade"},{"name":"Broken","expr":"sum(trade.nothing)","description":"x"}],"columns":[{"field":"trade.book","description":"The book the trade sits in"},{"field":"trade.currency","description":"The currency the trade is in"},{"field":"trade.ghost","description":"not a column"}]}'
""" % SUMMARY


class Receiver:
    """A fake webhook receiver and text-message gateway on one port: keeps every request (the path, the headers, the body)."""

    def __init__(self):
        self.requests: list[dict] = []
        outer = self

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                body = self.rfile.read(int(self.headers.get("Content-Length") or 0))
                outer.requests.append({"path": self.path, "headers": {k.lower(): v for k, v in self.headers.items()}, "body": body.decode()})
                self.send_response(200)
                self.send_header("Content-Length", "0")
                self.end_headers()

            def log_message(self, *a):
                pass

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.port = self.server.server_address[1]
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def on(self, path):
        return [r for r in self.requests if r["path"] == path]


@pytest.fixture(scope="module")
def smtp():
    s = FakeSmtp().start()
    yield s
    s.stop()


@pytest.fixture(scope="module")
def receiver():
    r = Receiver()
    yield r
    r.server.shutdown()


class WaveDStack(DistStack):
    def prepare(self, work):
        super().prepare(work)
        # the dataset helper works in the Model view of a version 1 dataset (a model has its designer): the 2.1 sample, kept by the server's tests
        old = bi_report_stack.ROOT / "drishti-server/src/test/resources/bi/samples-2.1/datasets/trades-basic.dataset.yaml"
        (work / "config/bi/datasets/trades-basic.dataset.yaml").write_text(old.read_text(encoding="utf-8"), encoding="utf-8")


@pytest.fixture(scope="module")
def stack(tmp_path_factory, smtp, receiver):
    for port in (bi_report_stack.SERVER_PORT, bi_report_stack.CONSOLE_PORT):
        if bi_report_stack.listening(port):
            pytest.skip(f"port {port} is in use: stop whatever listens there")
    tmp = tmp_path_factory.mktemp("waved")
    (tmp / "ai").mkdir()
    (tmp / "ai" / "mock.yaml").write_text(MOCK)
    s = WaveDStack(tmp, smtp.port)
    s.server_args += ["--drishti.ai.enabled=true", f"--drishti.ai.dir={tmp / 'ai'}", "--drishti.ai.default-model=mock/m", "--drishti.ai.watch=off",
                      "--drishti.bi.ai.values=aggregates",
                      "--drishti.ai.policy.features.[subscription-narrative].roles[0]=risk", "--drishti.ai.policy.features.[subscription-narrative].roles[1]=analyst",
                      "--drishti.notify.webhook.allowed-hosts=127.0.0.1", "--drishti.notify.webhook.allow-http=true",
                      f"--drishti.notify.webhooks.ops.url=http://127.0.0.1:{receiver.port}/hook", f"--drishti.notify.webhooks.ops.secret=${{WAVE_D_HOOK_SECRET:{SECRET}}}",
                      f"--drishti.notify.sms.url=http://127.0.0.1:{receiver.port}/sms", "--drishti.notify.sms.sender=Drishti"]
    try:
        s.start()
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


def shot(page, folder, name, **kw):
    """The documentation picture, only when DRISHTI_SHOTS is set."""
    if os.environ.get("DRISHTI_SHOTS"):
        target = ROOT / "docs" / folder
        target.mkdir(parents=True, exist_ok=True)
        page.evaluate("window.scrollTo(0, 0)")
        page.screenshot(path=str(target / name), **kw)


def test_a_subscription_with_a_summary_a_webhook_and_texts(stack, browser, smtp, receiver):
    status, _ = api("PUT", "/me/phone", "rita", ["risk"], {"phone": "+44 20 7946 0001"})
    assert status == 200
    page = sign_in(browser, "wes")
    page.goto(CONSOLE_URL + "/reports/subscribe?type=report&id=" + REPORT + "&page=overview&title=Morning+desk+pack")
    expect(page.locator("[data-narrative]")).to_be_visible()
    expect(page.locator("[data-webhooks]")).to_contain_text("ops")
    expect(page.locator("[data-sms]")).to_be_visible()
    page.fill('input[name="recipients"]', "rita, hana, ann")
    page.locator("[data-narrative]").check()
    page.locator('input[name="webhooks"][value="ops"]').check()
    page.locator('input[name="sms"]').check()
    page.fill('input[name="phone"]', "+44 20 7946 0000")
    shot(page, "guides/img/bi-dist", "subscribe-form-channels.png")
    page.click("[data-subscribe-form] button[type=submit]")
    page.wait_for_url("**/reports?msg=**")
    row = page.locator('tr[data-row-name="Morning desk pack"]')
    row.get_by_role("button", name="Run now").click()
    expect(page.locator(".adm-msg")).to_contain_text("Morning desk pack:")
    deadline = time.time() + 30
    while time.time() < deadline and not (all(smtp.to(f"{u}@desk.test") for u in ("rita", "hana", "ann")) and receiver.on("/hook") and receiver.on("/sms")):
        time.sleep(0.2)

    rita, hana, ann = [FakeSmtp.html(smtp.to(f"{u}@desk.test")[0]) for u in ("rita", "hana", "ann")]
    assert SUMMARY in rita and "written by an AI model" in rita, "the policy opens subscription-narrative to risk"
    assert SUMMARY in ann
    assert SUMMARY not in hana and "written by an AI model" not in hana, "the policy does not list desk-head: her mail goes without it"
    assert "DESK-FX" in hana and "DESK-FI" not in hana                               # still her own render
    page.reload()
    page.locator('tr[data-row-name="Morning desk pack"] summary').click()
    runs = page.locator('tr[data-row-name="Morning desk pack"] .dist-runs')
    expect(runs).to_contain_text("summary: written")
    expect(runs).to_contain_text("summary: skipped")
    expect(runs).to_contain_text("webhook:ops · sent")

    hooks = receiver.on("/hook")
    assert len(hooks) == 1, "one post for the run, not one per recipient"
    post = hooks[0]
    assert post["headers"]["x-drishti-event"] == "subscription"
    expect_sig = "sha256=" + hmac.new(SECRET.encode(), (post["headers"]["x-drishti-timestamp"] + "." + post["body"]).encode(), hashlib.sha256).hexdigest()
    assert post["headers"]["x-drishti-signature"] == expect_sig
    body = json.loads(post["body"])
    assert body["title"] == "Morning desk pack" and body["link"].startswith(CONSOLE_URL + "/bi/r/") and not re.search(r"\d{4,}", json.dumps({k: v for k, v in body.items() if k not in ("at", "link")}))
    texts = [json.loads(r["body"]) for r in receiver.on("/sms")]
    assert texts and all("is ready" in t["message"] and not re.search(r"-?\d[\d,]{4,}", t["message"].split("http")[0]) for t in texts), "a text holds a title and a link, no figure"
    assert {t["to"] for t in texts} <= {"+442079460001", "+442079460000"}
    page.context.close()


def test_the_excel_export_has_native_charts_and_a_masked_column_is_never_plotted(stack, browser, tmp_path):
    def export(user, scope=""):
        page = sign_in(browser, user)
        open_report(page, REPORT)
        settled(page)
        if scope:
            page.select_option("[data-export-scope]", scope)
        with page.expect_download() as d:
            page.click('[data-export-fmt="xlsx"]')
        path = tmp_path / f"{user}{'-' + scope if scope else ''}.xlsx"
        d.value.save_as(path)
        page.context.close()
        return path

    rita, ann = export("rita"), export("ann")
    one = export("rita", "by-desk") if os.environ.get("DRISHTI_SHOTS") else rita
    with zipfile.ZipFile(rita) as z:
        charts = [n for n in z.namelist() if n.startswith("xl/charts/chart")]
        assert charts and any(n.startswith("xl/drawings/drawing") for n in z.namelist())
        parts = {n: z.read(n).decode() for n in charts}
        assert all("<c:chartSpace" in p and "<c:ser>" in p for p in parts.values())
        wb = z.read("xl/workbook.xml").decode()
        assert "MTM by desk" in wb
    with zipfile.ZipFile(ann) as z:
        ann_charts = [n for n in z.namelist() if n.startswith("xl/charts/chart")]
    assert len(ann_charts) < len(charts), "a visual whose only measure is masked for ann has a data sheet and no chart"
    if os.environ.get("DRISHTI_SHOTS") and shutil.which("soffice") and shutil.which("pdftoppm"):
        out = tmp_path / "lo"
        out.mkdir()
        shutil.copy(one, out / "one.xlsx")
        subprocess.run(["soffice", "--headless", f"-env:UserInstallation=file://{out / 'profile'}", "--convert-to", "pdf", "--outdir", str(out), str(out / "one.xlsx")],
                       check=True, capture_output=True, timeout=180)
        subprocess.run(["pdftoppm", "-r", "70", "-png", str(out / "one.pdf"), str(out / "page")], check=True, timeout=60)
        # the first page whose picture is mostly chart: the pages after the About sheet; keep the second page of the workbook
        keep = os.environ.get("DRISHTI_SHOTS_KEEP")
        if keep:
            shutil.copytree(out, keep, dirs_exist_ok=True, ignore=shutil.ignore_patterns("profile"))
        pages = sorted(out.glob("page-*.png"), key=lambda p: int(p.stem.split("-")[1]))[1:2]       # the sheet after About: the data and its chart beside it, fitted to one page's width
        if pages:
            target = ROOT / "docs" / "guides" / "img" / "bi-dist"
            target.mkdir(parents=True, exist_ok=True)
            subprocess.run(["convert", *map(str, pages), "+append", "-trim", "-bordercolor", "white", "-border", "14", "-quality", "85", str(target / "export-excel-chart.jpg")],
                           check=True, timeout=60)


@pytest.mark.xfail(strict=True, reason='server: the AI draft and suggest endpoints resolve only a dataset (BiReportService.datasetFor), not the model a 2.2 report reads (DRS-9034 for trades-basic@1); the console sends the model')
def test_the_designer_copilot_suggests_checks_and_adds_on_accept(stack, browser):
    page = sign_in(browser, "wes", "/bi/design/" + REPORT)
    page.wait_for_selector("[data-ready=yes]", timeout=30000)
    page.click("[data-save-copy]")                                      # the sample report is shipped, read-only: work on a draft copy of it
    page.click("[data-ask-go]")                                         # the copy asks which workspace first
    page.wait_for_url(lambda u: "/bi/design/" in u and REPORT not in u, timeout=30000)
    page.wait_for_selector("[data-ready=yes]", timeout=30000)
    expect(page.locator("[data-ai-suggest]")).to_be_visible(timeout=15000)
    before = page.evaluate("window.drishtiBi.design.page().visuals.length")
    page.click("[data-ai-suggest]")
    page.fill("[data-ai-goal]", "where the notional sits, and a way to filter by desk")
    page.click("[data-ai-suggest-go]")
    cards = page.locator("[data-ai-suggestion]")
    expect(cards).to_have_count(3, timeout=30000)
    expect(cards.nth(0)).to_contain_text("Notional by desk")
    assert cards.nth(1).get_attribute("data-ai-suggestion") == "filter"
    assert "- id: by-desk" in cards.nth(0).inner_text() or "id: by-desk" in cards.nth(0).inner_text()
    page.wait_for_timeout(500)
    shot(page, "guides/img/bi-ai", "designer-suggest.jpg", type="jpeg", quality=85)
    assert page.evaluate("window.drishtiBi.design.page().visuals.length") == before, "nothing is added until Add is pressed"
    page.locator('[data-ai-add="total-card"]').click()
    assert page.evaluate("window.drishtiBi.design.page().visuals.length") == before + 1
    assert page.evaluate("window.drishtiBi.design.page().visuals.some(v => v.type === 'card' && v.title === 'Total notional')")
    page.click("[data-undo]")
    assert page.evaluate("window.drishtiBi.design.page().visuals.length") == before, "Add is one undoable edit"

    # a broken YAML is checked and explained in plain words (nothing changes); a fresh page, so the YAML tab holds what is typed
    page.reload()
    page.wait_for_selector("[data-ready=yes]", timeout=30000)
    expect(page.locator("[data-ai-suggest]")).to_be_visible(timeout=15000)
    page.evaluate("""() => { const D = window.drishtiBi.design; D.loadYaml('rachana: 1\\nreport: broken\\nversion: 1\\ntitle: Broken\\ndataset: trades-basic\\npages:\\n  - id: p\\n    title: P\\n    visuals:\\n'
      + '      - { id: v, type: bar, title: V, at: { x: 0, y: 0, w: 4, h: 3 }, wells: { axis: [trade.nope], values: [Nothing] } }\\n'); }""")
    page.wait_for_timeout(900)
    page.click("[data-check]")
    expect(page.locator("[data-check-box]")).to_be_visible()
    page.click("[data-ai-explain-checks]")
    expect(page.locator("[data-ai-checks-msg]")).not_to_have_text("Asking…", timeout=30000)
    msg = page.locator("[data-ai-checks-msg]").inner_text()
    assert "Explained by an AI model" in msg, msg
    expect(page.locator("[data-ai-checks] li").first).to_contain_text("does not have", timeout=30000)
    expect(page.locator("[data-ai-checks] li").first).to_contain_text("To fix")
    page.wait_for_timeout(400)
    shot(page, "guides/img/bi-ai", "check-explained.jpg", type="jpeg", quality=85)
    assert page.evaluate("window.__csp") == [] and page.errors == []
    page.context.close()


def test_the_dataset_helper_items_are_accepted_one_by_one_into_the_yaml(stack, browser):
    page = sign_in(browser, "wes", "/bi/m/file:dataset:trades-basic")
    page.wait_for_selector("[data-model-root][data-ready=yes]", timeout=30000)
    page.click("[data-save-copy]")                                      # the sample dataset is shipped, read-only: work on a draft copy of it
    page.wait_for_url(lambda u: "/bi/m/" in u and "trades-basic" not in u, timeout=30000)
    page.wait_for_selector("[data-model-root][data-ready=yes]", timeout=30000)
    expect(page.locator("[data-ai-helper]")).to_be_visible(timeout=15000)
    page.click("[data-ai-helper]")
    items = page.locator("[data-ai-item]")
    expect(items).to_have_count(3, timeout=30000)                      # the measure, two descriptions; the same-table relationship, the unknown column and the broken measure are dropped
    kinds = [items.nth(i).get_attribute("data-ai-item") for i in range(3)]
    assert kinds == ["measure", "column", "column"]
    yaml_before = page.evaluate("document.querySelector('.CodeMirror') ? document.querySelector('.CodeMirror').CodeMirror.getValue() : document.querySelector('[data-yaml]').value")
    assert "Average notional" not in yaml_before
    page.wait_for_timeout(400)
    shot(page, "guides/img/bi-ai", "dataset-helper.jpg", type="jpeg", quality=85)
    page.locator('[data-ai-accept="measure:Average notional"]').click()
    page.locator('[data-ai-accept="column:trade.book"]').click()
    got = page.evaluate("document.querySelector('.CodeMirror') ? document.querySelector('.CodeMirror').CodeMirror.getValue() : document.querySelector('[data-yaml]').value")
    assert 'Average notional: { expr: "avg(trade.notional)"' in got
    assert re.search(r'book:\s*\{ type: string, description: "The book the trade sits in" \}', got)
    assert "currency" in got and 'description: "The currency the trade is in"' not in got, "an item not accepted is not written"
    page.wait_for_timeout(1200)                                         # the draft is validated as it changes
    expect(page.locator("[data-valid]")).to_have_text("Valid", timeout=15000)
    assert page.errors == []
    page.context.close()
