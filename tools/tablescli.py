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
"""The table layer on the command line (docs/guides/TABLES_GUIDE.md, docs/guides/CLI_GUIDE.md "tables"): virtual tables and models
under config/tables, checked, previewed over sample documents, traced (lineage), offered for new tables, and BI's datasets and models
migrated into them.

  tables list    [--root DIR] [--json]                                 every table and model, its source and materialisation
  tables check   [--root DIR] [--sample TABLE=FILE.jsonl]... [--stored NAME,...]... [--json]
                                                                      every definition and the catalogue; exit 1 on an error
  tables preview TABLE --sample FILE.jsonl [--related TABLE=FILE.jsonl]... [--fx FILE.jsonl] [--limit N] [--json]
                                                                      the table's first rows over sample documents (JSONL, .gz too)
  tables lineage NAME [--json]                                        who uses a table or a model
  tables offer   NAME --sample FILE.jsonl (--kind KIND | --lake LAKE --table TABLE) [--grain '$.LIST[*]'] [--stored NAME,...] [--write]
                                                                      the flat virtual table offered for a new table (columns from a sample)
  tables migrate [--bi DIR] [--dry-run | --check] [--report FILE] [--audit FILE] [--who NAME] [--moved-to DIR] [--json]

The commands run in Java from the server's exec jar (java -jar drishti-server-*-exec.jar tables ...), without starting a server: they
read definitions and sample files only. Paths are read from the folder you run them in (config/tables and config/bi by default)."""
from __future__ import annotations

import argparse
import os
import subprocess

from javacmd import cli_tmp, java_cmd


