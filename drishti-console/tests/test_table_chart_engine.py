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

"""Chart any table, the engine (docs/architecture/TABLE_CHARTS.md section 19.1): table-chart-engine.js under Node, as
test_pivot.py runs pivot-engine.js. Classify, defaults, normalise, the mapping onto the pivot engine, aggregation, Top N
and Others, buckets and gaps, the pie refusal, masks, the summary, the export grid and the ECharts option (two axes,
stacking, 100%, combo, and no literal colour)."""
from __future__ import annotations

import json
import shutil
import subprocess
from pathlib import Path

import pytest

ENGINE = Path(__file__).resolve().parent.parent / "web" / "static" / "js" / "table-chart-engine.js"
pytestmark = pytest.mark.skipif(shutil.which("node") is None, reason="needs Node.js to run the chart engine")

HARNESS = """
const E = require(process.argv[1]);
const input = JSON.parse(process.argv[2]);
const out = (function (E, input) { %s })(E, input);
process.stdout.write(JSON.stringify(out === undefined ? null : out));
"""

TOKENS = {"link": "#1f77b4", "accent": "#ff7f0e", "ok": "#2ca02c", "neg": "#d62728", "warn": "#9467bd", "bad": "#8c564b",
          "muted": "#7f7f7f", "ink": "#111111", "faint": "#cccccc", "border": "#dddddd", "surface": "#fafafa", "mono": "monospace"}


def js(body: str, data=None):
    run = subprocess.run(["node", "-e", HARNESS % body, str(ENGINE), json.dumps(data)], capture_output=True, text=True, timeout=60)
    assert run.returncode == 0, run.stderr
    return json.loads(run.stdout)


def records(fields, rows):
    return {"fields": [f if isinstance(f, dict) else {"name": f} for f in fields], "rows": rows}


CASH = records(
    [{"name": "PAY_DATE", "fmt": "date"}, {"name": "AMOUNT", "fmt": "signed0"}, {"name": "CURRENCY"}, {"name": "PV", "fmt": "amount0"},
     {"name": "TRADE_ID", "id": True, "kind": "trade"}],
    [["2026-11-02", 100, "USD", 90, "T1"], ["2026-11-02", 50, "EUR", 45, "T2"], ["2026-11-03", -30, "USD", -28, "T3"],
     ["2026-12-01", 200, "EUR", 180, "T4"], ["2027-01-04", 10, "USD", 9, "T5"]])

BUILD = """
const cols = E.classify(input.r.fields, input.r);
const n = E.normalise(input.s, cols);
const m = E.build(input.r, cols, n.settings, input.o || {});
"""


def build(r, settings, opts=None, then="return m;"):
    return js(BUILD + then, {"r": r, "s": settings, "o": opts or {}})


# ---- classify ---------------------------------------------------------------------------------------------------------

def test_classify_reads_dates_numbers_and_text_by_the_ninety_percent_rule():
    nine = ["1", "2", "3", "4", "5", "6", "7", "8", "9"]
    r = records(["D", "DT", "N", "NT", "M89", "M91", "BLANK", "T"], [
        ["2026-10-01", "2026-10-01T09:30:00Z", 1.5, "1,500.25", None, None, None, "x"],
    ] * 1)
    out = js("const c = E.classify(input.fields, input); return c.map(x => [x.name, x.role]);", r)
    assert dict(out) == {"D": "date", "DT": "date", "N": "number", "NT": "number", "M89": "text", "M91": "text", "BLANK": "text", "T": "text"}
    # 89% numeric stays text; 91% is a number with the misfits counted
    vals89 = [[v] for v in (nine * 9)[:89]] + [["x"]] * 11
    vals91 = [[v] for v in (nine * 11)[:91]] + [["x"]] * 9
    assert js("return E.classify(input.fields, input)[0].role;", records(["A"], vals89)) == "text"
    c91 = js("return E.classify(input.fields, input)[0];", records(["A"], vals91))
    assert c91["role"] == "number" and c91["misfit"] == 9


