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

"""The scratch stack for the alert tests and pictures: tests/dist_stack.py (security on, the sample report "Trades overview", rows and a masked column per person, a local
fake SMTP) with the alert evaluator told to look a second after a load, and the file connector told to rescan every second, so a test can add trades, announce the load
and see the alert fire without waiting. ``add_trades`` appends losing trades to the lake the report reads; ``announce`` tells the server a load landed."""
from __future__ import annotations

import json
import time

import bi_report_stack
from dist_stack import REPORT, DistStack  # noqa: F401  (REPORT is re-exported for the tests)

FAST_RESCAN = "rescan-seconds: 1"

BOARD = """rachana: 1
board: desk-alerts
title: Desk wall
description: One tile that tells everyone who reads this board when the mark to market goes red, each judged on the number they may see.
pages:
  - id: p1
    title: Exposure
    tiles:
      - id: mtm
        report: trades-overview
        visual: mtm
        title: Mark to market
        at: { x: 0, y: 0, w: 6, h: 3 }
        alert:
          warn: { op: lt, value: -100000 }
          crit: { op: lt, value: -300000 }
          notify: { channels: [inbox, email], trigger: after-load, cooldown: 1h }
"""


class AlertsStack(DistStack):
    def __init__(self, tmp, smtp_port: int):
        super().__init__(tmp, smtp_port)
        bi_report_stack.CONNECTOR = bi_report_stack.CONNECTOR.replace("rescan-seconds: 3600", FAST_RESCAN)
        self.server_args += ["--drishti.alerts.measure.load-delay=1s", "--drishti.alerts.measure.tick=1s", "--drishti.alerts.measure.min-gap=0s"]

    def prepare(self, work):
        super().prepare(work)
        boards = work / "config" / "bi" / "boards"
        boards.mkdir(parents=True, exist_ok=True)
        (boards / "desk-alerts.board.yaml").write_text(BOARD)

    def add_trades(self, count: int = 10, mtm: int = -50_000, day: str = "2026-10-08") -> None:
        """More trades on the lake's latest day, each losing ``mtm`` (the sample lake's 60 trades sum to -225,000 a day)."""
        path = self.tmp / "lake" / day / "trade.jsonl"
        with open(path, "a") as f:
            for i in range(count):
                row = bi_report_stack.trade(day, i)
                row.update({"id": f"EXTRA-{i:03d}", "tradeId": f"EXTRA-{i:03d}", "mtm": mtm})
                f.write(json.dumps(row) + "\n")

    def announce(self, day: str = "2026-10-08") -> tuple:
        """The ETL says the trade data of ``day`` (the lake's latest) landed."""
        return bi_report_stack.admin("POST", "/packs/trading/loads", {"kind": "trade", "businessDate": day, "status": "ready", "rows": 70, "batchId": "alerts-test-" + str(time.time_ns())})
