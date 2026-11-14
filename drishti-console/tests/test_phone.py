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

"""The phone display (docs/guides/BUILD_GUIDE.md): the tiers resolved over the layers with the site and edition caps, the
compact forms cut on the console, a phone getting only must and glance panels, a *More* panel loaded on its own, a tablet keeping its
layout less the desk panels, the desktop unchanged, and the lens menus folded into one on a phone (LX1 option C)."""
import copy
import json
import re

from conftest import FIXTURES

from core import phone

SHOWCASE = json.loads((FIXTURES / "view_sutra_all-panels-showcase.json").read_text())
PHONE = {"drishti-device": "phone"}


def _vm(**phone_block):
    vm = copy.deepcopy(SHOWCASE)
    if phone_block:
        vm["phone"] = phone_block
    return vm


# ---- the tiers ------------------------------------------------------------------------------------------------------------------

def test_kind_defaults_tables_and_charts_glance_provenance_more_pivot_desk():
    t = phone.resolve(_vm(), SHOWCASE["panels"])
    assert t["terms"] == {"tier": "must", "from": "kind"}
    assert t["versions"]["tier"] == "glance" and t["pnl"]["tier"] == "glance"   # decided: charts glance, as tables
    assert t["built"]["tier"] == "more" and t["note"]["tier"] == "more"
    assert t["grid"]["tier"] == "desk"


def test_the_most_specific_layer_wins_and_a_cap_wins_over_every_layer():
    vm = _vm(panels={"versions": {"tier": "must", "from": "sutra"}, "coupons": {"tier": "more", "from": "fragment"},
                     "pnl": {"tier": "must", "from": "sutra"}}, default="more")
    site = [("site", [{"kind": "provenance", "tier": "desk"}, {"panel": "pnl", "max": "glance"}])]
    edition = [("edition", [{"panel": "coupons", "tier": "glance"}])]
    t = phone.resolve(vm, SHOWCASE["panels"], site + edition)
    assert t["versions"] == {"tier": "must", "from": "sutra"}               # layer 3
    assert t["coupons"]["tier"] == "glance" and t["coupons"]["from"] == "edition"   # layer 4 over the fragment
    assert t["built"] == {"tier": "desk", "from": "site"}
    assert t["pnl"]["tier"] == "glance" and t["pnl"]["capped"] and t["pnl"]["from"] == "site"   # the site's cap beats the Sutra
    assert t["terms"] == {"tier": "more", "from": "sutra"}                  # the screen's default replaces the kind default


def test_a_page_always_has_a_must_panel():
    panels = [p for p in SHOWCASE["panels"] if p["kind"] in ("table", "line")]
    t = phone.resolve(_vm(), panels)
    assert t[panels[0]["id"]] == {"tier": "must", "from": "rule"}


# ---- the compact forms ------------------------------------------------------------------------------------------------------------

def test_a_glance_table_is_cut_to_its_rows_columns_and_sort():
    table = {"id": "t", "kind": "table", "title": "Trades", "data": {"columns": ["Trade", "Product", "MTM"], "numeric": [False, False, True],
             "rows": [{"cells": [{"text": f"T{i}"}, {"text": "Swap"}, {"text": f"{v}m"}], "path": f"$.T[{i}]"} for i, v in enumerate([3, -9.5, 41.2, 7, 0.5, 12, 1])],
             "total": {"cells": []}, "more": "2 more", "pivot": {"x": 1}}}
    c = phone.compact(table, {"tier": "glance", "rows": 3, "columns": ["Trade", "MTM"], "sort": "-MTM"})
    assert c["data"]["columns"] == ["Trade", "MTM"] and c["data"]["numeric"] == [False, True]
    assert [r["cells"][0]["text"] for r in c["data"]["rows"]] == ["T2", "T5", "T3"]
    assert "pivot" not in c["data"] and c["data"]["total"] is None
    assert c["phone"]["compact"] and c["phone"]["total"] == 7 and c["phone"]["shown"] == 3
    assert table["data"]["rows"][0]["cells"][0]["text"] == "T0"               # the view model itself is untouched


def test_numbers_read_back_from_cell_text():
    assert phone.number("41.2m") == 41_200_000
    assert phone.number("(1,234)") == -1234
    assert phone.number("−3.5%") == -3.5
    assert phone.number("n/a") is None


