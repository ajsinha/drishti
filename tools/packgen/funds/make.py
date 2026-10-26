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

"""Build the funds pack's documents from the downloads tools/publicdata/funds.py keeps (no network).

    python3 tools/packgen/funds/make.py [--cache DIR]           write data/testdata/funds/, the pack's samples, README and guide
    python3 tools/packgen/funds/make.py --check [--cache DIR]   fail if what would be written differs from the pack

With a cache (<data>/publicdata/cache/funds by default: N-PORT, N-CEN and risk/return summary data sets, the ticker files), --check
rebuilds every document from it. Without one (a fresh clone, CI), --check reads the committed snapshot back (data/testdata/funds/) and
rebuilds what is derived from it (the reference samples, the guide, the README), so it needs no network and no 500 MB download.

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
import guide_data as GD  # noqa: E402  (the guide's Data contracts and Data retrieval sections)
sys.path.insert(0, str(HERE))
import fundsbuild as BUILD  # noqa: E402
import fundsglossary as GLOSSARY  # noqa: E402
import fundsread as R  # noqa: E402
sys.path.insert(0, str(ROOT / "tools" / "testdata"))
import publicsnap as SNAP  # noqa: E402  (the standard data pattern: data/testdata/<pack>/<kind>/)

PACK = ROOT / "config" / "packs" / "funds"
NAME = "funds"
HEADER = "\n".join(Path(__file__).read_text(encoding="utf-8").splitlines()[:13]) + "\n\n"     # the licence header of a YAML file


def default_cache() -> Path:
    return Path(os.environ.get("DRISHTI_DATA_DIR") or ROOT / "data").expanduser().resolve() / "publicdata" / "cache" / "funds"


def build(cache: Path, retrieved: str | None = None) -> dict[str, dict[str, dict]]:
    us = {d["TICKER"]: d for d in SNAP.kind_docs("us-equities", "us-company")}
    ix = {d["INDEX"]: d for d in SNAP.kind_docs("us-equities", "index-membership")}
    if not us:
        raise SystemExit("the us-equities snapshot (data/testdata/us-equities) is missing: the funds pack links its holdings to it")
    return BUILD.build(cache, us, ix, retrieved or retrieved_at(cache))


def retrieved_at(cache: Path) -> str:
    """The date the newest N-PORT data set was downloaded (its cache entry, else its file time): the snapshot's date."""
    import json
    from datetime import datetime, timezone
    z = R.newest(cache / "nport", "_nport.zip")
    try:
        idx = json.loads((cache / "cache-index.json").read_text(encoding="utf-8"))
        when = idx.get(f"nport/{z.name}", {}).get("fetched", "")
        if when:
            return when[:10]
    except (OSError, ValueError, AttributeError):
        pass
    return datetime.fromtimestamp(z.stat().st_mtime, timezone.utc).strftime("%Y-%m-%d")


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


