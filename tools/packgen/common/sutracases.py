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

"""A Sutra's test cases (2.9.4, docs/guides/DATA_GUIDE.md): `tests/<sutra>/cases.jsonl`, one case a line,
`{"case": "<name>", "document": {...}}`, beside the folder's `expect.yaml`. `sutra test` (SutraCli) reads it.

    text(cases)       the file's text from [(name, document)], in the order given
    read(folder)      [(name, document)] from cases.jsonl
"""
from __future__ import annotations

import json
from pathlib import Path

FILE = "cases.jsonl"


def line(name: str, doc: object) -> str:
    """One case as a JSON line: its name and the document."""
    return json.dumps({"case": str(name), "document": doc}, ensure_ascii=False, separators=(",", ":")) + "\n"


def text(cases: list[tuple[str, object]] | dict[str, object]) -> str:
    """cases: [(name, document)] or {name: document}; one line each, in the order given."""
    items = cases.items() if isinstance(cases, dict) else cases
    return "".join(line(n, d) for n, d in items)


def read(folder: Path) -> list[tuple[str, dict]]:
    """The cases of a Sutra's folder, (name, document) in order, from its cases file (none: an empty list)."""
    folder = Path(folder)
    f = folder / FILE
    out = []
    if f.is_file():
        for t in f.read_text(encoding="utf-8").splitlines():
            if t.strip():
                c = json.loads(t)
                out.append((c["case"], c["document"]))
    return out
