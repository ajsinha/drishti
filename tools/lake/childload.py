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

"""Child kinds in ``lake build``: a pack's arrays of records (``LEGS`` of ``trade``) loaded as kinds of their own, in Delta or Iceberg alike
(docs/guides/DATA_LAKE_GUIDE.md section 5.7, docs/guides/BUILD_GUIDE.md section 8.1.4).

The rules are the pack's ``ingest:`` block (``child-of``), read by tools/packgen/childkinds.py, the one implementation of the split in
Python (its ``explode``); the parallel loader reads the same block (com.ash.drishti.lakeload.ChildKinds). This module only carries them
out for a build: after the parent's load it writes each parent file's child rows to a scratch file named ``<child kind>.<business
date>.jsonl`` (the business date the parent's file was loaded under: ``filedate.decide``, as the stager decides it) and builds each child
kind from the scratch files with the same options (format, mode, lake, engine), so a child table has the layout, id, promoted columns
and date of any other table of the pack, whatever the format.
"""
from __future__ import annotations

import copy
import json
import pathlib
import shutil
import sys
import tempfile
from datetime import date

import filedate
import jsonstream

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1] / "packgen"))
import childkinds as CK  # noqa: E402


def children_of(pack: str | None, kind: str | None) -> list:
    """The child kinds the pack declares for ``kind`` (its ``ingest:`` block); none without a pack."""
    if not pack or not kind:
        return []
    import yaml
    m = yaml.safe_load((pathlib.Path(pack) / "pack.yaml").read_text(encoding="utf-8")) or {}
    return CK.of_ingest(m.get("ingest")).get(kind, [])


def _day(a, f):
    """The business date the parent's file was loaded under, as the stager decides it (--as-of, else its .ready file's, else its tar
    folder's, its name's, the business today)."""
    folder = getattr(f, "date", None)
    if folder is not None:
        return folder
    given = (getattr(a, "_markers", {}).get(str(getattr(f, "archive", f))) or {}).get("business-date")
    as_of = date.fromisoformat(a.as_of) if getattr(a, "as_of", None) else None
    dated = getattr(getattr(f, "archive", None), "name", None) or getattr(f, "name", None)
    return filedate.decide(as_of or given, filedate.of_input(f), getattr(a, "date_from", None) or "auto", None, dated or str(f)).date


def split(a, kind: str, table: str | None, children: list, scratch: pathlib.Path) -> dict[str, int]:
    """Writes the child rows of every parent document of ``a.src`` to ``scratch/<child>.<date>.jsonl``; {child kind: rows}."""
    rows = {c.kind: 0 for c in children}
    handles: dict = {}
    try:
        for f in jsonstream.input_files(a.src, a.recursive, table=table or kind, tar_table=getattr(a, "_tar_table", None)):
            day = _day(a, f)
            if day is None:
                continue
            for doc, _where in jsonstream.Reader(f):
                if isinstance(doc, jsonstream.Bad) or not isinstance(doc, dict):
                    continue
                for c in children:
                    for row in CK.explode(doc, c):
                        fh = handles.get((c.kind, day))
                        if fh is None:
                            fh = handles[(c.kind, day)] = open(scratch / f"{c.kind}.{day.isoformat()}.jsonl", "w", encoding="utf-8")
                        fh.write(json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n")
                        rows[c.kind] += 1
    finally:
        for fh in handles.values():
            fh.close()
    return rows


def run(a, kind: str, table: str | None, children: list, build_run) -> tuple[int, dict]:
    """Builds every child kind of ``kind`` (after its parent's load); (worst exit code, {child kind: key facts of its summary}).
    ``build_run`` is lake build's ``run`` (a child build is a build of the scratch files with the parent's options)."""
    scratch = pathlib.Path(tempfile.mkdtemp(prefix="drishti-children-"))
    worst, out = 0, {}
    try:
        rows = split(a, kind, table, children, scratch)
        for c in children:
            if not rows[c.kind]:
                out[c.kind] = {"status": "empty", "rows": 0}
                continue
            sub = copy.copy(a)
            sub.recursive, sub.kind, sub.table, sub._tar_table = False, c.kind, None, None
            sub.key = sub.date = sub.promote = sub.partition_by = sub.latest_by = sub.vector = sub.connector = None
            sub.as_of, sub.date_from, sub.summary, sub.signal, sub.new_table = None, "auto", None, None, False
            sub._markers = {}
            sub.src = [str(p) for p in sorted(scratch.glob(f"{c.kind}.*.jsonl"))]
            code, s = build_run(sub, lambda *_x, **_k: None)
            out[c.kind] = {k: s.get(k) for k in ("status", "table", "rows", "error", "engine", "accepted", "rejected")}
            worst = max(worst, code)
            if code in (1, 3):
                break
    finally:
        shutil.rmtree(scratch, ignore_errors=True)
    return worst, out
