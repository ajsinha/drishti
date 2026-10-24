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

"""Official public market data for the market-data pack, captured to files so it works offline: ECB euro reference FX rates,
the US Treasury par yield curve, SOFR (New York Fed) and euro short-term rate (ECB).

    python3 tools/publicdata/official_market_data.py status
    python3 tools/publicdata/official_market_data.py refresh --dry-run
    python3 tools/publicdata/official_market_data.py refresh                          a year of history into <data>/publicdata/market-data
    python3 tools/publicdata/official_market_data.py refresh ecb-fx --years 5 --scope USDMXN,EURSEK
    python3 tools/publicdata/official_market_data.py refresh us-treasury sofr --since 2020

The pack reads the same sources live through its feed connectors (nyfed-sofr-feed, ecb-estr-feed, ecb-fx-feed, us-treasury-feed;
off by default). This script writes them as documents in the pack's own shapes, one file a kind (fx-spot.jsonl, ir-curve.jsonl,
rate-fixing.jsonl), so tools/ingest_jsonl.py can load them into the lake. The pack's committed samples are synthetic and are
never overwritten: the output always goes to the data folder (or --out outside the repository). Guide: docs/guides/PUBLIC_DATA.md.
"""
from __future__ import annotations

import csv
import io
import json
import math
import sys
import zipfile
from datetime import date, datetime
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import pdbase as B  # noqa: E402

PACK = "market-data"
PAIRS = ["EURUSD", "EURGBP", "EURJPY", "EURCHF", "GBPUSD", "USDJPY", "USDCHF", "AUDUSD", "USDCAD", "USDINR", "USDBRL", "USDKRW"]
UST_TENORS = [("1 Mo", "1M"), ("2 Mo", "2M"), ("3 Mo", "3M"), ("4 Mo", "4M"), ("6 Mo", "6M"), ("1 Yr", "1Y"), ("2 Yr", "2Y"),
              ("3 Yr", "3Y"), ("5 Yr", "5Y"), ("7 Yr", "7Y"), ("10 Yr", "10Y"), ("20 Yr", "20Y"), ("30 Yr", "30Y")]


def sig(v: float, digits: int = 6) -> float:
    return 0.0 if v == 0 else round(v, digits - 1 - math.floor(math.log10(abs(v))))


def provenance(src: B.Source, url: str, retrieved: str) -> dict:
    return {"SOURCE": src.title, "URL": url, "LICENCE": src.licence, "RETRIEVED_AT": retrieved}


# ---------------------------------------------------------------- pure parsers (tested against recorded fixtures)

def ecb_table(text: str) -> dict[str, dict[str, float]]:
    """eurofxref-hist.csv -> {date: {currency: units per EUR}} ('N/A' and empty cells dropped)."""
    out: dict[str, dict[str, float]] = {}
    for row in csv.DictReader(io.StringIO(text)):
        d = (row.pop("Date", "") or "").strip()
        rates = {k.strip(): float(v) for k, v in row.items() if k and k.strip() and v and v.strip() not in ("N/A", "")}
        if d and rates:
            out[d] = rates
    return out


def fx_docs(table: dict, pairs: list[str], first: str, src: B.Source, url: str, retrieved: str) -> list[dict]:
    """fx-spot documents (the pack's shape: PAIR, PAIR_NAME, MID, BID, ASK, CHANGE_1D, SPOT_DATE, HISTORY) from the ECB table."""
    def rate(rates: dict, pair: str) -> float | None:
        base, quote = pair[:3], pair[3:]
        per = {**rates, "EUR": 1.0}
        if base not in per or quote not in per:
            return None
        return per[quote] / per[base]
    docs = []
    for pair in pairs:
        hist = [(d, rate(table[d], pair)) for d in sorted(table) if d >= first]
        hist = [(d, sig(v)) for d, v in hist if v]
        if not hist:
            continue
        mid, prev = hist[-1][1], hist[-2][1] if len(hist) > 1 else None
        docs.append({"PAIR": f"FX-{pair}", "PAIR_NAME": f"{pair[:3]}/{pair[3:]}", "MID": mid, "BID": mid, "ASK": mid,
                     "CHANGE_1D": sig(mid / prev - 1, 5) if prev else None, "SPOT_DATE": hist[-1][0], "AS_OF": hist[-1][0],
                     "HISTORY": [{"DATE": d, "MID": v} for d, v in hist],
                     "CONVENTIONS": {"BASE": pair[:3], "QUOTE": pair[3:], "FIXING": "ECB euro reference rate, 14:10 CET (crosses via EUR)"},
                     "PROVENANCE": provenance(src, url, retrieved)})
    return docs


