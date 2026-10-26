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

"""The hands-on tutorials (docs/tutorials/): every picture exists, every relative link and anchor resolves, and each tutorial has the parts a new reader needs.

The pictures themselves are made by tools/docs/screenshots.py (guides t01 to t19); test_guide_images.py already fails for a picture no document shows or
one the script cannot make. This file checks the tutorials as documents."""
import re
import subprocess
import sys
from pathlib import Path

import pytest

from conftest import CONSOLE

ROOT = CONSOLE.parent
TUTORIALS = ROOT / "docs" / "tutorials"
WRITTEN = sorted(TUTORIALS.glob("[0-9][0-9]-*.md"))          # 20 and 21 too (the glob was [01][0-9] and missed them)
LINK = re.compile(r"(?<!!)\[[^\]]*\]\(([^)\s]+)\)")
IMAGE = re.compile(r"!\[[^\]]*\]\(([^)\s]+)\)")
HEADING = re.compile(r"^#{1,6}\s+(.*?)\s*#*\s*$", re.M)


def slug(heading: str) -> str:
    """GitHub's anchor for a heading (what the help centre generates too)."""
    s = re.sub(r"[`*_]", "", heading.strip().lower())
    s = re.sub(r"[^\w\- ]", "", s)
    return s.replace(" ", "-")


def anchors(path: Path) -> set:
    text = re.sub(r"```.*?```", "", path.read_text(), flags=re.S)
    return {slug(h) for h in HEADING.findall(text)}


def test_there_are_twenty_seven_written_tutorials_and_the_index_names_every_one():
    assert [p.name[:2] for p in WRITTEN] == [f"{i:02d}" for i in range(1, 28)]
    index = (TUTORIALS / "README.md").read_text()
    rows = re.findall(r"^\|\s*(\d{1,2})\s*\|", index, re.M)
    assert [int(r) for r in rows] == list(range(1, 28))
    assert "coming" not in index.split("## What you need once")[0].split("## The tutorials")[1]
    for p in WRITTEN:
        assert p.name in index, p.name
        row = next(line for line in index.splitlines() if p.name in line)
        assert "written" in row and f"[" in row, row                      # the index row says it is written and links the tutorial by its title


def test_the_docs_index_links_to_the_tutorials():
    assert "tutorials/README.md" in (ROOT / "docs" / "README.md").read_text()


@pytest.mark.parametrize("doc", sorted(TUTORIALS.glob("*.md")), ids=lambda p: p.name)
def test_every_relative_link_and_anchor_resolves(doc):
    text = re.sub(r"```.*?```", "", doc.read_text(), flags=re.S)
    for target in LINK.findall(text):
        if re.match(r"^(https?:|mailto:)", target):
            continue
        path, _, frag = target.partition("#")
        dest = doc if not path else (doc.parent / path).resolve()
        assert dest.exists(), f"{doc.name}: {target} does not exist"
        if frag and dest.suffix == ".md":
            assert frag in anchors(dest), f"{doc.name}: {target}: no heading gives #{frag}"


@pytest.mark.parametrize("doc", WRITTEN, ids=lambda p: p.name)
def test_every_picture_a_tutorial_shows_is_there_and_is_an_image(doc):
    shown = IMAGE.findall(doc.read_text())
    assert len(shown) >= 5, f"{doc.name} shows only {len(shown)} pictures"
    for rel in shown:
        f = (doc.parent / rel).resolve()
        assert f.is_file(), f"{doc.name}: {rel} is missing: drishti-console/.venv/bin/python tools/docs/screenshots.py --guide {f.parent.name}"
        head = f.read_bytes()[:4]
        assert (head[:3] == b"\xff\xd8\xff" or head == b"\x89PNG") and f.stat().st_size > 2000, rel