def test_declared_types_win_and_masked_columns_are_not_chartable():
    r = records([{"name": "A", "type": "text"}, {"name": "B", "fmt": "pct2"}, {"name": "C", "masked": True}, "D", "E"],
                [["1", "x", 5, "•••", 1], ["2", "y", 6, "•••", "•••"]])
    c = {x["name"]: x for x in js("return E.classify(input.fields, input);", r)}
    assert c["A"]["role"] == "text" and c["B"]["role"] == "number"
    assert not c["C"]["chartable"] and c["C"]["reason"] == "hidden for your role"
    assert not c["D"]["chartable"]                         # every non-blank cell masked
    assert c["E"]["chartable"] and c["E"]["maskedCells"] == 1


def test_nothing_chartable_disables_the_icon_with_the_reason():
    every = records([{"name": "A", "masked": True}], [["•••"]])
    assert js("return E.chartable(E.classify(input.fields, input));", every) == {"ok": False, "reason": "Nothing to chart: every column is hidden for your role"}
    ids = records([{"name": "T", "id": True}], [["T1"]])
    assert js("return E.chartable(E.classify(input.fields, input)).reason;", ids) == "Nothing to chart: only identifiers"


# ---- defaults ---------------------------------------------------------------------------------------------------------

def defaults(r, saved=None):
    return js("return E.defaults(E.classify(input.r.fields, input.r), {saved: input.saved});", {"r": r, "saved": saved})


def test_defaults_date_and_number_make_a_line_by_date_and_ids_are_never_y():
    d = defaults(CASH)["settings"]
    assert d["type"] == "line" and d["x"]["field"] == "PAY_DATE" and d["series"][0]["field"] == "AMOUNT" and d["series"][0]["agg"] == "sum"
    assert d["sort"] == {"by": "x", "dir": "asc", "series": 0}
    r = records(["CCY", {"name": "TRADE_ID", "id": True, "type": "number"}, {"name": "RATE", "fmt": "rate4"}], [["USD", 1, 0.1], ["EUR", 2, 0.2]])
    d = defaults(r)["settings"]
    assert d["type"] == "bar" and d["x"]["field"] == "CCY" and d["series"][0] == {**d["series"][0], "field": "RATE", "agg": "avg"}
    assert d["sort"]["by"] == "value" and d["sort"]["dir"] == "desc"


def test_defaults_two_numbers_make_a_scatter_and_no_number_counts_rows():
    d = defaults(records(["X", "Y"], [[1, 2], [3, 4]]))["settings"]
    assert d["type"] == "scatter" and d["series"][0]["agg"] == "none"
    d = defaults(records(["DESK", "BOOK"], [["A", "b1"], ["B", "b2"]]))["settings"]
    assert d["type"] == "bar" and d["series"] == [{"field": None, "agg": "count", "axis": "left", "type": "bar", "label": None, "abs": False}]


def test_defaults_never_choose_a_masked_column_and_many_categories_start_at_top_20():
    r = records([{"name": "MTM", "masked": True, "fmt": "amount0"}, "DESK", "PV"], [["•••", "D%d" % i, i] for i in range(40)])
    out = defaults(r)
    assert out["settings"]["series"][0]["field"] == "PV" and out["settings"]["x"]["field"] == "DESK"
    assert out["settings"]["top"] == {"n": 20, "others": True} and "top 20" in out["notes"][0]


def test_a_saved_document_wins_unless_it_names_a_removed_or_masked_field():
    good = {"v": 1, "type": "bar", "x": {"field": "CURRENCY"}, "series": [{"field": "PV", "agg": "sum"}]}
    out = defaults(CASH, good)
    assert out["saved"] and out["settings"]["x"]["field"] == "CURRENCY"
    gone = {"v": 1, "type": "bar", "x": {"field": "CURRENCY"}, "series": [{"field": "NPV", "agg": "sum"}]}
    out = defaults(CASH, gone)
    assert not out["saved"] and out["settings"]["x"]["field"] == "PAY_DATE"
    assert out["notes"] == ["Your saved chart names NPV, which this table no longer has; using the defaults"]
    masked = records([{"name": "CCY"}, {"name": "MTM", "masked": True}, "PV"], [["USD", "•••", 1]])
    out = defaults(masked, {"v": 1, "x": "CCY", "series": [{"field": "MTM"}]})
    assert not out["saved"] and "MTM is hidden for your role" in out["notes"][0]


# ---- normalise --------------------------------------------------------------------------------------------------------

