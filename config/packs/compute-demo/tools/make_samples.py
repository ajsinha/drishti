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

"""Writes the synthetic data of the compute-demo sample pack (docs/architecture/DATA_ARCHITECTURE.md, the sample reactive connector):

  samples/path-book.jsonl     three books, each listing its strips (MEMBERS)
  samples/path-strip.jsonl    120 strips, each a P&L path of 250 scenario days (PNL), drawn from a fixed seed
  samples/catalog.jsonl       their ids, titles and subtitles, one a line

The numbers are made up (a seeded random walk per strip): no market data, nothing real. Run:
python3 config/packs/compute-demo/tools/make_samples.py. Attribute names are UPPER_SNAKE."""
import pathlib
import random
import sys

HERE = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(HERE.parents[2] / "tools" / "packgen" / "common"))
import packsamples  # noqa: E402  (samples/<kind>.jsonl, one document a line)

DAYS = 250
BOOKS = [("BOOK-RATES", "Rates book", "RATES", 50, 1.0), ("BOOK-FX", "FX book", "FX", 40, 0.6), ("BOOK-CREDIT", "Credit book", "CREDIT", 30, 1.4)]
INSTRUMENTS = {"RATES": ["IRS", "OIS", "FRA", "SWAPTION"], "FX": ["FX-FWD", "FX-SWAP", "FX-OPT"], "CREDIT": ["CDS", "BOND", "CDX"]}


def main():
    rnd = random.Random(20261024)
    docs: dict[str, dict[str, dict]] = {}
    catalog = []
    meta = {"source": "synthetic", "generation": 1, "live": False}
    for book_id, name, desk, n, vol in BOOKS:
        members = []
        for i in range(1, n + 1):
            sid = f"STRIP-{desk}-{i:03d}"
            members.append(sid)
            size = rnd.choice([0.5, 1, 2, 5]) * 1000
            pnl = [round(rnd.gauss(0, vol) * size, 2) for _ in range(DAYS)]
            inst = rnd.choice(INSTRUMENTS[desk])
            docs.setdefault("path-strip", {})[sid] = {"STRIP_ID": sid, "BOOK_ID": book_id, "DESK": desk, "INSTRUMENT": inst, "DAYS": DAYS,
                                                      "PNL": pnl, "_meta": meta}
            catalog.append({"kind": "path-strip", "id": sid, "title": sid, "subtitle": f"{inst} · {name}"})
        docs.setdefault("path-book", {})[book_id] = {"BOOK_ID": book_id, "NAME": name, "DESK": desk, "STRIP_COUNT": n, "MEMBERS": members,
                                                     "PATH_ID": book_id, "_meta": meta}
        catalog.insert(0, {"kind": "path-book", "id": book_id, "title": book_id, "subtitle": f"{name} · {n} strips of {DAYS} scenario days"})
    out = HERE / "samples"
    out.mkdir(exist_ok=True)
    (out / packsamples.OLD_CATALOG).unlink(missing_ok=True)   # catalog.json became catalog.jsonl (2.9.4)
    for name, text in packsamples.files(docs, catalog, packsamples.part_mb(sys.argv)).items():
        (out / name).write_text(text, encoding="utf-8")


if __name__ == "__main__":
    main()
