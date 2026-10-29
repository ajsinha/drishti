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

"""The data samples: one page per data type, a real record fully explained, for whoever writes the production query that must emit it.

    python3 tools/testdata/sample_docs.py              write docs/contracts/samples/<domain>/<kind>.md and docs/contracts/samples/README.md
    python3 tools/testdata/sample_docs.py --check      fail when a page differs from what would be written (or a page is left over)
    python3 tools/testdata/sample_docs.py audit        every contract against ALL its committed test data: fields missing from the
                                                       contract, contract fields no row has, required fields absent, types that differ

Each page is generated from two sources only: the committed test data (data/testdata/<domain>/<kind>/*.jsonl.gz, every row of every file,
read and never written; the pack's samples for the kinds without test data) and the kind's data contract
(config/packs/<pack>/contracts/<kind>.contract.yaml: every field's meaning, unit, presence and values). A meaning missing on a page is
fixed in the contract, never here. The page shows the most complete real record (the one with the most non-empty fields) pretty-printed,
long arrays folded; a kind whose rows come in sub-types of a different shape or meaning (a trade's product type, a P&L or PV form, a curve
type) shows the most complete record of each sub-type. Then every field of the samples (nested ones as LEGS[].CASH_FLOWS[].AMOUNT), the
fields only other rows have, and a checklist for the production query.
"""
from __future__ import annotations

import argparse
import gzip
import importlib.util
import json
import re
import sys
from collections import Counter, OrderedDict, defaultdict
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
TESTDATA = ROOT / "data" / "testdata"
PACKS = ROOT / "config" / "packs"
OUT = ROOT / "docs" / "contracts" / "samples"
DOCS = ROOT / "docs"
GENERATOR = "tools/testdata/sample_docs.py"

_spec = importlib.util.spec_from_file_location("drishti_contracts_for_samples", ROOT / "tools" / "contracts.py")
C = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(C)

DATE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
STAMP = re.compile(r"^\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}(:\d{2}(\.\d+)?)?(Z|[+-]\d{2}:?\d{2})?$")   # ISO-8601 only
NUMERIC = re.compile(r"^-?\d+(\.\d+)?([eE][-+]?\d+)?$")
MAX_VALUES = 8            # a text field with at most this many distinct values lists them
MAX_TRACK = 60            # distinct values kept per field (for the enum check)
KEEP_OBJECTS = 2          # array elements shown before a fold: objects
KEEP_SCALARS = 3          # numbers and strings

# -- what the pages add to the contracts: sub-types, folds, links, groups -------------------------------------------------------------


def _form(d: dict) -> str:
    """A VaR input's form (VAR_GUIDE.md sections 15.13 and 8.4): value type, grain, run kind, and a cancel."""
    vt = "P&L vector" if d.get("VALUE_TYPE") == "PNL" else "Scenario PVs"
    grain = (d.get("GRAIN") or "TRADE").lower()
    run = "intraday update" if d.get("RUN_KIND") == "INTRADAY" else "full run"
    cancel = ", cancel" if str(d.get("TRADE_STATUS") or "").upper() in ("CANCELLED", "CANCELED") else ""
    return f"{vt} by {grain}, {run}{cancel}"


#: folder (or pack/kind for sample-only kinds) -> (how a sub-type is recognised, the function giving a record's sub-type)
SUBTYPES = {
    "trades/endur": ("`PRODUCT_TYPE`", lambda d: d.get("PRODUCT_TYPE") or "(no product type)"),
    "trades/calypso": ("`PRODUCT_TYPE`", lambda d: d.get("PRODUCT_TYPE") or "(no product type)"),
    "trades/murex": ("`PRODUCT_TYPE`", lambda d: d.get("PRODUCT_TYPE") or "(no product type)"),
    "market-risk/pnl-vector": ("`VALUE_TYPE`, `GRAIN`, `RUN_KIND` and `TRADE_STATUS`", _form),
    "market-risk/scenario-pv": ("`VALUE_TYPE`, `GRAIN`, `RUN_KIND` and `TRADE_STATUS`", _form),
    "counterparty-risk/agreement": ("`TYPE`", lambda d: d.get("TYPE") or "(no type)"),
    "market-data/ir-curve": ("`CURVE_TYPE`", lambda d: d.get("CURVE_TYPE") or "(no curve type)"),
    "news-and-markets/quote": ("`INSTRUMENT_TYPE`", lambda d: d.get("INSTRUMENT_TYPE") or "(no instrument type)"),
    "funds/fund": ("`TYPE`", lambda d: d.get("TYPE") or "(no type)"),
    "gleif/lei-entity": ("`CATEGORY` (blank: a general entity)", lambda d: d.get("CATEGORY") or "General entity"),
    "gleif/lei-mapping": ("`TYPE`", lambda d: d.get("TYPE") or "(no type)"),
    "gleif/lei-relationship": ("`LEVEL`", lambda d: d.get("LEVEL") or "(no level)"),
    "market-risk/portfolio": ("`TYPE`", lambda d: d.get("TYPE") or "(no type)"),
    "market-risk/risk-hierarchy": ("`LEVEL`", lambda d: d.get("LEVEL") or "(no level)"),
}

#: rows beyond the committed test data that show a form the test data does not hold (the pack's samples), by folder
EXTRA_SOURCES = {
    "market-risk/pnl-vector": ["config/packs/market-risk/samples/pnl-vector.jsonl"],
    "market-risk/scenario-pv": ["config/packs/market-risk/samples/scenario-pv.jsonl",
                                "config/packs/market-risk/samples/intraday/I-2026-09-30-001.scenario-pv.jsonl"],
}

#: what a folded array holds, by field name
FOLD_WHAT = {
    "PNL": "one per scenario, in the scenario set's SCENARIO_DATES order",
    "SCENARIO_PV": "one per scenario, in the scenario set's SCENARIO_DATES order",
    "RATES": "one per scenario, in the scenario set's SCENARIO_DATES order",
    "SCENARIO_DATES": "the scenario days, oldest first",
    "MTM": "one per simulation path, path 0 first",
    "GRID_DATES": "the run's grid dates, in order",
    "TRADE_IDS": "trade ids",
}

#: guide sections and tutorials of a kind: (document under docs/, the heading's start); anchors are computed from the heading
_VAR = [("guides/VAR_GUIDE.md", "15.13 "), ("tutorials/25-var-from-the-bank-to-a-trade.md", "# ")]
_EXP = [("guides/EXPOSURE_GUIDE.md", "16. "), ("tutorials/26-exposure-from-the-bank-to-a-trade.md", "# ")]
_TRD = [("guides/PACKS.md", "Trading"), ("knowledge/trades-and-products.md", "# "),
        ("tutorials/24-correct-a-trade-and-approve-it.md", "# ")]
