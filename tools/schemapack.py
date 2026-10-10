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

"""`drishti.py sutra design --schema` and `drishti.py pack make --schema`: JSON Schema (alone, or with a folder of JSON Lines) in, Sutras or ONE pack folder out.

    drishti.py sutra design --schema trade.schema.json --kind trade --out build/sutras
    drishti.py pack make --schema schemas/ --name my-bank                     # schemas only: synthetic samples, marked synthetic
    drishti.py pack make --schema schemas/ data/jsonl --name my-bank         # real samples win; the schemas add labels, descriptions, links

The rules (kind, key, links, labels, match, strip, About text) are in drishti-console/core/schemakit (shared with the console's
Build -> New pack page) and are documented in docs/guides/SCHEMA_TO_PACK.md. This module is the command-line glue: it reads the files,
asks about what is ambiguous, drafts the Sutras with the Java auto-designer in ONE JVM run, and lays the folder out like `pack make`.
"""
from __future__ import annotations

import argparse
import datetime
import json
import pathlib
import shutil
import sys
import tempfile

TOOLS = pathlib.Path(__file__).resolve().parent
ROOT = TOOLS.parent
SCHEMA_SUFFIXES = (".json", ".yaml", ".yml")


class SchemaPackError(Exception):
    def __init__(self, message: str, code: int = 2):
        super().__init__(message)
        self.code = code


def kit():
    """The shared library (drishti-console/core/schemakit), imported by path."""
    core = str(ROOT / "drishti-console" / "core")
    if core not in sys.path:
        sys.path.insert(0, core)
    import schemakit.build as B
    import schemakit.plan as P
    return P, B


# ----------------------------------------------------------------------------------------------- reading

def gather_schemas(paths, recursive: bool = False) -> list[tuple[str, str]]:
    """[(name, text)] from files and folders. A folder contributes its *.schema.json / *.schema.yaml, else every *.json / *.yaml in it."""
    out: list[tuple[str, str]] = []
    for p in map(pathlib.Path, paths):
        if p.is_dir():
            pick = "**/" if recursive else ""
            files = sorted(p.glob(pick + "*.schema.json")) + sorted(p.glob(pick + "*.schema.yaml")) + sorted(p.glob(pick + "*.schema.yml"))
            if not files:
                files = sorted(f for s in SCHEMA_SUFFIXES for f in p.glob(pick + "*" + s))
            out += [(f.name, f.read_text(encoding="utf-8")) for f in files]
        elif p.is_file():
            out.append((p.name, p.read_text(encoding="utf-8")))
        else:
            raise SchemaPackError(f"{p} does not exist")
    if not out:
        raise SchemaPackError("no schema files found in " + ", ".join(map(str, paths)))
    return out


def split_arguments(paths, recursive: bool = False) -> tuple[list, list]:
    """--schema takes every path up to the next flag, so a JSON Lines file or folder written after it is data, not a schema."""
    schemas, data = [], []
    for p in map(pathlib.Path, paths):
        if p.suffix.lower() in (".jsonl", ".ndjson"):
            data.append(p)
        elif p.is_dir() and not any(list(p.glob(("**/" if recursive else "") + "*" + s)) for s in SCHEMA_SUFFIXES) and list(p.glob(("**/" if recursive else "") + "*.jsonl")):
            data.append(p)
        else:
            schemas.append(p)
    return schemas, data


def gather_samples(SG, P, inputs, recursive: bool, kind_names: list[str], kind_override: str | None) -> dict:
    """kind -> documents. A file's rows go to the schema kind its name fits (trades.jsonl -> trade), else to a kind of their own."""
    samples: dict[str, list] = {}
    files = SG.input_files(inputs, recursive)
    for k, doc, _ in SG.read_records(files):
        samples.setdefault(kind_override or k, []).append(doc)
    if kind_override:
        return samples
    mapped: dict[str, list] = {}
    for k, docs in samples.items():
        mapped.setdefault(P.match_kind(k, kind_names) or k, []).extend(docs)
    return mapped


