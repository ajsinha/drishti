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

"""The BI catalogue by kind (core/bi_catalogue_tabs.py): ?kind= names a tab (old singular links too, Reports otherwise); each item kind lands in one
tab (a report with board pages under Boards, Transforms and notebooks under Python); the counts follow the search; All groups the items by kind; the
console asks the server for every kind and for the boards, and passes the search, filters and sort on. Synthetic items only."""
import pytest
from fastapi.testclient import TestClient

from bi_backend import BiBackend
from conftest import CONSOLE
from core.app import create_app
from core.bi_catalogue_tabs import DEFAULT_TAB, split, tab_key, tab_of
from core.config import load_settings


def _item(i, kind, title, board=False):
    return {"id": f"bi-{kind}-{i}", "workspace": "rates", "workspaceName": "Rates Desk", "personal": False, "kind": kind, "title": title,
            "description": f"A synthetic {kind}", "owner": "ann", "endorsement": "certified" if i == 0 else "none", "tags": ["sample"], "status": "published",
            "publishedVersion": 1, "publishedAt": "2026-10-01T09:00:00Z", "views": 10 - i, "favourite": False, "shipped": False, "_board": board}


ITEMS = ([_item(i, "report", f"Desk P&L {i}") for i in range(3)] + [_item(9, "report", "Risk board", board=True), _item(0, "board", "Rates board")]
         + [_item(0, "model", "Trades model"), _item(0, "dataset", "Trades, the basics"), _item(1, "dataset", "Rates curve")]
         + [_item(0, "python", "VaR transform"), _item(0, "notebook", "Desk notebook")])


class CatalogueBackend(BiBackend):
    """The stand-in server's catalogue: every kind, the search over the title, kind=board for board kinds and reports with board pages."""

    async def bi(self, method, path, ident, body=None, **params):
        if path != "/catalogue" or self.refuse:
            return await super().bi(method, path, ident, body, **params)
        self.bi_calls.append((method, path, body, params))
        text = (params.get("q") or "").lower()
        hits = [i for i in ITEMS if text in i["title"].lower()]
        kind = params.get("kind")
        if kind == "board":
            hits = [i for i in hits if i["kind"] == "board" or i["_board"]]
        elif kind:
            hits = [i for i in hits if i["kind"] == kind]
        if params.get("sort") == "title":
            hits = sorted(hits, key=lambda i: i["title"].lower())
        return {"total": len(hits), "items": [{k: v for k, v in i.items() if k != "_board"} for i in hits],
                "facets": {"kind": {}, "workspace": {"rates": len(ITEMS)}, "owner": {"ann": len(ITEMS)}, "endorsement": {"certified": 5, "none": 5}, "tag": {"sample": 10}},
                "workspaceNames": {"rates": "Rates Desk"}}


@pytest.fixture()
def bi():
    app = create_app(load_settings(CONSOLE / "config"))
    app.state.backend = CatalogueBackend(("bi", "bi-author"), True)
    return TestClient(app), app.state.backend


@pytest.mark.parametrize("asked,tab", [(None, "reports"), ("", "reports"), ("reports", "reports"), ("report", "reports"), ("Boards", "boards"), ("board", "boards"),
                                       ("models", "models"), ("dataset", "datasets"), ("python", "python"), ("notebook", "python"), ("transform", "python"),
                                       ("all", "all"), ("nonsense", "reports")])
def test_a_kind_in_the_address_names_its_tab(asked, tab):
    assert tab_key(asked) == tab and DEFAULT_TAB == "reports"


def test_every_kind_has_one_tab():
    assert {k: tab_of({"id": "x", "kind": k}, set()) for k in ("report", "board", "model", "dataset", "python", "notebook", "transform")} == {
        "report": "reports", "board": "boards", "model": "models", "dataset": "datasets", "python": "python", "notebook": "python", "transform": "python"}
    assert tab_of({"id": "r", "kind": "report"}, {"r"}) == "boards"            # a report with board pages
    assert tab_of({"id": "z", "kind": "someday"}, set()) == ""                    # an unknown kind: under All only


def test_split_counts_every_tab_and_groups_all():
    items = [{"id": "a", "kind": "report"}, {"id": "b", "kind": "report"}, {"id": "c", "kind": "python"}, {"id": "d", "kind": "notebook"}, {"id": "e", "kind": "odd"}]
    out = split({"total": 7, "items": items, "facets": {}}, {"b"}, "all")
    assert {t["key"]: t["count"] for t in out["tabs"]} == {"reports": 1, "boards": 1, "models": 0, "datasets": 0, "python": 2, "all": 5}
    assert [g["key"] for g in out["groups"]] == ["reports", "boards", "python", "other"] and out["more"] is True and out["total"] == 5
    py = split({"total": 5, "items": items}, {"b"}, "python")
    assert [i["id"] for i in py["items"]] == ["c", "d"] and "groups" not in py and py["more"] is False


def test_the_route_answers_a_tab_with_its_counts(bi):
    client, backend = bi
    d = client.get("/bi/api/catalogue-tabs", params={"kind": "reports", "sort": "use"}).json()
    assert d["tab"] == "reports" and [i["title"] for i in d["items"]] == ["Desk P&L 0", "Desk P&L 1", "Desk P&L 2"]
    assert {t["key"]: t["count"] for t in d["tabs"]} == {"reports": 3, "boards": 2, "models": 1, "datasets": 2, "python": 2, "all": 10}
    asked = [c[3] for c in backend.bi_calls if c[1] == "/catalogue"]
    assert asked == [{"sort": "use"}, {"sort": "use", "kind": "board"}]      # every kind once, the boards once; the tab is the console's
    boards = client.get("/bi/api/catalogue-tabs", params={"kind": "boards"}).json()
    assert sorted(i["title"] for i in boards["items"]) == ["Rates board", "Risk board"]


def test_the_search_works_inside_the_tab_and_the_counts_follow_it(bi):
    client, _ = bi
    d = client.get("/bi/api/catalogue-tabs", params={"kind": "python", "q": "desk"}).json()
    assert [i["title"] for i in d["items"]] == ["Desk notebook"]
    assert {t["key"]: t["count"] for t in d["tabs"]} == {"reports": 3, "boards": 0, "models": 0, "datasets": 0, "python": 1, "all": 4}
    a = client.get("/bi/api/catalogue-tabs", params={"kind": "all", "q": "trades"}).json()
    assert [(g["label"], [i["title"] for i in g["items"]]) for g in a["groups"]] == [("Models", ["Trades model"]), ("Datasets", ["Trades, the basics"])]


def test_the_page_carries_the_tabs_and_a_refusal_is_passed_back(bi):
    client, backend = bi
    html = client.get("/bi/catalogue?kind=boards").text
    for key in ("reports", "boards", "models", "datasets", "python", "all"):
        assert f'data-cat-tab="{key}"' in html and f'href="?kind={key}"' in html
    assert 'role="tablist"' in html and "bi-runs.css" in html and 'name="kind"' not in html
    backend.refuse = ("DRS-9001", 403, "no")
    r = client.get("/bi/api/catalogue-tabs", params={"kind": "all"})
    assert r.status_code == 403 and r.json()["code"] == "DRS-9001"
