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

"""Writes (or checks) docs/contracts/CSV_FORMATS.md: one document with a section for every kind the CSV loader has a mapping for, from
the kind's contract, its mapping file(s) and its committed sample. Nothing in it is typed into this generator, so the document cannot
drift from the mapping.

examples (from the repository root):
  python3 tools/loaders/mrloader/kind_docs.py            write the document
  python3 tools/loaders/mrloader/kind_docs.py --check    fail (exit 1) when it differs from what would be written (the tests run this)
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

_TOOLS = str(Path(__file__).resolve().parents[2])
if _TOOLS not in sys.path:
    sys.path.insert(0, _TOOLS)

import yaml  # noqa: E402

from loaders.mrloader import csvread as R  # noqa: E402
from loaders.mrloader.common import ROOT  # noqa: E402
from loaders.mrloader.kindmap import KINDS_DIR, KindMapping  # noqa: E402

HERE = Path(__file__).resolve().parent
OUT = ROOT / "docs" / "contracts" / "CSV_FORMATS.md"
SAMPLES = HERE / "samples"
LICENCE = """<!--
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
"""
WAVES = {"scenario-set": 1, "scenario-pv": 1, "fx-scenario": 1, "var-limit": 1, "risk-factor": 1, "position-sensitivity": 1, "desk-pnl": 1,
         "legal-entity": 1, "desk": 1, "book": 1, "counterparty-group": 2, "counterparty": 2, "agreement": 2, "csa": 2, "netting-set": 2,
         "collateral-balance": 2, "credit-limit": 2, "trade-valuation": 2}
CODES = {"amount": "BAD_NUMBER", "rate": "BAD_NUMBER", "integer": "BAD_INTEGER", "date": "BAD_DATE", "timestamp": "BAD_TIMESTAMP",
         "boolean": "BAD_BOOLEAN", "id": "EXCEL_NUMBER_ID"}


def _md(text) -> str:
    return str(text).replace("|", "\\|").replace("\n", " ")


def mappings() -> dict[str, list[KindMapping]]:
    """{kind: [canonical mapping, source variants ...]} of every shipped mapping file but PVF's scenario-PV one (its own README)."""
    out: dict[str, list[KindMapping]] = {}
    for p in sorted(KINDS_DIR.glob("*.yaml")):
        if p.name == "scenario-pv.pvf.yaml":
            continue
        m = KindMapping(p)
        out.setdefault(m.kind, []).append(m)
    for v in out.values():
        v.sort(key=lambda m: (m.source != "canonical", m.source))
    return out


def comments(path: Path) -> dict[str, str]:
    """The comment beside each field's rule in a mapping file: the "Decision" column of the page."""
    out = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        m = re.match(r"^\s{2}([A-Z_][A-Z0-9_.\[\]]*):.*?\s#\s(.+)$", line)
        if m:
            out[m.group(1)] = m.group(2).strip()
    return out


def rule_words(rule: dict, t: str, dialect: R.Dialect) -> str:
    """A field's rule in words."""
    parts = []
    if "const" in rule:
        parts.append(f"always `{rule['const']}`")
    if "template" in rule:
        parts.append(f"built: `{rule['template']}`")
    if rule.get("upper"):
        parts.append("upper-cased")
    if rule.get("lower"):
        parts.append("lower-cased")
    if rule.get("slug"):
        parts.append("upper-cased, other characters `-`")
    if rule.get("prefix"):
        parts.append(f"prefixed `{rule['prefix']}` unless it has it")
    if rule.get("pad"):
        parts.append(f"padded to {rule['pad']['width']} with `{rule['pad'].get('with', '0')}`")
    if rule.get("split"):
        parts.append(f"a list, `{rule['split']}` between values")
    if "map" in rule:
        parts.append("mapped: " + ", ".join(f"`{k}` to `{v}`" for k, v in rule["map"].items()))
    if "default" in rule:
        parts.append(f"empty: `{rule['default']}`")
    fmt = {"amount": f"a number (decimal `{dialect.decimal}`)", "rate": "a fraction (0.05)" + (", or a percentage" if rule.get("percent") else ""),
           "integer": "a whole number", "date": f"a date ({rule.get('dates') or dialect.dates}, or YYYYMMDD)",
           "timestamp": "ISO-8601 with a zone", "boolean": "true/false, Y/N, 1/0", "id": "text, kept as written"}.get(t)
    if fmt:
        parts.append(fmt)
    return "; ".join(parts) or "as written"


