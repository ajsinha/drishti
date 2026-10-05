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

"""Draft Sutras from folders of JSON Lines files: one Sutra per kind, or one per distinct combination of the values
of some "match" fields (for example one per productType), each with its own samples under tests/.

    uv run --with pyyaml python tools/sutragen.py data/jsonl --key tradeId --match productType --out /tmp/sutras

Input: one or more folders (every *.jsonl in them; -r for subfolders) and/or single .jsonl files. A line is one plain JSON document, or the loader
envelope {"kind": ..., "id": ..., "doc": {...} | "<json text>"} whose doc is unwrapped (and whose kind wins over the
file name). The kind of a document is the file stem (trade.jsonl -> trade) unless --kind is given.

Per-kind options: --key, --date and --match take either one value for every kind (--key tradeId) or kind=value pairs
(--key trade=tradeId,counterparty=counterpartyId; --match trade=productType,assetClass;counterparty=type, where
';' separates kinds and ',' separates fields). Fields are dotted paths (risk.dv01).

Missing values: a document without a match field (or with null) forms its own group, tested with `$.f == null`;
use --skip-missing to leave those documents out instead. Values are quoted by type: strings 'x' (with \\ and \\' escaped),
numbers and booleans bare.

The draft is made by `sutra design --each` ONCE (one JVM for all groups), then the YAML text is edited minimally: the
sutra name, the description, the match line and the title id. The result is linted (`sutra lint`; non-zero exit on problems).
"""
from __future__ import annotations

import argparse
import glob
import json
import os
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
MISSING = object()


# ---------------------------------------------------------------------------------------------------- reading

def unwrap(rec: dict, stem: str) -> tuple[str, dict | None]:
    """(kind, document) of one JSONL record: the loader envelope is unwrapped, a plain document is itself."""
    if isinstance(rec, dict) and "doc" in rec and "kind" in rec and "id" in rec and len(rec) <= 6:
        doc = rec["doc"]
        if isinstance(doc, str):
            try:
                doc = json.loads(doc)
            except ValueError:
                return str(rec["kind"]), None
        return str(rec["kind"]), doc if isinstance(doc, dict) else None
    return stem, rec if isinstance(rec, dict) else None


def input_files(inputs, recursive: bool = False) -> list[pathlib.Path]:
    """The .jsonl files named by `inputs`: each is a file, or a folder (its *.jsonl; with `recursive` its subfolders too)."""
    files: list[pathlib.Path] = []
    for i in inputs if isinstance(inputs, (list, tuple)) else [inputs]:
        i = pathlib.Path(i)
        if i.is_dir():
            files += sorted(i.glob("**/*.jsonl" if recursive else "*.jsonl"))
        elif i.is_file():
            files.append(i)
        else:
            raise SystemExit(f"sutragen: {i} does not exist")
    if not files:
        raise SystemExit(f"sutragen: no *.jsonl files in {', '.join(map(str, inputs))}")
    return files


def read_records(files, say_bad=None):
    """Yields (kind, document, where) for every line; bad lines go to say_bad(text) (default: stderr)."""
    say_bad = say_bad or (lambda t: print("  skipped " + t, file=sys.stderr))
    for f in files:
        with open(f, encoding="utf-8") as fh:
            for n, line in enumerate(fh, 1):
                if not line.strip():
                    continue
                try:
                    k, doc = unwrap(json.loads(line), f.stem)
                except ValueError as e:
                    say_bad(f"{f}:{n}: not JSON ({e})")
                    continue
                if doc is None:
                    say_bad(f"{f}:{n}: not a JSON object")
                    continue
                yield k, doc, f"{f}:{n}"


def load_folder(inputs, recursive: bool = False, kind: str | None = None) -> dict[str, list[dict]]:
    """kind -> documents, from files and/or folders of *.jsonl."""
    out: dict[str, list[dict]] = {}
    for k, doc, _ in read_records(input_files(inputs, recursive)):
        out.setdefault(kind or k, []).append(doc)
    return out


def get(doc, path: str):
    """The value at a dotted path, MISSING when absent (null is a value only for a missing-or-null test, see group_value)."""
    cur = doc
    for part in path.split("."):
        if isinstance(cur, dict) and part in cur:
            cur = cur[part]
        else:
            return MISSING
    return cur


# ---------------------------------------------------------------------------------------------------- options

