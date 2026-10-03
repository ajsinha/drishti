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

"""Layout mode and personal layouts (USER_GUIDE.md, Layout mode): the overlay applied to a view (order, column, width,
height, hidden; a changed Sutra), the key and its reasons, the JSON routes, promotion's diff, the Admin roles power,
the review page's diff of a new version, and workspaces keeping their divider sizes."""
import re

import pytest

from core.layouts import Layouts

PANELS = [{"id": "legs", "area": "main"}, {"id": "cashflows", "area": "main", "span": 8}, {"id": "built", "area": "main"},
          {"id": "curve", "area": "right", "height": 6}, {"id": "refs", "area": "right"}]


@pytest.fixture()
def layouts_on(client, backend):
    backend.layout_allowed = True
    backend.layout_promote = True
    backend.layouts_kept = {}
    client.app.state.layouts._kept.clear()
    yield
    backend.layout_allowed = True
    backend.layout_promote = True
    backend.layouts_kept = {}
    client.app.state.layouts._kept.clear()


def _order(html: str) -> list[str]:
    return re.findall(r'data-panel="([^"]+)"', html)


# ---- the overlay -----------------------------------------------------------------------------------------------

def test_without_a_layout_the_sutra_places_and_sizes_every_panel():
    out = Layouts.apply(PANELS, None)
    assert [p["id"] for p in out] == ["legs", "cashflows", "built", "curve", "refs"]
    assert out[1]["span"] == 8 and out[3]["height"] == 6 and out[0]["span"] is None and not any(p["hidden"] for p in out)


def test_a_layout_orders_moves_sizes_and_hides():
    mine = {"panels": [{"id": "refs", "area": "main", "span": 4}, {"id": "legs", "area": "main", "span": 12, "height": 10},
                       {"id": "built", "hidden": True, "area": "main"}, {"id": "curve", "area": "right"}]}
    out = {p["id"]: p for p in Layouts.apply(PANELS, mine)}
    assert [p["id"] for p in Layouts.apply(PANELS, mine)] == ["refs", "legs", "built", "curve", "cashflows"]
    assert out["refs"]["area"] == "main" and out["refs"]["span"] == 4
    assert out["legs"]["span"] is None and out["legs"]["height"] == 10          # 12 columns is the whole column
    assert out["built"]["hidden"] and out["curve"]["height"] is None              # the user's natural height wins over the Sutra's
    assert out["cashflows"]["span"] == 8 and not out["cashflows"]["hidden"]       # not listed: as the Sutra has it


def test_a_changed_sutra_drops_removed_panels_and_appends_new_ones():
    mine = {"panels": [{"id": "gone", "area": "main"}, {"id": "curve", "area": "main", "span": 6}, {"id": "legs", "area": "right"}]}
    grown = PANELS + [{"id": "pnl-new", "area": "right", "span": 6}]
    out = Layouts.apply(grown, mine)
    assert [p["id"] for p in out] == ["curve", "legs", "cashflows", "built", "refs", "pnl-new"]
    assert out[-1]["area"] == "right" and out[-1]["span"] == 6


def test_bad_sizes_are_ignored_and_a_layout_never_hides_everything():
    mine = {"panels": [{"id": p["id"], "hidden": True, "span": 40, "height": "tall", "area": "left"} for p in PANELS]}
    out = Layouts.apply(PANELS, mine)
    assert not any(p["hidden"] for p in out)
    assert all(p["span"] is None and p["height"] is None for p in out)
    assert out[3]["area"] == "right"                                              # an unknown column keeps the Sutra's


# ---- the view ---------------------------------------------------------------------------------------------------