def parse_overrides(SG, kinds: list[str], key, date, match, mnemonic) -> dict:
    ov: dict = {k: {} for k in kinds}
    kd, kk = SG.parse_scalar(key)
    dd, dk = SG.parse_scalar(date)
    none = (match or "").strip().lower() == "none"          # --match none: one Sutra per kind even where the schema names a match column
    md, mk = SG.parse_match(None if none else match)
    mn_d, mn_k = SG.parse_scalar(mnemonic)
    for k in kinds:
        if kk.get(k, kd):
            ov[k]["key"] = kk.get(k, kd)
        if dk.get(k, dd):
            ov[k]["date"] = dk.get(k, dd)
        if none:
            ov[k]["match"] = []
        elif k in mk or md:
            ov[k]["match"] = mk.get(k, md)
        if mn_k.get(k):
            ov[k]["mnemonic"] = mn_k[k]
    return ov


def make_plan(a, SG, P, say=print):
    """The plan for the command line `a`; asks about ambiguous keys when a terminal is attached."""
    schema_paths, data_paths = split_arguments(a.schema, getattr(a, "recursive", False))
    a.inputs = list(getattr(a, "inputs", None) or []) + data_paths
    schemas = gather_schemas(schema_paths, getattr(a, "recursive", False))
    kinds0 = P.make_plan(schemas)
    names = [k.kind for k in kinds0.kinds]
    override_kind = getattr(a, "kind", None)
    if override_kind and len(kinds0.kinds) == 1 and not getattr(a, "inputs", None):
        ov0 = {names[0]: {"kind": override_kind}}
    else:
        if override_kind and len(kinds0.kinds) > 1:
            raise SchemaPackError("--kind names the kind of ONE schema; with several schemas each takes its kind from its title or file name "
                                  "(rename one with  x-drishti-kind  in the schema)")
        ov0 = {}
    samples = {}
    if getattr(a, "inputs", None):
        samples = gather_samples(SG, P, a.inputs, getattr(a, "recursive", False), names, override_kind if len(names) <= 1 else None)
        if override_kind and len(names) == 1 and override_kind not in names:
            ov0 = {names[0]: {"kind": override_kind}}
    ov = parse_overrides(SG, [ov0.get(n, {}).get("kind", n) for n in names] + [k for k in samples if k not in names],
                         getattr(a, "key", None), getattr(a, "date", None), getattr(a, "match", None), getattr(a, "mnemonic", None))
    for n, o in ov0.items():
        ov.setdefault(o["kind"], {}).update(o)
        ov.setdefault(n, {}).update(o)
    count = getattr(a, "count", None) or (a.samples if isinstance(getattr(a, "samples", None), int) else 5)
    settings = {"samples": count, "strip_max": getattr(a, "strip", 6) or 6}
    plan = P.make_plan(schemas, samples, ov, settings, pack_name=getattr(a, "name", "") or "")
    if sys.stdin.isatty() and not getattr(a, "yes", False):
        changed = False
        for k in plan.kinds:
            if k.key_ambiguous and k.key_candidates and len(k.key_candidates) > 1:
                print(f"{k.kind}: which field is the key?  " + ", ".join(f"{i}) {c}" for i, c in enumerate(k.key_candidates, 1)))
                pick = input(f"  number [1 = {k.key_candidates[0]}]: ").strip()
                idx = int(pick) - 1 if pick.isdigit() and 1 <= int(pick) <= len(k.key_candidates) else 0
                ov.setdefault(k.kind, {})["key"] = k.key_candidates[idx]
                changed = True
        if changed:
            plan = P.make_plan(schemas, samples, ov, settings, pack_name=getattr(a, "name", "") or "")
    return plan


