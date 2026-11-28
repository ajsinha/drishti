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

"""Build the us-equities pack's documents from the cache fetch.py fills (no network).

    python3 tools/packgen/us_equities/make.py [--cache DIR]           write data/testdata/us-equities/, the samples and the README
    python3 tools/packgen/us_equities/make.py --check [--cache DIR]   fail if what would be written differs from the pack

With a cache, --check rebuilds every document from it. Without one (a fresh clone, CI), --check reads the committed snapshot back
(data/testdata/us-equities/) and rebuilds what is derived from it (the reference samples, the README), so it needs no network.

The documents follow Drishti's standard data pattern (tools/testdata/publicsnap.py): one gzipped JSON Lines snapshot per kind under
data/testdata/us-equities/<kind>/, listed in data/testdata/testdata.yaml, built into the lake by tools/build_demo_lakes.py and served by
the pack's Delta connector. samples/ keeps only a few reference documents for the README and docs; nothing is served from it.
The pack's manifest, Sutras, fragments and help are hand-written; this writes the snapshot, samples/ and the README
(the pack's story is a section of docs/guides/WORLD_DATA_GUIDE.md since i7k).
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
sys.path.insert(0, str(HERE.parent / "common"))
import packsamples as PS  # noqa: E402
import readme as README  # noqa: E402  (the pack README standard)
sys.path.insert(0, str(HERE))
import fetch as F  # noqa: E402
sys.path.insert(0, str(ROOT / "tools" / "testdata"))
import publicsnap as SNAP  # noqa: E402  (the standard data pattern: data/testdata/<pack>/<kind>/)

PACK = ROOT / "modules" / "external-data" / "packs" / "us-equities"
DEFAULT_CACHE = ROOT / "data" / "cache" / "us-equities"
INDEX_NAMES = {"DJIA": "Dow Jones Industrial Average", "NDX": "Nasdaq-100", "SP500": "S&P 500", "R2000": "Russell 2000",
               **{c: n for c, (n, _t) in F.LISTS.items()}}
# the world-economy economy of a company whose business address is abroad: EDGAR's codes for the Canadian provinces and the UK
ECONOMY_ABROAD = {**{f"A{i}": "ECON-CAN" for i in range(10)}, "B0": "ECON-CAN", "X0": "ECON-GBR"}
CONCEPTS = {  # concept -> label, unit, what it is
    "REVENUE": ("Revenue", "USD", "Total revenue for the fiscal year (us-gaap Revenues, else revenue from contracts with customers, else net sales)."),
    "NET_INCOME": ("Net income", "USD", "Net income (loss) attributable to the company for the fiscal year (us-gaap NetIncomeLoss, else ProfitLoss)."),
    "ASSETS": ("Total assets", "USD", "Total assets at the fiscal year end (us-gaap Assets)."),
    "LIABILITIES": ("Total liabilities", "USD", "Total liabilities at the fiscal year end (us-gaap Liabilities; where a filer reports none, liabilities and equity less total equity)."),
    "EQUITY": ("Stockholders' equity", "USD", "Equity attributable to the company's stockholders at the fiscal year end (us-gaap StockholdersEquity)."),
    "EPS_DILUTED": ("Diluted EPS", "USD/share", "Diluted earnings per share for the fiscal year (us-gaap EarningsPerShareDiluted, else basic)."),
    "SHARES_OUTSTANDING": ("Shares outstanding", "shares", "Common shares outstanding on the cover of the annual report (dei EntityCommonStockSharesOutstanding), dated after the year end."),
}
DIVISIONS = [(100, 999, "A", "Agriculture, Forestry and Fishing"), (1000, 1499, "B", "Mining"), (1500, 1799, "C", "Construction"),
             (2000, 3999, "D", "Manufacturing"), (4000, 4999, "E", "Transportation, Communications, Electric, Gas and Sanitary Services"),
             (5000, 5199, "F", "Wholesale Trade"), (5200, 5999, "G", "Retail Trade"), (6000, 6799, "H", "Finance, Insurance and Real Estate"),
             (7000, 8999, "I", "Services"), (9100, 9729, "J", "Public Administration"), (9900, 9999, "K", "Nonclassifiable")]
FORMS = {"10-K": "Annual report", "10-K/A": "Annual report, amended", "10-Q": "Quarterly report", "8-K": "Current report", "8-K/A": "Current report, amended",
         "4": "Change in beneficial ownership (insider)", "3": "Initial beneficial ownership (insider)", "5": "Annual beneficial ownership (insider)",
         "144": "Notice of proposed sale of securities", "DEF 14A": "Definitive proxy statement", "DEFA14A": "Additional proxy materials",
         "S-8": "Securities for employee plans", "S-3ASR": "Automatic shelf registration", "424B2": "Prospectus", "424B5": "Prospectus supplement",
         "FWP": "Free writing prospectus", "11-K": "Annual report of an employee plan", "SD": "Specialized disclosure (conflict minerals)",
         "SC 13G": "Beneficial ownership over 5% (passive)", "SC 13G/A": "Beneficial ownership over 5% (passive), amended", "ARS": "Annual report to shareholders",
         "PX14A6G": "Notice of exempt solicitation", "CORRESP": "Correspondence with the SEC", "UPLOAD": "SEC staff letter", "IRANNOTICE": "Iran activity notice",
         "13F-HR": "Institutional holdings report", "S-4": "Registration for a merger or exchange", "25-NSE": "Removal from listing", "CERT": "Exchange certification"}
ITEMS_8K = {"1.01": "Entry into a material definitive agreement", "1.02": "Termination of a material definitive agreement", "1.03": "Bankruptcy or receivership",
            "1.05": "Material cybersecurity incident", "2.01": "Completion of an acquisition or disposition", "2.02": "Results of operations and financial condition",
            "2.03": "Creation of a direct financial obligation", "2.04": "Triggering events that accelerate an obligation", "2.05": "Costs of exit or disposal activities",
            "2.06": "Material impairments", "3.01": "Notice of delisting or failure to meet a listing rule", "3.02": "Unregistered sales of equity securities",
            "3.03": "Material modification to the rights of security holders", "4.01": "Change in the certifying accountant", "4.02": "Non-reliance on earlier financial statements",
            "5.01": "Change in control", "5.02": "Departure or appointment of directors or officers; compensation", "5.03": "Amendments to the articles or bylaws; change in fiscal year",
            "5.05": "Amendments to the code of ethics", "5.07": "Submission of matters to a vote of security holders", "5.08": "Shareholder director nominations",
            "7.01": "Regulation FD disclosure", "8.01": "Other events", "9.01": "Financial statements and exhibits"}
FEEDS = {"ALL": "Every form", "10-K": "Annual reports", "10-Q": "Quarterly reports", "8-K": "Current reports", "4": "Insider transactions (Form 4)",
         "DEF-14A": "Proxy statements"}
FEED_ROWS = 300
SOURCE_SEC = "U.S. Securities and Exchange Commission, EDGAR"
LICENCE_SEC = "Public domain (U.S. Government work); SEC EDGAR fair access policy (descriptive User-Agent, at most 10 requests a second)"
PROV_INDEX = {"SOURCE": "Wikidata (index membership as Wikidata records it); SEC EDGAR (company data)", "LICENCE": "CC0 1.0 (Wikidata); public domain (SEC EDGAR)",
              "NOTE": "Membership only. Index weights and levels are proprietary to their index providers and are not included."}
PROV_LIST = {"SOURCE": "A list kept by Drishti (tools/packgen/us_equities/fetch.py LISTS) of the banks that file with the SEC; SEC EDGAR (company data)",
             "LICENCE": "Public domain (SEC EDGAR)",
             "NOTE": "Banks with no SEC filings are in the gleif pack. Totals add the members that report in USD only; a 40-F (Canada) or 20-F "
                     "(UK) filer reports in its own currency, shown per member."}


def _division(sic: str) -> tuple[str, str]:
    try:
        n = int(sic)
    except (TypeError, ValueError):
        return "", "Unclassified"
    for lo, hi, code, name in DIVISIONS:
        if lo <= n <= hi:
            return code, name
    return "", "Unclassified"


def _title(name: str) -> str:
    """SEC names are upper case (APPLE INC); Wikidata's label is preferred, this is the fallback."""
    if not name.isupper():
        return name
    small = {"OF", "AND", "THE", "&"}
    keep = {"INC", "CORP", "CO", "LTD", "PLC", "NV", "SA", "AG", "LP", "LLC", "N.V.", "S.A.", "IBM", "AT&T", "USA", "US"}
    out = []
    for i, w in enumerate(name.split()):
        w2 = w.strip(",.")
        out.append(w if w2 in keep - {"INC", "CORP", "CO", "LTD"} else (w.lower() if w in small and i else w.capitalize()))
    return " ".join(out)


