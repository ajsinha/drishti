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

"""The map, funnel, histogram, box plot and bubble, true totals and the refresh block (docs/guides/BI_GUIDE.md › Reports: the YAML, the visuals and how they behave, sections 3 and 8; BI_GUIDE.md › Datasets: the YAML, the expression language and the security rules, sections 4 and 7): the vendored
country data, the routes that serve it, and the browser code that does not need a browser (run in Node, skipped without it). The pictures are in test_bi_new_visuals_browser.py."""
import json
import re
import shutil
import subprocess
from pathlib import Path

import pytest

from test_embed import HOST, make_client

WEB = Path(__file__).resolve().parent.parent / "web"
STATIC = WEB / "static"
ROOT = WEB.parent.parent
WORLD = STATIC / "vendor/echarts/maps/world-countries.json"
node = shutil.which("node")
needs_node = pytest.mark.skipif(node is None, reason="needs Node to run the shared report code")


def test_the_vendored_map_holds_the_countries_with_their_codes_and_no_url():
    text = WORLD.read_text(encoding="utf-8")
    geo = json.loads(text)
    assert geo["type"] == "FeatureCollection" and len(geo["features"]) >= 170
    assert len(text) < 300_000, "countries only, simplified: a few hundred kilobytes at most"
    assert "http://" not in text and "https://" not in text
    by2 = {f["properties"]["iso2"]: f["properties"] for f in geo["features"] if f["properties"]["iso2"]}
    by3 = {f["properties"]["iso3"]: f["properties"] for f in geo["features"]}
    for code in ("US", "GB", "NO", "FR", "JP", "DE", "CH", "CA", "BR", "GR"):                 # the countries of the Counterparty risk sample (France and Norway are the ones the source leaves unnumbered)
        assert code in by2, code
    assert by3["USA"]["name"] == "United States of America" and by2["GB"]["iso3"] == "GBR"
    assert all(set(f["properties"]) == {"name", "iso2", "iso3"} and f["geometry"]["type"] in ("Polygon", "MultiPolygon") for f in geo["features"])


def test_the_licence_of_the_map_data_is_recorded():
    assert "Natural Earth" in (STATIC / "vendor/VENDOR.md").read_text(encoding="utf-8")
    assert "Natural Earth" in (Path(__file__).resolve().parents[2] / "THIRD-PARTY-NOTICES.md").read_text(encoding="utf-8")


def test_the_map_is_served_from_the_console_and_to_the_element_with_cors(backend, client):
    r = client.get("/static/vendor/echarts/maps/world-countries.json")
    assert r.status_code == 200 and r.json()["type"] == "FeatureCollection"
    embed = make_client(backend)
    e = embed.get("/embed/v1/world-countries.json", headers={"Origin": HOST})
    assert e.status_code == 200 and e.headers["access-control-allow-origin"] == HOST and "json" in e.headers["content-type"]
    assert e.json() == r.json()


def test_every_new_type_is_in_the_catalogue_of_the_designer_and_has_an_icon():
    panes = (STATIC / "js/bi-design-panes.js").read_text(encoding="utf-8")
    groups = re.search(r"\['Charts', \[([^\]]+)\]\]", panes).group(1)
    icons = re.search(r"const ICONS = \{(.*?)\};", panes, re.S).group(1)
    for t in ("bubble", "funnel", "histogram", "box", "map"):
        assert f"'{t}'" in groups, t
        assert re.search(rf"\b{t}: '", icons), t
        icon = re.search(rf"\b{t}: '([a-z0-9-]+)'", icons).group(1)
        assert f".bi-{icon}::before" in (STATIC / "vendor/bootstrap-icons/bootstrap-icons.css").read_text(encoding="utf-8"), icon
    schemas = "".join(p.read_text(encoding="utf-8") for p in (ROOT / "drishti-server/src/main/resources/bi/report/format").glob("*.yaml"))   # the Format pane is generated from them (2.4.0)
    assert "x-hook: format-bins" in schemas and "x-hook: format-totals" in schemas and "data-time" in panes


