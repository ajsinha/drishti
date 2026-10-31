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

"""Chart any table, the console's server side (docs/guides/RACHANA_REFERENCE.md › Chart any table: the design, sections 8.4, 10, 13, 14): the raw values a search
page and a history page hand the chart (masks kept, numbers as numbers, named as the table heads them), the Chart button and its key
on those pages, the help address of the dialog's ?, the lazy loading from tables.js, the BI table's K beside the kiosk's, and the
palette's contrast in every theme."""
import json
import re
from html import unescape

from conftest import CONSOLE
from core.history_rows import MASK, build_page, chart_data
from routes.terminal_routes import search_chart_rows
from table_chart_backend import SEARCH_ROWS, ChartBackend
from test_contrast import THEMES, _ratio, _theme

JS = CONSOLE / "web" / "static" / "js"
SEARCH = {"kind": "trade", "mnemonic": "TRD", "columns": ["desk", "notional", "mtm", "legs"], "matched": 120,
          "labels": {"desk": "Desk", "notional": "Notional", "mtm": "MTM", "legs": "Legs"},
          "rows": [{"ref": {"kind": "trade", "id": "T-1"}, "title": "Swap one", "values": {"desk": "FX", "notional": 2_500_000, "mtm": MASK, "legs": [1, 2]}},
                   {"ref": {"kind": "trade", "id": "T-2"}, "title": "T-2", "values": {"desk": "Rates", "notional": None, "mtm": MASK, "legs": []}}],
          "pivot": {"fields": [{"name": "desk"}, {"name": "notional"}]}}


def test_search_rows_are_the_raw_values_named_as_the_table_heads_them_with_the_masks_kept():
    out = search_chart_rows(SEARCH, "TRD where notional > 0", titled=True)
    assert [f["name"] for f in out["fields"]] == ["TRD", "Title", "Desk", "Notional", "MTM", "Legs"]
    assert out["fields"][0]["id"] is True and out["fields"][2]["path"] == "desk"
    assert out["rows"][0] == ["T-1", "Swap one", "FX", 2_500_000, MASK, "[1, 2]"]          # a number stays a number, a list its text
    assert out["rows"][1][3] is None and out["rows"][1][4] == MASK
    assert out["matched"] == 120 and out["pivot"] == ["desk", "notional"] and out["q"] == "TRD where notional > 0"
    assert [f["name"] for f in search_chart_rows(SEARCH, "q", titled=False)["fields"]][:2] == ["TRD", "Desk"]


def test_two_columns_with_one_label_get_distinct_names():
    data = dict(SEARCH, columns=["a", "b"], labels={"a": "Amount", "b": "Amount"}, rows=[])
    names = [f["name"] for f in search_chart_rows(data, "q", titled=False)["fields"]]
    assert len(set(names)) == len(names) == 3


def test_the_search_page_hands_its_rows_to_the_chart_and_numbers_each_row(client, backend, monkeypatch):
    chart = ChartBackend()
    monkeypatch.setattr(backend, "search", chart.search, raising=False)
    monkeypatch.setattr(backend, "pivots", chart.pivots, raising=False)
    page = client.get("/s", params={"q": "TRD where notional > 0"}).text
    m = re.search(r'data-chart-rows="([^"]+)"', page)
    assert m, "no chart rows on the search page"
    data = json.loads(unescape(m.group(1)))
    assert len(data["rows"]) == len(SEARCH_ROWS) and data["kind"] == "trade" and data["matched"] == 500
    mtm = [f["name"] for f in data["fields"]].index("MTM")
    assert {r[mtm] for r in data["rows"]} == {MASK}                                  # masked on the server, masked here
    assert [int(i) for i in re.findall(r'<tr data-i="(\d+)"', page)] == list(range(len(SEARCH_ROWS)))


def test_a_compared_search_has_no_chart_rows(client, backend, monkeypatch):
    chart = ChartBackend()
    monkeypatch.setattr(backend, "search", chart.search, raising=False)
    page = client.get("/s", params={"q": "TRD where mtm > 0", "vs": "2026-09-29"}).text
    assert "data-chart-rows" not in page