GUIDE = """<!--
  Project Drishti · Any data. Any domain. One grammar.

  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
  All rights reserved.

  PROPRIETARY AND CONFIDENTIAL.

  This file is the confidential and proprietary property of Ashutosh Sinha.
  Unauthorised copying, use, modification, distribution or disclosure of this
  file, via any medium, is strictly prohibited except with the express prior
  written permission of the copyright holder.

  See the LICENSE file in the root of this repository for the full terms.
-->
<!-- Generated by tools/packgen/funds/make.py. Edit the generator, not this file. -->
# The funds pack

The **{funds} largest US mutual funds and ETFs by net assets** with their holdings, from SEC public data: each fund's Form N-PORT
report (holdings with CUSIP, ISIN and LEI, derivatives and their counterparties, three months of flows and returns), its Form N-CEN
census (ETF facts, adviser, custodian, auditor, authorised participants) and its prospectus risk/return summary (expense ratio,
turnover, calendar-year returns). Everything is published data retrieved on {retrieved}; the N-PORT reports are dated {first} to {last}.
No prices or NAV history. The full guide, with the data contract of every kind and where the data comes from, is
`docs/guides/FUNDS_GUIDE.md`; tutorial 28 walks through it.

The pack is **off by default**: add it to the packs a server loads, after `us-equities` (its holdings link to the companies there):
`DRISHTI_PACKS=core,market-data,trading,counterparty-risk,market-risk,world-economy,news-and-markets,us-equities,gleif,funds`.

![The FUND page: Invesco QQQ's fact sheet, its look-through and its index tabs](images/funds-fund.jpg)

## What you can open

| Type | Opens | Examples |
|---|---|---|
| `FUND <ticker>` | a fund or ETF: the fact sheet; tabs **Look-through holdings** and **vs its index** | `FUND QQQ`, `FUND VTI` (also `FUND VTSAX`: any share class), `FUND SPY`, `FUND AGG` |
| `FUND <field>=<value>` | a search of the funds | `FUND TYPE=ETF`, `FUND EXPENSE_RATIO<0.05`, `FUND INDEX_ID=SP500` |
| `HELD <ticker>` | the funds holding a us-equities company, by value | `HELD AAPL`, `HELD NVDA` |
| `TRACK <index>` | the funds tracking a us-equities index | `TRACK SP500`, `TRACK NDX`, `TRACK DJIA` |

With `us-equities` on, a company page (`USEQ AAPL`) has the tabs **Held by funds** and **Fund ownership**, and an index page
(`IDX SP500`) lists the ETFs and funds tracking it.

## The data

| Kind | Documents | What one holds |
|---|---|---|
| `fund` | {funds} | identity and share classes, census facts, fees and returns, flows, concentration, holdings by asset type, issuer type, country and SIC division, derivatives and counterparties, the {top_n} largest holdings (with the totals of the rest), the fund against its index |
| `fund-holder` | {holders} | one us-equities company: every fund that holds it, its weight, value and shares in each |
| `index-funds` | {indexes} | one us-equities index: the funds that track it, with net assets, expense ratio and active share |

Holdings kept: {kept} of {held} (a fund keeps its {top_n} largest; the reverse look-through counts every one). Funds by type: {types}.

**Where the data lives.** The documents are committed as Drishti's standard test data, gzipped JSON Lines under
`data/testdata/funds/<kind>/` (the fund kind in parts of about 1.2 MB, {size} in all), listed in `data/testdata/testdata.yaml`.
`python3 tools/drishti.py lake demo` (or `python3 tools/build_demo_lakes.py`) builds them into the demo lake and the pack's Delta
connector `funds-store` serves them; nothing is served by the demo connector. `samples/` keeps a few reference documents.

## Data sources and licences

| Source | What | Licence |
|---|---|---|
| SEC Form N-PORT data sets (quarterly, about 450 MB) | holdings, derivatives, counterparties, flows, monthly returns | Public domain (U.S. Government work) |
| SEC Form N-CEN data sets (quarterly, about 6 to 16 MB) | ETF facts, service providers, authorised participants, share classes | Public domain |
| SEC mutual fund prospectus risk/return summary data sets (quarterly) | expense ratio, management fee, turnover, calendar-year returns | Public domain |
| SEC `company_tickers_mf.json` and `company_tickers_exchange.json` | share class tickers; a unit investment trust's ticker | Public domain |
| Nasdaq Trader symbol directory | which tickers are ETFs, and where they list | Public directory file |
| GLEIF golden copy, level 2 relationships | managed by, sub-fund of, feeder to | CC0 1.0 |

The SEC asks for a descriptive User-Agent with a contact and at most 10 requests a second; the refresh script uses the same setting
as the us-equities pack (`DRISHTI_SEC_USER_AGENT`). **Not included:** index weights (proprietary to the index providers; the index tab
computes market-cap weights from SEC data and says so), prices and NAV history, non-US funds and money market funds (they file Form
N-MFP, not N-PORT).

## Refreshing the data

```bash
python3 tools/publicdata/funds.py status                 # licences, last refreshed, cached size
python3 tools/publicdata/funds.py refresh --dry-run      # what would be fetched; changes nothing
python3 tools/publicdata/funds.py refresh                # the downloads (about 700 MB, kept in the data folder), then make.py and --check
python3 tools/packgen/funds/make.py --check              # no network: the pack matches its generator
```

`tools/publicdata/funds.sh` and `tools/windows/publicdata-funds.ps1` run the same script. The downloads stay in the data folder
(`<data>/publicdata/cache/funds`), never in git. After a refresh, rebuild the lake: `python3 tools/drishti.py lake demo`.
"""


