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

"""Download the us-equities pack's public data into a cache folder (the network; make.py then builds the pack offline).

Sources, all openly licensed (docs/guides/packs/US_EQUITIES.md, Data sources and licences):
  Wikidata (CC0)          current members of the Dow Jones Industrial Average, the Nasdaq-100 and the S&P 500 as Wikidata
                          records them (membership only: no weights, no levels), CIK, ticker, LEI, headquarters, founded, website
  SEC EDGAR (US public    company_tickers_exchange.json; submissions/CIK##########.json (names, SIC, addresses, the latest
  domain)                 filings, metadata only); companyfacts (XBRL: revenue, net income, assets, liabilities, equity, EPS,
                          shares outstanding) reduced here to the annual values of those concepts
  Nasdaq Trader           nasdaqlisted.txt and otherlisted.txt (the symbol directory: listing exchange, ETF flag)
  GLEIF (CC0)             the LEI record of each company (by the LEI Wikidata gives, else by legal name)

No price data is fetched or kept: the price-history kind is read live by an off-by-default connector (internal use only).

    python3 tools/packgen/us_equities/fetch.py [--cache DIR] [--only wikidata,sec,nasdaq,gleif] [--limit N]

The default cache is data/cache/us-equities (git-ignored). Files already in the cache are kept: delete one to fetch it again.
SEC asks for a descriptive User-Agent with a contact and at most 10 requests a second; this stays under 8.
"""
from __future__ import annotations

import argparse
import gzip
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
DEFAULT_CACHE = ROOT / "data" / "cache" / "us-equities"
USER_AGENT = os.environ.get("DRISHTI_SEC_USER_AGENT", "Drishti research ajsinha@gmail.com")
INDEXES = {"DJIA": "Q180816", "NDX": "Q507306", "SP500": "Q242345"}
# curated lists, kept here (not in Wikidata): code -> (name, tickers as the SEC ticker file lists them). Only SEC filers can be in this
# pack; banks with no SEC filings (National Bank of Canada, Standard Chartered, Nationwide...) are in the gleif pack's bank groups.
LISTS = {
    "BANKS-US": ("Major US banks", ["JPM", "BAC", "C", "WFC", "GS", "MS", "BNY", "STT", "NTRS", "USB", "PNC", "TFC", "COF", "SCHW", "AXP",
                                    "CFG", "FITB", "MTB", "HBAN", "RF", "KEY", "ALLY", "FCNCA", "SYF", "ZION", "FHN", "WAL", "EWBC",
                                    "CFR", "BOKF", "WTFC", "ONB", "PNFP", "CBSH", "VLY", "FNB", "SSB", "COLB", "UMBF"]),
    "BANKS-CA": ("Canadian banks", ["RY", "TD", "BNS", "BMO", "CM", "VBNK"]),
    "BANKS-UK": ("Major UK banks", ["HSBC", "BCS", "LYG", "NWG"]),
}
ANNUAL_FORMS = ("10-K", "20-F", "40-F")   # a foreign private issuer's annual report is a 20-F or (Canada) a 40-F

# concept -> the XBRL tags tried in order (us-gaap unless prefixed dei: or ifrs-full:), the unit and whether it is a point in time; a
# USD unit means money in the currency the filer reports in (a 40-F in CAD, a 20-F in GBP: _unit)
CONCEPTS = {
    "REVENUE": (["Revenues", "RevenueFromContractWithCustomerExcludingAssessedTax", "SalesRevenueNet", "RevenuesNetOfInterestExpense",
                 "RevenueFromContractWithCustomerIncludingAssessedTax", "SalesRevenueGoodsNet", "ifrs-full:Revenue",
                 "ifrs-full:RevenueFromContractsWithCustomers"], "USD", False),
    "NET_INCOME": (["NetIncomeLoss", "ProfitLoss", "NetIncomeLossAvailableToCommonStockholdersBasic",
                    "ifrs-full:ProfitLossAttributableToOwnersOfParent", "ifrs-full:ProfitLoss"], "USD", False),
    "ASSETS": (["Assets", "ifrs-full:Assets"], "USD", True),
    "LIABILITIES": (["Liabilities", "ifrs-full:Liabilities"], "USD", True),
    "EQUITY": (["StockholdersEquity", "StockholdersEquityIncludingPortionAttributableToNoncontrollingInterest",
                "ifrs-full:EquityAttributableToOwnersOfParent", "ifrs-full:Equity"], "USD", True),
    # used by make.py only to derive total liabilities where a filer reports no Liabilities line (liabilities and equity less total equity)
    "LIABILITIES_AND_EQUITY": (["LiabilitiesAndStockholdersEquity", "ifrs-full:EquityAndLiabilities"], "USD", True),
    "EQUITY_TOTAL": (["StockholdersEquityIncludingPortionAttributableToNoncontrollingInterest", "StockholdersEquity",
                      "ifrs-full:Equity"], "USD", True),
    "EPS_DILUTED":(["EarningsPerShareDiluted", "EarningsPerShareBasic", "ifrs-full:DilutedEarningsLossPerShare",
                   "ifrs-full:BasicEarningsLossPerShare"], "USD/shares", False),
    "SHARES_OUTSTANDING": (["dei:EntityCommonStockSharesOutstanding"], "shares", True),
}
YEARS = 10
FILINGS = 20


