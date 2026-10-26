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

"""Readers of the funds pack's downloads (no network): the SEC's quarterly Form N-PORT, Form N-CEN and prospectus risk/return summary
data sets (ZIP files of tab-delimited tables), the SEC's ticker files and GLEIF's level-2 relationship records. Each reader streams
its tables once and keeps only what the build needs, so the ~1.6 GB of N-PORT tables never sit in memory.

    the cache (tools/publicdata/funds.py fills it; <data>/publicdata/cache/funds by default)
      sec/company_tickers_mf.json          fund and ETF share classes: CIK, series id, class id, ticker
      sec/company_tickers_exchange.json    listed companies and unit investment trusts (SPY, DIA, MDY have no series id)
      nport/<yyyy>q<n>_nport.zip           Form N-PORT data sets: the newest quarter is read
      ncen/<yyyy>q<n>_ncen.zip             Form N-CEN data sets: every quarter, the newest report of a series wins
      rr/<yyyy>q<n>_rr1.zip                prospectus risk/return summaries: every quarter, the newest filing of a class wins
      gleif/rr.csv.zip                     GLEIF golden copy level 2 (or the gleif pack's cache, golden/rr.csv.zip)
"""
from __future__ import annotations

import csv
import io
import json
import re
import sys
import zipfile
from datetime import datetime
from pathlib import Path

csv.field_size_limit(sys.maxsize)
MONTHS = {m: i for i, m in enumerate(("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"), 1)}


def rows(zf: zipfile.ZipFile, table: str):
    """The rows of one tab-delimited table of a data set as dicts (the SEC's files are not quoted)."""
    with zf.open(table) as fh:
        yield from csv.DictReader(io.TextIOWrapper(fh, encoding="utf-8", errors="replace", newline=""), delimiter="\t",
                                  quoting=csv.QUOTE_NONE)


def date(text: str) -> str:
    """31-AUG-2026 -> 2026-08-31 (the data sets' date form); empty stays empty."""
    t = (text or "").strip()
    m = re.fullmatch(r"(\d{1,2})-([A-Za-z]{3})-(\d{4})", t)
    if m:
        return f"{m.group(3)}-{MONTHS[m.group(2).upper()]:02d}-{int(m.group(1)):02d}"
    if re.fullmatch(r"\d{8}", t):
        return f"{t[:4]}-{t[4:6]}-{t[6:]}"
    return t[:10]


def num(text):
    try:
        v = float(text)
    except (TypeError, ValueError):
        return None
    return v


def clean(text: str) -> str:
    return " ".join((text or "").split())


def quarters(folder: Path, suffix: str) -> list[Path]:
    """The data set files of a folder, newest quarter first (2026q3_nport.zip before 2026q2_nport.zip)."""
    return sorted(Path(folder).glob(f"*{suffix}"), key=lambda p: p.name, reverse=True)


# ---------------------------------------------------------------- tickers

def tickers(cache: Path) -> tuple[dict[str, list[dict]], dict[str, str]]:
    """({series id: [{CLASS_ID, TICKER, CIK}]} from the mutual-fund ticker file, {CIK (10 digits): ticker} of the listed trusts)."""
    mf = json.loads((cache / "sec" / "company_tickers_mf.json").read_text(encoding="utf-8"))
    f = mf["fields"]
    by_series: dict[str, list[dict]] = {}
    for r in mf["data"]:
        d = dict(zip(f, r))
        if d.get("seriesId") and d.get("symbol"):
            by_series.setdefault(d["seriesId"], []).append({"CLASS_ID": d["classId"], "TICKER": d["symbol"].upper(),
                                                            "CIK": f"{int(d['cik']):010d}"})
    listed: dict[str, str] = {}
    ex = cache / "sec" / "company_tickers_exchange.json"
    if ex.is_file():
        t = json.loads(ex.read_text(encoding="utf-8"))
        for r in t["data"]:
            d = dict(zip(t["fields"], r))
            listed.setdefault(f"{int(d['cik']):010d}", str(d["ticker"]).upper())
    return by_series, listed


EXCHANGES = {"N": "NYSE", "P": "NYSE Arca", "Z": "Cboe BZX", "A": "NYSE American", "V": "IEX"}


