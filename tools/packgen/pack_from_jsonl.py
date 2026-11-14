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

"""Create a complete Drishti pack from JSON Lines files (.jsonl, .jsonl.gz, .ndjson, folders of them): the pack inside a module.

    python3 tools/drishti.py pack new data/jsonl --name my-bank --kind trade --key TRADE_ID --match PRODUCT_TYPE,SOURCE_SYSTEM --date BUSINESS_DATE

`pack new` (tools/packgen/module_from_jsonl.py) wraps this in a content module; this file makes the pack: pack.yaml (kinds, mnemonics,
columns, form), contracts/<kind>.contract.yaml (every field seen, with its types and presence), samples/, one Sutra per kind or per
--match combination (tools/sutragen.py), tests/<sutra>/ (cases.jsonl, expect.yaml), config/about.yaml (TODO glossary text) and a README.md;
then `sutra lint` and `sutra test`.

The analysis reads every record of every file once (tools/packgen/packprofile.py): every --match combination gets its Sutra and its
count, the id is checked for uniqueness over the whole input, the business dates are counted. Only the documents shown in Sutras,
tests and samples are a chosen few per combination.

`pack make` and `pack regenerate` reuse it; `pack make` alone asks for a dated store (lake= or files_root= in its options), the old
pack-folder deployment.
"""
from __future__ import annotations

import argparse
import json
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(ROOT / "tools"))
import sutragen as SG  # noqa: E402

sys.path.insert(0, str(HERE / "common"))
import packsamples  # noqa: E402  (samples/<kind>.jsonl, one document a line)


def _lakeconfig():
    """tools/lake/lakeconfig.py: where the lakes are."""
    import importlib
    from pathlib import Path as _P
    p = str(_P(__file__).resolve().parents[1] / "lake")
    if p not in sys.path:
        sys.path.insert(0, p)
    return importlib.import_module("lakeconfig")


def mnemonic_of(kind: str, taken: set[str]) -> str:
    words = [w for w in re.split(r"[-_\s]+|(?<=[a-z])(?=[A-Z])", kind) if w]
    m = ("".join(w[0] for w in words) if len(words) > 1 else kind[:3]).upper()
    base, i = m, 2
    while m in taken:
        m, i = f"{base}{i}", i + 1
    taken.add(m)
    return m


def pack_code(name: str) -> str:
    words = [w for w in name.split("-") if w]
    return ("".join(w[0] for w in words) if len(words) > 1 else name[:4]).upper()


def scalar_columns(docs: list[dict], key: str | None, n: int = 7) -> list[str]:
    """The top-level fields to promote: the first n scalar fields, then every list of simple values (a list attribute such as TAGS, an
    array<string> column, DATA_LAKE_GUIDE.md section 2.4); a list of objects stays in the document."""
    seen: dict[str, int] = {}
    lists: dict[str, bool] = {}
    for d in docs[:50]:
        for k, v in d.items():
            if isinstance(v, list) and k != key:
                lists[k] = lists.get(k, True) and not any(isinstance(x, (dict, list)) for x in v)
            elif not isinstance(v, dict) and k != key and v is not None:
                seen[k] = seen.get(k, 0) + 1
    return [k for k in seen][:n] + [k for k, simple in lists.items() if simple and k not in seen]


def shown_fields(groups) -> dict[str, list[str]]:
    """kind -> top-level fields bound by its generated Sutras (the ones coverage will ask the glossary about)."""
    out: dict[str, list[str]] = {}
    for g in groups:
        text = g.path.read_text(encoding="utf-8")
        for m in re.finditer(r'bind: "\$\.([A-Za-z0-9_]+)', text):
            if m.group(1) not in out.setdefault(g.kind, []):
                out[g.kind].append(m.group(1))
    return out


