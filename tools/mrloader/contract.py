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

"""The data contract of any kind, read from its YAML (never copied here; found by kind under the packs), and the checks of one record and
of one business date's records together: required fields, types, enums, patterns, nested records and lists of records, fields Drishti
derives (never loaded), the UPPER_SNAKE rule, no field the contract lacks, and the VaR engine's own one-date rules (one scenario set and
one batch, every vector as long as the set, unique ids, valuation dates)."""
from __future__ import annotations

import math
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

import yaml

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import resolved  # noqa: E402
from mrloader.common import FX_SCENARIO_CONTRACT, ROOT, SCENARIO_PV_CONTRACT, SCENARIO_SET_CONTRACT, PvfError, lower_keys

_DATE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
_TIMESTAMP = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:\d{2})$")


class Contract:
    """One kind's contract: its fields (type, presence, enum, pattern) and its required list."""

    def __init__(self, path: Path):
        self.path = Path(path)
        try:
            doc = yaml.safe_load(self.path.read_text(encoding="utf-8"))
        except OSError as e:
            raise PvfError(f"cannot read the contract {self.path}: {e}") from None
        self.kind = doc["kind"]
        self.fields = {f["name"]: f for f in doc["fields"]}
        req = [r for r in doc.get("validation", []) if r.get("rule") == "required"]
        self.required = list(req[0]["fields"]) if req else [n for n, f in self.fields.items() if f.get("presence") == "required"]
        self.enums = {f: r["values"] for r in doc.get("validation", []) if r.get("rule") == "enum" for f in [r["field"]]}
        self.patterns = {f: r["pattern"] for r in doc.get("validation", []) if r.get("rule") == "pattern" for f in [r["field"]]}
        for n, f in self.fields.items():
            if f.get("enum"):
                self.enums.setdefault(n, f["enum"])
            if f.get("pattern"):
                self.patterns.setdefault(n, f["pattern"])
        self.key = (doc.get("key") or [None])[0]
        self.doc = doc
        self.pack = doc.get("pack")
        self.klass = doc.get("class")
        self.derived = {n for n, f in self.fields.items() if f.get("presence") == "derived"}
        self.nested: dict[str, dict] = {p: {f["name"]: f for f in fs} for p, fs in (doc.get("nested") or {}).items()}
        self.links: dict[str, str] = {n: f["link"] for n, f in self.fields.items() if f.get("link")}
        for p, fs in self.nested.items():
            self.links.update({f"{p}.{n}": f["link"] for n, f in fs.items() if f.get("link")})
        self.links.update({str(x["field"]): str(x["kind"]) for x in doc.get("links") or [] if x.get("field") and x.get("kind")})

    @classmethod
    def for_kind(cls, kind: str, pack: str | None = None) -> "Contract":
        """The contract of ``kind`` (and ``pack`` when two packs have the kind), found under the packs: never a path written by hand."""
        return cls(contract_path(kind, pack))

    def field(self, dotted: str) -> dict | None:
        """A field by its dotted name: ``NAME``, ``KYC.STATUS``, ``SENSITIVITIES[].VALUE``; None when the contract has no such field."""
        if "." not in dotted:
            return self.fields.get(dotted)
        path, name = dotted.rsplit(".", 1)
        return (self.nested.get(path) or {}).get(name)

    def required_in(self, path: str) -> list[str]:
        """The required fields of a nested path (``KYC``, ``SENSITIVITIES[]``)."""
        return [n for n, f in (self.nested.get(path) or {}).items() if f.get("presence") == "required"]

    def type_of(self, name: str) -> str:
        return str(self.fields[name].get("type", "unknown"))

    def check(self, r: dict, extra_ok: bool = False, omitted: frozenset | set = frozenset()) -> list[str]:
        """What is wrong with one record (empty: nothing). ``extra_ok``: attributes the contract lacks are accepted (their names must
        still be UPPER_SNAKE); ``omitted``: required fields known to be left out on purpose (the mapping's omit-required)."""
        bad = [f"attribute name not UPPER_SNAKE: {k}" for k in lower_keys(r)]
        if not extra_ok:
            bad += [f"{k}: not a field of the {self.kind} contract" for k in self.extras(r)]
        for name in self.required:
            if name in omitted and name not in r:
                continue
            if r.get(name) is None or r.get(name) == "":
                if not (name in ("SCENARIO_PV",) and _no_vector_allowed(r)):
                    bad.append(f"{name}: required, missing")
        for name, v in r.items():
            if name not in self.fields or v is None or (v == "" and name not in self.required):
                continue
            why = _type_error(self.type_of(name), v)
            if why:
                bad.append(f"{name}: {why}")
                continue
            if name in self.enums and v not in self.enums[name]:
                bad.append(f"{name}: {v!r} is not one of {self.enums[name]}")
            if name in self.patterns and isinstance(v, str) and not re.search(self.patterns[name], v):
                bad.append(f"{name}: {v!r} does not match {self.patterns[name]}")
            if name in self.derived:
                bad.append(f"{name}: derived (Drishti computes it from other kinds): never loaded")
            t = self.type_of(name)
            if t == "record" and isinstance(v, dict):
                bad += self._check_nested(name, v)
            elif t == "record list" and isinstance(v, list):
                for i, x in enumerate(v):
                    bad += self._check_nested(f"{name}[]", x, f"{name}[{i}]") if isinstance(x, dict) else [f"{name}[{i}]: not a record"]
        return bad

    def _check_nested(self, path: str, r: dict, shown: str | None = None) -> list[str]:
        """One nested record against the contract's ``nested:`` fields of ``path``; a path the contract does not detail is accepted."""
        fields = self.nested.get(path)
        shown = shown or path
        if fields is None:
            return []
        bad = [f"{shown}.{k}: not a field of the {self.kind} contract" for k in r if k not in fields]
        for name, f in fields.items():
            v = r.get(name)
            if v is None or v == "":
                if f.get("presence") == "required":
                    bad.append(f"{shown}.{name}: required, missing")
                continue
            if f.get("presence") == "derived":
                bad.append(f"{shown}.{name}: derived (Drishti computes it): never loaded")
            why = _type_error(str(f.get("type", "unknown")), v)
            if why:
                bad.append(f"{shown}.{name}: {why}")
                continue
            if f.get("enum") and v not in f["enum"]:
                bad.append(f"{shown}.{name}: {v!r} is not one of {f['enum']}")
            if f.get("pattern") and isinstance(v, str) and not re.search(f["pattern"], v):
                bad.append(f"{shown}.{name}: {v!r} does not match {f['pattern']}")
        return bad


    def extras(self, r: dict) -> list[str]:
        """The record's attributes the contract does not have."""
        return [k for k in r if k not in self.fields]

    def gaps(self, r: dict) -> list[str]:
        """The required fields the record leaves out."""
        return [n for n in self.required if n not in r]


