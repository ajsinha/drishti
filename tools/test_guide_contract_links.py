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

"""Guides and tutorials link a data input's two homes and never copy them.

An input's fields have one home, its data contract (modules/<module>/packs/<pack>/contracts/<kind>.contract.yaml, published on
docs/contracts/packs/<pack>.md); its JSON example has one home, its data sample (docs/contracts/samples/<domain>.md#<kind>, written by
tools/testdata/sample_docs.py from the committed synthetic test data). A guide or tutorial (docs/guides/*.md, docs/tutorials/*.md) only
links them:

1. a page that names a kind with a sample, by its contract file (`<kind>.contract.yaml`, or `{a,b}.contract.yaml`) or in backticks
   (`scenario-pv`; only the kinds whose name has a hyphen: one-word kinds such as `desk`, `book` or `fund` are also scope words, commands
   and columns), links that kind's sample section AND its contract (its section on the pack's contract page, or its YAML file);
   every sample section a page links exists;
2. no ```json or ```jsonc block of a page is a contract record: a block whose keys include three or more fields of one contract, at
   least half of the block's keys, copies that contract's data and fails, unless ALLOWED names it with its reason (a walk-through of an
   API answer, never a data reference; the list can only shrink).

    python3 -m unittest -q tools/test_guide_contract_links.py
    python3 tools/test_guide_contract_links.py          # the report
"""
from __future__ import annotations

import importlib.util
import re
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SAMPLES = ROOT / "docs" / "contracts" / "samples"
PAGES = ("docs/guides/*.md", "docs/tutorials/*.md", "config/packs/*/*/guides/*.md", "modules/*/packs/*/guides/*.md", "modules/banking/dmod/packs/*/guides/*.md")
FENCE = re.compile(r"^```.*?^```[ \t]*$", re.S | re.M)
JSON_BLOCK = re.compile(r"^```jsonc?[ \t]*\n(.*?)^```", re.S | re.M)
KEY = re.compile(r'"([A-Za-z_][A-Za-z0-9_]*)"\s*:')
CONTRACT_FILE = re.compile(r"(?:\{([a-z0-9,\- ]+)\}|([a-z][a-z0-9-]*))\.contract\.yaml")
TICKED = re.compile(r"`([a-z][a-z0-9]*(?:-[a-z0-9]+)+)`")
SAMPLE_LINK = re.compile(r"contracts/samples/([a-z0-9-]+)\.md#([a-z0-9_-]+)\)")

_spec = importlib.util.spec_from_file_location("drishti_guide_data_for_links", ROOT / "tools" / "docs" / "guide_data.py")
GD = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(GD)

# (page, a text the block contains) -> why the block is not a copy of a contract record. Can only shrink.
ALLOWED: dict[tuple[str, str], str] = {
    ("docs/guides/BUILD_GUIDE.md", '"SOURCE_SYSTEM": "MUREX", "TRADE_ID": "MX-20000001", "TRANSACTION_ID"'):
        "the answer of GET /entities/trade/MX-20000001/raw whose paths section 6's Sutra examples walk ($.LEGS[1].CASH_FLOWS…)",
    ("docs/guides/BUILD_GUIDE.md", '"COUNTERPARTY_ID": "CP-MERIDIAN-RE"'):
        "the answer of GET /entities/counterparty/CP-MERIDIAN-RE/raw that section 12's first Sutra reads, path by path",
    ("docs/guides/RACHANA_REFERENCE.md", '"SOURCE_SYSTEM": "MUREX", "TRADE_ID": "MX-20000001", "TRADE_STATUS"'):
        "the answer of GET /api/v1/entities/trade/MX-20000001/raw that the reference's example Sutra reads, path by path",
}


def anchors() -> dict[str, str]:
    """kind -> its samples page (docs/contracts/samples/<domain>.md), from the pages' <a id="<kind>"></a>."""
    out: dict[str, str] = {}
    for page in sorted(SAMPLES.glob("*.md")):
        for a in re.findall(r'<a id="([^"]+)"></a>', page.read_text(encoding="utf-8")):
            out.setdefault(a, page.stem)
    return out


def contracts() -> dict[str, dict]:
    """kind -> its contract (guide_data.contracts: with _pack and _file)."""
    return GD.contracts()


def contract_targets(contract: dict) -> tuple[str, str]:
    """What a link to the kind's contract ends with: its section on the pack's contract page, or its YAML file's path."""
    return GD.contract_link(contract, ""), contract["_file"].relative_to(ROOT).as_posix()


def pages() -> list[Path]:
    return sorted(p for g in PAGES for p in ROOT.glob(g))


