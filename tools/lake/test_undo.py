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

"""lake undo (tools/lake/undo.py): the plan from the Java undo's answers, the words, the confirmation (a dry run first, the load id typed
again, --yes for scripts, refused without a terminal). The undo itself is tested in Java (drishti-lakeload AppendNewestLoadTest)."""
import json
import sys
import types
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import stores  # noqa: E402
import undo  # noqa: E402

LINE = {"table": "risk/scenario-pv", "date": "2026-10-27", "removed": 2, "restored": 0, "replaced": False, "version": 3}


def fake(calls):
    def runner(cmd):
        calls.append(cmd)
        return types.SimpleNamespace(returncode=0, stdout=json.dumps([LINE]))
    return runner


def test_plan_reads_the_java_answer(tmp_path, monkeypatch):
    monkeypatch.setattr(stores, "loader_command", lambda data, jar, java, main: ["fake-jvm", main])
    calls = []
    got = undo.plan(str(tmp_path), "R.1", True, runner=fake(calls))
    assert got == [{**LINE, "format": "delta"}]
    assert calls[0][:2] == ["fake-jvm", undo.UNDO] and "--dry-run" in calls[0] and calls[0][calls[0].index("--load-id") + 1] == "R.1"
    assert undo.words(got, "R.1", True) == ["would undo risk/scenario-pv 2026-10-27: 2 rows of load R.1 removed"]
    assert undo.words([{**LINE, "replaced": True, "restored": 5}], "R.1", False)[0].endswith("5 rows of the date before it restored")
    assert undo.words([], "R.1", False) == ["no row of load R.1 in the lake"]


def test_log_lines_before_the_answer_are_skipped(tmp_path, monkeypatch):
    """The exec jar's libraries log to standard output before the JSON array (pretty-printed over several lines)."""
    monkeypatch.setattr(stores, "loader_command", lambda data, jar, java, main: ["fake-jvm", main])
    out = "08:02:01.665 [main] INFO io.delta.kernel.internal.snapshot.SnapshotManager -- Loading log segment\n" + json.dumps([LINE], indent=1)
    got = undo.plan(str(tmp_path), "R.1", True, runner=lambda cmd: types.SimpleNamespace(returncode=0, stdout=out))
    assert got == [{**LINE, "format": "delta"}]


def test_the_undo_is_confirmed_by_the_load_id(tmp_path, monkeypatch):
    monkeypatch.setattr(stores, "loader_command", lambda data, jar, java, main: ["fake-jvm", main])
    done = []
    monkeypatch.setattr(undo, "plan", lambda lake, load_id, dry, jar=None, java=None: done.append(dry) or [LINE])
    a = types.SimpleNamespace(lake=str(tmp_path), load_id="R.1", dry_run=False, yes=False, json=False, jar=None, java=None)
    said = []
    monkeypatch.setattr(sys.stdin, "isatty", lambda: True, raising=False)
    assert undo.run(a, said.append, ask=lambda q: "R.2") == 1 and done == [True]           # a wrong id: nothing changed
    assert "not confirmed" in said[-1]
    assert undo.run(a, said.append, ask=lambda q: "R.1") == 0 and done[-1] is False
    a.yes = True
    done.clear()
    monkeypatch.setattr(sys.stdin, "isatty", lambda: False, raising=False)
    assert undo.run(a, said.append) == 0 and done == [True, False]
    a.yes = False
    done.clear()
    assert undo.run(a, said.append) == 1 and done == [True]                              # no terminal, no --yes: refused
    assert "--yes" in said[-1]