def test_normalise_drops_unknown_keys_cuts_series_replaces_bad_enums_and_checks_v():
    cols = "const cols = E.classify(input.r.fields, input.r);"
    s = {"v": 1, "type": "spline", "x": {"field": "PAY_DATE", "bucket": "fortnight"}, "evil": "<script>",
         "series": [{"field": "AMOUNT", "agg": "median", "axis": "up"}] * 7, "bars": "piled", "top": {"n": -5}}
    out = js(cols + "return E.normalise(input.s, cols);", {"r": CASH, "s": s})
    st = out["settings"]
    assert "evil" not in st and st["type"] == "bar" and st["x"]["bucket"] == "auto" and len(st["series"]) == 6
    assert st["series"][0]["agg"] == "sum" and st["series"][0]["axis"] == "left" and st["bars"] == "grouped" and st["top"]["n"] == 0
    assert out["problems"] == [] and "type" in out["fixes"] and "series" in out["fixes"]
    bad = js(cols + "return E.normalise({v: 2, x: 'PAY_DATE', series: ['AMOUNT']}, cols).problems;", {"r": CASH})
    assert bad[0]["why"] == "version"
    pie = js(cols + "return E.normalise({v: 1, type: 'pie', x: 'CURRENCY', split: 'TRADE_ID', series: [{field: 'AMOUNT', agg: 'max'}, 'PV']}, cols).settings;", {"r": CASH})
    assert len(pie["series"]) == 1 and pie["split"] is None and pie["series"][0]["agg"] == "sum"


# ---- the mapping and the aggregation ----------------------------------------------------------------------------------

def test_x_maps_to_rows_split_to_columns_and_series_to_values_with_avg_asking_sum_and_count():
    s = {"v": 1, "type": "bar", "x": {"field": "CURRENCY"}, "split": "PAY_DATE",
         "series": [{"field": "AMOUNT", "agg": "sum"}, {"field": "PV", "agg": "avg"}, {"agg": "count"}]}
    a = js("const cols = E.classify(input.r.fields, input.r); return E.toArrangement(E.normalise(input.s, cols).settings).arrangement;", {"r": CASH, "s": s})
    assert a["rows"] == ["CURRENCY"] and a["columns"] == ["PAY_DATE"]
    assert a["values"] == [{"field": "AMOUNT", "agg": "sum"}, {"field": "PV", "agg": "avg"}, {"field": "PV", "agg": "sum"},
                           {"field": "PV", "agg": "count"}, {"field": "·rows", "agg": "count"}]


@pytest.mark.parametrize("agg,expected", [("sum", [120, 200, 110]), ("avg", [40, 200, 55]), ("count", [3, 1, 2]),
                                          ("min", [-30, 200, 10]), ("max", [100, 200, 100])])
def test_aggregation_per_x_with_repeated_x_and_blanks(agg, expected):
    """A blank Y is left out of sum, avg, min and max; count of a field counts its non-blank values (pivot-engine.js Acc)."""
    r = records([{"name": "M", "type": "text"}, "AMOUNT"], [["a", 100], ["a", 50], ["a", -30], ["b", 200], ["c", 10], ["c", None], ["c", 100]])
    m = build(r, {"v": 1, "type": "bar", "x": "M", "series": [{"field": "AMOUNT", "agg": agg}], "sort": {"by": "x"}})
    assert m["x"] == ["a", "b", "c"] and m["series"][0]["data"] == expected


def test_split_makes_one_series_per_value_and_caps_at_twelve_with_others():
    m = build(CASH, {"v": 1, "type": "bar", "x": "CURRENCY", "split": "PAY_DATE", "series": ["AMOUNT"], "sort": {"by": "x"}})
    assert [d["name"] for d in m["series"]] == ["2026-11-02", "2026-11-03", "2026-12-01", "2027-01-04"]
    rows = [["S%02d" % i, "X", i + 1] for i in range(15)]
    m = build(records(["S", "X", "V"], rows), {"v": 1, "type": "bar", "x": "X", "split": "S", "series": ["V"]})
    names = [d["name"] for d in m["series"]]
    assert len(names) == 12 and names[-1] == "Others" and "S00" not in names and "S14" in names
    assert m["series"][-1]["data"] == [1 + 2 + 3 + 4]          # the four smallest merged
    assert m["series"][-1]["others"] is True


