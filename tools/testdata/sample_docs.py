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

"""The data of every contract: the example record of each data type, and the audit of every contract against ALL the committed test data.

    python3 tools/testdata/sample_docs.py              the audit: fields missing from a contract, contract fields no row has, required
                                                       fields absent, types that differ (the same as `audit`)
    python3 tools/contracts.py docs [--check]          writes the example of each kind into docs/contracts/packs/<pack>.md (this module's
                                                       examples(): the most complete real record of the data type, long arrays folded)

A data type is a test-data folder (data/testdata/<domain>/<table>/: its dated files and tars, tools/lake/feedfiles.py) or, for a kind that
has samples only, the pack's samples. Records are read, never written. A meaning missing in a contract page is fixed in the contract
(modules/<module>/packs/<pack>/contracts/<kind>.contract.yaml), never here.
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
sys.path.insert(0, str(ROOT / "tools" / "lake"))
import feedfiles as FF  # noqa: E402  (tools/lake/feedfiles.py: our dated feed files and tars)

_spec = importlib.util.spec_from_file_location("drishti_contracts_for_samples", ROOT / "tools" / "contracts.py")
C = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(C)

DATE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
STAMP = re.compile(r"^\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}(:\d{2}(\.\d+)?)?(Z|[+-]\d{2}:?\d{2})?$")   # ISO-8601 only
NUMERIC = re.compile(r"^-?\d+(\.\d+)?([eE][-+]?\d+)?$")
MAX_TRACK = 60            # distinct values kept per field (for the enum check)
KEEP_OBJECTS = 2          # array elements shown before a fold: objects
KEEP_SCALARS = 3          # numbers and strings

# -- sub-types and folds -------------------------------------------------------------


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
}

#: rows beyond the committed test data that show a form the test data does not hold (the pack's samples), by folder
EXTRA_SOURCES = {
    "market-risk/pnl-vector": ["modules/banking/packs/market-risk/samples/pnl-vector.jsonl"],
    "market-risk/scenario-pv": ["modules/banking/packs/market-risk/samples/scenario-pv.jsonl",
                                "modules/banking/packs/market-risk/samples/intraday/scenario-pv.2026-09-30.jsonl"],
    # a trade edited on the blotter carries UPDATED_BY (who last changed it): Drishti writes it, the booking systems never send it
    **{f"trades/{s}": [f"tools/testdata/edited/trade-{s}.jsonl"] for s in ("endur", "calypso", "murex")},
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


def _lines(f):
    """The documents of a file (a Path) or of a feed file's part (feedfiles.Part: a dated file, or a tar's member)."""
    if hasattr(f, "lines"):
        for line in f.lines():
            yield json.loads(line)
        return
    op = gzip.open if f.suffix == ".gz" else open
    with op(f, "rt", encoding="utf-8") as fh:
        for line in fh:
            if line.strip():
                yield json.loads(line)


def _label(f) -> str:
    """A file's name for the pages: its path under the repository (a tar's member after !/)."""
    p = getattr(f, "path", f)
    rel = str(p.relative_to(ROOT)) if p.is_relative_to(ROOT) else str(p)
    return rel + (f"!/{f.member}" if getattr(f, "member", None) else "")


def _size(f) -> int:
    return getattr(f, "path", f).stat().st_size


def manifest() -> dict:
    return yaml.safe_load((TESTDATA / "testdata.yaml").read_text(encoding="utf-8")) or {}


def _sample_files(pack: str, kind: str) -> list[Path]:
    d = C.PP.pack_dir(pack, PACKS) / "samples"
    return sorted(d.glob(f"{kind}.jsonl")) + sorted(d.glob(f"{kind}.part-*.jsonl"))


def pages() -> list[dict]:
    """Every data type that gets a page: each test-data folder, then each kind a bank or a public source produces that has samples but no
    test data. {name (domain/kind), pack, kind, dataset (the manifest's entry or None), files, extra}."""
    out, have = [], set()
    for d in manifest().get("datasets") or []:
        if not d.get("folder") or d.get("native"):       # a mocked source system's native style (src_*.py) has no sample page
            continue
        folder = TESTDATA / d["folder"]
        got = FF.read_table(folder) if folder.is_dir() else {}
        files = [p for parts in got.values() for p in parts]
        if d.get("dated-by") and d.get("last-date"):        # a dated dataset of several dates (the VaR data's three): its newest date's
            files = got.get(str(d["last-date"])) or files
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
    for f, c in C.planned(PACKS):                # planned inputs with demo data from their generator (not in the manifest)
        files = C.planned_demo_data(c, TESTDATA)
        if files and (c["pack"], c["kind"]) not in have:
            dated = sorted(m.group(0) for x in files for m in [re.search(r"\d{4}-\d{2}-\d{2}", x.name)] if m)
            ds = {"folder": f"{c['pack']}/{c['kind']}", "planned": True, "dated-by": c.get("dated-by"),
                  "connector": C.default_connector(c), "key": ", ".join(c.get("key") or []),
                  "first-date": dated[0] if dated else None, "last-date": dated[-1] if dated else None}
            out.append({"name": ds["folder"], "pack": c["pack"], "kind": c["kind"], "dataset": ds, "files": files, "extra": []})
    return out


def scan(page: dict) -> Scan:
    cf = contract_fields(contract(page["pack"], page["kind"]))
    s = Scan(SUBTYPES.get(page["name"], (None, None))[1], {p: x["pattern"] for p, x in cf.items() if x.get("pattern")})
    for f in page["files"]:
        for doc in _lines(f):
            s.add(doc, _label(f))
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
    order = sorted(all_pages, key=lambda p: -sum(_size(f) for f in p["files"] + p["extra"]))
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
    if ds.get("folder") and not ds.get("planned") and f"data/testdata/{ds['folder']}" not in ((c.get("binding-hint") or {}).get("test-data") or []):
        out.append(f"{page['name']}: the contract's binding-hint.test-data does not name data/testdata/{ds['folder']}")
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


# -- the index -----------------------------------------------------------------------------------------------------------------------


# -- the commands --------------------------------------------------------------------------------------------------------------------

def audit(scans: dict[str, Scan] | None = None) -> list[str]:
    all_pages = pages()
    scans = scans or scan_all(all_pages)
    return [x for p in all_pages for x in audit_one(p, scans[p["name"]])]


MAX_ROWS = 3000           # rows read from a data type's first file to find its most complete record


def example(page: dict) -> dict | None:
    """One data type's example: the most complete of the first MAX_ROWS records (the one with the most non-empty fields; the first
    such on a tie), long arrays folded. Every record is read, never written."""
    best, score, n = None, -1, 0
    for f in page["files"][:1] + page["extra"][:1]:
        for doc in _lines(f):
            n += 1
            if n > MAX_ROWS:
                break
            if len(_paths(doc)) > score:
                best, score = doc, len(_paths(doc))
        if best is not None:
            break
    return fold(best) if best is not None else None


def examples() -> dict[str, dict]:
    """Data type name (test-data folder <domain>/<table>, or <pack>/<kind> for a kind that has samples only) -> its example record."""
    out = {}
    for p in pages():
        e = example(p)
        if e is not None:
            out[p["name"]] = {"pack": p["pack"], "kind": p["kind"], "record": e,
                              "source": (f"data/testdata/{p['name']}" if p["dataset"] else ", ".join(_label(f) for f in p["extra"][:1]))}
    return out


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(prog="sample_docs.py", description=__doc__.split("\n\n")[0])
    ap.add_argument("command", nargs="?", choices=["audit"], default="audit")
    a = ap.parse_args(argv)
    probs = audit()
    print("\n".join(probs) or "every contract matches all its committed test data")
    return 1 if probs else 0


if __name__ == "__main__":
    sys.exit(main())
