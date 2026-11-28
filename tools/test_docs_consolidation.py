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

import re
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
    assert len(rows) == 458                       # main's 410, i8's eight documents, the six tools/loaders READMEs and their six generated Help pages (docs/loaders), the 24 load guides (docs/guides/loads) and three more generated pages (the table catalogue)
    assert (n["merge"], n["delete"], n["generate"], n["move"], n["rename"], n["connector"]) == (106, 64, 103, 5, 2, 22)
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


def test_verify_accepts_comments_tests_document_references_and_the_plans_decisions_only():
    diff = "+++ b/src/Main.java\n-// docs/A.md\n+// docs/B.md\n+++ b/tools/test_x.py\n-x = 'docs/A.md'\n+x = 'docs/B.md'\n"
    assert moved.verify_diff(diff) == []
    ref = "+++ b/src/Main.java\n-String s = \"docs/A.md\";\n+String s = \"docs/guides/B_GUIDE.md#3-x\";\n"
    assert moved.verify_diff(ref) == [] and len(moved.classify(ref, [])["reference"]) == 2     # only the document it names changed
    code = "+++ b/src/Main.java\n-int n = 1;\n+int n = 2;\n"
    assert len(moved.verify_diff(code)) == 2
    rules = [{"decision": "D5", "paths": ["src/*.java"]}]
    assert moved.classify(code, rules) == {"D5": ["src/Main.java: -int n = 1;", "src/Main.java: +int n = 2;"]}
    assert all(r["decision"] and r["paths"] for r in moved.intended())     # the plan's list: every entry names its decision and its files


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


def test_merge_audit_over_the_tree_finds_nothing_missing_with_both_merge_logs(capsys):
    d = ROOT / "tools" / "docs"
    assert merge_audit.main(["--show", "0", "--log", str(d / "merge_log_A.txt"), "--log", str(d / "merge_log_B.txt"), "--log", str(d / "merge_log_N_A.txt"), "--log", str(d / "merge_log_N_B.txt"), "--log", str(d / "merge_log_N_T.txt"), "--log", str(d / "merge_log_N_L.txt")]) == 0
    assert "127 merged sources audited, 0 items not found" in capsys.readouterr().out


# i7m, plan section 5: a document's appended parts take the next numbers, so no two headings of one level carry the same leading number
# (a link to "section 3" and an anchor "#3-..." would otherwise name two places). The documents below are repaired by the guide merges of
# i7n; the second test keeps this list honest (a repaired document must leave it).
NUMBERING_PENDING_I7N: set[str] = set()
_NUMBERED = re.compile(r"(#{2,6}) (\d+(?:\.\d+)*)\.? ")


def _duplicate_numbers(path: str) -> list[str]:
    seen: dict[tuple[int, str], int] = {}
    out, fence = [], False
    for i, line in enumerate((ROOT / path).read_text(encoding="utf-8").splitlines(), 1):
        if line.lstrip().startswith("```"):
            fence = not fence
        elif not fence and (m := _NUMBERED.match(line)):
            key = (len(m.group(1)), m.group(2))
            if key in seen:
                out.append(f"{path}:{i}: two level-{key[0]} headings numbered {key[1]} (the other at line {seen[key]})")
            seen.setdefault(key, i)
    return out


def _numbered_documents() -> list[str]:
    return [p for p in corpus.tracked() if p.startswith("docs/") and not p.startswith(("docs/products/", "docs/contracts/packs/"))]


def test_no_document_numbers_two_headings_alike():
    bad = [x for p in _numbered_documents() if p not in NUMBERING_PENDING_I7N for x in _duplicate_numbers(p)]
    assert not bad, "\n".join(bad)


def test_the_numbering_pending_list_is_honest():
    fixed = [p for p in sorted(NUMBERING_PENDING_I7N) if not _duplicate_numbers(p)]
    assert not fixed, f"repaired: take these out of NUMBERING_PENDING_I7N: {fixed}"