def build_parser(add_help: bool = True) -> argparse.ArgumentParser:
    """The command line shared by `pack new` (tools/packgen/module_from_jsonl.py) and `pack regenerate`."""
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0], formatter_class=argparse.RawDescriptionHelpFormatter,
                                epilog=__doc__.split("\n\n", 1)[1], add_help=add_help)
    p.add_argument("inputs", type=pathlib.Path, nargs="+", help="files (.jsonl, .jsonl.gz, .ndjson, .json arrays) and/or folders of them")
    p.add_argument("--name", required=True, help="pack (and module) name: lower-case letters, digits and '-'")
    p.add_argument("--title", help="title (default: the name)")
    p.add_argument("--description", help="description")
    p.add_argument("--version", default="1.0.0", help="version (default 1.0.0)")
    p.add_argument("--mnemonic", help="kind=MNEMONIC,kind2=M2 (default: initials of the kind, upper case)")
    p.add_argument("--out", type=pathlib.Path, help="where to write (pack new: default modules/<name>)")
    p.add_argument("--kind-prefix", help="put this before every kind (trade -> otc-trade with --kind-prefix otc-): kinds another module provides "
                                         "would refuse the module on a server that has that module")
    p.add_argument("--pack-domain", default="core", help="the domain the pack serves (pack.yaml domain:, the module's domain)")
    p.add_argument("--force", action="store_true", help="overwrite an existing --out folder")
    p.add_argument("--catalog-samples", type=int, default=25, help="documents per kind kept in samples/ (default 25; every combination has one)")
    p.add_argument("--sample-part-mb", type=float, default=50, help="split a kind's samples/<kind>.jsonl into parts above this size (default 50)")
    p.add_argument("--sutra-edits", type=pathlib.Path, help="a JSON file {sutra name: YAML}: these texts replace the drafted Sutras before lint and test "
                                                            "(the Pack designer's edits)")
    SG.add_arguments(p)
    return p


def main(argv=None) -> int:
    return run(build_parser().parse_args(argv))


NONE = ("none", "-")


class Analysis:
    """What the full pass found and what will be written: per kind its key, date and match fields (given or inferred, with why), the
    profile (packprofile.KindProfile) and the Sutra groups."""

    def __init__(self):
        self.prof = None
        self.keys: dict[str, str] = {}
        self.dates: dict[str, str] = {}
        self.match: dict[str, list[str]] = {}
        self.why: dict[str, list[str]] = {}
        self.groups: list = []
        self.skipped: dict[str, list] = {}


def _given(v):
    return None if v is None or str(v).lower() in NONE else v


