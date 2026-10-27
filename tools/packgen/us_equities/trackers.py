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

"""Index membership from the index's tracking ETF: the stocks SPY (S&P 500), QQQ (Nasdaq-100) and DIA (Dow Jones Industrial Average)
report holding in their newest Form N-PORT (SEC, public domain), each resolved to its SEC company (CIK).

Index providers' member lists are proprietary; a fund that tracks the index must report every holding to the SEC each quarter, so its
equity holdings are the index's members as of its report date. No network: it reads the funds pack's download cache (N-PORT data
sets, the SEC fund ticker file; tools/publicdata/funds.py refresh fills it) and writes trackers.json into the us-equities cache.

    python3 tools/packgen/us_equities/trackers.py [--funds-cache DIR] [--cache DIR]

A holding resolves to a CIK by its ticker (a fund files one, else the ticker other funds file for the same CUSIP, the most common),
else by its name. A holding whose company the SEC's current ticker file no longer lists (taken private or merged since the report)
is kept apart as not listed.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
import zipfile
from collections import Counter, defaultdict
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent / "funds"))
import fetch as F  # noqa: E402
import fundsread as R  # noqa: E402

TRACKERS = {"SP500": "SPY", "NDX": "QQQ", "DJIA": "DIA"}
FUNDS_CACHE = ROOT / "data" / "publicdata" / "cache" / "funds"
_FOREIGN = {"nv", "n", "v", "plc", "public", "limited", "sa", "ag", "se"}


def _ticker(t: str) -> str:
    return re.sub(r"[./ ]", "-", (t or "").strip().upper())


def _norm(name: str) -> str:
    """fetch.py's name key, without a foreign legal form either (LyondellBasell Industries NV, CRH PLC) or a trailing /NY, /The."""
    base = re.sub(r"/[A-Za-z]{2,3}\b", " ", name or "")
    return " ".join(w for w in F._norm(base).split() if w not in _FOREIGN)


def _names(cache: Path) -> dict[str, str]:
    """{name key: CIK} from the SEC ticker file, keyed as _norm keys a holding (a key two CIKs share is dropped)."""
    t = json.loads((cache / "sec" / "company_tickers_exchange.json").read_text(encoding="utf-8"))
    out: dict[str, str] = {}
    for row in t["data"]:
        n, cik = _norm(row[1]), str(row[0])
        out[n] = cik if out.get(n, cik) == cik else ""
    return out


def _cusip_tickers(zf: zipfile.ZipFile) -> dict[str, str]:
    """{CUSIP: ticker} from every fund's equity holdings that carry a ticker (iShares and SPDR file them): the most common one."""
    by_holding = {r["HOLDING_ID"]: r["IDENTIFIER_TICKER"].strip() for r in R.rows(zf, "IDENTIFIERS.tsv") if r["IDENTIFIER_TICKER"].strip()}
    seen: dict[str, Counter] = defaultdict(Counter)
    for r in R.rows(zf, "FUND_REPORTED_HOLDING.tsv"):
        t, c = by_holding.get(r["HOLDING_ID"]), r["ISSUER_CUSIP"].strip()
        if t and r["ASSET_CAT"] == "EC" and len(c) == 9 and c != "000000000":
            seen[c][_ticker(t)] += 1
    return {c: n.most_common(1)[0][0] for c, n in seen.items()}


def extract(funds_cache: Path, cache: Path, log=print) -> dict:
    """Write trackers.json; without the funds cache (no N-PORT data set or fund ticker file) keep the one there is, and say so."""
    nport = R.newest(funds_cache / "nport", "_nport.zip") if (funds_cache / "nport").is_dir() else None
    if nport is None or not (funds_cache / "sec" / "company_tickers_mf.json").is_file():
        kept = "keeping the index members of the last run" if (cache / "trackers.json").is_file() else "index members from Wikidata"
        log(f"  trackers: no N-PORT data set in {funds_cache} (tools/publicdata/funds.py refresh downloads it): {kept}")
        return {}
    series, listed = R.tickers(funds_cache)
    by_ticker, _ = F.sec_maps(cache)
    by_name = _names(cache)
    with zipfile.ZipFile(nport) as zf:
        funds = R.nport_funds(zf)
        chosen: dict[str, dict] = {}
        for key, f in funds.items():
            tickers = {c["TICKER"] for c in series.get(key, [])} | ({listed.get(f["CIK"], "")} if not f["SERIES_ID"] else set())
            for code, t in TRACKERS.items():
                if t in tickers:
                    chosen[code] = f
        missing = sorted(set(TRACKERS) - set(chosen))
        if missing:
            raise SystemExit(f"{nport.name} has no report of {', '.join(TRACKERS[c] for c in missing)}")
        holdings = R.nport_holdings(zf, {f["ACCESSION"] for f in chosen.values()}, log)
        cusips = _cusip_tickers(zf)
    out = {"nport": nport.name, "indexes": {}}
    for code, f in sorted(chosen.items()):
        members, not_listed = {}, []
        for h in holdings[f["ACCESSION"]]:
            if h["ASSET_CAT"] != "EC":
                continue
            c = h["ISSUER_CUSIP"].strip()
            t = _ticker(h.get("TICKER", "")) or cusips.get(c, "")
            cik = by_ticker.get(t) or by_name.get(_norm(h["ISSUER_NAME"]))
            row = {"NAME": R.clean(h["ISSUER_NAME"]), "TICKER": t, "CUSIP": c if c not in ("N/A", "000000000") else "",
                   "PCT": R.num(h["PERCENTAGE"])}
            if cik:
                old = members.get(cik)            # two share classes of one company (Alphabet's A and C): one member, weights added
                members[cik] = {**row, "CIK": cik, "PCT": (old["PCT"] if old else 0) + (row["PCT"] or 0)}
            else:
                not_listed.append(row)
        out["indexes"][code] = {"FUND": TRACKERS[code], "FUND_NAME": f["NAME"], "ACCESSION": f["ACCESSION"],
                                "REPORT_DATE": f["REPORT_DATE"], "FILED": f["FILED"],
                                "MEMBERS": sorted(members.values(), key=lambda m: int(m["CIK"])),
                                "NOT_LISTED": sorted(not_listed, key=lambda m: m["NAME"])}
        log(f"  {code}: {TRACKERS[code]} on {f['REPORT_DATE']}: {len(members)} members, {len(not_listed)} not listed by the SEC today")
    (cache / "trackers.json").write_text(json.dumps(out, indent=1, sort_keys=True) + "\n", encoding="utf-8")
    return out


def main(argv: list[str]) -> int:
    p = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    p.add_argument("--funds-cache", type=Path, default=FUNDS_CACHE)
    p.add_argument("--cache", type=Path, default=ROOT / "data" / "publicdata" / "cache" / "us-equities")
    a = p.parse_args(argv)
    extract(a.funds_cache, a.cache)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
