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

"""SdkBuildsGenomicsTest: the Module SDK folder is a complete kit. The genomics module (the SDK's own example) is copied beside the SDK's
parent pom into a scratch folder with NO kernel source, and built there with Maven against the SDK's file repository (``build sdk``): its
tests run, ``module pack`` makes the .dmod and the fourteen conformance checks pass. Heavy (a Maven build of the SDK and of the module):
one at a time, tools lane. Needs the server jar in lib/ (the verifier), a JDK and a Maven that can run offline."""
from __future__ import annotations

import re
import shutil
import subprocess
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
sys.path.insert(0, str(ROOT / "tools" / "launch"))
import drishti  # noqa: E402
import envsetup  # noqa: E402

GENOMICS = ROOT / "modules" / "genomics"


def _verifier_built() -> bool:
    try:
        drishti.find_jar(None)
        drishti.find_java(None)
        return True
    except Exception:
        return False


_GATE_JARS = (("kernel/drishti-api", "drishti-api"), ("kernel/drishti-common", "drishti-common"),
              ("kernel/drishti-kernel-api", "drishti-kernel-api"), ("testing/drishti-testkit", "drishti-testkit"))


def _version() -> str:
    return (ROOT / ".mvn" / "maven.config").read_text(encoding="utf-8").split("-Drevision=")[1].split()[0]


def _no_build_output(src: str, names: list[str]) -> set[str]:
    return {n for n in names if n in ("target", "dist", "build")}


@pytest.mark.parametrize("way", ["parent-folder", "settings-file"])
def test_a_module_builds_from_the_sdk_folder_alone(tmp_path, way):
    if not _verifier_built():
        pytest.skip("needs the server jar in lib/ (python tools/drishti.py build jar) and a JDK")
    sdk = tmp_path / "sdk"                                       # nothing at the repository root, no Maven in the working tree when the gate's jars are there
    jars_there = all((ROOT / rel / "target" / f"{name}-{_version()}.jar").is_file() for rel, name in _GATE_JARS)
    built = subprocess.run([sys.executable, str(ROOT / "tools" / "drishti.py"), "build", "sdk", "--offline", "--out", str(sdk), *(["--no-maven"] if jars_there else [])],
                           cwd=ROOT, capture_output=True, text=True)
    assert built.returncode == 0, built.stdout + built.stderr
    assert (sdk / "repo" / "com" / "ash" / "drishti" / "drishti-kernel-api").is_dir()
    assert (sdk / "drishti-module-parent" / "pom.xml").is_file()

    # the scratch folder holds the SDK's parent and the module and nothing of the kernel: the layout the module's own pom names
    work = tmp_path / "work"
    shutil.copytree(sdk / "drishti-module-parent", work / "drishti-sdk" / "drishti-module-parent")
    shutil.copytree(GENOMICS, work / "modules" / "genomics", ignore=_no_build_output)
    project = work / "modules" / "genomics" / "java" / "genomics"
    version = (ROOT / ".mvn" / "maven.config").read_text(encoding="utf-8").split("-Drevision=")[1].split()[0]
    local, tail = tmp_path / "m2", Path.home() / ".m2" / "repository"          # nothing of the kernel reaches ~/.m2: reads fall back to it
    cmd = [*envsetup.maven_command(ROOT), "-B", f"-Dmaven.repo.local={local}", f"-Dmaven.repo.local.tail={tail}",
           f"-Ddrishti.home={ROOT}", f"-Ddrishti.sdk.repo={sdk / 'repo'}", f"-Ddrishti.python={sys.executable}", "verify"]
    if way == "parent-folder":        # the sample's own pom: the parent by relativePath into the SDK folder, the version the one ${revision}
        cmd.insert(2, f"-Drevision={version}")
    else:                             # a partner's pom: the parent by version only, found through the settings file `build sdk` wrote
        pom = project / "pom.xml"
        pom.write_text(re.sub(r"<relativePath>.*?</relativePath>", "<relativePath/>", pom.read_text(encoding="utf-8")).replace("<version>${revision}</version>", f"<version>{version}</version>"), encoding="utf-8")
        shutil.rmtree(work / "drishti-sdk")
        cmd[2:2] = ["-s", str(sdk / "settings.xml")]
    r = subprocess.run(cmd, cwd=project, capture_output=True, text=True)
    assert r.returncode == 0, r.stdout[-6000:] + r.stderr[-2000:]
    dmod = project / "target" / "dmod" / "genomics-0.1.0.dmod"
    assert dmod.is_file()
    ok = drishti.run_module_cli(type("A", (), {})(), ["verify", str(dmod), "--conformance", "--project", str(project)], capture=True)
    assert ok.returncode == 0, ok.stdout + ok.stderr
    assert "all 14 conformance checks passed" in ok.stdout
