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

"""Regenerates the screenshots of the connector developer guide (docs/connectors/CONNECTOR_DEVELOPER_GUIDE.md) into
docs/connectors/img/connectors/.

It starts a SCRATCH server and console (default :18997 / :17997, never the usual :18480 / :17480) in a scratch directory,
with the teaching plugin (docs/guides/examples/connector, built with ./mvnw package) in a plugin folder, a connector over
its sample folder, and two connectors pointed at ports nothing listens on, so Admin -> Health shows UP and DOWN
(reconnecting) side by side. It stops what it started.

    ./mvnw -o -q package -DskipTests -pl docs/guides/examples/connector -am
    console/.venv/bin/python tools/docs/shots/connectors.py [--only provenance] [--list]
                             [--server-port 18997 --console-port 17997] [--server-jar path]

When the shared driver of tools/docs/screenshots.py takes over starting servers, only the SHOTS and the scratch
configuration below need to move."""
from __future__ import annotations

import argparse
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
OUT = ROOT / "docs" / "connectors" / "img" / "connectors"
EXAMPLE = ROOT / "docs" / "guides" / "examples" / "connector"
FORBIDDEN = {18480, 17480}
DEAD_DB, DEAD_BROKER = 59999, 59998          # nothing listens here: the connectors below are meant to be DOWN
SHOTS: list = []


def shot(name: str):
    def reg(fn):
        SHOTS.append((name, fn))
        return fn
    return reg


def listening(port: int) -> bool:
    try:
        urllib.request.urlopen(f"http://127.0.0.1:{port}/", timeout=2)
        return True
    except urllib.error.HTTPError:
        return True
    except OSError:
        return False


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


def start_servers(work: Path, a) -> list:
    jar = Path(a.server_jar) if a.server_jar else (sorted((ROOT / "drishti-server" / "target").glob("drishti-server-*-exec.jar")) or [None])[-1]
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
    env = dict(os.environ, DRISHTI_PORT=str(a.server_port), DRISHTI_PACKS="counterparty-risk",
               DRISHTI_DEMO_ENABLED="false", DRISHTI_LAKE_ENABLED="false")
    server = subprocess.Popen([java, "-jar", str(jar)], cwd=work, env=env, stdout=(work / "server.log").open("w"), stderr=subprocess.STDOUT)
    py = ROOT / "console" / ".venv" / "bin" / "python"
    py = py if py.exists() else Path(sys.executable)
    cenv = dict(os.environ, DRISHTI_BACKEND_URL=f"http://127.0.0.1:{a.server_port}", DRISHTI_CONSOLE_PORT=str(a.console_port))
    console = subprocess.Popen([str(py), str(ROOT / "console" / "run_drishti_web.py")], cwd=ROOT, env=cenv,
                               stdout=(work / "console.log").open("w"), stderr=subprocess.STDOUT)
    for _ in range(240):
        if listening(a.server_port) and listening(a.console_port):
            return [server, console]
        time.sleep(0.5)
    for p in (server, console):
        p.terminate()
    sys.exit("the scratch server or console did not start; see " + str(work))


class Ctx:
    def __init__(self, page, base):
        self.page, self.base = page, base

    def save(self, name, locator=None):
        OUT.mkdir(parents=True, exist_ok=True)
        path = OUT / name
        if locator:
            self.page.locator(locator).first.screenshot(path=str(path))
        else:
            self.page.screenshot(path=str(path))
        print("  wrote", path.relative_to(ROOT))


@shot("sources-health.png")
def sources_health(c: Ctx):
    c.page.goto(c.base + "/admin/health")
    c.page.wait_for_selector("[data-health] table")
    c.page.wait_for_timeout(26000)              # the broker connector says DOWN (reconnecting) after 10 s without a connection
    c.page.reload()
    c.page.wait_for_selector("[data-health] table")
    c.page.wait_for_timeout(1500)
    c.save("sources-health.png", "[data-health]")


@shot("dayfolder-view.png")
def dayfolder_view(c: Ctx):
    open_trade(c, "T-1")
    c.save("dayfolder-view.png")


@shot("business-date-picker.png")
def date_picker(c: Ctx):
    c.page.goto(f"{c.base}/asof?d=2026-09-29&next=/v/trade/T-1")          # what picking the date in the top bar does
    c.page.wait_for_selector("input#asofDate")
    c.page.wait_for_timeout(1500)
    c.save("business-date-picker.png")


@shot("provenance.png")
def provenance(c: Ctx):
    open_trade(c, "T-1")
    c.save("provenance.png", '[data-panel]:has-text("How this view was built")')


def open_trade(c: Ctx, id_: str):
    c.page.goto(f"{c.base}/v/trade/{id_}")
    c.page.wait_for_selector("[data-panel]", timeout=30000)
    c.page.wait_for_timeout(1500)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--server-port", type=int, default=18997)
    ap.add_argument("--console-port", type=int, default=17997)
    ap.add_argument("--server-jar", default="")
    ap.add_argument("--only", default="")
    ap.add_argument("--list", action="store_true")
    a = ap.parse_args()
    if a.list:
        print("\n".join(n for n, _ in SHOTS))
        return 0
    if {a.server_port, a.console_port} & FORBIDDEN:
        sys.exit("refusing the usual ports :18480 / :17480: screenshots are made on a scratch server")
    if listening(a.server_port) or listening(a.console_port):
        sys.exit(f"something already listens on :{a.server_port} or :{a.console_port} and it is not ours; pass other --server-port/--console-port")
    from playwright.sync_api import sync_playwright

    work, started = Path(tempfile.mkdtemp(prefix="drishti-connector-shots-")), []
    try:
        started = start_servers(work, a)
        failed = []
        with sync_playwright() as p:
            browser = p.chromium.launch()
            page = browser.new_page(viewport={"width": 1280, "height": 800})
            c = Ctx(page, f"http://127.0.0.1:{a.console_port}")
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