def test_a_glance_chart_is_a_sparkline_and_a_map_a_list():
    by_id = {p["id"]: p for p in SHOWCASE["panels"]}
    line = phone.compact(by_id["pnl"], {"tier": "glance"})
    sp = line["phone"]["spark"]
    assert sp and sp["lines"][0]["points"].count(",") == len(by_id["pnl"]["data"]["series"][0]["values"])
    candles = phone.compact(by_id["bars"], {"tier": "glance"})
    assert candles["phone"]["spark"]["lines"][0]["label"] == "Close"
    where = phone.compact(by_id["where"], {"tier": "glance"})
    assert "list" in where["phone"]


# ---- the page ---------------------------------------------------------------------------------------------------------------------

def _page(client, url, cookies=None, headers=None):
    client.cookies.clear()
    for k, v in (cookies or {}).items():
        client.cookies.set(k, v)
    try:
        return client.get(url, headers=headers or {})
    finally:
        client.cookies.clear()


def test_a_phone_gets_must_and_glance_panels_only_and_the_more_and_desk_rows(client):
    r = _page(client, "/v/sutra/all-panels-showcase", PHONE)
    assert r.status_code == 200
    html = r.text
    assert 'data-phone-page="phone"' in html
    assert 'id="p-terms"' in html and 'id="p-versions"' in html                # must, glance
    assert 'id="p-built"' not in html and 'id="p-grid"' not in html            # more and desk are not sent
    assert 'data-phone-load="built"' in html and 'data-phone-load="note"' in html
    assert "On desktop" in html and re.search(r"ph-desk-l[^>]*>[^<]*Pivot|ph-desk-l", html)
    assert 'data-phone-all="coupons"' in html                                 # a compact panel offers Show all
    # must first, then glance
    assert html.index('id="p-terms"') < html.index('id="p-versions"')


def test_the_header_says_phone_as_the_cookie_does(client):
    r = _page(client, "/v/sutra/all-panels-showcase", headers={"X-Drishti-Device": "phone"})
    assert 'data-phone-page="phone"' in r.text


def test_the_desktop_is_unchanged(client):
    for cookies in ({}, {"drishti-device": "desk"}):
        html = _page(client, "/v/sutra/all-panels-showcase", cookies).text
        assert "data-phone-page" not in html and 'id="p-grid"' in html and 'id="p-built"' in html


def test_show_everything_gives_the_whole_page_with_a_way_back(client):
    html = _page(client, "/v/sutra/all-panels-showcase", {**PHONE, "drishti-phone-all": "1"}).text
    assert "data-phone-page" not in html and 'id="p-grid"' in html and "data-phone-back" in html


def test_a_tablet_keeps_its_layout_less_the_desk_panels(client):
    html = _page(client, "/v/sutra/all-panels-showcase", {"drishti-device": "tablet"}).text
    assert 'data-phone-page="tablet"' in html
    assert 'id="p-built"' in html and 'id="p-versions"' in html                # more panels are shown on a tablet
    assert 'id="p-grid"' not in html and 'data-phone-load="grid"' in html      # desk ones wait under More
    assert "data-phone-compact" not in html                                    # nothing is cut on a tablet


def test_a_more_panel_loads_on_its_own_whole_or_compact(client):
    r = _page(client, "/v/sutra/all-panels-showcase/phone/built?full=1", PHONE)
    assert r.status_code == 200 and 'id="p-built"' in r.json()["html"]
    r = _page(client, "/v/sutra/all-panels-showcase/phone/coupons?compact=1", PHONE)
    assert "data-phone-compact" in r.json()["html"]
    assert _page(client, "/v/sutra/all-panels-showcase/phone/nope?full=1", PHONE).status_code == 404


def test_the_sutras_summary_line_and_order_reach_the_phone(client, backend, monkeypatch):
    real = backend.view

    async def view(kind, id_, user, controls=None, panels=None, **kw):
        vm = await real(kind, id_, user, controls=controls, panels=panels)
        vm["phone"] = {"summary": "Net MTM 214.7m · 270 trades", "order": ["headline", "terms"],
                       "panels": {"headline": {"tier": "must", "from": "sutra"}, "versions": {"tier": "more", "from": "sutra"}}}
        return vm
    monkeypatch.setattr(backend, "view", view)
    html = _page(client, "/v/sutra/all-panels-showcase", PHONE).text
    assert "Net MTM 214.7m · 270 trades" in html
    assert html.index('id="p-headline"') < html.index('id="p-terms"')
    assert 'data-phone-load="versions"' in html and 'id="p-versions"' not in html


