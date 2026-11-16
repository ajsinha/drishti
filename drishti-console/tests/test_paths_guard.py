"""
Project Drishti · Any data. Any domain. One grammar.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
All rights reserved.

PROPRIETARY AND CONFIDENTIAL.

This file is the confidential and proprietary property of Ashutosh Sinha.
Unauthorised copying, use, modification, distribution or disclosure of this
file, via any medium, is strictly prohibited except with the express prior
written permission of the copyright holder.

See the LICENSE file in the root of this repository for the full terms.
"""
import re
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]

pytestmark = pytest.mark.static

# a repository file reached by a literal relative path: Path("tools/x.py"), open("config/y.yaml"), os.path.join("data", ...), glob("modules/*")
# without an anchor (__file__, a ROOT/REPO/CONSOLE constant): it works only from one working folder.
CALL = re.compile(r"""(?<![\w.])(?:Path|PurePath|open|os\.path\.(?:join|exists|isdir|isfile)|glob\.glob|os\.chdir|os\.listdir)\(\s*f?["']([^"']*)["']""")

# Relative by design, each with its reason: (file suffix, text on the line).
ALLOWED: list[tuple[str, str]] = [
    ("test_datacli.py", 'Path(".").iterdir()'),   # the CLI under test writes to the working folder; the test first chdirs into its own temp folder
    ("test_contracts.py", 'Path("src", "main", "java"'),   # a suffix joined onto each module folder, never opened from the working folder
    ("test_maintain_admin.py", 'Path("data/lakes/lake-maintenance.yaml")'),   # a value handed to a command builder and compared as text; no file is opened
    ("test_tmpdir.py", 'Path("data") / "tmp" / "console"'),   # builds the expected tail of a configured folder to compare a string with, never opens it
]


def _roots() -> set[str]:
    return {".", "..", "src", "target", *(p.name for p in ROOT.iterdir() if p.is_dir() and not p.name.startswith("."))}


def _python_tests() -> list[Path]:
    found = list((ROOT / "drishti-console" / "tests").glob("*.py"))
    found += [p for p in (ROOT / "tools").rglob("test_*.py") if ".venv" not in p.parts and "node_modules" not in p.parts]
    found += [p for p in (ROOT / "compute").rglob("*.py") if "src/test/python" in p.as_posix() and ".venv" not in p.parts]
    # the guards themselves: their self-tests hold the very forms they refuse
    return [p for p in found if not (p.name.endswith("paths_guard.py") or p.name == "test_no_relative_paths.py")]


def test_no_python_test_opens_a_repository_file_relative_to_the_working_folder():
    roots = _roots()
    offenders = []
    for f in _python_tests():
        for n, line in enumerate(f.read_text(encoding="utf-8").splitlines(), 1):
            for m in CALL.finditer(line):
                lit = m.group(1)
                if lit.startswith("/") or re.match(r"[A-Za-z]:", lit) or lit.startswith("./m/"):
                    continue
                if lit.split("/")[0] in roots and not any(f.name == s and t in line for s, t in ALLOWED):
                    offenders.append(f"{f.relative_to(ROOT)}:{n}  {line.strip()}")
    assert not offenders, "tests that open a path from the working folder; anchor them on __file__ or ROOT:\n" + "\n".join(offenders)
