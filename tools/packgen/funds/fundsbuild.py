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

"""The funds pack's documents from the downloads (fundsread.py) and the committed us-equities snapshot (no network).

    choose(...)      the FUNDS largest funds and ETFs by net assets that have a ticker (N-PORT's newest report of each)
    build(cache)     {kind: {id: document}}: fund (one per fund, keyed by its ticker), fund-holder (per us-equities company: the
                     funds that hold it) and index-funds (per us-equities index: the funds that track it)

Every figure is as filed: values in US dollars as N-PORT reports them, a holding's weight in percent of the fund's net assets, flows
over the three months of the report, monthly returns of the share class, the expense ratio, turnover and calendar-year returns of
the newest prospectus risk/return summary. A fund keeps its TOP_N largest holdings (by value) and the totals of the rest; the
reverse look-through (fund-holder) counts every holding, not only those kept.
"""
from __future__ import annotations

import re
from collections import defaultdict
from pathlib import Path

import fundsread as R

FUNDS = 200          # the largest funds and ETFs by net assets
TOP_N = 600          # holdings kept a fund (every S&P 500 fund is whole); the rest as totals
DERIVATIVES_N = 50
HOLDERS_N = 250
ASSET = {"EC": "Common equity", "EP": "Preferred equity", "DBT": "Debt", "ABS-MBS": "Mortgage-backed", "ABS-ASBS": "Asset-backed",
         "ABS-CBDO": "CDO and CBO", "ABS-O": "Other asset-backed", "DCO": "Commodity derivative", "DCR": "Credit derivative",
         "DE": "Equity derivative", "DFE": "FX derivative", "DIR": "Interest-rate derivative", "DO": "Other derivative",
         "SN": "Structured note", "LON": "Loan", "RA": "Repo", "STIV": "Short-term investment vehicle", "RE": "Real estate",
         "COMM": "Commodity", "OTHER": "Other"}
ISSUER = {"CORP": "Corporate", "UST": "US Treasury", "USGA": "US government agency", "USGSE": "US government-sponsored entity",
          "MUN": "Municipal", "NUSS": "Non-US sovereign", "PF": "Private fund", "RF": "Registered fund", "OTHER": "Other"}
DERIV_FIELDS = ("NAME", "TITLE", "DERIVATIVE", "PAYOFF", "CURRENCY", "VALUE", "PCT", "CPTY", "CPTY_LEI", "NOTIONAL", "EXPIRY", "UNREALIZED",
                "REFERENCE")
DERIV = {"FWD":"Forward", "FUT": "Future", "SWP": "Swap", "OPT": "Option", "SWO": "Swaption", "WAR": "Warrant", "OTH": "Other"}
MIC = {"ARCX": "NYSE Arca", "XNYS": "NYSE", "XNAS": "Nasdaq", "XNMS": "Nasdaq", "XNGS": "Nasdaq", "BATS": "Cboe BZX", "XASE": "NYSE American",
       "XCBO": "Cboe", "CBOE": "Cboe", "EDGX": "Cboe EDGX"}
# A fund's tracked index, by its name (N-CEN says whether a fund is an index fund, not which index): the us-equities index codes.
TRACKS = [("NDX", re.compile(r"\bQQQ\b|NASDAQ[- ]?100", re.I)),
          ("DJIA", re.compile(r"DOW JONES INDUSTRIAL", re.I)),
          ("SP500", re.compile(r"S&P 500|\b500 INDEX\b|^VANGUARD INSTITUTIONAL INDEX FUND$", re.I))]
NOT_PLAIN = re.compile(r"EQUAL|GROWTH|VALUE|DIVIDEND|ESG|LOW VOL|MOMENTUM|QUALITY|HIGH|COVERED|BUFFER|HEDGED|\bEX[- ]|LEVERAG|INVERSE|"
                       r"MID|SMALL|ENHANCED|SECTOR|EXTENDED|COMPLETION|SCREENED|SUSTAIN|CLIMATE|PREMIUM|INCOME|PROTECT", re.I)
SOURCE = "U.S. Securities and Exchange Commission: Form N-PORT, Form N-CEN and prospectus risk/return summary data sets; mutual fund ticker file"
LICENCE = "Public domain (U.S. Government work); SEC fair access policy (descriptive User-Agent, at most 10 requests a second)"


def _r(x, n=4):
    return None if x is None else round(x, n)


def _plain(name: str) -> str:
    return re.sub(r"\((R|TM)\)", "", name or "").replace("  ", " ").strip()


def _title(name: str) -> str:
    """N-PORT names are often upper case (VANGUARD 500 INDEX FUND): title case, keeping S&P, ETF, MSCI and the like."""
    if not name or not name.isupper():
        return _plain(name)
    keep = {"S&P", "ETF", "MSCI", "EAFE", "FTSE", "US", "U.S.", "REIT", "ESG", "II", "III", "CRSP", "TIPS", "SPDR", "QQQ", "ESG", "AMT", "CDO"}
    small = {"OF", "AND", "THE", "FOR", "IN"}
    out = []
    for i, w in enumerate(_plain(name).split()):
        out.append(w if w in keep else (w.lower() if w in small and i else w.capitalize()))
    return " ".join(out)