def _no_vector_allowed(r: dict) -> bool:
    """Contract 1.1: no SCENARIO_PV on an intraday cancel (TRADE_STATUS CANCELLED) or a failed row (STATUS failed) only."""
    return r.get("TRADE_STATUS") == "CANCELLED" or r.get("STATUS") == "failed"


def _number(v) -> bool:
    return isinstance(v, (int, float)) and not isinstance(v, bool) and math.isfinite(v)


def _type_error(t: str, v) -> str | None:
    if t in ("text", "id"):
        return None if isinstance(v, str) else f"{v!r} is not text"
    if t in ("amount", "rate"):
        return None if _number(v) else f"{v!r} is not a finite number"
    if t == "record":
        return None if isinstance(v, dict) else f"{v!r} is not a record"
    if t == "record list":
        return None if isinstance(v, list) else "not a list of records"
    if t == "text list":
        return None if isinstance(v, list) and all(isinstance(x, str) for x in v) else "not a list of text"
    if t == "integer":
        return None if isinstance(v, int) and not isinstance(v, bool) else f"{v!r} is not an integer"
    if t == "date":
        return None if isinstance(v, str) and _DATE.match(v) else f"{v!r} is not a date (YYYY-MM-DD)"
    if t == "timestamp":
        return None if isinstance(v, str) and _TIMESTAMP.match(v) else f"{v!r} is not an ISO-8601 timestamp"
    if t == "boolean":
        return None if isinstance(v, bool) else f"{v!r} is not true or false"
    if t == "vector":
        if not isinstance(v, list) or not v:
            return "not a list of numbers"
        nulls = [i for i, x in enumerate(v) if not _number(x)]
        return None if not nulls else f"{len(nulls)} value(s) not finite numbers (first at index {nulls[0]}); never filled with zero"
    if t == "id list":
        return None if isinstance(v, list) and all(isinstance(x, str) and x for x in v) else "not a list of ids (text)"
    if t == "date list":
        return None if isinstance(v, list) and all(isinstance(x, str) and _DATE.match(x) for x in v) else "not a list of dates (YYYY-MM-DD)"
    return None                                                   # unknown: anything


def contract_path(kind: str, pack: str | None = None) -> Path:
    """The contract file of ``kind``: ``<pack>/contracts/<kind>.contract.yaml`` in the packs the resolver names; refused when
    no pack has the kind, or two do and ``pack`` does not say which."""
    found = sorted(d / "contracts" / f"{kind}.contract.yaml" for d in resolved.pack_dirs().values()
                   if (d / "contracts" / f"{kind}.contract.yaml").is_file())     # the resolver's packs, never a folder of ours
    if pack:
        found = [f for f in found if f.parent.parent.name == pack]
    if not found:
        raise PvfError(f"no pack has a contract for the kind {kind!r}" + (f" in the pack {pack}" if pack else ""))
    if len(found) > 1:
        raise PvfError(f"the kind {kind!r} has contracts in {len(found)} packs ({', '.join(f.parent.parent.name for f in found)}): name the pack")
    return found[0]


def scenario_pv() -> Contract:
    return Contract(SCENARIO_PV_CONTRACT)


def scenario_set() -> Contract:
    return Contract(SCENARIO_SET_CONTRACT)


def fx_scenario() -> Contract:
    return Contract(FX_SCENARIO_CONTRACT)


