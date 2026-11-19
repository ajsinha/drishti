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

"""The config-driven mapping of a joined PVF row to a scenario-pv record (mapping.yaml; the grammar is in its header and README.md),
checked against the contract when it loads, and the scenario-set record the vectors are indexed by."""
from __future__ import annotations

import datetime as dt
import math
import re
from collections import Counter
from pathlib import Path

import yaml

from mrloader.common import ROOT, PvfError, iso_date, upper_name
from mrloader.contract import Contract

RULE_KEYS = {"const", "from", "template", "regex", "map", "else", "sign", "sign_from", "slug", "upper", "prefix", "type", "default"}
TYPES = {"text", "number", "integer", "date", "list", "vector"}
_REF = re.compile(r"\{([$@]?)([A-Za-z_][A-Za-z0-9_]*)\}")

# why a row could not be mapped (the export audit's REASON)
MISSING_REQUIRED = "MISSING_REQUIRED"
UNMAPPED_VALUE = "UNMAPPED_VALUE"
BAD_VALUE = "BAD_VALUE"


class RowProblem(Exception):
    """One row cannot be mapped: (reason, detail)."""

    def __init__(self, reason: str, detail: str):
        super().__init__(detail)
        self.reason, self.detail = reason, detail


def _empty(v) -> bool:
    return v is None or (isinstance(v, str) and v.strip() == "") or (isinstance(v, float) and math.isnan(v))


def _text(v) -> str:
    if isinstance(v, float) and v.is_integer() and abs(v) < 1e15:
        return str(int(v))
    return str(v)


