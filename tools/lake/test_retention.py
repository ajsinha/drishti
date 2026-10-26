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

"""The risk lake keeps 7 years (docs/guides/VAR_GUIDE.md section 23.1 question 10): a table lake build creates in the domains risk and
risk-results keeps its Delta log and its removed files 7 years (its time travel), and lake maintenance never vacuums a file or deletes a
business date younger than that, whatever a table's settings say (a lake's retain-years changes it per domain). Other domains keep
their own settings.

    python3 -m unittest tools/lake/test_retention.py
"""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import maintconfig  # noqa: E402
import writers  # noqa: E402


class RetentionTest(unittest.TestCase):

    def test_a_risk_table_is_created_keeping_seven_years_of_log_and_removed_files(self):
        p = writers.retention_properties("/data/delta/risk/pnl-vector")
        self.assertEqual(p, {"delta.logRetentionDuration": "interval 2557 days", "delta.deletedFileRetentionDuration": "interval 2557 days"})
        self.assertEqual(writers.retention_properties("s3://bank/lake/risk-results/var-official")["delta.logRetentionDuration"], "interval 2557 days")
        self.assertEqual(writers.retention_properties("/data/delta/trading/trade_endur"), {})

    def test_maintenance_never_goes_below_the_floor_and_says_so(self):
        conf, notes = maintconfig.retention_floor("risk/pnl-vector", {"vacuum-hours": 168, "keep-business-days": 260, "compact": True})
        self.assertEqual((conf["vacuum-hours"], conf["keep-business-days"], conf["compact"]), (2557 * 24, 7 * 261, True))
        self.assertEqual(len(notes), 2)
        self.assertIn("raised to 61368", notes[0])
        conf, notes = maintconfig.retention_floor("risk/pnl-vector", {"vacuum-hours": 70000, "keep-business-days": None})
        self.assertEqual((conf["vacuum-hours"], conf["keep-business-days"], notes), (70000, None, []))      # longer, or every date: kept
        conf, notes = maintconfig.retention_floor("trading/trade_endur", {"vacuum-hours": 168, "keep-business-days": 260})
        self.assertEqual((conf["vacuum-hours"], conf["keep-business-days"], notes), (168, 260, []))
        conf, notes = maintconfig.retention_floor("risk/pnl-vector", {"vacuum-hours": 168}, {"risk": 0})   # a lake turns it off on purpose
        self.assertEqual((conf["vacuum-hours"], notes), (168, []))


if __name__ == "__main__":
    unittest.main()
