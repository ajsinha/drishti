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

"""The mocked SwapOne day for the source system tutorial (docs/guides/BUILD_GUIDE.md): a day of the committed mocked SwapOne data
(data/testdata/trades/swapone/, made by tools/samplegen/src_swapone.py: invented names and numbers, SwapOne's own snake_case fields) as
the file a booking system would send, named for the inbox rule of the module the tutorial makes (swapone-<date>.jsonl.gz), without the
SOURCE_SYSTEM column (the module's connector stamps it); --ready also writes the .ready file holding the SHA-256.

    python3 docs/tutorials/data/swapone/make_day.py --out drop --date 2026-07-08 --ready
"""
from __future__ import annotations

import argparse
import gzip
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
SOURCE = ROOT / "data" / "testdata" / "trades" / "swapone"


def make(out: Path, day: str) -> tuple[Path, int, str]:
    """Writes swapone-<day>.jsonl.gz into `out`; returns it, its rows and its SHA-256."""
    src = SOURCE / f"trades_shared.{day}.jsonl.gz"
    if not src.exists():
        raise SystemExit(f"no mocked SwapOne day {day}: the days are " + ", ".join(p.name.split(".")[1] for p in sorted(SOURCE.glob("*.jsonl.gz"))))
    out.mkdir(parents=True, exist_ok=True)
    rows = []
    with gzip.open(src, "rt", encoding="utf-8") as f:
        for line in f:
            d = json.loads(line)
            d.pop("SOURCE_SYSTEM", None)                      # the system's own file has no such column
            rows.append(d)
    target = out / f"swapone-{day}.jsonl.gz"
    with open(target, "wb") as raw, gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0) as g:   # mtime 0: the same rows, the same bytes
        g.write("".join(json.dumps(r, separators=(",", ":")) + "\n" for r in rows).encode("utf-8"))
    return target, len(rows), hashlib.sha256(target.read_bytes()).hexdigest()


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--out", default="drop")
    ap.add_argument("--date", default="2026-07-08")
    ap.add_argument("--ready", action="store_true", help="also write the .ready file (the SHA-256 of the file)")
    a = ap.parse_args()
    target, n, digest = make(Path(a.out), a.date)
    print(f"{target}  {n} rows  sha256 {digest}")
    if a.ready:
        Path(str(target) + ".ready").write_text(digest + "\n")
        print(f"{target}.ready")


if __name__ == "__main__":
    main()
