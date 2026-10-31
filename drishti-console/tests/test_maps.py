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

"""The maps in the console: the vendored shapes (a world file, a file of dots, one region file per country: valid, coded by ISO 3166-2, within the size
limits and with their README), the routes that serve them to the elements, the map panel's first paint, live patch, CSV and stylesheet, the
workbench's palette row, and the BI map's drill settings."""
import json
import re
from pathlib import Path

import yaml

from core import export
from routes.api_routes import _view_event
from test_embed import HOST, make_client

CONSOLE = Path(__file__).resolve().parents[1]
MAPS = CONSOLE / "web" / "static" / "vendor" / "echarts" / "maps"
REGIONS = MAPS / "regions"
CSS = (CONSOLE / "web" / "static" / "css" / "terminal.css").read_text(encoding="utf-8")
ISO_3166_2 = re.compile(r"^[A-Z]{2}-[A-Z0-9]{1,4}$")


def test_the_world_file_and_the_points_file_agree_and_hold_no_duplicates():
    world = json.loads((MAPS / "world-countries.json").read_text(encoding="utf-8"))
    iso3 = [f["properties"]["iso3"] for f in world["features"]]
    points = json.loads((MAPS / "world-points.json").read_text(encoding="utf-8"))
    assert len(iso3) == len(set(iso3)) >= 170
    assert len(points) >= 30 and not {p["iso3"] for p in points} & set(iso3), "a point is only for a country the shapes lack"
    for p in points:
        assert re.fullmatch(r"[A-Z]{3}", p["iso3"]) and -180 <= p["lon"] <= 180 and -90 <= p["lat"] <= 90 and p["name"], p
    assert {"CYM", "BMU", "MCO"} <= {p["iso3"] for p in points}                    # the territories the 1:110m shapes lack


def test_every_country_has_one_small_region_file_coded_by_iso_3166_2():
    files = sorted(REGIONS.glob("*.json"))
    assert len(files) >= 240
    total = sum(f.stat().st_size for f in files)
    assert total <= 15_000_000, f"{total} bytes in all (the limit is 15 MB)"
    biggest = max(files, key=lambda f: f.stat().st_size)
    assert biggest.stat().st_size <= 1_500_000, f"{biggest.name} is over 1.5 MB"
    for f in files:
        assert re.fullmatch(r"[A-Z]{3}\.json", f.name), f.name
        d = json.loads(f.read_text(encoding="utf-8"))
        assert d["type"] == "FeatureCollection" and d["properties"]["iso3"] == f.stem and len(d["properties"]["bbox"]) == 4, f.name
        codes = [x["properties"]["code"] for x in d["features"]]
        assert d["features"] and len(codes) == len(set(codes)), f.name
        for x in d["features"]:
            p = x["properties"]
            assert ISO_3166_2.match(p["code"]) and p["name"] and p["iso3"] == f.stem, (f.name, p)
            kind = x["geometry"]["type"]
            assert kind in ("Polygon", "MultiPolygon", "Point") and (kind == "Point") == bool(p.get("point")), (f.name, p["code"])


def test_australia_has_its_states_and_a_small_territory_is_a_clickable_point():
    aus = json.loads((REGIONS / "AUS.json").read_text(encoding="utf-8"))
    by = {x["properties"]["code"]: x for x in aus["features"]}
    assert {"AU-NSW", "AU-VIC", "AU-QLD", "AU-WA", "AU-SA", "AU-TAS", "AU-NT", "AU-ACT"} <= set(by)
    assert by["AU-NSW"]["geometry"]["type"] in ("Polygon", "MultiPolygon")
    assert any(x["geometry"]["type"] == "Point" for x in aus["features"]), "a region too small to draw is a dot"
    for country in ("RUS", "USA", "FJI"):                                          # across the antimeridian: one block, not a band round the world
        box = json.loads((REGIONS / f"{country}.json").read_text(encoding="utf-8"))["properties"]["bbox"]
        assert box[2] - box[0] < 200, country


