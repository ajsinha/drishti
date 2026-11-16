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

The pictures themselves are made by tools/docs/screenshots.py (one script per picture folder, tools/docs/shots/tNN.py); test_guide_images.py already fails for a picture no document shows or
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
    explicit = set(re.findall(r'<a\s+(?:id|name)="([^"]+)"', text))      # a page's own anchors (the sample pages' one per kind)
    found, seen = set(), {}
    for h in HEADING.findall(text):                          # repeats numbered as GitHub does: name, name-1, name-2
        s = base = slug(h)
        while s in seen:
            seen[base] += 1
            s = f"{base}-{seen[base]}"
        seen[s] = 0
        found.add(s)
    return found | explicit


# the documents that hold a tutorial as one of their parts, showing the pictures under docs/tutorials/img/
GUIDES = ROOT / "docs" / "guides"
SHOWING = [GUIDES / f"{g}.md" for g in ("VAR_GUIDE", "EXPOSURE_GUIDE", "FRTB_SA_GUIDE", "SIMM_GUIDE", "WORLD_DATA_GUIDE", "BUILD_GUIDE")] + sorted((GUIDES / "bi").glob("*.md"))


TRACKS = ["Start", "Risk engines", "BI and analysis", "Data in and out", "Build", "Operate"]


def test_the_written_tutorials_are_the_ones_kept_and_the_index_names_every_one_once_in_a_track():
    # One per major feature, numbered in track order; the risk measures', BI's, the world data's and the source systems' tutorials are parts of
    # their one document (VAR_GUIDE, EXPOSURE_GUIDE, FRTB_SA_GUIDE, SIMM_GUIDE, the BI folder's pages, WORLD_DATA_GUIDE, BUILD_GUIDE), so their numbers are gone.
    numbers = [1, *range(10, 13), *range(15, 18), *range(19, 23)]
    assert [p.name[:2] for p in WRITTEN] == [f"{i:02d}" for i in numbers]
    index = (TUTORIALS / "README.md").read_text()
    rows = re.findall(r"^\|\s*(\d{1,2})\s*\|", index, re.M)
    assert sorted(int(r) for r in rows) == numbers and len(rows) == len(set(rows)), rows      # each once
    assert re.findall(r"^### (.+)$", index.split("## The tutorials")[1].split("## What you need once")[0], re.M)[:len(TRACKS)] == TRACKS
    assert not re.search(r"\b(thirty|forty|\d\d) (hands-on )?tutorials\b", index, re.I)   # counts are computed, never typed
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
    if "*(screenshot filled in the heavy run" in doc.read_text():
        pytest.skip(f"{doc.name}: its screenshots are taken in the heavy run (tools/docs/screenshots.py), then this marker goes")
    shown = IMAGE.findall(doc.read_text())
    assert len(shown) >= 5, f"{doc.name} shows only {len(shown)} pictures"
    for rel in shown:
        f = (doc.parent / rel).resolve()
        assert f.is_file(), f"{doc.name}: {rel} is missing: drishti-console/.venv/bin/python tools/docs/screenshots.py --guide {f.parent.name}"
        head = f.read_bytes()[:4]
        assert (head[:3] == b"\xff\xd8\xff" or head == b"\x89PNG") and f.stat().st_size > 2000, rel


def test_every_picture_file_under_the_tutorials_is_shown_by_a_tutorial():
    shown = {(d.parent / rel).resolve() for d in [*WRITTEN, *SHOWING] for rel in IMAGE.findall(d.read_text())}
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


def _guide_tutorial_parts() -> list:
    """The tutorial parts held inside guides and guide-folder pages: the text from a `## Tutorial` heading to the next `## ` heading that is not one
    (a guide's tutorial is its `## Tutorial: ...` section; its steps are the `### Step` / `## Step` headings)."""
    out = []
    for doc in sorted([*GUIDES.glob("*.md"), *GUIDES.glob("*/*.md")]):
        text = doc.read_text(encoding="utf-8")
        if re.search(r"^## Tutorial\b", text, re.M):
            out.append(doc)
    return out