def ust_rows(text: str) -> dict[str, dict[str, float]]:
    """A Treasury daily par yield curve CSV -> {ISO date: {tenor: yield %}}."""
    out = {}
    for row in csv.DictReader(io.StringIO(text)):
        raw = (row.get("Date") or "").strip()
        if not raw:
            continue
        d = datetime.strptime(raw, "%m/%d/%Y").date().isoformat()
        pts = {t: float(row[c]) for c, t in UST_TENORS if (row.get(c) or "").strip() not in ("", "N/A")}
        if pts:
            out[d] = pts
    return out


def ust_doc(rows: dict, first: str, src: B.Source, url: str, retrieved: str) -> dict | None:
    """The ir-curve document CRV-USD-UST-PAR: the latest curve's points, its 10Y and 2s10s, and the daily history."""
    days = sorted(d for d in rows if d >= first)
    if not days:
        return None
    last = rows[days[-1]]
    ten, two = last.get("10Y"), last.get("2Y")
    return {"CURVE_ID": "CRV-USD-UST-PAR", "CURRENCY": "USD", "CURVE_TYPE": "Treasury par yield", "INDEX": "UST", "AS_OF": days[-1],
            "TEN_Y": sig(ten / 100) if ten is not None else None,
            "SLOPE_2S_10S": round((ten - two) * 100, 1) if ten is not None and two is not None else None,
            "POINTS": [{"TENOR": t, "INSTRUMENT": "On-the-run Treasury (par yield)", "QUOTE": sig(v / 100), "PAR_YIELD": v} for t, v in last.items()],
            "HISTORY": [{"DATE": d, **{f"Y_{t}": v for t, v in rows[d].items() if t in ("2Y", "10Y", "30Y")}} for d in days],
            "DAY_COUNT": "ACT/ACT", "CALENDAR": "CAL-USNY", "PROVENANCE": provenance(src, url, retrieved)}


def fixing_doc(index_id: str, name: str, admin: str, ccy: str, obs: list[tuple[str, float, float | None]], src: B.Source, url: str,
               retrieved: str) -> dict | None:
    """A rate-fixing document (INDEX_ID, LATEST, FIXINGS newest first) from (date, rate %, volume bn) observations."""
    obs = sorted({d: (r, v) for d, r, v in obs}.items(), reverse=True)
    if not obs:
        return None
    fix = [{"DATE": d, "RATE": sig(r / 100), "RATE_PCT": r, **({"VOLUME_BN": v} if v is not None else {})} for d, (r, v) in obs]
    return {"INDEX_ID": index_id, "NAME": name, "LATEST": fix[0]["RATE"], "AS_OF": fix[0]["DATE"], "ADMINISTRATOR": admin, "TENOR": "Overnight", "CURRENCY": ccy,
            "FIXINGS": fix, "PROVENANCE": provenance(src, url, retrieved)}


def sofr_obs(raw: dict) -> list[tuple[str, float, float | None]]:
    return [(r["effectiveDate"], float(r["percentRate"]), r.get("volumeInBillions")) for r in raw.get("refRates", [])
            if r.get("percentRate") is not None]


def estr_obs(text: str) -> list[tuple[str, float, float | None]]:
    return [(r["TIME_PERIOD"], float(r["OBS_VALUE"]), None) for r in csv.DictReader(io.StringIO(text)) if (r.get("OBS_VALUE") or "").strip()]


# ---------------------------------------------------------------- the sources

class MarketSource(B.Source):
    pack, kind, default_years = PACK, "", 1

    def first(self, run: B.Run) -> date:
        today = date.today()
        y = run.first_year(today.year - self.default_years + 1, today.year)
        return date(y, 1, 1) if (run.a.since or run.a.years) else date(today.year - 1, today.month, min(today.day, 28))


class EcbFx(MarketSource):
    name, title, kind = "ecb-fx", "ECB euro foreign exchange reference rates", "fx-spot"
    url = "https://www.ecb.europa.eu/stats/policy_and_exchange_rates/euro_reference_exchange_rates/html/index.en.html"
    licence, licence_url = "ECB: free reuse with the source acknowledged", "https://www.ecb.europa.eu/home/disclaimer/html/index.en.html"
    scope_help = "more pairs of ECB currencies, e.g. USDMXN,EURSEK (crosses go through EUR)"
    cache_prefixes = ("ecb-fx/",)
    ZIP = "https://www.ecb.europa.eu/stats/eurofxref/eurofxref-hist.zip"

    def plan(self, run):
        return [self.ZIP]

    def fetch(self, run):
        run.http.get(self.ZIP, run.cache.folder / "ecb-fx" / "eurofxref-hist.zip", accept="application/zip")

    def normalise(self, run):
        z = run.cache.folder / "ecb-fx" / "eurofxref-hist.zip"
        with zipfile.ZipFile(z) as zf:
            text = zf.read(zf.namelist()[0]).decode("utf-8")
        pairs = PAIRS + [p.upper() for p in run.scope if len(p) == 6 and p.upper() not in PAIRS]
        self.docs = fx_docs(ecb_table(text), pairs, self.first(run).isoformat(), self, self.ZIP, B.now())
        return {"rows": sum(len(d["HISTORY"]) for d in self.docs), "documents": len(self.docs)}