def column_table(m: KindMapping, child: str | None, notes: dict) -> list[str]:
    rows = ["| Column | Required | Field | Rule | Description | Decision |", "|---|---|---|---|---|---|"]
    for name, rule in m.fields.items():
        if (child is None and "[]." in name) or (child is not None and not name.startswith(child + "[].")):
            continue
        f = m.contract.field(name) or {}
        t = rule.get("type") or f.get("type", "text")
        src = rule.get("from")
        cols = [R.column_name(c) for c in (src if isinstance(src, list) else [src])] if src else []
        refs = [R.column_name(r) for k, r in re.findall(r"\{([$@]?)([A-Za-z_][A-Za-z0-9_]*)\}",
                                                         str(rule.get("template", "")) + str(rule.get("default", ""))) if not k]
        col = ", then ".join(f"`{c}`" for c in cols + [r for r in refs if r not in cols]) if cols or refs else "(none)"
        req = "yes" if f.get("presence") == "required" and "default" not in rule and src else "no"
        rows.append(f"| {col} | {req} | `{name}` | {_md(rule_words(rule, t, m.dialect))} | {_md(f.get('description', ''))[:220]} | "
                    f"{_md(notes.get(name, ''))} |")
    return rows


def dependents(kind: str, all_maps: dict) -> list[str]:
    return sorted({m.kind for ms in all_maps.values() for m in ms if kind in m.after})


