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

"""The checks across kinds (docs/guides/BANK_DATA_GUIDE.md section 11): each link of a kind's records (its contract's ``link:`` fields, and
the mapping's ``checks.links``, which set a policy or name another target) is looked up in the target kind's output of the same day
(``mr-<date>/<target>.<date>.jsonl[.gz]``) or in ``--against`` folders (yesterday's outputs, for a master not re-sent today). A target
found nowhere is reported as *not checked*, never as passed. The policy is ``warn`` unless the mapping says ``refuse`` (or the run says
--strict-links): a row whose key no master knows is kept and flagged, as the contracts say.

The limit's node check (``to: var-node``) needs the tree, not a table: the node ids are built from the date's scenario-PV records by the
market-risk pack's ``hierarchies:`` block (the level columns; ``ids: leaf`` or path), the way the server builds them, read from the pack."""
from __future__ import annotations

import csv
from collections import Counter
from pathlib import Path

import yaml

from loaders.mrloader.common import ROOT, read_jsonl
from loaders.mrloader.contract import Contract

#: kinds whose link fields are not checked (the target is never a CSV kind: built by the server or another module)
NOT_CSV_TARGETS = {"jurisdiction", "trade", "economy", "news", "position", "path-book", "election", "credit-curve", "trader", "cva", "sa-ccr",
                   "simm"}


def values(rec: dict, path: str) -> list:
    """The values of a dotted path in a record (``SENSITIVITIES[].RISK_FACTOR`` gives one per element; a list field each element)."""
    cur = [rec]
    for part in path.split("."):
        nxt = []
        for c in cur:
            if not isinstance(c, dict):
                continue
            key = part.removesuffix("[]")
            v = c.get(key)
            if v is None:
                continue
            nxt += v if isinstance(v, list) else [v]
        cur = nxt
    return [v for v in cur if v not in (None, "") and not isinstance(v, (dict, list))]


def target_file(kind: str, business_date: str, folder: Path, against: list[Path]) -> Path | None:
    """The target kind's records: the day's own file, else the newest file of the kind in the --against folders."""
    for name in (f"{kind}.{business_date}.jsonl", f"{kind}.{business_date}.jsonl.gz"):
        if (Path(folder) / name).is_file():
            return Path(folder) / name
    found = []
    for a in against:
        found += [p for p in Path(a).rglob(f"{kind}.*.jsonl*") if p.name.endswith((".jsonl", ".jsonl.gz"))]
    return sorted(found, key=lambda p: p.name)[-1] if found else None


def var_nodes(pv_records, pack_dir: Path | None = None) -> set[str]:
    """The node ids the date's scenario-PV records build in every hierarchy of the market-risk pack (``H~v`` for ``ids: leaf``, else
    ``H~v1/v2``), plus each hierarchy's root."""
    pack_dir = pack_dir or ROOT / "modules" / "banking" / "packs" / "market-risk"
    doc = yaml.safe_load((pack_dir / "pack.yaml").read_text(encoding="utf-8")) or {}
    out: set[str] = set()
    hs = doc.get("hierarchies") or {}
    for h, spec in hs.items():
        if (spec.get("root") or {}).get("id"):
            out.add(f"{h}~{spec['root']['id']}")
    for r in pv_records:
        for h, spec in hs.items():
            levels = [x.strip() for x in str(spec.get("levels", "")).split(">") if x.strip()]
            path = []
            for lv in levels:
                v = r.get(lv)
                if v in (None, ""):
                    break
                path.append(str(v))
                out.add(f"{h}~{path[-1]}" if spec.get("ids") == "leaf" else f"{h}~{'/'.join(path)}")
    return out