class UsTreasury(MarketSource):
    name, title, kind = "us-treasury", "US Treasury daily par yield curve rates", "ir-curve"
    url = "https://home.treasury.gov/policy-issues/financing-the-government/interest-rate-statistics"
    licence, licence_url = "Public domain (US Government work)", "https://www.usa.gov/government-copyright"
    cache_prefixes = ("us-treasury/",)
    CSV = ("https://home.treasury.gov/resource-center/data-chart-center/interest-rates/daily-treasury-rates.csv/{y}/all"
           "?type=daily_treasury_yield_curve&field_tdr_date_value={y}&page&_format=csv")

    def years(self, run):
        return range(self.first(run).year, date.today().year + 1)

    def plan(self, run):
        return [self.CSV.format(y=y) for y in self.years(run)]

    def fetch(self, run):
        for y in self.years(run):
            run.http.get(self.CSV.format(y=y), run.cache.folder / "us-treasury" / f"par-yield-{y}.csv", accept="text/csv")

    def normalise(self, run):
        rows = {}
        for y in self.years(run):
            p = run.cache.folder / "us-treasury" / f"par-yield-{y}.csv"
            if p.is_file():
                rows.update(ust_rows(p.read_text(encoding="utf-8-sig")))
        doc = ust_doc(rows, self.first(run).isoformat(), self, self.CSV.format(y="<year>"), B.now())
        self.docs = [doc] if doc else []
        return {"rows": len(doc["HISTORY"]) if doc else 0, "documents": len(self.docs)}


class Sofr(MarketSource):
    name, title, kind = "sofr", "Secured Overnight Financing Rate (Federal Reserve Bank of New York)", "rate-fixing"
    url = "https://www.newyorkfed.org/markets/reference-rates/sofr"
    licence, licence_url = "NY Fed terms of use: reuse with attribution", "https://www.newyorkfed.org/termsofuse"
    cache_prefixes = ("sofr/",)
    API = "https://markets.newyorkfed.org/api/rates/secured/sofr/search.json?startDate={s}&endDate={e}"

    def plan(self, run):
        return [self.API.format(s=self.first(run).isoformat(), e=date.today().isoformat())]

    def fetch(self, run):
        run.http.get(self.plan(run)[0], run.cache.folder / "sofr" / "sofr.json")

    def normalise(self, run):
        raw = json.loads((run.cache.folder / "sofr" / "sofr.json").read_text(encoding="utf-8"))
        doc = fixing_doc("FIX-SOFR", "Secured Overnight Financing Rate", "Federal Reserve Bank of New York", "USD", sofr_obs(raw), self,
                         self.plan(run)[0], B.now())
        self.docs = [doc] if doc else []
        return {"rows": len(doc["FIXINGS"]) if doc else 0, "documents": len(self.docs)}


class Estr(MarketSource):
    name, title, kind = "estr", "Euro short-term rate (European Central Bank)", "rate-fixing"
    url = "https://www.ecb.europa.eu/stats/financial_markets_and_interest_rates/euro_short-term_rate/html/index.en.html"
    licence, licence_url = "ECB: free reuse with the source acknowledged", "https://www.ecb.europa.eu/home/disclaimer/html/index.en.html"
    cache_prefixes = ("estr/",)
    API = "https://data-api.ecb.europa.eu/service/data/EST/B.EU000A2X2A25.WT?startPeriod={s}&format=csvdata"

    def plan(self, run):
        return [self.API.format(s=self.first(run).isoformat())]

    def fetch(self, run):
        run.http.get(self.plan(run)[0], run.cache.folder / "estr" / "estr.csv", accept="text/csv")

    def normalise(self, run):
        text = (run.cache.folder / "estr" / "estr.csv").read_text(encoding="utf-8-sig")
        doc = fixing_doc("FIX-ESTR", "Euro short-term rate", "European Central Bank", "EUR", estr_obs(text), self, self.plan(run)[0], B.now())
        self.docs = [doc] if doc else []
        return {"rows": len(doc["FIXINGS"]) if doc else 0, "documents": len(self.docs)}


