#!/usr/bin/env python3
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

"""The field map of a new source system onto an existing kind (`drishti.py pack new --source-for KIND`; the Pack designer's second mode).

Every record of every file is read once (`scan`): per native field its rows, JSON types, ISO dates and timestamps, numbers, up to
DISTINCT distinct values with their counts, value shapes and prefixes. `propose` scores every native field against every field of the
kind's contract (`contracts/<kind>.contract.yaml` of the installed packs) by four kinds of evidence, then assigns them one to one, the
surest first:

    name     tokens of the two names after the abbreviations a booking system uses (cpty, ccy, dt, txn, eff ...), and their spelling
    type     the contract's type against what the values are (dates, timestamps, numbers, text, arrays of records)
    values   the contract's enum (exactly, or through the usual synonyms: PAY -> B, CXL -> CANCELLED), the example's prefix and shape
    keys     a key field of the contract wants a native field unique over every row

Each pair has a confidence (0..1) and the reasons in words; the required fields left without a native field and the native fields left
without a canonical one are listed. `apply` is the reference reading of a map (the tests and the designer's preview): a canonical record
from a native one. The map file (`mappings/<sys>-<kind>.yaml`, written by `map_yaml`) is the connector's `map:`.
"""
from __future__ import annotations

import difflib
import gzip
import json
import pathlib
import re
from collections import Counter

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parents[1]
DISTINCT = 200                          # distinct values kept per field (counts stay exact for those; the rest are counted as "more")
SEEN = 20_000_000                       # uniqueness is checked over up to this many rows of a field
THRESHOLD = 0.35                        # the lowest confidence proposed
SURE = 0.75                             # at or above: sure; below: to check
OPTIONAL_FLOOR = 0.45                   # an optional canonical field is mapped only at or above this; below, it stays unmapped (candidates under others)
ISO_DATE = re.compile(r"^\d{4}-\d\d-\d\d$")
ISO_TS = re.compile(r"^\d{4}-\d\d-\d\d[T ]\d\d:\d\d")
NUMERIC = re.compile(r"^-?\d+(\.\d+)?([eE][-+]?\d+)?$")
SPLIT = re.compile(r"[A-Z]+(?=[A-Z][a-z])|[A-Z]?[a-z]+|[A-Z]+|\d+")
STOP = {"of", "the", "code", "flag", "a", "an", "in", "field", "value"}
# a booking system's abbreviation -> the words of the canonical names
WORDS = {
    "cpty": ["counterparty"], "ctpy": ["counterparty"], "cp": ["counterparty"], "party": ["counterparty"], "cparty": ["counterparty"],
    "ccy": ["currency"], "cur": ["currency"], "curr": ["currency"], "dt": ["date"], "dte": ["date"], "day": ["date"],
    "ts": ["time"], "tstamp": ["time"], "timestamp": ["time"], "txn": ["transaction"], "trans": ["transaction"], "tx": ["transaction"],
    "ref": ["id"], "no": ["id"], "num": ["id"], "nbr": ["id"], "number": ["id"], "key": ["id"], "identifier": ["id"], "deal": ["trade"],
    "eff": ["start"], "effective": ["start"], "begin": ["start"], "end": ["maturity"], "mat": ["maturity"], "mty": ["maturity"],
    "expiry": ["maturity"], "termination": ["maturity"], "settle": ["settlement"], "stl": ["settlement"], "settl": ["settlement"],
    "dir": ["buy", "sell"], "direction": ["buy", "sell"], "side": ["buy", "sell"], "payrec": ["buy", "sell"], "buysell": ["buy", "sell"],
    "notional": ["quantity", "notional"], "amt": ["quantity"], "amount": ["quantity"], "qty": ["quantity"], "nominal": ["quantity"],
    "px": ["price"], "rate": ["price", "rate"], "coupon": ["price"], "lifecycle": ["status"], "state": ["status"], "stat": ["status"],
    "prod": ["product"], "typ": ["type"], "subtype": ["sub", "type"], "book": ["portfolio"], "folio": ["portfolio"], "pf": ["portfolio"],
    "le": ["legal", "entity"], "entity": ["legal", "entity"], "legalentity": ["legal", "entity"], "dealer": ["trader"], "user": ["trader"],
    "ver": ["version"], "rev": ["version"], "revision": ["version"], "modified": ["source", "update", "time"], "updated": ["source", "update", "time"],
    "upd": ["update"], "changed": ["source", "update", "time"], "lastmodified": ["source", "update", "time"], "last": ["update"],
    "pkg": ["package"], "dvp": ["delivery", "versus", "payment"], "asset": ["product", "group"], "class": ["group"], "assetclass": ["product", "group"],
    "country": ["country"], "cntry": ["country"], "booked": ["input"], "entered": ["input"], "capture": ["input"], "captured": ["input"],
    "desk": ["group"], "strk": ["strike"], "opt": ["option"], "callput": ["option", "type"], "leg": ["legs"], "evt": ["events"], "event": ["events"],
    "ext": ["external"], "int": ["internal"], "branch": ["transit"], "sys": ["system"], "src": ["source"],
}
# the usual words booking systems use for an enum's values: canonical value -> native spellings
VALUES = {
    "B": ["B", "BUY", "BOUGHT", "PAY", "PAYER", "P", "LONG", "L", "BUYER"],
    "S": ["S", "SELL", "SOLD", "REC", "RECEIVE", "RECEIVER", "R", "SHORT", "SELLER"],
    "VALIDATED": ["LIVE", "ACTIVE", "CONFIRMED", "VERIFIED", "VALID", "OPEN", "DONE", "BOOKED"],
    "AMENDED": ["AMD", "AMEND", "AMENDED", "MODIFIED", "MOD", "CHANGED"],
    "CANCELLED": ["CXL", "CNX", "CANCEL", "CANCELED", "CANCELLED", "DEAD", "DELETED", "KILLED"],
    "MATURED": ["MAT", "MATURED", "EXPIRED", "CLOSED", "TERMINATED"],
    "NEW": ["NEW", "PENDING", "INITIAL", "N"],
    "AMEND": ["AMD", "AMEND", "MODIFY", "MOD", "A"],
    "YES": ["Y", "YES", "TRUE", "1", "T"], "NO": ["N", "NO", "FALSE", "0", "F"],
    "Y": ["Y", "YES", "TRUE", "1", "T"], "N": ["N", "NO", "FALSE", "0", "F"],
    "External": ["EXT", "EXTERNAL", "E", "CLIENT", "STREET"], "Portfolio": ["INT", "INTERNAL", "I", "INTERCO", "PORTFOLIO", "HOUSE"],
    "Call": ["C", "CALL"], "Put": ["P", "PUT"], "Payer": ["PAYER", "PAY"], "Receiver": ["RECEIVER", "REC"],
}


