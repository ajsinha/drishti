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

    python3 tools/packgen/us_equities/make.py [--cache DIR]           write data/testdata/us-equities/ and the pack's guide
    python3 tools/packgen/us_equities/make.py --check [--cache DIR]   fail if what would be written differs from the pack

With a cache, --check rebuilds every document from it. Without one (a fresh clone, CI), --check reads the committed snapshot back
(data/testdata/us-equities/) and rebuilds what is derived from it (the reference samples, the guide, the README), so it needs no network.

The documents follow Drishti's standard data pattern (tools/testdata/publicsnap.py): one gzipped JSON Lines snapshot per kind under
data/testdata/us-equities/<kind>/, listed in data/testdata/testdata.yaml, built into the lake by tools/build_demo_lakes.py and served by
the pack's Delta connector. samples/ keeps only a few reference documents for the README and docs; nothing is served from it.
The pack's manifest, Sutras, fragments and help are hand-written; this writes the snapshot, samples/ and guides/us-equities.md.
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
import guide_data as GD  # noqa: E402  (the guide's Data contracts and Data retrieval sections)
sys.path.insert(0, str(HERE))
import fetch as F  # noqa: E402
sys.path.insert(0, str(ROOT / "tools" / "testdata"))
import publicsnap as SNAP  # noqa: E402  (the standard data pattern: data/testdata/<pack>/<kind>/)

PACK = ROOT / "config" / "packs" / "us-equities"
DEFAULT_CACHE = ROOT / "data" / "cache" / "us-equities"
INDEX_NAMES = {"DJIA": "Dow Jones Industrial Average", "NDX": "Nasdaq-100", "SP500": "S&P 500", **{c: n for c, (n, _t) in F.LISTS.items()}}
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
    for f, key, into in ((ROOT / "config/packs/core/samples/legal-entity.jsonl", "LEI", leis),
                         (ROOT / "config/packs/news-and-markets/samples/listed-company.jsonl", "COMPANY_ID", cos)):
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
    # (since 2.9.2; docs/guides/GROUPINGS_ANALYTICS_GUIDE.md).

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
<!-- Generated by tools/packgen/us_equities/make.py. Edit the generator, not this file. -->
# The us-equities pack

Public data on the companies of the **Dow Jones Industrial Average, the Nasdaq-100 and the S&P 500**: {companies} companies with their
SEC filings, ten years of annual financials from their XBRL filings, their LEI, their SIC sector and the indexes they belong to.
Everything here is published data, not samples, retrieved on {retrieved}; prices are not included (see *Prices: internal use only*).

The pack is **off by default**: add it to the packs a server loads, `DRISHTI_PACKS=core,market-data,trading,counterparty-risk,market-risk,world-economy,news-and-markets,us-equities`
(it needs no other pack; with `world-economy` and `news-and-markets` on, its companies link to them).

![The company page: Apple Inc., with its latest filing opened beside the filings](images/us-equities-company.jpg)

## What you can open

| Type | Opens | Examples |
|---|---|---|
| `USEQ <ticker>` | a company: key facts, financials table and chart, recent filings (a click opens one beside), index memberships | `USEQ AAPL`, `USEQ JPM`, `USEQ BRK-B` |
| `IDX <index>` | an index's members, filterable; a click opens the member's summary beside | `IDX SP500`, `IDX NDX`, `IDX DJIA`, `IDX BANKS-US`, `IDX BANKS-CA`, `IDX BANKS-UK` |
| `SIC <code>` | a sector (Standard Industrial Classification), computed from the companies when asked: its companies by revenue, totals of the USD reporters | `SIC 3674`, `SIC 6021`; `SIC ALL` lists every code |
| `EDGAR <form>` | the filings browser: the latest filings, all or of one form; a click opens the filing beside | `EDGAR ALL`, `EDGAR 10-K`, `EDGAR 8-K`, `EDGAR 4`, `EDGAR DEF-14A` |
| `FILING <accession>` | one filing: form, dates, 8-K items and its EDGAR links | from a company's filings |
| `FACT <ticker>-<concept>` | one financial concept of a company over ten fiscal years | `FACT AAPL-REVENUE`, `FACT MSFT-NET-INCOME` |
| `PXH PX-<ticker>` | the price history, **live only, off as shipped** | `PXH PX-AAPL` |

The concepts are `REVENUE`, `NET-INCOME`, `ASSETS`, `LIABILITIES`, `EQUITY`, `EPS-DILUTED` and `SHARES-OUTSTANDING`.

## The data