LINKS = {
    "trade": _TRD,
    "pnl-vector": [("guides/VAR_GUIDE.md", "15.2 "), ("guides/VAR_GUIDE.md", "8.4 ")] + _VAR,
    "scenario-pv": [("guides/VAR_GUIDE.md", "15.9 "), ("guides/VAR_GUIDE.md", "8.4 ")] + _VAR,
    "fx-scenario": [("guides/VAR_GUIDE.md", "15.9 ")] + _VAR[1:],
    "scenario-set": [("guides/VAR_GUIDE.md", "15.3 ")] + _VAR[1:],
    "risk-hierarchy": [("guides/VAR_GUIDE.md", "15.4 "), ("guides/VAR_GUIDE.md", "15.14 ")] + _VAR[1:],
    "portfolio": [("guides/VAR_GUIDE.md", "15.5 ")] + _VAR[1:],
    "var-limit": [("guides/VAR_GUIDE.md", "15.15 ")] + _VAR[1:],
    "simulation-run": [("guides/EXPOSURE_GUIDE.md", "15.2 ")] + _EXP[1:],
    "exposure-path": [("guides/EXPOSURE_GUIDE.md", "15.3 "), ("guides/EXPOSURE_GUIDE.md", "21. ")] + _EXP[1:],
    "netting-set": _EXP, "csa": _EXP, "collateral-balance": _EXP, "agreement": _EXP, "margin-call": _EXP, "credit-limit": _EXP,
    "cva": _EXP, "sa-ccr": _EXP, "simm": _EXP,
    "trade-valuation": [("contracts/TVAL.md", "# ")] + _EXP,
    "frtb-sensitivity": [("contracts/FRTB.md", "# ")], "pnl-explain": [("contracts/PNL.md", "# ")], "stress-result": [("contracts/STR.md", "# ")],
    "fund": [("guides/FUNDS_GUIDE.md", "# "), ("tutorials/28-funds-and-etfs.md", "# ")],
    "fund-holder": [("guides/FUNDS_GUIDE.md", "# "), ("tutorials/28-funds-and-etfs.md", "# ")],
    "index-funds": [("guides/FUNDS_GUIDE.md", "# "), ("tutorials/28-funds-and-etfs.md", "# ")],
}
PACK_LINKS = {
    "core": [("knowledge/entities-and-ownership.md", "# "), ("knowledge/counterparty-credit-risk.md", "# ")],
    "market-data": [("knowledge/market-data.md", "# ")],
    "gleif": [("guides/PUBLIC_DATA.md", "# "), ("knowledge/entities-and-ownership.md", "# "), ("tutorials/27-groupings-and-analytics.md", "# ")],
    "us-equities": [("guides/PUBLIC_DATA.md", "# "), ("knowledge/companies-funds-and-etfs.md", "# "), ("tutorials/27-groupings-and-analytics.md", "# ")],
    "news-and-markets": [("guides/PUBLIC_DATA.md", "# ")],
    "world-economy": [("guides/PUBLIC_DATA.md", "# "), ("knowledge/economies.md", "# ")],
    "market-risk": [("knowledge/market-risk.md", "# ")],
    "counterparty-risk": [("knowledge/counterparty-credit-risk.md", "# ")],
}

#: the index's groups, in order: (title, who, the members as folder or pack/kind)
GROUPS = [
    ("Reference and master data", "You produce this", ["core/counterparty", "core/counterparty-group", "core/legal-entity", "core/book",
                                                        "core/desk", "core/trader", "core/issuer", "core/ccp", "core/clearing-account", "core/calendar"]),
    ("Trades, by source system", "You produce this", ["trades/endur", "trades/calypso", "trades/murex"]),
    ("Market-risk inputs (VaR)", "You produce this", ["market-risk/scenario-pv", "market-risk/pnl-vector", "market-risk/scenario-set",
                                                       "market-risk/fx-scenario", "market-risk/risk-hierarchy", "market-risk/portfolio",
                                                       "market-risk/var-limit"]),
    ("Valuations and other risk results your engines produce", "You produce this",
     ["market-risk/trade-valuation", "market-risk/frtb-sensitivity", "market-risk/pnl-explain", "market-risk/stress-scenario",
      "market-risk/stress-result"]),
    ("Counterparty-risk inputs (exposure)", "You produce this",
     ["counterparty-risk/simulation-run", "counterparty-risk/exposure-path", "counterparty-risk/netting-set", "counterparty-risk/agreement",
      "counterparty-risk/csa", "counterparty-risk/collateral-balance", "counterparty-risk/margin-call", "counterparty-risk/credit-limit",
      "counterparty-risk/cva", "counterparty-risk/sa-ccr", "counterparty-risk/simm"]),
    ("Market data", "You produce this", None),               # every market-data folder
    ("Public data (refreshed by Drishti's scripts)", "Public source", None),   # gleif, us-equities, funds, news-and-markets, world-economy
    ("Demonstration only", "Demo", None),                    # compute-demo
]
PUBLIC = ("gleif", "us-equities", "funds", "news-and-markets", "world-economy")

#: what each produced kind is needed for (VAR_GUIDE.md section 15, EXPOSURE_GUIDE.md sections 15 and 16)
NEEDED = {
    "scenario-pv": "**VaR: required** (each trade as scenario PVs or as a P&L vector)",
    "pnl-vector": "**VaR: required** (each trade as a P&L vector or as scenario PVs)",
    "scenario-set": "**VaR: required** (orders every vector)",
    "fx-scenario": "**VaR: required** for scenario PVs not in USD, unless fx-spot holds 510 days of history",
    "risk-hierarchy": "**VaR: required** for the MGMT and LEGAL hierarchies",
    "portfolio": "VaR: optional (stored portfolios)",
    "var-limit": "VaR: optional (limits of the column-built hierarchies)",
    "simulation-run": "**Exposure: required** (the run and its grid)",
    "exposure-path": "**Exposure: required** (every trade's MtM on every path)",
    "netting-set": "**Exposure: required** (membership, margining)",
    "csa": "Exposure: margined netting sets",
    "collateral-balance": "Exposure: collateral held today",
    "trade-valuation": "Exposure: today's MtM (the t₀ check)",
    "counterparty": "Exposure: `CPTY:` scopes",
    "counterparty-group": "Exposure: `GROUP:` scopes",
    "legal-entity": "VaR and exposure: the LEGAL hierarchy, `LE:` scopes",
    "book": "VaR: books under desks",
    "desk": "VaR: desks under legal entities and divisions",
    "trade": "VaR (positions, status, maturity) and exposure (maturity, status)",
    "fx-spot": "VaR: derived scenario FX when fx-scenario is not sent",
    "ir-curve": "Exposure: discounting when a discounted figure is asked",
}

