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

"""The mapping file of a kind (``kinds/<kind>.yaml``, ``kinds/<kind>.<source>.yaml``; docs/guides/BANK_DATA_GUIDE.md section 7): PVF's grammar
(mapping.py) with the additions a CSV of any kind needs: the inputs (a main file, child files grouped into a list), nested fields
(``KYC.STATUS``), lists in a cell (``split``), the cell's own format (``dates``, ``decimal``, ``percent``), ``pad``, ``lower``, ``trim``,
the kind's place in the day (``after``), what a rejected row does (``on-reject``), computed columns refused by name (``refuse-columns``)
and the checks over the date (``checks``). Cells are typed by the file's declared dialect (csvread.py), never guessed.

The mapping is checked against the contract before a row is read: every rule names a field of the contract (nested ones by their dotted
name), no rule maps a derived field, and every required field has a rule or a named omission (``omit-required``, with the reason)."""
from __future__ import annotations

import re
from pathlib import Path

import yaml

from mrloader import csvread as R
from mrloader.common import PvfError
from mrloader.contract import Contract
from mrloader.mapping import RULE_KEYS, Mapping, RowProblem, _empty, _fragments, _key, _resolve, _text
from mrloader.rejects import Problem

KINDS_DIR = Path(__file__).resolve().parent / "kinds"
NEW_KEYS = {"split", "pad", "lower", "trim", "percent", "dates", "decimal", "use"}
TYPES = {"text", "id", "integer", "amount", "rate", "date", "timestamp", "boolean", "vector", "text list", "id list", "date list",
         "number", "list"}
TOP_KEYS = {"format", "kind", "pack", "source", "about", "after", "csv", "inputs", "key", "run", "use", "fields", "omit-required",
            "refuse-columns", "pass-through", "on-reject", "max-rejects", "checks"}
_REF = re.compile(r"\{([$@]?)([A-Za-z_][A-Za-z0-9_]*)\}")
#: the run values every kind knows; a mapping's run: block adds or sets them
RUN_DEFAULTS = {"batch_id": "", "computed_at": "", "restatement": 0, "source_system": ""}


def mapping_path(kind: str, source: str | None = None) -> Path:
    """The shipped mapping of ``kind`` (``kinds/<kind>.yaml``, or ``kinds/<kind>.<source>.yaml``)."""
    p = KINDS_DIR / (f"{kind}.{source}.yaml" if source and source != "canonical" else f"{kind}.yaml")
    if not p.is_file():
        raise PvfError(f"no mapping for the kind {kind!r}{' and source ' + source if source else ''}: {p} (--kinds lists them; "
                       "a site's own mapping is given with --mapping FILE)")
    return p