class Http:
    """A polite client: one request at a time, a minimum gap per host, gzip accepted, three tries."""

    def __init__(self) -> None:
        self.last: dict[str, float] = {}
        self.gap = {"www.sec.gov": 0.13, "data.sec.gov": 0.13, "api.gleif.org": 1.05, "query.wikidata.org": 1.0}

    def get(self, url: str, accept: str = "*/*") -> bytes | None:
        host = urllib.parse.urlparse(url).hostname or ""
        for attempt in range(3):
            wait = self.last.get(host, 0) + self.gap.get(host, 0.2) - time.monotonic()
            if wait > 0:
                time.sleep(wait)
            self.last[host] = time.monotonic()
            req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Accept": accept, "Accept-Encoding": "gzip"})
            try:
                with urllib.request.urlopen(req, timeout=60) as r:
                    body = r.read()
                    return gzip.decompress(body) if r.headers.get("Content-Encoding") == "gzip" else body
            except urllib.error.HTTPError as e:
                if e.code == 404:
                    return None
                if e.code in (429, 503):
                    time.sleep(5 * (attempt + 1))
                    continue
                print(f"  {url}: HTTP {e.code}", file=sys.stderr)
                return None
            except (urllib.error.URLError, TimeoutError, OSError) as e:
                print(f"  {url}: {e}", file=sys.stderr)
                time.sleep(2 * (attempt + 1))
        return None


def _save(path: Path, data) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".part")
    tmp.write_text(json.dumps(data, ensure_ascii=False, separators=(",", ":")) if not isinstance(data, (bytes, str)) else
                   (data.decode("utf-8") if isinstance(data, bytes) else data), encoding="utf-8")
    tmp.replace(path)


def _sparql(http: Http, query: str) -> list[dict]:
    body = http.get("https://query.wikidata.org/sparql?" + urllib.parse.urlencode({"query": query}), "application/sparql-results+json")
    if body is None:
        raise SystemExit("Wikidata query failed")
    return [{k: v["value"] for k, v in b.items()} for b in json.loads(body)["results"]["bindings"]]


def wikidata(http: Http, cache: Path) -> None:
    out = cache / "wikidata.json"
    if out.exists():
        return
    print("Wikidata: index members and company facts")
    rows = []
    for code, qid in INDEXES.items():
        q = f"""SELECT ?i ?label ?cik ?start WHERE {{ ?i p:P361 ?s . ?s ps:P361 wd:{qid} . FILTER NOT EXISTS {{ ?s pq:P582 ?e }}
               OPTIONAL {{ ?s pq:P580 ?start }} OPTIONAL {{ ?i wdt:P5531 ?cik }}
               OPTIONAL {{ ?i rdfs:label ?label FILTER(lang(?label)='en') }} }}"""
        for r in _sparql(http, q):
            rows.append({"index": code, "qid": r["i"].rsplit("/", 1)[-1], "label": r.get("label", ""), "cik": r.get("cik", ""), "since": r.get("start", "")[:10]})
    rows += _list_members(http, cache)
    qids = sorted({r["qid"] for r in rows if r["qid"]})
    facts: dict[str, dict] = {}
    for i in range(0, len(qids), 150):
        values = " ".join("wd:" + q for q in qids[i:i + 150])
        q = f"""SELECT ?i ?hq ?hqLabel ?founded ?site ?lei ?ticker WHERE {{ VALUES ?i {{ {values} }}
               OPTIONAL {{ ?i wdt:P159 ?hq }} OPTIONAL {{ ?i wdt:P571 ?founded }} OPTIONAL {{ ?i wdt:P856 ?site }}
               OPTIONAL {{ ?i wdt:P1278 ?lei }} OPTIONAL {{ ?i p:P414 ?x . ?x pq:P249 ?ticker }}
               SERVICE wikibase:label {{ bd:serviceParam wikibase:language "en". }} }}"""
        for r in _sparql(http, q):
            f = facts.setdefault(r["i"].rsplit("/", 1)[-1], {})
            for k, src in (("hq", "hqLabel"), ("founded", "founded"), ("website", "site"), ("lei", "lei")):
                if r.get(src) and not f.get(k):
                    f[k] = r[src][:10] if k == "founded" else r[src]
            if r.get("ticker"):
                f.setdefault("tickers", [])
                if r["ticker"] not in f["tickers"]:
                    f["tickers"].append(r["ticker"])
    _save(out, {"retrieved": time.strftime("%Y-%m-%d"), "members": rows, "facts": facts})


