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

"""The pictures of the pack developer guide (docs/guides/PACK_DEVELOPER_GUIDE.md), written to docs/guides/img/packs/.

The worked example is the help-desk pack, docs/guides/examples/pack/helpdesk. The pictures need a scratch server and console that load the
QUICKSTART packs but NOT helpdesk, and that trust a registry holding a signed helpdesk-0.1.0.zip, so that the install picture is a real
install (the shared scratch server of tools/docs/screenshots.py has no registry). This module prepares and stops that setup itself:

    drishti-console/.venv/bin/python tools/docs/shots/packs.py            # starts it on :18996/:17996, runs the pictures, stops it
    drishti-console/.venv/bin/python tools/docs/screenshots.py --guide packs --base http://127.0.0.1:17996   # against a setup you prepared

It never touches :18480 / :17480. Installing changes the server, so run the pictures on a fresh setup (the first run does)."""
from __future__ import annotations

import os
import shutil
import subprocess
import sys
import tempfile
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from shotlib import Ctx, listening, out_dir, register  # noqa: E402

ROOT = Path(__file__).resolve().parents[3]
PACK = ROOT / "docs" / "guides" / "examples" / "pack" / "helpdesk"
QUICKSTART = ["banking-core", "market-data", "trading", "market-risk", "counterparty-risk", "liquidity-risk", "climate-risk",
              "operational-risk", "retail-banking", "genomics", "politics-society", "economics"]
LISTED = "market-risk,counterparty-risk,liquidity-risk,climate-risk,operational-risk,retail-banking,genomics,politics-society,economics"
SERVER_PORT, CONSOLE_PORT = 18996, 17996
shot = register("packs")


def registry_section(c: Ctx, name: str):
    """The table under 'From the registry' (it is far below the fold, and wider than a phone)."""
    c.page.set_viewport_size({"width": 2600, "height": 900})     # wide enough for every column
    c.page.evaluate("document.querySelectorAll('#registry ~ .tbl-wrap td').forEach(function (td) { td.style.whiteSpace = 'normal'; })")
    c.page.wait_for_timeout(500)
    c.page.locator("#registry ~ .tbl-wrap").first.screenshot(path=str(out_dir("packs") / name), type="jpeg", quality=84)
    c.page.set_viewport_size({"width": 1440, "height": 900})
    print("  wrote", (out_dir("packs") / name).relative_to(ROOT))


@shot("01-admin-packs-registry.jpg")
def admin_packs(c: Ctx):
    c.page.goto(c.base + "/admin/packs")
    c.page.locator('[data-registry="install"]').first.wait_for(timeout=30000)
    registry_section(c, "01-admin-packs-registry.jpg")


@shot("02-registry-installed.jpg", also=("02b-helpdesk-pack-row.jpg",))
def registry_install(c: Ctx):
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
    row.screenshot(path=str(out_dir("packs") / "02b-helpdesk-pack-row.jpg"), type="jpeg", quality=84)
    print("  wrote", (out_dir("packs") / "02b-helpdesk-pack-row.jpg").relative_to(ROOT))


@shot("03-pack-help-guide.jpg")
def help_guide(c: Ctx):
    c.page.goto(c.base + "/help/helpdesk-pack")
    c.page.wait_for_timeout(800)
    c.save("03-pack-help-guide.jpg", clip={"x": 0, "y": 0, "width": 1440, "height": 760})


@shot("04-ticket-view.jpg")
def ticket_view(c: Ctx):
    c.page.goto(c.base + "/v/ticket/TKT-1001")
    c.page.locator("[data-panel]").first.wait_for(timeout=30000)
    c.page.wait_for_timeout(4000)                               # the links panel resolves its badges a moment later
    c.save("04-ticket-view.jpg", clip={"x": 0, "y": 0, "width": 1440, "height": 620})


@shot("05-derived-load.jpg")
def derived_view(c: Ctx):
    c.page.goto(c.base + "/v/agent-load/AGT-07")
    c.page.locator("[data-panel]").first.wait_for(timeout=30000)
    c.page.wait_for_timeout(1200)
    c.save("05-derived-load.jpg", clip={"x": 0, "y": 0, "width": 1440, "height": 520})


@shot("06-workbench-export.jpg")
def workbench_export(c: Ctx):
    c.page.set_viewport_size({"width": 1700, "height": 900})     # the Ship menu opens at the right edge
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
        (packs / name).symlink_to(ROOT / "config" / "packs" / name)
    reg = work / "reg"
    reg.mkdir()
    py = ROOT / "drishti-console" / ".venv" / "bin" / "python"
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
    console = subprocess.Popen([str(py), str(ROOT / "drishti-console" / "run_drishti_web.py")], cwd=ROOT / "drishti-console", env=cenv,
                               stdout=(work / "console.log").open("w"), stderr=subprocess.STDOUT)
    for _ in range(240):
        if listening(SERVER_PORT) and listening(CONSOLE_PORT):
            return [server, console]
        time.sleep(0.5)
    for p in (server, console):
        p.terminate()
    sys.exit("the scratch server or console did not start; see " + str(work))


def main() -> int:
    work = Path(tempfile.mkdtemp(prefix="drishti-packshots-"))
    started = []
    try:
        started = serve(work)
        return subprocess.call([sys.executable, str(ROOT / "tools" / "docs" / "screenshots.py"), "--guide", "packs",
                                "--base", f"http://127.0.0.1:{CONSOLE_PORT}"])
    finally:
        for proc in started:
            proc.terminate()
        shutil.rmtree(work, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
