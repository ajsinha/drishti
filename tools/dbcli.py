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
"""The Drishti database on the command line (docs/admin/OPERATIONS.md, "Upgrading the Drishti database"; docs/architecture/DATA_ARCHITECTURE.md,
"The one Drishti database"): copy it into a fresh database built from the complete schema. There are no migrations: this copy is how
the schema is upgraded and how a site switches between SQLite and PostgreSQL.

  db copy --from SETTINGS.yaml --to SETTINGS.yaml [--check] [--drop-columns] [--data-dir DIR] [--json]

Each SETTINGS.yaml has the layout of config/db/database.yaml (drishti.database.kind, sqlite.file, postgres.url/user/password; ${ENV}
placeholders are resolved from the environment and ${drishti.data.dir} is --data-dir, default ./data). The target must be fresh: a
SQLite file that does not exist yet, or a PostgreSQL database whose Drishti tables are empty. Stop the servers that use the source first;
the source is never changed. The command runs in Java from the server's exec jar (java -jar drishti-server-*-exec.jar db copy ...),
without starting a server. Exit 0 copied and verified, 1 the copy failed, 2 a usage error or a refusal."""
from __future__ import annotations

import argparse
import os
import subprocess

from javacmd import cli_tmp, java_cmd


def register(groups, ctx: dict) -> None:
    """Adds the `db` group; ctx holds find_java, find_jar and CliError of drishti.py."""
    g = groups.add_parser("db", help="The Drishti database: copy it into a fresh database (schema upgrade, SQLite <-> PostgreSQL)",
                          description=__doc__.split("\n\n")[0], epilog=__doc__.split("\n\n", 1)[1], formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = g.add_subparsers(dest="cmd", metavar="COMMAND", required=True)

    def run(a, extra=None) -> int:
        args = ["copy", "--from", os.path.abspath(a.source), "--to", os.path.abspath(a.target), "--data-dir", os.path.abspath(a.data_dir)]
        for flag in ("check", "drop_columns", "json"):
            if getattr(a, flag, False):
                args.append("--" + flag.replace("_", "-"))
        java, jar = ctx["find_java"](getattr(a, "java", None)), ctx["find_jar"](getattr(a, "jar", None))
        with cli_tmp() as tmp:   # the command writes nothing under the data folder but the target database
            cmd = java_cmd(java, ["-jar", jar, "db", *args, *(extra or [])], tmp=tmp)
            try:
                return subprocess.run(cmd, cwd=os.getcwd()).returncode
            except FileNotFoundError as e:
                raise ctx["CliError"](f"cannot run {cmd[0]}: {e.strerror}; pass --java or set JAVA_HOME") from None

    what = "copy the Drishti database into a fresh one built from the complete schema, every table's row count verified"
    p = sub.add_parser("copy", help=what, description=what, formatter_class=argparse.RawDescriptionHelpFormatter, epilog=(
        "examples:\n"
        "  drishti.py db copy --from config/db/database.yaml --to new-database.yaml --check      # what would be copied; nothing written\n"
        "  drishti.py db copy --from config/db/database.yaml --to new-database.yaml              # upgrade into a fresh SQLite file\n"
        "  DRISHTI_DB_PASSWORD=... drishti.py db copy --from config/db/database.yaml --to postgres-database.yaml   # switch to PostgreSQL"))
    p.set_defaults(func=run)
    p.add_argument("--from", dest="source", required=True, metavar="SETTINGS.yaml", help="the source database's settings (layout of config/db/database.yaml)")
    p.add_argument("--to", dest="target", required=True, metavar="SETTINGS.yaml", help="the fresh target's settings")
    p.add_argument("--check", action="store_true", help="read the source and report what would be copied; write nothing")
    p.add_argument("--drop-columns", action="store_true", help="copy even though the new schema no longer has some of the source's columns (their values are left out)")
    p.add_argument("--data-dir", default="data", help="what ${drishti.data.dir} means in the settings files (default ./data)")
    p.add_argument("--json", action="store_true", help="the report as JSON")
    p.add_argument("--java", help="the java to run (default $JAVA_HOME/bin/java)")
    p.add_argument("--jar", help="the server's exec jar (default the newest drishti-server/target/drishti-server-*-exec.jar, or DRISHTI_JAR)")
