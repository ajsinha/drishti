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

"""The drill's page-load statistics: p95 and median per page, slowest first, and the generated section of the benchmark document."""
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import pageloads  # noqa: E402


def test_p95_and_median_per_page_slowest_first_torn_lines_skipped():
    lines = [json.dumps({"page": "/var", "secs": s}) for s in (1, 2, 3, 4, 20)] + [json.dumps({"page": "/", "secs": 0.2}), '{"page": "/x", "se']
    rows = pageloads.stats(lines)
    assert [r["page"] for r in rows] == ["/var", "/"]
    assert rows[0] == {"page": "/var", "n": 5, "p95": 20.0, "median": 3.0}
    assert "(slow)" in pageloads.table(rows) and "`/` | 1 | 0.20 s" in pageloads.table(rows)


def test_write_replaces_only_the_generated_section(tmp_path):
    doc = tmp_path / "B.md"
    doc.write_text(f"before\n{pageloads.BEGIN}\nold\n{pageloads.END}\nafter\n", encoding="utf-8")
    pageloads.write(doc, pageloads.stats([json.dumps({"page": "/pivot", "secs": 0.5})]), 25)
    text = doc.read_text(encoding="utf-8")
    assert text.startswith("before\n") and text.endswith(f"{pageloads.END}\nafter\n") and "old" not in text and "`/pivot` | 1 | 0.50 s" in text


def test_the_benchmark_document_has_the_section():
    doc = (Path(__file__).resolve().parents[2] / "docs" / "admin" / "BENCHMARK.md").read_text(encoding="utf-8")
    assert pageloads.BEGIN in doc and pageloads.END in doc
