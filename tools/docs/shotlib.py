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

"""Shared pieces of the documentation screenshot driver (tools/docs/screenshots.py): the picture registry, the Playwright context with its
helpers, and the scratch server and console. Per-guide pictures live in tools/docs/shots/<guide>.py and register with @shot."""
from __future__ import annotations

import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
EXAMPLES = ROOT / "docs" / "guides" / "examples"
PACKS = "market-risk,counterparty-risk,liquidity-risk,climate-risk,operational-risk,retail-banking,genomics,politics-society,economics"
SERVER_PORT = int(os.environ.get("DRISHTI_SHOTS_SERVER_PORT", 18997))      # the scratch ports; override when another run holds them
CONSOLE_PORT = int(os.environ.get("DRISHTI_SHOTS_CONSOLE_PORT", 17997))
FORBIDDEN = {18480, 17480}
SHOTS: list = []             # (guide, file name, function, also: file names the same function writes)
OUTDIRS: dict = {}           # guide -> output directory (default docs/guides/img/<guide>)


def out_dir(guide: str) -> Path:
    return OUTDIRS.get(guide, ROOT / "docs" / "guides" / "img" / guide)


def register(guide: str, out: Path | None = None):
    """Returns the @shot decorator of one guide's module; `out` overrides the default output directory."""
    if out is not None:
        OUTDIRS[guide] = out

    def shot(name: str, also: tuple = ()):
        """Registers one picture; `also` names further files the same function writes (listed so the image test knows them)."""
        def reg(fn):
            SHOTS.append((guide, name, fn, tuple(also)))
            return fn
        return reg
    return shot


# ---- helpers ---------------------------------------------------------------------------------------------------------------------------

class Ctx:
    def __init__(self, page, base):
        self.page, self.base = page, base
        self.showcase_open = False          # the first pictures build on one open copy of the showcase
        self.made = []
        self.guide = ""                     # set by the driver before each picture: where save() writes

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
        out = out_dir(self.guide)
        out.mkdir(parents=True, exist_ok=True)
        path = out / name
        if locator is not None:
            self.page.locator(locator).first.screenshot(path=str(path), type="jpeg", quality=84)
        else:
            self.page.screenshot(path=str(path), clip=clip, type="jpeg", quality=84)
        print("  wrote", path.relative_to(ROOT))

    def until(self, js, seconds=60):
        """Polls a JavaScript expression until it is truthy (wait_for_function evaluates a string, which the console's CSP forbids)."""
        for _ in range(seconds * 10):
            if self.page.evaluate(js):
                return
            self.page.wait_for_timeout(100)
        raise TimeoutError("still false: " + js)

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


def backend_preview(yaml_text: str, document: dict, kind: str = "trade") -> dict:
    """The real view model the server builds for a Sutra and a pasted document (POST /api/v1/studio/preview, which saves nothing)."""
    req = urllib.request.Request(f"http://127.0.0.1:{SERVER_PORT}/api/v1/studio/preview", headers={"Content-Type": "application/json"},
                                 data=json.dumps({"yaml": yaml_text, "kind": kind, "id": "SAMPLE", "document": document}).encode())
    return json.load(urllib.request.urlopen(req, timeout=60))


def render_panel(panel: dict) -> str:
    """One panel view rendered by the console's own Jinja macro (_macros/panels.html), as the view page does on first paint."""
    import jinja2
    env = jinja2.Environment(loader=jinja2.FileSystemLoader(str(ROOT / "console" / "web" / "templates")), autoescape=True)
    return env.from_string('{% from "_macros/panels.html" import panel %}{{ panel(p) }}').render(p=panel)


def showcase():
    return (EXAMPLES / "all-panels-showcase.json").read_text(), (EXAMPLES / "all-panels-showcase.sutra.yaml").read_text()


# ---- running ---------------------------------------------------------------------------------------------------------------------------------

def listening(port: int) -> bool:
    try:
        urllib.request.urlopen(f"http://127.0.0.1:{port}/", timeout=2)
        return True
    except urllib.error.HTTPError:
        return True                  # an HTTP error still means something answers (and it is an OSError, so it must be caught first)
    except OSError:
        return False


def start_servers(work: Path) -> list:
    jars = sorted((ROOT / "drishti-server" / "target").glob("drishti-server-*-exec.jar"))
    if not jars:
        sys.exit("build the server first: ./mvnw -o package -DskipTests -pl drishti-server -am")
    java = os.environ.get("JAVA_HOME", "/usr/lib/jvm/java-25-openjdk-amd64") + "/bin/java"
    for name in ("packs", "config"):
        if not (work / name).exists() and (ROOT / name).exists():
            (work / name).symlink_to(ROOT / name)
    (work / "sutras").mkdir(exist_ok=True)
    env = dict(os.environ, DRISHTI_PORT=str(SERVER_PORT), DRISHTI_PACKS=PACKS, DRISHTI_STUDIO_SAVE="true", DRISHTI_BUILDER_FILE_BINDING="true",
               DRISHTI_SUTRAS=str(work / "sutras"))
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