class Mapping:
    """mapping.yaml, loaded and checked: every required contract field has a rule, every rule names a contract field, the grammar holds."""

    def __init__(self, path: Path, run_overrides: dict | None = None, contract: Contract | None = None):
        self.path = Path(path)
        try:
            doc = yaml.safe_load(self.path.read_text(encoding="utf-8")) or {}
        except (OSError, yaml.YAMLError) as e:
            raise PvfError(f"cannot read the mapping {self.path}: {e}") from None
        self.contract = contract or Contract(ROOT / doc.get("contract", "modules/banking/packs/market-risk/contracts/scenario-pv.contract.yaml"))
        self.run = {k: v for k, v in (doc.get("run") or {}).items()}
        self.run.update({k: v for k, v in (run_overrides or {}).items() if v is not None})
        self.set = dict(doc.get("scenario_set") or {})
        self.fields: dict[str, dict] = dict(doc.get("fields") or {})
        self.omit: dict[str, str] = {str(k): str(v) for k, v in (doc.get("omit_required") or {}).items()}
        pt = doc.get("pass_through") or {}
        if isinstance(pt, list):
            pt = {"columns": pt}
        self.pass_columns = pt.get("columns", [])
        self.pass_exclude = {str(x) for x in pt.get("exclude", [])}
        self.pass_prefix = str(pt.get("collision_prefix", "PVF_"))
        self.sources: dict[str, Counter] = {}                     # field -> which column of a `from` list fed it, counted per row
        self._check()

    def _check(self) -> None:
        problems = []
        for name, rule in self.fields.items():
            if name not in self.contract.fields:
                problems.append(f"{name} is not a field of the {self.contract.kind} contract")
            if not isinstance(rule, dict):
                problems.append(f"{name}: a rule is a mapping (const, from, template ...)")
                continue
            unknown = set(rule) - RULE_KEYS
            if unknown:
                problems.append(f"{name}: unknown rule key(s) {sorted(unknown)}")
            if sum(k in rule for k in ("const", "from", "template")) != 1:
                problems.append(f"{name}: give exactly one of const, from, template")
            if rule.get("type") and rule["type"] not in TYPES:
                problems.append(f"{name}: type {rule['type']!r} is not one of {sorted(TYPES)}")
            if "sign" in rule and not set(rule["sign"]) <= {"negative", "zero", "positive"}:
                problems.append(f"{name}: sign takes negative, zero, positive")
            sf = rule.get("sign_from")
            if sf is not None and (not isinstance(sf, dict) or not sf.get("column") or not (sf.get("negative") or sf.get("positive"))):
                problems.append(f"{name}: sign_from takes column and the values that mean negative and positive (lists)")
            for kind, ref in _REF.findall(str(rule.get("template", "")) + str(rule.get("else", "")) + str(rule.get("default", ""))):
                if kind == "@" and (ref not in self.fields or list(self.fields).index(ref) >= list(self.fields).index(name)):
                    problems.append(f"{name}: {{@{ref}}} must name a field mapped above it")
        missing = [f for f in self.contract.required if f not in self.fields and f not in self.omit]
        if missing:
            problems.append(f"no rule for the required field(s) {missing} (map them, or name them in omit_required with the reason)")
        for f in self.omit:
            if f not in self.contract.required or f in self.fields:
                problems.append(f"omit_required {f}: only a required contract field without a rule is omitted")
        if self.pass_columns != "*" and not isinstance(self.pass_columns, list):
            problems.append("pass_through.columns is \"*\" or a list of joined columns")
        if not re.match(r"^[A-Z][A-Z0-9_]*$", self.pass_prefix):
            problems.append("pass_through.collision_prefix must be UPPER_SNAKE (PVF_)")
        if self.set.get("path_order", "oldest-first") not in ("oldest-first", "newest-first"):
            problems.append("scenario_set.path_order is oldest-first or newest-first")
        if problems:
            raise PvfError(f"{self.path}: " + "; ".join(problems))
        for k in ("scenario_set_id", "batch_id", "computed_at", "restatement"):
            if k not in self.run:
                raise PvfError(f"{self.path}: run.{k} is not set")

    # ---- run values ------------------------------------------------------------------------------------------------------------------
    def resolve_run(self, business_date: str, known: dict | None = None) -> dict:
        """The run values with {$business_date}, the ``known`` facts of the extract ({$pv_file_modified}) and each other filled in."""
        ctx = {"business_date": business_date, **(known or {})}
        for k, v in self.run.items():
            ctx[k] = self._fill(str(v), {}, {}, ctx) if isinstance(v, str) else v
        if ctx.get("computed_at") == "":
            raise PvfError("no COMPUTED_AT: run.computed_at names a value the run does not know (the join's summary beside the joined file "
                           "gives {$pv_file_modified}); give --computed-at")
        if not str(ctx["scenario_set_id"]).startswith("HS-"):
            raise PvfError(f"the scenario set id {ctx['scenario_set_id']!r} must start with HS-")
        return ctx

    @staticmethod
    def _fill(template: str, row: dict, out: dict, ctx: dict) -> str:
        """A template filled; empty when any part it names is empty."""
        empty = False

        def sub(m):
            nonlocal empty
            kind, name = m.group(1), m.group(2)
            v = ctx.get(name) if kind == "$" else out.get(name) if kind == "@" else row.get(name)
            if kind == "$" and name not in ctx and name != "pv_file_modified":
                raise PvfError(f"the template {template!r} names an unknown run value ${name}")
            if _empty(v):
                empty = True
                return ""
            return _text(v)

        s = _REF.sub(sub, template)
        return "" if empty else s

    # ---- one row ---------------------------------------------------------------------------------------------------------------------
    def apply(self, row: dict, ctx: dict, order: str = "oldest-first", count: int | None = None) -> dict:
        """The scenario-pv record of one joined row; RowProblem when a required field ends empty or a value cannot be mapped."""
        out: dict = {}
        for name, rule in self.fields.items():
            v = self._value(name, rule, row, out, ctx)
            if rule.get("type") == "vector" and isinstance(v, list):
                if count is not None and len(v) != count:
                    raise RowProblem(BAD_VALUE, f"{name} has {len(v)} values, the scenario set {count} (never padded)")
                v = list(reversed(v)) if order == "newest-first" else v
            if _empty(v):
                if name in self.contract.required:
                    raise RowProblem(MISSING_REQUIRED, f"{name} is required and its rule gave nothing ({_describe(rule)})")
                continue
            out[name] = v
        out.update(self.extras(row))
        return out

    def extras(self, row: dict) -> dict:
        """The source attributes kept beside the contract's fields (pass_through): names UPPER_SNAKE, a contract field's name prefixed
        (PVF_POSITION_ID), values as they are; null values left out."""
        cols = list(row) if self.pass_columns == "*" else [str(c) for c in self.pass_columns]
        out = {}
        for col in cols:
            if col in self.pass_exclude or row.get(col) is None:
                continue
            name = upper_name(col)
            out[self.pass_prefix + name if name in self.contract.fields else name] = row[col]
        return out

    def _value(self, name: str, rule: dict, row: dict, out: dict, ctx: dict):
        used = None
        if "const" in rule:
            v = rule["const"]
        elif "template" in rule:
            v = self._fill(str(rule["template"]), row, out, ctx)
        else:
            cols = rule["from"] if isinstance(rule["from"], list) else [rule["from"]]
            used = next((c for c in cols if not _empty(row.get(c))), None)
            v = row.get(used) if used else None
            if len(cols) > 1:
                self.sources.setdefault(name, Counter())[used or "(none)"] += 1
        if not _empty(v) and "regex" in rule:
            m = re.search(rule["regex"], _text(v))
            v = (m.group(1) if m and m.groups() else m.group(0) if m else None)
        if not _empty(v) and "map" in rule:
            key = _text(v)
            if key in rule["map"]:
                v = rule["map"][key]
            elif "else" in rule:
                v = self._fill(str(rule["else"]), row, out, ctx)
            else:
                raise RowProblem(UNMAPPED_VALUE, f"{name}: {key!r} is not in the map ({sorted(rule['map'])}) and the rule has no else")
        if not _empty(v) and "sign" in rule:
            try:
                x = float(v)
            except (TypeError, ValueError):
                raise RowProblem(BAD_VALUE, f"{name}: {v!r} is not a number (sign)") from None
            v = rule["sign"].get("negative" if x < 0 else "zero" if x == 0 else "positive")
        if not _empty(v) and rule.get("slug"):
            v = re.sub(r"[^A-Z0-9]+", "-", _text(v).upper()).strip("-")
        elif not _empty(v) and rule.get("upper"):
            v = _text(v).upper()
        if not _empty(v) and rule.get("prefix") and not _text(v).startswith(rule["prefix"]):
            v = rule["prefix"] + _text(v)
        if _empty(v) and "default" in rule:
            v = self._fill(str(rule["default"]), row, out, ctx)
        sf = rule.get("sign_from")
        if not _empty(v) and sf and ("only_for" not in sf or ("from" in rule and used in sf["only_for"])):
            v = _signed(name, v, sf, row)
        return _typed(name, rule.get("type") or _contract_type(self.contract, name), v)