| Kind | Documents | What one holds |
|---|---|---|
| `us-company` | {companies} | CIK, ticker, exchange and listing market, SIC with its description and division, state of incorporation, fiscal year end, LEI, headquarters, founded, website, index memberships, the latest year's figures, ten years of financials, the latest {filings_per} filings |
| `index-membership` | {indexes} | the members of the S&P 500, Nasdaq-100 and Dow as their tracking ETF (SPY, QQQ, DIA) reports holding them, and the bank lists, with their sector and latest revenue; members by sector |
| `sic-sector` | computed ({sectors} codes and `ALL`) | the companies of one SIC code ranked by revenue; `ALL` lists every code. Computed from the companies when asked by the derived connector `us-equities-groupings` (since 2.9.2): nothing is stored |
| `filing-feed` | {feeds} | the latest {feed_rows} filings of the companies, all forms or one |
| `filing` | {filing_count} | one filing: form, filing and report dates, document, 8-K items, links to the document and its EDGAR index |
| `financial-fact` | {fact_count} | one concept of one company: up to ten annual values with the change, the XBRL tag and the filing each came from |
| `price-history` | live only | the quote, a year of daily prices and the analytics computed from them when read (returns, volatility, drawdown, beta against the benchmark), from the price connector when a site switches it on; nothing is stored |

