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

"""The "data" section of each measure or feature guide: a short table linking to the data contracts, from tools/docs/guide_data.yaml.

    python3 tools/docs/guide_data.py            write every guide's data section
    python3 tools/docs/guide_data.py --check    fail on a stale section, or a kind without a data contract

The data contracts are the one home of every field and example record (docs/contracts/packs/<pack>.md, written by tools/contracts.py);
a guide only says which kinds its feature reads, whether each is needed and what for, and links to the contract. Per guide the spec names
its file and its section number, optionally the measure's own contract page, and per dataset the kind, whether the feature needs it and
what it is for. The tool writes, between ``<!-- guide-data:begin <id> -->`` and ``<!-- guide-data:end -->`` in the guide, one sentence
and the table. Text outside the markers is never touched. A kind with no contract, a contract link to a heading its page does not have,
or a stale section fails ``--check``.
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
SPEC = Path(__file__).resolve().with_name("guide_data.yaml")
CONTRACTS = ROOT / "config" / "packs"
PACK_PAGES = ROOT / "docs" / "contracts" / "packs"

BEGIN = "<!-- guide-data:begin {id} -->"
END = "<!-- guide-data:end -->"


def contracts() -> dict[str, dict]:
    """kind -> its contract (with ``_pack``)."""
    out = {}
    for f in sorted(CONTRACTS.glob("*/contracts/*.contract.yaml")):
        doc = yaml.safe_load(f.read_text(encoding="utf-8")) or {}
        if doc.get("kind"):
            doc["_pack"] = f.parts[-3]
            out[doc["kind"]] = doc
    return out


def slug(heading: str) -> str:
    """GitHub's anchor of a heading."""
    s = heading.strip().lower()
    s = re.sub(r"[^\w\- ]", "", s)
    return s.replace(" ", "-")


def contract_link(contract: dict, rel: str) -> str | None:
    """The kind's section on its pack page (docs/contracts/packs/<pack>.md), relative to the guide's folder; None when the page or the
    section is missing."""
    page = PACK_PAGES / f"{contract['_pack']}.md"
    if not page.exists():
        return None
    for line in page.read_text(encoding="utf-8").splitlines():
        if line.startswith("## ") and line.rstrip().endswith(f"(`{contract['kind']}`)"):
            return f"{rel}contracts/packs/{page.name}#{slug(line[3:])}"
    return None


def _rel(guide_file: str) -> str:
    depth = len(Path(guide_file).parent.relative_to("docs").parts)
    return "../" * depth


def build(guide: dict, cons: dict) -> tuple[str, list[str]]:
    """The generated block of one guide (without the markers), and the problems found."""
    problems: list[str] = []
    rel = _rel(guide["file"])
    own = guide.get("contract_page")
    lead = ("Every dataset the feature reads: whether it is needed, what for, and its **data contract**, the one home of its fields and of "
            "one example record from the committed test data.")
    if own:
        name = Path(own).name
        lead += f" The engine's own contract (what it is delivered, how it checks it, what it answers) is [{name}]({rel}{Path(own).relative_to('docs').as_posix()})."
    out = [lead, "", "| Kind | Needed? | What it is for | Data contract |", "|---|---|---|---|"]
    for ds in guide["datasets"]:
        kind, ckind = ds["kind"], ds.get("contract", ds["kind"])
        label = ds.get("label") or f"`{kind}`"
        where = f"{guide['id']}/{kind}"
        contract = cons.get(ckind) if ckind else None
        if ckind and contract is None:
            problems.append(f"{where}: no data contract for kind {ckind!r} (set contract: null and a contract_note if it has none)")
        if contract:
            link = contract_link(contract, rel)
            if link is None:
                problems.append(f"{where}: no section for {ckind!r} on docs/contracts/packs/{contract['_pack']}.md")
            cell = f"[`{ckind}`]({link}) ({contract.get('mnemonic', '—')}, {contract['_pack']})" if link else f"`{ckind}`"
        else:
            note = (ds.get("contract_note") or "").strip()
            if not note:
                problems.append(f"{where}: a kind without a contract needs a contract_note")
            cell = note or "—"
        out.append(f"| {label} | {ds['required']} | {ds['for'].strip()} | {cell} |")
    if guide.get("note"):
        out += ["", guide["note"].strip()]
    return "\n".join(out) + "\n", problems


def anchors(text: str) -> set[str]:
    """The anchors of a Markdown document's headings (outside code fences)."""
    out, fence = set(), False
    for line in text.splitlines():
        if line.startswith("```"):
            fence = not fence
        elif not fence and re.match(r"#{1,6} ", line):
            out.add(slug(line.lstrip("#")))
    return out


def splice(text: str, gid: str, block: str) -> str | None:
    """The guide's text with the block between its markers replaced; None when the markers are missing."""
    begin = BEGIN.format(id=gid)
    i = text.find(begin)
    j = text.find(END, i + len(begin)) if i >= 0 else -1
    if i < 0 or j < 0:
        return None
    return text[:i] + begin + "\n" + block + text[j:]


def load() -> list[dict]:
    return yaml.safe_load(SPEC.read_text(encoding="utf-8"))["guides"]


def run(check: bool) -> int:
    cons, problems, stale = contracts(), [], []
    for guide in load():
        path = ROOT / guide["file"]
        block, found = build(guide, cons)
        problems += found
        text = path.read_text(encoding="utf-8")
        new = splice(text, guide["id"], block)
        if new is None:
            problems.append(f"{guide['file']}: no {BEGIN.format(id=guide['id'])} … {END} markers")
            continue
        have = anchors(new)
        for a in sorted(set(re.findall(r"\]\(#([^)]+)\)", block)) - have):
            problems.append(f"{guide['file']}: the data section links to #{a}, a heading the guide does not have")
        if new != text:
            if check:
                stale.append(guide["file"])
            else:
                path.write_text(new, encoding="utf-8")
                print(f"wrote {guide['file']}")
    for p in problems:
        print(f"guide-data: {p}", file=sys.stderr)
    for f in stale:
        print(f"guide-data: stale: {f} (run python3 tools/docs/guide_data.py)", file=sys.stderr)
    return 1 if problems or stale else 0


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--check", action="store_true", help="fail on a stale section or a kind without a data contract")
    return run(ap.parse_args(argv).check)


if __name__ == "__main__":
    sys.exit(main())
