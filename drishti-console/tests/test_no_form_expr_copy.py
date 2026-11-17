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

"""One place evaluates a form's rules: the server (FormExpr, behind POST /api/v1/forms/check). The browser sends the draft and shows the answer;
no file under web/static (or its templates) holds a copy of the expression language, and no page loads one."""
import re
from pathlib import Path

WEB = Path(__file__).resolve().parent.parent / "web"
OWN = [p for p in (WEB / "static" / "js").glob("*.js")] + list((WEB / "templates").rglob("*.html"))


def test_no_form_expr_file_and_no_page_loads_one():
    assert not list((WEB / "static").rglob("*form-expr*")), "the form expressions are the server's: no copy under web/static"
    assert not [p.name for p in OWN if "form-expr" in p.read_text(encoding="utf-8")]


def test_no_console_file_parses_and_evaluates_form_rules():
    """The marks of the old copy: a tokenizer/parser/evaluator for rule text, and the language's function table."""
    bad = []
    for p in OWN:
        text = p.read_text(encoding="utf-8")
        if re.search(r"function\s+parse\s*\(", text) and re.search(r"function\s+(evaluate|ev)\s*\(", text):
            bad.append(p.name)
        if re.search(r"\bempty\s*:\s*1\b.*\blen\s*:\s*1\b", text):
            bad.append(p.name)
        if "formExpr" in text:
            bad.append(p.name)
    assert not bad, "form rules are checked by the server: " + ", ".join(sorted(set(bad)))
