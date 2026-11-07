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
"""The guard: no code outside the lake configuration hard-codes where a lake is (DATA_LAKE_GUIDE.md section 2). Every lake is in its own folder,
<data>/delta/<lake>, or where its lake file says; the server and the loader resolve it through drishti-lakeconfig, the tools through
tools/lake/lakeconfig.py. Code (Java, Python, JavaScript; tests excluded) may not:

- spell a lake's folder as a path: a string that is ``data/delta`` or starts with it (``./data/delta/trading``), or ``data`` joined to
  ``delta`` (``ROOT / "data" / "delta"``, ``data_dir() / "delta"``);
- spell the demo lake's root variable or reference: the literal ``DRISHTI_DELTA_ROOT`` (``lakeconfig.DEMO_ROOT_ENV``,
  ``Lakes.DEMO_ROOT_ENV``) or ``${DRISHTI_DELTA_ROOT...}`` (``lakeconfig.DEMO_ROOT_REF``).

Prose (docstrings, help text, comments) may name the folders; connector and lake files may name any path or URI."""
from __future__ import annotations

import ast
import pathlib
import re
import subprocess

ROOT = pathlib.Path(__file__).resolve().parents[1]
ALLOWED = ("drishti-lakeconfig/", "tools/lake/lakeconfig.py")
PATH = re.compile(r"^\.?[/\\]?data[/\\]delta([/\\]|$)")
REF = re.compile(r"DRISHTI_DELTA_ROOT")
JOIN = re.compile(r"""\bdata(?:_dir\(\))?["']?\s*\)?\s*/\s*["']delta["']""")
JAVA_STRING = re.compile(r'"((?:[^"\\]|\\.)*)"')


def _files() -> list[str]:
    out = subprocess.run(["git", "ls-files", "*.py", "*.java", "*.js"], cwd=ROOT, capture_output=True, text=True, check=True).stdout.split()
    keep = []
    for f in out:
        low = f.lower()
        if f.startswith(ALLOWED) or "/vendor/" in f or "/test/" in f or "/tests/" in f or pathlib.Path(f).name.startswith("test_") \
                or low.endswith(("test.java", "tests.java", "_test.py", "conftest.py")):
            continue
        keep.append(f)
    return keep


def _python(path: pathlib.Path) -> list[str]:
    text = path.read_text(encoding="utf-8")
    try:
        tree = ast.parse(text)
    except SyntaxError:
        return []
    docs = set()
    for node in ast.walk(tree):
        if isinstance(node, (ast.Module, ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)) and node.body:
            first = node.body[0]
            if isinstance(first, ast.Expr) and isinstance(first.value, ast.Constant) and isinstance(first.value.value, str):
                docs.add(id(first.value))
    bad = []
    for node in ast.walk(tree):
        if isinstance(node, ast.Constant) and isinstance(node.value, str) and id(node) not in docs:
            v = node.value
            if PATH.match(v) or v == "DRISHTI_DELTA_ROOT" or "${DRISHTI_DELTA_ROOT" in v:
                bad.append(f"{path.relative_to(ROOT)}:{node.lineno}: {v[:80]!r}")
    for i, line in enumerate(text.splitlines(), 1):
        code = line.split("#", 1)[0]
        if JOIN.search(code):
            bad.append(f"{path.relative_to(ROOT)}:{i}: {line.strip()[:80]}")
    return bad


def _java_or_js(path: pathlib.Path) -> list[str]:
    bad = []
    for i, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        s = line.strip()
        if s.startswith(("*", "//", "/*")):
            continue
        for m in JAVA_STRING.finditer(line.split("//", 1)[0]):
            v = m.group(1)
            if PATH.match(v) or REF.search(v):
                bad.append(f"{path.relative_to(ROOT)}:{i}: {s[:80]}")
    return bad


def violations() -> list[str]:
    out = []
    for f in _files():
        p = ROOT / f
        if not p.is_file():
            continue
        out += _python(p) if f.endswith(".py") else _java_or_js(p)
    return out


def test_no_code_outside_the_lake_configuration_spells_where_a_lake_is():
    bad = violations()
    assert not bad, ("a lake's folder is the lake file's (resolve it through drishti-lakeconfig or tools/lake/lakeconfig.py: root(), "
                     "DEMO_ROOT_ENV, DEMO_ROOT_REF):\n  " + "\n  ".join(bad))


def test_the_guard_finds_what_it_bans(tmp_path):
    f = tmp_path / "x.py"
    f.write_text('"""data/delta in a docstring is prose."""\nA = "data/delta/trading"\nB = ROOT / "data" / "delta"\nC = "DRISHTI_DELTA_ROOT"\n'
                 'D = "${DRISHTI_DELTA_ROOT:./x}"\nE = "the lake under data/delta, in words"\n', encoding="utf-8")
    global ROOT
    old, ROOT = ROOT, tmp_path
    try:
        found = _python(f)
    finally:
        ROOT = old
    assert [x.split(":")[1] for x in found] == ["2", "4", "5", "3"]


if __name__ == "__main__":
    print("\n".join(violations()))
