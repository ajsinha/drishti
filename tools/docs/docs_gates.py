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

"""The docs gates (docs/architecture/DOCS_PLAN.md section 5): the documentation's own rules, checked from the files.

    python3 tools/docs/docs_gates.py              every gate, its findings printed; exit 0 (warn mode)
    python3 tools/docs/docs_gates.py --strict     exit 1 when any gate has a finding (fail mode, from the end of 2.9.11.g)
    python3 tools/docs/docs_gates.py --json       the findings as JSON, by gate

The gates: the feature register (config/docs-features.yaml: every major feature has one guide and one tutorial, or `after <release>`);
the count caps (guides, tutorials, references, contracts, knowledge primers, Help entries); the page budgets (a guide or tutorial over its
budget by more than the tolerance); one home for data (a JSON record or a field table outside docs/contracts), for settings (a `drishti.*`
row outside CONFIGURATION.md), endpoints (an endpoint row outside the API guide) and functions (a function row outside FUNCTIONS.md); no
repeated prose (the same 40-word run in two customer documents); customer text (version history, defect words, links to internal
documents); Help is user documentation (an entry under docs/architecture/ for everyone, an entry without domain and lenses); and the
generated sections that must be current (the guides' data tables, the connector reference). A malformed register always fails.
The executable-tutorials gate (each tutorial's command-line block run against the demo lakes) is run by hand once per release.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import re
import sys
from collections import defaultdict
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
DOCS = ROOT / "docs"
REGISTER = ROOT / "config" / "docs-features.yaml"
HELP = ROOT / "drishti-console" / "config" / "help.yaml"
INTERNAL_DIRS = ("architecture", "lenses", "qa", "strategy", "requirements", "publications")
FEATURE_KEYS = {"id", "name", "who", "guide", "guide_budget", "tutorial", "tutorial_budget", "domain", "lenses", "section", "sources", "contracts"}
DOMAINS = {"core", "risk", "world"}
SHINGLE = 40
SHOWN = 12            # findings printed per gate

FENCE = re.compile(r"^```(\w*)\s*$")
VERSION = re.compile(r"\bsince (?:release |version )?\d+\.\d+(?:\.\d+)*[a-z]?\b|\((?:release |since )?\d\.\d+\.\d+(?:\.[a-z])?\)", re.I)
DEFECT = re.compile(r"\b(?:bugs?|workarounds?|known issues?|defects?|regressions?)\b", re.I)
INTERNAL_LINK = re.compile(r"\]\((?:\.\./)*(?:docs/)?(?:%s)/" % "|".join(INTERNAL_DIRS))
FIELD_TABLE = re.compile(r"^\|\s*(?:Field|Column)\s*\|\s*Type\b", re.I)
SETTING_ROW = re.compile(r"^\|\s*`drishti\.[a-z0-9.-]+`\s*\|")
ENDPOINT_ROW = re.compile(r"^\|\s*`(?:GET|POST|PUT|PATCH|DELETE) /")
FUNCTION_ROW = re.compile(r"^\|\s*`[A-Z][A-Z0-9_]*\(")


def _rel(p: Path) -> str:
    return p.relative_to(ROOT).as_posix()


def internal_contracts() -> set[str]:
    """The contracts of engines not built: marked internal on their page (docs/contracts/*.md with an **Internal.** line)."""
    return {_rel(p) for p in (DOCS / "contracts").glob("*.md") if "\n**Internal.**" in p.read_text(encoding="utf-8")}


def customer_docs() -> list[Path]:
    """Every document a customer reads: docs/ outside the internal folders (and the internal contracts), the packs' guides and READMEs,
    the plugins' READMEs."""
    skip = internal_contracts()
    out = [p for p in sorted(DOCS.rglob("*.md"))
           if p.relative_to(DOCS).parts[0] not in INTERNAL_DIRS and _rel(p) not in skip and "/img/" not in _rel(p)]
    out += sorted((ROOT / "config" / "packs").glob("*/guides/*.md")) + sorted((ROOT / "config" / "packs").glob("*/README.md"))
    out += sorted((ROOT / "plugins").glob("*/README.md"))
    return out


def prose(text: str) -> list[tuple[int, str, str | None]]:
    """(line number, line, the fence language or None) of a document."""
    out, lang = [], None
    for i, line in enumerate(text.splitlines(), 1):
        m = FENCE.match(line.strip())
        if m:
            lang = None if lang is not None else (m.group(1) or "")
            continue
        out.append((i, line, lang))
    return out


# ---- the gates --------------------------------------------------------------------------------------------------------------------

def register() -> tuple[dict, list[str]]:
    """The register and its structural errors (these fail in every mode)."""
    if not REGISTER.is_file():
        return {}, [f"{_rel(REGISTER)} is missing"]
    reg = yaml.safe_load(REGISTER.read_text(encoding="utf-8")) or {}
    errors, seen = [], set()
    for f in reg.get("features") or []:
        fid = f.get("id", "?")
        if fid in seen:
            errors.append(f"{fid}: listed twice")
        seen.add(fid)
        unknown = set(f) - FEATURE_KEYS
        if unknown:
            errors.append(f"{fid}: unknown keys {sorted(unknown)}")
        for k in ("name", "guide", "guide_budget", "tutorial", "domain", "lenses", "section"):
            if not f.get(k):
                errors.append(f"{fid}: no {k}")
        if f.get("domain") and f["domain"] not in DOMAINS:
            errors.append(f"{fid}: domain {f['domain']!r} is not one of {sorted(DOMAINS)}")
        t = str(f.get("tutorial") or "")
        if t and not (t.startswith("tutorials/") or t.startswith("after ") or t.startswith("none")):
            errors.append(f"{fid}: tutorial must be a tutorials/ path, 'after <release>' or 'none (<why>)'")
    guides = [f.get("guide") for f in reg.get("features") or []]
    for g in sorted({g for g in guides if guides.count(g) > 1}):
        errors.append(f"{g}: the guide of more than one feature")
    return reg, errors


def gate_register(reg: dict) -> list[str]:
    out = []
    for f in reg.get("features") or []:
        if not (DOCS / f["guide"]).is_file():
            out.append(f"{f['id']} {f['name']}: guide {f['guide']} not written yet")
        t = str(f.get("tutorial"))
        if t.startswith("tutorials/") and not (DOCS / t).is_file():
            out.append(f"{f['id']} {f['name']}: tutorial {t} not written yet")
        for s in f.get("sources") or []:
            if not (DOCS / s).is_file() and s not in (f["guide"], t):
                out.append(f"{f['id']}: source {s} no longer exists (drop it from the register)")
    return out


def help_entries() -> list[dict]:
    y = yaml.safe_load(HELP.read_text(encoding="utf-8"))
    return [g for s in y["sections"] if not s.get("roles") for grp in s["groups"] for g in grp["guides"] if not g.get("roles")]


def counts() -> dict[str, int]:
    return {"guides": len(list((DOCS / "guides").glob("*.md"))),
            "tutorials": len([p for p in (DOCS / "tutorials").glob("*.md") if p.name != "README.md"]),
            "reference": len(list((DOCS / "reference").glob("*.md"))) if (DOCS / "reference").is_dir() else 0,
            "contracts": len([p for p in (DOCS / "contracts").rglob("*.md")]),
            "knowledge": len([p for p in (DOCS / "knowledge").glob("*.md") if p.name != "README.md"]),
            "help-entries": len(help_entries())}


def gate_caps(reg: dict) -> list[str]:
    caps = reg.get("caps") or {}
    return [f"{k}: {n} (cap {caps[k]})" for k, n in counts().items() if k in caps and n > caps[k]]


def hand_lines(path: Path) -> int:
    """Hand-written lines: the lines outside generated blocks (<!-- ...:begin ... --> to <!-- ...:end -->)."""
    n, generated = 0, False
    for line in path.read_text(encoding="utf-8").splitlines():
        if re.match(r"<!-- [\w-]+:begin\b", line) or line.startswith("<!-- data-sections:"):
            generated = True
        elif re.match(r"<!-- (?:[\w-]+:end|/data-sections) -->", line):
            generated = False
        elif not generated:
            n += 1
    return n


def gate_budgets(reg: dict) -> list[str]:
    tol, out = float(reg.get("budget-tolerance", 0.10)), []
    for f in reg.get("features") or []:
        for key, budget in (("guide", f.get("guide_budget")), ("tutorial", f.get("tutorial_budget"))):
            p = DOCS / str(f.get(key))
            if budget and p.is_file():
                n = hand_lines(p)
                if n > budget * (1 + tol):
                    out.append(f"{f['id']} {f[key]}: {n:,} lines, budget {budget:,} (+{(n / budget - 1):.0%})")
    return out


def contract_fields() -> dict[str, set[str]]:
    out = {}
    for f in sorted((ROOT / "config" / "packs").glob("*/contracts/*.contract.yaml")):
        c = yaml.safe_load(f.read_text(encoding="utf-8")) or {}
        if c.get("kind"):
            out[c["kind"]] = {x["name"] for x in c.get("fields") or [] if x.get("name")}
    return out


def gate_one_home(docs: list[Path]) -> list[str]:
    fields = contract_fields()
    out = []
    for p in docs:
        rel = _rel(p)
        in_contracts, in_tutorial = rel.startswith("docs/contracts/"), rel.startswith("docs/tutorials/")
        text = p.read_text(encoding="utf-8")
        lines = prose(text)
        if not in_contracts and not in_tutorial:
            block, start, lang = [], 0, None
            for i, line in enumerate(text.splitlines(), 1):
                m = FENCE.match(line.strip())
                if m and lang is None:
                    lang, start, block = m.group(1), i, []
                elif m:
                    if lang in ("json", "jsonc"):
                        keys = set(re.findall(r'^\s*"([A-Za-z_][\w]*)"\s*:', "\n".join(block), re.M))
                        hit = max(((k, len(keys & fs)) for k, fs in fields.items()), key=lambda x: x[1], default=("", 0))
                        if hit[1] >= 4:
                            out.append(f"{rel}:{start}: a JSON record of `{hit[0]}` ({hit[1]} of its fields): link its contract instead")
                    lang = None
                elif lang is not None:
                    block.append(line)
            for i, line, fence in lines:
                if fence is None and FIELD_TABLE.match(line):
                    out.append(f"{rel}:{i}: a field table: fields belong to the data contracts")
        for i, line, fence in lines:
            if fence is not None:
                continue
            if SETTING_ROW.match(line) and not rel.endswith("admin/CONFIGURATION.md"):
                out.append(f"{rel}:{i}: a settings row: settings belong to CONFIGURATION.md")
            elif ENDPOINT_ROW.match(line) and not rel.endswith(("guides/API_GUIDE.md", "reference/API_REFERENCE.md")):
                out.append(f"{rel}:{i}: an endpoint row: endpoints belong to the API reference")
            elif FUNCTION_ROW.match(line) and not rel.endswith(("guides/FUNCTIONS.md", "reference/FUNCTIONS.md")):
                out.append(f"{rel}:{i}: a function row: functions belong to FUNCTIONS.md")
    return out


def gate_repeated(docs: list[Path]) -> list[str]:
    """Pairs of customer documents sharing a 40-word run of prose (tables, code and generated blocks aside)."""
    where: dict[bytes, set[str]] = defaultdict(set)
    for p in docs:
        words = []
        for _, line, fence in prose(p.read_text(encoding="utf-8")):
            if fence is None and not line.lstrip().startswith(("|", "<!--", "#")):
                words += re.findall(r"[\w'’-]+", line.lower())
        rel = _rel(p)
        for k in range(0, max(0, len(words) - SHINGLE + 1), 4):
            where[hashlib.blake2b(" ".join(words[k:k + SHINGLE]).encode(), digest_size=8).digest()].add(rel)
    pairs: dict[tuple[str, str], int] = defaultdict(int)
    for files in where.values():
        if 1 < len(files) <= 6:
            fs = sorted(files)
            for a in range(len(fs)):
                for b in range(a + 1, len(fs)):
                    pairs[(fs[a], fs[b])] += 1
    return [f"{a} and {b}: {n} shared 40-word runs" for (a, b), n in sorted(pairs.items(), key=lambda x: -x[1])]


def gate_customer_text(docs: list[Path]) -> list[str]:
    listed = {str(g.get("doc")) for g in help_entries() if g.get("doc")}
    out = []
    for p in docs:
        rel = _rel(p)
        if rel in ("docs/README.md",):
            continue
        in_help = rel.startswith("docs/") and rel[len("docs/"):] in listed
        for i, line, fence in prose(p.read_text(encoding="utf-8")):
            if fence is not None:
                continue
            if VERSION.search(line):
                out.append(f"{rel}:{i}: version history ({VERSION.search(line).group(0)})")
            if DEFECT.search(line):
                out.append(f"{rel}:{i}: a defect word ({DEFECT.search(line).group(0)})")
            if in_help and INTERNAL_LINK.search(line):
                out.append(f"{rel}:{i}: a link to an internal document from a Help page")
    return out


def gate_help() -> list[str]:
    out = [f"{g['slug']}: points under docs/architecture/ for everyone" for g in help_entries() if str(g.get("doc", "")).startswith("architecture/")]
    missing = [g["slug"] for g in help_entries() if not (g.get("domain") and g.get("lenses"))]
    if missing:
        out.append(f"{len(missing)} entries without domain and lenses (first: {', '.join(missing[:5])})")
    return out


def _tool(rel: str):
    spec = importlib.util.spec_from_file_location("drishti_gate_" + Path(rel).stem, ROOT / rel)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def gate_generated() -> list[str]:
    out = []
    gd = _tool("tools/docs/guide_data.py")
    for g in gd.load():
        block, problems = gd.build(g, gd.contracts())
        text = (ROOT / g["file"]).read_text(encoding="utf-8")
        if problems or gd.splice(text, g["id"], block) != text:
            out.append(f"{g['file']}: its data section is stale (python3 tools/docs/guide_data.py)")
    cr = _tool("tools/docs/connector_reference.py")
    if not cr.PAGE.is_file() or cr.PAGE.read_text(encoding="utf-8") != cr.render(cr.plugins()):
        out.append("docs/reference/CONNECTORS.md is stale (python3 tools/docs/connector_reference.py)")
    return out


def run() -> tuple[dict[str, list[str]], list[str]]:
    reg, errors = register()
    docs = customer_docs()
    found = {"feature register": gate_register(reg) if reg else [],
             "count caps": gate_caps(reg),
             "page budgets": gate_budgets(reg),
             "one home": gate_one_home(docs),
             "no repeated prose": gate_repeated(docs),
             "customer text": gate_customer_text(docs),
             "help is user docs": gate_help(),
             "generated sections": gate_generated()}
    return found, errors


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--strict", action="store_true", help="exit 1 when any gate has a finding (fail mode)")
    ap.add_argument("--json", action="store_true", help="print the findings as JSON")
    a = ap.parse_args(argv)
    found, errors = run()
    if a.json:
        print(json.dumps({"errors": errors, "gates": found, "counts": counts()}, indent=1))
    else:
        for e in errors:
            print(f"docs-gates: ERROR {e}")
        print("docs-gates: counts " + ", ".join(f"{k} {v}" for k, v in counts().items()))
        for gate, items in found.items():
            print(f"docs-gates: {gate}: {len(items)}" + (" (warn)" if items and not a.strict else ""))
            for x in items[:SHOWN]:
                print(f"    {x}")
            if len(items) > SHOWN:
                print(f"    … {len(items) - SHOWN} more (--json for all)")
    if errors:
        return 2
    return 1 if a.strict and any(found.values()) else 0


if __name__ == "__main__":
    sys.exit(main())
