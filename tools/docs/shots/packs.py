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

"""Screenshots of the pack developer guide (docs/guides/PACK_DEVELOPER_GUIDE.md) into docs/guides/img/packs/.

The guide's worked example is the help-desk pack, docs/guides/examples/pack/helpdesk. The pictures need a scratch server (:18996) and console
(:17996) that load the QUICKSTART packs but NOT helpdesk, and that trust a registry holding a signed helpdesk-0.1.0.zip, so that the install
picture is a real install:

    tools/docs/shots/packs.py --serve        # builds that scratch setup in a temp folder, runs the pictures, stops what it started
    tools/docs/shots/packs.py --base http://127.0.0.1:17996 --only 04   # against a setup you prepared yourself
    tools/docs/shots/packs.py --list

It never touches :18480 / :17480 (it refuses them). Playwright and the console venv are needed (console/.venv)."""
from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
OUT = ROOT / "docs" / "guides" / "img" / "packs"
PACK = ROOT / "docs" / "guides" / "examples" / "pack" / "helpdesk"
QUICKSTART = ["banking-core", "market-data", "trading", "market-risk", "counterparty-risk", "liquidity-risk", "climate-risk",
              "operational-risk", "retail-banking", "genomics", "politics-society", "economics"]
LISTED = "market-risk,counterparty-risk,liquidity-risk,climate-risk,operational-risk,retail-banking,genomics,politics-society,economics"
SERVER_PORT, CONSOLE_PORT = 18996, 17996
FORBIDDEN = {18480, 17480}
SHOTS: list = []


def shot(name: str):
    def reg(fn):
        SHOTS.append((name, fn))
        return fn
    return reg


class Ctx:
    def __init__(self, page, base):
        self.page, self.base = page, base

    def save(self, name, locator=None, clip=None):
        OUT.mkdir(parents=True, exist_ok=True)
        path = OUT / name
        if locator is not None:
            self.page.locator(locator).first.screenshot(path=str(path), type="jpeg", quality=84)
        else:
            self.page.screenshot(path=str(path), clip=clip, type="jpeg", quality=84)
        print("  wrote", path.relative_to(ROOT))

    def api(self, path, body):
        r = self.page.request.post(self.base + path, data=json.dumps(body), headers={"Content-Type": "application/json"})
        return r.json()

    def until(self, js, seconds=90):
        for _ in range(seconds * 10):
            if self.page.evaluate(js):
                return
            self.page.wait_for_timeout(100)
        raise TimeoutError("still false: " + js)


def registry_section(c: Ctx, name: str):
    """The table under 'From the registry' (it is far below the fold, and wider than a phone)."""
    c.page.set_viewport_size({"width": 2600, "height": 900})     # wide enough for every column
    c.page.wait_for_timeout(500)
    c.page.locator("#registry ~ .tbl-wrap").first.screenshot(path=str(OUT / name), type="jpeg", quality=84)
    c.page.set_viewport_size({"width": 1700, "height": 900})
    print("  wrote", (OUT / name).relative_to(ROOT))


@shot("01-admin-packs-registry.jpg")
def admin_packs(c: Ctx):
    OUT.mkdir(parents=True, exist_ok=True)
    c.page.goto(c.base + "/admin/packs")
    c.page.locator('[data-registry="install"]').first.wait_for(timeout=30000)
    registry_section(c, "01-admin-packs-registry.jpg")


@shot("02-registry-installed.jpg")
def registry_install(c: Ctx):
    OUT.mkdir(parents=True, exist_ok=True)
    c.page.on("dialog", lambda d: d.accept())                   # "Install helpdesk 0.1.0? The server restarts in place"
    c.page.goto(c.base + "/admin/packs")
    c.page.locator('[data-registry="install"]').first.click()
    for _ in range(240):                                        # the server restarts in place; the page reloads by itself
        c.page.wait_for_timeout(500)
        try:
            c.page.goto(c.base + "/admin/packs")
            if "loaded 0.1.0" in c.page.content():
                break
        except Exception:  # noqa: BLE001 - the console answers 5xx while the server restarts
            pass
    c.page.wait_for_timeout(800)
    registry_section(c, "02-registry-installed.jpg")
    row = c.page.locator('tr[data-pack="helpdesk"]').first
    row.scroll_into_view_if_needed()
    row.screenshot(path=str(OUT / "02b-helpdesk-pack-row.jpg"), type="jpeg", quality=84)
    print("  wrote", (OUT / "02b-helpdesk-pack-row.jpg").relative_to(ROOT))


@shot("03-pack-help-guide.jpg")
def help_guide(c: Ctx):
    c.page.goto(c.base + "/help/helpdesk-pack")
    c.page.wait_for_timeout(800)
    c.save("03-pack-help-guide.jpg", clip={"x": 0, "y": 0, "width": 1700, "height": 760})


@shot("04-ticket-view.jpg")
def ticket_view(c: Ctx):
    c.page.goto(c.base + "/v/ticket/TKT-1001")
    c.page.locator("[data-panel]").first.wait_for(timeout=30000)
    c.page.wait_for_timeout(4000)                               # the links panel resolves its badges a moment later
    c.save("04-ticket-view.jpg", clip={"x": 0, "y": 0, "width": 1700, "height": 620})


@shot("05-derived-load.jpg")
def derived_view(c: Ctx):
    c.page.goto(c.base + "/v/agent-load/AGT-07")
    c.page.locator("[data-panel]").first.wait_for(timeout=30000)
    c.page.wait_for_timeout(1200)
    c.save("05-derived-load.jpg", clip={"x": 0, "y": 0, "width": 1700, "height": 520})