#: kind-specific lines of the production checklist
CHECKLIST = {
    "_var": [
        "One row per trade (`GRAIN` `TRADE`, `TRADE_ID`) or per position (`GRAIN` `POSITION`, `POSITION_ID`) per business date and scenario "
        "set; one source system sends one form and one grain on one date (`DRS-5210`); a trade id twice on a date refuses the date (`DRS-5202`).",
        "The vector has exactly `SCENARIO_COUNT` numbers of the date's scenario set, element *i* for `SCENARIO_DATES[i]` (oldest first); no "
        "`null`, `NaN` or infinity inside a vector: a trade the batch could not value is `STATUS` `failed` with the vector null (`DRS-5201`, "
        "`DRS-5206`).",
        "One `BATCH_ID` and one `SCENARIO_SET_ID` per date across both forms (`DRS-5203`); `COMPUTED_AT` in UTC.",
        "Intraday: send only the changed trades, `RUN_KIND` `INTRADAY`, a new `BATCH_ID`, a `COMPUTED_AT` later than the morning run's (the "
        "latest per trade wins); a cancel is a row with `TRADE_STATUS` `CANCELLED` and the vector null. The same rows may be published to the "
        "PV topic on Kafka (connector `pv-updates`).",
        "`VALUE_TYPE` must match the payload and `GRAIN` the id (`DRS-5208`); a pinned system's form (`drishti.var.inputs`) must match (`DRS-5209`).",
    ],
    "pnl-vector": ["`PNL` and `PV` in `CURRENCY` (USD in the demo), whole currency units, gain positive, loss negative, from the bank's point "
                   "of view; a currency with no scenario FX on the date rejects the row (`DRS-5207`)."],
    "scenario-pv": ["`BASE_PV` and every `SCENARIO_PV` in the trade's own `CURRENCY`; `VALUATION_DATE` and `BASE_VALUATION_DATE` equal the "
                    "business date (`DRS-5201`); a non-USD currency needs scenario FX on the date: an `fx-scenario` row, or fx-spot history "
                    "(`DRS-5207`)."],
    "fx-scenario": ["`RATE` and every element of `RATES` in USD per one unit of `CURRENCY`; `RATES` in the scenario set's order, its length "
                    "`SCENARIO_COUNT`; the same `SCENARIO_SET_ID` and `BATCH_ID` as the date's PVs."],
    "scenario-set": ["One row per date (two sets on a date refuse it, `DRS-5203`); `SCENARIO_DATES` strictly increasing, all before the "
                     "business date, `SCENARIO_COUNT` its length; every vector of the date follows this order."],
    "exposure-path": ["One row per (`RUN_ID`, `TRADE_ID`, `GRID_INDEX`) (a duplicate fails the load); `MTM` has exactly the run's `PATHS` "
                      "numbers, no `null`, `NaN` or infinity (`DRS-5444`); `GRID_DATE` = the run's `GRID_DATES[GRID_INDEX]`; rows to the "
                      "trade's maturity; one `BATCH_ID` per run (`DRS-5441`)."],
    "simulation-run": ["One row per run and date; `GRID_DATES` increasing and after the business date; `PATHS` is the length of every "
                       "path vector of the run (`DRS-5441`)."],
    "trade": ["`TRADE_ID` is text, unique across the source systems; a JSON number is read as text, leading zeros kept.",
              "Every day sends the trades new or changed that day (or the whole book): an amendment is the whole trade again with "
              "`TRADE_VERSION` raised, `TRADE_ACTION` and `TRANSACTION_ID` new and `SOURCE_UPDATE_TIME` later; a cancellation is the "
              "trade again with `TRADE_STATUS` `CANCELLED`. A trade is never deleted from the files.",
              "Missing values may be spelt `\"\"` as the extracts do; the screens read them with `nonblank`.",
              "`EVENTS[].AMOUNT` is signed: paid negative, received positive."],
}


# -- reading the data ----------------------------------------------------------------------------------------------------------------

def _tname(v) -> str | None:
    if v is None:
        return None
    if isinstance(v, bool):
        return "boolean"
    if isinstance(v, int):
        return "integer"
    if isinstance(v, float):
        return "number"
    if isinstance(v, str):
        return "date" if DATE.match(v) else "timestamp" if STAMP.match(v) else "numeric string" if NUMERIC.match(v) else "string"
    if isinstance(v, dict):
        return "object"
    if isinstance(v, list):
        kinds = {_tname(x) for x in v[:20] if x is not None}       # the first elements decide (a vector holds 509 numbers)
        if not kinds:
            return "array"
        if kinds <= {"integer", "number"}:
            return "array of numbers"
        if kinds <= {"string", "date", "timestamp", "numeric string"}:
            return "array of dates" if kinds == {"date"} else "array of strings"
        if kinds == {"object"}:
            return "array of objects"
        return "array"
    return "string"


def _empty(v) -> bool:
    return v is None or v == "" or v == [] or v == {}


class Scan:
    """Every row of one data type: the fields (as paths), their types, presence and values; the rows of each sub-type and its most
    complete row."""

    def __init__(self, subtype=None, patterns: dict | None = None):
        self.subtype = subtype
        self.patterns = {p: re.compile(x) for p, x in (patterns or {}).items()}
        self.unmatched = Counter()      # path -> values that do not match the contract's pattern
        self.unmatched_example: dict = {}
        self.rows = 0
        self.objects = Counter()        # path of an object ("" the record) -> objects seen there
        self.keys = Counter()           # path -> objects that hold the key at all
        self.present = Counter()        # path -> objects where it is not empty
        self.nonnull = Counter()        # path -> objects where it is neither null nor "" (an empty array counts: the key is sent)
        self.row_present = Counter()    # path -> rows where it is not empty somewhere
        self.types = defaultdict(Counter)
        self.values = defaultdict(Counter)
        self.many = set()               # paths with more than MAX_VALUES distinct values
        self.example = {}
        self.order = OrderedDict()      # paths in the order first met
        self.subs = OrderedDict()       # sub-type -> {"rows", "score", "doc", "source"}
        self.sources = Counter()
        self._paths: dict[str, dict[str, str]] = {}     # parent path -> key -> path (built once)

    def add(self, doc: dict, source: str) -> None:
        self.rows += 1
        self.sources[source] += 1
        seen: set[str] = set()
        self._walk(doc, "", seen)
        self.row_present.update(seen)
        label = self.subtype(doc) if self.subtype else ""
        s = self.subs.setdefault(label, {"rows": 0, "score": -1, "doc": None, "source": ""})
        s["rows"] += 1
        if len(seen) > s["score"]:
            s.update(score=len(seen), doc=doc, source=source)

    def _walk(self, obj: dict, path: str, seen: set) -> None:
        self.objects[path] += 1
        cache = self._paths.setdefault(path, {})
        keys, nonnull, present, types, many, values = self.keys, self.nonnull, self.present, self.types, self.many, self.values
        for k, v in obj.items():
            p = cache.get(k)
            if p is None:
                p = cache[k] = f"{path}.{k}" if path else k
                self.order.setdefault(p, None)
            keys[p] += 1
            if v is None or v == "":
                continue
            nonnull[p] += 1
            if v == [] or v == {}:
                continue
            present[p] += 1
            seen.add(p)
            types[p][_tname(v)] += 1
            if p not in self.example:
                self.example[p] = v
            rx = self.patterns.get(p)
            if rx is not None and isinstance(v, (str, int)) and not rx.search(str(v)):
                self.unmatched[p] += 1
                self.unmatched_example.setdefault(p, v)
            if p not in many and isinstance(v, (str, bool, int)):
                vals = values[p]
                vals[v] += 1
                if len(vals) > MAX_TRACK:
                    many.add(p)
                    values.pop(p, None)
            if isinstance(v, dict):
                self._walk(v, p, seen)
            elif isinstance(v, list) and any(isinstance(x, dict) for x in v):
                for x in v:
                    if isinstance(x, dict):
                        self._walk(x, p + "[]", seen)


def _lines(f: Path):
    op = gzip.open if f.suffix == ".gz" else open
    with op(f, "rt", encoding="utf-8") as fh:
        for line in fh:
            if line.strip():
                yield json.loads(line)


def manifest() -> dict:
    return yaml.safe_load((TESTDATA / "testdata.yaml").read_text(encoding="utf-8")) or {}


def _sample_files(pack: str, kind: str) -> list[Path]:
    d = PACKS / pack / "samples"
    return sorted(d.glob(f"{kind}.jsonl")) + sorted(d.glob(f"{kind}.part-*.jsonl"))