class KindMapping(Mapping):
    """One kind's mapping file, loaded and checked against the kind's contract."""

    def __init__(self, path: Path, run_overrides: dict | None = None):
        self.path = Path(path)
        try:
            doc = yaml.safe_load(self.path.read_text(encoding="utf-8")) or {}
        except (OSError, yaml.YAMLError) as e:
            raise PvfError(f"cannot read the mapping {self.path}: {e}") from None
        self.doc = doc
        unknown = sorted(set(doc) - TOP_KEYS)
        if unknown:
            raise PvfError(f"{self.path}: unknown key(s) {unknown} (known: {sorted(TOP_KEYS)})")
        for k in ("format", "kind", "about", "fields"):
            if k not in doc:
                raise PvfError(f"{self.path}: {k}: is required")
        self.kind = str(doc["kind"])
        self.source = str(doc.get("source", "canonical"))
        self.about = str(doc["about"])
        self.contract = Contract.for_kind(self.kind, doc.get("pack"))
        self.pack = self.contract.pack
        self.after = [str(x) for x in doc.get("after") or []]
        self.dialect = R.Dialect.from_doc({**_shared_csv(), **(doc.get("csv") or {})})
        self.run = {**RUN_DEFAULTS, **(doc.get("run") or {})}
        self.run.update({k: v for k, v in (run_overrides or {}).items() if v is not None})
        self.set = {}
        self.fragments = _fragments(self.path, doc.get("use") or [])
        self.fields = {str(n): _resolve(str(n), r, self.fragments) for n, r in dict(doc.get("fields") or {}).items()}
        self.omit = {str(k): str(v) for k, v in (doc.get("omit-required") or {}).items()}
        self.refuse_columns = {R.column_name(k): str(v) for k, v in (doc.get("refuse-columns") or {}).items()}
        pt = doc.get("pass-through") or {}
        self.pass_columns = pt.get("columns", [])
        self.pass_exclude = {R.column_name(x) for x in pt.get("exclude", [])}
        self.pass_prefix = str(pt.get("collision-prefix", "SRC_"))
        self.on_reject = str(doc.get("on-reject", "refuse"))
        self.max_rejects = doc.get("max-rejects")
        self.checks = dict(doc.get("checks") or {})
        inputs = dict(doc.get("inputs") or {})
        self.main = dict(inputs.get("main") or {})
        self.children = {str(k): _on(dict(v)) for k, v in (inputs.get("children") or {}).items()}
        self.sources = {}
        self._check()

    # ---- the mapping checked before a row is read --------------------------------------------------------------------------------
    def _check(self) -> None:
        problems = []
        c = self.contract
        for name, rule in self.fields.items():
            f = c.field(name)
            top = name.split(".")[0].removesuffix("[]")
            if f is None:
                problems.append(f"{name} is not a field of the {c.kind} contract")
            elif f.get("presence") == "derived" or top in c.derived:
                problems.append(f"{name}: derived (Drishti computes it): a mapping never loads it")
            if not isinstance(rule, dict):
                problems.append(f"{name}: a rule is a mapping (const, from, template ...)")
                continue
            unknown = set(rule) - RULE_KEYS - NEW_KEYS
            if unknown:
                problems.append(f"{name}: unknown rule key(s) {sorted(unknown)}")
            if sum(k in rule for k in ("const", "from", "template")) != 1:
                problems.append(f"{name}: give exactly one of const, from, template")
            if rule.get("type") and rule["type"] not in TYPES:
                problems.append(f"{name}: type {rule['type']!r} is not one of {sorted(TYPES)}")
            if rule.get("dates") and rule["dates"] not in R.DATE_FORMATS:
                problems.append(f"{name}: dates {rule['dates']!r} is not one of {sorted(R.DATE_FORMATS)}")
            for kind, ref in _REF.findall(str(rule.get("template", "")) + str(rule.get("else", "")) + str(rule.get("default", ""))):
                if kind == "@" and (ref not in self.fields or list(self.fields).index(ref) >= list(self.fields).index(name)):
                    problems.append(f"{name}: {{@{ref}}} must name a field mapped above it")
        lists = {n.split("[]")[0] for n in self.fields if "[]." in n}
        for ln in lists:
            if ln not in self.children:
                problems.append(f"{ln}[]: its fields need inputs.children.{ln} (the file its rows come from)")
        for ln in self.children:
            if ln not in lists:
                problems.append(f"inputs.children.{ln}: no field {ln}[].X maps it")
        mapped_tops = {n.split(".")[0].removesuffix("[]") for n in self.fields}
        missing = [f for f in c.required if f not in mapped_tops and f not in self.omit]
        if missing:
            problems.append(f"no rule for the required field(s) {missing} (map them, or name them in omit-required with the reason)")
        for path in c.nested:
            top = path.split(".")[0].removesuffix("[]")
            if top in mapped_tops and path.count(".") == 0:
                need = [f"{path}.{n}" for n in c.required_in(path) if f"{path}.{n}" not in self.fields and f"{path}.{n}" not in self.omit]
                if need:
                    problems.append(f"no rule for the required nested field(s) {need}")
        for f in self.omit:
            top = f.split(".")[0].removesuffix("[]")
            fd = c.field(f)
            if fd is None or fd.get("presence") != "required" or f in self.fields:
                problems.append(f"omit-required {f}: only a required contract field without a rule is omitted")
            elif "." not in f and top not in c.required:
                problems.append(f"omit-required {f}: not required by the contract")
        if self.on_reject not in ("refuse", "skip"):
            problems.append("on-reject is refuse or skip")
        if self.max_rejects is not None and not re.fullmatch(r"\d+(\.\d+)?%?", str(self.max_rejects)):
            problems.append("max-rejects is a count (10) or a share (1%)")
        if self.pass_columns != "*" and not isinstance(self.pass_columns, list):
            problems.append("pass-through.columns is \"*\" or a list of columns")
        bad_checks = sorted(set(self.checks) - {"unique", "one-value", "when", "links"})
        if bad_checks:
            problems.append(f"checks: unknown key(s) {bad_checks}")
        if problems:
            raise PvfError(f"{self.path}: " + "; ".join(problems))

    # ---- run values ---------------------------------------------------------------------------------------------------------------
    def resolve_run(self, business_date: str, known: dict | None = None) -> dict:
        """The run values with ``{$business_date}`` and the facts of the run (``{$main_file_modified}``) filled in."""
        ctx = {"business_date": business_date, **(known or {})}
        for k, v in self.run.items():
            ctx[k] = self._fill(str(v), {}, {}, ctx) if isinstance(v, str) else v
        return ctx

    # ---- the columns a file must have ---------------------------------------------------------------------------------------------
    def columns_needed(self, child: str | None = None) -> list[str]:
        """The columns the main file (or the child file ``child``) must have: those the mapping's ``inputs`` list as required."""
        spec = self.children[child] if child else self.main
        rename = {R.column_name(k): R.column_name(v) for k, v in (spec.get("rename") or {}).items()}
        return [rename.get(R.column_name(c), R.column_name(c)) for c in spec.get("required") or []]

    def columns_read(self, child: str | None = None) -> set[str]:
        """Every column a rule of the main file (or of the child ``child``) names."""
        out = set()
        for name, rule in self.fields.items():
            if (child is None and "[]." in name) or (child is not None and not name.startswith(child + "[].")):
                continue
            if not isinstance(rule, dict):
                continue
            src = rule.get("from")
            out.update(R.column_name(c) for c in (src if isinstance(src, list) else [src] if src else []))
            for kind, ref in _REF.findall(str(rule.get("template", "")) + str(rule.get("else", "")) + str(rule.get("default", ""))):
                if not kind:
                    out.add(R.column_name(ref))
        return out

    # ---- one row ------------------------------------------------------------------------------------------------------------------
    def clean_row(self, row: dict) -> dict:
        """Every cell cleaned (trimmed, null words to None); a spreadsheet error or a formula rejects the row."""
        return {k: R.clean(v, self.dialect, k) for k, v in row.items()}

    def apply_row(self, row: dict, ctx: dict, child: str | None = None) -> dict:
        """The flat record (dotted names for nested fields) of one cleaned row of the main file, or of the child file ``child``."""
        out: dict = {}
        for name, rule in self.fields.items():
            if (child is None and "[]." in name) or (child is not None and not name.startswith(child + "[].")):
                continue
            short = name.split("[].", 1)[1] if child else name
            self._column = None
            try:
                v = self._value(name, rule, row, out, ctx)
            except RowProblem as e:
                raise Problem(e.reason, e.detail, self._column or "", name, None) from None
            except PvfError as e:
                raise Problem("BAD_VALUE", str(e), self._column or "", name) from None
            if _empty(v) or v == []:
                f = self.contract.field(name) or {}
                if f.get("presence") == "required" and name not in self.omit:
                    raise Problem("MISSING_REQUIRED", "required and empty", self._column or _describe_source(rule), name, None,
                                  f"give the {name}")
                continue
            out[short if child else name] = v
        if child is None:
            out.update(self.extras(row))
        return out

    def extras(self, row: dict) -> dict:
        """Source columns kept beside the contract's fields (pass-through): those not read by a rule, not refused, not excluded."""
        if not self.pass_columns:
            return {}
        read = self.columns_read()
        cols = list(row) if self.pass_columns == "*" else [R.column_name(c) for c in self.pass_columns]
        out = {}
        for col in cols:
            if col in read or col in self.pass_exclude or col in self.refuse_columns or row.get(col) is None:
                continue
            out[self.pass_prefix + col if col in self.contract.fields else col] = row[col]
        return out

    def _cell(self, row: dict, column: str, name: str):
        col = R.column_name(column) if column else column
        v = row.get(col)
        if not _empty(v):
            self._column = col
        return v

    def _value(self, name, rule, row, out, ctx):
        rule = dict(rule)
        if "from" in rule:
            src = rule["from"]
            rule["from"] = [R.column_name(c) for c in src] if isinstance(src, list) else R.column_name(src)
        return super()._value(name, rule, row, out, ctx)

    def _type_value(self, name: str, rule: dict, v, used):
        """``trim``, ``lower``, ``pad``, ``split``, then the value typed by the file's dialect and the field's own format."""
        if _empty(v):
            return None
        col = self._column or ""
        f = self.contract.field(name) or {}
        t = rule.get("type") or f.get("type") or "text"
        t = {"number": "amount", "list": "id list"}.get(t, t)
        if isinstance(v, (int, float)) and not isinstance(v, bool) and t in ("amount", "rate", "integer"):
            return int(v) if t == "integer" else float(v)
        if isinstance(v, bool):
            return v
        s = _text(v)
        if rule.get("trim"):
            s = s.strip()
        if rule.get("lower"):
            s = s.lower()
        if rule.get("pad"):
            pad = rule["pad"]
            s = s.rjust(int(pad["width"]), str(pad.get("with", "0")))
        if t.endswith(" list") or t == "vector":
            sep = rule.get("split")
            parts = [p.strip() for p in (s.split(sep) if sep else [s])]
            parts = [p for p in parts if p]
            each = {"text list": "text", "id list": "id", "date list": "date", "vector": "amount"}[t]
            return [self._scalar(each, p, rule, col, name) for p in parts]
        return self._scalar(t, s, rule, col, name)

    def _scalar(self, t: str, s: str, rule: dict, col: str, name: str):
        d = self.dialect
        if t == "amount":
            return R.number(s, d, col, name, rule.get("decimal"))
        if t == "rate":
            return R.rate(s, d, col, name, bool(rule.get("percent")))
        if t == "integer":
            return R.integer(s, d, col, name)
        if t == "date":
            return R.date(s, d, col, name, rule.get("dates"))
        if t == "timestamp":
            return R.timestamp(s, d, col, name)
        if t == "boolean":
            return R.boolean(s, d, col, name)
        if t == "id":
            return R.ident(s, col, name)
        return s