def analyse(opts, say=print) -> Analysis:
    """Infers what was not given from the first rows (`data profile`'s candidates), then reads every record once (packprofile.scan)."""
    import packprofile as PP
    an = Analysis()
    files = SG.input_files(opts.inputs, getattr(opts, "recursive", False))
    prefix = getattr(opts, "kind_prefix", None) or ""
    kind_of = (lambda k: prefix + opts.kind) if getattr(opts, "kind", None) else (lambda k: prefix + k)
    bare = (lambda k: k[len(prefix):]) if prefix else (lambda k: k)     # kind=FIELD options may name a kind without the prefix
    key_d, key_k = SG.parse_scalar(_given(opts.key))
    date_d, date_k = SG.parse_scalar(_given(opts.date))
    match_d, match_k = SG.parse_match(_given(opts.match))
    heads: dict[str, list[dict]] = {}
    if getattr(opts, "infer", False) and (opts.key is None or opts.date is None or opts.match is None):
        for k, docs in PP.head(files).items():
            heads.setdefault(kind_of(k), []).extend(docs)

    def choose(kind: str):
        why = an.why.setdefault(kind, [])
        c = PP.candidates(kind, heads[kind]) if kind in heads else None
        key = key_k.get(kind, key_k.get(bare(kind), key_d))
        if key is None and opts.key is None and c and c["keyCandidates"]:
            key = c["keyCandidates"][0]["field"]
            why.append(f"key {key} inferred ({c['keyCandidates'][0]['why']} in the first rows)")
        date = date_k.get(kind, date_k.get(bare(kind), date_d))
        named = [d for d in (c["dateCandidates"] if c else []) if re.search(r"business|cob|as_?of|report(ing)?_?date", d["field"], re.I)]
        if date is None and opts.date is None and named:     # only a field named as a business date: a trade date is not one
            date = named[0]["field"]
            why.append(f"date {date} inferred (named as a business date, every value an ISO date in the first rows)")
        fields = SG.for_kind(kind, SG.for_kind(bare(kind), match_d, match_k), match_k)
        if not fields and opts.match is None and c:
            fields = [m["field"] for m in c["matchCandidates"] if m["score"] >= 0.3 and m["field"] not in (key, date)][:2]
            if fields:
                why.append(f"match {','.join(fields)} inferred (" + "; ".join(m["explain"] for m in c["matchCandidates"] if m["field"] in fields) + ")")
        an.keys[kind] = key or "id"
        if date:
            an.dates[kind] = date
        an.match[kind] = [f for f in fields if f != date]

    for kind in sorted({kind_of(SG.file_kind(f)) for f in files} | set(heads)):
        choose(kind)
    conf = PP.Config(getattr(opts, "kind", None), {k: (an.match[k], an.keys[k], an.dates.get(k)) for k in an.keys},
                     (tuple(match_d), key_d or "id", date_d), prefix)
    workers = getattr(opts, "workers", None) or 1
    say(f"reading every record of {len(files)} file(s)" + (f" with {workers} worker(s)" if workers > 1 else "") + " ...")
    an.prof = PP.scan(files, conf, getattr(opts, "samples", 5), workers, lambda n, t: say(f"  {n:,} rows, {t:.0f} s"))
    for kind in an.prof.kinds:                 # a kind named only inside the files (the loader envelope): the defaults
        if kind not in an.keys:
            m, k, d = conf.default
            an.match[kind], an.keys[kind] = list(m), k
            if d:
                an.dates[kind] = d
    for kind, kp in an.prof.kinds.items():      # --match for every kind: a field this kind never has does not split it
        gone = PP.drop_absent(kp)
        if gone:
            an.match[kind] = list(kp.match)
            an.why.setdefault(kind, []).append(f"match {','.join(gone)} dropped: in no row of {kind}")
    used: set[str] = set()
    min_docs = getattr(opts, "min_docs", 1) or 1
    for kind in sorted(an.prof.kinds):
        kp = an.prof.kinds[kind]
        fields = an.match[kind]
        made = []
        for vals in sorted(kp.combos, key=lambda t: tuple("" if v is None else str(v) for v in t)):
            n = kp.combos[vals]
            if (getattr(opts, "skip_missing", False) and any(v is None for v in vals)) or n < min_docs:
                an.skipped.setdefault(kind, []).append((vals, n))
                continue
            g = SG.Group(kind, fields, vals, [])
            base = f"{opts.name_prefix}{kind}-{SG.slug(vals)}" if fields else f"{opts.name_prefix}{kind}-default"
            g.name, i = base, 2
            while g.name in used:
                g.name, i = f"{base}-{i}", i + 1
            used.add(g.name)
            g.priority = opts.priority if fields else 1
            g.count, g.samples, g.key = n, kp.chosen(vals), an.keys[kind]
            made.append(g)
        if getattr(opts, "fallback", False) and fields:
            g = SG.Group(kind, [], (), [])
            g.name, g.priority, g.count, g.key = f"{opts.name_prefix}{kind}-default", 1, kp.rows, an.keys[kind]
            g.samples = [d for vals in kp.combos for d in kp.chosen(vals)][:opts.samples]
            used.add(g.name)
            made.append(g)
        an.groups.extend(made)
    return an


