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
"""Drishti's own Elements libraries (static/elements/: drishti-insight, drishti-renderer, drishti-risk), the quick file checks of the gate:
their bundles current (the vendored Drishti Elements' tools/elements/build.py --root ... --check, run in this process), our file names
prefixed, every module under the line cap. Their unit tests in Node: test_elements_node.py (it starts Node). Drishti Elements itself
(the eight libraries) is an external component, vendored and checked by test_elements_vendored.py."""
import importlib.util
import json

import pytest

from conftest import CONSOLE
from core import elements_pin
from code_cap import CODE_LINE_CAP  # noqa: E402  (tools/code_cap.py: the one line cap of a code file)

pytestmark = pytest.mark.static   # a quick file check: the drill's gate runs it (TEST_HARNESS.md, "Quick file checks")

ROOT = CONSOLE.parent
ELEMENTS = CONSOLE / "web" / "static" / "elements"


def _tool(name, path):
    spec = importlib.util.spec_from_file_location(name, ROOT / path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def test_the_bundles_are_current(capsys):
    build = _tool("drishti_elements_build", (elements_pin.ROOT / "tools" / "elements" / "build.py").relative_to(ROOT))
    rc = build.main(["--root", str(ELEMENTS.parent), "--manifest", str(ELEMENTS / "drishti_elements.json"), "--header", str(ROOT / "config" / "license-header.txt"), "--check"])
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
            assert elements_pin.static_path(css).is_file(), f"{name} needs {css}"   # the console's sheet, or Drishti Elements' (vendored)


#: The size of one Elements module: the one code line cap of every source file (tools/code_cap.py; owner's rule): a module over it is
#: split by what it does, never trimmed of its comments.
MODULE_LINES = CODE_LINE_CAP


def test_every_elements_module_stays_under_the_line_cap():
    over = [f"{p.relative_to(ELEMENTS)}: {n} lines" for p in sorted(ELEMENTS.glob("*/src/*.js"))
            if (n := len(p.read_text(encoding="utf-8").splitlines())) > MODULE_LINES]
    assert not over, f"Elements modules over {MODULE_LINES} lines (split them by what they do): {over}"