def test_top_n_with_others_additive_and_avg_from_sum_and_count_and_ties_by_x_order():
    r = records(["K", "V"], [["a", 10], ["b", 30], ["c", 30], ["d", 5], ["d", 1], ["e", 2], ["e", 4]])
    m = build(r, {"v": 1, "type": "bar", "x": "K", "series": ["V"], "top": {"n": 2}, "sort": {"by": "value", "dir": "desc"}})
    assert m["x"] == ["b", "c", "Others"] and m["series"][0]["data"] == [30, 30, 10 + 6 + 6]
    m = build(r, {"v": 1, "type": "bar", "x": "K", "series": [{"field": "V", "agg": "avg"}], "top": {"n": 2}, "sort": {"by": "value"}})
    assert m["x"][-1] == "Others" and m["series"][0]["data"][-1] == pytest.approx((10 + 5 + 1 + 2 + 4) / 5)
    m = build(r, {"v": 1, "type": "bar", "x": "K", "series": ["V"], "top": {"n": 2, "others": False}})
    assert "Others" not in m["x"] and any("left out" in n for n in m["notes"])


def test_a_pie_is_capped_at_twelve_slices():
    r = records(["K", "V"], [["k%02d" % i, i + 1] for i in range(20)])
    m = build(r, {"v": 1, "type": "pie", "x": "K", "series": ["V"]})
    assert len(m["x"]) == 12 and m["x"][-1] == "Others" and m["series"][0]["data"][-1] == sum(range(1, 10))


def test_a_pie_refuses_negative_values_accepts_abs_and_leaves_zeros_out():
    r = records(["K", "AMOUNT"], [["a", 5], ["b", -3], ["c", 0], ["d", 2]])
    m = build(r, {"v": 1, "type": "pie", "x": "K", "series": ["AMOUNT"]})
    assert m["refusal"]["code"] == "pie-negative" and m["refusal"]["actions"] == ["bar", "abs"]
    assert "AMOUNT has negative values (1 of 4 slices)" in m["refusal"]["message"]
    m = build(r, {"v": 1, "type": "pie", "x": "K", "series": [{"field": "AMOUNT", "abs": True}]})
    assert m["refusal"] is None and "c" not in m["x"] and sorted(m["series"][0]["data"]) == [2, 3, 5]
    assert m["series"][0]["label"] == "AMOUNT (sum) absolute" and any("zero slice" in n for n in m["notes"])


# ---- dates ------------------------------------------------------------------------------------------------------------

def test_buckets_day_week_month_quarter_year():
    out = js("return ['day','week','month','quarter','year'].map(b => E.bucketOf('2027-02-18', b));")
    assert out == ["2027-02-18", "2027-02-15", "2027-02", "2027-Q1", "2027"]


def test_auto_keeps_days_up_to_500_points_then_takes_the_smallest_period_that_fits():
    days = js("const d = []; for (let i = 0; i < 400; i++) d.push(new Date(Date.UTC(2026, 0, 1) + i * 864e5).toISOString().slice(0, 10)); return d;")
    assert js("return E.autoBucket(input, 500);", days) == "day"
    long = js("const d = []; for (let i = 0; i < 800; i++) d.push(new Date(Date.UTC(2024, 0, 1) + i * 864e5).toISOString().slice(0, 10)); return d;")
    assert js("return E.autoBucket(input, 500);", long) == "week"
    m = build(records([{"name": "D", "fmt": "date"}, "V"], [[d, 1] for d in long]), {"v": 1, "type": "line", "x": "D", "series": ["V"]})
    assert m["bucket"] == "week" and m["axis"] == "time"
    m = build(CASH, {"v": 1, "type": "line", "x": {"field": "PAY_DATE", "bucket": "month"}, "series": ["AMOUNT"]})
    assert m["x"] == ["2026-11", "2026-12", "2027-01"] and m["series"][0]["data"] == [120, 200, 10] and m["axis"] == "category"