def test_every_picture_file_under_the_tutorials_is_shown_by_a_tutorial():
    shown = {(d.parent / rel).resolve() for d in WRITTEN for rel in IMAGE.findall(d.read_text())}
    files = {p.resolve() for p in (TUTORIALS / "img").rglob("*.jpg")}
    own = TUTORIALS.resolve() / "img"
    assert not [p for p in shown if not p.is_file()], f"missing: {sorted(p.name for p in shown if not p.is_file())}"
    shown_here = {p for p in shown if own in p.parents}       # a tutorial may also show a guide's own picture (../admin/img/...)
    assert files == shown_here, f"unused: {sorted(p.name for p in files - shown_here)}; missing: {sorted(p.name for p in shown_here - files)}"


@pytest.mark.parametrize("doc", WRITTEN, ids=lambda p: p.name)
def test_a_tutorial_has_the_parts_a_new_reader_needs(doc):
    text = doc.read_text()
    for part in ("Time to complete", "Before you start", "What you will learn", "Next steps"):
        assert part in text, f"{doc.name} has no '{part}'"
    steps = re.findall(r"^## Step \d+", text, re.M)
    assert len(steps) >= 4, doc.name
    # each step says what the reader should see
    for step in re.split(r"^## Step ", text, flags=re.M)[1:]:
        assert "You should see" in step, f"{doc.name}: a step without 'You should see': {step[:60]!r}"


@pytest.mark.parametrize("doc", WRITTEN, ids=lambda p: p.name)
def test_no_tutorial_leaks_the_authors_scratch_folders_or_a_token(doc):
    text = doc.read_text()
    # an absolute scratch path; a relative one the reader's own data folder holds (data/tmp/server) is fine
    assert not re.search(r"(?<![\w.~-])/tmp/", text) and "/home/" not in text and "claude" not in text.lower(), doc.name
    assert not re.search(r"drk_[A-Za-z0-9_-]{20,}", text), doc.name


def test_the_pictures_of_the_later_tutorials_are_made_by_the_script_and_shown_at_a_phone_width_where_it_matters():
    """Tutorials 10 to 19: every picture is listed by the picture script (guides t10 to t19), and the ones about reports, embedding and governance show a phone."""
    listed = []
    for guide in (f"t{n}" for n in range(10, 20)):
        listed += subprocess.run([sys.executable, str(ROOT / "tools" / "docs" / "screenshots.py"), "--list", "--guide", guide], capture_output=True, text=True, cwd=ROOT).stdout.split()
    for doc in sorted(TUTORIALS.glob("1[0-9]-*.md")):
        for rel in IMAGE.findall(doc.read_text()):
            if rel.startswith("img/t1"):
                assert Path(rel).name in listed, f"{doc.name}: {rel} is not made by tools/docs/screenshots.py"
    for name in ("10-first-bi-report.md", "13-govern-bi.md", "16-embed-drishti.md"):
        assert re.search(r"phone|390 px", (TUTORIALS / name).read_text()), name


def test_the_sample_data_the_tutorials_use_is_in_the_repository():
    data = TUTORIALS / "data"
    assert len(list((data / "desk-quotes").glob("*/desk-quote.jsonl"))) == 10
    for name in ("schemas/desk-quote.schema.json", "connectors/desk-quotes.yaml", "connectors/desk-ledger.yaml", "desk-book.sutra.yaml", "desks/desk-rates.json"):
        assert (data / name).is_file(), name
    for name in ("slicer-kinds.report.yaml", "trades-daily.dataset.yaml", "smtp-catcher.py", "rates-trades.dataset.yaml", "trades-model.model.yaml", "trades-report.report.yaml", "notional-by-desk.python.yaml",
                 "desk-python.report.yaml", "cp-risk.model.yaml", "cp-risk-view.report.yaml", "cp-risk-extracts.notebook.yaml", "cp-table.python.yaml",
                 "cp-table.dataset.yaml", "ai/mock.yaml", "ai/local-model.yaml", "pools.py", "trades-daily.model.yaml", "desk-check.report.yaml",
                 "live-desk.mq.yaml", "night-desk.report.yaml", "world-economics-21.dataset.yaml"):
        assert (data / name).is_file(), name
