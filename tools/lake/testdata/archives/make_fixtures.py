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

"""The shared archive fixtures: the archives beside this file and ``expected.json``, the cases both test suites run (Python:
tools/lake/test_archives.py; Java: drishti-lakeload's ArchivesTest). The rule they test is DATA_GUIDE.md section 2.7 ("Archives").

    python3 tools/lake/testdata/archives/make_fixtures.py          rewrite them (byte for byte the same: fixed times, sorted entries)

Each case: the archive, the options a loader is given (``dataset``, ``configured``, ``limits``) and what must come of it: the datasets
with their rows and members in reading order, the count of notes; or ``refused`` with a word the refusal must contain. The rows are
lake rows (``domain``, ``kind``, ``table``, ``id``, ``date``, ``doc``), so the same files load with load_rows.py and the Java loader
(``source.id: id``, ``source.date: date``)."""
from __future__ import annotations

import gzip
import io
import json
import pathlib
import tarfile
import zipfile

HERE = pathlib.Path(__file__).resolve().parent
EPOCH = (1980, 1, 1, 0, 0, 0)


def row(table: str, i: int, day: str) -> str:
    doc = {"ID": f"{table.upper()}-{i}", "NOTIONAL": 1000 * i, "BUSINESS_DATE": day}
    return json.dumps({"domain": "fixture", "kind": table, "table": table, "id": f"{table.upper()}-{i}", "date": day,
                       "doc": json.dumps(doc, separators=(",", ":"))}, separators=(",", ":"))


def text(table: str, ids, day: str, blank: bool = False) -> bytes:
    lines = [row(table, i, day) for i in ids]
    if blank:
        lines.insert(1, "")
    return ("\n".join(lines) + "\n").encode()


def gz(data: bytes) -> bytes:
    out = io.BytesIO()
    with gzip.GzipFile(fileobj=out, mode="wb", mtime=0) as f:
        f.write(data)
    return out.getvalue()


# the members of each source tree: name -> bytes (None: a folder entry)
FLAT = {"a.jsonl": text("trade", [1, 2, 3], "2026-10-01", blank=True), "b.jsonl.gz": gz(text("trade", [4, 5], "2026-10-02")),
        "c.ndjson": text("trade", [6], "2026-10-02"), "README.txt": b"Trades of the fixture.\n"}
BUNDLE = {"trade/": None, "trade/t1.jsonl": text("trade", [1, 2], "2026-10-01"), "trade/t2.jsonl.gz": gz(text("trade", [3, 4], "2026-10-02")),
          "position/": None, "position/p1.jsonl": text("position", [1, 2, 3], "2026-10-01"), "position/notes.md": b"# notes\n",
          "readme.txt": b"two datasets\n", "__MACOSX/._trade": b"\x00", ".DS_Store": b"\x00"}


def write_zip(name: str, members: dict, links: dict | None = None) -> None:
    with zipfile.ZipFile(HERE / name, "w", zipfile.ZIP_DEFLATED) as z:
        for m in sorted(members):
            info = zipfile.ZipInfo(m, EPOCH)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = (0o40755 << 16) | 0x10 if m.endswith("/") else 0o100644 << 16
            z.writestr(info, b"" if members[m] is None else members[m])
        for m, target in (links or {}).items():
            info = zipfile.ZipInfo(m, EPOCH)
            info.external_attr = 0o120777 << 16
            z.writestr(info, target.encode())


def write_tar(name: str, members: dict, gzipped: bool, links: dict | None = None) -> None:
    raw = io.BytesIO()
    with tarfile.open(fileobj=raw, mode="w", format=tarfile.PAX_FORMAT) as t:
        for m in sorted(members):
            info = tarfile.TarInfo(m.rstrip("/") if m.endswith("/") else m)
            info.mtime, info.uid, info.gid, info.uname, info.gname = 0, 0, 0, "", ""
            if members[m] is None:
                info.type, info.mode = tarfile.DIRTYPE, 0o755
                t.addfile(info)
            else:
                info.size, info.mode = len(members[m]), 0o644
                t.addfile(info, io.BytesIO(members[m]))
        for m, target in (links or {}).items():
            info = tarfile.TarInfo(m)
            info.type, info.linkname, info.mtime = tarfile.SYMTYPE, target, 0
            t.addfile(info)
    (HERE / name).write_bytes(gz(raw.getvalue()) if gzipped else raw.getvalue())