@pytest.mark.parametrize("doc", _guide_tutorial_parts(), ids=lambda p: p.relative_to(GUIDES).as_posix())
def test_every_step_of_a_tutorial_inside_a_guide_ends_with_what_you_should_see(doc):
    """The structure check of the tutorials that are parts of guides or of a guide folder's pages (VAR, BI and the rest): every step says what
    the reader should see, and the part has its time, its set-up and what it teaches."""
    text = re.sub(r"```.*?```", lambda m: re.sub(r"(?m)^#", "\u200b#", m.group(0)), doc.read_text(encoding="utf-8"), flags=re.S)
    tut = re.split(r"^## Tutorial\b", text, maxsplit=1, flags=re.M)[1]
    for part in ("Time to complete", "Before you start", "What you will learn"):
        assert part in tut or doc.parent.name == "bi" and "Before you start" in text, f"{doc.name}: its tutorial has no '{part}'"
    steps = re.split(r"^#{2,4} Step \d+[a-z]?\b", tut, flags=re.M)[1:]
    assert steps, f"{doc.name}: a tutorial part with no steps"
    for step in steps:
        body = re.split(r"^## ", step, maxsplit=1, flags=re.M)[0]
        assert "You should see" in body or "you should see" in body, f"{doc.name}: a step without 'You should see': {step[:60]!r}"


@pytest.mark.parametrize("doc", WRITTEN, ids=lambda p: p.name)
def test_no_tutorial_leaks_the_authors_scratch_folders_or_a_token(doc):
    text = doc.read_text()
    # an absolute scratch path; a relative one the reader's own data folder holds (data/tmp/server) is fine
    assert not re.search(r"(?<![\w.~-])/tmp/", text) and "/home/" not in text and "claude" not in text.lower(), doc.name
    assert not re.search(r"drk_[A-Za-z0-9_-]{20,}", text), doc.name


def test_the_pictures_of_the_later_tutorials_are_made_by_the_script_and_shown_at_a_phone_width_where_it_matters():
    """The pictures in the folders t10 to t19, whichever tutorial shows them, are listed by the picture script (guides t10 to t19), and the
    tutorials about reports and embedding show a phone."""
    listed = []
    for guide in (f"t{n}" for n in range(10, 20)):
        listed += subprocess.run([sys.executable, str(ROOT / "tools" / "docs" / "screenshots.py"), "--list", "--guide", guide], capture_output=True, text=True, cwd=ROOT).stdout.split()
    for doc in WRITTEN:
        for rel in IMAGE.findall(doc.read_text()):
            if rel.startswith("img/t1"):
                assert Path(rel).name in listed, f"{doc.name}: {rel} is not made by tools/docs/screenshots.py"
    for doc in (GUIDES / "bi" / "REPORTS.md", TUTORIALS / "20-embed-drishti.md"):
        assert re.search(r"phone|390 px", doc.read_text()), doc.name


def test_the_sample_data_the_tutorials_use_is_in_the_repository():
    data = TUTORIALS / "data"
    assert len(list((data / "desk-quotes").glob("*/desk-quote.jsonl"))) == 10
    for name in ("schemas/desk-quote.schema.json", "connectors/desk-quotes.yaml", "connectors/desk-ledger.yaml", "desk-book.sutra.yaml", "desks/desk-rates.json"):
        assert (data / name).is_file(), name
    for name in ("slicer-kinds.report.yaml", "trades-daily.dataset.yaml", "smtp-catcher.py", "rates-trades.dataset.yaml", "trades-model.model.yaml", "trades-report.report.yaml", "notional-by-desk.python.yaml",
                 "desk-python.report.yaml", "cp-risk.model.yaml", "cp-risk-view.report.yaml", "cp-risk-extracts.notebook.yaml", "cp-table.python.yaml",
                 "cp-table.dataset.yaml", "ai/mock.yaml", "ai/local-model.yaml", "pools.py", "trades-daily.model.yaml", "desk-check.report.yaml",
                 "live-desk.mq.yaml", "night-desk.report.yaml"):
        assert (data / name).is_file(), name