@shot("06-workbench-export.jpg")
def workbench_export(c: Ctx):
    sutra = (PACK / "sutras" / "ticket.v1.sutra.yaml").read_text(encoding="utf-8")
    d = c.api("/build/designs", {"name": "Ticket view", "kind": "ticket", "sutra": sutra})
    files = [{"name": p.name, "text": p.read_text(encoding="utf-8")} for p in sorted((PACK / "tests" / "ticket").glob("*.json"))]
    c.api(f"/build/designs/{d['id']}/files", {"files": files})
    c.page.goto(f"{c.base}/build/d/{d['id']}")
    c.page.locator("[data-preview] [data-panel]").first.wait_for(timeout=30000)
    c.page.wait_for_timeout(1500)
    c.page.locator("[data-ship-menu]").click()
    c.page.locator(".wb-menu").wait_for()
    c.save("06-workbench-export.jpg", clip={"x": 0, "y": 60, "width": 1700, "height": 470})
    c.page.keyboard.press("Escape")
    c.page.request.delete(f"{c.base}/build/designs/{d['id']}")


def listening(port: int) -> bool:
    try:
        urllib.request.urlopen(f"http://127.0.0.1:{port}/", timeout=2)
        return True
    except urllib.error.HTTPError:
        return True
    except OSError:
        return False


def serve(work: Path) -> list:
    """The scratch setup: QUICKSTART packs (not helpdesk), a registry with a signed helpdesk, its publisher trusted."""
    for port in (SERVER_PORT, CONSOLE_PORT):
        if listening(port):
            sys.exit(f":{port} already answers: stop it, or pass --base to use it as it is")
    jars = sorted((ROOT / "drishti-server" / "target").glob("drishti-server-*-exec.jar")) or sorted(
        Path(os.environ.get("DRISHTI_JAR_DIR", ROOT.parent / "drishti-server" / "target")).glob("drishti-server-*-exec.jar"))
    if not jars:
        sys.exit("no drishti-server-*-exec.jar: run ./mvnw -q -o -DskipTests package")
    packs = work / "packs"
    packs.mkdir()
    for name in QUICKSTART:
        (packs / name).symlink_to(ROOT / "packs" / name)
    reg = work / "reg"
    reg.mkdir()
    py = ROOT / "console" / ".venv" / "bin" / "python"
    py = py if py.exists() else Path(sys.executable)
    key = work / "acme"
    out = subprocess.run([sys.executable, str(ROOT / "tools" / "packreg" / "packreg.py"), "keygen", "--out", str(key)],
                         check=True, capture_output=True, text=True).stdout
    public = next(ln.split(":", 1)[1].strip() for ln in out.splitlines() if ln.startswith("public key"))
    subprocess.run([sys.executable, str(ROOT / "tools" / "packreg" / "packreg.py"), "publish", str(PACK), "--registry", str(reg), "--key",
                    str(key) + ".pem", "--publisher", "acme"], check=True, capture_output=True)
    (work / "application.yaml").write_text(f"drishti:\n  packs:\n    registry:\n      trusted-keys:\n        acme: {public}\n", encoding="utf-8")
    java = os.environ.get("JAVA_HOME", "/usr/lib/jvm/java-25-openjdk-amd64") + "/bin/java"
    env = dict(os.environ, DRISHTI_PORT=str(SERVER_PORT), DRISHTI_PACKS=LISTED, DRISHTI_PACKS_DIR=str(packs), DRISHTI_PACK_REGISTRY=str(reg),
               DRISHTI_STUDIO_SAVE="true", DRISHTI_SUTRAS=str(work / "sutras"))
    (work / "sutras").mkdir()
    server = subprocess.Popen([java, "-jar", str(jars[-1])], cwd=work, env=env, stdout=(work / "server.log").open("w"), stderr=subprocess.STDOUT)
    cenv = dict(os.environ, DRISHTI_BACKEND_URL=f"http://127.0.0.1:{SERVER_PORT}", DRISHTI_CONSOLE_PORT=str(CONSOLE_PORT), DRISHTI_PACKS_DIR=str(packs))
    console = subprocess.Popen([str(py), str(ROOT / "console" / "run_drishti_web.py")], cwd=ROOT / "console", env=cenv,
                               stdout=(work / "console.log").open("w"), stderr=subprocess.STDOUT)
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
    ap.add_argument("--serve", action="store_true", help="start the scratch server and console, and stop them afterwards")
    a = ap.parse_args()
    if a.list:
        print("\n".join(n for n, _ in SHOTS))
        return 0
    if int(a.base.rsplit(":", 1)[-1]) in FORBIDDEN:
        sys.exit(f"refusing {a.base}: pictures are made on the scratch console (:{CONSOLE_PORT}), never on the usual one")
    from playwright.sync_api import sync_playwright

    started, work = [], Path(tempfile.mkdtemp(prefix="drishti-packshots-"))
    try:
        if a.serve:
            started = serve(work)
        failed = []
        with sync_playwright() as p:
            browser = p.chromium.launch()
            c = Ctx(browser.new_page(viewport={"width": 1700, "height": 900}), a.base)
            for name, fn in SHOTS:
                if a.only and not any(o and o in name for o in a.only.split(",")):
                    continue
                print(name)
                try:
                    fn(c)
                except Exception as e:  # noqa: BLE001 - report every failed picture, keep going
                    print("  failed:", str(e).splitlines()[0][:200])
                    failed.append(name)
            browser.close()
        print("failed:", failed if failed else "none")
        return 1 if failed else 0
    finally:
        for proc in started:
            proc.terminate()
        shutil.rmtree(work, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