def guide(docs: dict[str, dict[str, dict]]) -> str:
    from collections import Counter
    funds = docs.get("fund", {})
    any_doc = next(iter(funds.values()), {})
    dates = sorted(d["REPORT_DATE"] for d in funds.values()) or [""]
    types = Counter(d["TYPE"] for d in funds.values())
    size = sum(len(b) for b in SNAP.files(NAME, docs).values())
    return GUIDE.format(funds=len(funds), retrieved=any_doc.get("PROVENANCE", {}).get("RETRIEVED_AT", ""), first=dates[0], last=dates[-1],
                        holders=len(docs.get("fund-holder", {})), indexes=len(docs.get("index-funds", {})), top_n=BUILD.TOP_N,
                        kept=f"{sum(len(d['HOLDINGS']) for d in funds.values()):,}", held=f"{sum(d['HOLDING_COUNT'] for d in funds.values()):,}",
                        types=", ".join(f"{t} {n}" for t, n in types.most_common()), size=f"{size / 1e6:.1f} MB")


def files(docs: dict[str, dict[str, dict]]) -> dict[Path, str]:
    ref = SNAP.reference(NAME, docs)                     # a few reference documents: the data itself is in data/testdata
    out = {PACK / "samples" / name: text for name, text in PS.files(ref, catalog(ref), catalog_indent=None).items()}
    out[PACK / "config" / "about.yaml"] = GLOSSARY.about_yaml(HEADER)
    out[PACK / "guides" / "funds.md"] = guide(docs).rstrip() + "\n\n" + "\n".join(GD.sections(PACK)).rstrip() + "\n"
    out[PACK / "README.md"] = README.render_pack(PACK, files=out, intro=HERE / "readme" / "funds.intro.md")
    return out


def main(argv: list[str]) -> int:
    p = argparse.ArgumentParser(description="Build the funds pack from the downloaded data sets (no network).")
    p.add_argument("--cache", type=Path, default=None, help="the downloads (default <data>/publicdata/cache/funds)")
    p.add_argument("--check", action="store_true")
    p.add_argument("--pack", type=Path, help="the pack folder to write or check (default config/packs/funds)")
    p.add_argument("--testdata", type=Path, help="the test data folder the snapshot goes to (default data/testdata for the committed pack)")
    a = p.parse_args(argv)
    global PACK
    PACK = a.pack.resolve() if a.pack else ROOT / "config" / "packs" / "funds"
    cache = (a.cache or default_cache()).resolve()
    testdata = a.testdata.resolve() if a.testdata else SNAP.default_testdata(PACK, ROOT / "config" / "packs" / "funds")
    if R.newest(cache / "nport", "_nport.zip") is not None and (cache / "sec" / "company_tickers_mf.json").is_file():
        docs = build(cache)
    elif a.check:
        docs = SNAP.read(testdata, NAME)
        if not docs:
            raise SystemExit(f"no downloads at {cache} and no snapshot in {testdata}/funds: run tools/publicdata/funds.py refresh first")
        print(f"no downloads at {cache}: checking the committed snapshot ({testdata}/funds) against what it derives")
    else:
        raise SystemExit(f"no downloads at {cache}: run tools/publicdata/funds.py refresh first")
    return SNAP.generate(NAME, PACK, docs, files(docs), a.check, testdata)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
