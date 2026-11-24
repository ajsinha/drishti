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

"""Every generated reference is current (i7k): one test runs every generator's --check, so a
changed YAML, contract, mapping or registry that was not followed by its generator fails here with the command that fixes it.

    python3 -m pytest -q tools/test_docs_generated.py
"""
from __future__ import annotations

import subprocess
import sys
from pathlib import Path

import pytest

pytestmark = pytest.mark.static

ROOT = Path(__file__).resolve().parents[1]

sys.path.insert(0, str(ROOT / "tools" / "docs"))
import generate as G  # noqa: E402  (the one list of generators: `drishti.py docs generate`)

CHECKS = [(what, chk) for what, _write, chk in G.GENERATORS]


@pytest.mark.parametrize("what,cmd", CHECKS, ids=[c[0] for c in CHECKS])
def test_a_generated_reference_is_current(what, cmd):
    r = subprocess.run([sys.executable, *cmd], cwd=ROOT, capture_output=True, text=True, timeout=600)
    assert r.returncode == 0, f"{what} is stale: run  python3 tools/drishti.py docs generate\n{(r.stdout + r.stderr)[-1500:]}"


def test_every_generator_has_a_write_and_a_check_command():
    for what, write, chk in G.GENERATORS:
        assert (ROOT / chk[0]).is_file(), what
        assert "--check" in chk, what
    assert len({w for w, _, _ in G.GENERATORS}) == len(G.GENERATORS)


def test_a_stale_reference_is_named_with_its_fix(monkeypatch, capsys):
    import io
    monkeypatch.setattr(G, "GENERATORS", [("x.md", ["tools/nope_a.py"], ["tools/nope_a.py", "--check"]), ("y.md", ["tools/knowledge.py"], ["tools/knowledge.py", "--check"])])
    out = io.StringIO()
    assert G.run(True, out=out) == 1
    text = out.getvalue()
    assert "STALE" in text and "x.md" in text and "fix with: python3 tools/nope_a.py" in text and "1 of 2" in text


def test_only_narrows_and_a_clean_run_returns_zero(monkeypatch):
    import io
    monkeypatch.setattr(G, "GENERATORS", [("a", ["-c", "pass"], ["-c", "pass"]), ("b", ["-c", "raise SystemExit(1)"], ["-c", "raise SystemExit(1)"])])
    out = io.StringIO()
    assert G.run(True, only=["a"], out=out) == 0 and "1 generated references are current" in out.getvalue()
