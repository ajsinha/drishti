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

"""The documentation gates (docs/architecture/TEST_HARNESS.md section 10), beside the help, links, rot, placeholders, settings, error-code and
image tests. They fail:
  - the feature register (config/docs-features.yaml) and the files disagree, or a feature has not exactly one guide and one tutorial
    (or says `after <release>`);
  - a count cap is passed (guides, tutorials, references, knowledge pages, Help's listed pages);
  - a customer document (a feature's guide or tutorial, a Knowledge page) carries version history;
  - Help lists a page under docs/architecture/ (internal) or a listed page's domain disagrees with the register.
They warn (pytest warnings, the summary lists them): a guide or tutorial over its line budget by more than 10%, contract pages over the
plan's count (the contracts are generated; their fold is tools/contracts.py's)."""
from __future__ import annotations

import re
import warnings
from pathlib import Path

import pytest
import yaml

pytestmark = pytest.mark.static

CONSOLE = Path(__file__).resolve().parent.parent
ROOT = CONSOLE.parent
DOCS = ROOT / "docs"
REGISTER = yaml.safe_load((ROOT / "config" / "docs-features.yaml").read_text(encoding="utf-8"))
FEATURES = REGISTER["features"]
REFERENCES = set(REGISTER.get("references") or [])

NOT_GUIDE_FOLDERS = {"examples"}      # docs/guides folders that are galleries, not guide folders
PAGE_CAP = 2000            # a guide folder's subpage: the lines one page may have (a folder counts as ONE guide for the caps; each page stays readable)
CAPS = {"guides": 24, "tutorials": 23, "references": 12, "knowledge": 5, "help": 67}
CONTRACTS_PLANNED = 30

HISTORY = re.compile(r"\b(?:since|new in|as of|added in|until|before|changed in) (?:v|version )?2\.\d+(?:\.\d+)*(?:\.[a-z])?\b"
                     r"|\((?:v)?2\.\d+\.\d+(?:\.[a-z])?(?:, [^)]*)?\)", re.I)


def _written(value) -> bool:
    return isinstance(value, str) and value.endswith(".md")


def _pages(rel: str) -> list[Path]:
    """The files of a guide or tutorial: the file itself, or, for a guide folder (docs/guides/<folder>/README.md, the index of subpages
    each with its own tutorial part), every page of the folder. A guide folder counts as ONE guide in the register, the caps and Help."""
    path = DOCS / rel
    if path.name == "README.md" and path.parent.parent == DOCS / "guides":
        return [path, *sorted(p for p in path.parent.glob("*.md") if p != path)]
    return [path]


def _customer_docs() -> list[Path]:
    out = [q for f in FEATURES for k in ("guide", "tutorial") if _written(f.get(k)) for q in _pages(f[k])]
    return out + sorted((DOCS / "knowledge").glob("*.md"))


def _help_rows() -> list[dict]:
    from core import modules
    data = yaml.safe_load((CONSOLE / "config" / "help.yaml").read_text(encoding="utf-8"))
    sections = [*(data.get("sections") or []), *modules.help_pages()]
    return [{**g, "_section": s["id"]} for s in sections for grp in s.get("groups") or [] for g in grp.get("guides") or []]


def test_every_feature_has_one_guide_and_one_tutorial_and_they_exist():
    ids = [f["id"] for f in FEATURES]
    assert len(ids) == len(set(ids)), ids
    for f in FEATURES:
        for key in ("guide", "tutorial"):
            v = str(f.get(key) or "")
            assert v, f"{f['id']}: no {key}"
            if _written(v):
                assert (DOCS / v).is_file(), f"{f['id']}: {v} does not exist"
            else:
                assert v == "none" and key == "tutorial" or v.startswith("after "), f"{f['id']}: {key}: {v!r} (a path, or 'after <release>')"
        assert f.get("domain") in {"core", "banking", "capitalmarkets", "economy"}, f["id"]
    guides = [f["guide"] for f in FEATURES if _written(f.get("guide"))]
    tutorials = [f["tutorial"] for f in FEATURES if _written(f.get("tutorial"))]
    assert len(guides) == len(set(guides)) and len(tutorials) == len(set(tutorials))