SOURCES = [EcbFx(), UsTreasury(), Sofr(), Estr()]
KEYS = {"fx-spot": "PAIR", "ir-curve": "CURVE_ID", "rate-fixing": "INDEX_ID"}
LOAD = ("uv run --with deltalake --with pyarrow --with pyyaml python tools/ingest_jsonl.py --from {out} --domain market "
        "--key fx-spot=PAIR,ir-curve=CURVE_ID,rate-fixing=INDEX_ID --date AS_OF --lake {lake}")


def write(out: Path, sources: list[MarketSource]) -> list[Path]:
    """One <kind>.jsonl a kind, sorted by id; a kind's documents from sources not in this run are kept (re-runs are idempotent)."""
    out.mkdir(parents=True, exist_ok=True)
    written = []
    for kind in sorted({s.kind for s in sources}):
        path, key = out / f"{kind}.jsonl", KEYS[kind]
        docs = {}
        if path.is_file():
            docs = {d[key]: d for d in (json.loads(x) for x in path.read_text(encoding="utf-8").splitlines() if x.strip())}
        for s in sources:
            if s.kind == kind:
                docs.update({d[key]: d for d in s.docs})
        path.write_text("".join(json.dumps(docs[k], ensure_ascii=False, separators=(",", ":")) + "\n" for k in sorted(docs)), encoding="utf-8")
        written.append(path)
    return written


def check(files: list[Path]) -> list[str]:
    """Every line a JSON document with its id, no duplicate ids, every number finite."""
    problems = []
    for f in files:
        key, seen = KEYS[f.stem], set()
        for n, line in enumerate(f.read_text(encoding="utf-8").splitlines(), 1):
            d = json.loads(line)
            if not d.get(key) or d[key] in seen:
                problems.append(f"{f.name}:{n}: missing or repeated {key}")
            seen.add(d.get(key))
            if "NaN" in line or "Infinity" in line:
                problems.append(f"{f.name}:{n}: a number is not finite")
    return problems


def refresh(a, log=print) -> int:
    selected = B.pick(SOURCES, a.sources)
    out = Path(a.out).expanduser().resolve() if a.out else B.data_dir() / "publicdata" / PACK
    if B.is_committed(out):
        raise B.Refusal(f"refused: {out} is inside the repository. The market-data pack's committed samples are synthetic and generated; "
                        "official market data goes to the data folder (leave out --out) or a folder outside the repository.")
    B.guard_out(selected, out, a.internal_ok)
    run = B.Run(PACK, a, out, log)
    if a.dry_run:
        log(f"dry run: {PACK} -> {out}")
        for s in selected:
            log(f"  {s.name} ({s.licence}): {s.kind}.jsonl from {s.first(run)}; " + "; ".join(s.plan(run)[:2])
                + (" ..." if len(s.plan(run)) > 2 else ""))
        return 0
    man = B.Manifest(PACK)
    for s in selected:
        log(f"fetch {s.name}")
        s.fetch(run)
    run.cache.save()
    done = []
    for s in selected:
        try:
            counts = s.normalise(run)
        except (OSError, ValueError, KeyError, zipfile.BadZipFile) as e:
            log(f"normalise {s.name}: skipped ({e})")
            continue
        man.record(s, s.plan(run), counts, out)
        done.append(s)
        log(f"normalise {s.name}: {counts['documents']} documents, {counts['rows']} observations")
    files = write(out, done) if done else []
    problems = [] if a.no_check else check(files)
    for p in problems:
        log(f"check: {p}")
    man.finish(out, files, sys.argv[1:], not problems and not run.http.failed)
    for f in files:
        log(f"wrote {f}")
    if files:
        log("load: " + LOAD.format(out=out, lake=B.data_dir() / "delta"))
    return 1 if not files else 2 if (problems or run.http.failed or len(done) < len(selected)) else 0


def main(argv: list[str] | None = None) -> int:
    a = B.parser("official_market_data.py", __doc__, SOURCES).parse_args(argv)
    try:
        if a.command == "status":
            return B.status(PACK, SOURCES, Path(a.cache) if a.cache else B.data_dir() / "publicdata" / "cache" / PACK,
                            "<data>/publicdata/market-data (fx-spot.jsonl, ir-curve.jsonl, rate-fixing.jsonl)")
        return refresh(a)
    except B.Refusal as e:
        print(e, file=sys.stderr)
        return 3


if __name__ == "__main__":
    sys.exit(main())
