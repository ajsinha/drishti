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

"""No console test builds a lake of its own (docs/architecture/TEST_HARNESS.md, "The cached demo lake"): every demo lake, VaR or exposure
lake, trade-grain lake, feed lake or BI sample lake a test reads comes from tools/drillkit/lake_cache.py, built ONCE per hash of the test
data, generators and loaders (across runs and drills) by the parallel Java loader, and a stack whose server may write gets a copy or its
own write folders (var_stack.own_folders). A drill's browser lane once spent 13 minutes and 110 errors on fixtures that built their lakes
with the Python writer under pytest's temporary folder.

The Python writer (``--engine deltalake``, ``write_deltalake``, tools/samplegen/load_rows.py) is named only where ALLOWED says why."""
import re
from pathlib import Path

import pytest

pytestmark = pytest.mark.static   # a quick file check: the drill's gate runs it (TEST_HARNESS.md, "Quick file checks")

HERE = Path(__file__).resolve().parent

#: the Python writer named on purpose, each with its reason
ALLOWED = {
    "test_admin_lakes.py": "Admin → Lakes' build is the subject: a 31-row synthetic file the test writes, built by lake build (engine auto, the Java loader)",
    "test_admin_lake_maintain.py": "its tiny lake is built inside lake_cache.cached (Java loader); another system's one-row table stands in through the Python writer",
    "bi_report_stack.py": "the BI sample lake's loader (tools/samplegen/load_rows.py) has no Java route: it runs only inside lake_cache.cached",
    "bi_exposure_fixture.py": "the script bi_report_stack's cached build runs (the BI samples' official exposure table)",
    "test_lake_builds_cached.py": "this guard",
}
#: builds a lake: the Python writer, or a lake demo / lake build / feed load of its own
WRITER = re.compile(r"""["']--engine["'],\s*["']deltalake["']|engine\s*=\s*["']deltalake["']|write_deltalake\(|load_rows\.py|load\s*=\s*["']python["']""")
BUILDS = re.compile(r"""["']lake["'],\s*["'](demo|build)["']|build_demo_lakes""")
CACHED = re.compile(r"lake_cache\b|_lake_cache\(|\.cached\(|demo_lake\(|var_lake\(")


def _files():
    return sorted(p for p in HERE.glob("*.py"))


def test_no_test_names_the_python_writer_unless_it_says_why():
    bad = [f"{p.name}:{n}: {line.strip()[:120]}" for p in _files() if p.name not in ALLOWED
           for n, line in enumerate(p.read_text(encoding="utf-8").splitlines(), 1) if WRITER.search(line)]
    assert not bad, ("the Python lake writer is slow and builds per run; read the cached lake (var_stack.var_lake, demo_lake_copy, "
                     "lake_cache.cached with the Java loader), or add the file to ALLOWED with why it tests the writer itself:\n  " + "\n  ".join(bad))


def test_every_lake_a_test_builds_is_built_inside_the_cache():
    bad = []
    for p in _files():
        text = p.read_text(encoding="utf-8")
        if p.name in ALLOWED or not BUILDS.search(text):
            continue
        if not CACHED.search(text):
            bad.append(p.name)
    assert not bad, "these build a lake outside tools/drillkit/lake_cache.py (built once per source hash, reused across runs): " + ", ".join(bad)


def test_the_allowed_files_still_need_it():
    stale = [n for n in ALLOWED if n != "test_lake_builds_cached.py" and not ((HERE / n).is_file()
             and (WRITER.search((HERE / n).read_text(encoding="utf-8")) or BUILDS.search((HERE / n).read_text(encoding="utf-8"))))]
    assert not stale, "no longer name the Python writer or build a lake: take them out of ALLOWED: " + ", ".join(stale)
