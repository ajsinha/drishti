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
lake is parked to i6 (var_stack.PARKED_I6, a visible skip) and only a parked file may still do so. A drill's browser lane once spent most
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
    "test_lake_builds_cached.py": "this guard",
}
#: the Python writer, or a lake built, loaded or copied by a test
WRITER = re.compile(r"""["']--engine["'],\s*["']deltalake["']|engine\s*=\s*["']deltalake["']|write_deltalake\(|load_rows\.py|load\s*=\s*["']python["']""")
BUILDS = re.compile(r"""["']lake["'],\s*["'](demo|build)["']|build_demo_lakes|\.cached\(|lake_copy\(|copytree\([^)\n]*(lake|delta)\b""")
SUBSET = re.compile(r"""demo_lake\(\s*(?!["']{2})["']|["']--only["']""")      # a lake of some datasets: the shared lake holds them all
PARKED = re.compile(r"\bPARKED_I6\b")


def _files():
    return sorted(HERE.glob("*.py"))


def _parked(text: str) -> bool:
    return bool(PARKED.search(text))


def test_no_test_builds_copies_or_subsets_a_lake_unless_it_is_parked():
    bad = []
    for p in _files():
        if p.name in ALLOWED or p.name == "var_stack.py":
            continue
        text = p.read_text(encoding="utf-8")
        if _parked(text):
            continue
        for n, line in enumerate(text.splitlines(), 1):
            if WRITER.search(line) or BUILDS.search(line) or SUBSET.search(line):
                bad.append(f"{p.name}:{n}: {line.strip()[:120]}")
    assert not bad, ("read the ONE shared lake (var_stack.shared_lake / shared_folder), read only; a test that must write to a lake is "
                     "parked to i6 (@PARKED_I6):\n  " + "\n  ".join(bad))


def test_the_shared_lake_is_the_only_lake_var_stack_makes():
    text = (HERE / "var_stack.py").read_text(encoding="utf-8")
    calls = re.findall(r"demo_lake\(([^)]*)\)", text)
    assert calls == ['"", SHARED_AS_OF, SHARED_EXTRA'], calls
    assert not WRITER.search(text) and not re.search(r"lake_copy|copytree|\.cached\(", text)


def test_the_parked_tests_say_why():
    from var_stack import PARKED_I6_REASON
    assert PARKED_I6_REASON == "parked to i6: writes to a lake (owner 2026-11-15)"
    listed = (HERE.parents[1] / "docs" / "architecture" / "TEST_HARNESS.md").read_text(encoding="utf-8")
    parked = sorted(p.name for p in _files() if p.name != "var_stack.py" and p.name not in ALLOWED and _parked(p.read_text(encoding="utf-8")))
    missing = [n for n in parked if n not in listed]
    assert not missing, "parked to i6 but not in TEST_HARNESS.md's parked list: " + ", ".join(missing)


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