def _r(x, n=4):
    return None if x is None else round(x, n)


def _load_json(p: Path):
    return json.loads(p.read_text(encoding="utf-8")) if p.is_file() else None


def _other_packs() -> tuple[set[str], set[str]]:
    """core legal-entity LEIs (a company links to one whose LEI matches) and the news-and-markets companies (CO-<ticker>)."""
    leis, cos = set(), set()
    for f, key, into in ((README.PP.pack_dir("core") / "samples" / "legal-entity.jsonl", "LEI", leis),
                         (README.PP.pack_dir("news-and-markets") / "samples" / "listed-company.jsonl", "COMPANY_ID", cos)):
        if f.is_file():
            for line in f.read_text(encoding="utf-8").splitlines():
                if line.strip():
                    d = json.loads(line)
                    if d.get(key):
                        into.add(d[key] if key == "COMPANY_ID" else (d[key], d.get("ENTITY_ID")))
    return leis, cos


def _nasdaq(cache: Path) -> dict[str, dict]:
    """ticker (SEC style, BRK-B) -> the Nasdaq Trader directory row: listing market and whether it is an ETF."""
    out: dict[str, dict] = {}
    markets = {"Q": "Nasdaq Global Select Market", "G": "Nasdaq Global Market", "S": "Nasdaq Capital Market"}
    other = {"A": "NYSE American", "N": "New York Stock Exchange", "P": "NYSE Arca", "Z": "Cboe BZX", "V": "IEX"}
    for name, sym, mkt in (("nasdaqlisted.txt", "Symbol", "Market Category"), ("otherlisted.txt", "ACT Symbol", "Exchange")):
        f = cache / "nasdaq" / name
        if not f.is_file():
            continue
        lines = f.read_text(encoding="utf-8").splitlines()
        head = lines[0].split("|")
        for line in lines[1:]:
            row = dict(zip(head, line.split("|")))
            s = row.get(sym, "")
            if not s or s.startswith("File Creation"):
                continue
            listing = markets.get(row.get(mkt, ""), "") if name.startswith("nasdaq") else other.get(row.get(mkt, ""), row.get(mkt, ""))
            out[s.replace(".", "-").replace("$", "-P")] = {"listing": listing, "etf": row.get("ETF") == "Y", "security": row.get("Security Name", "")}
    return out