def run(script: str):
    prelude = ("global.window = { drishtiBi: { h: () => ({}), visuals: {}, fmt: (v) => String(v), compact: (v) => String(v), label: (x) => x, clear: (x) => x, column: () => null } };"
               "for (const f of ['bi-visuals.js', 'bi-grids.js']) { require(%s + '/' + f); } const V = window.drishtiBi.visuals;" % json.dumps(str(STATIC / "js")))
    out = subprocess.run([node, "-e", prelude + script], capture_output=True, text=True, timeout=30)
    assert out.returncode == 0, out.stderr
    return json.loads(out.stdout)


@needs_node
def test_every_new_type_is_drawn_as_a_chart():
    got = run("console.log(JSON.stringify(['map','funnel','histogram','box','bubble','scatter','python'].map((t) => V.isChart(t))))")
    assert got == [True, True, True, True, True, True, False]


RES = {"totals": [
    {"by": [], "columns": ["Avg", "Share"], "masked": [], "rows": [[12.5, 0.4]]},
    {"by": ["trade.desk"], "columns": ["trade.desk", "Avg", "Share"], "masked": [], "rows": [["FX", 10.0, 0.5], ["FI", 20.0, 0.3]]},
    {"by": ["trade.ccy"], "columns": ["trade.ccy", "Avg", "Share"], "masked": [], "rows": [["USD", 11.0, 0.45], [None, 13.0, 0.1]]},
    {"by": ["trade.desk", "trade.ccy"], "columns": ["trade.desk", "trade.ccy", "Avg"], "masked": ["Avg"], "rows": [["FX", "USD", 9.0]]}]}


@needs_node
def test_the_engines_totals_are_looked_up_by_the_fields_and_values_of_the_group():
    find = lambda by, values, m: run("console.log(JSON.stringify(V.trueTotals(%s).find(%s, %s, %s) ?? null))" % (json.dumps(RES), json.dumps(by), json.dumps(values), json.dumps(m)))
    assert find([], [], "Avg") == 12.5 and find([], [], "Share") == 0.4
    assert find(["trade.desk"], ["FI"], "Avg") == 20.0
    assert find(["trade.ccy"], ["USD"], "Share") == 0.45
    assert find(["trade.ccy"], ["–"], "Avg") == 13.0, "an empty value is the dash the matrix writes for it"
    assert find(["trade.desk"], ["XX"], "Avg") is None, "no such group: the cell stays a dash"
    assert find(["trade.desk", "trade.ccy"], ["FX", "USD"], "Avg") is None, "masked for the reader: no figure"
    assert find(["trade.region"], ["EMEA"], "Avg") is None, "a level the engine was not asked for"
    assert find([], [], "Nope") is None
    assert run("console.log(JSON.stringify(V.trueTotals({}).any))") is False


def test_the_new_scripts_carry_no_cdn_url_inline_code_or_markup_strings():
    for rel in ("js/bi-visuals.js", "js/bi-grids.js", "js/bi-dataset.js", "js/bi-design-panes.js"):
        text = (STATIC / rel).read_text(encoding="utf-8")
        assert "https://" not in text and "innerHTML" not in text and "eval(" not in text, rel
        assert len(text.splitlines()) < 1500, rel
    html = (WEB / "templates/bi/dataset.html").read_text(encoding="utf-8")
    assert "data-refresh-sec" in html and not re.search(r"<script(?![^>]*\bsrc=)", html) and " style=" not in html


def test_the_new_visuals_stay_inside_the_phone_rules():
    css = (STATIC / "css/bi-report.css").read_text(encoding="utf-8")
    for t in ("bubble", "funnel", "histogram", "box", "map"):
        assert f".bi-vis-{t}" in css, t
    assert "overflow-wrap: anywhere" in css                       # the note under a map wraps instead of widening the page