def named_kinds(text: str, known: dict[str, str]) -> set[str]:
    """The kinds with a sample a page names: by contract file (anywhere) or in backticks (outside code blocks)."""
    out: set[str] = set()
    for braces, one in CONTRACT_FILE.findall(text):
        out |= {k.strip() for k in braces.split(",")} if braces else {one}
    out |= set(TICKED.findall(FENCE.sub("", text)))
    return {k for k in out if k in known}


def missing_links(path: Path, known: dict[str, str], cons: dict[str, dict]) -> list[str]:
    text, rel = path.read_text(encoding="utf-8"), path.relative_to(ROOT)
    linked = set(SAMPLE_LINK.findall(text))
    targets = set(re.findall(r"\]\(([^)\s]+)\)", text))
    probs = []
    for k in sorted(named_kinds(text, known)):
        if (known[k], k) not in linked:
            probs.append(f"{rel}: names `{k}` but does not link its sample (docs/contracts/samples/{known[k]}.md#{k})")
        if k in cons:
            section, yaml_file = contract_targets(cons[k])
            if not any(t.endswith(section) or t.endswith(yaml_file) for t in targets):
                probs.append(f"{rel}: names `{k}` but does not link its contract (docs/{section} or {yaml_file})")
    probs += [f"{rel}: links samples/{d}.md#{k}, which is not a sample section" for d, k in sorted(linked) if known.get(k) != d]
    return probs


def copied_records(rel: str, text: str, fields: dict[str, set[str]]) -> list[str]:
    """The page's (its path from the root, its text) json blocks that copy a contract record."""
    probs = []
    for m in JSON_BLOCK.finditer(text):
        block = m.group(1)
        if any(page == rel and needle in block for page, needle in ALLOWED):
            continue
        keys = set(KEY.findall(block))
        n, kind = max(((len(keys & names), kind) for kind, names in fields.items()), default=(0, ""))
        if n >= 3 and 2 * n >= len(keys):
            line = text[:m.start()].count("\n") + 1
            probs.append(f"{rel}:{line}: a ```json block copies the {kind} contract's record ({n} of its {len(keys)} keys): "
                         f"link its contract and docs/contracts/samples/…#{kind} instead")
    return probs


def field_names(cons: dict[str, dict]) -> dict[str, set[str]]:
    """kind -> the field names of its contract YAML."""
    return {k: {f["name"] for f in c.get("fields") or [] if isinstance(f, dict) and f.get("name")} for k, c in cons.items()}


def report() -> list[str]:
    known, cons = anchors(), contracts()
    fields = field_names(cons)
    return [p for page in pages() for p in missing_links(page, known, cons) + _copied(page, fields)]


class GuideContractLinks(unittest.TestCase):
    def test_every_named_kind_links_its_contract_and_its_sample(self):
        known, cons = anchors(), contracts()
        probs = [p for page in pages() for p in missing_links(page, known, cons)]
        self.assertFalse(probs, "\n".join(probs))

    def test_no_guide_copies_a_contract_record(self):
        fields = field_names(contracts())
        probs = [p for page in pages() for p in _copied(page, fields)]
        self.assertFalse(probs, "\n".join(probs))

    def test_the_allow_list_is_used_and_explained(self):
        texts = {p.relative_to(ROOT).as_posix(): p.read_text(encoding="utf-8") for p in pages()}
        stale = [k for k in ALLOWED if k[1] not in texts.get(k[0], "")]
        self.assertFalse(stale, f"remove from ALLOWED (no such block): {stale}")
        self.assertTrue(all(len(why) > 20 for why in ALLOWED.values()), "every allowed block says why")
        self.assertLessEqual(len(ALLOWED), 3, "the allow-list only shrinks")

    def test_a_copied_record_and_a_missing_link_are_caught(self):
        cons, known = contracts(), anchors()
        text = '```json\n{"SCENARIO_PV_ID": "SPV-1", "BASE_PV": 1.0, "SCENARIO_PV": [1.0], "CURRENCY": "USD"}\n```\n'
        self.assertIn("copies the scenario-pv", "\n".join(copied_records("docs/guides/X.md", text, field_names(cons))))
        self.assertIn("scenario-pv", "\n".join(sorted(named_kinds("The `scenario-pv` rows.", known))))


def _copied(page: Path, fields: dict[str, set[str]]) -> list[str]:
    return copied_records(page.relative_to(ROOT).as_posix(), page.read_text(encoding="utf-8"), fields)


if __name__ == "__main__":
    out = report()
    print("\n".join(out) or "ok")
    sys.exit(1 if out else 0)