def _ticker(t: str) -> str:
    """A holding's ticker as us-equities writes it: BRK/B, BRK.B and BRK B are BRK-B."""
    return re.sub(r"[./ ]", "-", (t or "").strip().upper())


def _month(report_date: str, back: int) -> str:
    y, m = int(report_date[:4]), int(report_date[5:7]) - back
    while m < 1:
        y, m = y - 1, m + 12
    return f"{y}-{m:02d}"


def choose(funds: dict[str, dict], series_tickers: dict[str, list[dict]], listed: dict[str, str], n: int | None = None) -> list[dict]:
    """The n largest funds by net assets with a ticker (a share class in the mutual-fund ticker file, or a listed trust's ticker)."""
    have = []
    for key, f in funds.items():
        if f["NET_ASSETS"] is None or f["SUB_TYPE"].startswith("NT"):
            continue
        if key.startswith("S") and series_tickers.get(key):
            have.append(f)
        elif key.startswith("CIK") and listed.get(key[3:]):
            have.append(f)
    have.sort(key=lambda f: (-f["NET_ASSETS"], f["KEY"]))
    return have[:n or FUNDS]


ETFS: dict[str, str] = {}      # ticker -> listing exchange (Nasdaq Trader), filled by build()


def _primary(f: dict, census: dict, classes: list[dict], listed: dict[str, str]) -> str:
    """The fund's ticker: its ETF ticker (N-CEN's exchange listing, else a class N-CEN or the ticker file names so), a trust's listed
    ticker, else its oldest share class."""
    if f["KEY"].startswith("CIK"):
        return listed[f["KEY"][3:]]
    tickers = [c["TICKER"] for c in classes]
    for e in census.get("EXCHANGES") or []:
        if e["TICKER"] in tickers:
            return e["TICKER"]
    for c in sorted(classes, key=lambda c: c["CLASS_ID"]):
        if c["TICKER"] in ETFS:
            return c["TICKER"]
    return sorted(classes, key=lambda c: c["CLASS_ID"])[0]["TICKER"]


def _tracks(name: str, is_index: bool) -> str:
    plain = _plain(name)
    for code, rx in TRACKS:
        if rx.search(plain) and not NOT_PLAIN.search(plain.replace("S&P 500", "")):
            return code if is_index or code != "SP500" or "INDEX" in plain.upper() or "ETF" in plain.upper() else ""
    return ""


def _companies(us: dict) -> tuple[dict[str, dict], dict[str, str]]:
    by_ticker = {t: c for t, c in us.items()}
    by_lei = {}
    for t, c in us.items():
        if c.get("LEI") and c["LEI"] not in by_lei:
            by_lei[c["LEI"]] = t
    return by_ticker, by_lei


_SUFFIX = re.compile(r"\b(INC|INCORPORATED|CORP|CORPORATION|CO|COMPANY|PLC|LTD|LIMITED|NV|SA|AG|LLC|LP|HOLDINGS?|GROUP|THE|CLASS [A-Z]|CL [A-Z]|/[A-Z]{2})\b")


def _norm(name: str) -> str:
    """A company name to match on: upper case, no punctuation, no legal suffix (Exxon Mobil Corp and EXXON MOBIL CORPORATION agree)."""
    n = re.sub(r"[^A-Z0-9/ ]", " ", (name or "").upper().replace("&", " AND "))
    return " ".join(_SUFFIX.sub(" ", n).split())


def _names(us: dict) -> dict[str, str]:
    """{normalised name: ticker} of the us-equities companies whose name is unique."""
    seen: dict[str, set] = defaultdict(set)
    for t, c in us.items():
        for n in (c.get("NAME"), c.get("SEC_NAME"), c.get("LEI_LEGAL_NAME")):
            if _norm(n):
                seen[_norm(n)].add(t)
    return {n: next(iter(ts)) for n, ts in seen.items() if len(ts) == 1}


def _cusips(holdings: dict[str, list[dict]], companies: dict) -> dict[str, str]:
    """{CUSIP: ticker} of the equities some fund filed with a ticker (iShares and SPDR do, Vanguard does not): the others match by it."""
    out: dict[str, str] = {}
    for rows in holdings.values():
        for h in rows:
            t, c = _ticker(h.get("TICKER", "")), h["ISSUER_CUSIP"].strip()
            if t in companies and h["ASSET_CAT"] == "EC" and len(c) == 9 and c != "000000000":
                out.setdefault(c, t)
    return out