def build(cache: Path) -> dict[str, dict[str, dict]]:
    wd = _load_json(cache / "wikidata.json")
    tick = _load_json(cache / "sec" / "company_tickers_exchange.json")
    if not wd or not tick:
        raise SystemExit(f"no cache at {cache}: run tools/packgen/us_equities/fetch.py first")
    retrieved = wd.get("retrieved", "")
    by_ticker, by_name = F.sec_maps(cache)
    first_ticker: dict[str, str] = {}
    for row in tick["data"]:                       # the SEC file lists a company's primary ticker first
        first_ticker.setdefault(str(row[0]), row[2])
    nasdaq = _nasdaq(cache)
    core_leis, news_cos = _other_packs()
    core_lei = dict(core_leis)

    # index membership per CIK, and Wikidata facts per CIK
    member: dict[str, dict[str, dict]] = {}
    wfacts: dict[str, dict] = {}
    for m in wd["members"]:
        f = wd["facts"].get(m["qid"], {})
        cik = F.member_cik(m, f, by_ticker, by_name)
        if not cik:
            continue
        member.setdefault(cik, {})[m["index"]] = {"since": m.get("since", ""), "label": m.get("label", "")}
        wfacts.setdefault(cik, {"label": m.get("label", ""), **f})
    # an index its tracking ETF reports (trackers.json): the ETF's holdings are the members, Wikidata's list only gives the date a member
    # joined; a company Wikidata still lists but the ETF does not hold stays in the pack, in no index
    tracked = F.tracked(cache)
    for code, ix in tracked.items():
        since = {cik: v[code]["since"] for cik, v in member.items() if code in v}
        for v in member.values():
            v.pop(code, None)
        for m in ix["MEMBERS"]:
            member.setdefault(m["CIK"], {})[code] = {"since": since.get(m["CIK"], ""), "label": m["NAME"]}

    companies: dict[str, dict] = {}
    filings: dict[str, dict] = {}
    facts: dict[str, dict] = {}
    for cik in sorted(member, key=int):
        c10 = f"{int(cik):010d}"
        sub = _load_json(cache / "sec" / "submissions" / f"{c10}.json")
        if not sub:
            continue
        ticker = first_ticker.get(cik) or (sub.get("tickers") or [""])[0]
        if not ticker:
            continue
        w = wfacts.get(cik, {})
        name = w.get("label") or _title(sub.get("name", ""))
        sic = str(sub.get("sic") or "")
        div_code, div = _division(sic)
        addr = dict((sub.get("addresses") or {}).get("business") or {})
        addr["stateOrCountry"] = addr.get("stateOrCountry") or addr.get("countryCode")    # some foreign filers give only the code
        us = bool(addr.get("stateOrCountry")) and not addr.get("isForeignLocation") and len(addr.get("stateOrCountry") or "") == 2 \
            and (addr.get("stateOrCountry") or "").isalpha()
        g = _load_json(cache / "gleif" / f"{c10}.json") or {}
        raw = _load_json(cache / "sec" / "facts" / f"{c10}.json") or {}
        # derive total liabilities where none is reported: liabilities and equity less total equity
        liab = {r["year"]: r for r in raw.get("LIABILITIES", [])}
        eq_total = {r["year"]: r for r in raw.get("EQUITY_TOTAL", [])}
        for r in raw.get("LIABILITIES_AND_EQUITY", []):
            if r["year"] not in liab and r["year"] in eq_total:
                liab[r["year"]] = {**r, "value": r["value"] - eq_total[r["year"]]["value"], "tag": "LiabilitiesAndStockholdersEquity less equity"}
        raw["LIABILITIES"] = [liab[y] for y in sorted(liab)][-F.YEARS:]
        # and total assets where none is reported for a year: total equity and liabilities (the balance sheet's other side)
        assets = {r["year"]: r for r in raw.get("ASSETS", [])}
        for r in raw.get("LIABILITIES_AND_EQUITY", []):
            assets.setdefault(r["year"], {**r, "tag": r.get("tag", "") + " (total equity and liabilities)"})
        raw["ASSETS"] = [assets[y] for y in sorted(assets)][-F.YEARS:]
        years = sorted({r["year"] for k in ("REVENUE", "NET_INCOME", "ASSETS", "EQUITY") for r in raw.get(k, [])})[-F.YEARS:]
        series = {k: {r["year"]: r for r in raw.get(k, [])} for k in CONCEPTS}
        fin = []
        for y in years:
            rev, ni = (series["REVENUE"].get(y) or {}).get("value"), (series["NET_INCOME"].get(y) or {}).get("value")
            fin.append({"YEAR": y, "PERIOD_END": next((series[k][y]["end"] for k in ("REVENUE", "NET_INCOME", "ASSETS") if y in series[k]), ""),
                        "REVENUE": rev, "NET_INCOME": ni,
                        "ASSETS": (series["ASSETS"].get(y) or {}).get("value"), "LIABILITIES": (series["LIABILITIES"].get(y) or {}).get("value"),
                        "EQUITY": (series["EQUITY"].get(y) or {}).get("value"), "EPS_DILUTED": (series["EPS_DILUTED"].get(y) or {}).get("value")})
        last = fin[-1] if fin else {}
        shares = raw.get("SHARES_OUTSTANDING") or []
        idx = member.get(cik, {})
        currency = next((r["unit"] for k in ("REVENUE", "ASSETS", "NET_INCOME") for r in reversed(raw.get(k) or []) if r.get("unit")), "USD")
        fact_ids = []
        for concept, (label, unit, _what) in CONCEPTS.items():
            rows = raw.get(concept) or []
            if not rows:
                continue
            fid = f"{ticker}-{concept.replace('_', '-')}"
            fact_ids.append({"FACT": fid, "LABEL": label})
            hist = []
            for i, r in enumerate(rows):
                # the change on the year is not written: the page computes it from the values (since 2.9.2)
                hist.append({"YEAR": r["year"], "PERIOD_END": r["end"], "VALUE": r["value"], "FILED": r.get("filed", ""),
                             "ACCESSION": r.get("accn", "")})
            lv = rows[-1]
            unit_used = lv.get("unit", unit.replace("/share", "/shares")).replace("/shares", "/share")
            facts[fid] = {"FACT_ID": fid, "COMPANY": ticker, "COMPANY_NAME": name, "CIK": c10, "CONCEPT": concept, "LABEL": label, "UNIT": unit_used,
                          "XBRL_TAG": ", ".join(sorted({r.get("tag", "") for r in rows})), "LATEST": lv["value"], "LATEST_YEAR": lv["year"],
                          "YEARS": len(hist), "HISTORY": hist,
                          "_meta": _meta(f"XBRL companyfacts ({(lv.get('form') or '10-K').removesuffix('/A')})", retrieved)}
        flist = []
        for fl in sub.get("filings", []):
            acc = fl["accessionNumber"]
            form = fl.get("form", "")
            items = [{"ITEM": it, "DESCRIPTION": ITEMS_8K.get(it, "")} for it in (fl.get("items") or "").split(",") if it]
            desc = fl.get("primaryDocDescription") or FORMS.get(form, form)
            flist.append({"ACCESSION": acc, "FORM": form, "FILED": fl.get("filingDate", ""), "REPORT_DATE": fl.get("reportDate", ""),
                          "DESCRIPTION": FORMS.get(form, desc)})
            filings[acc] = {"ACCESSION": acc, "COMPANY": ticker, "COMPANY_NAME": name, "FORM": form, "FORM_NAME": FORMS.get(form, desc),
                            "FILED": fl.get("filingDate", ""), "REPORT_DATE": fl.get("reportDate", ""), "PRIMARY_DOCUMENT": fl.get("primaryDocument", ""),
                            "DOCUMENT_DESCRIPTION": desc, "SIZE_KB": round((fl.get("size") or 0) / 1024), "ITEMS": items,
                            "ITEMS_TEXT": ", ".join(i["ITEM"] for i in items), "PATH": f"{int(cik)}/{acc.replace('-', '')}",
                            "_meta": _meta("submissions (recent filings)", retrieved)}
        nq = nasdaq.get(ticker, {})
        lei = g.get("lei") or w.get("lei") or ""
        doc = {
            "TICKER": ticker, "NAME": name, "SEC_NAME": sub.get("name", ""), "CIK": c10, "OTHER_TICKERS": ", ".join(t for t in sub.get("tickers") or [] if t != ticker),
            "EXCHANGE": ", ".join(e for e in sub.get("exchanges") or [] if e), "LISTING": nq.get("listing", ""),
            "SIC": sic, "SIC_DESCRIPTION": sub.get("sicDescription") or "", "DIVISION": div_code, "SECTOR": div,
            "STATE_OF_INCORPORATION": sub.get("stateOfIncorporation") or "", "FISCAL_YEAR_END": (lambda s: f"{s[:2]}-{s[2:]}" if s and len(s) == 4 else "")(sub.get("fiscalYearEnd") or ""),
            "FILER_CATEGORY": sub.get("category") or "", "HEADQUARTERS": w.get("hq") or "", "BUSINESS_ADDRESS": ", ".join(x for x in (addr.get("city"), addr.get("stateOrCountry")) if x),
            "FOUNDED": w.get("founded") or "", "WEBSITE": w.get("website") or sub.get("website") or "", "PHONE": sub.get("phone") or "",
            "LEI": lei, "LEI_LEGAL_NAME": g.get("legalName", ""), "LEI_STATUS": g.get("registration", ""), "LEI_SOURCE": ("GLEIF, " + g["how"]) if g.get("how") else ("Wikidata" if lei else ""),
            "INDEXES": [{"INDEX": k, "NAME": INDEX_NAMES[k], "SINCE": v["since"]} for k, v in sorted(idx.items())],
            "INDEX_TEXT": ", ".join(INDEX_NAMES[k] for k in sorted(idx)),
            # the ratios (net margin, revenue growth) are not written: the pages compute them from these facts (since 2.9.2)
            "CURRENCY": currency, "FISCAL_YEAR": last.get("YEAR"), "REVENUE": last.get("REVENUE"), "NET_INCOME": last.get("NET_INCOME"),
            "ASSETS": last.get("ASSETS"), "LIABILITIES": last.get("LIABILITIES"), "EQUITY": last.get("EQUITY"), "EPS_DILUTED": last.get("EPS_DILUTED"),
            "SHARES_OUTSTANDING": shares[-1]["value"] if shares else None,
            "FINANCIALS": fin, "FACTS": fact_ids, "FILINGS": flist,
            "INDEX_IDS": sorted(idx), "FACT_IDS": [f["FACT"] for f in fact_ids], "FILING_IDS": [f["ACCESSION"] for f in flist],
            "LATEST_FILING": flist[0]["FILED"] if flist else "",
            "SIC_SECTOR": sic or None, "PRICE_HISTORY": f"PX-{ticker}",
            "PROVENANCE": {"SOURCE": SOURCE_SEC + " (submissions, XBRL company facts); Wikidata; GLEIF; Nasdaq Trader symbol directory",
                           "RETRIEVED_AT": retrieved, "LICENCE": "Public domain (SEC EDGAR); CC0 1.0 (Wikidata, GLEIF)",
                           "URL": f"https://www.sec.gov/cgi-bin/browse-edgar?action=getcompany&CIK={c10}"},
            "_meta": {"source": "sec-edgar", "dataset": "submissions, companyfacts, Wikidata, GLEIF", "retrievedAt": retrieved, "licence": "public domain; CC0", "generation": 1},
        }
        if us:
            doc["ECONOMY"] = "ECON-USA"
        elif ECONOMY_ABROAD.get(addr.get("stateOrCountry") or ""):
            doc["ECONOMY"] = ECONOMY_ABROAD[addr["stateOrCountry"]]
        if f"CO-{ticker}" in news_cos:
            doc["MARKET_WATCH"] = f"CO-{ticker}"
        if lei and lei in core_lei:
            doc["LEGAL_ENTITY"] = core_lei[lei]
        companies[ticker] = doc

    # index pages
    indexes: dict[str, dict] = {}
    for code, label in INDEX_NAMES.items():
        rows = []
        for t, c in sorted(companies.items()):
            hit = next((i for i in c["INDEXES"] if i["INDEX"] == code), None)
            if hit:
                rows.append({"TICKER": t, "NAME": c["NAME"], "SECTOR": c["SECTOR"], "SIC": c["SIC"], "SIC_DESCRIPTION": c["SIC_DESCRIPTION"],
                             "EXCHANGE": c["EXCHANGE"], "REVENUE": c["REVENUE"], "NET_INCOME": c["NET_INCOME"],
                             "CURRENCY": c["CURRENCY"], "FISCAL_YEAR": c["FISCAL_YEAR"], "SINCE": hit["SINCE"]})
        # the members' totals (revenue, net income, SIC divisions) are not written: the page computes them from the rows (since 2.9.2)
        prov, meta = (PROV_LIST, {"source": "drishti list", "dataset": "a curated list of SEC filers", "licence": "public domain"}) \
            if code in F.LISTS else _tracker_prov(tracked.get(code)) if code in tracked else \
            (PROV_INDEX, {"source": "wikidata", "dataset": "index membership (Wikidata P361)", "licence": "CC0"})
        indexes[code] = {"INDEX": code, "NAME": label, "MEMBERS": len(rows),
                         "ROWS": rows, "PROVENANCE": {**prov, "RETRIEVED_AT": retrieved},
                         "_meta": {**meta, "retrievedAt": retrieved, "generation": 1}}

    # The SIC sectors (SIC) are not written: the us-equities-groupings connector computes them from the companies when asked
    # (since 2.9.2; docs/guides/bi/README.md).

    # the filings browser: the latest filings of the covered companies, all forms and by form
    feeds: dict[str, dict] = {}
    every = sorted(filings.values(), key=lambda f: (f["FILED"], f["ACCESSION"]), reverse=True)
    for fid, label in FEEDS.items():
        form = fid.replace("-", " ") if fid == "DEF-14A" else fid
        sel = [f for f in every if fid == "ALL" or f["FORM"] == form][:FEED_ROWS]
        counts: dict[str, int] = {}
        for f in sel:
            k = f["FORM"] if fid == "ALL" else f["COMPANY"]
            counts[k] = counts.get(k, 0) + 1
        feeds[fid] = {"FEED": fid, "NAME": label, "FORM": "" if fid == "ALL" else form, "FILINGS": len(sel), "COMPANIES": len({f["COMPANY"] for f in sel}),
                      "LATEST": sel[0]["FILED"] if sel else "", "EARLIEST": sel[-1]["FILED"] if sel else "",
                      "ROWS": [{"ACCESSION": f["ACCESSION"], "FILED": f["FILED"], "COMPANY": f["COMPANY"], "COMPANY_NAME": f["COMPANY_NAME"], "FORM": f["FORM"],
                                "DESCRIPTION": f["FORM_NAME"]} for f in sel],
                      "BY": [{"LABEL": k, "VALUE": v} for k, v in sorted(counts.items(), key=lambda kv: (-kv[1], kv[0]))][:15],
                      "BY_TITLE": "Filings by form" if fid == "ALL" else "Filings by company",
                      "PROVENANCE": {"SOURCE": SOURCE_SEC + " (submissions: the latest filings of each company covered)", "RETRIEVED_AT": retrieved, "LICENCE": LICENCE_SEC},
                      "_meta": {"source": "sec-edgar", "dataset": "submissions (recent filings)", "retrievedAt": retrieved, "licence": "public domain", "generation": 1}}

    return {"us-company": companies, "index-membership": indexes, "filing-feed": feeds, "filing": filings, "financial-fact": facts}


