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

"""One Python runtime (docs/guides/BI_GUIDE.md section 19): every Python run is the server's, on its compute pool ``python``; the
console only shows. So the console's own code (``core/``, ``routes/`` and its entry) imports no compute library, the console holds no Python
runner, and the server never calls the console to run Python. The browser's Labs Calc (``web/static/calc``, Pyodide in the reader's browser) is
not the console's code and is left out. A quick file check: the drill's gate runs it."""
from __future__ import annotations

import ast
from pathlib import Path

import pytest

pytestmark = pytest.mark.static   # a quick file check: the drill's gate runs it (TEST_HARNESS.md, "Quick file checks")

CONSOLE = Path(__file__).resolve().parent.parent
ROOT = CONSOLE.parent
#: The compute libraries the console never imports (the server's pool has them, pinned).
BANNED = {"pandas", "numpy", "scipy", "polars", "duckdb", "statsmodels", "sklearn"}


def console_sources() -> list[Path]:
    files = [p for d in ("core", "routes") for p in (CONSOLE / d).rglob("*.py")]
    return sorted(files + [CONSOLE / "run_drishti_web.py"])


def imports(path: Path) -> list[tuple[int, str]]:
    tree = ast.parse(path.read_text(encoding="utf-8"), str(path))
    out = []
    for n in ast.walk(tree):
        if isinstance(n, ast.Import):
            out += [(n.lineno, a.name.split(".")[0]) for a in n.names]
        elif isinstance(n, ast.ImportFrom) and n.level == 0 and n.module:
            out.append((n.lineno, n.module.split(".")[0]))
        elif isinstance(n, ast.Call) and getattr(n.func, "id", getattr(n.func, "attr", "")) in ("__import__", "import_module") and n.args \
                and isinstance(n.args[0], ast.Constant) and isinstance(n.args[0].value, str):
            out.append((n.lineno, n.args[0].value.split(".")[0]))
    return out


def test_the_console_imports_no_compute_library():
    found = [f"{p.relative_to(CONSOLE)}:{line} imports {name}" for p in console_sources() for line, name in imports(p) if name in BANNED]
    assert not found, "the console computes nothing; Python runs on the server's compute pool (BI_GUIDE.md section 19):\n" + "\n".join(found)


def test_the_console_has_no_python_runner_and_the_server_never_calls_it():
    for gone in ("core/pyrunner.py", "core/pyrunner_worker.py", "core/transform_cli.py", "core/pytests.py", "core/python_items.py", "core/quant",
                 "routes/pynode_routes.py"):
        assert not (CONSOLE / gone).exists(), f"{gone} is the deleted console runtime"
    server = ROOT / "kernel/drishti-server/src/main"
    for p in list(server.rglob("*.java")) + list(server.rglob("*.yaml")):
        text = p.read_text(encoding="utf-8")
        for word in ("transform_cli", "/internal/py", "ConsolePythonRunner", "ProcessTransformRunner", "console-token"):
            assert word not in text, f"{p.relative_to(ROOT)} names {word}: the server runs Python on its own pool, never through the console"


def test_the_ban_reads_imports_in_any_form(tmp_path):
    f = tmp_path / "x.py"
    f.write_text("import os\nimport numpy as np\nfrom pandas import DataFrame\n__import__('scipy')\nfrom . import pandas\n", encoding="utf-8")
    assert [n for _, n in imports(f) if n in BANNED] == ["numpy", "pandas", "scipy"]