def main() -> None:
    for old in HERE.iterdir():
        if old.suffix in (".zip", ".tar", ".tgz", ".gz"):
            old.unlink()
    write_tar("trade.tar.gz", FLAT, True)
    write_tar("trade.tgz", FLAT, True)
    write_tar("trade.tar", {"./": None, **{"./" + k: v for k, v in FLAT.items()}}, False)   # tar -C dir . : every name under ./
    write_zip("trade.zip", FLAT)
    write_zip("book.zip", FLAT)
    write_tar("bundle.tar.gz", BUNDLE, True)
    write_zip("bundle.zip", BUNDLE)
    write_tar("mixed.tar.gz", {"loose.jsonl": text("trade", [1], "2026-10-01"), "trade/t.jsonl": text("trade", [2], "2026-10-01")}, True)
    write_zip("deep.zip", {"trade/2026/t.jsonl": text("trade", [1], "2026-10-01")})
    write_tar("unknown.tar.gz", {"trade/t.jsonl": text("trade", [1], "2026-10-01"), "mystery/m.jsonl": text("mystery", [1], "2026-10-01"),
                                 "other/o.jsonl": text("other", [1], "2026-10-01")}, True)
    write_zip("slip.zip", {"../evil.jsonl": text("trade", [1], "2026-10-01")})
    write_tar("slip.tar", {"/abs/evil.jsonl": text("trade", [1], "2026-10-01")}, False)
    write_tar("link.tar.gz", {"trade/t.jsonl": text("trade", [1], "2026-10-01")}, True, links={"trade/l.jsonl": "/etc/passwd"})
    write_zip("nested.zip", {"trade/t.jsonl": text("trade", [1], "2026-10-01"), "trade/more.zip": b"PK\x05\x06" + b"\x00" * 18})
    write_zip("bomb.zip", {"big.jsonl": b"\n" * (8 << 20)})                  # 8 MB of blank lines in a few KB
    write_tar("gzbomb.tar", {"big.jsonl.gz": gz(b"\n" * (8 << 20))}, False)    # small entries, a member that expands as it is read
    write_zip("empty.zip", {"README.txt": b"nothing to load\n"})
    write_zip("badname.zip", {"bad name/t.jsonl": text("trade", [1], "2026-10-01")})

    flat = {"trade": {"rows": 6, "members": ["a.jsonl", "b.jsonl.gz", "c.ndjson"]}}
    bundle = {"position": {"rows": 3, "members": ["position/p1.jsonl"]},
              "trade": {"rows": 4, "members": ["trade/t1.jsonl", "trade/t2.jsonl.gz"]}}
    cases = [
        {"case": "a tarball without folders is one dataset named after it", "file": "trade.tar.gz", "expect": {"datasets": flat, "notes": 1}},
        {"case": "the same as .tgz", "file": "trade.tgz", "expect": {"datasets": flat, "notes": 1}},
        {"case": "a plain tar written from ./", "file": "trade.tar", "expect": {"datasets": flat, "notes": 1}},
        {"case": "the same as .zip", "file": "trade.zip", "expect": {"datasets": flat, "notes": 1}},
        {"case": "--dataset names the dataset", "file": "book.zip", "dataset": "trade", "expect": {"datasets": flat, "notes": 1}},
        {"case": "without --dataset it is named after the file", "file": "book.zip",
         "expect": {"datasets": {"book": flat["trade"]}, "notes": 1}},
        {"case": "folders are datasets", "file": "bundle.tar.gz", "configured": ["trade", "position"], "expect": {"datasets": bundle, "notes": 4}},
        {"case": "folders are datasets, zip", "file": "bundle.zip", "configured": ["trade", "position"], "expect": {"datasets": bundle, "notes": 4}},
        {"case": "loose files and folders", "file": "mixed.tar.gz", "refused": "mixed"},
        {"case": "two levels of folders", "file": "deep.zip", "refused": "one level deep"},
        {"case": "folders no dataset is configured for, all listed", "file": "unknown.tar.gz", "configured": ["trade", "position"],
         "refused": "mystery, other"},
        {"case": "a .. in a member's path", "file": "slip.zip", "refused": "unsafe"},
        {"case": "an absolute member path", "file": "slip.tar", "refused": "unsafe"},
        {"case": "a link member", "file": "link.tar.gz", "refused": "a link"},
        {"case": "an archive inside the archive", "file": "nested.zip", "refused": "one level deep"},
        {"case": "the ratio cap", "file": "bomb.zip", "limits": {"max_ratio": 2}, "refused": "max-ratio"},
        {"case": "the size cap", "file": "bomb.zip", "limits": {"max_mb": 1}, "refused": "max-mb"},
        {"case": "within the caps, blank lines are no rows", "file": "bomb.zip", "expect": {"datasets": {"bomb": {"rows": 0, "members": ["big.jsonl"]}},
                                                                                            "notes": 0}},
        {"case": "a compressed member held to the caps as it is read", "file": "gzbomb.tar", "limits": {"max_ratio": 2}, "refused": "zip bomb"},
        {"case": "the members cap", "file": "bundle.zip", "configured": ["trade", "position"], "limits": {"max_members": 3}, "refused": "max-members"},
        {"case": "no JSON Lines member", "file": "empty.zip", "refused": "no JSON Lines member"},
        {"case": "--dataset with folders", "file": "bundle.zip", "dataset": "trade", "refused": "has folders"},
        {"case": "a folder name that cannot name a table", "file": "badname.zip", "refused": "cannot name a table"},
    ]
    doc = {"rule": "docs/guides/DATA_GUIDE.md section 2.7 (Archives)",
           "defaults": {"max_mb": 102400, "max_ratio": 200, "max_members": 100000, "floor_bytes": 1 << 20}, "cases": cases}
    (HERE / "expected.json").write_text(json.dumps(doc, indent=1) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