def _tracker_prov(ix: dict) -> tuple[dict, dict]:
    """An index's provenance when its tracking ETF's N-PORT gives the members (trackers.py)."""
    gone = ", ".join(m["NAME"] for m in ix["NOT_LISTED"])
    prov = {"SOURCE": f"SEC Form N-PORT of {ix['FUND']} ({ix['FUND_NAME']}), the stocks it reported holding on {ix['REPORT_DATE']} "
                      f"(filed {ix['FILED']}, accession {ix['ACCESSION']}); SEC EDGAR (company data); Wikidata (the date a member joined)",
            "LICENCE": "Public domain (SEC EDGAR); CC0 1.0 (Wikidata)",
            "NOTE": "Membership as the tracking fund holds it on its report date. Index weights and levels are proprietary to their index "
                    "providers and are not included." + (f" Held then, no longer listed by the SEC: {gone}." if gone else "")}
    return prov, {"source": "sec-nport", "dataset": f"holdings of {ix['FUND']} (Form N-PORT)", "licence": "public domain"}


def _meta(dataset: str, retrieved: str) -> dict:
    return {"source": "sec-edgar", "dataset": dataset, "retrievedAt": retrieved, "generation": 1}


def _money(v) -> str:
    if v is None:
        return "n/a"
    a = abs(v)
    return f"USD {v / 1e9:,.1f}bn" if a >= 1e9 else f"USD {v / 1e6:,.1f}m"


