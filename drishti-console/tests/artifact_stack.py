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

"""A real scratch stack for the Admin → Artifacts browser test and the documentation pictures: the built server jar on :18936 and the console on :17936
(sign-in off, so the page opens as the development administrator), over a scratch site (the server runs in its folder, so ``./data`` is the site's own data folder) that holds exactly what the page needs to show:

* the pack ``lab-core`` named in ``DRISHTI_PACKS`` (a *configured* pack: it can be disabled here, not uninstalled), and three packs installed from archives:
  ``lab-base`` (kind ``lab-item``), ``lab-child`` which extends it (kind ``lab-part``), and ``lab-solo`` which nothing depends on;
* the connector ``lab-files`` (a folder of JSON Lines read by ``lab-base``) and ``scratch-conn``, which nothing uses;
* BI files in a scratch ``config/bi``: the dataset ``lab-items`` over the connector, the report ``lab-overview`` on it, and the Python item ``lab-spare``, which nothing uses.

Everything lives in the scratch directory; ports that are in use are never touched (the stack refuses to start)."""
from __future__ import annotations

import glob
import os
import subprocess
import sys
from pathlib import Path

from bi_stack import FORBIDDEN, listening, wait

ROOT = Path(__file__).resolve().parents[2]
CONSOLE = ROOT / "drishti-console"
SERVER_PORT = int(os.environ.get("DRISHTI_ARTIFACTS_SERVER_PORT", 18936))
CONSOLE_PORT = int(os.environ.get("DRISHTI_ARTIFACTS_CONSOLE_PORT", 17936))
SERVER = f"http://127.0.0.1:{SERVER_PORT}"
CONSOLE_URL = f"http://127.0.0.1:{CONSOLE_PORT}"

PACKS = {
    "lab-core": "pack: lab-core\nversion: 1.0.0\ntitle: Lab core\ndescription: The pack the site always loads.\nkinds: [lab-core-thing]\n",
    "lab-base": ("pack: lab-base\nversion: 1.2.0\ntitle: Lab base\ndescription: Lab items read from the lab-files connector.\nkinds: [lab-item]\n"
                 "connectors: [lab-files]\nroutes:\n  lab-item: lab-files\n"),
    "lab-child": "pack: lab-child\nversion: 1.0.0\ntitle: Lab child\ndescription: Parts of lab items.\nextends: [lab-base]\nkinds: [lab-part]\n",
    "lab-solo": "pack: lab-solo\nversion: 2.1.0\ntitle: Lab solo\ndescription: A pack nothing depends on.\nkinds: [lab-solo-thing]\n",
}
DATASET = """rachana: 1
dataset: lab-items
title: Lab items
tables:
  item:
    source: { connector: lab-files, kind: lab-item }
    mode: cached
    columns:
      itemId: { type: string, key: true }
      weight: decimal
measures:
  Weight: { expr: "sum(item.weight)", format: amount0 }
"""
REPORT = """rachana: 1
report: lab-overview
dataset: lab-items
title: Lab overview
pages:
  - id: overview
    title: Overview
    visuals:
      - { id: weight, type: card, title: Weight, at: { x: 0, y: 0, w: 3, h: 2 }, wells: { values: [Weight] } }
"""
SPARE = "rachana: 1\npython: lab-spare\ntitle: Lab spare\ndescription: A Python item nothing uses.\nsample: true\ninterface: visual\noutput: echarts\ninputs: [weight]\n"


def build(work: Path) -> Path:
    """Writes the scratch site under ``work``; returns the packs folder the server reads."""
    packs = work / "packs"
    installed = work / "data" / "packs" / "installed"
    packs.mkdir(parents=True)
    installed.mkdir(parents=True)
    for name, text in PACKS.items():
        folder = (packs if name == "lab-core" else installed) / name
        folder.mkdir()
        (folder / "pack.yaml").write_text(text, encoding="utf-8")
    (work / "data" / "packs" / "added.yaml").write_text("drishti:\n  packs:\n    added:\n      - lab-base\n      - lab-child\n      - lab-solo\n", encoding="utf-8")
    lake = work / "lake" / "2026-10-12"
    lake.mkdir(parents=True)
    (lake / "lab-item.jsonl").write_text("".join(f'{{"itemId": "L-{i}", "weight": {i * 3}}}\n' for i in range(1, 6)), encoding="utf-8")
    conns = work / "connectors"
    conns.mkdir()
    (conns / "lab-files.yaml").write_text(f"plugin: file\nkinds: [lab-item]\ndescription: The lab's drop folder\nsettings:\n  root: {work / 'lake'}\n", encoding="utf-8")
    (conns / "scratch-conn.yaml").write_text(f"plugin: file\nkinds: [scratch-thing]\ndescription: A scratch connector nothing uses\nsettings:\n  root: {work / 'scratch'}\n", encoding="utf-8")
    bi = work / "bi"
    for rel, text in (("datasets/lab-items.dataset.yaml", DATASET), ("reports/lab-overview.report.yaml", REPORT), ("python/lab-spare.python.yaml", SPARE)):
        (bi / rel).parent.mkdir(parents=True, exist_ok=True)
        (bi / rel).write_text(text, encoding="utf-8")
    return packs


class Stack:
    def __init__(self, tmp: Path):
        self.tmp, self.procs, self.work = tmp, {}, tmp / "site"

    def start(self) -> "Stack":
        try:
            return self._start()
        except BaseException:
            self.close()                                          # never leave a half-started stack listening
            raise

    def _start(self) -> "Stack":
        for port in (SERVER_PORT, CONSOLE_PORT):
            assert port not in FORBIDDEN and not listening(port), f"port {port} is in use: stop whatever listens there"
        jars = sorted(glob.glob(str(ROOT / "drishti-server/target/drishti-server-*-exec.jar")), key=os.path.getmtime, reverse=True)
        java = os.environ.get("JAVA_HOME", "/usr/lib/jvm/java-21-openjdk-amd64") + "/bin/java"
        if not jars or not Path(java).exists():
            raise FileNotFoundError("needs the built server: ./mvnw -o -q package -DskipTests -pl drishti-server -am")
        packs = build(self.work)
        env = dict(os.environ, DRISHTI_PORT=str(SERVER_PORT), DRISHTI_PACKS="lab-core", DRISHTI_PACKS_DIR=str(packs), DRISHTI_CONNECTORS_DIR=str(self.work / "connectors"),
                   DRISHTI_BI_DIR=str(self.work / "bi"), DRISHTI_DEMO_ENABLED="false", DRISHTI_BI_STORE=str(self.tmp / "store"))
        self.procs["server"] = subprocess.Popen([java, "-Xmx768m", "-jar", jars[0]], cwd=self.work, env=env, stdout=open(self.tmp / "server.log", "ab"), stderr=subprocess.STDOUT)
        wait(SERVER + "/actuator/health", 150)
        cenv = dict(os.environ, DRISHTI_BACKEND_URL=SERVER, DRISHTI_CONSOLE_PORT=str(CONSOLE_PORT), DRISHTI_PACKS_DIR=str(packs))
        self.procs["console"] = subprocess.Popen([sys.executable, str(CONSOLE / "run_drishti_web.py")], cwd=CONSOLE, env=cenv, stdout=open(self.tmp / "console.log", "ab"),
                                                 stderr=subprocess.STDOUT)
        wait(CONSOLE_URL + "/healthz")
        return self

    def close(self) -> None:
        for p in self.procs.values():
            p.terminate()
        for p in self.procs.values():
            try:
                p.wait(20)
            except subprocess.TimeoutExpired:
                p.kill()