def _holding(h: dict, companies: dict, by: dict, fund_of: dict[str, str]) -> dict:
    """One holding with its us-equities company: by ticker, else (an equity) by CUSIP, LEI or name, in that order."""
    t = _ticker(h.get("TICKER", ""))
    company = t if t in companies else ""
    if not company and h["ASSET_CAT"] in ("EC", "EP"):
        company = (by["cusip"].get(h["ISSUER_CUSIP"].strip()) if h["ASSET_CAT"] == "EC" else None) \
            or by["lei"].get(h["ISSUER_LEI"].strip()) or by["name"].get(_norm(h["ISSUER_NAME"])) or ""
    value, pct = R.num(h["CURRENCY_VALUE"]), R.num(h["PERCENTAGE"])
    name = R.clean(h["ISSUER_NAME"])
    out = {"NAME": name if name not in ("", "N/A") else R.clean(h["ISSUER_TITLE"]), "TITLE": R.clean(h["ISSUER_TITLE"]), "TICKER": t,
           "CUSIP": h["ISSUER_CUSIP"].strip() if h["ISSUER_CUSIP"].strip() not in ("N/A", "000000000") else "",
           "ISIN": h.get("ISIN", ""), "LEI": h["ISSUER_LEI"].strip() if h["ISSUER_LEI"].strip() != "N/A" else "",
           "ASSET": h["ASSET_CAT"] or ("OTHER" if h["OTHER_ASSET"] else ""), "ISSUER_TYPE": h["ISSUER_TYPE"],
           "COUNTRY": h["INVESTMENT_COUNTRY"], "CURRENCY": h["CURRENCY_CODE"], "VALUE": _r(value, 2), "PCT": _r(pct, 6),
           "BALANCE": _r(R.num(h["BALANCE"]), 4), "UNIT": h["UNIT"], "PAYOFF": h["PAYOFF_PROFILE"], "DERIVATIVE": h["DERIVATIVE_CAT"],
           "COMPANY": company, "FUND": fund_of.get(t, ""), "SECTOR": companies[company].get("SECTOR", "") if company else ""}
    for k in ("CPTY", "CPTY_LEI", "NOTIONAL", "EXPIRY", "UNREALIZED", "REFERENCE"):
        if h.get(k) not in (None, ""):
            out[k] = h[k]
    return out


def _compact(row: dict) -> dict:
    return {k: v for k, v in row.items() if v not in (None, "", [])}


def _shares(h: dict) -> float | None:
    return h["BALANCE"] if h.get("UNIT") == "NS" and h.get("BALANCE") else None


def _group(rows: list[dict], key: str, label=None, top: int = 12) -> list[dict]:
    acc: dict[str, list] = defaultdict(lambda: [0.0, 0.0, 0])
    for h in rows:
        k = h.get(key) or "Other"
        a = acc[k]
        a[0] += h.get("PCT") or 0
        a[1] += h.get("VALUE") or 0
        a[2] += 1
    out = [{"KEY": k, "LABEL": (label or {}).get(k, k), "PCT": _r(v[0], 3), "VALUE": _r(v[1], 0), "COUNT": v[2]} for k, v in acc.items()]
    out.sort(key=lambda r: -(r["PCT"] or 0))
    if len(out) > top:
        rest = out[top - 1:]
        out = out[:top - 1] + [{"KEY": "OTHER", "LABEL": f"Other ({len(rest)})", "PCT": _r(sum(r["PCT"] or 0 for r in rest), 3),
                                "VALUE": _r(sum(r["VALUE"] or 0 for r in rest), 0), "COUNT": sum(r["COUNT"] for r in rest)}]
    return out


def build(cache: Path, us: dict[str, dict], indexes: dict[str, dict], retrieved: str, log=print) -> dict[str, dict[str, dict]]:
    """Every document of the pack. ``us``: the us-equities companies by ticker; ``indexes``: its index-membership documents."""
    import zipfile
    series_tickers, listed = R.tickers(cache)
    ETFS.clear()
    ETFS.update(R.etfs(cache))
    nport_zip = R.newest(cache / "nport", "_nport.zip")
    if nport_zip is None:
        raise SystemExit(f"no N-PORT data set in {cache / 'nport'}: run tools/publicdata/funds.py refresh first")
    log(f"  N-PORT {nport_zip.name}")
    with zipfile.ZipFile(nport_zip) as zf:
        funds = R.nport_funds(zf)
        chosen = choose(funds, series_tickers, listed)
        accs = {f["ACCESSION"] for f in chosen}
        returns = R.nport_returns(zf, accs)
        holdings = R.nport_holdings(zf, accs, log)
    keys = {f["KEY"] for f in chosen}
    census = R.ncen(R.quarters(cache / "ncen", "_ncen.zip"), {k for k in keys if k.startswith("S")}, {k for k in keys if k.startswith("CIK")})
    log(f"  N-CEN: {len(census)} of {len(chosen)} funds found in {len(R.quarters(cache / 'ncen', '_ncen.zip'))} quarters")
    class_ids = {c["CLASS_ID"] for k in keys for c in series_tickers.get(k, [])}
    rr = R.risk_return(R.quarters(cache / "rr", "_rr1.zip"), class_ids, {k for k in keys if k.startswith("S")})
    log(f"  risk/return summaries: {len(rr)} classes or series")
    gleif_rr = cache / "gleif" / "rr.csv.zip"
    if not gleif_rr.is_file():
        alt = cache.parent / "gleif" / "golden" / "rr.csv.zip"
        gleif_rr = alt if alt.is_file() else None
    leis = {f["SERIES_LEI"] for f in chosen if f["SERIES_LEI"]}
    relations = R.gleif_funds(gleif_rr, leis)
    companies, by_lei = _companies(us)
    by = {"lei": by_lei, "cusip": _cusips(holdings, companies), "name": _names(us)}

    # the funds' tickers first: a holding that is one of these funds links to it
    meta = {}
    fund_of: dict[str, str] = {}
    for f in chosen:
        cen = census.get(f["KEY"], {})
        classes = series_tickers.get(f["KEY"], [])
        t = _primary(f, cen, classes, listed)
        meta[f["KEY"]] = (t, cen, classes)
        for c in classes:
            fund_of.setdefault(c["TICKER"], t)
        fund_of.setdefault(t, t)

    docs_fund: dict[str, dict] = {}
    all_rows: dict[str, list[dict]] = {}
    for rank, f in enumerate(chosen, 1):
        t, cen, classes = meta[f["KEY"]]
        rows = [_holding(h, companies, by, fund_of) for h in holdings.get(f["ACCESSION"], [])]
        rows = [r for r in rows if r["FUND"] != t]               # never itself
        rows.sort(key=lambda r: -(r["VALUE"] or 0))
        all_rows[t] = rows
        docs_fund[t] = _fund(f, rank, t, cen, classes, rows, returns.get(f["ACCESSION"], {}), rr, relations, companies, retrieved)
    holders = _holders(docs_fund, all_rows, companies, retrieved)
    for t, d in docs_fund.items():
        top = next((h["COMPANY"] for h in d["HOLDINGS"] if h.get("COMPANY")), "")
        d["MOST_HELD_COMPANY"] = top
        d["MOST_HELD_NAME"] = companies[top]["NAME"] if top else ""
        d["ALSO_HELD_BY"] = [r for r in (holders.get(top, {}).get("HOLDERS") or []) if r["FUND"] != t][:10] if top else []
        for h in d["HOLDINGS"]:
            if h.get("COMPANY") in holders:
                h["ALSO_IN"] = holders[h["COMPANY"]]["FUNDS"] - 1
    _vs_index(docs_fund, all_rows, companies, indexes, holders)
    tracking = _index_funds(docs_fund, indexes, retrieved)
    for t, d in docs_fund.items():
        d["HOLDINGS"] = [_compact(h) for h in d["HOLDINGS"]]
    log(f"  {len(docs_fund)} funds, {sum(len(d['HOLDINGS']) for d in docs_fund.values()):,} holdings kept "
        f"(of {sum(len(v) for v in all_rows.values()):,}), {len(holders)} companies held, {len(tracking)} indexes tracked")
    return {"fund": docs_fund, "fund-holder": holders, "index-funds": tracking}