def sec_tickers(http: Http, cache: Path) -> None:
    tick = cache / "sec" / "company_tickers_exchange.json"
    if not tick.exists():
        print("SEC: company_tickers_exchange.json")
        body = http.get("https://www.sec.gov/files/company_tickers_exchange.json")
        if body is None:
            raise SystemExit("SEC tickers failed")
        _save(tick, body)


def _list_members(http: Http, cache: Path) -> list[dict]:
    """The members of the curated LISTS as Wikidata member rows: the CIK from the SEC ticker file, the Wikidata item (for
    headquarters, founded, website and LEI) by that CIK (P5531) where Wikidata has one. A ticker the SEC file does not know is
    reported and left out."""
    sec_tickers(http, cache)
    by_ticker, _ = sec_maps(cache)
    rows, ciks = [], set()
    for code, (_name, tickers) in LISTS.items():
        for tk in tickers:
            cik = by_ticker.get(tk)
            if not cik:
                print(f"  {code}: {tk} is not in the SEC ticker file, left out", file=sys.stderr)
                continue
            rows.append({"index": code, "qid": "", "label": "", "cik": cik, "since": ""})
            ciks.add(cik)
    qid_by_cik: dict[str, tuple[str, str]] = {}
    values = " ".join(f'"{int(c):010d}" "{int(c)}"' for c in sorted(ciks, key=int))
    if values:
        q = f"""SELECT ?i ?cik ?label WHERE {{ VALUES ?cik {{ {values} }} ?i wdt:P5531 ?cik .
               OPTIONAL {{ ?i rdfs:label ?label FILTER(lang(?label)='en') }} }}"""
        for r in _sparql(http, q):
            qid_by_cik.setdefault(str(int(r["cik"])), (r["i"].rsplit("/", 1)[-1], r.get("label", "")))
    for r in rows:
        r["qid"], r["label"] = qid_by_cik.get(r["cik"], ("", ""))
    return rows


def nasdaq(http: Http, cache: Path) -> None:
    for name in ("nasdaqlisted.txt", "otherlisted.txt"):
        out = cache / "nasdaq" / name
        if not out.exists():
            print("Nasdaq Trader:", name)
            body = http.get("https://www.nasdaqtrader.com/dynamic/SymDir/" + name)
            if body is None:
                print("  could not fetch", name, file=sys.stderr)
                continue
            _save(out, body)


def _unit(units: dict, unit: str) -> str:
    """The unit to read: the one asked for, else for money the filer's own currency (CAD, GBP, EUR: the first three-letter unit
    with values, with /shares kept for a per-share figure)."""
    if unit in units or not unit.startswith("USD"):
        return unit
    rest = unit[3:]
    return next((u for u in sorted(units) if len(u) == 3 + len(rest) and u.endswith(rest) and u[:3].isalpha() and u[:3].isupper()), unit)