def register(groups, ctx: dict) -> None:
    """Adds the `tables` group; ctx holds find_java, find_jar and CliError of drishti.py."""
    t = groups.add_parser("tables", help="Virtual tables and models: list, check, preview, lineage, offer, migrate (BI's datasets and models)",
                          description=__doc__.split("\n\n")[0], epilog=__doc__.split("\n\n", 1)[1], formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = t.add_subparsers(dest="cmd", metavar="COMMAND", required=True)

    def run(a, extra=None) -> int:
        args = [a.cmd]
        if getattr(a, "name", None):
            args.append(a.name)
        for flag in ("root", "bi", "limit", "report", "audit", "who", "moved_to", "kind", "lake", "table", "grain", "fx"):
            v = getattr(a, flag, None)
            if v is not None:
                args += ["--" + flag.replace("_", "-"), str(v)]
        for s in getattr(a, "sample", None) or []:
            args += ["--sample", s]
        for s in getattr(a, "stored", None) or []:
            args += ["--stored", s]
        for s in getattr(a, "related", None) or []:
            args += ["--related", s]
        for flag in ("json", "dry_run", "check", "write"):
            if getattr(a, flag, False):
                args.append("--" + flag.replace("_", "-"))
        java, jar = ctx["find_java"](getattr(a, "java", None)), ctx["find_jar"](getattr(a, "jar", None))
        with cli_tmp() as tmp:   # the command writes nothing under the data folder
            cmd = java_cmd(java, ["-jar", jar, "tables", *args, *(extra or [])], tmp=tmp)
            try:
                return subprocess.run(cmd, cwd=os.getcwd()).returncode
            except FileNotFoundError as e:
                raise ctx["CliError"](f"cannot run {cmd[0]}: {e.strerror}; pass --java or set JAVA_HOME") from None

    def add(name, help_, epilog):
        p = sub.add_parser(name, help=help_, description=help_, epilog=epilog, formatter_class=argparse.RawDescriptionHelpFormatter)
        p.set_defaults(func=run)
        p.add_argument("--root", help="the catalogue's folder (default config/tables: a folder per domain)")
        p.add_argument("--json", action="store_true", help="JSON instead of words")
        p.add_argument("--java", help="the java to run (default $JAVA_HOME/bin/java)")
        p.add_argument("--jar", help="the server's exec jar (default the newest drishti-server/target/drishti-server-*-exec.jar, or DRISHTI_JAR)")
        return p

    add("list", "every table and model of the catalogue", "examples:\n  drishti.py tables list\n  drishti.py tables list --json")
    p = add("check", "check every table and model, and the catalogue (names, references, cycles); --sample adds the checks a sample allows",
            "examples:\n  drishti.py tables check\n  drishti.py tables check --sample bi.trading_risk_trades=data/samples/trades.jsonl")
    p.add_argument("--sample", action="append", metavar="TABLE=FILE.jsonl", help="sample documents for a table (repeatable)")
    p.add_argument("--stored", action="append", metavar="NAME,...", help="stored tables' names (lake tables, kinds): a virtual table named like one is refused")
    p = add("preview", "a table's first rows over sample documents (JSON Lines, one entity a line)",
            "examples:\n  drishti.py tables preview bi.trading_cashflows_cashflows --sample trades.jsonl --limit 10\n"
            "  drishti.py tables preview trading.trade_legs --sample trades.jsonl --related core.books=books.jsonl --fx fx-spot.jsonl")
    p.add_argument("name", metavar="TABLE", help="the table (or its was: name)")
    p.add_argument("--sample", action="append", required=True, metavar="FILE.jsonl", help="the sample documents")
    p.add_argument("--related", action="append", metavar="TABLE=FILE.jsonl", help="another table's sample, what related() reads (repeatable)")
    p.add_argument("--fx", metavar="FILE.jsonl", help="spot rates (PAIR_NAME 'EUR/USD', MID), what fx() reads; without it only a currency to itself converts")
    p.add_argument("--limit", type=int, help="the most rows shown (default 20)")
    p = add("lineage", "who uses a table or a model: models, tables over it, look-ups",
            "examples:\n  drishti.py tables lineage bi.trading_risk_trades\n  drishti.py tables lineage trading-risk")
    p.add_argument("name", metavar="NAME", help="a table or a model")
    p = add("offer", "the flat virtual table offered for a table a data load created: one column per scalar field of the sample, typed; "
            "the lists of objects as grains to choose instead; --write accepts it (a new file under config/tables/<domain>/)",
            "examples:\n  drishti.py tables offer bank.accounts --sample accounts.jsonl --kind account\n"
            "  drishti.py tables offer bank.moves --sample accounts.jsonl --lake demo --table bank.accounts --grain '$.MOVES[*]' --write")
    p.add_argument("name", metavar="NAME", help="the new virtual table, <domain>.<name>")
    p.add_argument("--sample", action="append", required=True, metavar="FILE.jsonl", help="a sample of the new table's documents")
    src = p.add_mutually_exclusive_group(required=True)
    src.add_argument("--kind", help="the kind the table reads (a pack serves it)")
    src.add_argument("--table", help="the lake table it reads (with --lake)")
    p.add_argument("--lake", help="the lake of --table")
    p.add_argument("--grain", help="one row per element of this list instead ($.LEGS[*]); the offer names the lists the sample holds")
    p.add_argument("--stored", action="append", metavar="NAME,...", help="stored tables' names the new name must not take")
    p.add_argument("--write", action="store_true", help="accept: write the offer to a new file (refused when the name is taken)")
    p = add("migrate", "BI's datasets and models into tables and models of the layer, DEL into Rachana (--dry-run: the diff, nothing written)",
            "examples:\n  drishti.py tables migrate --dry-run\n  drishti.py tables migrate --check        # CI: exit 1 when something would change\n"
            "  drishti.py tables migrate --report migration.txt --audit data/audit/tables-migrate.jsonl")
    p.add_argument("--bi", help="the BI folder (default config/bi)")
    mode = p.add_mutually_exclusive_group()
    mode.add_argument("--dry-run", action="store_true", help="print the diff and the report; write nothing")
    mode.add_argument("--check", action="store_true", help="exit 1 when anything would change; write nothing")
    p.add_argument("--report", help="write the report (every item, every expression before and after) to this file")
    p.add_argument("--audit", help="append one audit line per file written (JSON Lines)")
    p.add_argument("--who", help="the person named in the audit (default system)")
    p.add_argument("--moved-to", help="move the migrated BI files here (an installation's <data>/migrated/2.9.11.e/); default: left in place")