def _prov(retrieved: str, what: str, note: str = "") -> dict:
    p = {"SOURCE": SOURCE if not what else what, "LICENCE": LICENCE, "RETRIEVED_AT": retrieved}
    if note:
        p["NOTE"] = note
    return p


def _fund(f, rank, t, cen, classes, rows, rets, rr, relations, companies, retrieved) -> dict:
    names = {c["CLASS_ID"]: c for c in cen.get("CLASSES") or []}
    cls_rows = []
    for c in sorted(classes, key=lambda c: c["CLASS_ID"]):
        s = rr.get(c["CLASS_ID"], {})
        m = rets.get(c["CLASS_ID"]) or [None, None, None]
        cls_rows.append(_compact({"CLASS_ID": c["CLASS_ID"], "TICKER": c["TICKER"], "CLASS_NAME": names.get(c["CLASS_ID"], {}).get("CLASS_NAME", ""),
                                  "EXPENSE_RATIO": s.get("EXPENSE_RATIO", s.get("GROSS_EXPENSE_RATIO")), "RETURN_3M": _compound(m)}))
    primary_cls = next((c["CLASS_ID"] for c in classes if c["TICKER"] == t), "")
    s_cls, s_ser = rr.get(primary_cls, {}), rr.get(f["KEY"], {})
    m = rets.get(primary_cls) or (next(iter(rets.values())) if rets else [None, None, None])
    annual = s_cls.get("ANNUAL_RETURNS") or s_ser.get("ANNUAL_RETURNS") or {}
    etf_classes = {e["TICKER"] for e in cen.get("EXCHANGES") or []}
    is_etf = bool(cen.get("IS_ETF")) or bool(etf_classes) or "ETF" in f["NAME"].upper() or t in ETFS
    if f["KEY"].startswith("CIK"):
        kind = "ETF (unit investment trust)"
    elif is_etf and len(classes) > 1:
        kind = "Mutual fund with an ETF share class"
    elif is_etf:
        kind = "ETF"
    else:
        kind = "Mutual fund"
    name = _title(f["NAME"])
    index = _tracks(f["NAME"], bool(cen.get("IS_INDEX")) or f["KEY"].startswith("CIK"))
    net = f["NET_ASSETS"] or 0
    pcts = [h["PCT"] or 0 for h in rows]
    equity = [h for h in rows if h["ASSET"] == "EC"]
    deriv = [h for h in rows if h["DERIVATIVE"]]
    kept = rows[:TOP_N]
    rest = rows[TOP_N:]
    flows = []
    for i, fl in enumerate(f["FLOWS"]):
        sales, red, rein = fl["SALES"], fl["REDEMPTIONS"], fl["REINVESTMENT"]
        flows.append({"MONTH": _month(f["REPORT_DATE"], 2 - i), "SALES": sales, "REINVESTMENT": rein, "REDEMPTIONS": red,
                      "NET_FLOW": None if sales is None or red is None else _r(sales + (rein or 0) - red, 2),
                      "RETURN": m[i] if i < len(m) else None})
    cps: dict[str, dict] = {}
    for h in rows:
        if h.get("CPTY"):
            c = cps.setdefault(h["CPTY"], {"NAME": h["CPTY"], "LEI": h.get("CPTY_LEI", ""), "POSITIONS": 0, "NOTIONAL": 0.0, "VALUE": 0.0})
            c["POSITIONS"] += 1
            c["NOTIONAL"] += abs(h.get("NOTIONAL") or 0)
            c["VALUE"] += h.get("VALUE") or 0
    cp_rows = sorted(({**c, "NOTIONAL": _r(c["NOTIONAL"], 0), "VALUE": _r(c["VALUE"], 0)} for c in cps.values()),
                     key=lambda c: (-c["NOTIONAL"], -abs(c["VALUE"])))[:25]
    deriv_rows = sorted(deriv, key=lambda h: -abs(h.get("NOTIONAL") or h.get("VALUE") or 0))[:DERIVATIVES_N]
    sales = sum(x["SALES"] or 0 for x in flows)
    reds = sum(x["REDEMPTIONS"] or 0 for x in flows)
    rein = sum(x["REINVESTMENT"] or 0 for x in flows)
    hhi = sum((p / 100) ** 2 for p in pcts if p > 0)
    advisers = cen.get("ADVISERS") or []
    rel = [{"TYPE": r["TYPE"], "LEI": r["LEI"], "STATUS": r["STATUS"]} for r in relations.get(f["SERIES_LEI"], [])]
    exch = ETFS.get(t) or next((MIC.get(e["MIC"], e["MIC"]) for e in cen.get("EXCHANGES") or [] if e["TICKER"] == t), "")
    years = sorted(annual)
    d = {
        "TICKER": t, "NAME": name, "TYPE": kind, "FUND_KEY": f["KEY"], "SERIES_ID": f["SERIES_ID"], "CIK": f["CIK"],
        "REGISTRANT": _title(f["REGISTRANT"]), "LEI": f["SERIES_LEI"], "REGISTRANT_LEI": f["REGISTRANT_LEI"],
        "TICKERS": " ".join([t] + [c["TICKER"] for c in sorted(classes, key=lambda c: c["CLASS_ID"]) if c["TICKER"] != t]),
        "CLASSES": cls_rows, "CLASS_COUNT": len(classes) or 1, "PRIMARY_CLASS": primary_cls,
        "IS_ETF": is_etf, "IS_INDEX": bool(cen.get("IS_INDEX")) or bool(index), "IS_FUND_OF_FUNDS": bool(cen.get("IS_FUND_OF_FUNDS")),
        "IS_TARGET_DATE": bool(cen.get("IS_TARGET_DATE")), "IS_MASTER_FEEDER": bool(cen.get("IS_MASTER_FEEDER")),
        "INDEX_ID": index, "EXCHANGE": exch, "CREATION_UNIT": cen.get("CREATION_UNIT"), "AP_COUNT": len(cen.get("APS") or []) or None,
        "AUTHORISED_PARTICIPANTS": sorted(cen.get("APS") or [], key=lambda a: -((a["PURCHASES"] or 0) + (a["REDEMPTIONS"] or 0)))[:10],
        "ADVISER": advisers[0]["NAME"] if advisers else "", "ADVISER_LEI": advisers[0]["LEI"] if advisers else "",
        "SUB_ADVISERS": ", ".join(a["NAME"] for a in (cen.get("SUB_ADVISERS") or [])[:6]),
        "CUSTODIAN": cen.get("CUSTODIAN", ""), "CUSTODIAN_LEI": cen.get("CUSTODIAN_LEI", ""), "ADMINISTRATOR": cen.get("ADMINISTRATOR", ""),
        "TRANSFER_AGENT": cen.get("TRANSFER_AGENT", ""), "AUDITOR": cen.get("AUDITOR", ""), "AUDITOR_LEI": cen.get("AUDITOR_LEI", ""),
        "LENDS_SECURITIES": bool(cen.get("LENDS_SECURITIES")), "NCEN_PERIOD": cen.get("NCEN_PERIOD", ""), "NCEN_ACCESSION": cen.get("NCEN_ACCESSION", ""),
        "GLEIF_RELATIONSHIPS": rel,
        "RANK": rank, "REPORT_DATE": f["REPORT_DATE"], "FILED": f["FILED"], "ACCESSION": f["ACCESSION"],
        "NPORT_URL": f"https://www.sec.gov/Archives/edgar/data/{int(f['CIK'])}/{f['ACCESSION'].replace('-', '')}/",
        "TOTAL_ASSETS": _r(f["TOTAL_ASSETS"], 0), "NET_ASSETS": _r(net, 0),
        "EXPENSE_RATIO": s_cls.get("EXPENSE_RATIO", s_cls.get("GROSS_EXPENSE_RATIO")),
        "GROSS_EXPENSE_RATIO": s_cls.get("GROSS_EXPENSE_RATIO"), "MANAGEMENT_FEE": s_cls.get("MANAGEMENT_FEE"),
        "TURNOVER": s_ser.get("TURNOVER", s_cls.get("TURNOVER")), "PROSPECTUS_FILED": s_cls.get("FILED") or s_ser.get("FILED") or "",
        "ANNUAL_RETURNS": [{"YEAR": y, "RETURN": annual[y]} for y in years[-10:]],
        "RETURN_LAST_YEAR": annual[years[-1]] if years else None, "RETURN_YEAR": int(years[-1]) if years else None,
        "RETURN_3M": _compound(m), "FLOWS": flows, "SALES": _r(sales, 2), "REDEMPTIONS": _r(reds, 2), "REINVESTMENT": _r(rein, 2),
        "NET_FLOW": _r(sales + rein - reds, 2),
        "HOLDING_COUNT": len(rows), "HOLDINGS_SHOWN": len(kept), "HOLDINGS_REST_COUNT": len(rest),
        "HOLDINGS_REST_PCT": _r(sum(h["PCT"] or 0 for h in rest), 4), "HOLDINGS_REST_VALUE": _r(sum(h["VALUE"] or 0 for h in rest), 0),
        "TOP10_PCT": _r(sum(pcts[:10]), 3), "TOP50_PCT": _r(sum(pcts[:50]), 3), "HHI": _r(hhi, 5),
        "LARGEST": rows[0]["NAME"] if rows else "", "LARGEST_PCT": rows[0]["PCT"] if rows else None,
        "WITH_LEI": sum(1 for h in rows if h["LEI"]), "EQUITY_PCT": _r(sum(h["PCT"] or 0 for h in equity), 3),
        "US_COMPANY_PCT": _r(sum(h["PCT"] or 0 for h in rows if h["COMPANY"]), 3), "US_COMPANIES": len({h["COMPANY"] for h in rows if h["COMPANY"]}),
        "BY_ASSET": _group(rows, "ASSET", ASSET), "BY_ISSUER_TYPE": _group(rows, "ISSUER_TYPE", ISSUER),
        "BY_COUNTRY": _group(rows, "COUNTRY", top=10), "BY_SECTOR": _group([h for h in rows if h["COMPANY"]], "SECTOR"),
        "DERIVATIVE_COUNT": len(deriv), "DERIVATIVES_NOTIONAL": _r(sum(abs(h.get("NOTIONAL") or 0) for h in deriv), 0),
        "DERIVATIVES": [_compact({**{k: h.get(k) for k in DERIV_FIELDS}, "TYPE": DERIV.get(h["DERIVATIVE"], h["DERIVATIVE"])})
                        for h in deriv_rows],
        "COUNTERPARTIES": cp_rows, "COUNTERPARTY_COUNT": len(cps),
        "HOLDINGS": kept,
        "VIEWS": [{"VIEW": "LOOK_THROUGH", "TITLE": "Look-through holdings"}],
        "PROVENANCE": _prov(retrieved, "", "Holdings, flows and returns as the fund filed them on Form N-PORT for "
                            + f["REPORT_DATE"] + "; census facts from its newest Form N-CEN; fees and calendar-year returns from its newest "
                            "prospectus risk/return summary. No prices or NAV history."),
        "_meta": {"source": "sec-nport", "dataset": "funds", "retrievedAt": retrieved, "generation": 1},
    }
    return d