def _on(spec: dict) -> dict:
    """A child's spec with its ``on:`` key as written (YAML 1.1 reads a bare ``on`` as true)."""
    if True in spec:
        spec["on"] = spec.pop(True)
    return spec


def nest(flat: dict) -> dict:
    """Dotted names to nested records: ``{"KYC.STATUS": "A"}`` -> ``{"KYC": {"STATUS": "A"}}`` (a record whose every part is empty is left out)."""
    out: dict = {}
    for k, v in flat.items():
        if "." in k and not k.startswith("."):
            head, rest = k.split(".", 1)
            out.setdefault(head, {})
            if isinstance(out[head], dict):
                out[head][rest] = v
        else:
            out[k] = v
    return {k: (nest(v) if isinstance(v, dict) else v) for k, v in out.items()}


def _describe_source(rule: dict) -> str:
    src = rule.get("from")
    return R.column_name(src[0] if isinstance(src, list) else src) if src else ""


_SHARED_CSV: dict | None = None


def _shared_csv() -> dict:
    """The shared CSV defaults (kinds/shared/csv.yaml)."""
    global _SHARED_CSV
    if _SHARED_CSV is None:
        p = KINDS_DIR / "shared" / "csv.yaml"
        _SHARED_CSV = (yaml.safe_load(p.read_text(encoding="utf-8")) or {}) if p.is_file() else {}
    return dict(_SHARED_CSV)


__all__ = ["KindMapping", "mapping_path", "nest", "_key"]