def _annual(facts: dict, tags: list[str], unit: str, instant: bool) -> list[dict]:
    """The last YEARS annual values, one a fiscal year (the year its period ends; the latest filing wins), from the first tag in
    order that has a value for that year. Annual means a 10-K, or a foreign filer's 20-F or 40-F; each row says its unit."""
    years: dict[int, dict] = {}
    for tag in tags:
        ns, name = tag.split(":") if ":" in tag else ("us-gaap", tag)
        units = facts.get(ns, {}).get(name, {}).get("units", {})
        use = _unit(units, unit)
        best: dict[int, dict] = {}
        for v in units.get(use, []):
            if not str(v.get("form", "")).startswith(ANNUAL_FORMS) or not v.get("end"):
                continue
            if not instant:
                if not v.get("start"):
                    continue
                days = (time.mktime(time.strptime(v["end"], "%Y-%m-%d")) - time.mktime(time.strptime(v["start"], "%Y-%m-%d"))) / 86400
                if not 340 <= days <= 380:
                    continue
            year = int(v["end"][:4])
            cur = best.get(year)
            if cur is None or (v["end"], v.get("filed", "")) > (cur["end"], cur["filed"]):
                best[year] = {"year": year, "end": v["end"], "value": v["val"], "tag": name, "filed": v.get("filed", ""),
                              "form": v.get("form", ""), "accn": v.get("accn", ""), "unit": use}
        for y, r in best.items():
            years.setdefault(y, r)
    return [years[y] for y in sorted(years)][-YEARS:]


def sec(http: Http, cache: Path, limit: int | None) -> None:
    sec_tickers(http, cache)
    ciks = universe(cache)[:limit] if limit else universe(cache)
    print(f"SEC: {len(ciks)} companies (submissions and annual facts)")
    for n, cik in enumerate(ciks, 1):
        c10 = f"{int(cik):010d}"
        sub = cache / "sec" / "submissions" / f"{c10}.json"
        if not sub.exists():
            body = http.get(f"https://data.sec.gov/submissions/CIK{c10}.json")
            if body:
                s = json.loads(body)
                r = s.get("filings", {}).get("recent", {})
                filings = [{k: r[k][i] for k in ("accessionNumber", "filingDate", "reportDate", "form", "primaryDocument", "primaryDocDescription", "items", "size")
                            if k in r} for i in range(min(FILINGS, len(r.get("accessionNumber", []))))]
                keep = {k: s.get(k) for k in ("cik", "name", "tickers", "exchanges", "sic", "sicDescription", "category", "entityType", "stateOfIncorporation",
                                               "fiscalYearEnd", "website", "investorWebsite", "phone", "formerNames", "ownerOrg")}
                keep["addresses"] = s.get("addresses", {})
                keep["filings"] = filings
                _save(sub, keep)
        fac = cache / "sec" / "facts" / f"{c10}.json"
        if not fac.exists():
            body = http.get(f"https://data.sec.gov/api/xbrl/companyfacts/CIK{c10}.json")
            if body:
                facts = json.loads(body).get("facts", {})
                _save(fac, {c: _annual(facts, *spec) for c, spec in CONCEPTS.items()})
            else:
                _save(fac, {})
        if n % 50 == 0:
            print(f"  {n}/{len(ciks)}")


def _norm(name: str) -> str:
    words = [w for w in "".join(ch if ch.isalnum() else " " for ch in name.lower()).split()
             if w not in {"inc", "corp", "corporation", "co", "company", "the", "holdings", "group", "ltd", "plc", "llc"}]
    return " ".join(words)


def member_cik(m: dict, facts: dict, by_ticker: dict[str, str], by_name: dict[str, str]) -> str:
    """A Wikidata index member's CIK: Wikidata's own (P5531), else the SEC ticker file's for a ticker Wikidata gives, else the SEC
    ticker file's for a company whose name is the member's label (Netflix, PayPal Holdings)."""
    if m.get("cik"):
        return str(int(m["cik"]))
    for tk in facts.get("tickers", []):
        if tk.upper() in by_ticker:
            return by_ticker[tk.upper()]
    label = _norm(m.get("label", ""))
    return by_name.get(label, "") if label else ""


def sec_maps(cache: Path) -> tuple[dict[str, str], dict[str, str]]:
    """{ticker: CIK} and {normalised name: CIK} from the SEC ticker file (a name shared by two CIKs is dropped)."""
    t = json.loads((cache / "sec" / "company_tickers_exchange.json").read_text(encoding="utf-8"))
    by_ticker = {row[2]: str(row[0]) for row in t["data"]}
    by_name: dict[str, str] = {}
    for row in t["data"]:
        n = _norm(row[1])
        by_name[n] = str(row[0]) if by_name.get(n, str(row[0])) == str(row[0]) else ""
    return by_ticker, by_name