# ---- reading every record ----------------------------------------------------------------------------------------------------------

class FieldStat:
    """One native field over every row."""
    __slots__ = ("rows", "types", "dates", "stamps", "numbers", "values", "more", "prefixes", "shapes", "lo", "hi", "elements", "seen")

    def __init__(self):
        self.rows = self.dates = self.stamps = self.numbers = self.more = 0
        self.types: Counter = Counter()
        self.values: Counter = Counter()
        self.prefixes: Counter = Counter()
        self.shapes: Counter = Counter()
        self.lo = self.hi = None
        self.elements: Counter = Counter()             # an array of records: its elements' field names
        self.seen: set | None = set()                  # hashes of the values while every one is new (uniqueness over every row)

    def add(self, v) -> None:
        self.rows += 1
        t = type(v)
        if t is bool:
            self.types["boolean"] += 1
            v = str(v).upper()
        elif t in (int, float):
            self.types["number"] += 1
            self.numbers += 1
            self.lo = v if self.lo is None else min(self.lo, v)
            self.hi = v if self.hi is None else max(self.hi, v)
        elif t is list:
            self.types["array"] += 1
            for x in v:
                if type(x) is dict:
                    self.elements.update(x.keys())
            return
        elif v is None:
            self.types["null"] += 1
            return
        else:
            v = str(v)
            self.types["string"] += 1
            if ISO_DATE.match(v):
                self.dates += 1
            elif ISO_TS.match(v):
                self.stamps += 1
            elif NUMERIC.match(v):
                self.numbers += 1
            m = re.match(r"^([A-Za-z]{1,6}[-_])", v)
            if m:
                self.prefixes[m.group(1).upper()] += 1
            self.shapes[shape(v)] += 1
        key = v if isinstance(v, str) else repr(v)
        if self.seen is not None:
            h = hash(key)
            if h in self.seen or len(self.seen) >= SEEN:
                self.seen = None
            else:
                self.seen.add(h)
        if key in self.values or len(self.values) < DISTINCT:
            self.values[key] += 1
        else:
            self.more += 1

    def distinct(self) -> int:
        return len(self.values) + (1 if self.more else 0)

    def unique(self) -> bool:
        return self.seen is not None and self.rows > 0 and not self.types.get("null") and not self.types.get("array")

    def kind(self) -> str:
        """What the values are: date, timestamp, number, text, boolean, array or empty."""
        filled = self.rows - self.types.get("null", 0)
        if not filled:
            return "empty"
        if self.types.get("array", 0) * 2 > filled:
            return "array"
        if self.types.get("boolean", 0) * 2 > filled:
            return "boolean"
        if self.dates >= 0.9 * filled:
            return "date"
        if self.stamps >= 0.9 * filled or (self.stamps + self.dates) >= 0.9 * filled and self.stamps:
            return "timestamp"
        if self.numbers >= 0.95 * filled:
            return "number"
        return "text"

    def as_dict(self, top: int = 8) -> dict:
        return {"rows": self.rows, "kind": self.kind(), "distinct": self.distinct(), "unique": self.unique(),
                "values": [[k, n] for k, n in self.values.most_common(top)], "min": self.lo, "max": self.hi,
                "elements": sorted(self.elements)[:30]}


