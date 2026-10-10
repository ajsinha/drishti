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

"""Create a complete Drishti pack from a folder of JSON Lines files.

    uv run --with pyyaml python tools/packgen/pack_from_jsonl.py data/jsonl --name my-bank --title "My bank" \\
        --key trade=tradeId,counterparty=counterpartyId --match trade=productType --date trade=businessDate

Writes config/packs/<name>/ (or --out): pack.yaml (kinds, mnemonics, columns, and with --lake a Delta connector and routes),
samples/ (catalog.json and one file per document, up to --catalog-samples per kind), one Sutra per kind or per
--match group (tools/sutragen.py), tests/<sutra>/ with sample-*.json and expect.yaml, config/about.yaml (kind
titles and glossary entries with TODO text for every field the Sutras show) and a README.md. Then it runs
`sutra lint` and `sutra test` on the new pack and prints the result.

--date FIELD (or kind=FIELD,...) names the business-date field of each document. The pack then reads from a dated
Delta lake (business_date partition; the business-date picker and history work); --lake DIR writes the documents
into such a lake (<DIR>/<pack>/<kind>/, partitioned by that date, in the layout pack.yaml declares) so
DRISHTI_DELTA_ROOT=DIR serves it. Needs: uv run --with deltalake --with pyarrow --with pyyaml. Documents without the
date are reported and left out of the lake.
"""
from __future__ import annotations

import argparse
import json
import pathlib
import re
import shutil
import subprocess
import sys
from datetime import date

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(ROOT / "tools"))
import sutragen as SG  # noqa: E402


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
    seen: dict[str, int] = {}
    for d in docs[:50]:
        for k, v in d.items():
            if not isinstance(v, (dict, list)) and k != key and v is not None:
                seen[k] = seen.get(k, 0) + 1
    return [k for k in seen][:n]


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
    """The command line; tools/drishti.py (`pack new`) embeds it as a parent parser."""
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0], formatter_class=argparse.RawDescriptionHelpFormatter,
                                epilog=__doc__.split("\n\n", 1)[1], add_help=add_help)
    p.add_argument("inputs", type=pathlib.Path, nargs="+", help="folders of *.jsonl and/or single .jsonl files")
    p.add_argument("--name", required=True, help="pack name (letters, digits, '-')")
    p.add_argument("--title", help="pack title (default: the name)")
    p.add_argument("--description", help="pack description")
    p.add_argument("--version", default="1.0.0", help="pack version written to pack.yaml (default 1.0.0)")
    p.add_argument("--mnemonic", help="kind=MNEMONIC,kind2=M2 (default: initials of the kind, upper case)")
    p.add_argument("--out", type=pathlib.Path, help="default config/packs/<name>")
    p.add_argument("--force", action="store_true", help="overwrite an existing --out folder")
    p.add_argument("--catalog-samples", type=int, default=25, help="documents per kind kept in samples/ (default 25)")
    p.add_argument("--lake", type=pathlib.Path, help="also write the documents into a Delta lake here (needs --date); same as --store delta --lake")
    p.add_argument("--store", choices=("delta", "files"), default="delta", help="the dated store the pack reads: delta (default) or the File connector's files")
    p.add_argument("--files-root", type=pathlib.Path, help="with --store files: also write the documents as <root>/<pack>/<date>/<kind>.jsonl")
    SG.add_arguments(p)
    return p


def main(argv=None) -> int:
    return run(build_parser().parse_args(argv))


