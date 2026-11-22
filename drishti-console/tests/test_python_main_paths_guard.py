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
import os
import re
from pathlib import Path

import pytest

pytestmark = pytest.mark.static

ROOT = Path(__file__).resolve().parents[2]

# The owner's rule: no path of the product depends on the process's working folder. This guard reads the MAIN Python code (the console, tools,
# clients, modules, compute, examples; not tests) and fails, naming file:line, on a literal relative path, on the working folder itself
# (Path.cwd(), os.getcwd(), Path(".")), and on a "./" settings default. Tests are covered by test_paths_guard.py.
CALL = re.compile(r"""(?<![\w.])(?:Path|PurePath|open|os\.path\.(?:join|exists|isdir|isfile|abspath|realpath)|glob\.glob|os\.chdir|os\.listdir|os\.makedirs|os\.walk)\(\s*f?["']([^"']*)["']""")
CWD = re.compile(r"""Path\.cwd\(\)|os\.getcwd\(\)|\bPath\(\s*["']\.?["']\s*\)|os\.path\.abspath\(\s*["']\.?["']\s*\)""")
SETTING_DEFAULT = re.compile(r"""\$\{[A-Z_a-z.]+:\.{1,2}(?:/[^}]*)?\}|["']\./[^"']*["']""")

ARG_DEFAULT = re.compile(r"""default=(?:Path|pathlib\.Path)?\(?\s*f?["']([^"']*)["']""")
ENV_DEFAULT = re.compile(r"""(?:environ\.get|getenv)\([^)]*,\s*["'](?:\.{1,2}(?:/[^"']*)?|data|config|modules|tools)["']""")

SKIP_DIRS = {".git", "target", "node_modules", ".venv", "data", "__pycache__", ".claude", "venv", "site-packages"}

# Allowed, each with its reason: (file suffix, text on the line, reason).
ALLOWED: list[tuple[str, str, str]] = [
    ("tools/lake/lakeconfig.py", "${drishti.data.dir:./data}", "the placeholder text the Java lake files carry; substituted with the real data folder, never opened"),
    ("tools/lake/stores.py", "${drishti.data.dir:./data}", "the placeholder text the Java lake files carry; substituted with the real data folder, never opened"),
    ("tools/lake/demo.py", "${drishti.data.dir:./data}", "the placeholder text the Java lake files carry; substituted with the real data folder, never opened"),
    ("core/schemakit/build.py", "${drishti.data.dir:./data}", "text of a generated server connector setting; the server resolves drishti.data.dir"),
    ("tools/packgen/pack_from_jsonl.py", "${drishti.data.dir:./data}", "text of a generated server connector setting; the server resolves drishti.data.dir"),
    ("tools/packgen/module_from_jsonl.py", "${drishti.data.dir:./data}", "text of a generated server connector setting; the server resolves drishti.data.dir"),
    ("tools/packgen/banking/make_packs.py", "${drishti.data.dir:./data}", "text of a generated server setting; the server resolves drishti.data.dir"),
    ("tools/lake/stage.py", 'pathlib.Path("")', "a placeholder for a staged part with no folder; never opened"),
    ("tools/storeload.py", "cwd = Path.cwd()", "a path the operator typed on drishti.py data load resolves from where it was typed"),
    ("tools/api_docs.py", 'APIDOCS = Path("target")', "a suffix joined onto each Maven module's folder, never opened from the working folder"),
    ("tools/drillkit/affected.py", './/m:module', "an XPath, not a path"),
    ("tools/docs/shots/t05.py", "text.replace(\"./docs/tutorials", "the old text in a stored connector file, replaced by the absolute folder"),
    ("tools/drillkit/affected.py", '"./mvnw"', "run with cwd=ROOT (the drill's own subprocess call)"),
    ("drishti_compute/worker.py", "os.getcwd()", "remembers the worker's folder to return to after a request chdirs into its scratch folder"),
]