def _compound(m: list) -> float | None:
    vals = [x for x in (m or []) if x is not None]
    if len(vals) < 3:
        return None
    g = 1.0
    for x in vals:
        g *= 1 + x / 100
    return round((g - 1) * 100, 2)


def _holders(docs: dict, all_rows: dict, companies: dict, retrieved: str) -> dict[str, dict]:
    """Per us-equities company: every chosen fund that holds it (all its holdings, not only those kept), by value."""
    by: dict[str, list[dict]] = defaultdict(list)
    for t, rows in all_rows.items():
        d = docs[t]
        per: dict[str, dict] = {}
        for h in rows:
            c = h["COMPANY"]
            if not c or h["ASSET"] not in ("EC", "EP"):
                continue
            p = per.setdefault(c, {"FUND": t, "FUND_NAME": d["NAME"], "TYPE": d["TYPE"], "PCT": 0.0, "VALUE": 0.0, "SHARES": 0.0,
                                   "REPORT_DATE": d["REPORT_DATE"], "NET_ASSETS": d["NET_ASSETS"]})
            p["PCT"] += h["PCT"] or 0
            p["VALUE"] += h["VALUE"] or 0
            p["SHARES"] += _shares(h) or 0
        for c, p in per.items():
            by[c].append(p)
    out = {}
    for c, lst in sorted(by.items()):
        lst.sort(key=lambda p: (-p["VALUE"], p["FUND"]))
        co = companies[c]
        shares = sum(p["SHARES"] for p in lst)
        so = co.get("SHARES_OUTSTANDING")
        rows = [{**p, "PCT": _r(p["PCT"], 4), "VALUE": _r(p["VALUE"], 0), "SHARES": _r(p["SHARES"], 0) or None} for p in lst[:HOLDERS_N]]
        out[c] = {"TICKER": c, "NAME": co.get("NAME", ""), "COMPANY": c, "SECTOR": co.get("SECTOR", ""), "FUNDS": len(lst),
                  "ETFS": sum(1 for p in lst if p["TYPE"].startswith("ETF")), "TOTAL_VALUE": _r(sum(p["VALUE"] for p in lst), 0),
                  "TOTAL_SHARES": _r(shares, 0), "SHARES_OUTSTANDING": so,
                  "PCT_OF_SHARES": _r(shares / so * 100, 3) if so and shares and 0.2 <= shares / so * 100 <= 80 else None,
                  "AVG_WEIGHT": _r(sum(p["PCT"] for p in lst) / len(lst), 4), "MAX_WEIGHT": _r(max(p["PCT"] for p in lst), 4),
                  "TOP_HOLDER": lst[0]["FUND"], "HOLDERS": rows,
                  "TOP_WEIGHTS": [{"FUND": p["FUND"], "PCT": p["PCT"]} for p in sorted(rows, key=lambda p: (-p["PCT"], p["FUND"]))[:10]],
                  "PROVENANCE": _prov(retrieved, "", "Every holding of the pack's funds in this company (Form N-PORT), each fund as of its own report date."),
                  "_meta": {"source": "sec-nport", "dataset": "funds", "retrievedAt": retrieved, "generation": 1}}
    return out


