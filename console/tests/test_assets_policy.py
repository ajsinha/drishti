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

"""Front-end policy: everything vendored (no CDN, no external URL), no inline script, file sizes."""
import re
from pathlib import Path

WEB = Path(__file__).resolve().parent.parent / "web"
CONSOLE = WEB.parent
EXTERNAL = re.compile(r"""(src|href)\s*=\s*["']\s*(https?:)?//|@import\s+url\(\s*["']?(https?:)?//|url\(\s*["']?https?://""", re.I)
INLINE_SCRIPT = re.compile(r"<script(?![^>]*\bsrc=)[^>]*>", re.I)
INLINE_HANDLER = re.compile(r"\son[a-z]+\s*=", re.I)
INLINE_STYLE = re.compile(r"\sstyle\s*=", re.I)


def _files(*suffixes):
    return [p for p in WEB.rglob("*") if p.suffix in suffixes and "vendor" not in p.parts]


def test_no_external_references():
    offenders = [str(p) for p in _files(".html", ".css", ".js") if EXTERNAL.search(p.read_text(encoding="utf-8"))]
    assert offenders == [], "every asset must be vendored under web/static/vendor"


def test_vendor_has_no_external_references():
    offenders = [str(p) for p in (WEB / "static" / "vendor").rglob("*.css") if EXTERNAL.search(p.read_text(encoding="utf-8", errors="ignore"))]
    assert offenders == []


def test_no_inline_script_or_handlers():
    for p in _files(".html"):
        text = p.read_text(encoding="utf-8")
        assert not INLINE_SCRIPT.search(text), p
        assert not INLINE_HANDLER.search(text), p
        assert not INLINE_STYLE.search(text), p  # style-src 'self' blocks style attributes


def test_python_files_under_limit():
    over = [str(p) for p in CONSOLE.rglob("*.py") if ".venv" not in p.parts and len(p.read_text().splitlines()) > 1500]
    assert over == []


def test_vendored_echarts_gl_needs_no_eval():
    """ECharts GL is patched so the strict CSP (no unsafe-eval) holds: see vendor/echarts-gl/PATCHED.md."""
    from pathlib import Path
    gl = (Path(__file__).resolve().parent.parent / "web/static/vendor/echarts-gl/echarts-gl.min.js").read_text(encoding="utf-8")
    assert 'new Function("width","height","dpr"' not in gl
    assert "(width|height|dpr)" in gl


# ---- Calc's Python runtime (Pyodide): served from this origin only, never a CDN -------------------------------------------
REPO = CONSOLE.parent
CDN = re.compile(r"https?://|cdn\.jsdelivr|unpkg\.com|cdnjs\.|//cdn\.", re.I)


def test_calc_code_names_no_other_origin():
    """The page, the worker and the drishti module contain no URL at all: every load and every read is same-origin."""
    for f in [WEB / "static/js/calc.js", WEB / "static/js/calc-worker.js", WEB / "static/calc/drishti.py",
              WEB / "templates/terminal/_calc.html"]:
        text = "\n".join(line for line in f.read_text(encoding="utf-8").splitlines() if "Copyright" not in line)
        assert not CDN.search(text), f


def test_the_worker_points_pyodide_at_this_origin_for_everything():
    """Pyodide defaults its package URL to a CDN when it is not told otherwise; the worker tells it every location."""
    worker = (WEB / "static/js/calc-worker.js").read_text(encoding="utf-8")
    for option in ("indexURL: base", "packageBaseUrl: base", "lockFileURL: base + 'pyodide-lock.json'",
                   "stdLibURL: base + 'python_stdlib.zip'", "cdnUrl: base"):
        assert option in worker, option
    assert "new URL(path, self.location.origin)" in worker          # absolute, but on this origin
    template = (WEB / "templates/terminal/_calc.html").read_text(encoding="utf-8")
    assert 'data-runtime="{{ calc.runtime.base' in template
    from core.calc import Runtime
    assert Runtime(REPO / "nowhere").base == ""


def test_the_runtime_is_fetched_pinned_and_verified_and_never_committed():
    script = (REPO / "tools/fetch-pyodide.sh").read_text(encoding="utf-8")
    assert re.search(r'^VERSION="\d+\.\d+\.\d+"$', script, re.M)
    assert re.search(r'^SHA256="[0-9a-f]{64}"$', script, re.M)
    assert 'URL="https://github.com/pyodide/pyodide/releases/download/${VERSION}/pyodide-${VERSION}.tar.bz2"' in script
    assert "refusing it" in script                                   # a tarball with another hash is not unpacked
    assert "console/web/static/vendor/pyodide/" in (REPO / ".gitignore").read_text(encoding="utf-8").splitlines()
    docker = (REPO / "deploy/console.Dockerfile").read_text(encoding="utf-8")
    assert "tools/fetch-pyodide.sh" in docker                        # the image carries it: no internet at run time


def test_an_installed_runtime_is_whole_and_names_only_local_files():
    """When tools/fetch-pyodide.sh has run: every package in the lock file is a file here, and none is a URL."""
    import json
    folder = WEB / "static/vendor/pyodide"
    if not (folder / ".drishti-pyodide").exists():
        return                                                       # not installed (CI): nothing to check
    lock = json.loads((folder / "pyodide-lock.json").read_text(encoding="utf-8"))
    for name, p in lock["packages"].items():
        assert "://" not in p["file_name"] and "/" not in p["file_name"], name
        assert (folder / p["file_name"]).exists(), name
    assert {"numpy", "pandas", "scipy", "statsmodels", "matplotlib"} <= set(lock["packages"])
    for core in ("pyodide.mjs", "pyodide.asm.mjs", "pyodide.asm.wasm", "python_stdlib.zip"):
        assert (folder / core).exists(), core
