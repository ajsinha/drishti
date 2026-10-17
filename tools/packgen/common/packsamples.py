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

"""A pack's samples: `samples/catalog.json` and ONE JSON Lines file per kind, `samples/<kind>.jsonl` (one document a line,
in catalog order), split into `samples/<kind>.part-001.jsonl`, `part-002` ... only when a kind's file would pass the part size
(--sample-part-mb, default 50 MB). The older layout, one `samples/<kind>/<id>.json` per document, is still read everywhere
(the retired example packs and packs users wrote keep it); shipped and new packs are written as JSONL.

    files(docs, catalog, part_mb)   {relative path: text} of a samples folder (catalog.json and the .jsonl files)
    read(samples_dir)               {kind: {id: document}} from either layout
    kind_files(samples_dir, kind)   the .jsonl file(s) of a kind, in order (empty for the older layout)

A line's id is the catalog's: the entry at the line's position when the document holds it as a top-level value (writers keep the
catalog's order), else the first top-level value of the document that the catalog lists as an id of that kind.
"""
from __future__ import annotations

import json
import re
from pathlib import Path

PART_MB = 50          # a kind's samples are split into parts only above this (writers' --sample-part-mb)
PART = re.compile(r"^(?P<kind>.+?)(?:\.part-(?P<n>\d{3}))?\.jsonl$")


def part_mb(argv: list[str]) -> float:
    """--sample-part-mb N from a command line, else PART_MB."""
    return float(argv[argv.index("--sample-part-mb") + 1]) if "--sample-part-mb" in argv else PART_MB


def line(doc: dict) -> str:
    return json.dumps(doc, ensure_ascii=False, separators=(",", ":"))


def files(docs: dict[str, dict[str, dict]], catalog: list[dict], part_mb: float = PART_MB, catalog_indent: int | None = 1) -> dict[str, str]:
    """docs {kind: {id: document}}; catalog [{kind, id, title, subtitle}] (its order is the lines' order). Returns
    {"catalog.json": text, "<kind>.jsonl" or "<kind>.part-NNN.jsonl": text}."""
    out: dict[str, str] = {"catalog.json": (json.dumps(catalog, indent=catalog_indent, ensure_ascii=False) if catalog_indent
                                            else json.dumps(catalog, ensure_ascii=False, separators=(",", ":"))) + "\n"}
    limit = int(part_mb * 1024 * 1024)
    order: dict[str, list[str]] = {}
    for e in catalog:
        order.setdefault(e["kind"], []).append(str(e["id"]))
    for kind in sorted(docs):
        ids = order.get(kind) or sorted(docs[kind])
        parts, cur, size = [], [], 0
        for i in ids:
            if i not in docs[kind]:
                continue
            text = line(docs[kind][i]) + "\n"
            if cur and size + len(text.encode("utf-8")) > limit:
                parts.append(cur)
                cur, size = [], 0
            cur.append(text)
            size += len(text.encode("utf-8"))
        if cur:
            parts.append(cur)
        if len(parts) == 1:
            out[f"{kind}.jsonl"] = "".join(parts[0])
        else:
            for n, p in enumerate(parts, 1):
                out[f"{kind}.part-{n:03d}.jsonl"] = "".join(p)
    return out


def kind_files(samples_dir: Path, kind: str) -> list[Path]:
    one = samples_dir / f"{kind}.jsonl"
    if one.is_file():
        return [one]
    return sorted(samples_dir.glob(f"{kind}.part-[0-9][0-9][0-9].jsonl"))


def read(samples_dir: Path) -> dict[str, dict[str, dict]]:
    """Every sample document of a pack's samples folder, {kind: {id: document}}, whichever layout it has."""
    samples_dir = Path(samples_dir)
    if not samples_dir.is_dir():
        return {}
    cat_file = samples_dir / "catalog.json"
    catalog = json.loads(cat_file.read_text(encoding="utf-8")) if cat_file.is_file() else []
    order: dict[str, list[str]] = {}
    for e in catalog:
        order.setdefault(e["kind"], []).append(str(e["id"]))
    out: dict[str, dict[str, dict]] = {}
    kinds = {PART.match(f.name).group("kind") for f in samples_dir.glob("*.jsonl")}
    for kind in sorted(kinds):
        ids, n = set(order.get(kind, [])), 0
        for f in kind_files(samples_dir, kind):
            for text in f.read_text(encoding="utf-8").splitlines():
                if not text.strip():
                    continue
                n += 1
                doc = json.loads(text)
                got = out.setdefault(kind, {})
                pos = order[kind][n - 1] if n <= len(order.get(kind, [])) else None
                values = {str(v) for v in doc.values() if isinstance(v, (str, int)) and not isinstance(v, bool)}
                if pos is not None and pos not in got and pos in values:
                    id_ = pos                                          # the catalog's order is the lines' order
                else:
                    id_ = next((str(v) for v in doc.values() if isinstance(v, (str, int)) and not isinstance(v, bool)
                                and str(v) in ids and str(v) not in got), None) or (pos if pos not in got else None)
                got[id_ or f"{kind}-{n}"] = doc
    for d in sorted(p for p in samples_dir.iterdir() if p.is_dir()):         # the older layout: <kind>/<id>.json
        for f in sorted(d.glob("*.json")):
            out.setdefault(d.name, {}).setdefault(f.stem, json.loads(f.read_text(encoding="utf-8")))
    return out
