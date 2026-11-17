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

"""Guards G5 and G7 of the modules beside the server jar (docs/architecture/KERNEL_AND_MODULES.md section 3.3; i7a plan A3, C1).

G5: ONE server jar (its SHA-256 is the same at every start) and a modules folder that changes between restarts of one data folder: the
genomics module at 0.1.0, then replaced by 0.1.1, then taken away. What runs is what the folder holds at that start, never an older
copy kept from an earlier one. G7: an empty modules folder starts a server that is healthy and says it has no module. No browser; the
servers are started and stopped with the shipped commands on scratch ports and a scratch data folder (not the owner's stack).

    python3 -m pytest -q drishti-console/tests/test_modules_beside_jar.py
"""
from __future__ import annotations

import hashlib
import os
import shutil
import socket
import subprocess
import sys
import urllib.request
import zipfile
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
import javacmd  # noqa: E402
import modulepack  # noqa: E402


def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class Node:
    """One server over a modules folder and a data folder of its own, started and stopped with drishti.py."""

    def __init__(self, work: Path, modules: Path):
        self.data, self.modules = work / "data", modules
        self.port = free_port()
        self.env = {k: v for k, v in os.environ.items() if not k.startswith("DRISHTI_")}
        self.env.update(DRISHTI_MODULES_HOME=str(modules), DRISHTI_DEMO_ENABLED="false", DRISHTI_OFFLINE="true", DRISHTI_PACKS="core",
                        DRISHTI_PORT=str(self.port))
        self.cli = [sys.executable, str(ROOT / "tools" / "drishti.py")]

    def start(self) -> str:
        """Starts the server; the new lines of its log (what this start said)."""
        log = self.data / "logs" / "server.log"
        before = log.stat().st_size if log.is_file() else 0
        r = subprocess.run([*self.cli, "start", "server", "--port", str(self.port), "--data-dir", str(self.data), "--background", "--wait", "400", "--heap", "768m"],
                           cwd=str(ROOT), env=self.env, capture_output=True, text=True, timeout=600)
        assert r.returncode == 0, r.stdout[-1500:] + r.stderr[-1500:]
        with open(log, encoding="utf-8", errors="replace") as f:
            f.seek(before)
            return f.read()

    def stop(self) -> None:
        subprocess.run([*self.cli, "stop", "server", "--data-dir", str(self.data)], cwd=str(ROOT), env=self.env, capture_output=True, text=True, timeout=180)

    def get(self, path: str) -> str:
        with urllib.request.urlopen(f"http://127.0.0.1:{self.port}{path}", timeout=30) as r:
            return r.read().decode()

    def loaded(self) -> dict[str, bool]:
        import json
        return {m["id"]: m["loaded"] for m in json.loads(self.get("/api/v1/admin/modules"))["modules"]}


@pytest.fixture()
def work(tmp_path):
    yield tmp_path
    # every server is stopped by the test that started it (by pid file); nothing else to clean: tmp_path goes with pytest


def _genomics_package(tmp: Path, version: str) -> Path:
    """genomics at `version`: the shipped package's jar and the module folder with its version changed, packed by the one packer."""
    shipped = ROOT / "modules" / "genomics-0.1.0.dmod"
    if not shipped.is_file():
        pytest.fail("no genomics package in modules/ (python tools/drishti.py build modules)")
    folder = tmp / f"genomics-{version}-src"
    shutil.copytree(ROOT / "modules" / "genomics", folder, ignore=shutil.ignore_patterns("target", "__pycache__"))
    text = (folder / "module.yaml").read_text(encoding="utf-8")
    assert "version: 0.1.0" in text
    (folder / "module.yaml").write_text(text.replace("version: 0.1.0", f"version: {version}", 1), encoding="utf-8")
    jars = tmp / "jars"
    jars.mkdir(exist_ok=True)
    with zipfile.ZipFile(shipped) as z:
        names = [n for n in z.namelist() if n.startswith("java/") and n.endswith(".jar") and n.count("/") == 1]
        for n in names:
            (jars / Path(n).name).write_bytes(z.read(n))
    out = tmp / f"pack-{version}"
    dmod, _ = modulepack.pack(folder, sorted(jars.glob("*.jar")), out)
    return dmod


def place(modules: Path, dmod: Path | None) -> None:
    """The folder as `build modules` leaves it: genomics at this package (or none) and first-party.yaml written for what is there."""
    for old in modules.glob("genomics-*.dmod"):
        old.unlink()
    if dmod is not None:
        shutil.copy2(dmod, modules / dmod.name)
    modulepack.first_party(modules)


def _jar_hash() -> str:
    return hashlib.sha256(javacmd.server_jar().read_bytes()).hexdigest()


def test_G5_the_same_jar_runs_the_modules_the_folder_holds_at_each_start(work):
    modules = work / "modules"
    modules.mkdir()
    first = _genomics_package(work, "0.1.0")
    second = _genomics_package(work, "0.1.1")
    jar = _jar_hash()
    node = Node(work, modules)
    try:
        # 0.1.0
        place(modules, first)
        said = node.start()
        assert "module genomics 0.1.0 loaded" in said and node.loaded().get("genomics") is True
        node.stop()
        # replaced by 0.1.1: the old file goes, the new one comes
        place(modules, second)
        said = node.start()
        assert "module genomics 0.1.1 loaded" in said and "genomics 0.1.0 loaded" not in said, said[-1500:]
        assert node.loaded().get("genomics") is True
        node.stop()
        # taken away
        place(modules, None)
        said = node.start()
        assert "module genomics 0.1" not in said, said[-1500:]
        assert node.loaded().get("genomics") is not True, "a module the folder no longer holds still runs"
    finally:
        node.stop()
    assert _jar_hash() == jar, "the server jar changed under the test"


def test_G7_an_empty_modules_folder_starts_a_healthy_server_with_no_module(work):
    modules = work / "modules"
    modules.mkdir()
    node = Node(work, modules)
    try:
        said = node.start()
        assert "Modules: none" in said, said[-1500:]
        assert '"status":"UP"' in node.get("/actuator/health").replace(" ", "")
        assert [i for i, on in node.loaded().items() if on] == ["core"], node.loaded()
    finally:
        node.stop()
