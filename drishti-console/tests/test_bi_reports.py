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

"""Rupaka phase 3 (docs/architecture/BI_DESIGN.md › Rūpaka: the original design and every phase as built, sections 8 and 9), the console's routes for reports: the viewer, the designer, its start page and the Model view are
shells drawn by bi-report.js, bi-design*.js and bi-model.js; ``BI <report> <GO>`` opens the viewer; the same-origin JSON reaches the server's report paths under the
caller's identity and nothing else does; no page carries a script, handler or style of its own (the strict CSP of the BI pages)."""
import re
from pathlib import Path

from bi_backend import REPORT_ID
from test_bi import go, make

WEB = Path(__file__).resolve().parent.parent / "web"


def test_the_viewer_is_a_shell_with_the_toolbar_and_the_scripts_in_order():
    client, _ = make()
    r = client.get("/bi/r/" + REPORT_ID)
    assert r.status_code == 200
    assert f'data-item="{REPORT_ID}"' in r.text and 'data-bi-page="report"' in r.text
    for hook in ("data-pages", "data-grid", "data-date", "data-bookmarks", "data-mode", "data-reset", "data-chips", "data-back", "data-problems"):
        assert hook in r.text, hook
    scripts = re.findall(r"/static/js/([a-z-]+)\.js", r.text)
    assert scripts.index("bi-lib") < scripts.index("bi-visuals") < scripts.index("bi-grids") < scripts.index("bi-frame") < scripts.index("bi-report")
    assert "multiselect" in scripts and "/static/vendor/echarts/echarts.min.js" in r.text and "bi-report.css" in r.text


def test_the_viewer_takes_a_page_after_the_id():
    client, _ = make()
    r = client.get(f"/bi/r/{REPORT_ID}/counterparty")
    assert r.status_code == 200 and f'data-item="{REPORT_ID}"' in r.text


def test_the_designer_start_and_one_draft_and_the_model_view_are_real_pages():
    client, _ = make()
    start = client.get("/bi/designer")
    assert start.status_code == 200 and "data-new" in start.text and "data-drafts" in start.text and "Not built yet" not in start.text
    d = client.get("/bi/design/bi-77")
    assert d.status_code == 200 and 'data-item="bi-77"' in d.text
    for hook in ("data-canvas-grid", "data-fields-tree", "data-types", "data-build", "data-format", "data-filters", "data-yaml", "data-undo", "data-redo", "data-check", "data-save", "data-preview"):
        assert hook in d.text, hook
    scripts = re.findall(r"/static/js/([a-z-]+)\.js", d.text)
    assert scripts.index("bi-design") < scripts.index("bi-design-canvas") < scripts.index("bi-design-panes") and "cm-yaml" in scripts
    m = client.get("/bi/m/file:dataset:trades-basic")
    assert m.status_code == 200 and "data-diagram-inner" in m.text and "data-nr-add" in m.text and "data-infer" in m.text and "bi-model.js" in m.text


def test_the_designer_is_in_the_rail_without_a_phase_label():
    client, _ = make()
    r = client.get("/bi/designer")
    assert "Designer" in r.text and "phase 3" not in r.text


def test_the_keyword_opens_a_report_in_the_viewer():
    client, _ = make()
    assert go(client, "RUPAKA bi-1").headers["location"] == "/bi/r/bi-1"
    assert go(client, "bi desk p&l").headers["location"] == "/bi/r/bi-1"


def test_every_new_page_needs_the_bi_power():
    client, _ = make(powers=())
    for path in ("/bi/r/" + REPORT_ID, "/bi/designer", "/bi/design/bi-77", "/bi/m/file:dataset:trades-basic"):
        assert client.get(path).status_code == 403, path