def run(opts) -> int:
    """Generates the pack described by the parsed options; 0 when its lint and test pass."""
    import yaml  # PyYAML

    if not re.fullmatch(r"[a-z0-9][a-z0-9-]*", opts.name):
        raise SystemExit("pack_from_jsonl: --name is lower-case letters, digits and '-'")
    out = (opts.out or ROOT / "config" / "packs" / opts.name).resolve()
    if out.exists() and any(out.iterdir()):
        if not opts.force:
            raise SystemExit(f"pack_from_jsonl: {out} exists; pass --force to overwrite it")
        shutil.rmtree(out)
    if (opts.lake or opts.files_root) and not opts.date:
        raise SystemExit("pack_from_jsonl: --lake needs --date (the business-date field)")
    say = print
    kinds = SG.load_folder(opts.inputs, opts.recursive, opts.kind)
    key_d, key_k = SG.parse_scalar(opts.key)
    date_d, date_k = SG.parse_scalar(opts.date)
    mn_d, mn_k = SG.parse_scalar(opts.mnemonic)
    keys = {k: key_k.get(k, key_d or "id") for k in kinds}
    dates = {k: date_k.get(k, date_d) for k in kinds if date_k.get(k, date_d)}
    opts.key = ",".join(f"{k}={v}" for k, v in keys.items())
    match_d, match_k = SG.parse_match(opts.match)

    sutras, tests = out / "sutras", out / "tests"
    groups = SG.generate(kinds, opts, sutras, tests, say)
    SG.summary(groups, say)

    taken: set[str] = set()
    mnemonics = {}
    for k in sorted(kinds):
        m = mn_k.get(k) or mnemonic_of(k, taken)
        taken.add(m)
        mnemonics[m] = {"kind": k, "label": k.replace("-", " ").replace("_", " ").capitalize()}
    columns = {k: scalar_columns(v, keys[k]) for k, v in kinds.items()}
    lake_cols = {}
    for k in dates:
        extra = [f for f in SG.for_kind(k, match_d, match_k) if f != dates[k]]
        lake_cols[k] = list(dict.fromkeys(extra + columns[k]))
    manifest: dict = {"pack": opts.name, "version": getattr(opts, "version", None) or "1.0.0", "code": pack_code(opts.name), "title": opts.title or opts.name,
                      "description": opts.description or f"Generated from {', '.join(p.name for p in opts.inputs)} by tools/packgen/pack_from_jsonl.py.",
                      "kinds": sorted(kinds), "sutras": "sutras", "samples": "samples", "mnemonics": mnemonics, "columns": columns}
    if dates:
        if opts.store == "files":
            conn = {"plugin": "file", "enabled": "${DRISHTI_FILES_ENABLED:true}", "kinds": sorted(dates),
                    "settings": {"root": "${DRISHTI_FILES_ROOT:${drishti.data.dir:./data}/files}", "domain": opts.name, "id-field": "id",
                                 "layout": {k: {"columns": lake_cols[k]} for k in sorted(dates)}}}
        else:
            conn = {"plugin": "delta", "enabled": "${DRISHTI_LAKE_ENABLED:true}", "kinds": sorted(dates),
                    "settings": {"root": "${DRISHTI_DELTA_ROOT:${drishti.data.dir:./data}/delta}", "domain": opts.name,
                                 "layout": {k: {"columns": lake_cols[k], "sort-by": "id", "file-rows": 250000, "row-group-rows": 1000}
                                            for k in sorted(dates)}}}
        manifest["connectors"] = [f"{opts.name}-store"]                  # named by its logical name (docs/connectors/CONNECTOR_FILES.md) ...
        manifest["connector-templates"] = {f"{opts.name}-store": conn}   # ... with the connection settings as the pack's template
        manifest["routes"] = {k: f"{opts.name}-store" for k in sorted(dates)}
        manifest["ingest"] = {k: {"key": keys[k], "date": dates[k]} for k in sorted(dates)}
    out.mkdir(parents=True, exist_ok=True)
    note = ("# Generated by tools/packgen/pack_from_jsonl.py; now yours to edit.\n"
            + "".join(f"# business date of {k}: $.{f} (the Delta partition business_date)\n" for k, f in sorted(dates.items())))
    (out / "pack.yaml").write_text(note + yaml.safe_dump(manifest, sort_keys=False, width=120, allow_unicode=True), encoding="utf-8")

    # samples
    cat = []
    for k, docs in sorted(kinds.items()):
        chosen = SG.pick(docs, opts.catalog_samples, keys[k], dates.get(k))
        sd = out / "samples" / k
        sd.mkdir(parents=True, exist_ok=True)
        for d in chosen:
            kv = SG.get(d, keys[k])
            if kv is SG.MISSING:
                continue
            (sd / f"{kv}.json").write_text(json.dumps(d, indent=1, ensure_ascii=False) + "\n", encoding="utf-8")
            cat.append({"kind": k, "id": str(kv), "title": str(kv), "subtitle": k})
    (out / "samples" / "catalog.json").write_text(json.dumps(cat, indent=1) + "\n", encoding="utf-8")

    # tests expectations
    for g in groups:
        (tests / g.name / "expect.yaml").write_text("noErrors: true\n", encoding="utf-8")

    # about
    about: dict = {"about": 1, "kinds": {}}
    for k, fields in shown_fields(groups).items():
        about["kinds"][k] = {"title": k.replace("-", " ").capitalize(), "about": f"TODO: one sentence about a {k}, e.g. ${{$.{keys[k]}}}.",
                             "glossary": {f: {"term": f, "means": "TODO: what this field means."} for f in fields}}
    (out / "config").mkdir(exist_ok=True)
    (out / "config" / "about.yaml").write_text(yaml.safe_dump(about, sort_keys=False, width=120, allow_unicode=True), encoding="utf-8")

    # dated store, through the standalone ingest tool
    lake_line = ""
    target = opts.files_root if opts.store == "files" else opts.lake
    if target:
        import ingest_jsonl as IJ
        ia = argparse.Namespace(src=opts.inputs, recursive=opts.recursive, pack=out, domain=None, kind=opts.kind, key=None, date=None,
                                columns=None, lake=opts.lake, store=opts.store, root=opts.files_root, mode="replace", dry_run=False,
                                batch_rows=250_000)
        rep = IJ.ingest(ia, say)
        skipped = sum(rep.no_date.values()) + sum(rep.no_key.values())
        undated = [k for k in kinds if k not in dates]
        lake_line = (f"\nDated store ({opts.store}): {sum(rep.rows.values())} rows written to {target} (domain {opts.name}); {skipped} document(s) "
                     f"without a date or id skipped" + (f"; kinds without --date not loaded: {', '.join(undated)}" if undated else ""))
        say(lake_line.strip())

    (out / "README.md").write_text(readme(opts, kinds, groups, keys, dates, lake_line), encoding="utf-8")
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


