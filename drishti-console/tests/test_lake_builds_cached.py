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

"""Every console test reads the ONE shared test lake (docs/architecture/TEST_HARNESS.md, "The shared test lake", owner 2026-11-15): the full
demo lake of the saved test data, built once per source hash by the parallel Java loader (tools/drillkit/lake_cache.py, var_stack.shared_lake),
read only. No test builds a lake of its own, asks for a subset of it, copies it or names the Python writer; a test that has to write to a
lake does it under its own tmp or cache folder and its file is named in ALLOWED below; no other file may. A drill's browser lane once spent most
of its time on fixtures that built their own lakes with the Python writer."""
import re
from pathlib import Path

import pytest

pytestmark = pytest.mark.static   # a quick file check: the drill's gate runs it (TEST_HARNESS.md, "Quick file checks")

HERE = Path(__file__).resolve().parent

#: files that are no test but may name the writer, each with its reason
ALLOWED = {
    "bi_report_stack.py": "make_lake: the BI sample lake of the parked BI tests and the docs' screenshots (bi_report_shots.py)",
    "bi_exposure_fixture.py": "the script make_lake runs (the BI samples' official exposure table)",
    "test_admin_lakes.py": "Admin -> Lakes builds a lake: under the test's tmp_path only (owner 2026-11-22)",
    "test_admin_lake_maintain.py": "maintenance compacts a copy of its own small lake: under tmp_path and the cache's own folder only",
    "trade_grain_stack.py": "the trade-grain copy of the shared lake, built once per source hash in the cache's own folder",
    "test_admin_lakes_browser.py": "a file becomes a lake: under the test's mktemp folder only",
    "test_admin_lake_maintain_browser.py": "maintenance of a small lake of its own: under the test's mktemp folder only",
    "test_public_sector_browser.py": "the recorded feeds loaded into lakes of its own: under the test's mktemp folder only",
    "test_bi_multi_dataset_browser.py": "the BI sample lake (bi_report_stack.make_lake): under the test's mktemp folder only",
    "test_bi_reports_cross_browser.py": "the BI sample lake (bi_report_stack.make_lake): under the test's mktemp folder only",
    "test_var_explorer_browser.py": "the trade-grain copy (trade_grain_stack): the cache's own folder only",
    "test_var_currency_browser.py": "the trade-grain copy (trade_grain_stack): the cache's own folder only",
    "test_lake_builds_cached.py": "this guard",
}
#: the Python writer, or a lake built, loaded or copied by a test
WRITER = re.compile(r"""["']--engine["'],\s*["']deltalake["']|engine\s*=\s*["']deltalake["']|write_deltalake\(|load_rows\.py|load\s*=\s*["']python["']""")
BUILDS = re.compile(r"""["']lake["'],\s*["'](demo|build)["']|build_demo_lakes|\.cached\(|lake_copy\(|copytree\([^)\n]*(lake|delta)\b""")
SUBSET = re.compile(r"""demo_lake\(\s*(?!["']{2})["']|["']--only["']""")      # a lake of some datasets: the shared lake holds them all


def _files():
    return sorted(HERE.glob("*.py"))


def test_no_test_builds_copies_or_subsets_a_lake_unless_it_is_allowed():
    bad = []
    for p in _files():
        if p.name in ALLOWED or p.name == "var_stack.py":
            continue
        text = p.read_text(encoding="utf-8")
        for n, line in enumerate(text.splitlines(), 1):
            if WRITER.search(line) or BUILDS.search(line) or SUBSET.search(line):
                bad.append(f"{p.name}:{n}: {line.strip()[:120]}")
    assert not bad, ("read the ONE shared lake (var_stack.shared_lake / shared_folder), read only; a test that must write to a lake is "
                     "named in ALLOWED (under its own tmp folder):\n  " + "\n  ".join(bad))


def test_the_shared_lake_is_the_only_lake_var_stack_makes():
    text = (HERE / "var_stack.py").read_text(encoding="utf-8")
    calls = re.findall(r"demo_lake\(([^)]*)\)", text)
    assert calls == ['"", SHARED_AS_OF, SHARED_EXTRA'], calls
    assert not WRITER.search(text) and not re.search(r"lake_copy|copytree|\.cached\(", text)


def test_a_shared_lake_server_asks_for_no_less_than_the_var_heap():
    """The shared lake holds every trade set: a server on it given less than var_stack.VAR_HEAP ran out of heap at start and its pages
    never loaded (test_exposure_explorer_browser and test_stress_window_browser at 768 MB, 2026-11-16)."""
    from var_stack import VAR_HEAP

    def mb(h: str) -> int:
        return int(h[:-1]) * (1024 if h[-1].lower() == "g" else 1)

    bad = []
    for p in _files():
        text = p.read_text(encoding="utf-8")
        if p.name == "var_stack.py" or not re.search(r"shared_(lake|folder)\(", text):
            continue
        for n, line in enumerate(text.splitlines(), 1):
            for h in re.findall(r"""\bheap\s*=\s*["'](\d+[mMgG])["']""", line):
                if mb(h) < mb(VAR_HEAP):
                    bad.append(f"{p.name}:{n}: heap={h}")
    assert not bad, f"a server on the shared lake gets at least var_stack.VAR_HEAP ({VAR_HEAP}):\n  " + "\n  ".join(bad)


def test_every_server_over_the_shared_lake_has_the_lake_s_last_date_for_today():
    """The lake ends on a fixed date; a server over it with the clock's today found nothing in a live read eleven days later (a snapshot read
    looks back lookback-days, 10: drill of 2026-11-17). var_server starts every server with SHARED_TODAY, and a file that starts its own over
    the lake's connectors (shared_connectors) passes it too."""
    text = (HERE / "var_stack.py").read_text(encoding="utf-8")
    assert 'SHARED_TODAY = {"DRISHTI_BUSINESS_TODAY": SHARED_AS_OF}' in text
    assert re.search(r"_stack\(tmp_path_factory, \{[^}]*\*\*SHARED_TODAY", text), "var_server starts its server without SHARED_TODAY"
    bad = [p.name for p in _files() if p.name not in ("var_stack.py", "test_lake_builds_cached.py")
           and re.search(r"\bshared_connectors\(", p.read_text(encoding="utf-8")) and "SHARED_TODAY" not in p.read_text(encoding="utf-8")]
    assert not bad, "a server over the shared lake without its today at the lake's last date (var_stack.SHARED_TODAY): " + ", ".join(bad)
