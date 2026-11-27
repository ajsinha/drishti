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
"""Drishti Elements is an external component (github.com/drishti-platform/drishti-elements), vendored as its release tree under
static/vendor/drishti-elements/<version>/ (docs/architecture/THIN_CONSOLE_DESIGN.md). The pin has one home, the folder's name: exactly one
folder, its manifest says the same version, the Maven property that reads its two tables says it too; nobody edits the copy (every file
matches the SHA-384 of its manifest, nothing added); Drishti keeps no second copy of what moved; the host script registers Drishti's own
libraries as their manifest says."""
import base64
import hashlib
import json
import re
from pathlib import Path

import pytest

from core import elements_pin

pytestmark = pytest.mark.static   # a quick file check: the drill's gate runs it

ROOT = Path(__file__).resolve().parents[2]
STATIC = ROOT / "drishti-console" / "web" / "static"
TREE = elements_pin.ROOT
MANIFEST = json.loads((TREE / "drishti-elements.json").read_text(encoding="utf-8"))


def test_one_pinned_folder_its_manifest_and_the_maven_property_say_the_same_version():
    assert [p.name for p in elements_pin.VENDOR.iterdir()] == [elements_pin.VERSION]
    assert MANIFEST["version"] == elements_pin.VERSION and (TREE / "LICENSE").is_file()
    prop = re.search(r"<drishti\.elements\.version>([^<]+)</drishti\.elements\.version>", (ROOT / "pom.xml").read_text(encoding="utf-8"))
    assert prop and prop.group(1) == elements_pin.VERSION


def test_nobody_edits_the_copy():
    have = {p.relative_to(TREE).as_posix() for p in TREE.rglob("*") if p.is_file() and "__pycache__" not in p.parts}   # its build tool's cache is not the copy
    listed = set(MANIFEST["files"]) | {"drishti-elements.json"}
    assert have == listed, f"added: {sorted(have - listed)[:10]}; missing: {sorted(listed - have)[:10]}"
    bad = [rel for rel, rec in MANIFEST["files"].items()
           if rec["integrity"] != "sha384-" + base64.b64encode(hashlib.sha384((TREE / rel).read_bytes()).digest()).decode()]
    assert not bad, f"changed in the vendored copy (update the pin instead): {bad[:10]}"


MOVED = ("drishti-console/web/static/gallery/", "drishti-console/web/static/vendor/echarts/", "drishti-console/web/static/vendor/echarts-gl/",
         "drishti-console/web/static/vendor/codemirror/", "drishti-console/web/static/vendor/apache-arrow/", "tools/elements/", "tools/maps/",
         "tools/docs/elements_api.py", "tools/docs/elements_catalogue.py", "packaging/drishti-elements/", "docs/architecture/ELEMENTS.md",
         "compute/drishti-rachana/src/main/resources/world-regions.yaml", "compute/drishti-rachana/src/main/resources/formats.yaml") \
    + tuple(f"drishti-console/web/static/elements/{lib}/" for lib in elements_pin.LIBRARIES) \
    + tuple(f"drishti-console/web/static/css/{sheet}" for sheet in elements_pin.CORE_SHEETS)


def test_drishti_keeps_no_second_copy_of_what_moved():
    left = []
    for rel in MOVED:
        p = ROOT / rel
        left += [str(f.relative_to(ROOT)) for f in ([p] if p.is_file() else p.rglob("*") if p.is_dir() else []) if f.is_file() and "__pycache__" not in f.parts]
    assert left == [], left[:20]


def test_the_host_script_registers_drishti_s_libraries_as_the_manifest_says():
    own = json.loads((STATIC / "elements" / "drishti_elements.json").read_text(encoding="utf-8"))["libraries"]
    host = (STATIC / "js" / "drishti_elements-host.js").read_text(encoding="utf-8")
    registered = {m.group(1): json.loads(m.group(2).replace("'", '"')) for m in re.finditer(r"\['(drishti-[a-z]+)', (\[[^\]]*\])\]", host)}
    assert registered == {name: spec["requires"] for name, spec in own.items()}