def etfs(cache: Path) -> dict[str, str]:
    """{ticker: listing exchange} of the exchange-traded funds in the Nasdaq Trader symbol directory (nasdaq/nasdaqlisted.txt and
    otherlisted.txt; the ETF column)."""
    out: dict[str, str] = {}
    for name, ex_col in (("nasdaqlisted.txt", None), ("otherlisted.txt", "Exchange")):
        p = cache / "nasdaq" / name
        if not p.is_file():
            continue
        for r in csv.DictReader(io.StringIO(p.read_text(encoding="utf-8", errors="replace")), delimiter="|"):
            sym = (r.get("Symbol") or r.get("ACT Symbol") or "").strip().upper()
            if sym and r.get("ETF") == "Y" and r.get("Test Issue") != "Y":
                out[sym] = "Nasdaq" if ex_col is None else EXCHANGES.get(r.get(ex_col, ""), r.get(ex_col, ""))
    return out


# ---------------------------------------------------------------- N-PORT

def nport_funds(zf: zipfile.ZipFile) -> dict[str, dict]:
    """Every fund's newest report in the data set: {fund key: {...}}. The key is the series id, or CIK<cik> for a fund without one
    (a unit investment trust such as SPY)."""
    sub = {r["ACCESSION_NUMBER"]: r for r in rows(zf, "SUBMISSION.tsv")}
    reg = {r["ACCESSION_NUMBER"]: r for r in rows(zf, "REGISTRANT.tsv")}
    best: dict[str, dict] = {}
    for r in rows(zf, "FUND_REPORTED_INFO.tsv"):
        acc = r["ACCESSION_NUMBER"]
        s, g = sub.get(acc, {}), reg.get(acc, {})
        cik = (g.get("CIK") or "").zfill(10)
        sid = r["SERIES_ID"].strip()
        key = sid if sid.startswith("S") else f"CIK{cik}"
        rec = {"KEY": key, "SERIES_ID": sid if sid.startswith("S") else "", "ACCESSION": acc, "NAME": clean(r["SERIES_NAME"]),
               "SERIES_LEI": r["SERIES_LEI"].strip() if r["SERIES_LEI"].strip() not in ("", "N/A") else "",
               "REPORT_DATE": date(s.get("REPORT_DATE", "")), "FILED": date(s.get("FILING_DATE", "")),
               "FISCAL_YEAR_END": date(s.get("REPORT_ENDING_PERIOD", "")), "SUB_TYPE": s.get("SUB_TYPE", ""),
               "CIK": cik, "REGISTRANT": clean(g.get("REGISTRANT_NAME", "")), "REGISTRANT_LEI": (g.get("LEI") or "").strip(),
               "TOTAL_ASSETS": num(r["TOTAL_ASSETS"]), "TOTAL_LIABILITIES": num(r["TOTAL_LIABILITIES"]), "NET_ASSETS": num(r["NET_ASSETS"]),
               "FLOWS": [{"SALES": num(r[f"SALES_FLOW_MON{i}"]), "REINVESTMENT": num(r[f"REINVESTMENT_FLOW_MON{i}"]),
                          "REDEMPTIONS": num(r[f"REDEMPTION_FLOW_MON{i}"])} for i in (1, 2, 3)]}
        if rec["NAME"] in ("", "N/A"):
            rec["NAME"] = rec["REGISTRANT"]
        old = best.get(key)
        if old is None or (rec["REPORT_DATE"], rec["FILED"], acc) > (old["REPORT_DATE"], old["FILED"], old["ACCESSION"]):
            best[key] = rec
    return best


def nport_returns(zf: zipfile.ZipFile, accessions: set[str]) -> dict[str, dict[str, list]]:
    """{accession: {class id: [return month 1, 2, 3]}} (percent; month 3 is the report month)."""
    out: dict[str, dict[str, list]] = {}
    for r in rows(zf, "MONTHLY_TOTAL_RETURN.tsv"):
        if r["ACCESSION_NUMBER"] in accessions:
            out.setdefault(r["ACCESSION_NUMBER"], {})[r["CLASS_ID"].strip()] = [num(r[f"MONTHLY_TOTAL_RETURN{i}"]) for i in (1, 2, 3)]
    return out


HOLDING_FIELDS = ("HOLDING_ID", "ISSUER_NAME", "ISSUER_LEI", "ISSUER_TITLE", "ISSUER_CUSIP", "BALANCE", "UNIT", "CURRENCY_CODE",
                  "CURRENCY_VALUE", "PERCENTAGE", "PAYOFF_PROFILE", "ASSET_CAT", "OTHER_ASSET", "ISSUER_TYPE", "INVESTMENT_COUNTRY",
                  "FAIR_VALUE_LEVEL", "DERIVATIVE_CAT", "IS_RESTRICTED_SECURITY")