def pages() -> list[dict]:
    """Every data type that gets a page: each test-data folder, then each kind a bank or a public source produces that has samples but no
    test data. {name (domain/kind), pack, kind, dataset (the manifest's entry or None), files, extra}."""
    out, have = [], set()
    for d in manifest().get("datasets") or []:
        if not d.get("folder"):
            continue
        folder = TESTDATA / d["folder"]
        files = sorted(folder.glob("*.jsonl.gz"))
        out.append({"name": d["folder"], "pack": d["pack"], "kind": d["kind"], "dataset": d, "files": files,
                    "extra": [ROOT / x for x in EXTRA_SOURCES.get(d["folder"], [])]})
        have.add((d["pack"], d["kind"]))
    for cmd, f, c in C.contracts(PACKS):
        if not c or (cmd["pack"], cmd["kind"]) in have or c["class"] not in ("input", "fundamental"):
            continue
        files = _sample_files(cmd["pack"], cmd["kind"])
        if files:
            out.append({"name": f"{cmd['pack']}/{cmd['kind']}", "pack": cmd["pack"], "kind": cmd["kind"], "dataset": None, "files": [],
                        "extra": files})
    return out


def computed() -> list[dict]:
    """The kinds Drishti computes or derives (no page): their contracts' class is computed or drishti-owned, or they are stored but have
    neither test data nor samples."""
    have = {(p["pack"], p["kind"]) for p in pages()}
    out = []
    for cmd, f, c in C.contracts(PACKS):
        if c and (cmd["pack"], cmd["kind"]) not in have:
            out.append({"pack": cmd["pack"], "kind": cmd["kind"], "mnemonic": cmd["code"], "class": c["class"],
                        "title": c.get("title", ""), "description": c.get("description", ""), "source": c.get("source", "")})
    return out


def scan(page: dict) -> Scan:
    cf = contract_fields(contract(page["pack"], page["kind"]))
    s = Scan(SUBTYPES.get(page["name"], (None, None))[1], {p: x["pattern"] for p, x in cf.items() if x.get("pattern")})
    for f in page["files"]:
        for doc in _lines(f):
            s.add(doc, str(f.relative_to(ROOT)))
    for f in page["extra"]:
        for doc in _lines(f):
            s.add(doc, str(f.relative_to(ROOT)))
    return s


def _scan_named(name: str) -> Scan:
    s = scan(next(p for p in pages() if p["name"] == name))
    s.subtype = None                       # a function does not travel back from a worker
    return s


def scan_all(all_pages: list[dict], workers: int = 4) -> dict[str, Scan]:
    """Every page's scan, the largest first, in a few worker processes (the test data is 150 MB of gzip)."""
    import concurrent.futures as cf
    import multiprocessing as mp
    order = sorted(all_pages, key=lambda p: -sum(f.stat().st_size for f in p["files"] + p["extra"]))
    with cf.ProcessPoolExecutor(max_workers=workers, mp_context=mp.get_context("fork")) as ex:
        futs = {p["name"]: ex.submit(_scan_named, p["name"]) for p in order}
        return {name: f.result() for name, f in futs.items()}


# -- the contract --------------------------------------------------------------------------------------------------------------------

def contract(pack: str, kind: str) -> dict:
    f = C.contract_file(PACKS, pack, kind)
    return C.load(f) if f.is_file() else {}


def contract_fields(c: dict) -> dict[str, dict]:
    """Path (LEGS[].CASH_FLOWS[].AMOUNT) -> the contract's field entry."""
    out = {x["name"]: x for x in c.get("fields") or []}
    for path, fs in (c.get("nested") or {}).items():
        for x in fs:
            out[f"{path}.{x['name']}"] = x
    return out


_FAMILY = {"text": {"string", "date", "timestamp", "numeric string"}, "id": {"string", "integer", "date", "numeric string"}, "integer": {"integer", "numeric string"},
           "amount": {"integer", "number", "numeric string"}, "rate": {"integer", "number", "numeric string"}, "date": {"date"}, "timestamp": {"timestamp", "date"},
           "boolean": {"boolean"}, "vector": {"array of numbers"}, "record": {"object"}, "record list": {"array of objects"},
           "text list": {"array of strings", "array of dates", "array"}, "id list": {"array of strings", "array"},
           "date list": {"array of dates", "array of strings", "array"}, "list": {"array of numbers", "array of strings", "array of dates",
                                                                                  "array of objects", "array"}}


def audit_one(page: dict, s: Scan | None = None) -> list[str]:
    """What in the contract no longer matches ALL the kind's committed rows (contracts.py check reads a sample of them)."""
    if not page["files"]:
        return []
    c = contract(page["pack"], page["kind"])
    if not c:
        return [f"{page['name']}: no contract"]
    s = s or scan(page)
    cf = contract_fields(c)
    out = []
    ds = page["dataset"] or {}
    if ds.get("key") and [ds["key"]] != (c.get("key") or []):
        out.append(f"{page['name']}: the contract's key {c.get('key')} is not the test data's {ds['key']}")
    if c.get("validation") and c["validation"] != C.Draft.rules(None, c.get("fields") or [], c.get("nested") or {}, c.get("key") or [],
                                                               c.get("dated-by") or ""):
        out.append(f"{page['name']}: the contract's validation rules no longer follow its fields (required, enums, links)")
    if ds.get("folder") and f"data/testdata/{ds['folder']}" not in ((c.get("storage") or {}).get("test-data") or []):
        out.append(f"{page['name']}: the contract's storage.test-data does not name data/testdata/{ds['folder']}")
    test_paths = {p for p in s.keys}
    depth_ok = lambda p: p.count(".") <= C.MAX_DEPTH        # noqa: E731  (the contracts describe three nested levels)
    for p in s.order:
        if p not in cf and depth_ok(p) and not p.startswith("_meta"):
            out.append(f"{page['name']}: {p} is in the data but not in the contract")
    for p, x in cf.items():
        if p not in test_paths:
            out.append(f"{page['name']}: {p} is in the contract but in no row")
            continue
        parent = p.rsplit(".", 1)[0] if "." in p else ""
        if x["presence"] == "required" and s.nonnull[p] < s.objects[parent]:
            out.append(f"{page['name']}: {p} is required but null or missing in {s.objects[parent] - s.nonnull[p]} of {s.objects[parent]}")
        if s.unmatched.get(p):
            out.append(f"{page['name']}: {p} does not match its pattern {x['pattern']} in {s.unmatched[p]:,} values "
                       f"(e.g. {s.unmatched_example[p]!r})")
        if x.get("enum") and p in s.values and set(s.values[p]) - set(x["enum"]):
            out.append(f"{page['name']}: {p} has values outside its enum: {sorted(map(str, set(s.values[p]) - set(x['enum'])))[:8]}")
        seen = set(s.types[p])
        if seen and x["type"] in _FAMILY and not seen <= _FAMILY[x["type"]] | ({"integer"} if x["type"] in ("amount", "rate") else set()):
            out.append(f"{page['name']}: {p} is {x['type']} in the contract but {', '.join(sorted(seen))} in the data")
    return out


# -- rendering -----------------------------------------------------------------------------------------------------------------------