def test_the_readme_records_the_source_the_licence_and_the_sizes():
    text = (REGIONS / "README.md").read_text(encoding="utf-8")
    assert "Natural Earth" in text and "Public domain" in text and "tools/maps/build_regions.py" in text and "Total size" in text
    vendor = (MAPS.parents[1] / "VENDOR.md").read_text(encoding="utf-8")
    assert "ne_10m_admin_1_states_provinces" in vendor and "Public domain" in vendor


def test_the_shapes_are_all_local_and_the_script_loads_nothing_from_outside():
    js = (CONSOLE / "web" / "static" / "js" / "geomap.js").read_text(encoding="utf-8")
    assert not re.search(r"https?://", js) and "/static/vendor/echarts/maps/" in js, "geomap.js loads local files only"
    assert not re.search(r"https?://", (MAPS / "world-points.json").read_text(encoding="utf-8"))


def test_the_elements_get_the_same_shapes_with_cors(backend):
    embed = make_client(backend)
    for path, marker in (("/embed/v1/world-countries.json", "FeatureCollection"), ("/embed/v1/world-points.json", "CYM"),
                         ("/embed/v1/regions/AUS.json", "AU-NSW")):
        r = embed.get(path, headers={"Origin": HOST})
        assert r.status_code == 200 and marker in r.text and r.headers["access-control-allow-origin"] == HOST, path
    assert embed.get("/embed/v1/regions/ZZZ.json", headers={"Origin": HOST}).status_code == 404
    for bad in ("aus", "AU", "AUSS", "..%2Fregions%2FAUS", "A%2FS"):
        assert embed.get(f"/embed/v1/regions/{bad}.json", headers={"Origin": HOST}).status_code in (404, 405, 422), bad
    assert embed.get("/embed/v1/js/geomap.js", headers={"Origin": HOST}).status_code == 200


PANEL = {"id": "geography", "kind": "map", "title": "Where it is", "area": "main", "empty": False, "error": None,
         "data": {"mode": "world", "highlight": "AUS",
                  "link": {"kind": "jurisdiction", "template": "JUR-{iso3}", "codes": ["AUS", "NZL"]},
                  "shade": {"label": "GDP per capita (USD)", "items": [{"code": "AUS", "value": 65130.0, "text": "65,130"}, {"code": "NZL", "value": 48800.0, "text": "48,800"}]}}}


def render(client, panel):
    tpl = client.app.state.templates.env.from_string('{% from "_macros/panels.html" import panel %}{{ panel(p) }}')
    return tpl.render(p=panel)


def test_first_paint_holds_the_codes_a_named_picture_the_places_list_and_the_figures_as_a_table(client):
    html = render(client, PANEL)
    assert 'data-kind="map"' in html and 'class="chart xchart xmap"' in html and 'role="img"' in html
    assert "world map, AUS highlighted, shaded by GDP per capita (USD)" in html
    data = json.loads(re.search(r'data-xchart="([^"]*)"', html).group(1).replace("&#34;", '"').replace("&quot;", '"'))
    assert data["highlight"] == "AUS" and data["link"]["codes"] == ["AUS", "NZL"]
    assert 'class="links xmap-list"' in html and "Open a country" in html                     # the way in for a keyboard, a reader and a phone
    assert "<td" in html and ">65,130<" in html and ">NZL<" in html                           # the same figures as a table
    assert "style=" not in html and 'href="/help/panels#map"' in html and 'data-export-panel="geography"' in html


def test_a_region_map_names_the_country_and_an_unreachable_one_says_no_data(client):
    html = render(client, dict(PANEL, data={"mode": "region", "region": "AUS"}))
    assert "regions of AUS" in html and "Open a region" in html
    assert "No data available" in render(client, dict(PANEL, empty=True, data={"mode": "region"}))


