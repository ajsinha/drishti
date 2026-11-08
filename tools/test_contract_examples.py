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

"""The JSON examples of the computed-measure contracts (docs/contracts/<MNEMONIC>.md): every page shows at least one ```json block;
every block parses (one JSON value, or JSON Lines, or several values one after the other); every key in a block is a field of that
page's field tables (a top-level key in a table's first column, a nested key anywhere in a table), of a pack contract the page links
(config/packs/.../contracts/<kind>.contract.yaml) or of the shared rules' tables (docs/contracts/README.md: the published official
results' shared columns); and every field a table marks required appears in at least one of the page's examples.

    python3 -m unittest -q tools/test_contract_examples.py
    python3 tools/test_contract_examples.py          # the per-page report
"""
from __future__ import annotations

import json
import re
import sys
import unittest
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parent.parent
CONTRACTS = ROOT / "docs" / "contracts"
TOKEN = re.compile(r"`([^`]+)`")
NAME = re.compile(r"[A-Za-z_][A-Za-z0-9_\-]*")
FIELD = re.compile(r"^[A-Za-z][A-Za-z0-9_]*$")  # stored rows UPPER_SNAKE, API answers camelCase
JSON_BLOCK = re.compile(r"^```json[ \t]*\n(.*?)^```", re.S | re.M)
PACK_LINK = re.compile(r"\((?:\.\./)+(config/packs/[^)#\s]+\.contract\.yaml)\)")
PRESENCE_HEADERS = {"req.", "req", "presence", "required"}


def pages() -> list[Path]:
    """The computed-measure contract pages: every Markdown page directly under docs/contracts except its README."""
    return sorted(p for p in CONTRACTS.glob("*.md") if p.name != "README.md")


def _names(cell: str) -> set[str]:
    """The field names a table cell names in backticks: `A`, `B[]`, `LIST[].ITEM`, `A`/`B` all count; values like `2026-09-30` do not."""
    out: set[str] = set()
    for tok in TOKEN.findall(cell):
        for part in re.split(r"[.\s,/]+", tok.replace("[]", "")):
            if NAME.fullmatch(part) and not part.isdigit():
                out.add(part)
    return out


def _cells(line: str) -> list[str]:
    body = line.strip()
    if body.startswith("|"):
        body = body[1:]
    if body.endswith("|"):
        body = body[:-1]
    return [c.strip() for c in re.split(r"(?<!\\)\|", body)]


