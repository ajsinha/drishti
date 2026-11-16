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

"""The code-volume figures: which file counts where, and the table's replacement in the document."""

from __future__ import annotations

import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import code_volume as cv  # noqa: E402

pytestmark = pytest.mark.static


@pytest.mark.parametrize("path,column", [
    ("kernel/drishti-engine/src/main/java/A.java", "java main"), ("kernel/drishti-engine/src/test/java/A.java", "java test"),
    ("drishti-console/core/a.py", "console python"), ("drishti-console/web/static/js/a.js", "console js"),
    ("drishti-console/tests/test_a.py", None), ("drishti-console/web/static/vendor/a.js", None),
    ("drishti-console/web/static/render/1.2.0/a.js", None), ("drishti-console/web/embed/a.js", None), ("tools/a.py", None),
])
def test_category(path: str, column: str | None) -> None:
    assert cv.category(path) == column


def test_replaced_between_markers() -> None:
    doc = f"a\n{cv.BEGIN}\nold\n{cv.END}\nb\n"
    assert cv.replaced(doc, "new") == f"a\n{cv.BEGIN}\nnew\n{cv.END}\nb\n"
    with pytest.raises(ValueError):
        cv.replaced("no markers", "new")


def test_benchmark_has_the_table() -> None:
    assert cv.BEGIN in cv.DOC.read_text(encoding="utf-8")