def github_slug(text: str) -> str:
    """GitHub's anchor of a heading (the help centre makes the same, core/guides.py)."""
    text = re.sub(r"<[^>]+>", "", text).strip().lower()
    text = re.sub(r"[^\w\- ]", "", text)
    return text.replace(" ", "-")


def _heading(doc: str, start: str) -> tuple[str, str]:
    """(the document's title or the heading starting with ``start``, its anchor); raises when the document has no such heading."""
    lines = (DOCS / doc).read_text(encoding="utf-8").splitlines()
    for ln in lines:
        m = re.match(r"^(#{1,4}) (.*)$", ln)
        if not m:
            continue
        if start == "# " and m.group(1) == "#":
            return m.group(2).strip(), ""
        if start != "# " and m.group(2).startswith(start):
            return m.group(2).strip(), github_slug(m.group(2))
    raise SystemExit(f"sample_docs: {doc} has no heading starting {start!r}")


def _cell(v) -> str:
    return str(v).replace("|", "\\|").replace("\n", " ")


def _table(head: list[str], rows: list[list]) -> list[str]:
    return ["| " + " | ".join(head) + " |", "|" + "---|" * len(head)] + ["| " + " | ".join(_cell(c) for c in r) + " |" for r in rows] + [""]


def _short(v, n: int = 60) -> str:
    if isinstance(v, list):
        t = _tname(v)
        return f"[{len(v)} {'numbers' if t == 'array of numbers' else 'objects' if t == 'array of objects' else 'values'}]"
    if isinstance(v, dict):
        return "{…}"
    s = json.dumps(v, ensure_ascii=False)
    return f"`{s if len(s) <= n else s[:n - 1] + '…'}`"


def fold(v, name: str = "", keep_objects: int = KEEP_OBJECTS):
    """A record with its long arrays folded: the first elements, then one string standing for the rest."""
    if isinstance(v, dict):
        return {k: fold(x, k, keep_objects) for k, x in v.items()}
    if isinstance(v, list):
        objects = any(isinstance(x, dict) for x in v)
        keep = (KEEP_OBJECTS if name == "LEGS" else keep_objects) if objects else KEEP_SCALARS
        if len(v) <= keep + 1:
            return [fold(x, name, keep_objects) for x in v]
        t = _tname(v)
        noun = {"array of numbers": "numbers", "array of objects": "elements of the same shape", "array of dates": "dates",
                "array of strings": "strings"}.get(t, "elements")
        what = FOLD_WHAT.get(name)
        return [fold(x, name, keep_objects) for x in v[:keep]] + [f"… {len(v) - keep} more {noun}" + (f" ({what})" if what else "")]
    return v


def _type_of(s: Scan, p: str, cf: dict) -> str:
    seen = set(s.types.get(p) or [])
    numeric = (cf.get(p) or {}).get("type") in ("amount", "rate", "integer")
    if not seen:
        return {"integer": "integer", "amount": "number", "rate": "number", "date": "date (YYYY-MM-DD)", "timestamp": "timestamp (ISO-8601)",
                "boolean": "boolean", "vector": "array of numbers", "record": "object", "record list": "array of objects",
                "text list": "array of strings", "id list": "array of strings", "date list": "array of dates", "list": "array"
                }.get((cf.get(p) or {}).get("type"), "string") + " (the contract's type; always empty in the test data)"
    if "numeric string" in seen:
        if numeric and seen <= {"integer", "number", "numeric string"}:
            return ("number" if seen & {"integer", "number"} else "number written as text") + \
                (", some rows write it as text (`\"14.2486\"`)" if seen & {"integer", "number"} else "")
        seen = (seen - {"numeric string"}) | {"string"}
    if seen == {"integer", "number"}:
        seen = {"number"}
    if len(seen) == 1:
        t = next(iter(seen))
    elif seen <= {"string", "date", "timestamp"}:
        t = "string"
    elif seen:
        t = " or ".join(sorted(seen))
    else:
        t = "always empty"
    return {"date": "date (YYYY-MM-DD)", "timestamp": "timestamp (ISO-8601)"}.get(t, t)


def _presence(s: Scan, p: str, cf: dict) -> str:
    x = cf.get(p)
    parent = p.rsplit(".", 1)[0] if "." in p else ""
    n, of = s.present[p], s.objects[parent] or 1
    where = "row" if not parent else f"`{parent}` entry"
    base = x["presence"] if x else "optional"
    if n >= of:
        return base if base == "required" else f"{base} (filled in every {where})"
    pct = 100 * n / of
    share = "over 99%" if pct > 99 else "under 1%" if pct < 1 else f"{round(pct)}%"
    return f"{base} (filled in {share} of {'rows' if not parent else f'`{parent}` entries'})"


def _values(s: Scan, p: str, cf: dict) -> str:
    x = cf.get(p) or {}
    parts = []
    if x.get("enum"):
        parts.append("one of " + ", ".join(f"`{e}`" for e in x["enum"]))
    elif p in s.values and len(s.values[p]) <= MAX_VALUES and x.get("type") not in ("id",) and not set(s.types[p]) & {"date", "timestamp"} \
            and len(s.values[p]) > 1 and s.present[p] >= 2 * len(s.values[p]):
        parts.append("seen: " + ", ".join(f"`{json.dumps(v) if isinstance(v, bool) else v}`" for v, _ in sorted(s.values[p].items(), key=lambda kv: (-kv[1], str(kv[0])))))
    if x.get("unit"):
        parts.append(f"unit: {x['unit']}")
    if x.get("pattern"):
        parts.append(f"pattern `{x['pattern']}`")
    if x.get("link"):
        parts.append(f"links to `{x['link']}`")
    return "; ".join(parts) or "—"


def _meaning(p: str, cf: dict) -> str:
    if p.startswith("_meta"):
        return _META.get(p, "Part of the generator's provenance stamp; carried through, not read by Drishti.")
    x = cf.get(p)
    if not x:
        return "Not in the contract."
    d = (x.get("description") or "").strip()
    return no_release((" ".join(d.split()) if d else "Not described in the contract.") + (f" *{x['note']}*" if x.get("note") else ""))


_REL = r"\d\.\d+\.\d+"


def no_release(text: str) -> str:
    """A contract's words without its release numbers (the pages describe the data as it is, not when it changed)."""
    text = re.sub(rf"\((?:as built |since |from |in )?{_REL}(?:, | and | or )", "(", text)
    text = re.sub(rf"\s*\((?:as built |since |from |in )?{_REL}\)", "", text)
    text = re.sub(rf"^{_REL}: ", "", text)
    text = re.sub(rf"(^|[.!?]\s+)(?:since|from|as of|in) {_REL},? (\w)", lambda m: m.group(1) + m.group(2).upper(), text, flags=re.I)
    text = re.sub(rf"\s*(?:since|from|as of|in|before|until) (?:release )?{_REL}", "", text, flags=re.I)
    text = re.sub(rf"(?:, | )?{_REL}(?=[;:,.)])", "", text)
    return re.sub(r"\(\s*\)", "", text).replace(" ,", ",").replace(" .", ".").replace("( ", "(")


