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

"""One version, one home: .mvn/maven.config. No other file may carry the product version (a pom version, a jar name, an image tag, the
console's asset version, a document's example); the release tag must be the version's tag. Third-party and library versions, and the
Drishti Elements / Render JS libraries' own versions, are not the product version and are not looked at."""
from __future__ import annotations

import json
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "drishti-console"))
from core import product_version  # noqa: E402

# a file that records history or a measurement made on a past build, never what this build is
HISTORY = {"CHANGELOG.md", "RELEASE_NOTES.md", ".mvn/maven.config", "drishti-console/tests/test_one_version.py"}
HISTORY_PREFIX = (
    "kernel/drishti-server/src/test/resources/capacity/",       # recorded benchmark vectors
    "kernel/drishti-server/src/main/resources/capacity/template/",   # the planner workbook's cached cells; its jar input is filled with the server's version
    "docs/plans/",                                                # the release plans name the releases they plan
    "docs/lenses/mockups/",                                       # mock-ups show example figures
    "drishti-console/tests/answers/",                             # recorded server answers
    "docs/architecture/",                                         # design documents and mock-ups name releases of the plan
)
SKIP_SUFFIX = (".png", ".jpg", ".gif", ".pdf", ".zip", ".jar", ".gz", ".xlsx", ".ico", ".woff", ".woff2", ".parquet")
NS = "{http://maven.apache.org/POM/4.0.0}"
# a hard-coded product version in a place that names the build: the exec jar, an image tag
ARTIFACT = re.compile(r"drishti-(?:server|console|loader)(?:-ecs)?[-:]\d+\.\d+\.\d+")


def tracked() -> list[str]:
    out = subprocess.run(["git", "ls-files", "-z"], cwd=ROOT, capture_output=True, check=True).stdout.decode()
    return [f for f in out.split("\0") if f and not f.endswith(SKIP_SUFFIX) and (ROOT / f).is_file()]


def version() -> str:
    found = product_version.current(ROOT / "drishti-console" / "core" / "x.py", default="")
    assert re.fullmatch(r"\d+\.\d+\.\d+(\.[a-z]\d*[a-z]?)?", found), f"the version file does not hold a version: {found!r}"
    return found


def test_the_tag_is_the_versions():
    assert product_version.tag_of("2.9.11.i5") == "v2.9.11.i_5"
    assert product_version.tag_of("2.9.11.i7a") == "v2.9.11.i_7a"
    assert product_version.tag_of("2.9.11.g") == "v2.9.11.g"
    assert product_version.tag_of("3.0.0") == "v3.0.0"
    tool = ROOT / "tools" / "version.py"
    good = subprocess.run([sys.executable, str(tool), "--check", product_version.tag_of(version())], capture_output=True)
    assert good.returncode == 0, good.stderr
    bad = subprocess.run([sys.executable, str(tool), "--check", "v0.0.1"], capture_output=True)
    assert bad.returncode == 1


def test_every_pom_takes_the_version_from_the_one_file():
    """Every pom of the product (group com.ash.drishti) takes ${revision}; the Elements WebJar carries the Elements train's version, and a
    module of another publisher (org.helixlab's genomics) or a stand-alone example its own."""
    wrong = []
    for f in tracked():
        if not f.endswith("pom.xml") or "archetype-resources" in f:
            continue
        root = ET.parse(ROOT / f).getroot()
        own = root.find(NS + "version")
        parent = root.find(NS + "parent")
        group = root.findtext(NS + "groupId") or (parent.findtext(NS + "groupId") if parent is not None else "")
        if own is not None and own.text != "${revision}" and group == "com.ash.drishti":
            if root.findtext(NS + "artifactId") == "drishti-elements":          # the Elements release train's own version, not the product's
                train = json.loads((ROOT / "drishti-console/web/static/elements/drishti_elements.json").read_text(encoding="utf-8"))["elements"]
                if own.text != train:
                    wrong.append(f"{f}: version {own.text}, but the Elements train (drishti_elements.json) is {train}")
            else:
                wrong.append(f"{f}: version {own.text}")
        if parent is not None and parent.findtext(NS + "groupId") == "com.ash.drishti" and parent.findtext(NS + "version") != "${revision}":
            wrong.append(f"{f}: parent version {parent.findtext(NS + 'version')}")
    assert not wrong, "a pom carries its own version:\n" + "\n".join(wrong)


def test_no_other_file_carries_the_product_version():
    v = version()
    pattern = re.compile(r"(?<![\w.])" + re.escape(v) + r"(?![\w.]*\w)")
    hits = []
    for f in tracked():
        if f in HISTORY or f.startswith(HISTORY_PREFIX):
            continue
        fixture = "/src/test/" in f or "/tests/" in f or Path(f).name.startswith("test_")   # a test's own made-up versions
        for n, line in enumerate((ROOT / f).read_text(encoding="utf-8", errors="replace").splitlines(), 1):
            if pattern.search(line) or (not fixture and ARTIFACT.search(line)):
                hits.append(f"{f}:{n}: {line.strip()[:120]}")
    assert not hits, "a hard-coded product version (read it from .mvn/maven.config, or write <version> in a document):\n" + "\n".join(hits)
