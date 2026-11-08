#!/usr/bin/env python3
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

"""Push rows into a Drishti lake through the data inbox (docs/guides/DATA_LAKE_GUIDE.md section 3.4). Python's standard library only.

One file:  push_inbox.py --inbox <data>/dataload/inbox --table trade_endur --date 2026-11-06 --source rows.jsonl.gz --lake risklake \
               --pack trading --kind trade --connector trade-endur --key TRADE_ID
A tar:     push_inbox.py --inbox ... --tar eod-2026-11-06 --part trade_endur:2026-11-06:a.jsonl.gz --part risk_hierarchy:2026-11-06:b.jsonl \
               --folders trade_endur=trading,risk_hierarchy=market-risk --lake risklake

The data file is written in <dataload>/staging/ and renamed into the inbox when complete (one atomic rename); its .ready file,
with the SHA-256 and the row count, is written LAST, the same way. A re-run of a day is safe with --mode replace."""
from __future__ import annotations

import argparse
import gzip
import hashlib
import io
import json
import os
import tarfile
from pathlib import Path


def documents(source: Path):
    """Your rows, one JSON object each: here read from a JSON Lines export (.jsonl or .jsonl.gz); yield dicts from your program instead."""
    opener = gzip.open if source.name.endswith(".gz") else open
    with opener(source, "rt", encoding="utf-8") as f:
        for line in f:
            if line.strip():
                yield json.loads(line)


def write_jsonl_gz(rows, out) -> int:
    """Rows as gzipped JSON Lines into a binary stream; the number written."""
    n = 0
    with gzip.GzipFile(fileobj=out, mode="wb", mtime=0) as gz:
        for row in rows:
            gz.write((json.dumps(row, separators=(",", ":"), ensure_ascii=False) + "\n").encode("utf-8"))
            n += 1
    return n


def sha256_of(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def place(inbox: Path, name: str, write) -> Path:
    """Writes inbox/<name> atomically: first into <dataload>/staging/ (beside the inbox, the same file system), flushed, then renamed in."""
    staging = inbox.parent / "staging"
    staging.mkdir(parents=True, exist_ok=True)
    final, tmp = inbox / name, staging / name
    with open(tmp, "wb") as f:
        write(f)
        f.flush()
        os.fsync(f.fileno())
    os.replace(tmp, final)
    return final


def ready_text(keys: dict) -> str:
    return "".join(f"{k}: {json.dumps(v) if isinstance(v, dict) else v}\n" for k, v in keys.items() if v is not None)


def push_file(inbox: Path, table: str, day: str, rows, **keys) -> Path:
    """One table, one business date: inbox/<table>.<day>.jsonl.gz, then its .ready with sha256 and rows."""
    counted = {}
    data = place(inbox, f"{table}.{day}.jsonl.gz", lambda f: counted.setdefault("rows", write_jsonl_gz(rows, f)))
    note = {**keys, "sha256": sha256_of(data), "rows": counted["rows"]}
    place(inbox, data.name + ".ready", lambda f: f.write(ready_text(note).encode("utf-8")))
    return data


def push_tar(inbox: Path, name: str, parts: list[tuple[str, str, object]], **keys) -> Path:
    """Several tables and dates in one archive: <table>/<YYYY-MM-DD>/part-0001.jsonl.gz each; one .ready, all or nothing."""
    total = 0

    def write(f):
        nonlocal total
        with tarfile.open(fileobj=f, mode="w") as tar:
            for table, day, rows in parts:
                buf = io.BytesIO()
                total += write_jsonl_gz(rows, buf)
                info = tarfile.TarInfo(f"{table}/{day}/part-0001.jsonl.gz")
                info.size = buf.tell()
                buf.seek(0)
                tar.addfile(info, buf)

    data = place(inbox, f"{name}.tar", write)
    note = {**keys, "sha256": sha256_of(data), "rows": total}
    place(inbox, data.name + ".ready", lambda f: f.write(ready_text(note).encode("utf-8")))
    return data


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--inbox", required=True, type=Path, help="<data>/dataload/inbox of the inbox's owner node")
    ap.add_argument("--table"), ap.add_argument("--date"), ap.add_argument("--source", type=Path)
    ap.add_argument("--tar", help="an archive's name (without .tar)")
    ap.add_argument("--part", action="append", default=[], help="table:YYYY-MM-DD:source, for --tar")
    ap.add_argument("--folders", help="table=pack,... for --tar")
    ap.add_argument("--lake"), ap.add_argument("--pack"), ap.add_argument("--kind"), ap.add_argument("--connector")
    ap.add_argument("--key", help="the id field, when the lake entry and the pack name none (TRADE_ID)")
    ap.add_argument("--mode", default="replace", choices=["replace", "append"])
    ap.add_argument("--hold", action="store_true", help="the inbox waits until hold is removed from the .ready file")
    a = ap.parse_args()
    a.inbox.mkdir(parents=True, exist_ok=True)
    common = {"lake": a.lake, "mode": a.mode, "hold": True if a.hold else None}
    if not a.tar:
        common["key"] = a.key
    if a.tar:
        parts = [(t, d, documents(Path(s))) for t, d, s in (p.split(":", 2) for p in a.part)]
        folders = dict(x.split("=", 1) for x in a.folders.split(",")) if a.folders else None
        out = push_tar(a.inbox, a.tar, parts, folders=folders, **common)
    else:
        out = push_file(a.inbox, a.table, a.date, documents(a.source), pack=a.pack, kind=a.kind, connector=a.connector, **common)
    print("pushed", out, "and", out.name + ".ready")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
