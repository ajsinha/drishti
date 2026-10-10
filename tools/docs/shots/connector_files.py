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

"""The pictures of Admin -> Connectors (docs/connectors/CONNECTOR_FILES.md), written to docs/connectors/img/connector_files/.

The setup is its own: a scratch server and console (the ports of DRISHTI_SHOTS_SERVER_PORT / DRISHTI_SHOTS_CONSOLE_PORT) with the trading
pack, whose connector templates are written out as files in a scratch connector folder at the first start, a site connector over a folder of
JSON Lines files, one pointed at a port nothing listens on, and a small pack, desk-demo, that names a connector nobody has created yet.

    DRISHTI_SHOTS_SERVER_PORT=18988 DRISHTI_SHOTS_CONSOLE_PORT=17988 \\
        drishti-console/.venv/bin/python tools/docs/screenshots.py --guide connector_files
"""
from __future__ import annotations

import os
import subprocess
import sys
import time
from pathlib import Path

from shotlib import CONSOLE_PORT, ROOT, SERVER_PORT, Ctx, listening, register

OUT = ROOT / "docs" / "connectors" / "img" / "connector-files"
DEAD_DB = 59997                              # nothing listens here: the connector below is meant to be DOWN
shot = register("connector_files", OUT)


def start_servers(work: Path) -> list:
    for port in (SERVER_PORT, CONSOLE_PORT):
        if listening(port):
            sys.exit(f":{port} already answers: stop it, or pass --base to use it as it is")
    given = os.environ.get("DRISHTI_SHOTS_SERVER_JAR", "")
    jar = Path(given) if given else (sorted((ROOT / "drishti-server" / "target").glob("drishti-server-*-exec.jar")) or [None])[-1]
    if jar is None or not jar.exists():
        sys.exit("build the server first: ./mvnw -o package -DskipTests -pl drishti-server -am")
    packs = work / "packs"
    packs.mkdir()
    for name in ("banking-core", "market-data", "trading"):
        (packs / name).symlink_to(ROOT / "packs" / name)
    (packs / "desk-demo").mkdir()
    (packs / "desk-demo" / "pack.yaml").write_text(
        "pack: desk-demo\nversion: 1.0.0\ntitle: Desk demo\ndescription: Quotes read from a stream somebody has not set up yet.\n"
        "kinds: [quote, quote-archive]\nconnectors: [desk-quotes, desk-archive]\nroutes:\n  quote: desk-quotes\n  quote-archive: desk-archive\n"
        "connector-templates:\n  desk-quotes:\n    plugin: file\n    kinds: [quote]\n    settings:\n      root: ./data/desk-quotes\n", encoding="utf-8")
    lake = work / "lake"
    for day in ("2026-10-02", "2026-10-05"):
        (lake / day).mkdir(parents=True)
        (lake / day / "quote.jsonl").write_text("".join(f'{{"id": "Q-{i}", "bid": {100 + i}}}\n' for i in range(4 if day.endswith("05") else 3)), encoding="utf-8")
    conns = work / "connectors"
    conns.mkdir()
    (conns / "site-quotes.yaml").write_text(
        f"# A site connector: no pack suggests it.\nplugin: file\nkinds: [quote]\ndescription: Quotes from the desk's drop folder\nsettings:\n  root: {lake}\n  lookback-days: '10'\n",
        encoding="utf-8")
    (conns / "ledger-db.yaml").write_text(
        f"plugin: jdbc\nkinds: [ledger-entry]\ndescription: The ledger database (down on purpose)\nsettings:\n  url: jdbc:postgresql://127.0.0.1:{DEAD_DB}/ledger\n  user: drishti\n"
        "  password: ${LEDGER_DB_PASSWORD}\n  table: ledger.entities\n  tls:\n    enabled: true\n    truststore:\n      path: /etc/drishti/tls/ca.p12\n"
        "      password: ${LEDGER_TRUSTSTORE_PASSWORD}\n", encoding="utf-8")
    (work / "application.local.yaml").write_text("drishti:\n  sources:\n    connectors:\n      legacy-feed:\n        plugin: file\n        settings: { root: ./data/legacy }\n", encoding="utf-8")
    for d in ("data/legacy", "data/desk-quotes", "delta"):
        (work / d).mkdir(parents=True, exist_ok=True)
    java = os.environ.get("JAVA_HOME", "/usr/lib/jvm/java-21-openjdk-amd64") + "/bin/java"
    env = dict(os.environ, DRISHTI_PORT=str(SERVER_PORT), DRISHTI_PACKS="trading,desk-demo", DRISHTI_PACKS_DIR=str(packs), DRISHTI_CONNECTORS_DIR=str(conns),
               DRISHTI_DEMO_ENABLED="false", DRISHTI_LAKE_ENABLED="true", DRISHTI_DELTA_ROOT=str(work / "delta"), LEDGER_DB_PASSWORD="not-a-real-password",
               LEDGER_TRUSTSTORE_PASSWORD="changeit")
    server = subprocess.Popen([java, "-jar", str(jar)], cwd=work, env=env, stdout=(work / "server.log").open("w"), stderr=subprocess.STDOUT)
    py = ROOT / "drishti-console" / ".venv" / "bin" / "python"
    py = py if py.exists() else (ROOT / ".venv" / "bin" / "python")
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


def wide(c: Ctx, w=1280, h=900):
    c.page.set_viewport_size({"width": w, "height": h})


def open_dialog_shot(c: Ctx, name: str):
    c.save(name, "[data-edit-dialog]")