def _signed(name: str, v, sf: dict, row: dict):
    """``v`` as a number signed by another column (sign_from: column, negative: [SELL ...], positive: [BUY ...]): its magnitude, minus
    when the column holds a negative value; a value in neither list is audited (UNMAPPED_VALUE), never guessed."""
    try:
        x = abs(float(v))
    except (TypeError, ValueError):
        raise RowProblem(BAD_VALUE, f"{name}: {v!r} is not a number (sign_from)") from None
    flag = "" if _empty(row.get(sf["column"])) else _text(row[sf["column"]]).strip().upper()
    if flag in {str(s).upper() for s in sf.get("negative") or []}:
        return -x
    if flag in {str(s).upper() for s in sf.get("positive") or []}:
        return x
    raise RowProblem(UNMAPPED_VALUE, f"{name}: {sf['column']} {flag!r} is neither negative {sf.get('negative')} nor positive {sf.get('positive')}")


def _contract_type(c: Contract, name: str) -> str:
    t = c.type_of(name)
    return {"amount": "number", "integer": "integer", "date": "date", "vector": "vector", "id list": "list"}.get(t, "text")


def _typed(name: str, t: str, v):
    if _empty(v):
        return None
    try:
        if t == "number":
            x = float(v)
            if not math.isfinite(x):
                raise ValueError("not finite")
            return x
        if t == "integer":
            if not float(v).is_integer():
                raise ValueError("has a fraction")
            return int(float(v))
        if t == "date":
            return iso_date(_text(v))
        if t == "list":
            return [_text(x) for x in v] if isinstance(v, list) else [_text(v)]
        if t == "vector":
            if not isinstance(v, list):
                raise ValueError("not a list")
            return [float(x) for x in v]
        return _text(v)
    except (TypeError, ValueError, PvfError) as e:
        raise RowProblem(BAD_VALUE, f"{name}: {v!r} is not a {t} ({e})") from None


def _describe(rule: dict) -> str:
    return ", ".join(f"{k}: {rule[k]}" for k in ("const", "from", "template") if k in rule)


# ---- the scenario set ------------------------------------------------------------------------------------------------------------------
def weekdays_before(business_date: str, n: int) -> list[str]:
    """The ``n`` weekdays before the business date, oldest first (no holiday calendar)."""
    d = dt.date.fromisoformat(business_date)
    out = []
    while len(out) < n:
        d -= dt.timedelta(days=1)
        if d.weekday() < 5:
            out.append(d.isoformat())
    return out[::-1]


def scenario_dates(m: Mapping, business_date: str, override: str | None = None) -> tuple[list[str], str]:
    """The set's days, oldest first, and the words saying where they came from."""
    rule = str(override or m.set.get("dates", "weekdays"))
    n = int(m.set.get("count", 0))
    if n < 1:
        raise PvfError("scenario_set.count must be 1 or more")
    if rule == "weekdays":
        return weekdays_before(business_date, n), f"assumed: the {n} weekdays before {business_date}, no holiday calendar"
    p = Path(rule) if Path(rule).is_absolute() else ROOT / rule
    if not p.is_file():
        p = Path(rule)
    try:
        days = [iso_date(x) for x in p.read_text(encoding="utf-8").split() if x.strip()]
    except OSError as e:
        raise PvfError(f"cannot read the scenario dates {rule}: {e}") from None
    if len(days) != n:
        raise PvfError(f"{rule} has {len(days)} dates, scenario_set.count is {n} (never padded)")
    if days != sorted(set(days)) or days[-1] >= business_date:
        raise PvfError(f"{rule}: the dates must be distinct, oldest first, all before {business_date}")
    return days, f"from {p.name}"


def scenario_set_record(m: Mapping, ctx: dict, days: list[str], words: str) -> dict:
    """The date's scenario-set record (the scenario-set contract)."""
    desc = str(m.set.get("description", "Historical simulation from the PVF extract")).format(count=len(days), dates_rule=words)
    return {"SCENARIO_SET_ID": ctx["scenario_set_id"], "BUSINESS_DATE": ctx["business_date"], "SCENARIO_DATES": days,
            "SCENARIO_COUNT": len(days), "WINDOW_START": days[0], "WINDOW_END": days[-1], "HORIZON_DAYS": int(m.set.get("horizon_days", 1)),
            "REVALUATION": str(m.set.get("revaluation", "full")), "WEIGHTS": None, "SOURCE_BATCH": ctx["batch_id"], "DESCRIPTION": desc,
            "SET_TYPE": "HISTORICAL"}
