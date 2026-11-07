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
"""Drishti Elements (static/elements/): the libraries' unit tests in Node (tests/elements/*.test.mjs, node --test: fast, no browser), the
bundles and generated modules current (tools/elements/build.py --check), the API reference current and every public symbol typed
(tools/docs/elements_api.py --check), our file names prefixed, and an exported workbook a real xlsx (a zip of well-formed parts)."""
import io
import json
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path
from xml.etree import ElementTree

import pytest

from conftest import CONSOLE

pytestmark = pytest.mark.static   # a quick file check: the drill's gate runs it (TEST_HARNESS.md, "Quick file checks")

ROOT = CONSOLE.parent
ELEMENTS = CONSOLE / "web" / "static" / "elements"
NODE = shutil.which("node")


@pytest.mark.skipif(NODE is None, reason="needs Node.js")
def test_the_libraries_unit_tests_pass_in_node():
    files = sorted(str(p) for p in (CONSOLE / "tests" / "elements").glob("*.test.mjs"))
    assert files
    run = subprocess.run([NODE, "--test", *files], capture_output=True, text=True, timeout=300)
    assert run.returncode == 0, run.stdout[-4000:] + run.stderr[-2000:]


def test_the_bundles_and_generated_modules_are_current():
    run = subprocess.run([sys.executable, str(ROOT / "tools" / "elements" / "build.py"), "--check"], capture_output=True, text=True, timeout=120)
    assert run.returncode == 0, run.stdout + run.stderr


def test_the_api_reference_is_current_and_every_public_symbol_is_typed():
    run = subprocess.run([sys.executable, str(ROOT / "tools" / "docs" / "elements_api.py"), "--check"], capture_output=True, text=True, timeout=120)
    assert run.returncode == 0, run.stdout + run.stderr


def test_every_file_of_ours_is_prefixed_and_every_library_is_in_the_manifest():
    manifest = json.loads((ELEMENTS / "drishti_elements.json").read_text(encoding="utf-8"))
    for p in ELEMENTS.rglob("*"):
        if p.is_file():
            assert p.name.startswith("drishti_"), f"{p.relative_to(ELEMENTS)}: our static files are named drishti_<name>"
    libraries = {p.name for p in ELEMENTS.iterdir() if p.is_dir()}
    assert libraries == set(manifest["libraries"]), "every folder is a library of the manifest, and every library has its folder"
    for name, spec in manifest["libraries"].items():
        assert (ELEMENTS / name / spec["entry"]).is_file()
        for css in spec.get("css", []):
            assert (CONSOLE / "web" / "static" / css).is_file(), f"{name} needs {css}"


@pytest.mark.skipif(NODE is None, reason="needs Node.js")
def test_an_exported_workbook_is_a_real_xlsx():
    grid = ELEMENTS / "drishti-grid" / "drishti_grid.js"
    code = ("const G = require(%s); const t = {columns: [{header: 'Desk', type: 'text'}, {header: 'Notional', type: 'number', format: 'amount2'},"
            " {header: 'Day', type: 'date'}], rows: [['RATES <NY> & co', 1234.5, '2026-11-06'], ['FX', -2, '2026-11-07']], levels: [0, 1], bold: [true, false]};"
            " process.stdout.write(Buffer.from(G.toXlsx(t, {sheetName: 'Trades'})).toString('base64'));") % json.dumps(str(grid))
    run = subprocess.run([NODE, "-e", code], capture_output=True, text=True, timeout=60, check=True)
    import base64
    z = zipfile.ZipFile(io.BytesIO(base64.b64decode(run.stdout)))
    assert z.testzip() is None
    names = set(z.namelist())
    assert {"[Content_Types].xml", "_rels/.rels", "xl/workbook.xml", "xl/_rels/workbook.xml.rels", "xl/worksheets/sheet1.xml", "xl/styles.xml"} <= names
    for n in names:
        ElementTree.fromstring(z.read(n))                                     # every part is well-formed XML
    ns = {"m": "http://schemas.openxmlformats.org/spreadsheetml/2006/main"}
    sheet = ElementTree.fromstring(z.read("xl/worksheets/sheet1.xml"))
    rows = sheet.findall(".//m:sheetData/m:row", ns)
    assert len(rows) == 3
    assert rows[2].get("outlineLevel") == "1"
    cells = rows[1].findall("m:c", ns)
    assert cells[0].find(".//m:t", ns).text == "RATES <NY> & co"
    assert cells[1].find("m:v", ns).text == "1234.5" and cells[1].get("t") is None
    assert ElementTree.fromstring(z.read("xl/workbook.xml")).find(".//m:sheet", ns).get("name") == "Trades"