def test_gaps_break_against_a_monday_to_friday_calendar_and_connect_by_default():
    r = records([{"name": "D", "fmt": "date"}, "V"], [["2026-10-14", 1], ["2026-10-16", 2], ["2026-10-19", 3]])   # Wed, Fri, Mon
    m = build(r, {"v": 1, "type": "line", "x": {"field": "D", "gaps": "break"}, "series": ["V"]})
    assert m["x"] == ["2026-10-14", "2026-10-15", "2026-10-16", "2026-10-19"] and m["series"][0]["data"] == [1, None, 2, 3]
    m = build(r, {"v": 1, "type": "line", "x": {"field": "D", "gaps": "break"}, "series": ["V"]}, {"holidays": ["2026-10-15"]})
    assert m["x"] == ["2026-10-14", "2026-10-16", "2026-10-19"]
    m = build(r, {"v": 1, "type": "line", "x": "D", "series": ["V"]})
    assert m["x"] == ["2026-10-14", "2026-10-16", "2026-10-19"]
    opt = build(r, {"v": 1, "type": "line", "x": "D", "series": ["V"]}, then="return E.option(m, n.settings, input.t);".replace("input.t", json.dumps(TOKENS)))
    assert opt["series"][0]["connectNulls"] is True and opt["xAxis"]["type"] == "time" and opt["series"][0]["data"][0] == ["2026-10-14", 1]


def test_a_time_axis_leaves_blank_dates_out_and_says_so_and_date_times_bucket_by_the_local_day():
    r = records([{"name": "PAY_DATE", "fmt": "date"}, "V"], [["2026-10-14", 1], [None, 2], ["", 3], [None, 4], ["2026-10-15T23:30:00Z", 5]])
    m = build(r, {"v": 1, "type": "line", "x": "PAY_DATE", "series": ["V"]}, {"offsetMinutes": 60})
    assert "3 rows without a PAY_DATE left out" in m["notes"] and m["x"] == ["2026-10-14", "2026-10-16"]


# ---- masks and mixed types --------------------------------------------------------------------------------------------

def test_masked_cells_are_left_out_of_every_aggregate_and_counted():
    M = "•••"
    r = records(["DESK", "MTM"], [["A", 10], ["A", M], ["B", M], ["B", 5], [M, 100]])
    m = build(r, {"v": 1, "type": "bar", "x": "DESK", "series": ["MTM"], "sort": {"by": "x"}})
    assert m["x"] == ["A", "B"] and m["series"][0]["data"] == [10, 5]
    assert "3 values hidden for your role were left out" in m["notes"]
    m = build(r, {"v": 1, "type": "bar", "x": "DESK", "series": [{"agg": "count"}], "sort": {"by": "x"}})
    assert m["series"][0]["data"] == [2, 2]                      # a count is of the visible rows
    grid = build(r, {"v": 1, "type": "bar", "x": "DESK", "series": ["MTM"], "top": {"n": 1}},
                 then="return E.exportGrid(m, n.settings, 'desk');")
    assert M not in json.dumps(grid) and grid["rows"] == [["A", 10], ["Others", 5]]


def test_a_masked_column_cannot_be_x_y_or_split():
    r = records(["DESK", {"name": "MTM", "masked": True}, "PV"], [["A", "•••", 1]])
    out = js("const c = E.classify(input.fields, input); return E.normalise({v: 1, x: 'MTM', split: 'MTM', series: ['MTM', 'PV']}, c);", r)
    assert out["settings"]["x"]["field"] is None and out["settings"]["split"] is None
    assert [s["field"] for s in out["settings"]["series"]] == ["PV"] and {p["why"] for p in out["problems"]} == {"masked"}


def test_mixed_values_that_do_not_fit_are_left_out_and_counted():
    r = records(["K", "AMOUNT"], [["a", 1]] * 18 + [["a", "n/a"], ["b", "tbd"]])
    m = build(r, {"v": 1, "type": "bar", "x": "K", "series": ["AMOUNT"]})
    assert "2 non-numeric AMOUNT values left out" in m["notes"] and m["series"][0]["data"][0] == 18


def test_a_single_row_and_no_rows():
    one = build(records(["K", "V"], [["a", 4]]), {"v": 1, "type": "line", "x": "K", "series": ["V"]},
                then="const o = E.option(m, n.settings, %s); return [m.single, E.summary(m, n.settings), o.series[0].showSymbol];" % json.dumps(TOKENS))
    assert one[0] is True and one[1].endswith("one row.") and one[2] is True
    none = build(records(["K", "V"], []), {"v": 1, "type": "bar", "x": "K", "series": ["V"]},
                 then="return [m.empty, E.summary(m, n.settings, {filters: 'STATUS = Live'})];")
    assert none == [True, "No rows to chart (the table's filters leave none)."]


# ---- words ------------------------------------------------------------------------------------------------------------