def catalog(docs: dict[str, dict[str, dict]]) -> list[dict]:
    out = []
    for i, d in docs.get("index-membership", {}).items():
        out.append({"kind": "index-membership", "id": i, "title": i, "subtitle": f"Index · {d['NAME']} · {d['MEMBERS']} members"})
    for i, d in docs.get("us-company", {}).items():
        out.append({"kind": "us-company", "id": i, "title": i, "subtitle": f"US company · {d['NAME']} · {d['SIC_DESCRIPTION']}"})
    for i, d in docs.get("filing-feed", {}).items():
        out.append({"kind": "filing-feed", "id": i, "title": f"EDGAR {i}", "subtitle": f"Filings · {d['NAME']}"})
    for i, d in docs.get("financial-fact", {}).items():
        out.append({"kind": "financial-fact", "id": i, "title": i, "subtitle": f"{d['COMPANY']} {d['LABEL']} {d['LATEST_YEAR']}"})
    for i, d in docs.get("filing", {}).items():
        out.append({"kind": "filing", "id": i, "title": i, "subtitle": f"{d['COMPANY']} {d['FORM']} {d['FILED']}"})
    return out


def _rel(p: Path) -> str:
    return str(p.relative_to(ROOT)) if p.resolve().is_relative_to(ROOT) else str(p)