def _roots() -> set[str]:
    return {".", "..", "src", "target", *(p.name for p in ROOT.iterdir() if p.is_dir() and not p.name.startswith("."))}


def _is_test(rel: Path) -> bool:
    return rel.name.startswith("test_") or rel.name == "conftest.py" or "tests" in rel.parts or "test" in rel.parts


def _main_python() -> list[Path]:
    found = []
    for top in ("drishti-console", "tools", "clients", "modules", "compute", "examples"):
        base = ROOT / top
        if not base.is_dir():
            continue
        for dirpath, dirs, files in os.walk(base):
            dirs[:] = [d for d in dirs if d not in SKIP_DIRS and not d.startswith(".")]
            for f in files:
                p = Path(dirpath) / f
                if f.endswith(".py") and not _is_test(p.relative_to(ROOT)) and "src/test" not in p.as_posix():
                    found.append(p)
    return found


def scan_line(line: str, roots: set[str]) -> list[str]:
    """What in one line of main code depends on the working folder (empty when nothing does)."""
    code = line
    if "#" in line and not re.search(r"""["'][^"']*#[^"']*["']""", line):
        code = line.split("#", 1)[0]
    found = []
    for m in CALL.finditer(code):
        lit = m.group(1)
        if lit.startswith("/") or re.match(r"[A-Za-z]:", lit) or "://" in lit or lit == "":
            continue
        if lit.split("/")[0] in roots:
            found.append(lit)
    if CWD.search(code):
        found.append("working folder")
    for m in ARG_DEFAULT.finditer(code):
        lit = m.group(1)
        if lit and not lit.startswith("/") and "://" not in lit and lit.split("/")[0] in roots:
            found.append("relative default")
    if ENV_DEFAULT.search(code):
        found.append("relative default")
    if SETTING_DEFAULT.search(code) and re.search(r"(?i)\b(dir|folder|path|root|home)\b", code):
        found.append("./ default")
    return found


def scan() -> list[str]:
    roots = _roots()
    offenders = []
    for f in _main_python():
        rel = f.relative_to(ROOT).as_posix()
        for n, line in enumerate(f.read_text(encoding="utf-8", errors="replace").splitlines(), 1):
            if scan_line(line, roots) and not any(rel.endswith(s) and t in line for s, t, _ in ALLOWED):
                offenders.append(f"{rel}:{n}  {line.strip()}")
    return offenders


def test_no_main_python_path_depends_on_the_working_folder():
    offenders = scan()
    assert not offenders, ("main Python code that takes a path from the working folder; anchor it on __file__, a ROOT constant, DRISHTI_HOME or the "
                           "configured data folder:\n" + "\n".join(offenders))


@pytest.mark.parametrize("line,hit", [
    ('p = Path("tools/bench/results")', True),
    ('p = Path("config", "x.yaml")', True),
    ('open("data/x.json")', True),
    ('os.path.join("data", "x")', True),
    ('glob.glob("modules/*")', True),
    ('root = Path.cwd()', True),
    ('d = os.getcwd()', True),
    ('p = Path(".")', True),
    ('dir: "${X:./config/ai}" # dir', True),
    ('p = Path(__file__).parent / "x"', False),
    ('p = ROOT / "tools" / "x.py"', False),
    ('p = Path("/etc/x")', False),
    ('u = open("https://x")', False),
    ('p = Path(args.out)', False),
    ('ap.add_argument("--out", default=".")', True),
    ('ap.add_argument("--s", default="modules/x")', True),
    ('os.environ.get("DRISHTI_DATA_DIR", "data")', True),
    ('os.environ.get("X", "")', False),
    ('ap.add_argument("--out", default=str(ROOT / "dist"))', False),
    ('x = 1  # Path("data/x")', False),
])
def test_the_patterns_find_what_they_should(line, hit):
    assert bool(scan_line(line, {".", "..", "tools", "config", "data", "modules"})) is hit