def nport_holdings(zf: zipfile.ZipFile, accessions: set[str], log=print) -> dict[str, list[dict]]:
    """{accession: [holding]} for the chosen funds, with each holding's ticker and ISIN (IDENTIFIERS), its derivative terms and
    counterparties (part C.11 tables) and a repo's counterparty."""
    by_acc: dict[str, list[dict]] = {}
    by_id: dict[str, dict] = {}
    n = 0
    for r in rows(zf, "FUND_REPORTED_HOLDING.tsv"):
        n += 1
        acc = r["ACCESSION_NUMBER"]
        if acc not in accessions:
            continue
        h = {k: r.get(k, "") for k in HOLDING_FIELDS}
        by_acc.setdefault(acc, []).append(h)
        by_id[h["HOLDING_ID"]] = h
    log(f"  N-PORT holdings: {sum(len(v) for v in by_acc.values()):,} of {n:,} rows belong to the {len(accessions)} funds")
    for r in rows(zf, "IDENTIFIERS.tsv"):
        h = by_id.get(r["HOLDING_ID"])
        if h is not None:
            if r["IDENTIFIER_TICKER"].strip() and not h.get("TICKER"):
                h["TICKER"] = r["IDENTIFIER_TICKER"].strip()
            if r["IDENTIFIER_ISIN"].strip() and not h.get("ISIN"):
                h["ISIN"] = r["IDENTIFIER_ISIN"].strip()
    for r in rows(zf, "DERIVATIVE_COUNTERPARTY.tsv"):
        h = by_id.get(r["HOLDING_ID"])
        if h is not None and not h.get("CPTY"):
            h["CPTY"] = clean(r["DERIVATIVE_COUNTERPARTY_NAME"])
            h["CPTY_LEI"] = r["DERIVATIVE_COUNTERPARTY_LEI"].strip()
    for r in rows(zf, "REPURCHASE_COUNTERPARTY.tsv"):
        h = by_id.get(r["HOLDING_ID"])
        if h is not None and not h.get("CPTY"):
            h["CPTY"] = clean(r["NAME"])
            h["CPTY_LEI"] = r["LEI"].strip()
    for r in rows(zf, "REPURCHASE_AGREEMENT.tsv"):
        h = by_id.get(r["HOLDING_ID"])
        if h is not None:
            h["EXPIRY"] = date(r["MATURITY_DATE"])
            if r["IS_CLEARED"] == "Y" and r["CENTRAL_COUNTER_PARTY"].strip() and not h.get("CPTY"):
                h["CPTY"] = clean(r["CENTRAL_COUNTER_PARTY"]) + " (cleared)"
    for table, notional, expiry in (("FUT_FWD_NONFOREIGNCUR_CONTRACT.tsv", "NOTIONAL_AMOUNT", "EXPIRATION_DATE"),
                                    ("NONFOREIGN_EXCHANGE_SWAP.tsv", "NOTIONAL_AMOUNT", "TERMINATION_DATE"),
                                    ("FWD_FOREIGNCUR_CONTRACT_SWAP.tsv", "CURRENCY_PURCHASED_AMOUNT", "SETTLEMENT_DATE"),
                                    ("SWAPTION_OPTION_WARNT_DERIV.tsv", "PRINCIPAL_AMOUNT", "EXPIRATION_DATE")):
        for r in rows(zf, table):
            h = by_id.get(r["HOLDING_ID"])
            if h is not None:
                h["NOTIONAL"] = num(r.get(notional))
                h["EXPIRY"] = date(r.get(expiry, ""))
                h["UNREALIZED"] = num(r.get("UNREALIZED_APPRECIATION"))
    for r in rows(zf, "DESC_REF_INDEX_BASKET.tsv"):
        h = by_id.get(r["HOLDING_ID"])
        if h is not None and r["INDEX_NAME"].strip():
            h["REFERENCE"] = clean(r["INDEX_NAME"])
    return by_acc


# ---------------------------------------------------------------- N-CEN