#: the provenance stamp the public-data and demo generators put on each record (no contract describes it: tools/contracts.py skips it)
_META = {
    "_meta": "The generator's provenance stamp, one object; optional, carried through in the stored document and not read by Drishti "
             "(the contracts leave it out). A bank's extract may omit it.",
    "_meta.source": "The source system or public source the record was built from (sec-edgar, gleif, …).",
    "_meta.dataset": "The source's data set the record was read from.",
    "_meta.generation": "The generator's format generation (always 1).",
    "_meta.retrievedAt": "The date the source data was retrieved, YYYY-MM-DD.",
    "_meta.licence": "The licence of the source data.",
}


def _paths(doc: dict, prefix: str = "") -> list[str]:
    """The non-empty fields of one record, in document order, nested ones after their parent."""
    out = []
    for k, v in doc.items():
        if _empty(v):
            continue
        p = f"{prefix}.{k}" if prefix else k
        out.append(p)
        if isinstance(v, dict):
            out += _paths(v, p)
        elif isinstance(v, list):
            sub: list[str] = []
            for x in v:
                if isinstance(x, dict):
                    for q in _paths(x, p + "[]"):
                        if q not in sub:
                            sub.append(q)
            out += sub
    return out


def _first(doc, path: str):
    """The first non-empty value of a path in one record."""
    parts = path.split(".")

    def walk(o, i):
        if i == len(parts):
            return o
        key = parts[i]
        arr = key.endswith("[]")
        key = key[:-2] if arr else key
        if not isinstance(o, dict) or _empty(o.get(key)):
            return None
        v = o[key]
        if arr:
            for x in v if isinstance(v, list) else []:
                r = walk(x, i + 1)
                if not _empty(r):
                    return r
            return None
        return walk(v, i + 1)

    return walk(doc, 0)


def field_table(s: Scan, paths: list[str], cf: dict, doc: dict) -> list[str]:
    return _table(["Field", "Type", "Required", "Meaning", "Values, format, unit", "Example"],
                  [[f"`{p}`", _type_of(s, p, cf), _presence(s, p, cf), _meaning(p, cf), _values(s, p, cf), _short(_first(doc, p))]
                   for p in paths])


def _pattern(files: list[Path]) -> str:
    names = sorted({re.sub(r"\.part-\d+", ".part-NNN", re.sub(r"\d{4}-\d{2}-\d{2}", "YYYY-MM-DD", f.name)) for f in files})
    return ", ".join(f"`{n}`" for n in names)


def _layout(pack: str, connector: str, kind: str) -> dict:
    conn = C._connector(C.manifests(PACKS), pack, connector)
    return (((conn.get("settings") or {}).get("layout") or {}).get(kind) or {})


def _key(page: dict, c: dict) -> str:
    ds = page["dataset"] or {}
    m = C.manifests(PACKS).get(page["pack"]) or {}
    ingest = ((m.get("ingest") or {}).get(page["kind"]) or {}).get("key")
    return ds.get("key") or ingest or ", ".join(c.get("key") or []) or "—"


def _cadence(page: dict, s: Scan) -> str:
    ds = page["dataset"]
    if not ds:
        return (f"There is **no committed test data** for this kind: the sample comes from the pack's samples "
                f"({', '.join(f'`{f.relative_to(ROOT)}`' for f in page['extra'])}, {s.rows} records). A bank or a public refresh delivers it "
                f"in the same JSON Lines shape.")
    if ds.get("days"):
        days = ds["days"]
        ch = [d["documents"] for d in days[1:]]
        if ch and max(ch) == 0:
            change = "the records never changed over the period, so every later day's file is empty"
        else:
            change = f"each later day's file holds only the records new or changed that day ({min(ch):,}–{max(ch):,} a day)"
        return (f"Delivered **daily**, {len(days)} business days ({days[0]['day']} to {days[-1]['day']}): the first day's file is a **full "
                f"snapshot** ({days[0]['documents']:,} records); {change}. Drishti applies a day's file by key over the day before: a record "
                f"absent from a day's file keeps its previous version, and nothing is ever deleted (an end is a changed status). "
                f"{s.rows:,} rows in all.")
    if ds.get("dated-by"):
        extra = (f" Beside the morning run, intraday updates are appended during the day (only the changed trades, `RUN_KIND` "
                 f"`INTRADAY`); the forms the test data lacks come from the pack's samples ({', '.join(f'`{f.relative_to(ROOT)}`' for f in page['extra'])})."
                 if page["extra"] else "")
        return (f"Delivered **per close of business**: one file per business date (`{ds['dated-by']}` {ds.get('first-date')}"
                f"{'' if ds.get('first-date') == ds.get('last-date') else ' to ' + str(ds.get('last-date'))} in the test data), split into "
                f"numbered parts when large; each date's file is the **full set** of that date ({ds.get('documents', s.rows):,} records), "
                f"never a change on the day before.{extra}")
    return (f"A **snapshot of public data**, captured {ds.get('captured', '')} from {ds.get('source', 'its source')}, refreshed whole by "
            f"its script (External data); {s.rows:,} records. A refresh replaces the whole snapshot.")


def _date_field(c: dict, s: Scan, ds: dict) -> str:
    d = c.get("dated-by")
    if d and d in s.keys:
        return f"`{d}` (in every record: the business date it belongs to; the lake's partition `business_date`)"
    if ds.get("days"):
        return ("none in the record: the business date is the file's date (`snapshot-` or `changes-YYYY-MM-DD`), which becomes the "
                "lake's partition `business_date`")
    if ds:
        return ("none in the record: a snapshot of public data, its file named by the capture date; the demo lake serves the same "
                "records on every business date (partition `business_date`)")
    return "none in the record: the pack's samples are served on every business date (partition `business_date`)"


def _intro(page: dict, c: dict, s: Scan) -> str:
    desc = " ".join((c.get("description") or "").split())
    grain = " ".join((c.get("grain") or "").split())
    ds = page["dataset"] or {}
    conn = ds.get("connector") or ((c.get("storage") or {}).get("connector")) or "—"
    table = (c.get("storage") or {}).get("table")
    return (f"{desc.rstrip('.')}. {grain} Read by the **{page['pack']}** pack's connector `{conn}`"
            + (f" (table `{table}`)" if table else "") + ".")


def _links(page: dict, c: dict) -> list[str]:
    anchor = C._anchor(c) if c else ""
    out = [f"- Data contract: [{c.get('mnemonic', '')} in the {page['pack']} pack's contracts](../../packs/{page['pack']}.md#{anchor}) "
           f"(`config/packs/{page['pack']}/contracts/{page['kind']}.contract.yaml`, the source of every meaning on this page)."]
    for doc, start in LINKS.get(page["kind"], []) + PACK_LINKS.get(page["pack"], []):
        title, a = _heading(doc, start)
        title = re.sub(r"\s*\([^)]*\d\.\d+\.\d+[^)]*\)", "", title)
        out.append(f"- [{title}](../../../{doc}{'#' + a if a else ''})")
    out.append("- [The test data and the demo lakes](../../../connectors/DEMO_DATA.md#the-test-data-and-the-demo-lakes-the-default) and "
               "the [index of every data sample](../README.md).")
    return list(OrderedDict.fromkeys(out))