def say_plan(plan, say=print) -> None:
    for k in plan.kinds:
        say(f"{k.kind}: key {k.key or '(none)'} ({k.key_why or 'choose one with --key'}); "
            f"date {k.date or '(none)'}; match {', '.join(k.match) or '(none)'}; {len(k.sutras)} Sutra(s)"
            + (f"; links {', '.join(f'{l.field}->{l.to}' for l in k.links)}" if k.links else "")
            + ("; real samples: " + str(len(k.samples)) if k.samples else "; synthetic samples"))
    for w in plan.warnings:
        say("  warning: " + w)


# ----------------------------------------------------------------------------------------------- the Java drafter

def jvm_draft(items, SG, opts, say=print) -> dict:
    """{Sutra name: draft YAML} for every item, from ONE `sutra design --each` run."""
    drafts = {}
    with tempfile.TemporaryDirectory(prefix="schemapack-") as tmp:
        t = pathlib.Path(tmp)
        for i, it in enumerate(items):
            d = t / "in" / f"g{i:04d}"
            d.mkdir(parents=True)
            (d / "kind").write_text(it.kp.kind + "\n")
            for j, s in enumerate(it.samples, 1):
                (d / f"s{j}.json").write_text(json.dumps(s.doc))
        say(f"drafting {len(items)} Sutra(s) with one `sutra design --each` run ...")
        r = SG.sutra_cli(opts, "design", "--each", str(t / "in"), "--out", str(t / "out"))
        if r.returncode != 0:
            sys.stderr.write(r.stdout + r.stderr)
            raise SchemaPackError(f"sutra design failed (exit {r.returncode})", 1)
        for i, it in enumerate(items):
            drafts[it.name] = (t / "out" / f"g{i:04d}.sutra.yaml").read_text(encoding="utf-8")
    return drafts


# ----------------------------------------------------------------------------------------------- sutra design --schema

def design(a, cli) -> int:
    """`sutra design --schema FILE... [--kind K] [--out DIR]`: the Sutras the schemas describe, printed or written under DIR."""
    P, B = kit()
    SG = cli.sutragen()
    cli.need_yaml()
    a.inputs = [pathlib.Path(p) for p in (a.paths or [])]
    plan = make_plan(a, SG, P)
    say_plan(plan, lambda t: print(t, file=sys.stderr))
    a.jar, a.java = cli.find_jar(getattr(a, "jar", None)), cli.find_java(getattr(a, "java", None))
    items = B.prepare(plan)
    drafts = jvm_draft(items, SG, a, lambda t: print(t, file=sys.stderr))
    with tempfile.TemporaryDirectory(prefix="schemapack-pack-") as tmp:
        pack_dir = pathlib.Path(tmp) / "pack"
        built = B.assemble(plan, items, drafts, pack_dir)
        if a.out:
            out = pathlib.Path(a.out).resolve()
            for s in built.sutras:
                dest = out / s["kind"] / pathlib.Path(s["file"]).name
                dest.parent.mkdir(parents=True, exist_ok=True)
                dest.write_text(s["yaml"], encoding="utf-8")
            tests = out / "tests"
            if tests.exists():
                shutil.rmtree(tests)
            shutil.copytree(pack_dir / "tests", tests)
            print(f"wrote {len(built.sutras)} Sutra(s) under {out} (samples in {tests}; synthetic ones are named sample-synthetic-N.json)")
            r = cli.run_java(a, ["lint", str(out)], capture=True)
            lines = cli.report_lines(r)
            help_warnings = [l for l in lines if "DRS-2047" in l or "DRS-2046" in l or "DRS-2045" in l]
            for l in lines:
                if l not in help_warnings:
                    print(l)
            if help_warnings:
                print(f"({len(help_warnings)} help warning(s) about missing About text are not shown: `pack make --schema` writes config/about.yaml)")
            return 1 if r.returncode else 0
        for n, s in enumerate(built.sutras):
            if n:
                print("---")
            print(s["yaml"], end="")
    return 0