def shape(v: str) -> str:
    s = re.sub(r"[A-Za-z]+", "A", v)
    return re.sub(r"\d+", "9", s)[:16]


def open_text(f: pathlib.Path):
    return gzip.open(f, "rt", encoding="utf-8") if f.name.endswith(".gz") else open(f, encoding="utf-8")


def files_of(inputs, recursive: bool = True) -> list[pathlib.Path]:
    out: list[pathlib.Path] = []
    for i in inputs:
        p = pathlib.Path(i)
        if p.is_dir():
            it = p.rglob("*") if recursive else p.iterdir()
            out += sorted(f for f in it if f.is_file() and re.search(r"\.(jsonl|ndjson)(\.gz)?$", f.name))
        elif p.is_file():
            out.append(p)
    return out


def scan(inputs, progress=None) -> dict:
    """Every record of every file: {rows, files, bad, fields: {path: FieldStat}} (dotted paths into objects; arrays as one field)."""
    fields: dict[str, FieldStat] = {}
    rows = bad = 0
    files = files_of(inputs)

    def fold(obj: dict, prefix: str = "", depth: int = 0) -> None:
        for k, v in obj.items():
            p = prefix + k
            if type(v) is dict and depth < 4:
                fold(v, p + ".", depth + 1)
                continue
            s = fields.get(p)
            if s is None:
                s = fields[p] = FieldStat()
            s.add(v)

    for f in files:
        with open_text(f) as fh:
            for line in fh:
                line = line.strip()
                if not line:
                    continue
                try:
                    doc = json.loads(line)
                except ValueError:
                    bad += 1
                    continue
                if type(doc) is not dict:
                    bad += 1
                    continue
                rows += 1
                fold(doc)
                if progress and rows % 100_000 == 0:
                    progress(f"  {rows:,} rows")
    return {"rows": rows, "files": len(files), "bad": bad, "fields": fields}


