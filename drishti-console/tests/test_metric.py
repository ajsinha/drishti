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

"""The metric panel in the console: a big toned figure with an accessible name, its change, unit and caption; the live patch
that replaces it; its CSV; and its stylesheet."""
import json
import re
from pathlib import Path

from core import export
from routes.api_routes import _view_event

CSS = (Path(__file__).resolve().parents[1] / "web" / "static" / "css" / "terminal.css").read_text(encoding="utf-8")

PANEL = {"id": "mtm", "kind": "metric", "title": "MTM", "area": "right", "empty": False, "error": None,
         "data": {"value": {"label": "Net MTM", "text": "−1,250", "tone": "neg", "emphasis": True, "path": "mtm"},
                  "delta": {"text": "+1.23%", "tone": "pos", "path": "chg"}, "unit": "USD", "caption": "vs yesterday"}}


def render(client, panel):
    tpl = client.app.state.templates.env.from_string('{% from "_macros/panels.html" import panel %}{{ panel(p) }}')
    return tpl.render(p=panel)


def test_the_tile_shows_the_figure_toned_with_its_change_unit_and_caption_in_a_named_group(client):
    html = render(client, PANEL)
    assert 'data-kind="metric"' in html and 'class="metric"' in html and 'role="group"' in html
    assert 'aria-label="MTM: −1,250 USD, change +1.23%"' in html
    assert re.search(r'class="metric-v mono t-neg"[^>]*data-path="mtm">−1,250<', html)
    assert re.search(r'class="metric-d mono t-pos"[^>]*>\+1.23%<', html)
    assert ">USD<" in html and "vs yesterday" in html and "Net MTM" in html
    assert "style=" not in html                                          # the CSP forbids inline styles
    assert 'href="/help/panel-kinds#metric"' in html and 'data-export-panel="mtm"' in html


def test_a_masked_figure_shows_the_mask_and_an_empty_one_says_so(client):
    masked = dict(PANEL, data={"value": {"text": "•••"}})
    html = render(client, masked)
    assert 'aria-label="MTM: •••"' in html and "t-neg" not in html and "t-pos" not in html
    assert "No data available" in render(client, dict(PANEL, empty=True))
    assert "no access to curve" in render(client, dict(PANEL, data=None, empty=True, denied="no access to curve")).lower()


def test_a_live_frame_replaces_the_tile_with_its_new_figure(client):
    request = type("R", (), {"app": client.app})()
    changed = json.loads(json.dumps(PANEL))
    changed["data"]["value"].update(text="+9,999", tone="pos")
    frame = json.loads(_view_event(request, "frame", json.dumps({"seq": 1, "patches": [{"op": "panel", "panel": changed}]})))
    patch = frame["patches"][0]
    assert patch["panel"] == {"id": "mtm", "kind": "metric"}
    assert 'id="p-mtm"' in patch["html"] and "+9,999" in patch["html"] and "t-pos" in patch["html"] and "−1,250" not in patch["html"]


def test_the_csv_has_the_figure_change_unit_and_caption():
    header, rows = export.panel_rows(PANEL)
    assert header == ["Label", "Value", "Change", "Unit", "Caption"]
    assert rows == [["Net MTM", -1250, 0.0123, "USD", "vs yesterday"]]
    assert export.panel_rows(dict(PANEL, empty=True)) == ([], [])


def test_the_stylesheet_styles_every_part_with_theme_colours_and_a_phone_size():
    for part in (".metric ", ".metric-row", ".metric-v", ".metric-u", ".metric-d", ".metric-l"):
        assert part in CSS, part
    block = "".join(re.findall(r"\.metric[^{}]*\{[^}]*\}", CSS))
    assert "var(--d-ink)" in block and "var(--d-muted)" in block         # theme variables only: contrast comes from the themes
    assert not re.search(r"#[0-9a-fA-F]{3,6}\b", block)
    assert re.search(r"@media \(max-width: 640px\) \{ \.metric-v", CSS)