# ----------------------------------------------------------------------------------------------- pack make --schema

def readme_text(v: dict) -> str:
    L = []
    w = L.append
    w(f"{v['title']}  ({v['name']} {v['version']})")
    w("=" * 72)
    w(f"Made {v['made']} by `drishti.py pack make --schema` from {v['input']}.")
    w("This folder is everything you need to deploy: take it as it is.\n")
    w("1. WHAT IS INSIDE")
    w("-" * 72)
    w(f"  pack/{v['name']}/".ljust(18) + f"the pack: pack.yaml, sutras/ ({v['sutras']} Sutra(s) for {len(v['kinds'])} kind(s)), tests/, samples/, config/about.yaml, README.md")
    w(f"  bundle/          {v['name']}-{v['version']}.tar.gz + .sha256: the pack alone, versioned and checksummed")
    if v["data"]:
        w(f"  data/{v['store']}/".ljust(18) + "the real documents, partitioned by business date (kinds with a date field)")
    else:
        w("  (no data/)       no real documents were given, so there is no dated store: the pack serves its samples")
    w("  config/          drishti-site.yaml: a ready server configuration overlay")
    w("  run-server.sh    run-server.ps1: start a server on this folder only (JDK 21+ and the server jar needed)")
    w("  MANIFEST.json    what was generated: kinds, keys, links, counts, checksums\n")
    w("What was chosen for you (change any of it with the flags in docs/guides/SCHEMA_TO_PACK.md)")
    for k in v["kinds"]:
        w(f"  {k['kind']:<18}key {k['key'] or '(none)'} ({k['key_why']}); date {k['date'] or '(none)'}; match {', '.join(k['match']) or '(none)'} "
          f"-> {k['sutras']} Sutra(s); {k['samples']}")
        for l in k["links"]:
            w(f"  {'':<18}link {l['field']} -> {l['to']} ({l['why']})")
    if v["warnings"]:
        w("\nThings to look at")
        for x in v["warnings"]:
            w("  * " + x)
    w("\n2. DEPLOY")
    w("-" * 72)
    w("  Quickest, on this machine (nothing is copied):")
    w("      DRISHTI_JAR=/path/to/drishti-server-X.Y.Z-exec.jar DRISHTI_PORT=18480 ./run-server.sh        (Windows: .\\run-server.ps1)")
    w("  On an existing server, any one of:")
    w(f"      Admin -> Packs -> Deploy archive: choose bundle/{v['name']}-{v['version']}.tar.gz (the page shows the checks and what changes, then you confirm);")
    w(f"      python3 tools/drishti.py pack deploy bundle/{v['name']}-{v['version']}.tar.gz --to /opt/drishti/packs;")
    w(f"      cp -r pack/{v['name']} /opt/drishti/packs/  and  DRISHTI_PACKS={v['name']}  then restart.")
    w("  The pack refers to its sources by logical connector name (" + (v["connector"] or "none: it serves its samples") + "); the server's connector settings say where they are.\n")
    w("3. VERIFY")
    w("-" * 72)
    w(f"      python3 tools/drishti.py pack check pack/{v['name']}                 expect: 'sutra lint: exit 0' and 'sutra test: exit 0'")
    w(f"      python3 tools/drishti.py pack verify bundle/{v['name']}-{v['version']}.tar.gz     expect: the last line 'verified'")
    k0 = v["kinds"][0]
    w(f"      Console: type  {k0['mnemonic']} {k0['sample_id']}  and press Enter.\n")
    w("4. UPDATE AND ROLL BACK")
    w("-" * 72)
    w("  Change a schema, run `pack make --schema ... --version 1.0.1 --force` again, deploy the new bundle; Admin -> Packs keeps the previous version:")
    w(f"      python3 tools/drishti.py pack rollback {v['name']} --to /opt/drishti/packs\n")
    w("5. SYNTHETIC SAMPLES")
    w("-" * 72)
    w("  Samples named sample-synthetic-N.json were generated from the schema, not read from your data. They prove the Sutra renders; they say nothing about")
    w("  your data. Add real documents (pack make --schema SCHEMAS DATA_FOLDER) and they replace the synthetic ones.")
    w("  Fields without a schema description say TODO in config/about.yaml: add `description` to the schema and generate again.")
    return "\n".join(L) + "\n"