def ncen(files: list[Path], series: set[str], ciks: set[str]) -> dict[str, dict]:
    """{fund key: census facts} from the newest N-CEN report of each chosen fund: its type flags, ETF facts (creation unit, exchange,
    authorised participants), adviser, custodian, administrator, transfer agent, auditor and share classes."""
    out: dict[str, dict] = {}
    for f in files:                                       # newest quarter first: the first report of a fund found wins
        with zipfile.ZipFile(f) as zf:
            sub = {r["ACCESSION_NUMBER"]: r for r in rows(zf, "SUBMISSION.tsv")}
            info = {}
            for r in rows(zf, "FUND_REPORTED_INFO.tsv"):
                sid = r["SERIES_ID"].strip()
                cik = sub.get(r["ACCESSION_NUMBER"], {}).get("CIK", "").zfill(10)
                key = sid if sid in series else (f"CIK{cik}" if f"CIK{cik}" in ciks and not sid.startswith("S") else None)
                if key and key not in out:
                    info[r["FUND_ID"]] = key
                    s = sub.get(r["ACCESSION_NUMBER"], {})
                    out[key] = {"NCEN_ACCESSION": r["ACCESSION_NUMBER"], "NCEN_PERIOD": date(s.get("REPORT_ENDING_PERIOD", "")),
                                "IS_ETF": r["IS_ETF"] == "Y", "IS_INDEX": r["IS_INDEX"] == "Y",
                                "IS_FUND_OF_FUNDS": r["IS_FUND_OF_FUND"] == "Y", "IS_MASTER_FEEDER": r["IS_MASTER_FEEDER"] == "Y",
                                "IS_TARGET_DATE": r["IS_TARGET_DATE"] == "Y", "IS_NON_DIVERSIFIED": r["IS_NON_DIVERSIFIED"] == "Y",
                                "LENDS_SECURITIES": r["DID_LEND_SECURITIES"] == "Y",
                                "MONTHLY_AVG_NET_ASSETS": num(r["MONTHLY_AVG_NET_ASSETS"]) or num(r["DAILY_AVG_NET_ASSETS"]),
                                "CLASSES": [], "ADVISERS": [], "SUB_ADVISERS": [], "APS": []}
            acc_key = {}
            for fid, key in info.items():
                acc_key.setdefault(fid.split("_")[0], set()).add(key)
            if not info:
                continue

            def each(table):
                for r in rows(zf, table):
                    key = info.get(r.get("FUND_ID", ""))
                    if key:
                        yield out[key], r
            for d, r in each("ETF.tsv"):
                d["IS_ETF"] = True
                d["CREATION_UNIT"] = num(r["NUM_SHARES_PER_CREATION_UNIT"])
                d["IN_KIND_ETF"] = r["IS_FUND_IN_KIND_ETF"] == "Y"
            for d, r in each("SECURITY_EXCHANGE.tsv"):
                d.setdefault("EXCHANGES", []).append({"MIC": r["FUND_EXCHANGE"].strip(), "TICKER": r["FUND_TICKER_SYMBOL"].strip().upper()})
            for d, r in each("SHARES_OUTSTANDING.tsv"):
                d["CLASSES"].append({"CLASS_ID": r["CLASS_ID"].strip(), "CLASS_NAME": clean(r["CLASS_NAME"]),
                                     "TICKER": r["TICKER"].strip().upper()})
            for d, r in each("ADVISER.tsv"):
                who = {"NAME": clean(r["ADVISER_NAME"]), "LEI": r["ADVISER_LEI"].strip()}
                (d["SUB_ADVISERS"] if "sub" in r["ADVISER_TYPE"].lower() else d["ADVISERS"]).append(who)
            for d, r in each("CUSTODIAN.tsv"):
                if r["IS_SUB_CUSTODIAN"] != "Y" and not d.get("CUSTODIAN"):
                    d["CUSTODIAN"], d["CUSTODIAN_LEI"] = clean(r["CUSTODIAN_NAME"]), r["CUSTODIAN_LEI"].strip()
            for d, r in each("ADMIN.tsv"):
                if r["IS_SUB_ADMIN"] != "Y" and not d.get("ADMINISTRATOR"):
                    d["ADMINISTRATOR"], d["ADMINISTRATOR_LEI"] = clean(r["ADMIN_NAME"]), r["ADMIN_LEI"].strip()
            for d, r in each("TRANSFER_AGENT.tsv"):
                if r["IS_SUBTRANSFER_AGENT"] != "Y" and not d.get("TRANSFER_AGENT"):
                    d["TRANSFER_AGENT"] = clean(r["TRANSFERAGENT_NAME"])
            for d, r in each("AUTHORIZED_PARTICIPANT.tsv"):
                d["APS"].append({"NAME": clean(r["PARTICIPANT_NAME"]), "LEI": r["PARTICIPANT_LEI"].strip(),
                                 "PURCHASES": num(r["PURCHASE_VALUE"]), "REDEMPTIONS": num(r["REDEEM_VALUE"])})
            for r in rows(zf, "PUBLIC_ACCOUNTANT.tsv"):
                for key in acc_key.get(r["ACCESSION_NUMBER"], ()):
                    if not out[key].get("AUDITOR"):
                        out[key]["AUDITOR"], out[key]["AUDITOR_LEI"] = clean(r["PUB_ACCOUNTANT_NAME"]), r["PUB_ACCOUNTANT_LEI"].strip()
    return out


