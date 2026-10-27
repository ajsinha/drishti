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

"""convert: turn CSV, a JSON array or a folder of per-entity .json files into JSON Lines, Drishti's data format (one JSON object a line).

    python3 tools/drishti.py convert trades.csv                       trades.jsonl: one line per CSV row
    python3 tools/drishti.py convert book.json --out book.jsonl       a JSON array: one line per element (a single object: one line)
    python3 tools/drishti.py convert samples/trade                    trade.jsonl beside the folder: one line per <id>.json
    python3 tools/drishti.py convert feeds/fixing/SOFR-HISTORY.csv --rows-document
                                                                       one line {"id": "SOFR-HISTORY", "rows": [...]}: what the file
                                                                       connector made of a CSV feed file, so FIX SOFR-HISTORY still opens
    python3 tools/drishti.py convert big.json.gz --out -              to standard output

CSV input is deprecated (docs/guides/DATA_FORMATS.md): a header row, then one row a line; cells are typed as the file connector types
them (numbers, true/false; an empty cell is null). A JSON array is streamed element by element, never read whole; .gz files are read
and, with an --out ending .gz, written gzipped. A folder's file name becomes the line's "id" when the document has none (the id the file
connector gave it). The default output is the input's path with .jsonl (a folder: <folder>.jsonl beside it); an existing output is
replaced only with --force. Exit codes: 0 converted, 1 a file could not be read, 2 usage.
"""
from __future__ import annotations

import argparse
import csv
import gzip
import json
import pathlib
import sys

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE / "lake"))


def typed(cell: str):
    """A CSV cell as the file connector's CsvReader types it: empty -> null, true/false, integers, decimals, else text."""
    s = cell.strip()
    if not s:
        return None
    if s.lower() in ("true", "false"):
        return s.lower() == "true"
    c = s[0]
    if c.isdigit() or (c in "-+." and len(s) > 1):
        try:
            return float(s) if any(x in s for x in ".eE") else int(s)
        except ValueError:
            pass
    return s


def _open(path: pathlib.Path):
    return gzip.open(path, "rt", encoding="utf-8", newline="") if path.name.endswith(".gz") else open(path, encoding="utf-8", newline="")


def csv_rows(path: pathlib.Path):
    with _open(path) as f:
        reader = csv.reader(f)
        header = next(reader, None)
        if header is None:
            return
        for row in reader:
            if not any(c.strip() for c in row):
                continue
            yield {h: typed(row[i] if i < len(row) else "") for i, h in enumerate(header)}


def json_docs(path: pathlib.Path):
    """The documents of a JSON file, streamed (tools/lake/jsonstream.py, as lake build reads them): each element of an array, each line
    of JSON Lines, or the one object; gzip either way. A document that cannot be read stops the conversion, naming where it is."""
    import jsonstream
    for doc, where in jsonstream.Reader(path):
        if isinstance(doc, jsonstream.Bad):
            raise ValueError(f"{doc.where}: {doc.reason}")
        yield doc


def folder_docs(path: pathlib.Path):
    """One document per <id>.json in the folder, in name order, its file name as "id" when it has none."""
    for f in sorted(path.glob("*.json")):
        with open(f, encoding="utf-8") as fh:
            doc = json.load(fh)
        if isinstance(doc, dict) and "id" not in doc:
            doc = {"id": f.stem, **doc}
        yield doc


def documents(path: pathlib.Path, rows_document: bool = False):
    name = path.name.lower().removesuffix(".gz")
    if path.is_dir():
        return folder_docs(path)
    if name.endswith(".csv"):
        if rows_document:
            stem = path.name.removesuffix(".gz").rsplit(".", 1)[0]
            return iter([{"id": stem, "rows": list(csv_rows(path))}])
        return csv_rows(path)
    if name.endswith((".jsonl", ".ndjson", ".json")):
        return json_docs(path)
    raise ValueError(f"{path}: not a .csv, .json, .jsonl or a folder of .json files")


def default_out(path: pathlib.Path) -> pathlib.Path:
    if path.is_dir():
        return path.parent / (path.name + ".jsonl")
    base = path.name.removesuffix(".gz")
    return path.with_name(base.rsplit(".", 1)[0] + ".jsonl")


def convert(src: pathlib.Path, out: str | None = None, rows_document: bool = False, force: bool = False, say=print) -> int:
    """Writes src's documents as JSON Lines to out (a path, "-" for standard output, None for the default). Returns the line count."""
    target = None if out == "-" else pathlib.Path(out) if out else default_out(src)
    if target is not None and target.resolve() == src.resolve():
        raise ValueError(f"{src} is already the output; give --out")
    if target is not None and target.exists() and not force:
        raise ValueError(f"{target} exists; give --force to replace it")
    docs = documents(src, rows_document)
    n = 0
    if target is None:
        for d in docs:
            sys.stdout.write(json.dumps(d, ensure_ascii=False, separators=(",", ":")) + "\n")
            n += 1
        return n
    target.parent.mkdir(parents=True, exist_ok=True)
    tmp = target.with_name(target.name + ".part")
    try:
        with (gzip.open(tmp, "wt", encoding="utf-8") if target.name.endswith(".gz") else open(tmp, "w", encoding="utf-8")) as f:
            for d in docs:
                f.write(json.dumps(d, ensure_ascii=False, separators=(",", ":")) + "\n")
                n += 1
        tmp.replace(target)
    finally:
        if tmp.exists():
            tmp.unlink()
    say(f"{target}: {n} lines from {src}")
    return n


def register(groups, ctx: dict) -> None:
    def cmd_convert(a, extra=None) -> int:
        src = pathlib.Path(a.file)
        if not src.exists():
            print(f"convert: {src} does not exist", file=sys.stderr)
            return 2
        try:
            convert(src, a.out, a.rows_document, a.force, say=lambda m: print(m, file=sys.stderr) if a.out == "-" else print(m))
        except (ValueError, json.JSONDecodeError, OSError, UnicodeDecodeError, csv.Error) as e:
            print(f"convert: {e}", file=sys.stderr)
            return 1
        return 0

    p = groups.add_parser("convert", help="convert: CSV, a JSON array or a folder of .json files into JSON Lines (one object a line)",
                          description=__doc__.split("\n\n")[0], epilog=__doc__.split("\n\n", 1)[1], formatter_class=argparse.RawDescriptionHelpFormatter)
    p.set_defaults(func=cmd_convert, cmd=None)
    p.add_argument("file", help="a .csv, .json (array or one object), .jsonl or .gz file, or a folder of <id>.json files")
    p.add_argument("--out", help="the JSONL file to write (default: the input's name with .jsonl; - for standard output; .gz to gzip)")
    p.add_argument("--rows-document", action="store_true", help="CSV: one line {id: file name, rows: [...]}, as the file connector serves a CSV feed")
    p.add_argument("--force", action="store_true", help="replace an existing output file")


if __name__ == "__main__":
    ap = argparse.ArgumentParser(prog="convert")
    register(ap.add_subparsers(dest="group", required=True), {})
    args = ap.parse_args(["convert"] + sys.argv[1:])
    sys.exit(args.func(args))
