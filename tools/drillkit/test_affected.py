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
"""The affected-tests selection, the cached demo lake and the screenshot map."""
from __future__ import annotations

import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "drillkit"))
sys.path.insert(0, str(ROOT / "tools" / "docs"))
import affected  # noqa: E402
import lake_cache  # noqa: E402
import shots_affected  # noqa: E402


def test_shared_infrastructure_selects_everything():
    for f in ("pom.xml", "tools/drill.sh", "drishti-console/tests/conftest.py", "drishti-common/src/main/java/X.java"):
        assert affected.classify([f]).everything, f


def test_a_file_it_cannot_place_selects_everything():
    assert affected.classify(["somewhere/new/thing.bin"]).everything


def test_java_reaches_its_users_and_the_mapped_console_tests():
    plan = affected.classify(["modules/banking/banking-compute/src/main/java/com/ash/X.java"])
    assert {"modules/banking/banking-compute", "modules/banking/banking-service", "drishti-app"} <= plan.modules
    assert "drishti-rachana" not in plan.modules
    assert any("test_var_lens" in c for c in plan.console) and not any("test_funds" in c for c in plan.console)
    assert not plan.all_console and not plan.everything


def test_java_the_map_does_not_narrow_selects_every_console_test():
    assert affected.classify(["drishti-net/src/main/java/X.java"]).all_console


def test_a_java_test_stays_in_its_module():
    plan = affected.classify(["modules/banking/banking-service/src/test/java/com/ash/XTest.java"])
    assert plan.modules == {"modules/banking/banking-service"} and not plan.console and not plan.all_console


def test_a_console_test_runs_alone_and_docs_run_the_docs_tests():
    assert affected.classify(["drishti-console/tests/test_var_lens_browser.py"]).console == {"drishti-console/tests/test_var_lens_browser.py"}
    plan = affected.classify(["docs/admin/BENCHMARK.md"])
    assert plan.console and all("test_docs" in c or "test_help" in c or "test_tools_guide" in c for c in plan.console)
    assert not plan.modules and not plan.everything


def test_a_tool_runs_its_tests_and_one_no_test_names_runs_them_all():
    plan = affected.classify(["tools/lake/demo.py"])
    assert "tools/lake/test_demo.py" in plan.tools and not plan.all_tools
    assert affected.classify(["tools/no_test_names_" + "this_xyz.py"]).all_tools


def test_commands_build_before_the_console():
    plan = affected.Plan(modules={"modules/banking/banking-service"}, console={"drishti-console/tests/test_var_lens_browser.py"})
    cmds = affected.commands(plan)
    assert [c[0] for c in cmds] == ["maven", "console"]
    assert any("modules/banking/banking-service" in a for a in cmds[0][1]) and any(a.startswith("-Dtest=") and "VarGoldenMatrixTest" in a for a in cmds[0][1])
    assert "--dist" in cmds[1][1] and cmds[1][1][cmds[1][1].index("-n") + 1] == str(affected.CONSOLE_WORKERS)


def test_downstream_follows_the_graph():
    mods = {"a": {"artifact": "a", "deps": set()}, "b": {"artifact": "b", "deps": {"a"}}, "c": {"artifact": "c", "deps": {"b"}}, "d": {"artifact": "d", "deps": set()}}
    assert affected.downstream({"a"}, mods) == {"a", "b", "c"}


def _repo(tmp_path: Path) -> Path:
    r = tmp_path / "r"
    (r / "data/testdata").mkdir(parents=True)
    (r / "data/testdata/a.jsonl").write_text("1\n")
    for c in (["init", "-q"], ["add", "."], ["-c", "user.name=x", "-c", "user.email=x@x", "commit", "-qm", "c"]):
        subprocess.run(["git", *c], cwd=r, check=True)
    return r


def test_the_cache_builds_once_per_hash(tmp_path, monkeypatch):
    r = _repo(tmp_path)
    monkeypatch.setenv("DRISHTI_LAKE_CACHE", str(tmp_path / "cache"))
    built = []

    def build(work: Path) -> None:
        built.append(work)
        (work / "x").write_text("1")

    a = lake_cache.cached("n", build, r)
    b = lake_cache.cached("n", build, r)
    assert a == b and len(built) == 1 and (a / "x").is_file() and (a / lake_cache.DONE).is_file()
    (r / "data/testdata/a.jsonl").write_text("2\n")           # an edit of the test data: a new hash
    c = lake_cache.cached("n", build, r)
    assert c != a and len(built) == 2


def test_a_failed_build_leaves_nothing(tmp_path, monkeypatch):
    r = _repo(tmp_path)
    monkeypatch.setenv("DRISHTI_LAKE_CACHE", str(tmp_path / "cache"))

    def fail(work: Path) -> None:
        raise RuntimeError("no")

    try:
        lake_cache.cached("n", fail, r)
    except RuntimeError:
        pass
    base = lake_cache.cache_root(r) / lake_cache.source_hash(r)
    assert not [d for d in base.iterdir() if d.is_dir()]


def test_prune_keeps_the_newest(tmp_path, monkeypatch):
    monkeypatch.setenv("DRISHTI_LAKE_CACHE", str(tmp_path / "c"))
    for i, n in enumerate(("a", "b", "c", "d")):
        d = tmp_path / "c" / n
        d.mkdir(parents=True)
        import os
        os.utime(d, (1000 + i, 1000 + i))
    assert sorted(lake_cache.prune(2)) == ["a", "b"]


def test_the_page_map_finds_a_page_and_when_unsure_retakes_all():
    pages = shots_affected.page_map()
    assert "about" in pages and pages["about"]
    guides, _ = shots_affected.select(["drishti-console/drishti_console/static/drishti_var_explorer.js"], pages)
    assert guides and len(guides) < len(pages)
    assert shots_affected.select(["drishti-console/drishti_console/templates/base.html"], pages)[0] == sorted(pages)
    assert shots_affected.select(["drishti-console/drishti_console/static/drishti_zzzz_never.js"], pages)[0] == sorted(pages)
    assert shots_affected.select(["docs/x.md"], pages)[0] == []


def test_the_java25_list_names_real_modules_and_tests():
    for raw in (ROOT / "tools/drill-java25.txt").read_text(encoding="utf-8").splitlines():
        line = raw.split("#", 1)[0].strip()
        if line.startswith("@"):
            assert any(ROOT.glob(f"**/src/test/java/**/{line[1:]}.java")), line
        elif line:
            assert (ROOT / line / "src/test/java").is_dir(), line