# ---------------------------------------------------------------- the prospectus risk/return summary

def risk_return(files: list[Path], classes: set[str], series: set[str]) -> dict[str, dict]:
    """{class id or series id: {EXPENSE_RATIO, GROSS_EXPENSE_RATIO, MANAGEMENT_FEE, TURNOVER, ANNUAL_RETURNS {year: %}, FILED}} from the
    newest filing that has them. Ratios as fractions in the data sets; returned in percent."""
    out: dict[str, dict] = {}
    want = {"NetExpensesOverAssets": "EXPENSE_RATIO", "ExpensesOverAssets": "GROSS_EXPENSE_RATIO",
            "ManagementFeesOverAssets": "MANAGEMENT_FEE", "PortfolioTurnoverRate": "TURNOVER", "AnnualReturnPercent": "RETURN",
            "AnnlRtrPct": "RETURN"}                          # the bar chart: AnnualReturnPercent, AnnlRtrPct since the 2024 taxonomy
    for f in files:                                       # newest quarter first: a fund answered by a newer quarter keeps it
        with zipfile.ZipFile(f) as zf:
            filed = {r["adsh"]: r["filed"] for r in rows(zf, "sub.tsv")}
            found: dict[str, dict] = {}
            for r in rows(zf, "num.tsv"):
                name = want.get(r["tag"])
                if not name:
                    continue
                dims = r["otherdims"]
                m = re.search(r"Class=(C\d{9})", dims)
                cls = m.group(1) if m else ""
                sid = r["series"] if r["series"].startswith("S") else ""
                key = cls if cls in classes else (sid if sid in series and not cls and "Index=" not in dims else None)
                if key is None or key in out:
                    continue
                d = found.get(key)
                if d is None or filed.get(r["adsh"], "") > filed.get(d["_adsh"], ""):
                    found[key] = d = {"_adsh": r["adsh"], "FILED": date(filed.get(r["adsh"], "")), "ANNUAL_RETURNS": {}}
                elif r["adsh"] != d["_adsh"]:
                    continue                              # an older filing of the same quarter
                v = num(r["value"])
                if v is None:
                    continue
                if name == "RETURN":
                    d["ANNUAL_RETURNS"][r["ddate"][:4]] = round(v * 100, 2)
                else:
                    d[name] = round(v * 100, 4)
            out.update(found)
    for d in out.values():
        d.pop("_adsh", None)
    return out


# ---------------------------------------------------------------- GLEIF level 2: fund relationships

def gleif_funds(path: Path | None, leis: set[str]) -> dict[str, list[dict]]:
    """{fund LEI: [{TYPE, LEI, STATUS}]} of the relationships GLEIF records for a fund: managed by, sub-fund of, feeder to."""
    out: dict[str, list[dict]] = {}
    if not path or not path.is_file():
        return out
    kinds = {"IS_FUND-MANAGED_BY": "Managed by", "IS_SUBFUND_OF": "Sub-fund of", "IS_FEEDER_TO": "Feeder to"}
    with zipfile.ZipFile(path) as zf:
        with zf.open(zf.namelist()[0]) as fh:
            r = csv.reader(io.TextIOWrapper(fh, encoding="utf-8", newline=""))
            head = next(r)
            i_s, i_e = head.index("Relationship.StartNode.NodeID"), head.index("Relationship.EndNode.NodeID")
            i_t, i_st = head.index("Relationship.RelationshipType"), head.index("Relationship.RelationshipStatus")
            for row in r:
                if row[i_s] in leis and row[i_t] in kinds:
                    out.setdefault(row[i_s], []).append({"TYPE": kinds[row[i_t]], "LEI": row[i_e], "STATUS": row[i_st]})
    return out


def newest(folder: Path, suffix: str) -> Path | None:
    got = quarters(folder, suffix)
    return got[0] if got else None


def today() -> str:
    return datetime.now().strftime("%Y-%m-%d")
