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

"""The pictures of Admin → Packs → Deploy archive, history and roll back, and the pack row (docs/guides/OPERATIONALISING.md, PACKS.md),
written to docs/guides/img/deploy/.

The setup is its own: a scratch server and console (the ports of DRISHTI_SHOTS_SERVER_PORT / DRISHTI_SHOTS_CONSOLE_PORT, 18971 / 17971 when you
run it as below) with the QUICKSTART packs and a small pack, desk-lake, whose data is JSON Lines files on disk (two folders: the 'prod' lake it is
installed with and a 'DR' copy the Data source picture switches to). A second version of desk-lake is bundled with `drishti.py pack bundle` and
uploaded in the pictures. Deploying and saving change the server, so the pictures run on a fresh setup (the run starts one):

    DRISHTI_SHOTS_SERVER_PORT=18971 DRISHTI_SHOTS_CONSOLE_PORT=17971 \\
        drishti-console/.venv/bin/python tools/docs/screenshots.py --guide deploy --base http://127.0.0.1:17971
"""
from __future__ import annotations

import json
import os
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from shotlib import CONSOLE_PORT, PACKS, ROOT, SERVER_PORT, Ctx, listening, out_dir, register  # noqa: E402

shot = register("deploy")
QUICKSTART = ["banking-core", "market-data", "trading", "market-risk", "counterparty-risk", "liquidity-risk", "climate-risk",
              "operational-risk", "retail-banking", "genomics", "politics-society", "economics"]
HELPDESK = ROOT / "docs" / "guides" / "examples" / "pack" / "helpdesk"
STATE: dict = {}
ROW = "table.adm-tbl:not([data-kept]):not([data-history]) tr[data-pack='desk-lake']"


def pack_yaml(version: str, lake: Path, kinds: list, with_agent: bool) -> str:
    mnemonics = "  TKT: {kind: ticket, label: Ticket}\n" + ("  AGT: {kind: agent, label: Agent}\n" if with_agent else "")
    routes = "  ticket: ticket-store\n" + ("  agent: ticket-store\n" if with_agent else "")
    return (f"pack: desk-lake\nversion: {version}\ntitle: Desk lake\ndescription: Support tickets read from JSON Lines files on disk.\n"
            f"kinds: [{', '.join(kinds)}]\nmnemonics:\n{mnemonics}connectors:\n  ticket-store:\n    plugin: file\n    kinds: [{', '.join(kinds)}]\n"
            f"    settings:\n      root: {lake}\n      lookback-days: 10\nroutes:\n{routes}")


def write_pack(folder: Path, version: str, lake: Path, kinds: list, with_agent: bool, note: str = "") -> None:
    (folder / "sutras").mkdir(parents=True, exist_ok=True)
    (folder / "pack.yaml").write_text(pack_yaml(version, lake, kinds, with_agent), encoding="utf-8")
    sutra = (HELPDESK / "sutras" / "ticket.v1.sutra.yaml").read_text(encoding="utf-8")
    (folder / "sutras" / "ticket.v1.sutra.yaml").write_text(sutra.replace("A support ticket with its history, agent and client.",
                                                                          "A support ticket with its history, agent and client." + note), encoding="utf-8")


def write_lake(root: Path, days: dict) -> None:
    for day, rows in days.items():
        (root / day).mkdir(parents=True, exist_ok=True)
        for kind, n in rows.items():
            lines = [json.dumps({"id": f"{kind[:3].upper()}-{1000 + i}", "ticketId": f"TKT-{1000 + i}", "subject": f"Ticket {i}", "status": "Open",
                                 "priority": "P2", "ageHours": 4 + i, "assignee": "AGT-07", "client": "CLI-ACME", "history": []}) for i in range(n)]
            (root / day / f"{kind}.jsonl").write_text("\n".join(lines) + "\n", encoding="utf-8")


def start_servers(work: Path) -> list:
    for port in (SERVER_PORT, CONSOLE_PORT):
        if listening(port):
            sys.exit(f":{port} already answers: stop it, or pass --base to use it as it is")
    jars = sorted((ROOT / "drishti-server" / "target").glob("drishti-server-*-exec.jar"))
    if not jars:
        sys.exit("build the server first: ./mvnw -o package -DskipTests -pl drishti-server -am")
    packs = work / "packs"
    packs.mkdir()
    for name in QUICKSTART:
        (packs / name).symlink_to(ROOT / "config" / "packs" / name)
    prod, dr = work / "lake-prod", work / "lake-dr"
    write_lake(prod, {"2026-10-02": {"ticket": 3}, "2026-10-05": {"ticket": 4, "agent": 2}})
    write_lake(dr, {"2026-10-04": {"ticket": 5, "agent": 2}, "2026-10-05": {"ticket": 6, "agent": 2}})
    write_pack(packs / "desk-lake", "1.0.0", prod, ["ticket", "agent"], True)
    build = work / "build" / "desk-lake"                     # version 1.1.0: drops the 'agent' kind (breaking) and edits the Sutra
    write_pack(build, "1.1.0", prod, ["ticket"], False, note=" Now with the owner's team.")
    dist = work / "dist"
    done = subprocess.run([sys.executable, str(ROOT / "tools" / "drishti.py"), "pack", "bundle", str(build), "--no-check", "--out", str(dist),
                           "--requires-server", "1.0.0"], capture_output=True, text=True)
    if done.returncode != 0:
        sys.exit("pack bundle failed: " + done.stdout + done.stderr)
    STATE["archive"], STATE["dr"] = dist / "desk-lake-1.1.0.tar.gz", str(dr)
    java = os.environ.get("JAVA_HOME", "/usr/lib/jvm/java-21-openjdk-amd64") + "/bin/java"
    env = dict(os.environ, DRISHTI_PORT=str(SERVER_PORT), DRISHTI_PACKS=PACKS + ",desk-lake", DRISHTI_PACKS_DIR=str(packs), DRISHTI_STUDIO_SAVE="true",
               DRISHTI_SUTRAS=str(work / "sutras"), DRISHTI_PACKS_INSTALLED=str(work / "data" / "installed"), DRISHTI_PACKS_SETTINGS=str(work / "data" / "settings"),
               DRISHTI_PACKS_DEPLOY_HISTORY=str(work / "data" / "history.jsonl"))
    (work / "sutras").mkdir()
    server = subprocess.Popen([java, "-jar", str(jars[-1])], cwd=work, env=env, stdout=(work / "server.log").open("w"), stderr=subprocess.STDOUT)
    py = ROOT / "drishti-console" / ".venv" / "bin" / "python"
    py = py if py.exists() else Path(sys.executable)
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