def make(a, cli) -> int:
    """`pack make --schema ...`: the folder of `pack make`, for the kinds the schemas describe (with or without a folder of JSON Lines)."""
    import packmake as PM
    P, B = kit()
    SG = cli.sutragen()
    cli.need_yaml()
    if not PM.NAME_RE.fullmatch(a.name):
        raise PM.MakeError("--name is lower-case letters, digits and '-'")
    out = (a.out or pathlib.Path("build") / f"{a.name}-{a.version}").resolve()
    plan = make_plan(a, SG, P)
    plan.pack.update(name=a.name, version=a.version, title=a.title or plan.pack.get("title") or a.name,
                     code=getattr(a, "code", None) or P.suggest_pack(plan.kinds, a.name)["code"])
    if getattr(a, "description", None):
        plan.pack["description"] = a.description
    say_plan(plan)
    store = "delta" if a.store == "delta" else "file"
    for k in plan.kinds:
        if k.real and k.date and a.store:
            k.template = store
        if getattr(a, "connector", None):
            k.connector = a.connector
    nokey = [k.kind for k in plan.kinds if not k.key]
    if nokey:
        raise PM.MakeError("no key could be chosen for " + ", ".join(nokey) + ": mark one with  x-drishti-role: key  or pass  --key kind=FIELD")
    if out.exists() and any(out.iterdir()):
        if not a.force:
            raise PM.MakeError(f"{out} exists; pass --force to replace it, or --out elsewhere")
        shutil.rmtree(out)
    a.jar, a.java = cli.find_jar(a.jar), cli.find_java(a.java)
    items = B.prepare(plan)
    drafts = jvm_draft(items, SG, a)
    pack_dir = out / "pack" / a.name
    built = B.assemble(plan, items, drafts, pack_dir)
    tool = ROOT / "tools" / "license_headers.py"
    if tool.exists() and str(out).startswith(str(ROOT)):
        import subprocess
        subprocess.run([sys.executable, str(tool), "--fix"], cwd=ROOT, capture_output=True)
    dated = [k for k in plan.kinds if k.template in ("delta", "file") and k.real and k.date]
    data_dir = out / "data" / ("delta" if a.store == "delta" else "files")
    if dated:
        stage_and_ingest(a, cli, plan, dated, pack_dir, data_dir, SG)
    tool_args = ["--jar", a.jar, "--java", a.java]
    print("\n== pack check")
    chk = cli.main(["pack", "check", str(pack_dir), *tool_args])
    bundle_dir = out / "bundle"
    print("\n== pack bundle")
    if cli.main(["pack", "bundle", str(pack_dir), "--out", str(bundle_dir), "--no-check", *tool_args]):
        raise PM.MakeError("bundling failed", 1)
    archive = bundle_dir / f"{a.name}-{a.version}.tar.gz"
    print("\n== pack verify")
    ver = cli.main(["pack", "verify", str(archive), *tool_args])
    PM.write_site_yaml(out, a.name, a.store)
    PM.write_scripts(out, a.name, a.store)
    kinds_v = []
    for k in plan.kinds:
        docs = k.samples
        kinds_v.append({"kind": k.kind, "key": k.key, "key_why": k.key_why, "date": k.date, "match": k.match, "sutras": len(k.sutras), "links": [vars(l) for l in k.links],
                        "mnemonic": k.mnemonic, "sample_id": str((docs[0].get(k.key) if docs else None) or f"{k.mnemonic}-0001"),
                        "samples": f"{len(docs)} real document(s)" if docs else "synthetic samples"})
    connectors = sorted({k.connector or f"{a.name}-store" for k in plan.kinds if k.template in ("delta", "file")})
    vals = dict(name=a.name, title=plan.pack["title"], version=a.version, made=datetime.date.today().isoformat(), input=", ".join(str(p) for p in list(a.schema) + list(a.inputs or [])),
                sutras=len(items), kinds=kinds_v, data=bool(dated), store=("delta" if a.store == "delta" else "files"), warnings=plan.warnings, connector=", ".join(connectors))
    (out / "README.txt").write_text(readme_text(vals), encoding="utf-8")
    files = {str(p.relative_to(out)): PM.sha256(p) for p in PM.tree_files(out) if p.relative_to(out).parts[0] != "data"}
    manifest = {"name": a.name, "version": a.version, "generator": "drishti.py pack make --schema", "kinds": {k["kind"]: {kk: k[kk] for kk in ("key", "key_why", "date", "match", "sutras", "links")} for k in kinds_v},
                "controls": {k.kind: k.controls for k in plan.kinds if k.controls}, "syntheticSutras": built.synthetic, "warnings": plan.warnings,
                "bundle": {"file": f"bundle/{archive.name}", "sha256": PM.sha256(archive)}, "tools": cli.tool_versions(),
                "madeAt": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds"), "checks": {"packCheck": chk == 0, "packVerify": ver == 0}, "files": files}
    (out / "MANIFEST.json").write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"\npack make: {out}")
    print(f"  {len(items)} Sutra(s) for {len(plan.kinds)} kind(s)" + (f" ({len(built.synthetic)} tested on synthetic samples only)" if built.synthetic else "")
          + f"; pack check {'passed' if chk == 0 else 'FAILED'}, pack verify {'passed' if ver == 0 else 'FAILED'}")
    print(f"next: read {out / 'README.txt'}, then  DRISHTI_JAR=<exec jar> DRISHTI_PORT=18480 {out / 'run-server.sh'}")
    return 1 if (chk or ver) else 0