def files(docs: dict[str, dict[str, dict]]) -> dict[Path, str]:
    ref = SNAP.reference("us-equities", docs)                     # a few reference documents: the data itself is in data/testdata
    out = {PACK / "samples" / name: text for name, text in PS.files(ref, catalog(ref), catalog_indent=None).items()}
    out[PACK / "README.md"] = README.render_pack(PACK, files=out)
    return out


def main(argv: list[str]) -> int:
    p = argparse.ArgumentParser(description="Build the us-equities pack from the fetched cache (no network).")
    p.add_argument("--cache", type=Path, default=DEFAULT_CACHE)
    p.add_argument("--check", action="store_true")
    p.add_argument("--pack", type=Path, help="the pack folder to write or check (default modules/external-data/packs/us-equities; "
                                               "drishti.py feed run us-equities points a widened build at the data folder)")
    p.add_argument("--testdata", type=Path, help="the test data folder the snapshot goes to (default data/testdata for the committed "
                                                  "pack; beside a widened build's pack folder otherwise)")
    a = p.parse_args(argv)
    global PACK
    PACK = a.pack.resolve() if a.pack else ROOT / "modules" / "external-data" / "packs" / "us-equities"
    testdata = a.testdata.resolve() if a.testdata else SNAP.default_testdata(PACK, ROOT / "modules" / "external-data" / "packs" / "us-equities")
    if a.cache.joinpath("wikidata.json").is_file():
        docs = build(a.cache)
    elif a.check:
        docs = SNAP.read(testdata, "us-equities")
        if not docs:
            raise SystemExit(f"no cache at {a.cache} and no snapshot in {testdata}/us-equities: run tools/packgen/us_equities/fetch.py first")
        print(f"no cache at {a.cache}: checking the committed snapshot ({testdata}/us-equities) against what it derives")
    else:
        raise SystemExit(f"no cache at {a.cache}: run tools/packgen/us_equities/fetch.py first")
    return SNAP.generate("us-equities", PACK, docs, files(docs), a.check, testdata)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
