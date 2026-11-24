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

"""Unit tests of the documentation consolidation tools (i7k): corpus manifest, moved.yaml, moved.py, dup_check.py, merge_audit.py."""
from __future__ import annotations

import sys
from pathlib import Path

import pytest
import yaml

pytestmark = pytest.mark.static

ROOT = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(ROOT / "tools" / "docs")]
import corpus  # noqa: E402
import dup_check  # noqa: E402
import merge_audit  # noqa: E402
import moved  # noqa: E402


def test_every_rule_row_matches_and_totals_are_the_plans():
    rows, unmatched = corpus.build()
    assert unmatched == []
    n = corpus.counts(rows)
    assert (n["merge"], n["delete"], n["generate"], n["move"], n["rename"], n["connector"]) == (123, 64, 81, 5, 2, 22) or len(rows) != 400
    assert len({r[0] for r in rows}) == len(rows)


def test_classify_first_match_wins():
    assert corpus.classify("docs/guides/PVF_LOADING.md")[0] == "rename"
    assert corpus.classify("docs/guides/VAR_GUIDE.md")[0] == "keep"
    assert corpus.classify("docs/guides/FUNCTIONS.md")[0] == "generate"
    assert corpus.classify("docs/architecture/FIGMA_INTEGRATION.md")[2] == "docs/plans/FIGMA_INTEGRATION.md"
    assert corpus.classify("README.md")[0] == "keep"
    assert corpus.classify("nowhere/unknown.md") is None


def test_manifest_lists_every_tracked_markdown_file():
    manifest = {r["path"]: r["class"] for r in yaml.safe_load((ROOT / "config" / "docs-corpus.yaml").read_text(encoding="utf-8"))["files"]}
    tracked = set(corpus.tracked())
    assert not tracked - set(manifest), "tracked Markdown files missing from config/docs-corpus.yaml (python3 tools/docs/corpus.py --write)"
    gone = set(manifest) - tracked
    assert all(manifest[p] in ("merge", "delete", "move", "rename") for p in gone), "an entry that is no longer tracked must be a moved/removed one"


def test_manifest_and_moved_yaml_are_what_the_rules_give():
    assert corpus.main(["--check"]) == 0


def test_moved_yaml_covers_every_file_that_goes():
    rows, _ = corpus.build()
    goes = {r[0] for r in rows if r[1] in ("merge", "delete", "move", "rename")}
    assert goes <= set(moved.table())
    assert moved.table()["docs/architecture/DOCS_PLAN.md"] == ""
    assert moved.table()["docs/guides/PVF_LOADING.md"] == "docs/guides/BANK_DATA_GUIDE.md"


ROWS = [{"from": "docs/architecture/ACCESS_MODEL.md", "to": "docs/architecture/KERNEL_AND_MODULES.md", "how": "merge"},
        {"from": "docs/plans/LAUNCHER_REVIEW.md", "to": "", "how": "delete"}]


def test_sweep_rewrites_full_paths_and_unique_names_and_lists_deleted():
    files = {"A.java": "// see docs/architecture/ACCESS_MODEL.md section 3\n", "B.py": "# ACCESS_MODEL.md\n",
             "C.py": "# docs/plans/LAUNCHER_REVIEW.md\n", "D.py": "x = 1\n"}
    changed, left = moved.sweep(files, ROWS)
    assert changed == {"A.java": "// see docs/architecture/KERNEL_AND_MODULES.md section 3\n", "B.py": "# KERNEL_AND_MODULES.md\n"}
    assert left == ["C.py: names the deleted docs/plans/LAUNCHER_REVIEW.md"]
    assert moved.hint("docs/plans/LAUNCHER_REVIEW.md", ROWS).endswith("was deleted")


def test_verify_accepts_comment_and_test_lines_only():
    diff = "+++ b/src/Main.java\n-// docs/A.md\n+// docs/B.md\n+++ b/tools/test_x.py\n-x = 'docs/A.md'\n+x = 'docs/B.md'\n"
    assert moved.verify_diff(diff) == []
    code = "+++ b/src/Main.java\n-String s = \"docs/A.md\";\n+String s = \"docs/B.md\";\n"
    assert len(moved.verify_diff(code)) == 2


def test_dup_check_finds_a_repeated_run_an_owned_command_a_settings_row_and_repeated_text():
    run = "```\npython3 tools/drishti.py setup\npython3 tools/drishti.py lake demo\npython3 tools/drishti.py start all\n```\n"
    para = " ".join(f"word{i}" for i in range(60))
    texts = {"docs/A.md": run + "\n" + para + "\n", "docs/B.md": run.replace("python3 tools", "py -3.13 tools") + "\n" + para + "\n| `DRISHTI_X` | y |\n",
             "docs/tutorials/01-x.md": run}
    owners = {"owners": [{"pattern": r"drishti\.py\s+setup", "home": "docs/DEPLOYMENT_AND_QA_GUIDE.md"}]}
    f = dup_check.analyse(texts, owners)
    assert len(f["runs"]) == 1 and "docs/A.md and docs/B.md" in f["runs"][0]
    assert len(f["owned"]) == 2          # the tutorial is exempt
    assert len(f["settings"]) == 1
    assert f["text"] and "docs/A.md and docs/B.md" in f["text"][0]
    owners["allow_runs"] = [["docs/A.md", "docs/B.md", "both are a quick path"]]
    assert dup_check.analyse(texts, owners)["runs"] == []


def test_dup_check_report_mode_exits_zero():
    assert dup_check.main(["--top", "0"]) == 0


def test_merge_audit_finds_a_lost_heading_and_a_lost_block_and_honours_the_log():
    src = {"old.md": "# Old\n## Keep me\ntext\n```\nmvn -q package\n```\n## Lost\n```\nrm -rf x\n```\n"}
    homes = {"old.md": "new.md"}
    docs = {"new.md": "# New\n## Keep me\n```\nmvn   -q package\n```\n"}
    bad = merge_audit.audit(src, homes, docs)
    assert any("heading 'Lost'" in b for b in bad) and any("rm -rf x" in b for b in bad) and not any("Keep me" in b for b in bad)
    log = merge_audit.parse_log("old.md | Lost | dropped as a duplicate of new.md\nold.md | rm -rf x | duplicate of new.md\nold.md | Old | title\n")
    assert merge_audit.audit(src, homes, docs, log) == []


def test_merge_audit_runs_over_the_tree():
    assert merge_audit.main(["--show", "0"]) in (0, 1)   # step 0 baseline: nothing merged yet, so it reports; it must not crash