def stage_and_ingest(a, cli, plan, dated, pack_dir, data_dir, SG) -> None:
    """Writes the real documents of the dated kinds into the dated store, one <kind>.jsonl per kind staged in a temp folder (the file names the kind)."""
    IJ = cli.load_module("ingest_jsonl", "ingest_jsonl.py")
    with tempfile.TemporaryDirectory(prefix="schemapack-stage-") as tmp:
        for k in dated:
            with open(pathlib.Path(tmp) / f"{k.kind}.jsonl", "w", encoding="utf-8") as f:
                for d in k.samples:
                    f.write(json.dumps(d, ensure_ascii=False) + "\n")
        ia = argparse.Namespace(src=[pathlib.Path(tmp)], recursive=False, pack=pack_dir, domain=None, kind=None, key=None, date=None, columns=None,
                                lake=data_dir if a.store == "delta" else None, store=a.store, root=data_dir if a.store == "files" else None,
                                mode="replace", dry_run=False, batch_rows=250_000)
        IJ.ingest(ia, print)


# ----------------------------------------------------------------------------------------------- arguments

def add_schema_arguments(p: argparse.ArgumentParser, with_pack: bool = False) -> None:
    p.add_argument("--schema", nargs="+", metavar="FILE|DIR", help="JSON Schema file(s) or folder(s) (JSON or YAML; draft 2020-12 and 07): the kinds, keys, links, labels and "
                   "About text come from them. See docs/guides/SCHEMA_TO_PACK.md")
    p.add_argument("--mnemonic", help="kind=MNEMONIC,kind2=M2 (default: initials of the kind)")
    p.add_argument("--strip", type=int, default=6, help="entries in the strip of key numbers (default 6)")
    p.add_argument("--yes", action="store_true", help="never ask which field is the key: take the first candidate and warn")
