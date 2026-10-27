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

"""Every export and download offers JSON Lines (2.9.4, docs/guides/DATA_FORMATS.md section 4): the server's export endpoints and
format parameters, the cluster's forwarding allow-list, the console's download routes (each CSV route has a .jsonl twin), every UI menu
and button that offers CSV or Excel, and the command-line flags. Read from the source, so a new export without JSONL fails here.

    python3 -m unittest -q tools/test_exports_offer_jsonl.py
"""
from __future__ import annotations

import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SERVER = ROOT / "drishti-server" / "src" / "main" / "java" / "com" / "ash" / "drishti" / "server"
CONSOLE = ROOT / "drishti-console"


def text(path: Path) -> str:
    return path.read_text(encoding="utf-8")


class Server(unittest.TestCase):
    #: file -> what proves JSON Lines is offered there
    WANT = {"api/SearchController.java": '@GetMapping(path = "/jsonl"',
            "bi/sql/SqlController.java": 'case "jsonl" ->',
            "bi/runs/RunsController.java": 'format.equals("jsonl")',
            "dist/ContentExporter.java": "JSONL(JsonlWriter.MEDIA_TYPE",
            "dist/StatementController.java": 'format.equals("jsonl")',
            "dist/SubscriptionService.java": '"html", "pdf", "xlsx", "jsonl"',
            "reports/ReportService.java": 'format.equals("jsonl")',
            "exposure/ExposureController.java": '"/captures/{id}/jsonl"',
            "var/VarController.java": '"/captures/{id}/jsonl"',
            "net/ViewForwardFilter.java": 'Set.of("csv", "jsonl", "compare")'}

    def test_every_server_export_offers_jsonl(self):
        for rel, proof in self.WANT.items():
            with self.subTest(rel):
                self.assertIn(proof, text(SERVER / rel))
        self.assertIn('"jsonl".equalsIgnoreCase(format)', text(SERVER / "exposure/ExposureController.java"))      # the profile's format=
        self.assertIn('"jsonl".equalsIgnoreCase(format)', text(SERVER / "bi/runs/RunsController.java"))           # a stored result

    def test_the_compliance_export_is_jsonl(self):
        export = text(SERVER / "collab/compliance/ExportService.java")
        self.assertNotIn(".ndjson", export)
        for name in ("shares", "threads", "chains", "holds", "inbox", "outbox"):
            self.assertIn(f'"{name}.jsonl"', export)


class Console(unittest.TestCase):
    def test_every_csv_download_route_has_a_jsonl_twin(self):
        routes = []
        for f in sorted((CONSOLE / "routes").glob("*.py")):
            routes += re.findall(r'@router\.(?:get|post)\("([^"]+)"\)', text(f))
        csv = [r for r in routes if r.endswith(".csv")]
        self.assertGreaterEqual(len(csv), 4)
        for r in csv:
            with self.subTest(r):
                self.assertIn(r[:-4] + ".jsonl", routes, f"{r} has no .jsonl twin")
        self.assertIn("/export/search.{fmt}", routes)                         # one route for both
        self.assertIn('FORMATS = ("pdf", "png", "xlsx", "csv", "jsonl")', text(CONSOLE / "routes" / "dist_routes.py"))
        self.assertIn('"/csv", "/jsonl"', text(CONSOLE / "routes" / "var_routes.py"))
        self.assertIn('("/csv", "/jsonl")', text(CONSOLE / "routes" / "exposure_routes.py"))
        self.assertIn("def to_jsonl(", text(CONSOLE / "core" / "export.py"))

    #: every UI menu or button that offers CSV or Excel: the file and what proves JSON Lines is offered beside it
    UI = {"web/static/js/table-chart.js": "self.grid('jsonl')",
          "web/static/js/pivot.js": "self.exportAs('jsonl')",
          "web/static/js/bi-runs.js": "b('jsonl', 'Export JSONL')",
          "web/templates/bi/report.html": 'data-export-fmt="jsonl"',
          "web/static/js/bi-statement.js": "'.jsonl?page='",
          "web/templates/terminal/view.html": 'data-export-fmt="jsonl"',
          "web/templates/_macros/panels.html": 'data-export-ext="jsonl"',
          "web/static/js/view.js": "data-export-ext",
          "web/templates/terminal/history.html": 'data-hx-export="jsonl"',
          "web/templates/terminal/search.html": "/export/search.jsonl",
          "web/templates/terminal/form.html": "/export/search.jsonl",
          "web/static/js/exposure-explorer.js": 'format: "jsonl"',
          "web/templates/terminal/exposure.html": "data-xx-jsonl",
          "web/static/js/var-explorer.js": '"/jsonl"',
          "web/templates/terminal/var.html": "data-vx-jsonl-path",
          "web/templates/subscribe.html": 'value="jsonl"',
          "web/templates/reports.html": 'value="jsonl"'}

    def test_every_ui_export_offers_jsonl(self):
        for rel, proof in self.UI.items():
            with self.subTest(rel):
                self.assertIn(proof, text(CONSOLE / rel))


class CommandLine(unittest.TestCase):
    def test_every_cli_export_offers_jsonl(self):
        tools = ROOT / "tools"
        self.assertIn('p.add_argument("--jsonl"', text(tools / "historycmd.py"))
        self.assertIn('pf.add_argument("--jsonl"', text(tools / "expcli.py"))
        self.assertIn('choices=("csv", "json", "jsonl", "arrow", "parquet")', text(tools / "biruns.py"))
        self.assertIn('choices=("csv", "xlsx", "jsonl")', text(tools / "biruns.py"))
        self.assertIn('choices=["table", "json", "csv", "jsonl"]', text(tools / "bilang.py"))
        self.assertIn('choices=("pdf", "xlsx", "jsonl")', text(tools / "bistatement.py"))


if __name__ == "__main__":
    unittest.main()