def test_the_phone_display_can_be_turned_off(client, monkeypatch):
    s = client.app.state.settings
    real = s.get
    monkeypatch.setattr(s, "get", lambda k, d=None: {"enabled": False} if k == "ui.phone" else real(k, d))
    html = _page(client, "/v/sutra/all-panels-showcase", PHONE).text
    assert "data-phone-page" not in html


# ---- the lens menus' phone form ---------------------------------------------------------------------------------------------------

def test_a_lens_folds_its_subject_menus_into_one_menu_named_after_it(backend, monkeypatch):
    from test_edition import _app, _client, _workspace
    app = _app(backend, monkeypatch)
    _workspace(app, "trading")
    html = _client(app).get("/t").text
    fold = html[html.index("data-lens-fold"):html.index("</template>")]
    assert 'class="tbar-lens-l">Trading<' in fold
    for subject in ("Markets", "Blotter", "Positions", "Risk", "Counterparties"):
        assert f'mega-subject">{subject}<' in fold, subject
    assert html.count("data-subject>") == 5                                   # the subject menus stay, for a wider screen


def test_the_risk_edition_folds_risk_and_counterparties_into_its_lens_menu_on_a_phone(backend, monkeypatch):
    from test_edition import RISK_ABOUT, _app, _client
    app = _app(backend, monkeypatch)
    app.state.editions.learn(RISK_ABOUT)
    html = _client(app).get("/t").text
    fold = html[html.index("data-lens-fold"):html.index("</template>")]
    assert 'mega-subject">Risk<' in fold and 'mega-subject">Counterparties<' in fold and "data-engines-menu" not in html


def test_a_full_drishti_has_no_lens_menu(backend, monkeypatch):
    from test_edition import _app, _client
    app = _app(backend, monkeypatch)
    app.state.editions.learn({"product": "Drishti", "edition": None, "lenses": []})
    assert "data-lens-fold" not in _client(app).get("/t").text


# ---- beyond Sutra pages: the VaR and exposure pages and the landings (docs/guides/BUILD_GUIDE.md section 8) --------------------------------

def _tier(html: str, part: str) -> str | None:
    m = re.search(r'data-phone-part="%s" data-phone-tier="([a-z]+)"' % re.escape(part), html)
    return m.group(1) if m else None


def test_the_var_page_on_a_phone_headline_must_path_glance_contributions_more_whatif_desk(client):
    html = _page(client, "/var?scope=BANK", PHONE).text
    assert _tier(html, "tiles") == "must" and _tier(html, "path-dist") == "glance"
    assert _tier(html, "contrib") == "more" and _tier(html, "hist") == "more" and _tier(html, "whatif") == "desk"
    assert 'data-phone-reveal="contrib"' in html and "On desktop" in html
    desk = _page(client, "/var?scope=BANK").text
    assert "data-phone-tier" not in desk and "ph-parts" not in desk                  # the desktop is unchanged


def test_the_exposure_page_on_a_phone(client):
    html = _page(client, "/exposure?scope=BANK", PHONE).text
    assert _tier(html, "tiles") == "must" and _tier(html, "chart") == "glance" and _tier(html, "tabs") == "more" and _tier(html, "fan") == "desk"


def test_a_tablet_shows_more_parts_and_keeps_desk_ones_under_more(client):
    html = _page(client, "/var?scope=BANK", {"drishti-device": "tablet"}).text
    assert _tier(html, "contrib") == "must" and _tier(html, "whatif") == "more"


def test_the_site_and_the_edition_set_and_cap_a_pages_parts(client, monkeypatch):
    s = client.app.state.settings
    real = s.get
    monkeypatch.setattr(s, "get", lambda k, d=None: {"pages": {"var": {"contrib": "glance", "tiles": {"max": "glance"}}}} if k == "ui.phone" else real(k, d))
    html = _page(client, "/var?scope=BANK", PHONE).text
    assert _tier(html, "contrib") == "glance" and _tier(html, "tiles") == "glance"


def test_the_risk_landing_on_a_phone(backend, monkeypatch):
    from test_edition import RISK_ABOUT, _app, _client
    app = _app(backend, monkeypatch)
    app.state.editions.learn(RISK_ABOUT)
    c = _client(app)
    c.cookies.set("drishti-device", "phone")
    html = c.get("/").text
    assert _tier(html, "tiles") == "must" and _tier(html, "card-1") == "must" and _tier(html, "table") == "glance"
    assert 'data-phone-reveal="quick"' in html
