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

"""Drishti never installs a Python library (owner rule, 2026-11-23: "if the owner wants, he will use requirements.txt and install himself;
an automatic install can corrupt an env"), so the owner's Python, the operating system's above all, is never altered.

1. A guard over the code: nothing in tools/, drishti-console/ or compute/ starts pip to install (``-m pip install``, ``pip install``, a
   ProcessBuilder or ``subprocess`` with ``pip``); the only remaining mentions of ``pip install`` are PRINTED commands and docs. The
   libraries are checked read-only (importlib.metadata), never through pip.
2. Setup against an EXTERNALLY-MANAGED interpreter (a stand-in Python whose standard library folder holds the PEP 668 marker): one
   [FAIL] line and NO pip at all.
3. Setup against a Python that lacks libraries: one [FAIL] line with the pasteable command, and that Python's site-packages byte-identical.

The Linux end-to-end run (tools/test_doctor_acceptance.py) also compares /usr/bin/python3's installed libraries and the user site
before and after the whole first run."""
from __future__ import annotations

import argparse
import ast
import re
import subprocess
import sys
from pathlib import Path

import pytest

TOOLS = Path(__file__).resolve().parent
ROOT = TOOLS.parent
for p in (str(TOOLS), str(TOOLS / "launch")):
    if p not in sys.path:
        sys.path.insert(0, p)
import envsetup  # noqa: E402
import matrix_oracles as mo  # noqa: E402
import pylibs  # noqa: E402

SKIP_PARTS = ("tests", "node_modules", "static", "vendor")
RUNNERS = r"(subprocess\.)?(run|Popen|call|check_call|check_output)$|os\.system|os\.exec\w*|os\.spawn\w*|^run$|spawn|java_cmd"


def py_sources():
    for base in (ROOT / "tools", ROOT / "drishti-console", ROOT / "compute"):
        for f in sorted(base.rglob("*.py")):
            rel = f.relative_to(ROOT)
            if any(p in SKIP_PARTS for p in rel.parts) or f.name.startswith("test_") or f.name in ("conftest.py", "matrix_oracles.py"):
                continue
            yield rel, f.read_text(encoding="utf-8", errors="replace")


def _strings(node) -> list[str]:
    return [n.value for n in ast.walk(node) if isinstance(n, ast.Constant) and isinstance(n.value, str)]


def test_no_python_code_starts_pip():
    """No call that starts a process carries ``pip`` (as a word, ``-m pip`` or a ``pip ...`` command line), and no argument list
    literal holds both ``pip`` and ``install``."""
    found = []
    for rel, text in py_sources():
        try:
            tree = ast.parse(text)
        except SyntaxError:
            continue
        for node in ast.walk(tree):
            if isinstance(node, ast.Call) and node.args and re.search(RUNNERS, ast.unparse(node.func)):
                words = _strings(node.args[0])
                if any(re.fullmatch(r"pip3?(\.exe)?", w) or re.match(r"pip3?\s", w) or "-m pip" in w for w in words):
                    found.append(f"{rel}:{node.lineno}: {ast.unparse(node)[:160]}")
            if isinstance(node, (ast.List, ast.Tuple)):
                words = [e.value for e in node.elts if isinstance(e, ast.Constant) and isinstance(e.value, str)]
                if "pip" in words and "install" in words:
                    found.append(f"{rel}:{node.lineno}: {words}")
    assert not found, "Drishti never runs pip:\n" + "\n".join(found)


def test_no_java_code_starts_pip():
    found = []
    for base in (ROOT / "compute", ROOT / "kernel", ROOT / "modules"):
        for f in base.rglob("*.java"):
            if "/test/" in f.as_posix() or "/target/" in f.as_posix():
                continue
            text = f.read_text(encoding="utf-8", errors="replace")
            for m in re.finditer(r"\"pip\"|\"-m\",\s*\"pip\"|\"pip install|--target\"", text):
                found.append(f"{f.relative_to(ROOT)}:{text.count(chr(10), 0, m.start()) + 1}: {m.group()}")
    assert not found, "no ProcessBuilder or argument list names pip:\n" + "\n".join(found)


def test_the_printed_command_is_the_one_start_here_teaches():
    home = Path("/home/John Smith/drishti-platform (1)/drishti")
    pack = Path("/home/John Smith/drishti-wheels")
    # requirements.txt and python.lock: one pip run each (python.lock has --hash lines; one run with both is refused by pip's hash mode)
    lin = pylibs.install_commands("/opt/py 3.13/bin/python3", home, None, win=False)
    p = "'/opt/py 3.13/bin/python3' -m pip install"
    assert lin == [f"cd '{home}/drishti-console'   then   {p} -r requirements.txt   then   {p} -r ../config/compute/locks/python.lock"
                   "   (uses your package index)"]
    off = pylibs.install_commands("/usr/bin/python3.13", home, pack, win=False)
    p = f"/usr/bin/python3.13 -m pip install --no-index --find-links '{pack}'"
    assert off[0] == f"cd '{home}/drishti-console'   then   {p} -r requirements.txt   then   {p} -r ../config/compute/locks/python.lock"
    win = pylibs.install_commands("C:/Python313/python.exe", Path("C:/Users/John Smith/drishti"), Path("C:/Users/John Smith/drishti-wheels"),
                                  win=True, version=(3, 13))
    assert win[0].startswith('cd "C:') and "drishti-console\"   then   py -3.13 -m pip install --no-index --find-links \"C:" in win[0]
    assert win[0].endswith("-r ..\\config\\compute\\locks\\python.lock") and "-r requirements.txt   then" in win[0]
    assert "--user" not in " ".join(win) and "--break-system-packages" not in " ".join(win)