def test_summary_of_a_bar_and_of_a_time_line_with_its_trend():
    r = records(["CURRENCY", "AMOUNT"], [["USD", 4100000], ["EUR", 1000], ["NOK", -300000]])
    text = build(r, {"v": 1, "type": "bar", "x": "CURRENCY", "series": ["AMOUNT"], "sort": {"by": "value", "dir": "desc"}},
                 then="return E.summary(m, n.settings, {shown: 25, total: 1340});")
    assert text == "Bar chart of AMOUNT (sum) by CURRENCY, 3 categories, highest USD 4.1m, lowest NOK −300k; 25 of 1,340 rows."
    up = records([{"name": "D", "fmt": "date"}, "V"], [["2026-10-%02d" % d, d * 10] for d in range(1, 11)])
    text = build(up, {"v": 1, "type": "line", "x": "D", "series": ["V"]}, then="return E.summary(m, n.settings);")
    assert "10 days" in text and "from 2026-10-01 to 2026-10-10" in text and "trend up" in text
    flat = js("return [E.trend([5, 5.01, 4.99, 5]), E.trend([3, 2, 1])];")
    assert flat == ["flat", "down"]


def test_the_export_grid_is_the_drawn_data():
    g = build(CASH, {"v": 1, "type": "bar", "x": "CURRENCY", "series": ["AMOUNT", {"field": "PV", "axis": "right"}], "sort": {"by": "x"}},
              then="return E.exportGrid(m, n.settings, 'cash');")
    assert g == {"name": "cash", "header": ["CURRENCY", "AMOUNT (sum)", "PV (sum)"], "rows": [["EUR", 250, 225], ["USD", 80, 71]]}


# ---- the option -------------------------------------------------------------------------------------------------------

def colours(node, out):
    if isinstance(node, dict):
        for k, v in node.items():
            if isinstance(v, str) and "color" in k.lower():
                out.append(v)
            colours(v, out)
    elif isinstance(node, list):
        for v in node:
            colours(v, out)
    return out


def option(r, settings, opts=None):
    return build(r, settings, opts, then="return E.option(m, n.settings, %s);" % json.dumps(TOKENS))


def test_two_value_axes_when_a_series_is_on_the_right():
    o = option(CASH, {"v": 1, "type": "combo", "x": "CURRENCY", "series": [{"field": "AMOUNT", "type": "bar"}, {"field": "PV", "type": "line", "axis": "right"}]})
    assert len(o["yAxis"]) == 2 and o["yAxis"][1]["position"] == "right"
    assert [(s["type"], s["yAxisIndex"]) for s in o["series"]] == [("bar", 0), ("line", 1)]
    assert o["animation"] is False


def test_stacked_and_100_percent_bars_and_a_percent_column_refuses_100():
    s = {"v": 1, "type": "bar", "x": "CURRENCY", "split": "PAY_DATE", "series": ["PV"], "bars": "stacked", "percent": True}
    m = build(CASH, s)
    for j in range(len(m["x"])):
        assert sum(abs(d["data"][j] or 0) for d in m["series"]) == pytest.approx(1)
    o = option(CASH, s)
    assert {x["stack"] for x in o["series"]} == {"bar-left"} and o["yAxis"][0]["max"] == 1
    pct = records(["K", "S", {"name": "R", "fmt": "pct2"}], [["a", "x", 0.1], ["a", "y", 0.2]])
    m = build(pct, {"v": 1, "type": "bar", "x": "K", "split": "S", "series": ["R"], "bars": "stacked", "percent": True})
    assert any("already percentages" in n for n in m["notes"]) and m["series"][0]["data"] == [pytest.approx(0.1)]


def test_area_line_pie_donut_and_scatter_options():
    o = option(CASH, {"v": 1, "type": "area", "x": "PAY_DATE", "split": "CURRENCY", "series": ["AMOUNT"]})
    assert all(s["type"] == "line" and "areaStyle" in s and s["stack"] == "area-left" for s in o["series"])
    o = option(CASH, {"v": 1, "type": "pie", "x": "CURRENCY", "series": ["PV"], "donut": True})
    assert o["series"][0]["type"] == "pie" and o["series"][0]["radius"] == ["45%", "70%"]
    o = option(records(["X", "Y", "Z", "G"], [[1, 2, 5, "a"], [3, 4, 9, "b"], [2, 1, 1, "a"]]),
               {"v": 1, "type": "scatter", "x": "X", "split": "G", "series": [{"field": "Y", "agg": "none", "size": "Z"}]})
    assert o["xAxis"]["type"] == "value" and [s["name"] for s in o["series"]] == ["a", "b"] and o["series"][0]["data"] == [[1, 2, 5], [2, 1, 1]]