def print_plan(an: Analysis, say=print) -> None:
    p = an.prof
    rate = p.rows / p.seconds if p.seconds else 0
    say(f"\nread {p.rows:,} rows from {p.files} file(s) in {p.seconds:.1f} s ({rate:,.0f} rows/s); {p.bad} unreadable line(s)"
        + (": " + "; ".join(p.bad_named[:3]) if p.bad else ""))
    if p.undated:
        say(f"note: {len(p.undated):,} file(s) carry no date in their name ({', '.join(p.undated[:3])}{', ...' if len(p.undated) > 3 else ''}): "
            "by the load rules they load under --as-of, their .ready file's date or the business today; name them "
            "<kind>.<YYYY-MM-DD>.jsonl[.gz] (one folder per source when several deliver the same day)")
    for kind in sorted(p.kinds):
        kp = p.kinds[kind]
        say(f"\nkind {kind}: {kp.rows:,} rows, {len(kp.fields)} fields")
        for w in an.why.get(kind, []):
            say(f"  {w}")
        key = an.keys[kind]
        per = " per business date" if kp.dates else ""
        if kp.key and kp.no_key == kp.rows:
            say(f"  id {key}: in no row; give --key {kind}=FIELD (the duplicate check needs it)")
        elif kp.unique():
            say(f"  id {key}: unique{per} over all {kp.rows:,} rows")
        else:
            say(f"  id {key}: NOT unique{per}: {kp.dup_rows:,} duplicate row(s)" + (f" (e.g. {', '.join(kp.dup_named[:5])})" if kp.dup_named else "")
                + (f"; {kp.no_key:,} row(s) without it" if kp.no_key else ""))
        ds = sorted(kp.dates)
        span = f"{len(ds)} business date(s)" + (f", {ds[0]} .. {ds[-1]}" if ds else "") + (f"; {kp.no_date:,} row(s) without one" if kp.no_date else "")
        if kind in an.dates:
            say(f"  date {an.dates[kind]}: {span}")
        else:
            say(f"  date: each file's by the load rules (its name, else the day of the load): {span}")
        fields = an.match[kind]
        say(f"  match: {', '.join(fields) or '(none: one Sutra for the kind)'}: {len(kp.combos)} combination(s)")
        for g in (g for g in an.groups if g.kind == kind):
            say(f"    {g.count:>10,}  {g.label[:60]:<60}  -> {g.name}")
        for vals, n in an.skipped.get(kind, []):
            say(f"    {n:>10,}  {', '.join(f'{f}={v}' for f, v in zip(fields, vals))[:60]:<60}  (skipped: --min-docs or --skip-missing)")


CONTRACT_TYPE = {"string": "text", "integer": "integer", "number": "amount", "boolean": "boolean", "object": "record", "array": "list", "null": "unknown"}


def contract(kind: str, kp, mnemonic: str, pack: str, key: str, date: str | None) -> dict:
    """The kind's contract from the full pass: every top-level field, its type and whether every row had it."""
    fields = []
    nested = " ".join(kp.fields.keys())
    for path, f in kp.fields.items():
        if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", path):
            continue
        t = max((x for x in f.types if x != "null"), key=f.types.get, default="null")
        ctype = "id" if path == key else CONTRACT_TYPE[t]
        if t == "string" and path != key and f.iso and f.iso == f.strings:
            ctype = "timestamp" if f.stamps else "date"
        if t == "array" and f"{path}[]." in nested:
            ctype = "record list"
        row = {"name": path, "type": ctype, "presence": "required" if f.docs == kp.rows and "null" not in f.types else "optional"}
        if f.examples:
            ex = f.examples[0]
            row["example"] = ex if isinstance(ex, (str, int, float, bool)) else str(ex)
        if len(f.types) > 1:
            row["note"] = "types seen: " + ", ".join(f"{k} {v}" for k, v in f.types.items())
        fields.append(row)
    return {"kind": kind, "mnemonic": mnemonic, "pack": pack, "version": "1.0", "class": "input", "status": "active",
            "title": kind.replace("-", " ").replace("_", " ").capitalize(), "description": f"TODO: what a {kind} is.",
            "source": "TODO: the system that delivers it", "grain": f"One row per {key}" + (" per business date." if date else "."),
            "key": [key], "dated-by": "business_date" if date else "snapshot",
            "cadence": {"frequency": "daily" if date else "on change", "due": "to be confirmed"},
            "binding-hint": {"lake-table": f"{re.sub('[^a-z0-9_-]', '-', pack)}.{re.sub('[^a-z0-9_.-]', '-', kind.lower())}"},
            "fields": fields, "validation": [{"rule": "required", "fields": [x["name"] for x in fields if x["presence"] == "required"]}],
            "review": [f"types and presence from all {kp.rows} rows (drishti.py pack new): confirm each required field is mandatory upstream",
                       "description and source are TODO: say what it is and which system delivers it"]}