class Page:
    """One contract page read for its tables, its linked pack contracts and its JSON examples."""

    def __init__(self, path: Path):
        self.path = path
        self.text = path.read_text(encoding="utf-8")
        self.top: set[str] = set()        # first-column names: a record's own fields
        self.vocabulary: set[str] = set()  # any name in any table cell: nested fields, map keys
        self.required: set[str] = set()
        self._read_tables()
        self._read_pack_contracts()
        if path.name != "README.md" and path.parent == CONTRACTS:
            shared = Page(CONTRACTS / "README.md")  # the shared rules: the published official results' shared columns
            self.top |= shared.top
            self.vocabulary |= shared.vocabulary
        self.blocks = JSON_BLOCK.findall(self.text)

    def _read_tables(self) -> None:
        lines = self.text.splitlines()
        i = 0
        while i < len(lines):
            if lines[i].lstrip().startswith("|") and i + 1 < len(lines) and re.match(r"^\s*\|[\s:|-]+\|\s*$", lines[i + 1]):
                header = [h.lower() for h in _cells(lines[i])]
                presence = next((k for k, h in enumerate(header) if h in PRESENCE_HEADERS), None)
                i += 2
                while i < len(lines) and lines[i].lstrip().startswith("|"):
                    cells = _cells(lines[i])
                    first = {n for n in _names(cells[0]) if FIELD.match(n)} if cells else set()
                    self.top |= first
                    for c in cells:
                        self.vocabulary |= _names(c)
                    if presence is not None and presence < len(cells) and cells[presence].lower().startswith("required"):
                        self.required |= first
                    i += 1
            else:
                i += 1
        self.vocabulary |= self.top

    def _read_pack_contracts(self) -> None:
        for rel in sorted(set(PACK_LINK.findall(self.text))):
            f = ROOT / rel
            if not f.is_file():
                continue
            doc = yaml.safe_load(f.read_text(encoding="utf-8")) or {}
            stack = list(doc.get("fields") or [])
            for nested in (doc.get("nested") or {}).values():
                for fd in nested or []:
                    if isinstance(fd, dict) and fd.get("name"):
                        self.vocabulary.add(fd["name"])
            while stack:
                fd = stack.pop()
                if isinstance(fd, dict) and fd.get("name"):
                    self.top.add(fd["name"])
                    self.vocabulary.add(fd["name"])
                    stack.extend(fd.get("fields") or [])
                    stack.extend((fd.get("items") or {}).get("fields") or [] if isinstance(fd.get("items"), dict) else [])

    def records(self) -> list[tuple[int, object]]:
        """Each block's parsed values as (block number, value); raises ValueError naming the block that does not parse."""
        out = []
        dec = json.JSONDecoder()
        for n, block in enumerate(self.blocks, 1):
            pos, text = 0, block
            while True:
                while pos < len(text) and text[pos].isspace():
                    pos += 1
                if pos >= len(text):
                    break
                try:
                    value, pos = dec.raw_decode(text, pos)
                except json.JSONDecodeError as e:
                    raise ValueError(f"{self.path.name} JSON block {n}: {e}") from e
                out.append((n, value))
        return out

    def problems(self) -> list[str]:
        if not self.blocks:
            return [f"{self.path.name}: no ```json example"]
        try:
            recs = self.records()
        except ValueError as e:
            return [str(e)]
        found: list[str] = []
        seen: set[str] = set()

        def walk(value, n: int, depth: int, where: str) -> None:
            if isinstance(value, list):
                for v in value:
                    walk(v, n, depth, where)
            elif isinstance(value, dict):
                for k, v in value.items():
                    seen.add(k)
                    known = self.top if depth == 0 else self.vocabulary
                    if k not in known:
                        found.append(f"{self.path.name} JSON block {n}: {where}{k} is in no field table of the page")
                    walk(v, n, depth + 1, f"{where}{k}.")

        for n, value in recs:
            walk(value, n, 0, "")
        for f in sorted(self.required - seen):
            found.append(f"{self.path.name}: required field {f} appears in no example")
        return found


class ContractExamples(unittest.TestCase):
    def test_every_page_has_parsing_examples_matching_its_tables(self):
        self.assertTrue(pages(), "no contract pages found")
        problems = [p for path in pages() for p in Page(path).problems()]
        self.assertEqual([], problems, "\n" + "\n".join(problems))

    def test_the_checks_catch_a_bad_page(self):
        import tempfile
        with tempfile.TemporaryDirectory(dir=ROOT / "drishti-console" / "test-tmp" if (ROOT / "drishti-console" / "test-tmp").is_dir() else None) as d:
            p = Path(d) / "X.md"
            table = "| Field | Type | Req. | Meaning |\n|---|---|---|---|\n| `A_ID` | id | required | |\n| `B`, `C` | text | optional | `ITEMS[]`: `X` |\n"
            p.write_text("# X\n" + table, encoding="utf-8")
            self.assertIn("no ```json", Page(p).problems()[0])
            p.write_text("# X\n" + table + "```json\n{\"A_ID\": 1,}\n```\n", encoding="utf-8")
            self.assertIn("block 1", Page(p).problems()[0])
            p.write_text("# X\n" + table + "```json\n{\"B\": 1, \"ZED\": 2, \"C\": [{\"X\": 1, \"Y\": 2}]}\n```\n", encoding="utf-8")
            probs = Page(p).problems()
            self.assertTrue(any("ZED" in s for s in probs) and any("C.Y" in s for s in probs) and any("A_ID" in s for s in probs), probs)
            p.write_text("# X\n" + table + "```json\n{\"A_ID\": \"a\", \"B\": 1}\n{\"A_ID\": \"b\", \"C\": [{\"X\": 1}]}\n```\n", encoding="utf-8")
            self.assertEqual([], Page(p).problems())


def report() -> int:
    bad = 0
    for path in pages():
        page = Page(path)
        probs = page.problems()
        try:
            nrec = len(page.records())
        except ValueError:
            nrec = 0
        print(f"{path.name:24} blocks={len(page.blocks):2} records={nrec:3} required={len(page.required):3} problems={len(probs)}")
        for s in probs:
            print("   ", s)
        bad += bool(probs)
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(report())
