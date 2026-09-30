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