def parse_scalar(spec: str | None) -> tuple[str | None, dict[str, str]]:
    """--key / --date: 'FIELD' for every kind, or 'kind=FIELD,kind2=FIELD2' (a bare FIELD among pairs is the default)."""
    default, kinds = None, {}
    for part in [p.strip() for p in (spec or "").split(",") if p.strip()]:
        if "=" in part:
            k, v = part.split("=", 1)
            kinds[k.strip()] = v.strip()
        else:
            default = part
    return default, kinds


def parse_match(spec: str | None) -> tuple[list[str], dict[str, list[str]]]:
    """--match: 'a,b' for every kind, or 'kind=a,b;kind2=c' (';' separates kinds)."""
    default: list[str] = []
    kinds: dict[str, list[str]] = {}
    for part in [p.strip() for p in (spec or "").split(";") if p.strip()]:
        if "=" in part:
            k, v = part.split("=", 1)
            kinds[k.strip()] = [x.strip() for x in v.split(",") if x.strip()]
        else:
            default = [x.strip() for x in part.split(",") if x.strip()]
    return default, kinds


def for_kind(kind: str, default, kinds):
    return kinds.get(kind, default)


# ---------------------------------------------------------------------------------------------------- grouping

def literal(v) -> str:
    """A value as a Sutra expression literal."""
    if v is None or v is MISSING:
        return "null"
    if isinstance(v, bool):
        return "true" if v else "false"
    if isinstance(v, (int, float)):
        return repr(v)
    text = v if isinstance(v, str) else json.dumps(v, sort_keys=True)
    return "'" + text.replace("\\", "\\\\").replace("'", "\\'") + "'"


def where_clause(fields: list[str], values: tuple) -> str:
    parts = []
    for f, v in zip(fields, values):
        parts.append(f"$.{f} == {literal(v)}")
    return " && ".join(parts)


def slug(values: tuple) -> str:
    s = "-".join("none" if v is None else str(v) for v in values).lower()
    s = re.sub(r"[^a-z0-9]+", "-", s).strip("-")
    return s[:48].strip("-") or "x"


def group_value(doc, field: str):
    v = get(doc, field)
    if v is MISSING:
        return None
    if isinstance(v, (dict, list)):
        return json.dumps(v, sort_keys=True)
    return v


def doc_date(doc, date_field: str | None):
    if not date_field:
        return None
    v = get(doc, date_field)
    return None if v is MISSING or v is None else str(v)[:10]