def wait_back(c: Ctx, marker: str, seconds: int = 120) -> None:
    """The server restarts in place; the page reloads by itself. Waits until the packs page answers with `marker` in it."""
    for _ in range(seconds * 2):
        c.page.wait_for_timeout(500)
        try:
            c.page.goto(c.base + "/admin/packs")
            if marker in c.page.content():
                return
        except Exception:  # noqa: BLE001 - the console answers 5xx while the server restarts
            pass
    raise TimeoutError("the server did not come back with " + marker)


@shot("01-deploy-checks.jpg", also=("02-deploy-preview.jpg",))
def upload(c: Ctx):
    c.page.set_viewport_size({"width": 1280, "height": 900})
    c.page.goto(c.base + "/admin/packs")
    c.page.set_input_files("[data-file]", str(STATE["archive"]))
    c.page.click("[data-upload]")
    c.page.locator("[data-result]:not([hidden]) .dep-find").wait_for(timeout=60000)
    c.page.wait_for_timeout(500)
    deploy = c.page.locator("[data-deploy]")
    out = out_dir("deploy")
    out.mkdir(parents=True, exist_ok=True)
    kids = "Array.from(document.querySelector('[data-result]').children)"
    # 01: the chooser and the checks only
    c.page.evaluate(f"{kids}.slice(2).forEach(function (n) {{ n.style.display = 'none'; }})")
    deploy.screenshot(path=str(out / "01-deploy-checks.jpg"), type="jpeg", quality=84)
    print("  wrote", (out / "01-deploy-checks.jpg").relative_to(ROOT))
    # 02: what it changes, boxed breaking changes and the confirmation
    c.page.evaluate(f"{kids}.forEach(function (n, i) {{ n.style.display = i < 2 ? 'none' : ''; }}); ['.dep-drop', '.dep-adv', '[data-dep-msg]'].forEach(function (q) {{ document.querySelector(q).style.display = 'none'; }})")
    c.page.locator("[data-result]").screenshot(path=str(out / "02-deploy-preview.jpg"), type="jpeg", quality=84)
    print("  wrote", (out / "02-deploy-preview.jpg").relative_to(ROOT))
    c.page.evaluate(f"{kids}.forEach(function (n) {{ n.style.display = ''; }}); ['.dep-drop', '.dep-adv', '[data-dep-msg]'].forEach(function (q) {{ document.querySelector(q).style.display = ''; }})")


@shot("03-history-rollback.jpg", also=("04-pack-row.jpg",))
def deployed(c: Ctx):
    c.page.on("dialog", lambda d: d.accept())
    c.page.check("[data-ack]")
    c.page.click("[data-deploy-go]")
    wait_back(c, "Roll back to this")
    c.page.wait_for_timeout(800)
    c.page.locator("#history").scroll_into_view_if_needed()
    c.page.locator("[data-kept]").scroll_into_view_if_needed()
    path = out_dir("deploy") / "03-history-rollback.jpg"
    c.page.evaluate("document.querySelectorAll('[data-kept], [data-history]').forEach(function (t) { t.closest('.tbl-wrap').setAttribute('data-shot', '1'); })")
    c.page.locator("[data-shot]").first.screenshot(path=str(out_dir("deploy") / "03a.jpg"), type="jpeg", quality=84)
    c.page.evaluate("document.querySelector('[data-kept]').closest('.tbl-wrap').scrollIntoView()")
    c.page.screenshot(path=str(path), type="jpeg", quality=84)
    (out_dir("deploy") / "03a.jpg").unlink()
    print("  wrote", path.relative_to(ROOT))
    row = c.page.locator(ROW).first
    row.scroll_into_view_if_needed()
    row.screenshot(path=str(out_dir("deploy") / "04-pack-row.jpg"), type="jpeg", quality=84)
    print("  wrote", (out_dir("deploy") / "04-pack-row.jpg").relative_to(ROOT))
