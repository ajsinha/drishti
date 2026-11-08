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

"""The "data" section of each measure or feature guide, from tools/docs/guide_data.yaml and the shipped sample data.

    python3 tools/docs/guide_data.py            write every guide's data section
    python3 tools/docs/guide_data.py --check    fail on a stale section, a kind without its contract, or a sample without its page

Per guide the spec names its file, its section number and, per dataset: the kind, the sample file glob under data/testdata (a
.jsonl.gz, or a tar of <table>/<date>/*.jsonl.gz as DATA_LAKE_GUIDE.md describes), whether the feature needs it, what it is for (written
by hand) and a link to the guide's own section. The tool writes, between ``<!-- guide-data:begin <id> -->`` and
``<!-- guide-data:end -->`` in the guide, an at-a-glance table and one subsection per dataset that LINKS the input's two homes and copies
neither: its data contract (the pack's contract page and its file, config/packs/<domain>/<pack>/contracts/<kind>.contract.yaml, with the
key and the field counts) and its example (the data-sample section docs/contracts/samples/<domain>.md#<table>, a real record with every
field explained, written by tools/testdata/sample_docs.py). Text outside the markers is never touched.

``--check`` fails on a stale section, a kind with no contract (unless ``contract: null``), a sample glob that matches no file, a sample
folder with no data-sample section, or a link to a heading the guide does not have.
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
if str(ROOT / "drishti-console") not in sys.path:
    sys.path.insert(0, str(ROOT / "drishti-console"))
from core.pack_paths import pack_dirs  # noqa: E402  (the one rule for where a pack's folder is)
SPEC = Path(__file__).resolve().with_name("guide_data.yaml")
TESTDATA = ROOT / "data" / "testdata"
CONTRACTS = ROOT / "config" / "packs"
PACK_PAGES = ROOT / "docs" / "contracts" / "packs"
SAMPLE_PAGES = ROOT / "docs" / "contracts" / "samples"

BEGIN = "<!-- guide-data:begin {id} -->"
END = "<!-- guide-data:end -->"



# ---------------------------------------------------------------- contracts

def contracts() -> dict[str, dict]:
    """kind -> its contract (with ``_pack``)."""
    out = {}
    for f in (f for d in pack_dirs(CONTRACTS).values() for f in sorted(d.glob("contracts/*.contract.yaml"))):
        doc = yaml.safe_load(f.read_text(encoding="utf-8")) or {}
        if doc.get("kind"):
            doc["_pack"] = f.parts[-3]
            doc["_file"] = f
            out[doc["kind"]] = doc
    return out


def slug(heading: str) -> str:
    """GitHub's anchor of a heading."""
    s = heading.strip().lower()
    s = re.sub(r"[^\w\- ]", "", s)
    return s.replace(" ", "-")


def contract_link(contract: dict, rel: str) -> str | None:
    """The kind's section on its pack page (docs/contracts/packs/<pack>.md), relative to the guide's folder."""
    page = PACK_PAGES / f"{contract['_pack']}.md"
    if not page.exists():
        return None
    for line in page.read_text(encoding="utf-8").splitlines():
        if line.startswith("## ") and line.rstrip().endswith(f"(`{contract['kind']}`)"):
            return f"{rel}contracts/packs/{page.name}#{slug(line[3:])}"
    return f"{rel}contracts/packs/{page.name}"


# ---------------------------------------------------------------- samples

def sample_files(ds: dict) -> list[Path]:
    """The dataset's committed sample files (empty when it has no ``sample``); a glob that matches nothing is an error."""
    glob = ds.get("sample")
    if not glob:
        return []
    files = sorted(p for p in TESTDATA.glob(glob) if p.is_file())
    if not files:
        raise ValueError(f"{ds['kind']}: no file matches data/testdata/{glob}")
    return files


def sample_link(ds: dict, rel: str) -> str | None:
    """The dataset's section on its data-sample page (docs/contracts/samples/<domain>.md#<table>, for the test data's folder
    <domain>/<table>), relative to the guide's folder; None when the page has no such section."""
    parts = Path(ds.get("sample") or "").parts
    if len(parts) < 2:
        return None
    page = SAMPLE_PAGES / f"{parts[0]}.md"
    if not page.exists() or f'<a id="{parts[1]}"></a>' not in page.read_text(encoding="utf-8"):
        return None
    return f"{rel}contracts/samples/{page.name}#{parts[1]}"


# ---------------------------------------------------------------- the section

def _rel(guide_file: str) -> str:
    depth = len(Path(guide_file).parent.relative_to("docs").parts)
    return "../" * depth


def build(guide: dict, cons: dict) -> tuple[str, list[str]]:
    """The generated block of one guide (without the markers), and the problems found."""
    problems: list[str] = []
    num, rel = str(guide["number"]), _rel(guide["file"])
    out = [f"### {num}.1 At a glance", "",
           "| Dataset (kind) | What it is for | Required? | Sample files (`data/testdata/…`) | Guide |", "|---|---|---|---|---|"]
    subs: list[str] = []
    for i, ds in enumerate(guide["datasets"], start=2):
        kind, ckind = ds["kind"], ds.get("contract", ds["kind"])
        contract = cons.get(ckind) if ckind else None
        where = f"{guide['id']}/{kind}"
        if ckind and contract is None:
            problems.append(f"{where}: no data contract for kind {ckind!r} (set contract: null if it has none)")
        link = contract_link(contract, rel) if contract else None
        label = ds.get("label") or f"`{kind}`"
        head = f"### {num}.{i} {label}: {ds['title']}"
        anchor = f"#{slug(head[4:])}"
        name = f"[{label}]({anchor})"
        files = f"`{ds['sample']}`" if ds.get("sample") else "no sample in the demo data"
        out.append(f"| {name} | {ds['for'].strip()} | {ds['required']} | {files} | {ds.get('guide') or '—'} |")

        subs += ["", head, "", ds["about"].strip(), ""]
        if contract:
            req = [f["name"] for f in contract.get("fields") or [] if f.get("presence") == "required"]
            key = ", ".join(f"`{k}`" for k in contract.get("key") or []) or "none"
            subs.append(f"- **Data contract:** [`{ckind}`]({link}) ({contract.get('mnemonic', '—')}), key {key}; "
                        f"{len(contract.get('fields') or [])} fields, {len(req)} required; the file "
                        f"[`{contract['_file'].name}`]({rel}../{contract['_file'].relative_to(ROOT).as_posix()}).")
        elif ds.get("contract_note"):
            subs.append(f"- **Data contract:** {ds['contract_note'].strip()}")
        try:
            sample_files(ds)
        except ValueError as e:
            problems.append(f"{where}: {e}")
            continue
        if not ds.get("sample"):
            subs.append(f"- **Example:** {(ds.get('sample_note') or 'no sample in the demo data.').strip()}")
            continue
        example = sample_link(ds, rel)
        if example is None:
            problems.append(f"{where}: docs/contracts/samples has no section for data/testdata/{ds['sample']}"
                            " (run python3 tools/testdata/sample_docs.py)")
            continue
        subs.append(f"- **Example:** [a real record of the test data, every field explained]({example}) (files "
                    f"`data/testdata/{ds['sample']}`).")
    if guide.get("note"):
        out += ["", guide["note"].strip()]
    return "\n".join(out + subs) + "\n", problems


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
    ap.add_argument("--check", action="store_true", help="fail on a stale section or a field the contract does not have")
    return run(ap.parse_args(argv).check)


if __name__ == "__main__":
    sys.exit(main())