def page(kind: str, ms: list[KindMapping], all_maps: dict, n: int = 0) -> str:
    """One kind's section; its numbered headings carry the kind's number in the index (n.1, n.2 ...) so that no two headings share one."""
    m = ms[0]
    c = m.contract
    notes = comments(m.path)
    child_opts = "".join(f" --child {n}=FILE" for n in m.children)
    rel = m.path.relative_to(ROOT).as_posix()
    run_opts = " --source-system SYSTEM" if "{$source_system}" in json.dumps(m.fields) else ""
    lines = [f"## {kind}: {c.doc.get('title', kind)} (from CSV)", "",
             f"### {n}.1 What it is and who reads it", "", m.about, "", _md(c.doc.get("description", "")), "",
             f"- **Contract:** `{c.path.relative_to(ROOT).as_posix()}` (pack `{c.pack}`, class `{c.klass}`), key `{c.key}`.",
             f"- **Mapping:** `{rel}` (source `{m.source}`); wave {WAVES.get(kind, '-')} of the CSV loaders.",
             f"- **Grain:** {_md(c.doc.get('grain', ''))}", "",
             f"### {n}.2 The command", "", "```bash",
             f"python3 tools/loaders/mrloader/csv_load.py --kind {kind} --csv FILE{child_opts}{run_opts} --date YYYY-MM-DD --dry-run   # check; write nothing",
             f"python3 tools/loaders/mrloader/csv_load.py --kind {kind} --csv FILE{child_opts}{run_opts} --date YYYY-MM-DD --inbox     # convert and hand to the data inbox",
             f"python3 tools/loaders/mrloader/csv_load.py --kind {kind} --template                                       # the header and an example row",
             "```", "",
             f"In a day file: `{kind}: {{csv: \"FILE\"" + "".join(f", child: {{{n}: \"FILE\"}}" for n in m.children) + "}`. "
             "A bank's own column names: a mapping file of its own, given with `--mapping FILE`.", "",
             f"### {n}.3 The CSV, column by column", "",
             f"The file's format: delimiter `{m.dialect.delimiter}`, encoding `{m.dialect.encoding}`, decimal `{m.dialect.decimal}`, thousands "
             f"`{m.dialect.thousands}`, dates `{m.dialect.dates}` (declared in the mapping's `csv:` block over `kinds/shared/csv.yaml`; never guessed). "
             "Column names compare upper-cased, other characters as `_`.", ""]
    lines += column_table(m, None, notes)
    for n, spec in m.children.items():
        on = spec.get("on")
        lines += ["", f"**The child file `{n}`** (`--child {n}=FILE`): one row per element of `{n}`, joined to its record by "
                  f"{', '.join(f'`{x}`' for x in (on if isinstance(on, list) else [on]))}; a record with no row: "
                  f"{'rejected (NO_CHILDREN)' if spec.get('empty', 'reject') == 'reject' else 'kept'}; never twice the same "
                  f"{', '.join(spec.get('unique') or []) or 'row'}.", ""]
        lines += column_table(m, n, notes)
    lines += ["", f"### {n}.4 A sample", ""]
    sample = SAMPLES / f"{kind}.csv"
    expected = SAMPLES / f"{kind}.expected.jsonl"
    if sample.is_file():
        head = sample.read_text(encoding="utf-8").splitlines()[:3]
        lines += [f"The first rows of `tools/loaders/mrloader/samples/{kind}.csv` (synthetic):", "", "```csv", *head, "```", ""]
    if expected.is_file():
        first = expected.read_text(encoding="utf-8").splitlines()[0]
        lines += [f"The first record of `tools/loaders/mrloader/samples/{kind}.expected.jsonl` (records are written in key order):", "", "```json", first, "```", ""]
    lines += [f"### {n}.5 What is not loaded", ""]
    gone = [f"| `{k}` | a column of this name | {_md(v)} |" for k, v in m.refuse_columns.items()]
    gone += [f"| `{k}` | a required field left out | {_md(v)} |" for k, v in m.omit.items()]
    gone += [f"| `{n}` | derived | {_md(f.get('note', 'Drishti computes it'))} |" for n, f in c.fields.items() if f.get("presence") == "derived"]
    lines += (["| Name | What | Why |", "|---|---|---|", *gone] if gone else ["Nothing: every field the CSV gives is loaded."]) + [""]
    lines += [f"### {n}.6 Checks", "",
              f"- **A rejected row:** `on-reject: {m.on_reject}`" + (f", `max-rejects: {m.max_rejects}`" if m.max_rejects else "") + " ("
              + ("any rejected row refuses the whole kind: fix the file and run again" if m.on_reject == "refuse" else
                 "rejected rows are listed and the rest loads") + "). A duplicate key always refuses it.",
              f"- **Unique:** `{c.key}`" + "".join(f", `{u}`" for u in (m.checks.get("unique") or []) if u != c.key) + " per business date.",
              *[f"- **When** {_md(json.dumps(w.get('if')))}: {', '.join(f'`{x}`' for x in w.get('need') or [])} needed (CONDITION)."
                for w in m.checks.get("when") or []],
              *[f"- **One value a date:** `{x}`." for x in m.checks.get("one-value") or []]]
    from loaders.mrloader.links import links_of
    for f, spec in links_of(kind, m.checks).items():
        lines.append(f"- **Link** `{f}` to `{spec['to']}`: policy `{spec.get('policy', 'warn')}` (mr_run.py checks it against the day's "
                     f"`{spec['to']}` file, else `--against`; a target found nowhere is *not checked*).")
    types = sorted({CODES[t] for t in ((m.contract.field(n) or {}).get("type") for n in m.fields) if t in CODES})
    lines += ["", "Reject codes it can give: " + ", ".join(f"`{x}`" for x in ["MISSING_REQUIRED", *types, "BAD_ENUM", "BAD_PATTERN",
                                                                         "DUPLICATE_KEY", "EXCEL_ERROR", "FORMULA", "BAD_ROW_SHAPE"]) + ".",
              "Each line of `<kind>.<date>.rejects.csv` gives FILE, ROW (as a spreadsheet shows it), KEY, COLUMN, FIELD, VALUE, REASON, WHY, FIX.", ""]
    lines += [f"### {n}.7 Before and after", "",
              f"- **Load first:** {', '.join(f'`{x}`' for x in m.after) or 'nothing'}.",
              f"- **Then:** {', '.join(f'`{x}`' for x in dependents(kind, all_maps)) or 'nothing depends on it'}.", "",
              f"### {n}.8 Repeat, restate, remove", "",
              f"- **Again for the same date:** the same command; the date's `{kind}` rows are replaced as a whole (`mode: replace`).",
              "- **Restate a past date:** run with that `--date`" + (" and `--restatement 1` (then 2 ...)" if "RESTATEMENT" in m.fields else "")
              + "; to change one row, send the whole file again.",
              f"- **Remove the date:** `python3 tools/drishti.py lake delete --lake LAKE --kind {kind} --date YYYY-MM-DD --dry-run` (then without `--dry-run`).",
              ""]
    risky = [n for n in m.fields if (m.contract.field(n) or {}).get("type") in ("id", "date") and m.fields[n].get("from")]
    lines += [f"### {n}.9 Spreadsheet warnings", ""]
    lines += ([f"- `{n}`: " + ("an id: export the column as text (a long id saved as a number, `2.00001E+11`, is refused; leading zeros "
                                "lost are not detectable without `pad:`)" if (m.contract.field(n) or {}).get("type") == "id"
                                else "a date: a spreadsheet may rewrite it to the machine's locale; declare `csv.dates` or export ISO dates")
               for n in risky] or ["None of this kind's columns is at risk."]) + [""]
    lines += [f"### {n}.10 Source variants", ""]
    if len(ms) == 1:
        lines.append("None shipped. A source with other column names gets a mapping file `kinds/" + kind + ".<source>.yaml` (or a site's own, by `--mapping`).")
    for v in ms[1:]:
        lines += [f"#### `{v.source}`: `{v.path.relative_to(ROOT).as_posix()}`", "", v.about, "",
                  f"`python3 tools/loaders/mrloader/csv_load.py --kind {kind} --source {v.source} --csv FILE --date YYYY-MM-DD`", ""]
        if v.main.get("valid"):
            lines += [f"Only the rows valid on the date are read (`{v.main['valid'].get('from')}` <= date < `{v.main['valid'].get('to')}`).", ""]
        lines += column_table(v, None, comments(v.path)) + [""]
    return "\n".join(lines).rstrip() + "\n"