# ---- the kinds and their contracts ----------------------------------------------------------------------------------------------------

def contracts() -> dict[str, pathlib.Path]:
    """kind -> its contract file, from the installed packs (config/packs) and the modules' packs."""
    out: dict[str, pathlib.Path] = {}
    for f in sorted(ROOT.glob("config/packs/**/contracts/*.contract.yaml")) + sorted(ROOT.glob("modules/*/packs/**/contracts/*.contract.yaml")):
        out.setdefault(f.name[: -len(".contract.yaml")], f)
    return out


def contract(kind: str) -> dict:
    import yaml
    f = contracts().get(kind)
    if f is None:
        raise SystemExit(f"no contract for the kind '{kind}' in the installed packs (config/packs/**/contracts/{kind}.contract.yaml)")
    c = yaml.safe_load(f.read_text(encoding="utf-8")) or {}
    c["_file"] = str(f.relative_to(ROOT))
    c["_pack"] = f.parent.parent.name
    return c


# ---- names ------------------------------------------------------------------------------------------------------------------------

def tokens(name: str, expand: bool = True) -> list[str]:
    last = name.split(".")[-1]
    parts = [p.lower() for p in SPLIT.findall(last.replace("_", " ").replace("-", " ").replace(" ", "_")) if p]
    if not parts:
        parts = [last.lower()]
    whole = "".join(parts)
    out: list[str] = []
    if expand and whole in WORDS and len(parts) > 1:
        out += WORDS[whole]
    for p in parts:
        out += WORDS.get(p, [p]) if expand else [p]
    return [t for t in out if t not in STOP] or out


def name_score(native: str, canon: str) -> float:
    n, c = set(tokens(native)), set(tokens(canon, expand=False))
    dice = 2 * len(n & c) / (len(n) + len(c)) if n and c else 0.0
    spell = difflib.SequenceMatcher(None, "".join(tokens(native, False)), "".join(tokens(canon, False))).ratio()
    return max(dice, 0.8 * spell if spell >= 0.75 else 0.5 * spell)


# ---- evidence -----------------------------------------------------------------------------------------------------------------------

def type_score(canon_type: str, s: FieldStat) -> float:
    k = s.kind()
    t = (canon_type or "").lower()
    if k == "empty":
        return 0.2
    if "list" in t or t == "record":
        return 1.0 if k == "array" else 0.0
    if k == "array":
        return 0.0
    if t == "date":
        return {"date": 1.0, "timestamp": 0.6}.get(k, 0.0)
    if t == "timestamp":
        return {"timestamp": 1.0, "date": 0.5}.get(k, 0.0)
    if t in ("amount", "rate", "number", "integer", "decimal", "percent"):
        return 1.0 if k == "number" else 0.0
    if t in ("id", "text", "string"):
        return {"text": 0.9, "number": 0.5 if t == "id" else 0.3, "boolean": 0.4, "date": 0.1, "timestamp": 0.1}.get(k, 0.3)
    if t == "boolean":
        return 1.0 if k == "boolean" else 0.4
    return 0.5


def value_map(enum: list, s: FieldStat) -> tuple[dict, float]:
    """(native value -> canonical value, the share of rows mapped) for a field with an enum."""
    canon = {str(e).upper(): str(e) for e in enum}
    spelled = {}
    for c, alts in VALUES.items():
        if c.upper() in canon:
            for a in alts:
                spelled.setdefault(a.upper(), canon[c.upper()])
    out, mapped, total = {}, 0, sum(s.values.values()) or 1
    for v, n in s.values.items():
        u = v.upper()
        to = canon.get(u) or spelled.get(u)
        if to is None:
            first = [c for c in canon if c[:1] == u[:1]]
            to = canon[first[0]] if len(first) == 1 and len(s.values) <= len(canon) else None
        if to is not None:
            out[v] = to
            mapped += n
    return out, mapped / total