def test_every_guide_and_tutorial_on_disk_is_in_the_register():
    named = {f[k] for f in FEATURES for k in ("guide", "tutorial") if _written(f.get(k))} | REFERENCES
    for p in sorted((DOCS / "guides").glob("*.md")):
        assert f"guides/{p.name}" in named, f"docs/guides/{p.name} is neither a feature's guide nor a reference (config/docs-features.yaml)"
    for d in sorted(p for p in (DOCS / "guides").iterdir() if p.is_dir() and p.name not in NOT_GUIDE_FOLDERS and any(p.glob("*.md"))):     # a guide folder: its README.md is the register's guide
        assert (d / "README.md").is_file(), f"docs/guides/{d.name}/ has no README.md (the index of a guide folder)"
        assert f"guides/{d.name}/README.md" in named, f"docs/guides/{d.name}/README.md is neither a feature's guide nor a reference (config/docs-features.yaml)"
    for p in sorted((DOCS / "tutorials").glob("[0-9][0-9]-*.md")):
        assert f"tutorials/{p.name}" in named, f"docs/tutorials/{p.name} is no feature's tutorial (config/docs-features.yaml)"
    for r in REFERENCES:
        assert (DOCS / r).is_file(), r


def test_the_count_caps():
    guides = {f["guide"] for f in FEATURES if _written(f.get("guide"))}
    tutorials = list((DOCS / "tutorials").glob("[0-9][0-9]-*.md"))
    knowledge = list((DOCS / "knowledge").glob("*.md"))
    listed = [r for r in _help_rows() if not r.get("roles")]
    assert len(guides) <= CAPS["guides"], len(guides)
    assert len(tutorials) <= CAPS["tutorials"], len(tutorials)
    assert len(REFERENCES) <= CAPS["references"], len(REFERENCES)
    assert len(knowledge) <= CAPS["knowledge"], [p.name for p in knowledge]
    assert len(listed) <= CAPS["help"], len(listed)
    contracts = list((DOCS / "contracts").rglob("*.md"))
    if len(contracts) > CONTRACTS_PLANNED:
        warnings.warn(f"docs gate (warn): {len(contracts)} contract pages, the plan's count is {CONTRACTS_PLANNED} (tools/contracts.py)")


def test_docs_architecture_holds_only_what_is_built():
    """A design that is not built lives in docs/plans/ (frozen until built); docs/architecture/ holds no document whose own status line says so."""
    for doc in sorted((DOCS / "architecture").glob("*.md")):
        head = "\n".join(doc.read_text(encoding="utf-8").splitlines()[:40])
        assert not re.search(r"^\W*Status:\W*(design, not built|proposed)", head, re.M | re.I), f"{doc.name} is a design not built: move it to docs/plans/"
    for doc in sorted((DOCS / "plans").glob("*.md")):
        assert "not built" in doc.read_text(encoding="utf-8")[:3000].lower() or "proposed" in doc.read_text(encoding="utf-8")[:3000].lower(), f"{doc.name} is built or finished: fold it into a guide and delete it"


@pytest.mark.parametrize("doc", _customer_docs(), ids=lambda p: p.name)
def test_customer_documents_carry_no_version_history(doc):
    text = re.sub(r"```.*?```", "", doc.read_text(encoding="utf-8"), flags=re.S)
    hits = [m.group(0) for m in HISTORY.finditer(text)]
    assert not hits, f"{doc.relative_to(ROOT)}: version history in a customer document (CHANGELOG is the history): {hits[:5]}"


def test_help_lists_user_documents_only_and_their_domains_agree_with_the_register():
    by_doc = {}
    for f in FEATURES:
        for k in ("guide", "tutorial"):
            if _written(f.get(k)):
                by_doc[f[k]] = f
    for r in _help_rows():
        doc = str(r.get("doc") or "")
        assert not doc.startswith("architecture/") or r.get("roles") or doc in REFERENCES, f"Help lists an internal document: {r['slug']} ({doc})"
        assert r.get("domain") or r["_section"] == "internal" or r.get("roles"), f"{r['slug']}: a listed Help page without its domain"
        f = by_doc.get(doc)
        if f is not None and r.get("domain"):
            assert r["domain"] == f["domain"], f"{r['slug']}: Help says domain {r['domain']}, the register {f['domain']}"


def test_a_guide_folders_pages_stay_readable():
    """A guide folder is one guide for the caps and the register, but each of its pages is one a reader can hold: under PAGE_CAP lines."""
    for f in FEATURES:
        if _written(f.get("guide")) and len(_pages(f["guide"])) > 1:
            for page in _pages(f["guide"]):
                n = len(page.read_text(encoding="utf-8").splitlines())
                assert n <= PAGE_CAP, f"{page.relative_to(ROOT)}: {n} lines, over the {PAGE_CAP}-line page cap of a guide folder: split it"


def test_pages_within_their_budgets():
    over = []
    for f in FEATURES:
        for key, budget in (("guide", "budget"), ("tutorial", "tutorial-budget")):
            if _written(f.get(key)) and f.get(budget):
                n = sum(len(q.read_text(encoding="utf-8").splitlines()) for q in _pages(f[key]))
                if n > 1.1 * int(f[budget]):
                    over.append(f"{f[key]} {n}/{f[budget]}")
    if over:
        warnings.warn("docs gate (warn): over budget: " + ", ".join(over))
