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

"""Build the funds pack's documents from the downloads drishti.py feed run funds keeps (no network).

    python3 tools/packgen/funds/make.py [--cache DIR]           write data/testdata/funds/, the pack's samples and README
    python3 tools/packgen/funds/make.py --check [--cache DIR]   fail if what would be written differs from the pack

With a cache (<data>/datafeeds/sources/cache/funds by default: N-PORT, N-CEN and risk/return summary data sets, the ticker files), --check
rebuilds every document from it. Without one (a fresh clone, CI), --check reads the committed snapshot back (data/testdata/funds/) and
rebuilds what is derived from it (the reference samples, the README), so it needs no network and no 500 MB download.

The documents follow Drishti's standard data pattern (tools/testdata/publicsnap.py): gzipped JSON Lines under
data/testdata/funds/<kind>/, the fund kind in parts of about 1.2 MB, listed in data/testdata/testdata.yaml, built into the lake by
tools/build_demo_lakes.py and served by the pack's Delta connector funds-store. The pack also reads the committed us-equities snapshot
(its companies and indexes): a holding links to its company, and the index tab weighs the index's members.
"""
from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
sys.path.insert(0, str(HERE.parent / "common"))
import packsamples as PS  # noqa: E402
import readme as README  # noqa: E402  (the pack README standard)
sys.path.insert(0, str(HERE))
import fundsbuild as BUILD  # noqa: E402
import fundsglossary as GLOSSARY  # noqa: E402
import fundsread as R  # noqa: E402
sys.path.insert(0, str(ROOT / "tools" / "testdata"))
import publicsnap as SNAP  # noqa: E402  (the standard data pattern: data/testdata/<pack>/<kind>/)

PACK = ROOT / "modules" / "external-data" / "packs" / "funds"
NAME = "funds"
HEADER = "\n".join(Path(__file__).read_text(encoding="utf-8").splitlines()[:13]) + "\n\n"     # the licence header of a YAML file


def default_cache() -> Path:
    return Path(os.environ.get("DRISHTI_DATA_DIR") or ROOT / "data").expanduser().resolve() / "datafeeds" / "sources" / "cache" / "funds"


def build(cache: Path, retrieved: str | None = None) -> dict[str, dict[str, dict]]:
    us = {d["TICKER"]: d for d in SNAP.kind_docs("us-equities", "us-company")}
    ix = {d["INDEX"]: d for d in SNAP.kind_docs("us-equities", "index-membership")}
    if not us:
        raise SystemExit("the us-equities snapshot (data/testdata/us-equities) is missing: the funds pack links its holdings to it")
    return BUILD.build(cache, us, ix, retrieved or retrieved_at(cache))


def retrieved_at(cache: Path) -> str:
    """The date the newest N-PORT data set was downloaded (its cache entry, else its file time): the snapshot's date, a day of the
    business calendar (local time, hostdates.local_day), so an evening download is never dated tomorrow."""
    import json
    sys.path.insert(0, str(ROOT / "tools" / "lake"))
    import lakewheel  # noqa: F401  (the drishti-lake wheel on the path: tools/lake/lakewheel.py)
    from drishti_lake import filedate
    import hostdates  # noqa: E402  (the one business-date rule)
    z = R.newest(cache / "nport", "_nport.zip")
    try:
        idx = json.loads((cache / "cache-index.json").read_text(encoding="utf-8"))
        when = idx.get(f"nport/{z.name}", {}).get("fetched", "")
        if when:
            return hostdates.local_day(when).isoformat()
    except (OSError, ValueError, AttributeError):
        pass
    return hostdates.local_day(z.stat().st_mtime).isoformat()


def _money(v) -> str:
    if v is None:
        return "n/a"
    return f"USD {v / 1e9:,.1f}bn" if abs(v) >= 1e9 else f"USD {v / 1e6:,.1f}m"


def catalog(docs: dict[str, dict[str, dict]]) -> list[dict]:
    out = []
    for i, d in docs.get("fund", {}).items():
        out.append({"kind": "fund", "id": i, "title": i, "subtitle": f"{d['TYPE']} · {d['NAME']} · {_money(d['NET_ASSETS'])}"})
    for i, d in docs.get("fund-holder", {}).items():
        out.append({"kind": "fund-holder", "id": i, "title": f"HELD {i}", "subtitle": f"Held by funds · {d['NAME']} · {d['FUNDS']} funds"})
    for i, d in docs.get("index-funds", {}).items():
        out.append({"kind": "index-funds", "id": i, "title": f"TRACK {i}", "subtitle": f"Funds tracking · {d['NAME']} · {d['FUNDS']} funds"})
    return out


def _intro() -> str:
    """The pack's hand-written README intro (tools/packgen/funds/intro.yaml, without its comment lines): the ``intro:`` of the about.yaml."""
    lines = (HERE / "intro.yaml").read_text(encoding="utf-8").split("\n")
    while lines and (lines[0].startswith("#") or not lines[0].strip()):
        lines.pop(0)
    return "\n".join(lines)


def files(docs: dict[str, dict[str, dict]]) -> dict[Path, str]:
    ref = SNAP.reference(NAME, docs)                     # a few reference documents: the data itself is in data/testdata
    out = {PACK / "samples" / name: text for name, text in PS.files(ref, catalog(ref), catalog_indent=None).items()}
    out[PACK / "config" / "about.yaml"] = GLOSSARY.about_yaml(HEADER, _intro())
    out[PACK / "README.md"] = README.render_pack(PACK, files=out)
    return out


def main(argv: list[str]) -> int:
    p = argparse.ArgumentParser(description="Build the funds pack from the downloaded data sets (no network).")
    p.add_argument("--cache", type=Path, default=None, help="the downloads (default <data>/datafeeds/sources/cache/funds)")
    p.add_argument("--check", action="store_true")
    p.add_argument("--pack", type=Path, help="the pack folder to write or check (default modules/external-data/packs/funds)")
    p.add_argument("--testdata", type=Path, help="the test data folder the snapshot goes to (default data/testdata for the committed pack)")
    a = p.parse_args(argv)
    global PACK
    PACK = a.pack.resolve() if a.pack else ROOT / "modules" / "external-data" / "packs" / "funds"
    cache = (a.cache or default_cache()).resolve()
    testdata = a.testdata.resolve() if a.testdata else SNAP.default_testdata(PACK, ROOT / "modules" / "external-data" / "packs" / "funds")
    if R.newest(cache / "nport", "_nport.zip") is not None and (cache / "sec" / "company_tickers_mf.json").is_file():
        docs = build(cache)
    elif a.check:
        docs = SNAP.read(testdata, NAME)
        if not docs:
            raise SystemExit(f"no downloads at {cache} and no snapshot in {testdata}/funds: run drishti.py feed run funds first")
        print(f"no downloads at {cache}: checking the committed snapshot ({testdata}/funds) against what it derives")
    else:
        raise SystemExit(f"no downloads at {cache}: run drishti.py feed run funds first")
    return SNAP.generate(NAME, PACK, docs, files(docs), a.check, testdata)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