def value_score(cf: dict, s: FieldStat) -> tuple[float, dict, str]:
    """(score, value map, why) from the contract's enum or example against the native values."""
    enum = cf.get("enum")
    if enum and s.kind() in ("text", "boolean", "number") and s.distinct() <= max(50, 3 * len(enum)):
        vm, share = value_map(enum, s)
        exact = sum(n for v, n in s.values.items() if v in {str(e) for e in enum}) / (sum(s.values.values()) or 1)
        if exact >= 0.95:
            return 1.0, {}, f"its values are the enum's ({s.distinct()} distinct)"
        if share >= 0.9:
            return 0.9, {k: v for k, v in vm.items() if k != v}, f"{s.distinct()} values map onto the enum {', '.join(map(str, enum[:6]))}"
        if s.distinct() <= len(enum):
            return 0.45, {k: v for k, v in vm.items() if k != v}, f"{s.distinct()} values for an enum of {len(enum)}"
        return 0.1, {}, f"{s.distinct()} values do not fit the enum"
    if enum and s.distinct() > max(50, 3 * len(enum)):
        return 0.05, {}, f"{s.distinct()} distinct values for an enum of {len(enum)}"
    ex = cf.get("example")
    if isinstance(ex, str) and ex:
        m = re.match(r"^([A-Za-z]{1,6}[-_])", ex)
        if m and s.prefixes:
            pre, n = s.prefixes.most_common(1)[0]
            if pre == m.group(1).upper() and n >= 0.8 * s.rows:
                return 1.0, {}, f"the prefix {pre} like the canonical {ex}"
        if s.shapes:
            sh, n = s.shapes.most_common(1)[0]
            if sh == shape(ex) and n >= 0.8 * s.rows and len(sh) > 2:
                return 0.75, {}, f"the shape of {ex}"
    return 0.5, {}, ""


def pair(cf: dict, native: str, s: FieldStat, keys: set) -> dict:
    ns = name_score(native, cf["name"])
    ts = type_score(cf.get("type"), s)
    vs, vm, vwhy = value_score(cf, s)
    conf = 0.55 * ns + 0.2 * ts + 0.25 * vs
    if cf["name"] in keys:
        conf += 0.1 if s.unique() and s.rows else -0.15
    if ts == 0:
        conf *= 0.4
    if ns < 0.3 and vs < 0.75:
        conf *= 0.5                                                     # no word in common and nothing telling in the values: a guess
    why = []
    if ns >= 0.6:
        why.append("name")
    elif ns >= 0.3:
        why.append("name in part")
    why.append({"date": "ISO dates", "timestamp": "ISO timestamps", "number": "numbers", "text": "text", "array": "an array of records",
                "boolean": "yes/no", "empty": "always empty"}[s.kind()] + (" (a match)" if ts >= 0.9 else "" if ts >= 0.5 else " (another type)"))
    if vwhy:
        why.append(vwhy)
    if cf["name"] in keys and s.unique():
        why.append(f"unique in {s.rows:,} rows")
    return {"to": cf["name"], "from": native, "confidence": round(max(0.0, min(conf, 1.0)), 3), "values": vm, "why": "; ".join(why),
            "evidence": {"name": round(ns, 2), "type": round(ts, 2), "values": round(vs, 2)}}


