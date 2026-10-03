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

"""The screen designer guide (docs/guides/SCREEN_DESIGNER.md): in the help centre, F1 on every Build page, linked from the Build menu,
QUICKSTART and SCREEN_BUILDER.md, and every screenshot it shows exists (they are made by tools/docs/screenshots.py)."""
from pathlib import Path
import re
import subprocess
import sys

from conftest import CONSOLE

ROOT = CONSOLE.parent
GUIDE = ROOT / "docs" / "guides" / "SCREEN_DESIGNER.md"
IMG = ROOT / "docs" / "guides" / "img" / "designer"
SHOTS = ROOT / "tools" / "docs" / "screenshots.py"
SECTIONS = ["Start: bring data", "The workbench at a glance", "The Data pane", "The canvas and the grid", "Add a panel", "Drop a field", "Auto-design",
            "The inspector", "The YAML tab", "Problems", "Tests as you type", "Preview with a file", "Undo, redo", "Phone width", "Keyboard reference",
            "Worked examples", "Troubleshooting", "Limits", "Studio users: where things went", "The command palette", "Diff and versions",
            "Saving and proposing"]
FAMILIES = ["all-panels-showcase", "tree-table", "pivot-row-groups", "exposure-profile", "market-charts", "risk-distribution", "pnl-explain", "relationships",
            "operations-status"]


def pictures():
    return re.findall(r"!\[[^\]]*\]\((img/designer/[^)\s]+)\)", GUIDE.read_text())


def test_every_picture_the_guide_shows_exists_and_is_a_png():
    shown = pictures()
    assert len(shown) >= 25, "the guide shows each step: " + str(len(shown))
    for rel in shown:
        f = GUIDE.parent / rel
        assert f.is_file(), f"{rel} is referenced by SCREEN_DESIGNER.md but missing: run console/.venv/bin/python tools/docs/screenshots.py"
        assert f.read_bytes()[:3] == b"\xff\xd8\xff" and f.stat().st_size > 5000, rel


def test_no_picture_is_left_over_that_the_guide_does_not_show():
    assert {p.name for p in IMG.glob("*.jpg")} == {Path(r).name for r in pictures()}


def test_the_screenshot_script_makes_every_picture_and_refuses_the_users_ports():
    out = subprocess.run([sys.executable, str(SHOTS), "--list"], capture_output=True, text=True, timeout=60)
    assert out.returncode == 0, out.stderr
    made = set(out.stdout.split())
    assert {Path(r).name for r in pictures()} - made == {"06b-panel-added.jpg", "17b-keyboard-bind.jpg"}      # these two are written by the shot before them
    text = SHOTS.read_text()
    assert "18997" in text and "17997" in text and "FORBIDDEN = {18480, 17480}" in text


def test_the_guide_covers_the_whole_workbench_and_has_a_worked_example_per_family():
    text = GUIDE.read_text()
    for s in SECTIONS:
        assert re.search(rf"^## \d+\. {re.escape(s)}", text, re.M), s
    for name in FAMILIES:
        assert f"examples/{name}.md" in text and (ROOT / "docs" / "guides" / "examples" / f"{name}.md").exists(), name
    for kind in ("kv", "status", "table", "pivot", "line", "area", "candlestick", "surface", "histogram", "scatter", "gauge", "waterfall", "hbar", "graph", "links",
                 "tabs", "timeline", "ladder"):
        assert f"`{kind}`" in text or kind in text, kind
    assert "DRS-5007" in text and "Alt + Up/Down" in text and "Shift + Left/Right" in text


def test_the_help_centre_serves_the_guide_with_its_pictures(client):
    page = client.get("/help/screen-designer")
    assert page.status_code == 200 and "Screen designer" in page.text and "data-unavailable" not in page.text
    srcs = re.findall(r'<img[^>]*\bsrc="([^"]+)"', page.text)
    assert len(srcs) == len(pictures()) and all(s.startswith("/help/files/guides/img/designer/") for s in srcs)
    for s in srcs:
        r = client.get(s)
        assert r.status_code == 200 and r.headers["content-type"] == "image/jpeg", s


def test_help_files_serves_pictures_from_docs_and_nothing_else(client):
    assert client.get("/help/files/guides/SCREEN_DESIGNER.md").status_code == 404                     # not a picture
    assert client.get("/help/files/guides/img/designer/nope.png").status_code == 404
    assert client.get("/help/files/../README.md").status_code == 404
    assert client.get("/help/files/guides/img/%2e%2e/%2e%2e/%2e%2e/console/web/static/img/guide/layout-mode.png").status_code == 404


def test_f1_on_every_build_page_opens_the_guide(client):
    assert client.get("/help/context/build", follow_redirects=False).headers["location"] == "/help/screen-designer"
    for url in ("/build", "/build/new"):
        assert 'data-screen="build"' in client.get(url).text, url


def test_the_build_menu_help_centre_quickstart_and_the_architecture_note_link_to_it(client):
    menu = client.get("/build").text
    assert "/help/screen-designer" in menu and "Screen designer guide" in menu
    assert "/help/screen-designer" in client.get("/build/new").text
    assert "SCREEN_DESIGNER.md" in (ROOT / "docs" / "guides" / "QUICKSTART.md").read_text()
    assert "SCREEN_DESIGNER.md" in (ROOT / "docs" / "architecture" / "SCREEN_BUILDER.md").read_text()
    index = client.get("/help").text
    assert "/help/screen-designer" in index


def test_the_examples_page_offers_each_example_in_the_workbench(client):
    page = client.get("/help/examples").text
    for name in FAMILIES:
        assert f'href="/build/new?example={name}"' in page, name