def record_rules(r: dict) -> list[str]:
    """The engine's per-record rules beyond the contract's (VarIntake): grain and ids, direction and quantity, valuation dates."""
    bad = []
    if r.get("GRAIN") == "POSITION":
        if not r.get("POSITION_ID"):
            bad.append("GRAIN POSITION needs POSITION_ID")
        if r.get("TRADE_ID"):
            bad.append("GRAIN POSITION has no TRADE_ID")
    elif r.get("GRAIN") == "TRADE" and not r.get("TRADE_ID"):
        bad.append("GRAIN TRADE needs TRADE_ID")
    q, d = r.get("QUANTITY"), r.get("DIRECTION")
    if _number(q) and d in ("LONG", "SHORT") and d != ("SHORT" if q < 0 else "LONG"):
        bad.append(f"DIRECTION {d} disagrees with QUANTITY {q} (LONG when zero or more, SHORT when negative)")
    bd = r.get("BUSINESS_DATE")
    for f in ("VALUATION_DATE", "BASE_VALUATION_DATE"):
        if r.get(f) and bd and r[f] != bd:
            bad.append(f"{f} {r[f]} differs from BUSINESS_DATE {bd} (DRS-5201)")
    return bad


class DateCheck:
    """The rules over one business date's records together: unique SCENARIO_PV_ID, one BATCH_ID for the full run, one normal scenario
    set (a stress set, SET_TYPE STRESS, may sit beside it: its rows name its own id); per set, every vector as long as its SCENARIO_DATES,
    the days in order, before the date and matching WINDOW_START/WINDOW_END, and SCENARIO_COUNT their number."""

    def __init__(self) -> None:
        self.ids: dict[str, Counter] = defaultdict(Counter)
        self.batches: dict[str, Counter] = defaultdict(Counter)
        self.lengths: dict[tuple[str, str], Counter] = defaultdict(Counter)
        self.set_records: dict[str, list[dict]] = defaultdict(list)

    def add_pv(self, r: dict) -> None:
        d = str(r.get("BUSINESS_DATE"))
        self.ids[d][r.get("SCENARIO_PV_ID")] += 1
        if r.get("RUN_KIND") in ("FULL", None):
            self.batches[d][r.get("BATCH_ID")] += 1
        n = len(r["SCENARIO_PV"]) if isinstance(r.get("SCENARIO_PV"), list) else None
        c = self.lengths[(d, str(r.get("SCENARIO_SET_ID")))]
        if n is not None:
            c[n] += 1
        else:
            c.update(())                                          # the set is named, even by a row without a vector

    def add_set(self, r: dict) -> None:
        self.set_records[str(r.get("BUSINESS_DATE"))].append(r)

    def problems(self) -> list[str]:
        out: list[str] = []
        for d in sorted(set(self.ids) | set(self.set_records)):
            dups = [i for i, n in self.ids[d].items() if n > 1]
            if dups:
                out.append(f"{d}: {len(dups)} SCENARIO_PV_ID(s) more than once (first {dups[0]})")
            if len(self.batches[d]) > 1:
                out.append(f"{d}: more than one BATCH_ID in the full run: {sorted(map(str, self.batches[d]))} (DRS-5203)")
            sets = {str(s.get("SCENARIO_SET_ID")): s for s in self.set_records[d]}
            normal = [s for s in self.set_records[d] if s.get("SET_TYPE") in (None, "HISTORICAL")]
            if self.set_records[d] and len(normal) != 1:
                out.append(f"{d}: {len(normal)} normal scenario sets (one expected)")
            for s in self.set_records[d]:
                out += _set_problems(d, s)
            for (day, sid), lengths in sorted(self.lengths.items()):
                if day != d:
                    continue
                if len(lengths) > 1:
                    out.append(f"{d}: the vectors of {sid} differ in length: {dict(lengths)}")
                if sid not in sets:
                    out.append(f"{d}: no scenario-set record {sid} among the files checked (the lake must already hold it)")
                elif lengths and set(lengths) != {len(sets[sid].get("SCENARIO_DATES") or [])}:
                    out.append(f"{d}: SCENARIO_PV length {sorted(lengths)} but {sid} has {len(sets[sid].get('SCENARIO_DATES') or [])} days (never padded)")
        return out


def _set_problems(d: str, s: dict) -> list[str]:
    sid, days = s.get("SCENARIO_SET_ID"), s.get("SCENARIO_DATES") or []
    out = []
    if s.get("SCENARIO_COUNT") != len(days):
        out.append(f"{d}: {sid} SCENARIO_COUNT {s.get('SCENARIO_COUNT')} but {len(days)} SCENARIO_DATES")
    if days != sorted(set(days)):
        out.append(f"{d}: {sid} SCENARIO_DATES not distinct and oldest first")
    if days and (s.get("WINDOW_START") != days[0] or s.get("WINDOW_END") != days[-1]):
        out.append(f"{d}: {sid} WINDOW_START/WINDOW_END do not match its first and last SCENARIO_DATES")
    if days and s.get("SET_TYPE") != "STRESS" and days[-1] >= d:
        out.append(f"{d}: {sid} SCENARIO_DATES must end before the business date")
    return out
