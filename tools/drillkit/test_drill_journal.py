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

"""The drill's journal (drill_journal.py): the lines, the resume list and the Java classes that count as done."""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import drill_journal as dj  # noqa: E402


def _xml(tree: Path, cls: str, failed: bool) -> None:
    d = tree / "m" / "x" / "target" / "surefire-reports"
    d.mkdir(parents=True, exist_ok=True)
    body = '<failure message="x"/>' if failed else ""
    (d / f"TEST-{cls}.xml").write_text(f'<testsuite name="{cls}"><testcase classname="{cls}" name="t" time="1">{body}</testcase></testsuite>')


def test_java_done_excludes_only_fully_passed_classes_at_the_same_commit(tmp_path):
    j = str(tmp_path / "j.jsonl")
    _xml(tmp_path, "p.A", False)
    _xml(tmp_path, "p.B", True)
    dj.scan(tmp_path, "java21", "c1", j, {})
    assert dj.java_done_classes(j, "java21", "c1", tmp_path) == ["p.A"]
    assert dj.java_done_classes(j, "java21", "c2", tmp_path) == []          # another commit: nothing is done
    (tmp_path / "m" / "x" / "src" / "test" / "java" / "p").mkdir(parents=True)
    (tmp_path / "m" / "x" / "src" / "test" / "java" / "p" / "A.java").write_text("class A {}")
    assert dj.java_done_classes(j, "java21", "c1", tmp_path) == []          # the test's own file changed: its key no longer matches


def test_lines_survive_a_torn_last_line(tmp_path):
    j = str(tmp_path / "j.jsonl")
    dj.append(j, {"lane": "l", "id": "a", "outcome": "passed", "commit": "c", "key": "k"})
    Path(j).write_text(Path(j).read_text() + '{"lane": "l", "id"')
    assert [r["id"] for r in dj.read(j)] == ["a"]