def test_the_json_for_the_viewer_and_the_designer_is_passed_on():
    client, backend = make()
    assert client.get("/bi/api/reports/" + REPORT_ID).json()["report"]["pages"][0]["visuals"][0]["id"] == "mtm"
    body = {"page": "overview", "visual": "by-desk", "state": {"slicers": {}, "drill": []}}
    answer = client.post(f"/bi/api/reports/{REPORT_ID}/query", json=body).json()
    assert answer["visual"] == "by-desk" and answer["echo"] == body
    assert client.get("/bi/api/reports/catalogue").json()["reserved"] == ["map"]
    assert client.post("/bi/api/reports/validate", json={"yaml": "report: x\n", "quality": True}).json()["valid"] is True
    assert client.post("/bi/api/reports/format", json={"report": {"report": "x"}}).json()["yaml"].startswith("report:")
    assert client.get("/bi/api/reports/dataset", params={"ref": "trades-basic"}).json()["name"] == "trades-basic"
    assert [c[1] for c in backend.bi_calls if c[0] == "POST"][-3:] == [f"/reports/{REPORT_ID}/query", "/reports/validate", "/reports/format"]
    assert ("GET", "/reports/dataset", None, {"ref": "trades-basic"}) in backend.bi_calls


def test_a_write_to_the_report_paths_checks_the_origin_and_the_body():
    client, _ = make()
    assert client.post(f"/bi/api/reports/{REPORT_ID}/query", json={"page": "p", "visual": "v"}, headers={"origin": "https://evil.example"}).status_code == 403
    assert client.post(f"/bi/api/reports/{REPORT_ID}/query", content="x=1", headers={"content-type": "text/plain"}).status_code in (400, 415, 422)


def test_the_servers_refusals_for_reports_come_back_with_their_codes():
    client, backend = make()
    backend.refuse = ("DRS-9035", 422, "page 'nope' has no visual 'v'")
    r = client.post(f"/bi/api/reports/{REPORT_ID}/query", json={"page": "overview", "visual": "v"})
    assert r.status_code == 422 and r.json() == {"code": "DRS-9035", "detail": "page 'nope' has no visual 'v'"}
    backend.refuse = ("DRS-9034", 422, "no dataset 'x'")
    assert client.get("/bi/api/reports/" + REPORT_ID).json()["code"] == "DRS-9034"


def test_the_new_scripts_and_stylesheets_exist_and_carry_no_cdn_or_inline_code():
    for rel in ("js/bi-lib.js", "js/bi-visuals.js", "js/bi-grids.js", "js/bi-frame.js", "js/bi-report.js", "js/bi-design.js", "js/bi-design-canvas.js", "js/bi-design-panes.js", "js/bi-model.js",
                "css/bi-report.css", "css/bi-design.css"):
        text = (WEB / "static" / rel).read_text(encoding="utf-8")
        assert "http://" not in text.replace("http://www.w3.org/2000/svg", "") and "https://" not in text, rel
        assert "innerHTML" not in text and "document.write" not in text and "eval(" not in text, rel
        assert len(text.splitlines()) < 1500, rel
    for rel in ("report.html", "design.html", "designer.html", "model.html"):
        html = (WEB / "templates" / "bi" / rel).read_text(encoding="utf-8")
        assert " onclick=" not in html and "<style" not in html and " style=" not in html, rel
        assert not re.search(r"<script(?![^>]*\bsrc=)", html), rel


def test_no_class_the_bi_pages_define_is_a_bootstrap_icons_glyph():
    """``bi-`` is also the icon prefix: a class named like an icon (``bi-grid``, ``bi-type``) paints that glyph on the element; the pages use their own names."""
    glyphs = set(re.findall(r"\.(bi-[a-z0-9-]+)::before", (WEB / "static" / "vendor" / "bootstrap-icons" / "bootstrap-icons.css").read_text(encoding="utf-8")))
    used: set[str] = set()
    for rel in ("js/bi-visuals.js", "js/bi-grids.js", "js/bi-frame.js", "js/bi-report.js", "js/bi-design.js", "js/bi-design-canvas.js", "js/bi-design-panes.js", "js/bi-model.js"):
        text = (WEB / "static" / rel).read_text(encoding="utf-8")
        for m in re.finditer(r"class: '([^']+)'", text):                      # an element's own class list, not the icon helper's names
            used.update(c for c in m.group(1).split() if c.startswith("bi-"))
    for rel in ("report.html", "design.html", "designer.html", "model.html"):
        for m in re.finditer(r'<(?!i\b)[a-z0-9]+[^>]*class="([^"]+)"', (WEB / "templates" / "bi" / rel).read_text(encoding="utf-8")):         # not the <i class="bi bi-x"> icons
            used.update(c for c in m.group(1).split() if c.startswith("bi-") and c != "bi")
    clashes = sorted(c for c in used & glyphs if c != "bi")
    assert not clashes, f"classes that are also icon names: {clashes}"