def links_of(kind: str, mapping_checks: dict | None) -> dict[str, dict]:
    """A kind's links to check: {field: {to, field (the target's), policy}} from its contract, then its mapping's checks.links."""
    c = Contract.for_kind(kind)
    out = {}
    for f, target in c.links.items():
        if target in NOT_CSV_TARGETS or target == kind:
            continue
        out[f] = {"to": target, "field": None, "policy": "warn"}
    for f, spec in ((mapping_checks or {}).get("links") or {}).items():
        out[str(f)] = {**out.get(str(f), {}), "to": spec.get("to", out.get(str(f), {}).get("to")), "field": spec.get("field"),
                       "from-kind": spec.get("from-kind"), "policy": spec.get("policy", "warn")}
    return out


def check(kind: str, records: list[dict], mapping_checks: dict | None, folder: Path, business_date: str, against: list[Path] = (),
          strict: bool = False, keys_of=None) -> list[dict]:
    """Each link's result: FIELD, TO, POLICY, CHECKED (False: the target was found nowhere), VALUES, UNRESOLVED ({value: count}),
    FIRST (a key with an unresolved value), REFUSE (a miss under policy refuse)."""
    out = []
    key = Contract.for_kind(kind).key
    for f, spec in links_of(kind, mapping_checks).items():
        target = spec["to"]
        known = None
        if target == "var-node":
            src = target_file(spec.get("from-kind") or "scenario-pv", business_date, folder, list(against))
            known = var_nodes(read_jsonl(src)) if src else None
        else:
            src = target_file(target, business_date, folder, list(against))
            if src:
                fields = spec.get("field") or [Contract.for_kind(target).key]
                fields = fields if isinstance(fields, list) else [fields]
                known = set()
                for r in read_jsonl(src):
                    for tf in fields:
                        known.update(str(v) for v in values(r, tf))
        miss: Counter = Counter()
        first = {}
        n = 0
        for r in records:
            for v in values(r, f):
                if target == "var-node" and str(v).startswith("PF-"):
                    continue                                      # a system basket's limit: the basket lives in Drishti's store
                n += 1
                if known is not None and str(v) not in known:
                    miss[str(v)] += 1
                    first.setdefault(str(v), r.get(key))
        if n == 0:
            continue                                              # no record carries the field: nothing to check
        policy = "refuse" if strict else spec.get("policy", "warn")
        out.append({"KIND": kind, "FIELD": f, "TO": target, "POLICY": policy, "CHECKED": known is not None, "VALUES": n,
                    "UNRESOLVED": dict(miss.most_common()), "FIRST": first, "REFUSE": bool(miss) and policy == "refuse",
                    "AGAINST": str(src) if src else None})
    return out


def write_report(path: Path, results: list[dict]) -> Path:
    """``links-<date>.csv``: one line per unresolved value (KIND, FIELD, TO, POLICY, VALUE, COUNT, FIRST_KEY) and one per link not
    checked (VALUE empty, COUNT -1)."""
    with open(path, "w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh, lineterminator="\n")
        w.writerow(["KIND", "FIELD", "TO", "POLICY", "VALUE", "COUNT", "FIRST_KEY"])
        for r in results:
            if not r["CHECKED"]:
                w.writerow([r["KIND"], r["FIELD"], r["TO"], r["POLICY"], "", -1, "not checked: no target found"])
            for v, n in r["UNRESOLVED"].items():
                w.writerow([r["KIND"], r["FIELD"], r["TO"], r["POLICY"], v, n, r["FIRST"].get(v, "")])
    return path


def say(results: list[dict]) -> list[str]:
    """One line per link with a miss or not checked."""
    lines = []
    for r in results:
        if not r["CHECKED"]:
            lines.append(f"{r['KIND']}.{r['FIELD']} -> {r['TO']}: not checked (no {r['TO']} file for the date or in --against)")
        elif r["UNRESOLVED"]:
            top = ", ".join(f"{v} ({n})" for v, n in list(r["UNRESOLVED"].items())[:3])
            lines.append(f"{r['KIND']}.{r['FIELD']} -> {r['TO']}: {sum(r['UNRESOLVED'].values())} unresolved of {r['VALUES']} "
                         f"[{r['POLICY']}]: {top}")
    return lines
