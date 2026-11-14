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

"""Row retention in the lake maintenance (docs/guides/DATA_LAKE_GUIDE.md, "Row retention"): the table's ``retention: {ttl, on, max-rows}`` read
from a lake file's entry and from the maintenance file, validated, floored for the risk domains, handed to the Delta, Iceberg and files steps (the
Java side's parity across the four stores is RetentionParityTest).

    drishti-console/.venv/bin/python -m pytest tools/lake/test_maintain_rows.py -q
"""
import json
import sys
import tempfile
import unittest
from datetime import date
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
import maintain  # noqa: E402
import maintain_iceberg  # noqa: E402
import maintain_rows  # noqa: E402
import maintconfig  # noqa: E402


def launcher(data, jar=None, java=None, main=None):
    return ["<launcher>", main]


@mock.patch("stores.loader_command", launcher)
class RowRetentionConfig(unittest.TestCase):
    def test_a_lake_file_entry_gives_the_block_and_a_form_is_validated(self):
        conf, _ = maintconfig.from_lake_entry({"row-retention": {"ttl": "8h", "on": "UPDATED_AT", "max-rows": 5}})
        self.assertEqual({"ttl": "8h", "on": "UPDATED_AT", "max-rows": 5}, conf["row-retention"])
        clean, _ = maintconfig.clean_settings({"row-retention": {"ttl": "8h", "max-rows": "5"}})
        self.assertEqual({"ttl": "8h", "max-rows": "5"}, clean["row-retention"])
        for bad in ({"ttl": "soon"}, {"max-rows": "0"}):
            with self.assertRaises(ValueError):
                maintconfig.clean_settings({"row-retention": bad})
        self.assertIsNone(maintconfig.TABLE_DEFAULTS["row-retention"])

    def test_the_risk_domains_floor_raises_the_ttl_and_drops_the_cap(self):
        conf, notes = maintconfig.retention_floor("risk/var", {"row-retention": {"ttl": "8h", "max-rows": "5"}})
        self.assertEqual({"ttl": "2557d"}, conf["row-retention"])
        self.assertTrue(notes)
        conf, notes = maintconfig.retention_floor("risk/var", {"row-retention": {"ttl": "3650d"}})
        self.assertEqual({"ttl": "3650d"}, conf["row-retention"])
        self.assertEqual([], notes)
        self.assertEqual({"ttl": "8h"}, maintconfig.retention_floor("news/items", {"row-retention": {"ttl": "8h"}})[0]["row-retention"])

    def test_iceberg_gets_the_options_and_a_table_without_a_block_none(self):
        base = {"vacuum-hours": 168, "compact": False}
        block = {"ttl": "8h", "on": "UPDATED_AT", "max-rows": "5"}
        cmd = maintain_iceberg.command("/lake/news/items", {**base, "row-retention": block}, date(2026, 11, 14), False, False, 0, Path("/data"),
                                       jar="x.jar", java="java")
        self.assertEqual(["8h", "UPDATED_AT", "5"], [cmd[cmd.index(k) + 1] for k in ("--ttl", "--on", "--max-rows")])
        plain = maintain_iceberg.command("/lake/news/items", base, date(2026, 11, 14), False, False, 0, Path("/data"))
        self.assertNotIn("--ttl", plain)

    def test_the_delta_and_files_command_and_its_answer(self):
        seen = []

        def runner(cmd):
            seen.append(cmd)
            return json.dumps({"dropped": 2, "dates": [{"date": "2026-11-14", "rows": 5, "dropped": 2}]})
        rr = maintain_rows.clean({"row-retention": {"ttl": "8h", "max-rows": 5}})
        self.assertEqual({"ttl": "8h", "max-rows": "5"}, rr)
        self.assertIsNone(maintain_rows.clean({}))
        got = maintain_rows.apply("/lake", "delta", "news/items", rr, True, data=Path("/data"), runner=runner)
        self.assertEqual(2, got["dropped"])
        for word in ("--lake", "/lake", "--format", "delta", "--table", "news/items", "--ttl", "8h", "--max-rows", "5", "--dry-run"):
            self.assertIn(word, seen[0])

    def test_a_files_store_lists_its_tables(self):
        with tempfile.TemporaryDirectory() as d:
            for day in ("2026-11-13", "2026-11-14"):
                (Path(d) / "risk" / day).mkdir(parents=True)
                (Path(d) / "risk" / day / "trades.jsonl").write_text("{}\n")
            self.assertEqual(["risk/trades"], maintain_rows.files_tables(d, ["*"]))
            self.assertEqual([], maintain_rows.files_tables(d, ["other"]))

    def test_lake_maintain_runs_a_files_store(self):
        calls = []
        real = maintain_rows.apply
        maintain_rows.apply = lambda root, fmt, name, rr, dry, **kw: calls.append((root, fmt, name, rr, dry)) or {"dropped": 1, "dates": []}
        try:
            with tempfile.TemporaryDirectory() as d:
                (Path(d) / "risk" / "2026-11-14").mkdir(parents=True)
                (Path(d) / "risk" / "2026-11-14" / "trades.jsonl").write_text("{}\n")
                cfg = {"lakes": [{"root": d, "format": "files", "tables": {"risk/trades": {"row-retention": {"ttl": "8h"}}}}]}
                out = maintain.run_detailed(cfg, False, quiet=True)
        finally:
            maintain_rows.apply = real
        self.assertEqual(0, out["failures"])
        self.assertEqual([(d, "files", "risk/trades", {"ttl": "8h"}, False)], calls)
        self.assertEqual("files", out["tables"][0]["format"])


if __name__ == "__main__":
    unittest.main()