def _checklist(page: dict, c: dict, s: Scan, key: str) -> list[str]:
    cf = contract_fields(c)
    required = [p for p in cf if "." not in p and cf[p]["presence"] == "required"]
    always = [p for p in s.order if "." not in p and s.present[p] >= s.rows and not p.startswith("_meta")]
    dated = c.get("dated-by") or "—"
    out = [f"- **Identity:** one record per `{key}`" + (f" per `{dated}`" if dated not in ("—", "", None) else "") +
           "; the key is text, unique within the date.",
           "- **Never null:** " + (", ".join(f"`{p}`" for p in required) if required else "the key") + " (the contract's required fields)"
           + (f"; every row of the test data also fills {', '.join(f'`{p}`' for p in always if p not in required)}"
              if [p for p in always if p not in required] else "") + ".",
           "- **Format:** JSON Lines, UTF-8, one object a line, gzip; field names exactly as shown (UPPER_SNAKE); dates `YYYY-MM-DD`; "
           "numbers as JSON numbers, never strings; a missing value is `null` or absent (an empty string is read as empty).",
           "- **Arrays:** keep the order shown (vectors in the scenario set's or the run's order); nested objects as JSON, never flattened."]
    lines = list(CHECKLIST.get(page["kind"], []))
    if page["kind"] in ("pnl-vector", "scenario-pv"):
        lines = CHECKLIST["_var"] + lines
    if page["dataset"] and page["dataset"].get("days"):
        lines.append("Each day: the records new or changed that day (or all of them); Drishti keeps the rest from the day before.")
    elif page["dataset"] and page["dataset"].get("dated-by"):
        lines.append(f"Each date: the full set of the date in one or more files, every record with its `{dated}`; a later full file of the "
                     "same date restates it.")
    for r in c.get("validation") or []:          # required, enum and link are in the tables above
        if r["rule"] == "unique":
            lines.append(f"Unique: {', '.join(f'`{k}`' for k in r['key'])} per {r['per'].replace('BUSINESS_DATE', '`BUSINESS_DATE`')}.")
        elif r["rule"] == "pattern":
            lines.append(f"`{r['field']}` matches `{r['pattern']}`.")
        elif r["rule"] == "range":
            lines.append(f"`{r['field']}` is at least {r['min']}.")
        elif r["rule"] == "vector-length":
            lines.append(f"`{r['field']}` has the same length on {r['same']}.")
    out += [f"- {no_release(x)}" for x in lines]
    return out


def render(page: dict, s: Scan) -> str:
    c = contract(page["pack"], page["kind"])
    cf = contract_fields(c)
    ds = page["dataset"] or {}
    key = _key(page, c)
    mn = c.get("mnemonic", "")
    title = (f"{page['name'].split('/')[1].upper()} trades: trade ({mn})" if page["kind"] == "trade"
             else f"{c.get('title') or page['kind']}: {page['kind']} ({mn})")
    out = [C.R.licence_comment(), f"<!-- Generated by {GENERATOR} from the test data and config/packs/{page['pack']}/contracts/"
           f"{page['kind']}.contract.yaml. Edit the contract or the tool, not this file. -->", "", f"# {title}", "",
           no_release(_intro(page, c, s)) + " " + _cadence(page, s), ""]
    lay = _layout(page["pack"], ds.get("connector") or (c.get("storage") or {}).get("connector"), page["kind"])
    folder = f"data/testdata/{page['name']}/" if ds else ", ".join(f"`{f.relative_to(ROOT)}`" for f in page["extra"])
    out += ["## File layout", ""]
    out += _table(["", ""], [
        ["Path", (f"`{folder}` + " + _pattern(page["files"])) if ds else folder],
        ["Format", "JSON Lines: UTF-8, one JSON object a line, gzip-compressed (`.jsonl.gz`)" if ds else "JSON Lines: UTF-8, one JSON object a line"],
        ["Key field", f"`{key}`"],
        ["Date field", _date_field(c, s, ds)],
        ["Promoted columns", ", ".join(f"`{x}`" for x in lay.get("columns") or []) or "none (the whole record is kept as `doc`)"],
        ["Vector fields", ", ".join(f"`{x}`" for x in lay.get("vectors") or []) or "none"],
        ["Partitioned by", ", ".join(["`business_date`"] + [f"`{x}`" for x in ds.get("partition-by") or []])],
        ["A day's file", "a full snapshot on the first day, then only the changed records" if ds.get("days") else
         "the full set of its date" if ds.get("dated-by") else "the whole snapshot" if ds else "no committed files"],
    ])
    subs = s.subs
    labels = [k for k, _ in sorted(subs.items(), key=lambda kv: (-kv[1]["rows"], str(kv[0])))]
    many = page["name"] in SUBTYPES and len(labels) > 1          # one sub-type only: a plain page
    sample_paths = {k: _paths(subs[k]["doc"]) for k in labels}
    if many:
        how = SUBTYPES[page["name"]][0]
        out += ["## Sub-types", "",
                f"The rows come in {len(labels)} sub-types, recognised by {how}. Each has its own most complete record below; the fields every "
                "sub-type's record has are in one shared table first.", ""]
        out += _table(["Sub-type", "Recognised by", "Rows", "Sample from", "Section"],
                      [[f"**{k}**", _recognise(page["name"], subs[k]["doc"]), f"{subs[k]['rows']:,}",
                        "test data" if subs[k]["source"].startswith("data/") else f"`{subs[k]['source']}`",
                        f"[{k}](#{github_slug(k)})"] for k in labels])
        common = [p for p in sample_paths[labels[0]] if all(p in sample_paths[k] for k in labels)]
        out += ["## Fields every sub-type has", "", f"The example is from the **{labels[0]}** record.", ""]
        out += field_table(s, common, cf, subs[labels[0]]["doc"])
        out += ["## One record of each sub-type", "",
                "Each record is real, the most complete of its sub-type, pretty-printed with two-space indents. **A string such as "
                "`\"… 506 more numbers\"` inside an array marks a fold**: it stands for the elements left out and is not part of the data. "
                "Below each record, the fields it has beyond the shared table.", ""]
        for i, k in enumerate(labels):
            doc = subs[k]["doc"]
            out += [f"### {k}", "", f"{subs[k]['rows']:,} rows; recognised by {_recognise(page['name'], doc)}"
                    + ("" if subs[k]["source"].startswith("data/") else f"; the record is from `{subs[k]['source']}`") + ".", "",
                    "```json", json.dumps(fold(doc, keep_objects=KEEP_OBJECTS if i == 0 else 1), indent=2, ensure_ascii=False), "```", ""]
            own = [p for p in sample_paths[k] if p not in common]
            out += (["Its fields beyond the shared table:", ""] + field_table(s, own, cf, doc)) if own else \
                ["No fields beyond the shared table.", ""]
    else:
        doc = subs[labels[0]]["doc"]
        out += ["## One sample record", "",
                f"A real record, the most complete of the {s.rows:,} (the one with the most non-empty fields), pretty-printed with "
                "two-space indents. **A string such as `\"… 506 more numbers\"` inside an array marks a fold**: it stands for the elements "
                "left out and is not part of the data.", "", "```json", json.dumps(fold(doc), indent=2, ensure_ascii=False), "```", "",
                "## Field reference", "", "Every field of the record above, nested ones as dotted paths (`[]` marks an array of objects). "
                "*Required* is the contract's; *in N%* is how often the test data fills it.", ""]
        out += field_table(s, sample_paths[labels[0]], cf, doc)
    shown = {p for k in labels for p in sample_paths[k]}
    rest = [p for p in s.order if p not in shown]
    out += ["## Fields in other rows", ""]
    if rest:
        out += [f"Fields no record above has, found by reading all {s.rows:,} rows, with how many rows fill them.", ""]
        out += _table(["Field", "Type", "Rows with it", "Meaning", "Example"],
                      [[f"`{p}`", _type_of(s, p, cf), f"{s.row_present[p]:,} of {s.rows:,}" if s.row_present[p] else f"always empty ({s.keys[p]:,} rows hold the key)",
                        _meaning(p, cf), _short(s.example.get(p)) if p in s.example else "—"] for p in rest])
    else:
        out += [f"None: all {s.rows:,} rows were read, and every field any of them has is in the record{'s' if many else ''} above.", ""]
    out += ["## For the production query", ""] + _checklist(page, c, s, key) + [""]
    out += ["## See also", ""] + _links(page, c) + [""]
    return "\n".join(out).rstrip() + "\n"