def _prices(all_rows: dict) -> dict[str, float]:
    """A price per us-equities company implied by the filings: value over shares of its holdings, the median across the funds."""
    seen: dict[str, list[float]] = defaultdict(list)
    for rows in all_rows.values():
        for h in rows:
            s = _shares(h)
            if h["COMPANY"] and h["ASSET"] == "EC" and s and h["VALUE"] and h["VALUE"] > 0:
                seen[h["COMPANY"]].append(h["VALUE"] / s)
    out = {}
    for c, ps in seen.items():
        ps.sort()
        out[c] = ps[len(ps) // 2]
    return out


PRICE_WEIGHTED = {"DJIA"}       # an index weighted by share price, not market capitalisation
CAPPED = {"NDX"}                # a modified market capitalisation: the largest members' weights capped (_capped)


def _capped(w: dict[str, float]) -> dict[str, float]:
    """The Nasdaq-100's quarterly rebalance (its published methodology): no member over 24% (cut to 20%), and when the members over
    4.5% weigh more than 48% together, they are scaled down to 40%; what is cut goes to the others pro rata."""
    w = dict(w)
    for _ in range(20):
        over = {m: v for m, v in w.items() if v > 24}
        big = {m: v for m, v in w.items() if v > 4.5}
        if over:
            fix = {m: 20.0 for m in over}
        elif sum(big.values()) > 48:
            k = 40 / sum(big.values())
            fix = {m: v * k for m, v in big.items()}
        else:
            return w
        rest = {m: v for m, v in w.items() if m not in fix}
        spare = 100 - sum(fix.values())
        total = sum(rest.values()) or 1
        w = {**fix, **{m: v / total * spare for m, v in rest.items()}}
    return w


def _vs_index(docs: dict, all_rows: dict, companies: dict, indexes: dict, holders: dict) -> None:
    """Each fund against its tracked index (or, for a US equity fund that tracks none of them, the S&P 500 for reference): the index
    weights are computed from SEC data, market capitalisation as shares outstanding (10-K cover, dei) times the price the filings imply
    (value over shares across the funds), or equal weights where too few members can be priced; the Dow by share price, its own method.
    The Nasdaq-100 caps its largest members' weights (a modified market capitalisation), which plain capitalisation does not."""
    prices = _prices(all_rows)
    weights: dict[str, tuple[dict[str, float], str, list[str], list[str]]] = {}
    for code, ix in indexes.items():
        members = sorted({r["TICKER"] for r in ix.get("ROWS") or [] if r.get("TICKER") in companies})
        caps, left_out = {}, []
        for m in members:
            cap = (companies[m].get("SHARES_OUTSTANDING") or 0) * prices.get(m, 0)
            held = (holders.get(m) or {}).get("TOTAL_VALUE") or 0
            # a share count the filings cannot support (none filed, a scale error, one class of several): the funds' holdings would be
            # under 0.2% or over 80% of the company, which no member of these indexes is
            if cap > 0 and 0.002 <= held / cap <= 0.8:
                caps[m] = cap
            else:
                left_out.append(m)
        priced = {m: prices[m] for m in members if prices.get(m)}
        if code in PRICE_WEIGHTED and members and len(priced) == len(members):
            total = sum(priced.values())               # the Dow weighs its members by share price (its divisor cancels out)
            weights[code] = ({m: p / total * 100 for m, p in priced.items()}, "share price (the index's own method)", members, [])
        elif members and len(caps) >= 0.7 * len(members):
            total = sum(caps.values())
            w = {m: c / total * 100 for m, c in caps.items()}
            how = "market capitalisation computed from SEC data"
            if code in CAPPED:
                w, how = _capped(w), how + ", capped as the Nasdaq-100 methodology caps it"
            weights[code] = (w, how, members, left_out)
        elif members:
            weights[code] = ({m: 100 / len(members) for m in members}, "equal weights", members, [])
    for t, d in docs.items():
        code, basis = d["INDEX_ID"], "tracks"
        if not code and (d["EQUITY_PCT"] or 0) >= 60:
            code, basis = "SP500", "reference"
        if code not in weights or (d["US_COMPANY_PCT"] or 0) < 40:
            continue                                   # a feeder or fund of funds holds funds, not the members
        w_ix, how, listed, left_out = weights[code]
        rows = all_rows[t]
        raw: dict[str, float] = defaultdict(float)
        for h in rows:
            if h["COMPANY"] and h["ASSET"] == "EC":
                raw[h["COMPANY"]] += h["PCT"] or 0
        in_list = sum(v for n, v in raw.items() if n in set(listed))
        base = sum(v for n, v in raw.items() if n in w_ix) or 1
        w_f = {n: v / base * 100 for n, v in raw.items() if n in w_ix}       # the fund over the weighted members, to 100
        names = set(w_f) | set(w_ix)
        diff = {n: w_f.get(n, 0) - w_ix.get(n, 0) for n in names}
        active = sum(abs(v) for v in diff.values()) / 2
        cmp_rows = sorted(names, key=lambda n: -max(w_f.get(n, 0), w_ix.get(n, 0)))[:25]
        sect_f, sect_i = defaultdict(float), defaultdict(float)
        for n, v in w_f.items():
            sect_f[companies[n].get("SECTOR") or "Unclassified"] += v
        for n, v in w_ix.items():
            sect_i[companies[n].get("SECTOR") or "Unclassified"] += v
        sectors = sorted(set(sect_f) | set(sect_i), key=lambda s: -sect_i.get(s, 0))
        big = max(diff, key=lambda n: abs(diff[n])) if diff else ""
        held = sum(1 for n in listed if raw.get(n, 0) > 0)
        d["VS_INDEX"] = {
            "INDEX_ID": code, "INDEX_NAME": indexes[code]["NAME"], "BASIS": basis, "WEIGHTING": how,
            "MEMBERS": len(listed), "MEMBERS_WEIGHTED": len(w_ix), "MEMBERS_NOT_WEIGHTED": ", ".join(left_out),
            "MEMBERS_HELD": held, "FUND_IN_INDEX_PCT": _r(in_list, 2), "NOT_IN_INDEX_PCT": _r(100 - in_list, 2), "ACTIVE_SHARE": _r(active, 2),
            "LARGEST_DIFF_COMPANY": big, "LARGEST_DIFF_NAME": companies[big]["NAME"] if big else "", "LARGEST_DIFF": _r(diff.get(big), 3),
            "AS_OF": d["REPORT_DATE"],
            "ROWS": [{"COMPANY": n, "NAME": companies[n]["NAME"], "SECTOR": companies[n].get("SECTOR", ""), "FUND_PCT": _r(w_f.get(n, 0), 3),
                      "INDEX_PCT": _r(w_ix.get(n, 0), 3), "DIFF": _r(diff[n], 3)} for n in cmp_rows],
            "SECTORS": [{"SECTOR": s, "FUND_PCT": _r(sect_f.get(s, 0), 2), "INDEX_PCT": _r(sect_i.get(s, 0), 2),
                         "TILT": _r(sect_f.get(s, 0) - sect_i.get(s, 0), 2)} for s in sectors]}
        title = ("vs " if basis == "tracks" else "vs the ") + indexes[code]["NAME"] + ("" if basis == "tracks" else " (reference)")
        d["VIEWS"].append({"VIEW": "VS_INDEX", "TITLE": title})
        d["ACTIVE_SHARE"] = d["VS_INDEX"]["ACTIVE_SHARE"]


def _index_funds(docs: dict, indexes: dict, retrieved: str) -> dict[str, dict]:
    by: dict[str, list[dict]] = defaultdict(list)
    for t, d in docs.items():
        v = d.get("VS_INDEX") or {}
        if d["INDEX_ID"] and v.get("BASIS") == "tracks":
            by[d["INDEX_ID"]].append({"FUND": t, "NAME": d["NAME"], "TYPE": d["TYPE"], "NET_ASSETS": d["NET_ASSETS"],
                                      "EXPENSE_RATIO": d["EXPENSE_RATIO"], "MEMBERS_HELD": v.get("MEMBERS_HELD"),
                                      "ACTIVE_SHARE": v.get("ACTIVE_SHARE"), "REPORT_DATE": d["REPORT_DATE"], "IS_ETF": d["IS_ETF"]})
    out = {}
    for code, rows in sorted(by.items()):
        rows.sort(key=lambda r: -(r["NET_ASSETS"] or 0))
        out[code] = {"INDEX": code, "NAME": indexes[code]["NAME"], "FUNDS": len(rows), "ETFS": sum(1 for r in rows if r["IS_ETF"]),
                     "TOTAL_NET_ASSETS": _r(sum(r["NET_ASSETS"] or 0 for r in rows), 0), "LARGEST": rows[0]["FUND"], "ROWS": rows,
                     "PROVENANCE": _prov(retrieved, "", "The pack's funds whose name names this index (N-CEN says whether a fund is an index "
                                         "fund, not which index); index weights are not public."),
                     "_meta": {"source": "sec-nport", "dataset": "funds", "retrievedAt": retrieved, "generation": 1}}
    return out


def size_mb(docs: dict) -> float:
    import json
    return sum(len(json.dumps(d, separators=(",", ":"))) for k in docs.values() for d in k.values()) / 1e6