@shot("01-connectors-list.jpg")
def listing(c: Ctx):
    wide(c)
    c.page.goto(c.base + "/admin/connectors")
    c.page.wait_for_selector("[data-list] tr[data-conn]")
    c.page.wait_for_timeout(1500)
    c.save("01-connectors-list.jpg", ".adm")


@shot("02-new-connector-form.jpg", also=("03-secret-refused.jpg",))
def new_form(c: Ctx):
    wide(c)
    c.page.goto(c.base + "/admin/connectors")
    c.page.click("[data-new]")
    d = c.page.locator("[data-edit-dialog]")
    d.locator("[data-f-name]").wait_for()
    d.locator("[data-f-plugin]").select_option("jdbc")
    d.locator("[data-f-name]").fill("orders-db")
    d.locator("[data-f-description]").fill("The orders database")
    d.locator("[data-setting='url']").fill("jdbc:postgresql://orders.example.com:5432/orders")
    d.locator("[data-setting='user']").fill("drishti")
    d.locator("[data-setting='password']").fill("${ORDERS_DB_PASSWORD}")
    d.locator("[data-setting='tls.enabled']").select_option("true")
    d.locator("[data-setting='tls.truststore.path']").fill("/etc/drishti/tls/ca.p12")
    d.locator("[data-setting='tls.truststore.password']").fill("${ORDERS_TRUSTSTORE_PASSWORD}")
    c.page.wait_for_timeout(500)
    c.page.evaluate("() => { const d = document.querySelector('[data-edit-dialog]'); d.scrollTop = 0; }")
    c.save("02-new-connector-form.jpg", "[data-edit-dialog]")
    d.locator("[data-setting='password']").fill("hunter2")
    d.locator("[data-save]").click()
    c.page.wait_for_timeout(500)
    c.page.evaluate("() => document.querySelector('[data-setting=\"password\"]').scrollIntoView({block: 'center'})")
    c.save("03-secret-refused.jpg", "[data-edit-dialog]")
    c.page.keyboard.press("Escape")


@shot("04-test-connection.jpg")
def test_tab(c: Ctx):
    wide(c)
    c.page.goto(c.base + "/admin/connectors?name=ledger-db")
    d = c.page.locator("[data-edit-dialog]")
    d.locator("[data-f-description]").wait_for()
    d.locator("[data-test]").click()
    d.locator("[data-test-out] p").first.wait_for(timeout=60000)
    c.page.wait_for_timeout(500)
    c.save("04-test-connection.jpg", "[data-edit-dialog]")
    c.page.keyboard.press("Escape")


@shot("05-yaml-tab.jpg", also=("06-history-diff.jpg",))
def yaml_and_history(c: Ctx):
    wide(c)
    c.page.goto(c.base + "/admin/connectors?name=site-quotes")
    d = c.page.locator("[data-edit-dialog]")
    d.locator("[data-f-description]").wait_for()
    d.locator("[data-tab='yaml']").click()
    c.page.wait_for_timeout(500)
    c.save("05-yaml-tab.jpg", "[data-edit-dialog]")
    y = d.locator("[data-yaml]")
    y.fill(y.input_value().replace("lookback-days: '10'", "lookback-days: '30'") + "  refresh-seconds: '60'\n")
    d.locator("[data-save]").click()
    c.page.wait_for_timeout(2500)
    c.page.goto(c.base + "/admin/connectors?name=site-quotes")
    d.locator("[data-f-description]").wait_for()
    d.locator("[data-tab='history']").click()
    d.get_by_role("button", name="Show what changed since version").first.click()
    d.locator("[data-diff]:not([hidden])").wait_for()
    c.save("06-history-diff.jpg", "[data-edit-dialog]")
    c.page.keyboard.press("Escape")


@shot("07-pack-connectors.jpg")
def pack_view(c: Ctx):
    wide(c)
    c.page.goto(c.base + "/admin/packs")
    c.page.locator("tr[data-pack='desk-demo'] [data-datasource]").click()
    c.page.locator("[data-ds-dialog] [data-connector]").first.wait_for()
    c.page.wait_for_timeout(500)
    c.save("07-pack-connectors.jpg", "[data-ds-dialog]")
    c.page.keyboard.press("Escape")


@shot("08-create-from-suggestion.jpg")
def from_suggestion(c: Ctx):
    wide(c)
    c.page.goto(c.base + "/admin/connectors?new=desk-quotes")
    d = c.page.locator("[data-edit-dialog]")
    d.locator("[data-f-name]").wait_for()
    c.page.wait_for_timeout(800)
    c.save("08-create-from-suggestion.jpg", "[data-edit-dialog]")
    c.page.keyboard.press("Escape")


@shot("09-disable-asks.jpg")
def disable_asks(c: Ctx):
    wide(c)
    c.page.goto(c.base + "/admin/connectors")
    row = c.page.locator("tr[data-conn='trading-store']")
    row.get_by_role("button", name="Disable trading-store").click()
    c.page.locator("[data-confirm-dialog]").wait_for()
    c.page.wait_for_timeout(400)
    c.save("09-disable-asks.jpg", "[data-confirm-dialog]")
    c.page.locator("[data-confirm-no]").click()


@shot("10-phone.jpg")
def phone(c: Ctx):
    c.page.set_viewport_size({"width": 390, "height": 844})
    c.page.goto(c.base + "/admin/connectors")
    c.page.click("[data-new]")
    c.page.locator("[data-edit-dialog] [data-f-name]").wait_for()
    c.page.wait_for_timeout(600)
    c.page.screenshot(path=str(OUT / "10-phone.jpg"), type="jpeg", quality=84)
    print("  wrote", (OUT / "10-phone.jpg").relative_to(ROOT))
    c.page.keyboard.press("Escape")