def test_history_rows_for_the_chart_keep_numbers_and_mask_the_masked_columns():
    data = {"shape": "document", "columns": [{"path": "MTM", "label": "MTM", "type": "number", "format": "signed0"},
                                             {"path": "LIMIT", "label": "Limit", "type": "number", "masked": True},
                                             {"path": "TAGS", "label": "Tags"}],
            "rows": [{"date": "2026-09-29", "values": {"MTM": 110.5, "LIMIT": None, "TAGS": ["a"]}, "display": {"TAGS": "a"}},
                     {"date": "2026-09-30", "values": {"MTM": None, "LIMIT": None, "TAGS": "b"}}]}
    page = build_page(data, "trade", "T-1")
    out = chart_data(page, "trade", "T-1")
    assert [f["name"] for f in out["fields"]] == ["DATE", "MTM", "LIMIT", "TAGS"]
    assert out["fields"][2]["masked"] is True and out["fields"][1]["fmt"] == "signed0"
    assert out["rows"] == [["2026-09-29", 110.5, MASK, "a"], ["2026-09-30", None, MASK, "b"]]       # oldest first
    assert out["shape"] == "document" and out["id"] == "T-1"


def test_the_history_page_offers_the_chart_on_shift_k_in_its_bar_key_line_and_key_card(client):
    page = client.get("/v/trade/1000015/history", params={"days": "30"}).text
    btn = re.search(r'<button[^>]*data-hx-chart-open[^>]*>', page).group(0)
    assert 'aria-keyshortcuts="Shift+K"' in btn and "data-hx-chart-rows=" in btn
    data = json.loads(unescape(re.search(r'data-hx-chart-rows="([^"]+)"', btn).group(1)))
    assert data["fields"][0]["name"] == "DATE" and data["rows"] and data["kind"] == "trade"
    assert "<kbd>Shift</kbd>+<kbd>K</kbd> chart" in page
    assert "chart these days" in page
    diff = client.get("/v/trade/1000015/history", params={"days": "30", "diff": "1"}).text
    assert "data-hx-chart-open" not in diff                                          # the diff log is not a table


def test_the_dialogs_help_opens_the_user_guides_section(client):
    r = client.get("/help/table-chart", follow_redirects=False)
    assert r.status_code == 301 and r.headers["location"] == "/help/user-guide#chart-any-table"
    assert 'id="chart-any-table"' in client.get("/help/user-guide").text


def test_tables_load_the_chart_on_first_use_and_k_is_bound_on_the_table_only():
    tables = (JS / "tables.js").read_text()
    for src in ("vendor/echarts/echarts.min.js", "js/pivot-engine.js", "js/table-chart-engine.js", "js/table-chart-sources.js", "js/table-chart.js"):
        assert f"'{src}'" in tables, src
    assert "css/table-chart.css" in tables and "data-no-chart" in tables
    assert re.search(r"case 'k': case 'K': if \(chartable && !e\.altKey && !e\.ctrlKey && !e\.metaKey\)", tables)
    base = (CONSOLE / "web" / "templates" / "base.html").read_text()
    assert "table-chart" not in base                                                  # nothing loads until a chart is opened


def test_a_bi_table_takes_k_before_the_kiosk_and_the_kiosk_respects_it():
    report = (JS / "bi-report.js").read_text()
    board = (JS / "bi-report-board.js").read_text()
    assert "(e.key === 'k' || e.key === 'K') && chartable(spec)) { e.preventDefault();" in report
    assert "e.defaultPrevented) { return; }" in board
    assert "'data-act': 'chart'" in report


def test_charts_js_exports_the_theme_helpers_the_dialog_uses():
    charts = (JS / "charts.js").read_text()
    assert charts.count("tokens: tokens, palette: palette, compact: compact, axis: axis") == 2


def test_the_chart_palette_and_others_stand_out_on_the_dialog_in_every_theme():
    """Every series colour (the eight palette tokens) and Others (faint) is a graphical object at 3:1 on the dialog (surface) and on
    the settings column (bg-2), WCAG 1.4.11; the axis labels (muted) are text at 4.5:1; the dialog drops a repeated tone (Crimson gives
    link and accent one colour), and at least six distinct series colours remain in every theme."""
    for name in THEMES:
        t = _theme(name)
        for tok in ("link", "accent", "ok", "neg", "warn", "bad", "muted", "ink", "faint"):
            for bg in ("surface", "bg-2"):
                assert _ratio(t[tok], t[bg]) >= 3.0, (name, tok, round(_ratio(t[tok], t[bg]), 2))
        assert _ratio(t["muted"], t["surface"]) >= 4.5, name
        assert len({t[k].lower() for k in ("link", "accent", "ok", "neg", "warn", "bad", "muted", "ink")}) >= 6, name