def test_a_view_is_drawn_with_the_users_layout(client, backend, layouts_on):
    backend.layouts_kept[("irs-vanilla", "trade")] = {"panels": [
        {"id": "cashflows", "area": "main", "span": 8, "height": 10}, {"id": "refs", "area": "main", "span": 4},
        {"id": "legs", "area": "main"}, {"id": "curve", "area": "right", "hidden": True}]}
    html = client.get("/v/trade/IRS-48213").text
    assert _order(html)[:4] == ["cashflows", "refs", "legs", "leg2"]
    assert re.search(r'class="pnl c-span-8 c-h-10" id="p-cashflows"', html)
    assert re.search(r'class="pnl pnl-off" id="p-curve"', html)
    assert 'data-fkey="F4" data-action="panel" aria-label="F4 USD-SOFR curve" disabled title="USD-SOFR curve: hidden in your layout' in html      # its key is greyed
    assert "data-layout-open" in html and "<b>Alt+L</b> Layout" in html and "/static/js/layout.js" in html
    assert 'data-sutra="irs-vanilla"' in html and "data-layout-mine" in html and 'id="layoutPromote"' in html
    main, right = html.split('<div class="vmain">')[1].split('<aside class="vright"')
    assert 'id="p-refs"' in main and 'id="p-refs"' not in right


def test_the_key_is_greyed_with_the_reason(client, backend, layouts_on):
    backend.layout_allowed = False
    html = client.get("/v/trade/IRS-48213").text
    assert "data-layout-open disabled" in html and "customising layouts needs a role with layout" in html
    assert "/static/js/layout.js" not in html and "data-layout-bar" not in html
    backend.layout_allowed = True
    client.app.state.layouts._kept.clear()
    html = client.get("/v/trade/IRS-47102").text                               # inference only: no Sutra to arrange
    assert "data-layout-open disabled" in html and "laid out by inference alone" in html


def test_an_author_may_promote_others_may_not(client, backend, layouts_on):
    backend.layout_promote = False
    html = client.get("/v/trade/IRS-48213").text
    assert "data-layout-bar" in html and "data-layout-promote" not in html and 'id="layoutPromote"' not in html


def test_workspace_panes_follow_the_layout_without_offering_layout_mode(client, backend, layouts_on):
    backend.layouts_kept[("irs-vanilla", "trade")] = {"panels": [{"id": "dv01", "area": "main", "span": 6}]}
    html = client.get("/v/trade/IRS-48213?embed=1").text
    assert _order(html)[0] == "dv01" and "c-span-6" in html
    assert "data-layout-open" not in html and "/static/js/layout.js" not in html


def test_a_view_from_a_server_without_layouts_is_unchanged(client, backend, layouts_on, monkeypatch):
    async def older(ident=None):
        raise RuntimeError("404 from an older server")
    monkeypatch.setattr(backend, "layouts", older)
    html = client.get("/v/trade/IRS-48213").text
    assert "data-layout-open" not in html and _order(html)[0] == "legs"


# ---- the JSON routes ---------------------------------------------------------------------------------------------

def test_saving_forwards_only_layout_keys_and_is_seen_at_once(client, backend, layouts_on):
    client.get("/v/trade/IRS-48213")                                              # the user's layouts are now cached
    r = client.put("/api/layout/irs-vanilla/trade", json={"panels": [{"id": "dv01", "area": "main", "span": 6, "evil": "<x>"}], "x": 1})
    assert r.status_code == 200
    assert backend.layouts_kept[("irs-vanilla", "trade")] == {"panels": [{"id": "dv01", "area": "main", "span": 6}]}
    assert _order(client.get("/v/trade/IRS-48213").text)[0] == "dv01"             # not the cached answer of a minute ago


def test_the_server_refuses_unknown_panels_and_users_without_the_power(client, backend, layouts_on):
    r = client.put("/api/layout/irs-vanilla/trade", json={"panels": [{"id": "ghost"}]})
    assert r.status_code == 400 and "not a panel of irs-vanilla" in r.json()["detail"]
    backend.layout_allowed = False
    r = client.put("/api/layout/irs-vanilla/trade", json={"panels": [{"id": "legs"}]})
    assert r.status_code == 403 and "role with layout" in r.json()["detail"]