def run(opts, say=print, an: Analysis | None = None) -> int:
    """Generates the pack described by the parsed options; 0 when its lint and test pass."""
    import yaml  # PyYAML

    if not re.fullmatch(r"[a-z0-9][a-z0-9-]*", opts.name):
        raise SystemExit("pack_from_jsonl: --name is lower-case letters, digits and '-'")
    out = (opts.out or ROOT / "config" / "packs" / (getattr(opts, "pack_domain", None) or "core") / opts.name).resolve()
    if out.exists() and any(out.iterdir()):
        if not opts.force:
            raise SystemExit(f"pack_from_jsonl: {out} exists; pass --force to overwrite it")
        shutil.rmtree(out)
    if an is None:
        an = analyse(opts, say)
        print_plan(an, say)
    if not an.groups:
        raise SystemExit("pack_from_jsonl: nothing to generate (no combination has --min-docs documents)")
    kinds = an.prof.kinds
    keys, dates = an.keys, an.dates
    opts.key = ",".join(f"{k}={v}" for k, v in keys.items())
    mn_d, mn_k = SG.parse_scalar(opts.mnemonic)

    sutras, tests = out / "sutras", out / "tests"
    groups = SG.draft(an.groups, opts, sutras, tests, say)
    SG.summary(groups, say)
    edits = json.loads(opts.sutra_edits.read_text(encoding="utf-8")) if getattr(opts, "sutra_edits", None) else {}
    for g in groups:
        if g.name in edits:
            g.path.write_text(edits[g.name].rstrip("\n") + "\n", encoding="utf-8")
    say(f"{sum(1 for g in groups if g.name in edits)} Sutra(s) taken from the edits file" if edits else "no edits file")

    taken: set[str] = set()
    mnemonics = {}
    for k in sorted(kinds):
        m = mn_k.get(k) or mnemonic_of(k, taken)
        taken.add(m)
        mnemonics[m] = {"kind": k, "label": k.replace("-", " ").replace("_", " ").capitalize()}
    columns = {k: scalar_columns(kinds[k].head, keys[k]) for k in kinds}
    lake = getattr(opts, "lake", None)
    store = getattr(opts, "store", "delta")
    target = getattr(opts, "files_root", None) if store == "files" else lake
    stored = sorted(kinds) if target else []
    lake_cols = {k: list(dict.fromkeys([f for f in an.match[k] if f != dates.get(k)] + columns[k])) for k in kinds}
    domain = getattr(opts, "pack_domain", None) or "core"
    manifest: dict = {"pack": opts.name, **({"domain": domain} if domain != "core" else {}), "version": getattr(opts, "version", None) or "1.0.0",
                      "code": pack_code(opts.name), "title": opts.title or opts.name,
                      "description": opts.description or f"Generated from {', '.join(p.name for p in opts.inputs)} by drishti.py pack new.",
                      "kinds": sorted(kinds), "sutras": "sutras", "samples": "samples", "mnemonics": mnemonics, "columns": columns}
    import packform as PF                   # the form: section (FORM pack): each kind's search form, typed from its sample values
    manifest["form"] = {k: PF.form_from_documents(kinds[k].head, 10, keys[k]) for k in sorted(kinds)}
    if stored:                                   # pack make only: the pack folder with its own store
        if store == "files":
            conn = {"plugin": "file", "enabled": "${DRISHTI_FILES_ENABLED:true}", "kinds": stored,
                    "settings": {"root": "${DRISHTI_FILES_ROOT:${drishti.data.dir:./data}/files}", "domain": opts.name, "id-field": "id",
                                 "layout": {k: {"columns": lake_cols[k]} for k in stored}}}
        else:
            conn = {"plugin": "delta", "enabled": "${DRISHTI_LAKE_ENABLED:true}", "kinds": stored,
                    "settings": {"root": _lakeconfig().DEMO_ROOT_REF, "domain": opts.name,
                                 "layout": {k: {"columns": lake_cols[k], "sort-by": "id", "file-rows": 250000, "row-group-rows": 1000}
                                            for k in stored}}}
        manifest["connectors"] = [f"{opts.name}-store"]
        manifest["connector-templates"] = {f"{opts.name}-store": conn}
        manifest["routes"] = {k: f"{opts.name}-store" for k in stored}
    manifest["ingest"] = {k: {"key": keys[k], **({"date": dates[k]} if k in dates else {"snapshot": True})} for k in sorted(kinds)}
    out.mkdir(parents=True, exist_ok=True)
    note = ("# Generated by drishti.py pack new (tools/packgen/pack_from_jsonl.py); now yours to edit.\n"
            + "".join(f"# {k}: its own date in $.{f} (counted only: a file's business date is its name's, the store's business_date)\n"
                      for k, f in sorted(dates.items())))
    (out / "pack.yaml").write_text(note + yaml.safe_dump(manifest, sort_keys=False, width=120, allow_unicode=True), encoding="utf-8")

    (out / "contracts").mkdir(exist_ok=True)
    mn_of = {v["kind"]: m for m, v in mnemonics.items()}
    for k in sorted(kinds):
        c = contract(k, kinds[k], mn_of[k], opts.name, keys[k], dates.get(k))
        (out / "contracts" / f"{k}.contract.yaml").write_text(yaml.safe_dump(c, sort_keys=False, width=120, allow_unicode=True), encoding="utf-8")

    # samples: one document of every combination first, then the rest up to --catalog-samples per kind
    cat, by_kind = [], {}
    for k in sorted(kinds):
        mine = [g for g in groups if g.kind == k]
        cap = max(opts.catalog_samples, len(mine))
        for d in [g.samples[0] for g in mine if g.samples] + [d for g in mine for d in g.samples[1:]]:
            kv = SG.get(d, keys[k])
            have = by_kind.setdefault(k, {})
            if kv is SG.MISSING or str(kv) in have or len(have) >= cap:
                continue
            have[str(kv)] = d
            cat.append({"kind": k, "id": str(kv), "title": str(kv), "subtitle": k})
    (out / "samples").mkdir(parents=True, exist_ok=True)
    for name, text in packsamples.files(by_kind, cat, getattr(opts, "sample_part_mb", packsamples.PART_MB)).items():   # catalog.jsonl and samples/<kind>.jsonl
        (out / "samples" / name).write_text(text, encoding="utf-8")

    for g in groups:
        (tests / g.name / "expect.yaml").write_text("noErrors: true\n", encoding="utf-8")

    about: dict = {"about": 1, "kinds": {}}
    for k, fields in shown_fields(groups).items():
        about["kinds"][k] = {"title": k.replace("-", " ").capitalize(), "about": f"TODO: one sentence about a {k}, e.g. ${{$.{keys[k]}}}.",
                             "glossary": {f: {"term": f, "means": "TODO: what this field means."} for f in fields}}
    (out / "config").mkdir(exist_ok=True)
    (out / "config" / "about.yaml").write_text(yaml.safe_dump(about, sort_keys=False, width=120, allow_unicode=True), encoding="utf-8")

    lake_line = ""
    if target:                                   # pack make: the dated store, through the standalone ingest tool (reads the inputs again)
        import ingest_jsonl as IJ
        sys.path.insert(0, str(ROOT / "tools" / "lake"))
        import feedfiles
        docs = SG.load_folder(opts.inputs, getattr(opts, "recursive", False), getattr(opts, "kind", None))
        with tempfile.TemporaryDirectory(prefix="pack-from-jsonl-") as staged:
            for k in sorted(docs):
                f = dates.get(k)
                feedfiles.write_documents(pathlib.Path(staged), k, docs[k], (lambda d, f=f: SG.doc_date(d, f)) if f else None)
            ia = argparse.Namespace(src=[pathlib.Path(staged)], recursive=False, pack=out, domain=None, kind=None, key=None, date=None,
                                    columns=None, lake=lake, store=store, root=getattr(opts, "files_root", None), mode="replace", dry_run=False,
                                    batch_rows=250_000)
            rep = IJ.ingest(ia, say)
        lake_line = (f"\nDated store ({store}): {sum(rep.rows.values())} rows written to {target} (domain {opts.name}); "
                     f"{sum(rep.no_key.values())} document(s) without an id skipped")
        say(lake_line.strip())

    (out / "README.md").write_text(readme(opts, an, groups, lake_line, out), encoding="utf-8")
    license_fix(out)
    import packregen
    packregen.snapshot(out, opts)  # the baseline `pack regenerate` merges against

    say(f"pack written to {out}")
    rc = 0
    for cmd in ("lint", "test"):
        r = SG.sutra_cli(opts, cmd, str(out))
        tail = "\n".join((r.stdout or "").strip().splitlines()[-6:])
        say(f"sutra {cmd}: exit {r.returncode}\n{tail}")
        if r.returncode:
            sys.stderr.write((r.stderr or "")[-1500:])
            rc = 1
    return rc


