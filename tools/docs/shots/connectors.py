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

"""The pictures of the connector developer guide (docs/connectors/CONNECTOR_DEVELOPER_GUIDE.md), written to docs/connectors/img/connectors/.

This guide needs its own scratch server: the teaching plugin (docs/guides/examples/connector, built with ./mvnw package) in a plugin folder,
a connector over its sample folder, and two connectors pointed at ports nothing listens on, so Admin -> Health shows UP and DOWN
(reconnecting) side by side. start_servers() below replaces the shared one for this guide.

    ./mvnw -o -q package -DskipTests -pl docs/guides/examples/connector -am
    drishti-console/.venv/bin/python tools/docs/screenshots.py --guide connectors [--only provenance]
    (DRISHTI_SHOTS_SERVER_PORT / DRISHTI_SHOTS_CONSOLE_PORT move it off :18997 / :17997 when another run holds them;
     DRISHTI_SHOTS_SERVER_JAR names a built server jar)"""
from __future__ import annotations

import os
import shutil
import subprocess
import sys
import time
from pathlib import Path

from shotlib import CONSOLE_PORT, ROOT, SERVER_PORT, Ctx, listening, register

OUT = ROOT / "docs" / "connectors" / "img" / "connectors"
EXAMPLE = ROOT / "docs" / "guides" / "examples" / "connector"
DEAD_DB, DEAD_BROKER = 59999, 59998          # nothing listens here: the connectors below are meant to be DOWN
shot = register("connectors", OUT)


def site_config(work: Path) -> str:
    return f"""drishti:
  sources:
    plugin-dir: ./plugins-extra
    connectors:
      desk-days:
        plugin: dayfolder
        kinds: [trade, counterparty]
        settings: {{ root: {work / 'sample'}, source-name: desk-days, mode.counterparty: effective, link.trade.netting-set: nettingSet, rescan-seconds: 5 }}
      desk-db:
        plugin: jdbc
        kinds: [desk-position]
        settings: {{ url: "jdbc:postgresql://127.0.0.1:{DEAD_DB}/desk", user: drishti, password: none, table: desk.entities }}
      desk-ticks:
        plugin: kafka
        kinds: [quote]
        settings: {{ bootstrap-servers: "127.0.0.1:{DEAD_BROKER}", topics: quotes, kind: quote, id-field: id }}
    routes: {{ trade: desk-days, counterparty: desk-days }}
"""


def start_servers(work: Path) -> list:
    given = os.environ.get("DRISHTI_SHOTS_SERVER_JAR", "")
    jar = Path(given) if given else (sorted((ROOT / "drishti-server" / "target").glob("drishti-server-*-exec.jar")) or [None])[-1]
    if jar is None or not jar.exists():
        sys.exit("build the server first (./mvnw -o package -DskipTests -pl drishti-server -am) or pass --server-jar")
    plugin = sorted((EXAMPLE / "target").glob("drishti-example-dayfolder-*.jar"))
    if not plugin:
        sys.exit("build the teaching plugin first: ./mvnw -o -q package -DskipTests -pl docs/guides/examples/connector -am")
    (work / "plugins-extra").mkdir()
    shutil.copy(plugin[-1], work / "plugins-extra")
    shutil.copytree(EXAMPLE / "sample", work / "sample")
    (work / "data" / "feeds").mkdir(parents=True)             # the shipped `file` connector reads ./data/feeds: an empty folder keeps it UP
    for name in ("packs", "config"):
        (work / name).symlink_to(ROOT / name)
    (work / "application.local.yaml").write_text(site_config(work))
    java = os.environ.get("JAVA_HOME", "/usr/lib/jvm/java-25-openjdk-amd64") + "/bin/java"
    env = dict(os.environ, DRISHTI_PORT=str(SERVER_PORT), DRISHTI_PACKS="counterparty-risk",
               DRISHTI_DEMO_ENABLED="false", DRISHTI_LAKE_ENABLED="false")
    server = subprocess.Popen([java, "-jar", str(jar)], cwd=work, env=env, stdout=(work / "server.log").open("w"), stderr=subprocess.STDOUT)
    py = ROOT / "drishti-console" / ".venv" / "bin" / "python"
    py = py if py.exists() else Path(sys.executable)
    cenv = dict(os.environ, DRISHTI_BACKEND_URL=f"http://127.0.0.1:{SERVER_PORT}", DRISHTI_CONSOLE_PORT=str(CONSOLE_PORT))
    console = subprocess.Popen([str(py), str(ROOT / "drishti-console" / "run_drishti_web.py")], cwd=ROOT, env=cenv,
                               stdout=(work / "console.log").open("w"), stderr=subprocess.STDOUT)
    for _ in range(240):
        if listening(SERVER_PORT) and listening(CONSOLE_PORT):
            return [server, console]
        time.sleep(0.5)
    for p in (server, console):
        p.terminate()
    sys.exit("the scratch server or console did not start; see " + str(work))


@shot("sources-health.jpg")
def sources_health(c: Ctx):
    c.page.goto(c.base + "/admin/health")
    c.page.wait_for_selector("[data-health] table")
    c.page.wait_for_timeout(26000)              # the broker connector says DOWN (reconnecting) after 10 s without a connection
    c.page.reload()
    c.page.wait_for_selector("[data-health] table")
    c.page.wait_for_timeout(1500)
    c.save("sources-health.jpg", "[data-health]")


@shot("dayfolder-view.jpg")
def dayfolder_view(c: Ctx):
    open_trade(c, "T-1")
    c.save("dayfolder-view.jpg")


@shot("business-date-picker.jpg")
def date_picker(c: Ctx):
    c.page.goto(f"{c.base}/asof?d=2026-09-29&next=/v/trade/T-1")          # what picking the date in the top bar does
    c.page.wait_for_selector("input#asofDate")
    c.page.wait_for_timeout(1500)
    c.save("business-date-picker.jpg")


@shot("provenance.jpg")
def provenance(c: Ctx):
    open_trade(c, "T-1")
    c.save("provenance.jpg", '[data-panel]:has-text("How this view was built")')


def open_trade(c: Ctx, id_: str):
    c.page.goto(f"{c.base}/v/trade/{id_}")
    c.page.wait_for_selector("[data-panel]", timeout=30000)
    c.page.wait_for_timeout(1500)