def test_scatter_points_are_capped_with_a_note():
    r = records(["X", "Y"], [[i, i] for i in range(30)])
    m = build(r, {"v": 1, "type": "scatter", "x": "X", "series": [{"field": "Y", "agg": "none"}]}, {"limits": {"scatterPoints": 10}})
    assert len(m["series"][0]["data"]) == 10 and m["notes"][0] == "30 points: the first 10 by X"


def test_no_literal_colour_in_any_option_every_colour_is_a_token():
    allowed = set(TOKENS.values())
    many = records(["K", "S", "V"], [["k%d" % (i % 3), "s%02d" % i, i - 5] for i in range(11)])
    cases = [
        (CASH, {"v": 1, "type": "line", "x": "PAY_DATE", "series": ["AMOUNT", {"field": "PV", "axis": "right"}]}),
        (CASH, {"v": 1, "type": "bar", "x": "CURRENCY", "series": ["AMOUNT"], "labels": True}),
        (CASH, {"v": 1, "type": "pie", "x": "CURRENCY", "series": ["PV"], "top": {"n": 1}}),
        (many, {"v": 1, "type": "bar", "x": "K", "split": "S", "series": ["V"], "bars": "stacked"}),
        (many, {"v": 1, "type": "line", "x": "K", "split": "S", "series": ["V"]}),
        (records(["X", "Y"], [[1, 2]]), {"v": 1, "type": "scatter", "x": "X", "series": [{"field": "Y", "agg": "none"}]}),
    ]
    for r, s in cases:
        found = colours(option(r, s), [])
        assert found and set(found) <= allowed, (s, set(found) - allowed)


def test_more_series_than_the_palette_differ_by_dash_or_decal_and_others_is_faint():
    many = records(["K", "S", "V"], [["k", "s%02d" % i, i + 1] for i in range(11)])
    o = option(many, {"v": 1, "type": "line", "x": "K", "split": "S", "series": ["V"]})
    assert o["aria"]["decal"] == {"show": True}
    assert [s["lineStyle"]["type"] for s in o["series"]][8:] == ["dashed", "dashed", "dashed"]
    o = option(records(["K", "S", "V"], [["k", "s%02d" % i, i + 1] for i in range(14)]), {"v": 1, "type": "bar", "x": "K", "split": "S", "series": ["V"]})
    assert o["series"][-1]["name"] == "Others" and o["series"][-1]["itemStyle"]["color"] == TOKENS["faint"]


def test_long_text_labels_make_horizontal_bars_and_a_negative_single_bar_uses_neg():
    r = records(["NAME", "V"], [["A very long counterparty name %d" % i, i - 1] for i in range(3)])
    o = option(r, {"v": 1, "type": "bar", "x": "NAME", "series": ["V"]})
    assert o["yAxis"]["type"] == "category" and o["xAxis"]["type"] == "value"
    o = option(records(["K", "V"], [["a", -1], ["b", 2]]), {"v": 1, "type": "bar", "x": "K", "series": ["V"], "sort": {"by": "x"}})
    assert o["series"][0]["data"][0]["itemStyle"]["color"] == TOKENS["neg"] and o["series"][0]["data"][1] == 2


def test_a_server_cube_draws_like_the_browser_cube():
    """The search pivot answers the pivot engine's cube shape: drawn through fromCube it equals the browser's drawing."""
    body = BUILD + """
const prep = E.prepare(input.r, n.settings, cols, {});
const c = E.cube(prep, n.settings, {});
const server = JSON.parse(JSON.stringify(c)); delete server.slots;
const m2 = E.fromCube(server, n.settings, cols, {});
return [m.x, m.series.map(d => d.data), m2.x, m2.series.map(d => d.data)];
"""
    out = js(body, {"r": CASH, "s": {"v": 1, "type": "bar", "x": "CURRENCY", "series": ["AMOUNT", {"field": "PV", "agg": "avg"}]}})
    assert out[0] == out[2] and out[1] == out[3]