def universe(cache: Path) -> list[str]:
    """The CIKs of the current index members (member_cik)."""
    wd = json.loads((cache / "wikidata.json").read_text(encoding="utf-8"))
    by_ticker, by_name = sec_maps(cache)
    out = {member_cik(m, wd["facts"].get(m["qid"], {}), by_ticker, by_name) for m in wd["members"]}
    return sorted((c for c in out if c), key=int)


def gleif(http: Http, cache: Path) -> None:
    wd = json.loads((cache / "wikidata.json").read_text(encoding="utf-8"))
    lei_by_cik: dict[str, str] = {}
    for m in wd["members"]:
        f = wd["facts"].get(m["qid"], {})
        if m["cik"] and f.get("lei"):
            lei_by_cik[str(int(m["cik"]))] = f["lei"]
    ciks = universe(cache)
    print(f"GLEIF: {len(ciks)} companies ({len(lei_by_cik)} with an LEI from Wikidata)")
    for cik in ciks:
        out = cache / "gleif" / f"{int(cik):010d}.json"
        if out.exists():
            continue
        lei = lei_by_cik.get(cik)
        rec = None
        if lei:
            body = http.get("https://api.gleif.org/api/v1/lei-records/" + lei, "application/vnd.api+json")
            rec = json.loads(body).get("data") if body else None
            how = "LEI from Wikidata"
        if rec is None:
            sub = cache / "sec" / "submissions" / f"{int(cik):010d}.json"
            if not sub.exists():
                continue
            s = json.loads(sub.read_text(encoding="utf-8"))
            name = re.sub(r"\s*/[A-Z]{2,4}/?\s*$", "", s.get("name", "")).strip()       # EDGAR's suffixes: "/CAN/", "/DE/"
            biz = (s.get("addresses") or {}).get("business") or {}
            where = biz.get("stateOrCountry") or biz.get("countryCode") or ""
            country = "CA" if where[:1] == "A" or where == "B0" else "GB" if where == "X0" else "US"   # EDGAR's codes abroad
            q = urllib.parse.urlencode({"filter[entity.legalName]": name, "filter[entity.legalAddress.country]": country, "page[size]": "5"})
            body = http.get("https://api.gleif.org/api/v1/lei-records?" + q, "application/vnd.api+json")
            data = json.loads(body).get("data", []) if body else []
            data = [d for d in data if d["attributes"]["entity"].get("status") == "ACTIVE"]
            exact = [d for d in data if d["attributes"]["entity"]["legalName"]["name"].casefold() == name.casefold()]
            rec = data[0] if len(data) == 1 else exact[0] if len(exact) == 1 else None
            how = "matched by legal name"
        if rec is None:
            _save(out, {})
            continue
        a = rec["attributes"]
        e = a["entity"]
        _save(out, {"lei": a["lei"], "how": how, "legalName": e["legalName"]["name"], "status": e.get("status"),
                    "jurisdiction": e.get("jurisdiction"), "legalForm": (e.get("legalForm") or {}).get("id"),
                    "city": (e.get("headquartersAddress") or {}).get("city"), "region": (e.get("headquartersAddress") or {}).get("region"),
                    "registration": (a.get("registration") or {}).get("status"), "nextRenewal": ((a.get("registration") or {}).get("nextRenewalDate") or "")[:10]})


def main(argv: list[str]) -> int:
    p = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    p.add_argument("--cache", type=Path, default=DEFAULT_CACHE)
    p.add_argument("--only", default="wikidata,nasdaq,sec,gleif")
    p.add_argument("--limit", type=int, help="the first N companies only (a quick try)")
    a = p.parse_args(argv)
    a.cache.mkdir(parents=True, exist_ok=True)
    http = Http()
    steps = a.only.split(",")
    if "wikidata" in steps:
        wikidata(http, a.cache)
    if "nasdaq" in steps:
        nasdaq(http, a.cache)
    if "sec" in steps:
        sec(http, a.cache, a.limit)
    if "gleif" in steps:
        gleif(http, a.cache)
    print("cache:", a.cache)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
