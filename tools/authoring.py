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

"""The pack-authoring commands of drishti.py: `data profile`, `pack regenerate`, `pack diff`, `pack catalogue`, `pack i18n export|import`.

Each command's logic lives in its own module (dataprofile, packregen, packdiff, packcatalogue, packi18n); this file only registers the
sub-commands and turns their errors into drishti.py exit codes (0 ok, 1 findings or failure, 2 usage).
"""
from __future__ import annotations

import pathlib


def register(cli, packs, data, add, jvm, js, PFJ) -> None:
    """`packs` and `data` are the `pack` and `data` sub-command groups of drishti.py; `add` is its sub-command helper."""

    def guarded(modname, fn):
        def run(a, extra):
            mod = cli.load_module(modname, modname + ".py")
            try:
                return fn(mod, a)
            except tuple(getattr(mod, n) for n in dir(mod) if n.endswith("Error") and isinstance(getattr(mod, n), type)) as e:
                raise cli.CliError(str(e), getattr(e, "code", 2)) from None
        return run

    # data profile
    p = add(data, "profile", guarded("dataprofile", lambda m, a: m.run(a, cli)), "data profile: every field of your JSON Lines (types, coverage, distinct, examples) and the key, date and match candidates, with a ready `pack make` line",
            [js], "example:\n  drishti.py data profile data/jsonl\n  drishti.py data profile data/jsonl --kind trade --json\n"
            "Streaming: memory is bounded by --max-distinct per field. Exit 0; 2 when nothing could be read.")
    p.add_argument("inputs", type=pathlib.Path, nargs="+", help="folders of *.jsonl and/or single .jsonl files")
    p.add_argument("--kind", help="treat every document as this kind (default: the file stem, or the loader envelope's kind)")
    p.add_argument("--max-distinct", type=int, default=10000, help="distinct values tracked per field (default 10000); past it counts are 'at least'")
    p.add_argument("-r", "--recursive", action="store_true", help="read *.jsonl in subfolders too")

    # pack regenerate
    p = add(packs, "regenerate", guarded("packregen", lambda m, a: m.regenerate(a, cli, getattr(a, "_actions", ()))),
            "pack regenerate: regenerate Sutras, about text and tests from new data and three-way merge them into the pack, keeping your edits",
            [PFJ.build_parser(add_help=False), js],
            "example:\n  drishti.py pack regenerate config/packs/my-bank data/jsonl --dry-run\n  drishti.py pack regenerate config/packs/my-bank data/jsonl\n"
            "Options not given are taken from the ones the pack was generated with (stored in <pack>/.generated/). Untouched generated files are refreshed, new\n"
            "groups added, files you edited kept, files edited on both sides merged line by line (conflict markers, listed, exit 1). Needs the jar (like pack new).")
    # the parents' positional `inputs` comes first; this command takes the pack folder before it, and does not require --name (the pack has one)
    pack_arg = p.add_argument("pack", metavar="PACK", help="the pack folder to update")
    p._actions.remove(pack_arg)
    p._actions.insert(0, pack_arg)
    for act in p._actions:
        if act.dest == "name":
            act.required = False
    p.add_argument("--dry-run", action="store_true", help="show the plan; write nothing")
    p.set_defaults(_actions=list(p._actions))

    # pack diff
    p = add(packs, "diff", guarded("packdiff", lambda m, a: m.run(a, cli)),
            "pack diff: what changed between two pack versions (folders or bundles); kinds and mnemonics removed or renamed are BREAKING", [js],
            "example:\n  drishti.py pack diff dist/my-bank-1.0.0.tar.gz config/packs/my-bank --fail-on breaking\n"
            "Levels: breaking (kind or mnemonic removed/renamed), selection (Sutra added/removed, match where/priority changed), layout (data layout), change.\n"
            "Exit 1 when a finding of the --fail-on level exists (breaking by default, any, none).")
    p.add_argument("old", metavar="OLD", help="pack folder, `pack make` folder or bundle (.tar.gz)")
    p.add_argument("new", metavar="NEW")
    p.add_argument("--unified", action="store_true", help="show a unified diff of every changed Sutra")
    p.add_argument("--fail-on", choices=("breaking", "any", "none"), default="breaking", help="exit 1 on breaking findings (default), any difference, or never")

    # pack catalogue
    p = add(packs, "catalogue", guarded("packcatalogue", lambda m, a: m.run(a, cli)),
            "pack catalogue: a readable catalogue of a pack for business review (kinds, fields and glossary, every Sutra, match rules, panels)", [jvm, js],
            "example:\n  drishti.py pack catalogue config/packs/my-bank --out build/catalogue --format html --shots\n"
            "--shots also renders every Sutra's samples with `sutra preview` (HTML snapshots) and links them.")
    p.add_argument("pack", metavar="PACK")
    p.add_argument("--out", metavar="DIR", help="default build/<pack>-catalogue")
    p.add_argument("--format", choices=("md", "html"), default="md", help="catalogue.md (default) or one self-contained index.html")
    p.add_argument("--shots", action="store_true", help="include HTML previews of each Sutra's samples (needs the jar)")

    # pack i18n
    i18n = packs.add_parser("i18n", help="i18n export|import: translate a pack's About text through a CSV", description="Translate config/about.yaml through a spreadsheet; import writes config/about.<lang>.yaml.")
    sub = i18n.add_subparsers(dest="i18n_cmd", metavar="ACTION", required=True)
    e = add(sub, "export", guarded("packi18n", lambda m, a: m.export(a, cli)), "i18n export: write the pack's About texts as CSV (key, source, translation)", [js],
            "example:\n  drishti.py pack i18n export config/packs/my-bank --lang fr --out build/fr.csv")
    e.add_argument("pack", metavar="PACK")
    e.add_argument("--lang", required=True, help="language tag (fr, de, pt-BR); an existing about.<lang>.yaml prefills the translation column")
    e.add_argument("--out", required=True, metavar="FILE.csv")
    i = add(sub, "import", guarded("packi18n", lambda m, a: m.import_(a, cli)), "i18n import: write config/about.<lang>.yaml from the CSV; reports missing and extra keys", [js],
            "example:\n  drishti.py pack i18n import config/packs/my-bank build/fr.csv --lang fr\n"
            "Exit 1 on extra keys, changed ${...} expressions or an invalid overlay; missing keys only with --strict (the overlay merges over English key by key).")
    i.add_argument("pack", metavar="PACK")
    i.add_argument("file", metavar="FILE.csv")
    i.add_argument("--lang", help="language tag (default: the end of the file name, e.g. about-fr.csv)")
    i.add_argument("--dry-run", action="store_true", help="check and report; write nothing")
    i.add_argument("--strict", action="store_true", help="missing translations fail the run too")
