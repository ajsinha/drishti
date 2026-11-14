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

"""The grid exports of the pivot screen (POST /export/grid.csv and /export/grid.xlsx): the downloaded file holds the posted grid, and a body that is
not a JSON object is refused with a DRS code (never an empty file that looks like a result)."""
import csv
import io
import zipfile


def _read(body: bytes) -> list[list[str]]:
    assert body.startswith("﻿".encode("utf-8"))                   # a BOM, so spreadsheets read UTF-8
    return list(csv.reader(io.StringIO(body.decode("utf-8-sig"))))


GRID = {"name": "desk pnl", "header": ["Desk", "MTM"], "rows": [["D-1", 5.5], ["=cmd|x", -1], ["D-3", "−1,250.00"]]}


def test_grid_csv_downloads_the_posted_grid_with_formulas_kept_as_text(client):
    r = client.post("/export/grid.csv", json=GRID)
    assert r.status_code == 200 and r.headers["content-type"].startswith("text/csv")
    assert 'filename="desk_pnl' in r.headers["content-disposition"] and r.headers["content-disposition"].endswith('.csv"')
    rows = _read(r.content)
    assert rows[0] == ["Desk", "MTM"] and rows[1] == ["D-1", "5.5"]
    assert rows[2][0] == "'=cmd|x"                                   # text a spreadsheet would run as a formula stays text
    assert rows[3][0] == "D-3" and len(rows) == 4
    assert client.post("/export/grid.csv", json=GRID).content == r.content           # the same request twice gives the same file


def test_grid_csv_refuses_what_is_not_a_json_object(client):
    assert client.post("/export/grid.csv").status_code == 200                        # no body at all: an empty grid
    bad = client.post("/export/grid.csv", content=b"{not json", headers={"content-type": "application/json"})
    assert bad.status_code == 400 and "DRS-5001" in bad.text
    wrong = client.post("/export/grid.csv", content=b"[1, 2]", headers={"content-type": "application/json"})
    assert wrong.status_code == 400 and "DRS-5001" in wrong.text
    typed = client.post("/export/grid.csv", content=b"x", headers={"content-type": "text/plain"})
    assert typed.status_code == 415 and "DRS-5001" in typed.text


def test_grid_xlsx_is_a_workbook_whose_cells_are_the_posted_grid(client):
    r = client.post("/export/grid.xlsx", json={"name": "desk pnl", "header": ["Desk", "MTM"], "rows": [["D-1", 5.5], ["D-2", -1250]]})
    assert r.status_code == 200
    assert r.headers["content-type"] == "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    assert r.headers["content-disposition"].endswith('.xlsx"') and "desk_pnl" in r.headers["content-disposition"]
    with zipfile.ZipFile(io.BytesIO(r.content)) as z:
        names = z.namelist()
        assert "xl/workbook.xml" in names
        sheet = z.read("xl/worksheets/sheet1.xml").decode("utf-8")
        shared = z.read("xl/sharedStrings.xml").decode("utf-8") if "xl/sharedStrings.xml" in names else ""
    for want in ("Desk", "MTM", "D-1", "D-2"):
        assert want in sheet + shared, want
    assert "5.5" in sheet and "-1250" in sheet                       # numbers are numbers, not text


def test_grid_xlsx_refuses_what_is_not_a_json_object(client):
    bad = client.post("/export/grid.xlsx", content=b"{oops", headers={"content-type": "application/json"})
    assert bad.status_code == 400 and "DRS-5001" in bad.text
    assert client.post("/export/grid.xlsx", content=b"x", headers={"content-type": "text/plain"}).status_code == 415