def readme(opts, kinds, groups, keys, dates, lake_line) -> str:
    rows = "\n".join(f"| {k} | {len(v)} | `{keys[k]}` | {('`' + dates[k] + '`') if k in dates else ''} |" for k, v in sorted(kinds.items()))
    return f"""# {opts.title or opts.name}

Generated by `tools/packgen/pack_from_jsonl.py` from `{', '.join(map(str, opts.inputs))}`. Nothing here is final: edit it.

| kind | documents | id field | business date |
|---|---|---|---|
{rows}

## What was generated

- `pack.yaml`: kinds, mnemonics (change them), `columns` (the pick-list columns), and, for dated kinds, a Delta connector
  (`domain: {opts.name}`, root `${{DRISHTI_DELTA_ROOT}}`) and routes.
- `sutras/<kind>/`: {len(groups)} Sutra(s) drafted by auto-design, one per kind or per match-field value; each matches with
  a `where` and a priority, shows its id from the key field.
- `tests/<sutra>/`: up to {opts.samples} samples each and an `expect.yaml` (`noErrors: true`).
- `samples/`: up to {opts.catalog_samples} documents per kind so the pack opens before any data source.
- `config/about.yaml`: kind titles and glossary entries whose text is `TODO`; fill them (the coverage check names the gaps).
{lake_line}

## Next steps

1. Enable the pack: `DRISHTI_PACKS=<others>,{opts.name}` (and `DRISHTI_PACKS_DIR` if it is not under `./config/packs`).
2. Open a document in the console and refine the Sutras in the Build workbench; add `nonEmpty:` to `expect.yaml`.
3. Fill the `TODO` entries in `config/about.yaml`; add `console.examples`, `roles`, `alerts` as the pack guide describes.
4. Dated packs: serve the lake with `DRISHTI_DELTA_ROOT=<lake>`; the business-date picker lists its partitions.
5. Check: `java -jar drishti-server-*-exec.jar sutra lint config/packs/{opts.name}` and `... sutra test config/packs/{opts.name}`.
"""


if __name__ == "__main__":
    sys.exit(main())
