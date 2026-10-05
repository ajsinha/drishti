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

"""Guide pictures, in general and for the screen designer guide.

Every guide's screenshots are made by tools/docs/screenshots.py (one module per guide in tools/docs/shots/, output in docs/guides/img/<guide>/, or
docs/connectors/img/connectors/): a guide that shows a picture that is not there fails here, so does a picture no guide shows, and so does a picture
the script cannot make. Then the screen designer guide (docs/guides/SCREEN_DESIGNER.md): in the help centre, F1 on every Build page, linked from
the Build menu, QUICKSTART and SCREEN_BUILDER.md."""
from pathlib import Path
import re
import subprocess
import sys

import pytest

from conftest import CONSOLE

ROOT = CONSOLE.parent
DOCS = ROOT / "docs"
GUIDE = DOCS / "guides" / "SCREEN_DESIGNER.md"
IMG = DOCS / "guides" / "img" / "designer"
SHOTS = ROOT / "tools" / "docs" / "screenshots.py"
LIB = ROOT / "tools" / "docs" / "shotlib.py"
PICTURE = re.compile(r"!\[[^\]]*\]\((img/[^)\s]+)\)")
SECTIONS = ["Start: bring data", "The workbench at a glance", "The Data pane", "The canvas and the grid", "Add a panel", "Drop a field", "Auto-design",
            "The inspector", "The YAML tab", "Problems", "Tests as you type", "Preview with a file", "Undo, redo", "Phone width", "Keyboard reference",
            "Worked examples", "Troubleshooting", "Limits", "Studio users: where things went", "The command palette", "Diff and versions",
            "Saving and proposing", "End to end", "Pack fragments", "Sharing a design read-only", "The command line and CI", "Binding a design to a file",
            "Troubleshooting shipping", "Limits of shipping", "Keyboard reference for shipping"]
FAMILIES = ["all-panels-showcase", "tree-table", "pivot-row-groups", "exposure-profile", "market-charts", "risk-distribution", "pnl-explain", "relationships",
            "operations-status"]


def pictures():
    return re.findall(r"!\[[^\]]*\]\((img/designer/[^)\s]+)\)", GUIDE.read_text())




def pictures_of(doc: Path) -> list:
    return PICTURE.findall(doc.read_text())


def image_dirs() -> list:
    """Every directory of generated pictures: docs/<guides|connectors>/img/<guide>/."""
    return sorted(p for p in DOCS.glob("*/img/*") if p.is_dir())


def shown_in(directory: Path) -> set:
    """The files of one picture directory that some document under docs/ shows."""
    out = set()
    for doc in DOCS.rglob("*.md"):
        for rel in pictures_of(doc):
            f = (doc.parent / rel).resolve()
            if f.parent == directory.resolve():
                out.add(f.name)
    return out


def test_every_picture_any_guide_shows_exists_and_is_a_real_image():
    count = 0
    for doc in DOCS.rglob("*.md"):
        for rel in pictures_of(doc):
            f = doc.parent / rel
            count += 1
            assert f.is_file(), f"{doc.relative_to(ROOT)} shows {rel}, which is missing: drishti-console/.venv/bin/python tools/docs/screenshots.py --guide <guide>"
            head = f.read_bytes()[:4]
            assert (head[:3] == b"\xff\xd8\xff" or head == b"\x89PNG") and f.stat().st_size > 2000, rel
    assert count >= 25


def test_no_generated_picture_is_left_over_that_no_guide_shows():
    dirs = image_dirs()
    assert {d.name for d in dirs} >= {"designer", "panels"}
    for d in dirs:
        files = {p.name for p in d.iterdir() if p.is_file()}
        assert files == shown_in(d), f"{d.relative_to(ROOT)}: unused {sorted(files - shown_in(d))}, missing {sorted(shown_in(d) - files)}"


@pytest.mark.parametrize("guide", sorted(p.stem for p in (ROOT / "tools" / "docs" / "shots").glob("*.py")))
def test_the_screenshot_script_makes_every_picture_of_its_guide(guide):
    out = subprocess.run([sys.executable, str(SHOTS), "--list", "--guide", guide], capture_output=True, text=True, timeout=60)
    assert out.returncode == 0, out.stderr
    made = set(out.stdout.split())
    dirs = [d for d in image_dirs() if d.name == guide]
    assert dirs, f"no picture directory for the guide {guide}"
    for d in dirs:
        assert {p.name for p in d.iterdir() if p.is_file()} <= made, "a picture the script no longer makes: " + guide
        assert shown_in(d) <= made, f"{guide}: shown but not made by tools/docs/shots/{guide}.py: {sorted(shown_in(d) - made)}"


def test_the_screenshot_script_refuses_the_users_ports_and_uses_the_scratch_ones():
    text = LIB.read_text()
    assert "18997" in text and "17997" in text and "FORBIDDEN = {18480, 17480}" in text
    bad = subprocess.run([sys.executable, str(SHOTS), "--base", "http://127.0.0.1:17480", "--guide", "panels"], capture_output=True, text=True, timeout=60)
    assert bad.returncode != 0 and "refusing" in (bad.stderr + bad.stdout)


def test_every_picture_the_designer_guide_shows_exists():
    shown = pictures()
    assert len(shown) >= 25, "the guide shows each step: " + str(len(shown))
    assert {p.name for p in IMG.glob("*.jpg")} == {Path(r).name for r in shown}


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
    assert client.get("/help/files/guides/img/%2e%2e/%2e%2e/%2e%2e/drishti-console/web/static/img/guide/layout-mode.png").status_code == 404


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
        assert f'data-example-copy="{name}"' in page, name