def test_reset_goes_back_to_the_sutra(client, backend, layouts_on):
    backend.layouts_kept[("irs-vanilla", "trade")] = {"panels": [{"id": "dv01", "area": "main"}]}
    assert client.delete("/api/layout/irs-vanilla/trade").json() == {"ok": True}
    assert ("irs-vanilla", "trade") not in backend.layouts_kept
    assert client.delete("/api/layout/irs-vanilla/trade").status_code == 200     # nothing to forget is fine
    assert _order(client.get("/v/trade/IRS-48213").text)[0] == "legs"


def test_promotion_shows_the_diff_and_proposes_through_review(client, backend, layouts_on):
    d = client.get("/api/layout/irs-vanilla/trade/promotion?dropHidden=true").json()
    assert d["fromVersion"] == 3 and d["version"] == 4 and d["review"] is True
    assert "'built' is removed (hidden in the layout)" in d["changes"]
    assert ["del", "-version: 3"] in d["diff"] and ["add", "+version: 4"] in d["diff"]
    assert ["add", "+  - { id: refs, kind: links, span: 6 }"] in d["diff"]
    assert d["moves"] == ["moved: refs from position 1 to 2 (side → main)"]          # in words, beside the edits
    assert [x for x in d["edits"] if x[0] in ("add", "del")] == [["del", "-version: 3"], ["add", "+version: 4"],
                                                                ["del", "-  - { id: refs, kind: links, area: right }"],
                                                                ["add", "+  - { id: refs, kind: links, span: 6 }"]]
    js = client.get("/static/js/layout.js").text
    assert "data-promote-moves" in js and "data-promote-full-diff" in js and "res.b.edits" in js
    r = client.post("/api/layout/irs-vanilla/trade/promotion", json={"note": "cashflows first", "dropHidden": True}).json()
    assert r["proposal"]["id"] == "P-000042" and r["href"] == "/studio/reviews/P-000042"
    assert ("promote", "irs-vanilla", "trade", "cashflows first", True) in backend.calls
    backend.layout_promote = False
    r = client.get("/api/layout/irs-vanilla/trade/promotion")
    assert r.status_code == 403 and "needs a role with author" in r.json()["detail"]


# ---- around it ---------------------------------------------------------------------------------------------------

def test_admin_roles_has_the_layout_power_on_by_default(client, backend):
    page = client.get("/admin/roles").text
    assert '<input type="checkbox" name="layout" checked>' in page and "Customise layouts" in page
    client.post("/admin/api/roles/kiosk", json={"kinds": ["*"], "layout": False})
    assert ("admin", "PUT", "/role-definitions/kiosk", {"kinds": ["*"], "layout": False}) in backend.calls


def test_a_new_version_is_reviewed_against_the_version_before_it(client, backend, monkeypatch):
    async def proposal(id_, ident=None):
        return {"id": id_, "name": "irs-vanilla", "version": 4, "note": "", "author": "ana", "createdAt": "2026-10-01T09:00:00Z",
                "status": "pending", "newVersion": True, "stale": False, "mayApprove": True, "mayWithdraw": False,
                "text": "sutra: irs-vanilla\nversion: 4\n", "baseText": "", "liveText": "", "previousText": "sutra: irs-vanilla\nversion: 3\n"}
    monkeypatch.setattr(backend, "proposal", proposal, raising=False)
    html = client.get("/studio/reviews/P-000042").text
    assert "changes against the latest earlier version" in html
    assert '<span class="d-del">-version: 3</span>' in html and '<span class="d-add">+version: 4</span>' in html


def test_workspaces_keep_where_their_dividers_were_dragged(client, backend):
    body = {"layout": "2col", "sizes": {"cols": [1.5, 0.5]},
            "panes": [{"ref": {"kind": "trade", "id": "IRS-48213"}, "follows": None, "title": ""}, {"ref": None, "follows": None, "title": ""}]}
    assert client.post("/w/api/Sized", json=body).status_code == 200
    assert backend.saved_workspaces["Sized"]["sizes"] == {"cols": [1.5, 0.5]}
    html = client.get("/w/Sized").text
    assert "&#34;sizes&#34;: {&#34;cols&#34;: [1.5, 0.5]}" in html or '"sizes": {"cols": [1.5, 0.5]}' in html.replace("&#34;", '"')
    assert "/static/js/workspace.js" in html