def license_fix(out: pathlib.Path) -> None:
    tool = ROOT / "tools" / "license_headers.py"
    if tool.exists() and str(out).startswith(str(ROOT)):
        subprocess.run([sys.executable, str(tool), "--fix"], cwd=ROOT, capture_output=True)


def readme(opts, an: Analysis, groups, lake_line: str, out: pathlib.Path) -> str:
    """The pack's README in the README standard (tools/packgen/common/readme.py), its intro written from the full pass."""
    import importlib.util
    spec = importlib.util.spec_from_file_location("drishti_pack_readme", HERE / "common" / "readme.py")
    R = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(R)
    p = an.prof
    rows = "\n".join(f"| `{k}` | {kp.rows:,} | `{an.keys[k]}` | {'unique' if kp.unique() else f'{kp.dup_rows:,} duplicate(s)'} | "
                     f"{('`' + an.dates[k] + '`, ' + str(len(kp.dates)) + ' date(s)') if k in an.dates else 'none'} | "
                     f"{', '.join(an.match[k]) or '-'} | {sum(1 for g in groups if g.kind == k)} |" for k, kp in sorted(p.kinds.items()))
    sources = ", ".join(str(i) for i in opts.inputs)
    intro = f"""---
summary: {json.dumps(opts.description or f"{opts.title or opts.name}: generated from JSON Lines by drishti.py pack new.")}
sources:
- name: {json.dumps(f"JSON Lines files ({sources})")}
---
## What it is

Generated by `drishti.py pack new` from {p.rows:,} rows in {p.files} file(s), every row read. Nothing here is final: edit it.

| Kind | Rows | Id | Id check | Business date | Match fields | Sutras |
|---|---|---|---|---|---|---|
{rows}

## Refreshing the data

The pack carries no connector: the site binds each kind (`config/bindings.yaml`, Admin -> Data bindings). New files of the same
shape are loaded with `drishti.py data ingest`; new fields or combinations: `drishti.py pack regenerate` keeps your edits.{lake_line}
"""
    f = out / f"{opts.name}.intro.md"           # beside the pack while the README is rendered (the renderer names it relative to the repository)
    f.write_text(intro, encoding="utf-8")
    try:
        return R.render_pack(out, generator="drishti.py pack new", intro=f)
    finally:
        f.unlink(missing_ok=True)


if __name__ == "__main__":
    sys.exit(main())