def propose(prof: dict, kind: str, system: str) -> dict:
    """The proposed map: each canonical field's native field (or none), with its confidence and why; the missing and the unmapped."""
    c = contract(kind)
    keys = set(c.get("key") or [])
    canon = [f for f in c.get("fields") or [] if f.get("name")]
    fields = prof["fields"]
    pairs = []
    for cf in canon:
        if cf["name"] == "SOURCE_SYSTEM":
            continue                                                  # stamped from the system name (the connector's set:)
        for native, s in fields.items():
            p = pair(cf, native, s, keys)
            if p["confidence"] >= THRESHOLD:
                pairs.append(p)
    pairs.sort(key=lambda p: (-p["confidence"], p["to"], p["from"]))
    used_to, used_from, chosen = set(), set(), {}
    required_names = {cf["name"] for cf in canon if cf.get("presence") == "required"}
    for p in pairs:
        if p["to"] in used_to or p["from"] in used_from:
            continue
        if p["to"] not in required_names and p["confidence"] < OPTIONAL_FLOOR:
            continue                                          # weak evidence for an optional field: left unmapped, offered under others
        used_to.add(p["to"])
        used_from.add(p["from"])
        chosen[p["to"]] = p
    others: dict[str, list] = {}
    for p in pairs:
        if chosen.get(p["to"], {}).get("from") != p["from"] and len(others.setdefault(p["to"], [])) < 3:
            others[p["to"]].append({"from": p["from"], "confidence": p["confidence"]})
    rows = []
    for cf in canon:
        name = cf["name"]
        required = cf.get("presence") == "required"
        if name == "SOURCE_SYSTEM":
            rows.append({"to": name, "type": cf.get("type"), "required": required, "from": None, "value": system, "confidence": 1.0,
                         "why": "the system's name, stamped at load (the connector's set:)", "values": {}, "others": []})
            continue
        p = chosen.get(name)
        rows.append({"to": name, "type": cf.get("type"), "required": required, "from": p["from"] if p else None, "value": None,
                     "confidence": p["confidence"] if p else 0.0, "why": p["why"] if p else "no native field fits",
                     "values": p["values"] if p else {}, "evidence": p["evidence"] if p else None, "others": others.get(name, []),
                     "enum": cf.get("enum") or []})
    unmapped = [{"field": n, **s.as_dict(4)} for n, s in sorted(fields.items()) if n not in used_from]
    return summarise({"format": 1, "kind": kind, "system": system, "pack": c.get("pack") or c["_pack"], "contract": c["_file"], "rows": prof["rows"],
                      "files": prof["files"], "bad": prof["bad"], "fields": rows, "unmapped": unmapped,
                      "native": {n: s.as_dict() for n, s in sorted(fields.items())}})


def summarise(m: dict) -> dict:
    rows = m["fields"]
    m["mapped"] = sum(1 for r in rows if r.get("from") or r.get("value") is not None)
    m["sure"] = sum(1 for r in rows if r.get("from") and r["confidence"] >= SURE)
    m["check"] = sum(1 for r in rows if r.get("from") and r["confidence"] < SURE)
    m["missing"] = [r["to"] for r in rows if r["required"] and not r.get("from") and r.get("value") is None]
    return m


def suggest(prof: dict, top: int = 5) -> list[dict]:
    """The kinds this file could feed, by how many of its fields a contract's names fit: [{kind, pack, overlap, fields}]."""
    import yaml
    natives = list(prof["fields"])
    out = []
    for kind, f in contracts().items():
        try:
            c = yaml.safe_load(f.read_text(encoding="utf-8")) or {}
        except (OSError, yaml.YAMLError):
            continue
        names = [x["name"] for x in c.get("fields") or [] if isinstance(x, dict) and x.get("name")]
        if len(names) < 3:
            continue
        hit = sum(1 for n in natives if max((name_score(n, x) for x in names), default=0) >= 0.6)
        if hit:
            out.append({"kind": kind, "pack": f.parent.parent.name, "overlap": hit, "fields": len(natives), "canonical": len(names),
                        "score": round(hit / max(1, len(natives)), 3)})
    out.sort(key=lambda x: (-x["score"], -x["overlap"], x["kind"]))
    return out[:top]


# ---- edits, the file, and reading a record through the map ----------------------------------------------------------------------------

