#!/usr/bin/env python3
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

"""Regenerates the screenshots of the screen designer guide (docs/guides/SCREEN_DESIGNER.md) into docs/guides/img/designer/.

Playwright drives the real console over a real server: a SCRATCH server on :18997 and console on :17997 with the QUICKSTART packs. It never
touches the usual :18480 / :17480 (it refuses them). If nothing answers on the scratch ports the script starts both, in a scratch directory,
and stops what it started; if something does, it uses it.

    console/.venv/bin/python tools/docs/screenshots.py            # all of them
    console/.venv/bin/python tools/docs/screenshots.py --only 07  # the ones whose file name contains "07"
    console/.venv/bin/python tools/docs/screenshots.py --list

Every picture is a step of the guide; when the workbench's look changes, run this and commit the new files. A test
(console/tests/test_designer_guide.py) fails if the guide shows a picture that is not there."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "docs" / "guides" / "img" / "designer"
EXAMPLES = ROOT / "docs" / "guides" / "examples"
PACKS = "market-risk,counterparty-risk,liquidity-risk,climate-risk,operational-risk,retail-banking,genomics,politics-society,economics"
SERVER_PORT, CONSOLE_PORT = 18997, 17997
FORBIDDEN = {18480, 17480}
SHOTS: list = []


def shot(name: str):
    def reg(fn):
        SHOTS.append((name, fn))
        return fn
    return reg


# ---- helpers ---------------------------------------------------------------------------------------------------------------------------

class Ctx:
    def __init__(self, page, base):
        self.page, self.base = page, base
        self.showcase_open = False          # the first pictures build on one open copy of the showcase
        self.made = []

    def api(self, path, body):
        r = self.page.request.post(self.base + path, data=json.dumps(body), headers={"Content-Type": "application/json"})
        return r.json()

    def design(self, name, files=None, sutra=None, kind="trade"):
        d = self.api("/build/designs", {"name": name, "kind": kind, **({"sutra": sutra} if sutra else {"empty": True})})
        if files:
            self.api(f"/build/designs/{d['id']}/files", {"files": [{"name": n, "text": t} for n, t in files.items()]})
        return d["id"]

    def example(self, name):
        """The example opened as a design copy, as the New page does."""
        self.page.goto(self.base + "/build/new#examples")
        self.page.locator(f'[data-example="{name}"]').click()
        self.page.wait_for_url("**/build/d/*")
        self.showcase_open = name == "all-panels-showcase"
        self.ready()

    def open(self, id_):
        self.page.goto(f"{self.base}/build/d/{id_}")
        self.showcase_open = False
        self.ready()

    def forget(self):
        """Deletes the designs the pictures made, so repeated runs do not fill the 50 a person may keep."""
        for id_ in self.made:
            self.page.request.delete(f"{self.base}/build/designs/{id_}")

    def ready(self):
        m = self.page.url.rsplit("/build/d/", 1)
        if len(m) == 2 and m[1] not in self.made:
            self.made.append(m[1].split("?")[0].split("#")[0])
        self.page.locator("[data-preview] [data-panel]").first.wait_for(timeout=30000)
        self.page.wait_for_timeout(1800)                       # charts draw, the first check runs

    def save(self, name, locator=None, clip=None):
        OUT.mkdir(parents=True, exist_ok=True)
        path = OUT / name
        if locator is not None:
            self.page.locator(locator).first.screenshot(path=str(path), type="jpeg", quality=84)
        else:
            self.page.screenshot(path=str(path), clip=clip, type="jpeg", quality=84)
        print("  wrote", path.relative_to(ROOT))

    def rev(self):
        return self.page.evaluate("window.drishtiWorkbench.store.state.rev")

    def settle(self, rev):
        for _ in range(150):
            if self.page.evaluate("window.drishtiWorkbench.store.state.rev") > rev:
                break
            self.page.wait_for_timeout(100)
        self.page.wait_for_timeout(500)

    def panel(self, id_):
        return self.page.locator(f'[data-preview] [data-panel="{id_}"]')

    def box(self, locator):
        return locator.bounding_box()


def showcase():
    return (EXAMPLES / "all-panels-showcase.json").read_text(), (EXAMPLES / "all-panels-showcase.sutra.yaml").read_text()


# ---- the pictures -----------------------------------------------------------------------------------------------------------------------

@shot("01-new-flow.jpg")
def new_flow(c: Ctx):
    c.page.goto(c.base + "/build/new")
    c.page.wait_for_timeout(800)
    c.save("01-new-flow.jpg")


@shot("02-workbench.jpg")
def workbench(c: Ctx):
    c.example("all-panels-showcase")
    c.save("02-workbench.jpg")


@shot("03-data-pane.jpg")
def data_pane(c: Ctx):
    c.page.locator(".wb-field-row", has_text="productName").first.click()
    c.page.wait_for_timeout(300)
    c.save("03-data-pane.jpg", ".wb-left")


@shot("04-palette.jpg")
def palette(c: Ctx):
    c.page.locator('[data-palette] [data-kind="scatter"]').scroll_into_view_if_needed()
    c.save("04-palette.jpg", "[data-palette-box]")


@shot("05-select-panel.jpg")
def select_panel(c: Ctx):
    c.panel("terms").locator(".pnl-h").click()
    c.page.wait_for_timeout(300)
    c.panel("terms").hover()
    c.save("05-select-panel.jpg")


@shot("06-drop-panel.jpg")
def drop_panel(c: Ctx):
    src = c.page.locator('[data-palette] [data-kind="gauge"]')
    src.scroll_into_view_if_needed()
    s = c.box(src)
    t = c.box(c.panel("ops"))
    c.page.mouse.move(s["x"] + 30, s["y"] + 10)
    c.page.mouse.down()
    c.page.mouse.move(s["x"] + 70, s["y"] + 40, steps=3)
    c.page.mouse.move(t["x"] + 14, t["y"] + 90, steps=10)             # the left half of the status panel: before it, beside the key/value panel
    c.page.wait_for_timeout(200)
    c.save("06-drop-panel.jpg")
    rev = c.rev()
    c.page.mouse.up()
    c.settle(rev)
    c.save("06b-panel-added.jpg")


@shot("07-drop-field.jpg")
def drop_field(c: Ctx):
    f = c.page.locator(".wb-field-row", has_text="productName").first
    f.scroll_into_view_if_needed()
    s = c.box(f)
    t = c.box(c.panel("terms"))
    c.page.mouse.move(s["x"] + 30, s["y"] + 5)
    c.page.mouse.down()
    c.page.mouse.move(s["x"] + 80, s["y"] + 30, steps=3)
    c.page.mouse.move(t["x"] + t["width"] / 2, t["y"] + t["height"] - 6, steps=10)
    c.page.mouse.up()
    c.page.locator(".wb-menu").wait_for()
    c.page.wait_for_timeout(300)
    c.save("07-drop-field.jpg")
    c.page.keyboard.press("Escape")


@shot("08-bind-field.jpg")
def bind_field(c: Ctx):
    f = c.page.locator(".wb-field-row", has_text="currency").first
    f.scroll_into_view_if_needed()
    s = c.box(f)
    t = c.box(c.panel("terms"))
    c.page.mouse.move(s["x"] + 30, s["y"] + 5)
    c.page.mouse.down()
    c.page.mouse.move(s["x"] + 80, s["y"] + 30, steps=3)
    c.page.mouse.move(t["x"] + t["width"] / 2, t["y"] + t["height"] / 2, steps=10)
    c.page.wait_for_timeout(200)
    c.save("08-bind-field.jpg")
    c.page.keyboard.press("Escape")
    c.page.mouse.up()
    c.page.wait_for_timeout(500)


@shot("09-resize.jpg")
def resize(c: Ctx):
    p = c.panel("terms")
    p.hover()
    e = c.box(p.locator(".lh-e"))
    c.page.mouse.move(e["x"] + 5, e["y"] + 30)
    c.page.mouse.down()
    c.page.mouse.move(e["x"] - 70, e["y"] + 30, steps=6)
    c.page.wait_for_timeout(200)
    c.save("09-resize.jpg")
    c.page.keyboard.press("Escape")
    c.page.mouse.up()
    c.page.wait_for_timeout(500)


@shot("10-inspector.jpg")
def inspector(c: Ctx):
    c.panel("coupons").locator(".pnl-h").click()
    c.page.wait_for_timeout(400)
    c.save("10-inspector.jpg", ".wb-right")


@shot("11-expression.jpg")
def expression(c: Ctx):
    rows = c.page.get_by_label("rows *")
    rows.fill("$.")
    c.page.wait_for_timeout(300)
    c.save("11-expression.jpg", ".wb-right")
    rows.fill("$.schedule")
    rows.blur()
    c.page.wait_for_timeout(500)


@shot("12-yaml-tab.jpg")
def yaml_tab(c: Ctx):
    c.page.get_by_role("tab", name="YAML").click()
    c.page.wait_for_timeout(500)
    c.page.evaluate("document.querySelector('.CodeMirror').CodeMirror.scrollTo(0, 560)")
    c.save("12-yaml-tab.jpg")


@shot("13-split.jpg")
def split(c: Ctx):
    c.page.locator("[data-split]").click()
    c.page.wait_for_timeout(700)
    c.save("13-split.jpg")
    c.page.locator("[data-split]").click()
    c.page.get_by_role("tab", name="Design").click()


@shot("14-problems.jpg")
def problems(c: Ctx):
    c.page.get_by_role("tab", name="YAML").click()
    c.page.evaluate("document.querySelector('.CodeMirror').CodeMirror.replaceRange('  - id: oops\\n    kind: nonsense\\n', {line: 40, ch: 0})")
    c.page.get_by_role("tab", name="Problems").click()
    c.page.locator(".wb-problem-b", has_text="nonsense").first.wait_for(timeout=20000)
    c.save("14-problems.jpg")


@shot("15-tests.jpg")
def tests(c: Ctx):
    json_text, sutra = showcase()
    doc = json.loads(json_text)
    thin = dict(doc)
    for k in ("legs", "schedule", "pnlHistory"):
        thin.pop(k, None)
    thin["tradeId"] = "DEMO-BOND-THIN"
    other = dict(doc)
    other["tradeId"] = "DEMO-BOND-2"
    c.open(c.design("Screenshots: tests", files={"full.json": json_text, "thin.json": json.dumps(thin), "second.json": json.dumps(other)}, sutra=sutra))
    c.page.get_by_role("tab", name="Tests").click()
    c.page.locator(".wb-matrix").wait_for(timeout=30000)
    c.page.wait_for_timeout(600)
    c.save("15-tests.jpg")


@shot("16-preview-file.jpg")
def preview_file(c: Ctx):
    json_text, sutra = showcase()
    doc = json.loads(json_text)
    doc["tradeId"] = "TRY-IT-ON-THE-SPOT"
    doc["productName"] = "A file that is not in the design"
    tmp = Path(tempfile.mkdtemp()) / "try-this.json"
    tmp.write_text(json.dumps(doc))
    c.open(c.design("Screenshots: preview", files={"full.json": json_text}, sutra=sutra))
    c.page.locator("[data-preview-file]").set_input_files(str(tmp))
    c.page.get_by_text("TRY-IT-ON-THE-SPOT").first.wait_for(timeout=20000)
    c.page.wait_for_timeout(600)
    c.save("16-preview-file.jpg")


@shot("17-keyboard-add.jpg")
def keyboard_add(c: Ctx):
    json_text, sutra = showcase()
    c.open(c.design("Screenshots: keyboard", files={"full.json": json_text}, sutra=sutra))
    c.panel("terms").focus()
    c.page.keyboard.press("n")
    c.page.locator(".wb-menu input").wait_for()
    c.page.keyboard.type("g")
    c.page.wait_for_timeout(300)
    c.save("17-keyboard-add.jpg")
    c.page.keyboard.press("Escape")
    c.panel("terms").focus()
    c.page.keyboard.press("b")
    c.page.locator(".wb-menu input").wait_for()
    c.page.keyboard.type("curr")
    c.page.wait_for_timeout(300)
    c.save("17b-keyboard-bind.jpg")
    c.page.keyboard.press("Escape")


@shot("18-undo.jpg")
def undo(c: Ctx):
    c.panel("terms").focus()
    for _ in range(2):
        rev = c.rev()
        c.page.keyboard.press("Shift+ArrowLeft")
        c.settle(rev)
    c.save("18-undo.jpg", ".wb-bar")


@shot("19-phone.jpg")
def phone(c: Ctx):
    c.page.locator('[data-width="phone"]').click()
    c.page.wait_for_timeout(900)
    c.save("19-phone.jpg")
    c.page.locator('[data-width="desktop"]').click()


@shot("20-autodesign.jpg")
def autodesign(c: Ctx):
    docs = {f"trade-{i}.json": json.dumps({"tradeId": f"T-{i}", "book": ["rates", "fx", "credit"][i % 3], "notional": 1000000 * (i + 1), "mtm": 12345 * i - 20000,
                                           "legs": [{"leg": 1, "rate": 0.01 * i, "amount": 100 * i}, {"leg": 2, "rate": 0.02, "amount": 200}]}) for i in range(1, 6)}
    c.open(c.design("Screenshots: auto-design", files=docs))
    c.page.on("dialog", lambda d: d.accept())
    rev = c.rev()
    c.page.locator("[data-autodesign]").click()
    c.settle(rev)
    c.page.wait_for_timeout(1000)
    c.save("20-autodesign.jpg")


FAMILIES = [("30-family-facts.jpg", "all-panels-showcase", "ops", "key figures, key/value and status"),
            ("31-family-tables.jpg", "tree-table", None, "tables and the tree table"),
            ("32-family-pivot.jpg", "pivot-row-groups", None, "pivot row groups"),
            ("33-family-series.jpg", "exposure-profile", None, "time series"),
            ("34-family-market.jpg", "market-charts", None, "market"),
            ("35-family-risk.jpg", "risk-distribution", None, "risk"),
            ("36-family-pnl.jpg", "pnl-explain", None, "P&L"),
            ("37-family-relationships.jpg", "relationships", None, "relationships"),
            ("38-family-operations.jpg", "operations-status", None, "operations")]


def family(file, example, select):
    def run(c: Ctx):
        c.example(example)
        first = select or c.page.evaluate("window.drishtiWorkbench.canvas.ids()[0]")
        c.panel(first).locator(".pnl-h").click()
        c.page.wait_for_timeout(500)
        c.save(file)
    return run


for _f, _e, _s, _t in FAMILIES:
    SHOTS.append((_f, family(_f, _e, _s)))


# ---- running ---------------------------------------------------------------------------------------------------------------------------------

def listening(port: int) -> bool:
    try:
        urllib.request.urlopen(f"http://127.0.0.1:{port}/", timeout=2)
        return True
    except OSError:
        return False
    except Exception:  # noqa: BLE001 - an HTTP error still means something answers
        return True


def start_servers(work: Path) -> list:
    jars = sorted((ROOT / "drishti-server" / "target").glob("drishti-server-*-exec.jar"))
    if not jars:
        sys.exit("build the server first: ./mvnw -o package -DskipTests -pl drishti-server -am")
    java = os.environ.get("JAVA_HOME", "/usr/lib/jvm/java-25-openjdk-amd64") + "/bin/java"
    for name in ("packs", "config"):
        if not (work / name).exists() and (ROOT / name).exists():
            (work / name).symlink_to(ROOT / name)
    env = dict(os.environ, DRISHTI_PORT=str(SERVER_PORT), DRISHTI_PACKS=PACKS)
    server = subprocess.Popen([java, "-jar", str(jars[-1])], cwd=work, env=env, stdout=(work / "server.log").open("w"), stderr=subprocess.STDOUT)
    py = ROOT / "console" / ".venv" / "bin" / "python"
    py = py if py.exists() else Path(sys.executable)
    cenv = dict(os.environ, DRISHTI_BACKEND_URL=f"http://127.0.0.1:{SERVER_PORT}", DRISHTI_CONSOLE_PORT=str(CONSOLE_PORT))
    console = subprocess.Popen([str(py), str(ROOT / "console" / "run_drishti_web.py")], cwd=ROOT, env=cenv, stdout=(work / "console.log").open("w"), stderr=subprocess.STDOUT)
    for _ in range(240):
        if listening(SERVER_PORT) and listening(CONSOLE_PORT):
            return [server, console]
        time.sleep(0.5)
    for p in (server, console):
        p.terminate()
    sys.exit("the scratch server or console did not start; see " + str(work))


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--base", default=f"http://127.0.0.1:{CONSOLE_PORT}")
    ap.add_argument("--only", default="")
    ap.add_argument("--list", action="store_true")
    a = ap.parse_args()
    if a.list:
        print("\n".join(n for n, _ in SHOTS))
        return 0
    port = int(a.base.rsplit(":", 1)[-1])
    if port in FORBIDDEN:
        sys.exit(f"refusing {a.base}: screenshots are made on the scratch console (:{CONSOLE_PORT}), never on the usual one")
    from playwright.sync_api import sync_playwright

    started, work = [], Path(tempfile.mkdtemp(prefix="drishti-shots-"))
    try:
        if not listening(port):
            started = start_servers(work)
        failed = []
        with sync_playwright() as p:
            browser = p.chromium.launch()
            page = browser.new_page(viewport={"width": 1440, "height": 900})
            c = Ctx(page, a.base)
            # one design stays open across the first shots (they build on each other); later ones open their own
            for name, fn in SHOTS:
                if a.only and a.only not in name:
                    continue
                print(name)
                for attempt in (1, 2):                      # a slow machine can time a step out once; the second try starts from a clean page
                    try:
                        if "03" <= name[:2] <= "14" and not c.showcase_open:
                            c.example("all-panels-showcase")
                        fn(c)
                        break
                    except Exception as e:  # noqa: BLE001 - report every failed picture, keep going
                        print("  attempt", attempt, "failed:", str(e).splitlines()[0][:200])
                        c.showcase_open = False
                        if attempt == 2:
                            failed.append(name)
            c.forget()
            browser.close()
        print("failed:", failed if failed else "none")
        return 1 if failed else 0
    finally:
        for proc in started:
            proc.terminate()
        shutil.rmtree(work, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