Members per index or list: {members}. The bank lists (BANKS-US, BANKS-CA, BANKS-UK) are kept by Drishti (`LISTS` in
`tools/packgen/us_equities/fetch.py`) and hold banks that file with the SEC. Annual values are those of the annual reports (10-K, or
a foreign filer's 20-F or 40-F, in the currency it reports in: `CURRENCY`; totals over many companies add USD reporters only): a flow (revenue, net income, EPS) for a period of a
year, a balance (assets, liabilities, equity) at the year end, the latest filing winning when a figure was restated. A fiscal year is the
calendar year its period ends in. Where a filer reports no total assets for a year, they are its total equity and liabilities; where it reports no
total liabilities line, it is derived as liabilities and equity less total
equity. Banks and insurers report revenue under several tags; where none of the standard ones is filed the revenue is left empty.

**Where the data lives.** The documents are committed as Drishti's standard test data: one gzipped JSON Lines snapshot per
kind, `data/testdata/us-equities/<kind>/snapshot-<date>.jsonl.gz`, listed in `data/testdata/testdata.yaml`. `python3 tools/drishti.py lake demo`
(or `python3 tools/build_demo_lakes.py`) builds them into the demo lake, the same documents on every business day it holds, and the
pack's Delta connector `us-equities-store` serves them. Nothing is served by the demo connector: a server with `DRISHTI_DEMO_ENABLED=false`
opens every page of the pack from the lake. The pack's `samples/` folder keeps only a few reference documents for this guide and the
README.

## Links

A company links to its filings, its financial facts, its SIC sector, its indexes, the United States economy (`world-economy`, for a
company with a US business address), its market watch page (`news-and-markets`, for the twenty companies there) and to a `core`
legal entity whose LEI is the company's ({core_matches} match today: core's entities are a fictional bank's). A filing and a fact link
back to their company; an index and a sector to their members. With the `gleif` pack on, the company's LEI (key facts, and F5)
opens its LEI record, and the record links back to the company.

## Data sources and licences

| Source | What | Licence |
|---|---|---|
| SEC EDGAR: `company_tickers_exchange.json`, `submissions/CIK##########.json`, `api/xbrl/companyfacts` | tickers, CIK, names, SIC, addresses, filings (metadata and links only), annual XBRL facts | Public domain (U.S. Government work). Fetched under the SEC's fair access policy: a descriptive User-Agent with a contact, under 10 requests a second |
| Nasdaq Trader symbol directory (`nasdaqlisted.txt`, `otherlisted.txt`) | the listing market | Public directory file |
| GLEIF LEI records | the LEI, legal name and registration status | CC0 1.0 |
| SEC Form N-PORT of SPY, QQQ and DIA (read from the funds pack's download cache) | index membership: the stocks each tracking ETF holds on its report date | Public domain (U.S. Government work) |
| Wikidata | headquarters, founded, website, LEI, the date a member joined its index | CC0 1.0 |

**Not included:** index weights and index levels (proprietary to S&P Dow Jones Indices and Nasdaq), GICS sectors (proprietary to MSCI and
S&P; the pack uses SIC, which the SEC publishes) and prices. Index membership is what the index's tracking ETF reported holding on
its N-PORT report date (each index page names the fund, the date and the filing), so it lags a change by up to a quarter; a company
since taken private or merged is listed in the index page's note, not as a member. Until 2.9.3 membership came from Wikidata, whose
lists were out of date (the S&P 500 lacked Exxon Mobil, BlackRock and about 80 others); the companies Wikidata still lists but no
tracking ETF holds stay in the pack, in no index.

## Prices: internal use only

!!! warning "Internal use only"
    Free price feeds forbid redistribution. The pack ships **no price data**: `price-history` is read live by the `us-equities-prices`
    connector (Yahoo Finance's public chart endpoint, the yfinance plugin), **off as shipped**. Switch it on only for internal
    demonstrations (`DRISHTI_US_EQUITIES_PRICES_ENABLED=true`, or Admin, Connectors); every ticker you open is then sent to Yahoo. Raise
    it before any client or external use, as for the IMF data of the world-economy pack.

## Refreshing the data

The pack's own refresh script fetches, builds and checks in one step (guide: `docs/guides/PUBLIC_DATA.md`, us-equities):

```bash
python3 tools/publicdata/us_equities.py status                 # licences, last refreshed, cached size
python3 tools/publicdata/us_equities.py refresh --dry-run      # what would be fetched; changes nothing
python3 tools/publicdata/us_equities.py refresh                # the network (about 1,100 SEC and 500 GLEIF calls), then make.py and --check
python3 tools/publicdata/us_equities.py refresh --scope TSLA,FILINGS:40,YEARS:15   # a wider build in the data folder, not this pack
```

`tools/publicdata/us-equities.sh` and `tools/windows/publicdata-us-equities.ps1` run the same script. Underneath it are the
generator's two steps, which can also be run by hand:

```bash
python3 tools/packgen/us_equities/fetch.py --cache data/cache/us-equities     # the network: about 1,100 SEC requests and 500 GLEIF lookups
python3 tools/packgen/us_equities/make.py --cache data/cache/us-equities      # writes data/testdata/us-equities/, samples/ and this guide
python3 tools/packgen/us_equities/make.py --check                              # no network: the pack matches its generator
```

`fetch.py` keeps what it downloaded (delete a file to fetch it again) and reduces each company's XBRL facts to the annual values of the
seven concepts, so the cache stays small. Prices are never part of a refresh into the pack: the script's `prices` source writes a
snapshot to the data folder only (internal use only). Set `DRISHTI_SEC_USER_AGENT` to your own name and contact before running it.

After a refresh, rebuild the lake so the server reads the new snapshot: `python3 tools/drishti.py lake demo` (or
`python3 tools/build_demo_lakes.py`), then restart the server or let it pick the tables up at its next refresh.

The picture
above is made by `tools/packgen/us_equities/shot.py` (its own scratch server and console on 18742 and 18743).
"""


def guide(docs: dict[str, dict[str, dict]]) -> str:
    cos = docs.get("us-company", {})
    any_doc = next(iter(cos.values()), {})
    idx = docs.get("index-membership", {})
    return GUIDE.format(companies=f"{len(cos):,}", retrieved=any_doc.get("PROVENANCE", {}).get("RETRIEVED_AT", ""), indexes=len(idx),
                        sectors=len({c["SIC"] for c in cos.values() if c.get("SIC")}), feeds=len(docs.get("filing-feed", {})), feed_rows=FEED_ROWS,
                        filing_count=f"{len(docs.get('filing', {})):,}", fact_count=f"{len(docs.get('financial-fact', {})):,}",
                        filings_per=max((len(c["FILINGS"]) for c in cos.values()), default=0),
                        members=", ".join(f"{d['NAME']} {d['MEMBERS']}" for d in idx.values()),
                        core_matches=sum(1 for c in cos.values() if c.get("LEGAL_ENTITY")))


def _rel(p: Path) -> str:
    return str(p.relative_to(ROOT)) if p.resolve().is_relative_to(ROOT) else str(p)


def files(docs: dict[str, dict[str, dict]]) -> dict[Path, str]:
    ref = SNAP.reference("us-equities", docs)                     # a few reference documents: the data itself is in data/testdata
    out = {PACK / "samples" / name: text for name, text in PS.files(ref, catalog(ref), catalog_indent=None).items()}
    out[PACK / "guides" / "us-equities.md"] = guide(docs).rstrip() + "\n\n" + "\n".join(GD.sections(PACK)).rstrip() + "\n"
    out[PACK / "README.md"] = README.render_pack(PACK, files=out, intro=HERE / "readme" / "us-equities.intro.md")
    return out


def main(argv: list[str]) -> int:
    p = argparse.ArgumentParser(description="Build the us-equities pack from the fetched cache (no network).")
    p.add_argument("--cache", type=Path, default=DEFAULT_CACHE)
    p.add_argument("--check", action="store_true")
    p.add_argument("--pack", type=Path, help="the pack folder to write or check (default config/packs/us-equities; "
                                               "tools/publicdata/us_equities.py points a widened build at the data folder)")
    p.add_argument("--testdata", type=Path, help="the test data folder the snapshot goes to (default data/testdata for the committed "
                                                  "pack; beside a widened build's pack folder otherwise)")
    a = p.parse_args(argv)
    global PACK
    PACK = a.pack.resolve() if a.pack else ROOT / "config" / "packs" / "us-equities"
    testdata = a.testdata.resolve() if a.testdata else SNAP.default_testdata(PACK, ROOT / "config" / "packs" / "us-equities")
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