def _recognise(name: str, doc: dict) -> str:
    if name in ("market-risk/pnl-vector", "market-risk/scenario-pv"):
        bits = [f"`VALUE_TYPE` `{doc.get('VALUE_TYPE')}`", f"`GRAIN` `{doc.get('GRAIN')}`", f"`RUN_KIND` `{doc.get('RUN_KIND') or 'FULL'}`"]
        if str(doc.get("TRADE_STATUS") or "").upper() in ("CANCELLED", "CANCELED"):
            bits.append(f"`TRADE_STATUS` `{doc.get('TRADE_STATUS')}`")
        return ", ".join(bits)
    field = re.search(r"`([A-Z_]+)`", SUBTYPES[name][0]).group(1)
    return f"`{field}` `{doc.get(field)}`" if doc.get(field) else f"`{field}` empty"


# -- the index -----------------------------------------------------------------------------------------------------------------------

def _group_of(page: dict) -> int:
    for i, (_, _, members) in enumerate(GROUPS):
        if members and page["name"] in members:
            return i
    if page["pack"] == "market-data":
        return 5
    if page["pack"] in PUBLIC:
        return 6
    if page["pack"] == "compute-demo":
        return 7
    raise SystemExit(f"sample_docs: {page['name']} is in no group of the index")


def render_index(all_pages: list[dict], scans: dict) -> str:
    out = [C.R.licence_comment(), f"<!-- Generated by {GENERATOR} from the test data and the contracts. Edit those or the tool, not this file. -->", "",
           "# Data samples, field by field", "",
           "One page per data type: a real record of the committed test data, pretty-printed, and every field of it explained from the "
           "type's data contract, so a production query or ETL job can emit exactly this JSON Lines shape. Each page gives the file layout "
           "(path, key and date fields, promoted and vector columns, snapshot or changes), one fully explained record of each sub-type, the "
           "fields only other rows have, and a checklist for the query author. The meanings come from the contracts "
           "([packs/README.md](../packs/README.md)); the pages are generated by `tools/testdata/sample_docs.py` and checked by "
           "`tools/test_sample_docs.py`, so they follow the data and the contracts.", "",
           f"{len(all_pages)} pages: what a bank produces, grouped as it produces them; then the kinds Drishti computes, which you never "
           "send. *Needed for* says which kinds VaR and exposure cannot work without.", ""]
    for i, (title, who, _) in enumerate(GROUPS):
        rows = []
        for p in sorted((p for p in all_pages if _group_of(p) == i), key=lambda p: (GROUPS[i][2] or []).index(p["name"]) if GROUPS[i][2] and p["name"] in GROUPS[i][2] else 999):
            c = contract(p["pack"], p["kind"])
            ds = p["dataset"] or {}
            pattern = (f"`{p['name']}/`, " + ("a snapshot, then daily changes" if ds.get("days") else "one full file set per date"
                                               if ds.get("dated-by") else "one snapshot") if ds else "none: the pack's samples")
            desc = no_release(" ".join((c.get("description") or c.get("title") or "").split()))
            rows.append([f"[`{p['kind']}`" + (f" {p['name'].split('/')[1].upper()}" if p["kind"] == "trade" else "") + f"]({p['name']}.md)",
                         f"`{c.get('mnemonic', '')}`", pattern, f"{scans[p['name']].rows:,}", NEEDED.get(p["kind"], "—"), desc])
        if not rows:
            continue
        out += [f"## {title}", "", f"**{who}.** Test data under `data/testdata/`" + (", or the pack's samples." if i >= 6 else "."), ""]
        if i == 2:
            out += ["During the day the same scenario-PV and P&L-vector records may also arrive on Kafka (the PV topic, connector "
                    "`pv-updates`): each message's value is JSON Lines of exactly these records.", ""]
        out += _table(["Kind", "Command", "Test data", "Rows", "Needed for", "What one record is"], rows)
    out += ["## Computed by Drishti: you do not produce these", "",
            "These kinds have a contract but no page: Drishti computes or derives them from the kinds above when asked (or when a load "
            "lands), and a bank never sends them.", ""]
    out += _table(["Pack", "Kind", "Command", "Class", "Where it comes from"],
                  [[x["pack"], f"`{x['kind']}`", f"`{x['mnemonic']}`", "Drishti computes this" if x["class"] == "computed" else
                    "Drishti derives this" if x["class"] == "drishti-owned" else "fetched by Drishti",
                    no_release(" ".join((x["description"] or x["title"]).split()))] for x in computed()])
    return "\n".join(out).rstrip() + "\n"


# -- the commands --------------------------------------------------------------------------------------------------------------------

def build(scans: dict[str, Scan] | None = None) -> dict[Path, str]:
    """Every page and the index, in memory."""
    all_pages = pages()
    scans = scans or scan_all(all_pages)
    files = {OUT / f"{p['name']}.md": render(p, scans[p["name"]]) for p in all_pages}
    files[OUT / "README.md"] = render_index(all_pages, scans)
    return files


def audit(scans: dict[str, Scan] | None = None) -> list[str]:
    all_pages = pages()
    scans = scans or scan_all(all_pages)
    return [x for p in all_pages for x in audit_one(p, scans[p["name"]])]


def check(files: dict[Path, str] | None = None) -> list[str]:
    files = files or build()
    out = [f"{f.relative_to(ROOT)}: stale (run python3 {GENERATOR})" for f, text in files.items()
           if not f.is_file() or f.read_text(encoding="utf-8") != text]
    out += [f"{f.relative_to(ROOT)}: no data type has this page (remove it)" for f in sorted(OUT.rglob("*.md")) if f not in files]
    return out


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(prog="sample_docs.py", description=__doc__.split("\n\n")[0])
    ap.add_argument("command", nargs="?", choices=["write", "audit"], default="write")
    ap.add_argument("--check", action="store_true", help="fail when a page is stale or left over (write nothing)")
    a = ap.parse_args(argv)
    if a.command == "audit":
        probs = audit()
        print("\n".join(probs) or "every contract matches all its committed test data")
        return 1 if probs else 0
    files = build()
    if a.check:
        probs = check(files)
        print("\n".join(probs) or f"{len(files)} pages current")
        return 1 if probs else 0
    for f, text in files.items():
        f.parent.mkdir(parents=True, exist_ok=True)
        if not f.is_file() or f.read_text(encoding="utf-8") != text:
            f.write_text(text, encoding="utf-8")
    for f in sorted(OUT.rglob("*.md")):
        if f not in files:
            f.unlink()
    print(f"{len(files)} pages under {OUT.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