def pick(docs: list[dict], n: int, key: str | None, date_field: str | None) -> list[dict]:
    """n deterministic samples. With a date field: round-robin over the dates, most recent first; otherwise evenly
    spaced over the documents sorted by key (or by their JSON text)."""
    def ident(d):
        k = get(d, key) if key else MISSING
        return str(k) if k is not MISSING else json.dumps(d, sort_keys=True)
    ordered = sorted(docs, key=ident)
    if date_field:
        by_date: dict[str, list[dict]] = {}
        for d in ordered:
            by_date.setdefault(doc_date(d, date_field) or "", []).append(d)
        days = sorted(by_date, reverse=True)
        chosen: list[dict] = []
        depth = 0
        while len(chosen) < n and any(depth < len(by_date[x]) for x in days):
            for x in days:
                if depth < len(by_date[x]) and len(chosen) < n:
                    chosen.append(by_date[x][depth])
            depth += 1
        return chosen
    if len(ordered) <= n:
        return ordered
    return [ordered[(i * len(ordered)) // n] for i in range(n)]


class Group:
    def __init__(self, kind: str, fields: list[str], values: tuple, docs: list[dict]):
        self.kind, self.fields, self.values, self.docs = kind, fields, values, docs
        self.samples: list[dict] = []
        self.name = ""
        self.path: pathlib.Path | None = None
        self.priority, self.key = 10, None

    @property
    def where(self) -> str:
        return where_clause(self.fields, self.values)

    @property
    def label(self) -> str:
        return ", ".join(f"{f}={'(none)' if v is None else v}" for f, v in zip(self.fields, self.values)) or "(all)"


def build_groups(kinds: dict[str, list[dict]], opts) -> list[Group]:
    key_d, key_k = parse_scalar(opts.key)
    date_d, date_k = parse_scalar(opts.date)
    match_d, match_k = parse_match(opts.match)
    groups: list[Group] = []
    used: set[str] = set()
    for kind in sorted(kinds):
        docs = kinds[kind]
        key = key_k.get(kind, key_d)
        date = date_k.get(kind, date_d)
        fields = [f for f in for_kind(kind, match_d, match_k) if f != date]
        if date:
            missing = sum(1 for d in docs if doc_date(d, date) is None)
            if missing:
                print(f"  {kind}: {missing} document(s) without date field '{date}'", file=sys.stderr)
        buckets: dict[tuple, list[dict]] = {}
        for d in docs:
            vals = tuple(group_value(d, f) for f in fields)
            if opts.skip_missing and any(v is None for v in vals):
                continue
            buckets.setdefault(vals, []).append(d)
        made = []
        for vals in sorted(buckets, key=lambda t: tuple("" if v is None else str(v) for v in t)):
            if len(buckets[vals]) < opts.min_docs:
                continue
            g = Group(kind, fields, vals, buckets[vals])
            base = f"{opts.name_prefix}{kind}-{slug(vals)}" if fields else f"{opts.name_prefix}{kind}-default"
            g.name, i = base, 2
            while g.name in used:
                g.name, i = f"{base}-{i}", i + 1
            used.add(g.name)
            g.priority = opts.priority if fields else 1
            made.append(g)
        if opts.fallback and fields:
            g = Group(kind, [], (), docs)
            g.name = f"{opts.name_prefix}{kind}-default"
            g.priority = 1
            used.add(g.name)
            made.append(g)
        for g in made:
            g.samples = pick(g.docs, opts.samples, key, date)
            g.key = key
        groups.extend(made)
    return groups


# ---------------------------------------------------------------------------------------------------- the JVM

def find_jar(explicit: str | None) -> str:
    if explicit:
        return explicit
    jars = sorted(glob.glob(str(ROOT / "drishti-server/target/drishti-server-*-exec.jar")), key=os.path.getmtime)
    if not jars:
        raise SystemExit("sutragen: no drishti-server-*-exec.jar under drishti-server/target; build it or pass --jar")
    return jars[-1]


def find_java(explicit: str | None) -> str:
    if explicit:
        return explicit
    for home in (os.environ.get("JAVA_HOME"), "/usr/lib/jvm/java-21-openjdk-amd64"):
        if home and os.path.exists(os.path.join(home, "bin", "java")):
            return os.path.join(home, "bin", "java")
    return shutil.which("java") or "java"


def sutra_cli(opts, *args: str) -> subprocess.CompletedProcess:
    cmd = [find_java(opts.java), "-jar", find_jar(opts.jar), "sutra", *args]
    return subprocess.run(cmd, cwd=ROOT, text=True, capture_output=True)


# ---------------------------------------------------------------------------------------------------- editing the draft

def edit_yaml(text: str, g: Group) -> str:
    desc = f"Sutra for {g.kind} documents where {g.label}." if g.fields else f"Default Sutra for every {g.kind} document."
    match = f"match: {{ kind: {g.kind}, " + (f'where: {json.dumps(g.where, ensure_ascii=False)}, ' if g.fields else "") + f"priority: {g.priority} }}"
    out = []
    for line in text.splitlines():
        if line.startswith("sutra:"):
            line = f"sutra: {g.name}"
        elif line.startswith("description:"):
            line = f"description: {json.dumps(desc, ensure_ascii=False)}"
        elif line.startswith("match:"):
            line = match
        elif line.startswith("title:") and g.key:
            line = re.sub(r'id: "[^"]*"', f'id: "$.{g.key}"', line, count=1)
        out.append(line)
    return "\n".join(out) + "\n"


def generate(kinds: dict[str, list[dict]], opts, out: pathlib.Path, tests: pathlib.Path | None = None, say=print) -> list[Group]:
    """Groups, drafts, writes sutras under out/<kind>/ and samples under tests/<name>/. Returns the groups written."""
    groups = build_groups(kinds, opts)
    if not groups:
        raise SystemExit("sutragen: nothing to generate (no group has --min-docs documents)")
    tests = tests or out / "tests"
    say(f"{sum(len(v) for v in kinds.values())} documents, {len(kinds)} kind(s), {len(groups)} group(s)")
    with tempfile.TemporaryDirectory(prefix="sutragen-") as tmp:
        t = pathlib.Path(tmp)
        for i, g in enumerate(groups):
            d = t / "in" / f"g{i:04d}"
            d.mkdir(parents=True)
            (d / "kind").write_text(g.kind + "\n")
            for j, doc in enumerate(g.samples, 1):
                (d / f"s{j}.json").write_text(json.dumps(doc))
        say(f"drafting {len(groups)} Sutra(s) with one `sutra design --each` run ...")
        r = sutra_cli(opts, "design", "--each", str(t / "in"), "--out", str(t / "out"))
        if r.returncode != 0:
            sys.stderr.write(r.stdout + r.stderr)
            raise SystemExit(f"sutragen: sutra design failed (exit {r.returncode})")
        for i, g in enumerate(groups):
            text = (t / "out" / f"g{i:04d}.sutra.yaml").read_text(encoding="utf-8")
            g.path = out / g.kind / f"{g.name}.v1.sutra.yaml"
            g.path.parent.mkdir(parents=True, exist_ok=True)
            g.path.write_text(edit_yaml(text, g), encoding="utf-8")
            sd = tests / g.name
            sd.mkdir(parents=True, exist_ok=True)
            for j, doc in enumerate(g.samples, 1):
                (sd / f"sample-{j}.json").write_text(json.dumps(doc, indent=1, ensure_ascii=False) + "\n", encoding="utf-8")
    return groups


def summary(groups: list[Group], say=print) -> None:
    say(f"{'kind':<16}{'group':<44}{'docs':>7}{'samples':>8}  sutra")
    for g in groups:
        say(f"{g.kind:<16}{g.label[:43]:<44}{len(g.docs):>7}{len(g.samples):>8}  {g.name}")


def lint(opts, path: pathlib.Path, say=print) -> int:
    r = sutra_cli(opts, "lint", str(path))
    say(r.stdout.strip() or "lint ok")
    if r.returncode:
        sys.stderr.write(r.stderr[-2000:])
    return r.returncode


# ---------------------------------------------------------------------------------------------------- command line

def add_arguments(p: argparse.ArgumentParser) -> None:
    p.add_argument("--kind", help="kind for every document (default: the file stem, or the envelope's kind)")
    p.add_argument("--key", help="id field (dotted): FIELD, or kind=FIELD,kind2=FIELD2; becomes the title id")
    p.add_argument("--date", help="business-date field (dotted): FIELD or kind=FIELD,...; kept out of the match, samples span dates")
    p.add_argument("--match", help="fields whose values split the kind: a,b  or  kind=a,b;kind2=c ; one Sutra per combination")
    p.add_argument("--skip-missing", action="store_true", help="leave out documents lacking a match field (default: a `== null` group)")
    p.add_argument("--samples", type=int, default=5, help="samples per group (default 5)")
    p.add_argument("--min-docs", type=int, default=1, help="skip groups with fewer documents (default 1)")
    p.add_argument("--priority", type=int, default=10, help="priority of a match-field Sutra (default 10)")
    p.add_argument("--fallback", action="store_true", help="also write <kind>-default (no where, priority 1)")
    p.add_argument("--name-prefix", default="", help="prefix of every Sutra name")
    p.add_argument("--jar", help="the drishti-server exec jar (default: newest under drishti-server/target)")
    p.add_argument("--java", help="java binary (default: JAVA_HOME, then JDK 21, then java)")
    p.add_argument("-r", "--recursive", action="store_true", help="read *.jsonl in subfolders too")


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0], formatter_class=argparse.RawDescriptionHelpFormatter,
                                epilog=__doc__.split("\n\n", 1)[1])
    p.add_argument("inputs", type=pathlib.Path, nargs="+", help="folders of *.jsonl and/or single .jsonl files")
    add_arguments(p)
    p.add_argument("--out", type=pathlib.Path, required=True, help="writes <out>/<kind>/<kind>-<slug>.v1.sutra.yaml and <out>/tests/<sutra>/sample-*.json")
    p.add_argument("--tests-dir", type=pathlib.Path, help="put the samples here instead of <out>/tests")
    p.add_argument("--no-lint", action="store_true", help="do not run `sutra lint` on the result")
    opts = p.parse_args(argv)
    kinds = load_folder(opts.inputs, opts.recursive, opts.kind)
    groups = generate(kinds, opts, opts.out, opts.tests_dir)
    summary(groups)
    if opts.no_lint:
        return 0
    return 1 if lint(opts, opts.out) else 0


if __name__ == "__main__":
    sys.exit(main())
