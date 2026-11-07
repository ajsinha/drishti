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

"""One JSON example of a kind, from the committed test data, formatted for a page: the one way the documentation shows a record.

Used by tools/contracts.py (the contract pages, docs/contracts/packs/<pack>.md: each kind's example). Deterministic and synthetic only:
records are read from data/testdata (its newest business date; a tar's members in name order) and, for a kind without test data, from
its pack's samples (config/packs/<pack>/samples/). The record shown is the most complete of the first ``SCAN`` read (the most
non-empty top-level fields; the first on a tie), envelope fields (``_meta``) left out. ``fmt`` indents by two and shortens long
arrays with a comment, ``/* … N more … */``, so the example stays readable whatever the record holds.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
TESTDATA = ROOT / "data" / "testdata"
PACKS = ROOT / "config" / "packs"

SCALAR_MAX = 9          # a scalar array longer than this shows its first and last HEAD
HEAD = 4
OBJECT_HEAD = 2         # an array of objects (or arrays) longer than this shows its first OBJECT_HEAD
KEYS_MAX = 12           # a nested object with more keys shows its first HEAD
STRING_MAX = 160        # a longer string is cut, ending in …
SCAN = 400              # records read to find the most complete one


# ---------------------------------------------------------------- formatting

def _scalar(v) -> str:
    if isinstance(v, str) and len(v) > STRING_MAX:
        v = v[:STRING_MAX - 1].rstrip() + "…"
    return json.dumps(v, ensure_ascii=False)


def _more(n: int, what: str = "more") -> str:
    return f"/* … {n} {what} … */"


def fmt(value, indent: int = 0) -> str:
    """JSON, indented by two, with long arrays and objects shortened by a comment."""
    pad, inner = "  " * indent, "  " * (indent + 1)
    if isinstance(value, dict):
        if not value:
            return "{}"
        items = list(value.items())
        lines = [f'{inner}{json.dumps(k)}: {fmt(v, indent + 1)}' for k, v in items[:HEAD if indent and len(items) > KEYS_MAX else None]]
        if indent and len(items) > KEYS_MAX:
            lines.append(f"{inner}{_more(len(items) - HEAD, 'more keys')}")
        return "{\n" + ",\n".join(lines) + f"\n{pad}}}"
    if isinstance(value, list):
        if not value:
            return "[]"
        nested = any(isinstance(v, (dict, list)) for v in value)
        if nested and len(value) > OBJECT_HEAD:
            parts = [inner + fmt(v, indent + 1) for v in value[:OBJECT_HEAD]] + [inner + _more(len(value) - OBJECT_HEAD)]
        elif not nested and len(value) > SCALAR_MAX:
            parts = ([inner + _scalar(v) for v in value[:HEAD]] + [inner + _more(len(value) - 2 * HEAD)]
                     + [inner + _scalar(v) for v in value[-HEAD:]])
        else:
            parts = [inner + fmt(v, indent + 1) for v in value]
        return "[\n" + ",\n".join(parts) + f"\n{pad}]"
    return _scalar(value)


# ---------------------------------------------------------------- the record

def _filled(rec: dict) -> int:
    return sum(1 for k, v in rec.items() if not k.startswith("_") and v not in (None, "", [], {}))


def _best(lines, origin: str) -> tuple[str, dict] | None:
    best, score = None, -1
    for i, line in enumerate(lines):
        if i >= SCAN:
            break
        rec = json.loads(line)
        if isinstance(rec, dict) and _filled(rec) > score:
            best, score = rec, _filled(rec)
    return (origin, best) if best is not None else None


def _datasets(pack: str, kind: str, testdata: Path) -> list[dict]:
    y = yaml.safe_load((testdata / "testdata.yaml").read_text(encoding="utf-8")) if (testdata / "testdata.yaml").is_file() else {}
    return [d for d in (y or {}).get("datasets") or [] if d.get("pack") == pack and d.get("kind") == kind and d.get("folder")]


def _from_testdata(folder: Path, testdata: Path) -> tuple[str, dict] | None:
    sys.path.insert(0, str(ROOT / "tools" / "lake"))
    import feedfiles  # noqa: E402  (tools/lake/feedfiles.py: the dated feed files and tars)
    got = feedfiles.read_table(folder) if folder.is_dir() else {}
    for day in sorted(got, reverse=True):             # the newest date; a day's change file may be empty: the one before
        for part in got[day]:
            rel = part.path.relative_to(ROOT).as_posix() if part.path.is_relative_to(ROOT) else part.path.name
            found = _best(part.lines(), rel + (f"` → `{part.member}" if part.member else ""))
            if found:
                return found
    return None


def _from_files(files: list[Path]) -> tuple[str, dict] | None:
    for f in files:
        rel = f.relative_to(ROOT).as_posix() if f.is_relative_to(ROOT) else f.name
        if f.suffix == ".json":
            rec = json.loads(f.read_text(encoding="utf-8"))
            if isinstance(rec, dict):
                return rel, rec
            continue
        if f.suffix == ".gz":
            import gzip
            text = gzip.decompress(f.read_bytes()).decode("utf-8")
        else:
            text = f.read_text(encoding="utf-8")
        found = _best((x for x in text.splitlines() if x.strip()), rel)
        if found:
            return found
    return None


def example(pack: str, kind: str, testdata: Path = TESTDATA, packs: Path = PACKS) -> tuple[str, dict] | None:
    """(where it came from, the record without envelope fields) of a kind, or None when nothing committed holds one."""
    testdata, packs = Path(testdata), Path(packs)
    got = None
    for d in _datasets(pack, kind, testdata):
        got = _from_testdata(testdata / d["folder"], testdata)
        if got:
            break
    if got is None:                                   # a kind generated before it has a command: its folder under data/testdata
        got = _from_files(sorted((testdata / pack / kind).glob("*.jsonl.gz")))
    if got is None:
        samples = packs / pack / "samples"
        got = _from_files(sorted(samples.glob(f"{kind}.jsonl")) + sorted(samples.glob(f"{kind}.part-*.jsonl"))
                          + sorted(samples.glob(f"{kind}/*.json")))
    if got is None:
        return None
    origin, rec = got
    return origin, {k: v for k, v in rec.items() if not k.startswith("_")}