# ---- 2. setup against an EXTERNALLY-MANAGED interpreter: one [FAIL], no pip at all ---------------------------------------------------------

def managed_python(tmp: Path) -> tuple[Path, Path]:
    """A stand-in operating-system Python: the real interpreter, told (by a sitecustomize) that its standard library is a folder that holds
    EXTERNALLY-MANAGED and that it is no virtual environment; any ``-m pip`` is logged and refused. (the interpreter, the pip log)"""
    stdlib = tmp / "fake stdlib"
    stdlib.mkdir()
    (stdlib / "EXTERNALLY-MANAGED").write_text("[externally-managed]\nError=use apt\n", encoding="utf-8")
    site = tmp / "fake site"
    site.mkdir()
    (site / "sitecustomize.py").write_text(
        "import sys, sysconfig\n_g = sysconfig.get_path\n"
        f"sysconfig.get_path = lambda name, *a, **k: {str(stdlib)!r} if name == 'stdlib' else _g(name, *a, **k)\n"
        "sys.base_prefix = sys.prefix\n", encoding="utf-8")
    log = tmp / "pip.log"
    py = tmp / "python3"
    py.write_text(f"#!/bin/sh\nfor a in \"$@\"; do [ \"$a\" = pip ] && {{ echo \"$*\" >> '{log}'; exit 97; }}; done\n"
                  f"PYTHONPATH='{site}' exec '{sys.executable}' \"$@\"\n", encoding="utf-8")
    py.chmod(0o755)
    return py, log


def libs_setup(tmp_path, monkeypatch, capsys, py: Path, missing=("alpha is not installed",)) -> tuple[int, str, list]:
    seen = []

    def run(cmd, **kw):
        seen.append([str(c) for c in cmd])
        return subprocess.run(cmd, **kw)
    pack = tmp_path / "pack"
    pack.mkdir(exist_ok=True)
    (pack / "alpha-1.2-py3-none-any.whl").write_bytes(b"w")
    monkeypatch.setattr(sys, "executable", str(py))                    # setup checks the Python that runs it
    monkeypatch.setattr(pylibs, "verify", lambda *a, **k: list(missing))
    a = argparse.Namespace(part="libs", system=False, check=False, home=None, data_dir=str(tmp_path / "data"), envs_dir=str(tmp_path / "envs"),
                           java_home=None, python=None, skip_venv=False, reinstall=False, wheels=str(pack))
    rc = envsetup.setup(a, {"DRISHTI_HOME": str(ROOT), "HOME": str(tmp_path / "home")}, run=run)
    return rc, capsys.readouterr().out, seen


@pytest.mark.skipif(sys.platform.startswith("win"), reason="a POSIX stand-in interpreter")
def test_setup_on_an_externally_managed_python_is_one_fail_and_runs_no_pip(tmp_path, monkeypatch, capsys):
    py, log = managed_python(tmp_path)
    assert pylibs.externally_managed(str(py)), "the stand-in reads as an operating system's Python"
    rc, out, seen = libs_setup(tmp_path, monkeypatch, capsys, py)
    fails = [l for l in out.splitlines() if "[FAIL]" in l]
    assert rc == 1 and len(fails) == 1 and "managed by the operating system" in fails[0], out
    assert not [c for c in seen if "pip" in c] and not log.exists(), seen
    assert "break-system-packages" not in out and "-m pip install" not in out


@pytest.mark.skipif(sys.platform.startswith("win"), reason="a POSIX stand-in interpreter")
def test_setup_on_a_python_lacking_libraries_prints_the_command_and_leaves_its_site_packages_byte_identical(tmp_path, monkeypatch, capsys):
    """A real Python of the test's own (a virtual environment the TEST makes, outside any checkout), lacking a library: setup prints one
    [FAIL] line and the pasteable command, runs no pip, and that Python's site-packages is byte-identical afterwards."""
    venv = tmp_path / "owners py"
    subprocess.run([sys.executable, "-m", "venv", "--without-pip", str(venv)], check=True, capture_output=True)
    py = venv / "bin" / "python"
    site = next((venv / "lib").glob("python3*/site-packages"))
    before = mo.snapshot(site)
    monkeypatch.setattr(pylibs, "externally_managed", lambda *a, **k: False)
    rc, out, seen = libs_setup(tmp_path, monkeypatch, capsys, py)
    fails = [l for l in out.splitlines() if "[FAIL]" in l]
    assert rc == 1 and len(fails) == 1 and "[FAIL] libraries" in fails[0] and "Drishti installs nothing" in fails[0], out
    assert f"{py} -m pip install --no-index --find-links" in out.replace("'", "") and "-r requirements.txt" in out
    assert "(uses your package index)" in out
    assert not [c for c in seen if "pip" in c], seen
    assert mo.snapshot(site) == before, "setup left the Python's site-packages byte-identical"