def edit(m: dict, edits: dict) -> dict:
    """The user's changes: {CANONICAL: null (drop) | {from: native} | {value: constant} | {values: {native: canonical}}}."""
    by = {r["to"]: r for r in m["fields"]}
    for to, e in (edits or {}).items():
        r = by.get(to)
        if r is None:
            raise SystemExit(f"--map-edits: '{to}' is not a field of the kind {m['kind']}")
        if e is None:
            r.update({"from": None, "value": None, "values": {}, "confidence": 0.0, "why": "dropped by you"})
            continue
        if "from" in e:
            if e["from"] and e["from"] not in m["native"]:
                raise SystemExit(f"--map-edits: '{e['from']}' is not a field of the file")
            r.update({"from": e["from"] or None, "value": None, "confidence": 1.0, "why": "chosen by you"})
        if "value" in e:
            r.update({"from": None, "value": e["value"], "confidence": 1.0, "why": "a constant you set"})
        if "values" in e:
            r["values"] = {**(r.get("values") or {}), **{str(k): v for k, v in (e["values"] or {}).items()}}
    used = {r["from"] for r in m["fields"] if r.get("from")}
    m["unmapped"] = [{"field": n, **{k: v for k, v in d.items() if k in ("rows", "kind", "distinct", "values")}} for n, d in m["native"].items() if n not in used]
    return summarise(m)


def map_yaml(m: dict, header: str = "") -> str:
    """The map file the connector's `map:` names: canonical field <- native field or constant, value maps, the native fields kept."""
    q = lambda v: json.dumps(v, ensure_ascii=False)                                       # noqa: E731
    out = [header + f"# {m['system']} -> {m['kind']}: proposed by drishti.py pack new --source-for {m['kind']} from {m['rows']:,} rows "
           f"(every row read), then edited.\n# Each line: the canonical field <- the native field (from) or a constant (value); values: maps a native value.\n",
           "format: 1", f"kind: {m['kind']}", f"system: {m['system']}", "set:", f"  SOURCE_SYSTEM: {q(m['system'])}", "fields:"]
    for r in m["fields"]:
        if r["to"] == "SOURCE_SYSTEM" or (not r.get("from") and r.get("value") is None):
            continue
        body = [f"from: {q(r['from'])}"] if r.get("from") else [f"value: {q(r['value'])}"]
        if r.get("values"):
            body.append("values: {" + ", ".join(f"{q(k)}: {q(v)}" for k, v in sorted(r["values"].items())) + "}")
        out.append(f"  {r['to']}: {{{', '.join(body)}}}   # {round(100 * r['confidence'])}%: {r['why']}")
    if m["missing"]:
        out.append("# required, no native field yet (give a field or a constant): " + ", ".join(m["missing"]))
    out.append("keep:                              # native fields with no canonical one: carried as they are (the module's section of the page)")
    out += [f"  - {q(u['field'])}" for u in m["unmapped"]] or ["  []"]
    if not m["unmapped"]:
        out[-2] = out[-2].replace("keep:", "keep: []", 1)
        out.pop()
    return "\n".join(out) + "\n"


def get(doc: dict, path: str):
    cur = doc
    for p in path.split("."):
        if not isinstance(cur, dict) or p not in cur:
            return None
        cur = cur[p]
    return cur


def apply(mapping: dict, doc: dict) -> dict:
    """One native record read through a map file's content (the reference reading; the connector does the same)."""
    out: dict = {}
    for to, e in (mapping.get("fields") or mapping.get("map") or {}).items():
        if not isinstance(e, dict):                                       # map: CANONICAL: native.path
            e = {"from": e}
        if "value" in e:
            out[to] = e["value"]
            continue
        src = e.get("first-of") or e["from"]                              # a dotted path, or a list: the first non-empty
        v = next((x for x in (get(doc, f) for f in (src if isinstance(src, list) else [src])) if x not in (None, "")), None)
        vm = e.get("values") or {}
        out[to] = vm.get(str(v).upper() if isinstance(v, bool) else str(v), v) if vm and v is not None else v
        if e.get("scale") is not None and isinstance(v, (int, float)) and not isinstance(v, bool):
            out[to] = out[to] * e["scale"]
    for k in mapping.get("keep") or []:
        out[k] = get(doc, k)
    out.update(mapping.get("set") or {})
    return out