def slug(heading: str) -> str:
    return re.sub(r"[^\w\- ]", "", heading.lower()).replace(" ", "-")


def index(all_maps: dict) -> str:
    never = yaml.safe_load((KINDS_DIR / "shared" / "never.yaml").read_text(encoding="utf-8")) or {}
    from loaders.mrloader.hooks import DEFAULT_ORDER
    rank = {k: i for i, k in enumerate(DEFAULT_ORDER)}
    lines = [LICENCE + "# The CSV formats of every kind", "",
             "*Generated by `tools/loaders/mrloader/kind_docs.py` from each kind's contract, mapping file and sample: do not edit; change the mapping "
             "or the contract and run it again.* One section per kind; the day's runbook and the tool's conventions are in "
             "[the bank data guide](../guides/BANK_DATA_GUIDE.md).", "",
             "| # | Kind | Wave | Pack | Inputs | Load first | Sources |", "|---|---|---|---|---|---|---|"]
    kinds = sorted(all_maps, key=lambda k: (rank.get(k, 99), k))
    for i, k in enumerate(kinds, 1):
        m = all_maps[k][0]
        lines.append(f"| {i} | [`{k}`](#{slug(heading(k, all_maps))}) | {WAVES.get(k, '-')} | `{m.pack}` | `--csv`{''.join(f', `--child {n}=`' for n in m.children)} | "
                     f"{', '.join(f'`{x}`' for x in m.after) or '-'} | {', '.join(x.source for x in all_maps[k])} |")
    lines += ["", "The scenario PVs, the scenario set and the scenario FX of the PVF source are written by `pvf_run.py`, "
              "`pvf_scenario_set.py` and `pvf_fx_scenario.py` (the bank data guide).", "",
              "## Never loaded from CSV", "", "| Kind | Why |", "|---|---|"]
    lines += [f"| `{k}` | {_md(v)} |" for k, v in never.items()]
    lines += ["| every kind of class `computed` or `drishti-owned` | Drishti computes or keeps it |"]
    return "\n".join(lines) + "\n"


def heading(kind: str, all_maps: dict) -> str:
    return f"{kind}: {all_maps[kind][0].contract.doc.get('title', kind)} (from CSV)"


def expected() -> dict[Path, str]:
    """The whole document: the index, then every kind's section."""
    all_maps = mappings()
    from loaders.mrloader.hooks import DEFAULT_ORDER
    rank = {k: i for i, k in enumerate(DEFAULT_ORDER)}
    body = [page(k, all_maps[k], all_maps, i) for i, k in enumerate(sorted(all_maps, key=lambda k: (rank.get(k, 99), k)), 1)]
    return {OUT: index(all_maps) + "\n" + "\n".join(body)}


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0], epilog=__doc__.split("\n\n", 1)[1],
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--check", action="store_true", help="fail when a page differs from what would be written")
    a = p.parse_args(argv)
    stale = []
    for path, text in expected().items():
        if a.check:
            if not path.is_file() or path.read_text(encoding="utf-8") != text:
                stale.append(path.relative_to(ROOT).as_posix())
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding="utf-8")
    if a.check:
        if stale:
            print("CSV formats stale (run python3 tools/loaders/mrloader/kind_docs.py): " + ", ".join(stale))
            return 1
        print("CSV formats are current")
    else:
        print(f"wrote {OUT.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
