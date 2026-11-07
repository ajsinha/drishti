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
"""Drishti Elements (static/elements/), the quick file checks of the gate: the bundles and generated modules current (tools/elements/build.py
--check), the API reference current and every public symbol typed (tools/docs/elements_api.py --check), both run in this process, and our
file names prefixed. The libraries' unit tests in Node and the exported workbook: test_elements_node.py (they start Node)."""
import importlib.util
import json

import pytest

from conftest import CONSOLE

pytestmark = pytest.mark.static   # a quick file check: the drill's gate runs it (TEST_HARNESS.md, "Quick file checks")

ROOT = CONSOLE.parent
ELEMENTS = CONSOLE / "web" / "static" / "elements"


def _tool(name, path):
    spec = importlib.util.spec_from_file_location(name, ROOT / path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def test_the_bundles_and_generated_modules_are_current(capsys):
    rc = _tool("drishti_elements_build", "tools/elements/build.py").main(["--check"])
    assert rc == 0, capsys.readouterr()


def test_the_api_reference_is_current_and_every_public_symbol_is_typed(capsys):
    rc = _tool("drishti_elements_api", "tools/docs/elements_api.py").main(["--check"])
    assert rc == 0, capsys.readouterr()


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