def test_a_live_frame_replaces_the_map_panel(client):
    request = type("R", (), {"app": client.app})()
    changed = json.loads(json.dumps(PANEL))
    changed["data"]["highlight"] = "NZL"
    frame = json.loads(_view_event(request, "frame", json.dumps({"seq": 1, "patches": [{"op": "panel", "panel": changed}]})))
    patch = frame["patches"][0]
    assert patch["panel"] == {"id": "geography", "kind": "map"} and 'id="p-geography"' in patch["html"] and "NZL highlighted" in patch["html"]


def test_the_csv_lists_each_shaded_place_with_what_is_highlighted_and_openable():
    header, rows = export.panel_rows(PANEL)
    assert header == ["Code", "GDP per capita (USD)", "Highlighted", "Opens a page"]
    assert rows == [["AUS", 65130.0, "yes", "yes"], ["NZL", 48800.0, "", "yes"]]
    only = export.panel_rows(dict(PANEL, data={"mode": "world", "highlight": "FRA", "link": {"codes": ["AUS"]}}))
    assert only[1] == [["FRA", "", "yes", ""]]
    assert export.panel_rows(dict(PANEL, empty=True)) == ([], [])


def test_the_stylesheet_sizes_the_map_with_a_phone_rule_and_theme_colours_only():
    for part in (".chart.xmap", ".xmap-list", ".xmap-note"):
        assert part in CSS, part
    block = "".join(re.findall(r"\.xmap[^{}]*\{[^}]*\}", CSS))
    assert "var(--d-muted)" in block and not re.search(r"#[0-9a-fA-F]{3,6}\b", block)
    assert re.search(r"@media \(max-width: 640px\) \{ \.chart\.xmap", CSS)


def test_the_workbench_offers_the_map_and_seeds_it_from_the_field_that_names_a_country():
    palette = (CONSOLE / "web" / "static" / "js" / "build" / "palette.js").read_text(encoding="utf-8")
    actions = (CONSOLE / "web" / "static" / "js" / "build" / "actions.js").read_text(encoding="utf-8")
    assert "['map', 'globe2'," in palette and "kind === 'map'" in actions and "out.highlight = place.path" in actions


def test_the_bi_map_has_level_and_drill_in_the_format_pane_and_the_scripts_load_geomap_first():
    # 2.4.0: the Format pane is generated from the format schemas; the map's level and drill carry the hooks the schema names
    schema = yaml.safe_load((CONSOLE.parent / "drishti-server" / "src" / "main" / "resources" / "bi" / "report" / "format" / "other-chart.yaml").read_text(encoding="utf-8"))
    props = schema["properties"]
    assert "map" in schema["x-types"]
    assert props["level"]["x-hook"] == "format-level" and props["level"]["type"] == "string" and props["level"]["x-types"] == ["map"]
    assert props["drill"]["x-hook"] == "format-drill" and props["drill"]["type"] == "boolean" and props["drill"]["x-types"] == ["map"]
    pane = (CONSOLE / "web" / "static" / "js" / "bi-format-pane.js").read_text(encoding="utf-8")
    assert "'data-' + prop['x-hook']" in pane, "the pane gives each control the data attribute its x-hook names"
    for page in ("bi/report.html", "bi/design.html"):           # a board is a report page since 2.4.0 (no bi/board.html)
        text = (CONSOLE / "web" / "templates" / page).read_text(encoding="utf-8")
        assert text.index("geomap.js") < text.index("bi-visuals.js"), page
    for page in ("terminal/view.html", "build/design.html", "build/shared.html"):
        text = (CONSOLE / "web" / "templates" / page).read_text(encoding="utf-8")
        assert text.index("geomap.js") < text.index("charts.js"), page
    embed = (CONSOLE / "web" / "embed" / "assets.js").read_text(encoding="utf-8")
    assert "'geomap', 'charts'" in embed and "['geomap'].concat(" in embed
