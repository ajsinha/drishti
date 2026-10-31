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

"""Distribution of Drishti Elements (docs/architecture/CLIENT_RENDERER.md › Drishti Elements: the design, section 13; build step 12): a version names the same bytes for ever (the committed integrity record
and the npm package folder are what the sources make), the console serves an exact version immutably with its integrity.json and answers a partial one with a 302,
a version it does not hold is 410, the element API data the TypeScript declarations are generated from matches the real elements, and the embed API's lifecycle
(Deprecation and Sunset headers, then 410 DRS-8006) follows its configuration."""
import hashlib
import re
from datetime import date

from core import element_api as api
from core import element_dist as dist
from core import element_package as pkg
from test_embed import HOST, make_client, token

RETIRE = "bump VERSION in core/element_dist.py and `version` in web/embed/drishti-elements.js, then: python3 tools/elements_package.py"


def test_a_version_names_the_same_bytes_the_integrity_record_is_committed():
    now = dist.lock_text()
    have = dist.LOCK.read_text(encoding="utf-8")
    assert have == now, f"a module under web/embed/ changed (or the version did): {RETIRE}"


def test_the_npm_package_folder_is_what_the_sources_make():
    for rel, data in pkg.build().items():
        path = dist.PACKAGE / rel
        assert path.is_file() and path.read_bytes() == data, f"{rel} is stale: python3 tools/elements_package.py"
    on_disk = {str(p.relative_to(dist.PACKAGE)) for p in dist.PACKAGE.rglob("*") if p.is_file()}
    assert on_disk == set(pkg.build()), "the package folder holds files the build does not make"


def test_the_entry_reports_the_version_the_console_serves():
    entry = (dist.EMBED / dist.ENTRY).read_text(encoding="utf-8")
    assert f"version: '{dist.VERSION}'" in entry
    assert re.fullmatch(r"\d+\.\d+\.\d+", dist.VERSION)
    import json

    assert json.loads((dist.PACKAGE / "package.json").read_text())["version"] == dist.VERSION


def test_every_module_is_in_the_package_with_its_integrity_and_nothing_else_is_published():
    record = dist.integrity()
    assert dist.ENTRY in record["files"] and "m/report.js" in record["files"] and "m/panel.js" in record["files"]
    for rel, info in record["files"].items():
        data = (dist.EMBED / rel.removeprefix("m/")).read_bytes() if rel != dist.ENTRY else (dist.EMBED / dist.ENTRY).read_bytes()
        assert info["integrity"] == "sha384-" + __import__("base64").b64encode(hashlib.sha384(data).digest()).decode() and info["bytes"] == len(data)
    assert not any("package" in r or r.endswith((".css", ".woff2")) for r in record["files"]), "sheets, fonts and chart code come from the console, not the package"


def test_an_exact_version_is_served_immutably_with_its_integrity_and_a_partial_one_redirects(backend):
    c = make_client(backend)
    h = {"Origin": HOST}
    entry = c.get(f"/embed/v1/elements/{dist.VERSION}/drishti-elements.js", headers=h)
    assert entry.status_code == 200 and entry.headers["cache-control"] == "public, max-age=31536000, immutable" and entry.headers["access-control-allow-origin"] == HOST
    assert entry.headers["etag"] == '"' + dist.integrity()["files"][dist.ENTRY]["integrity"] + '"'
    module = c.get(f"/embed/v1/elements/{dist.VERSION}/m/report.js", headers=h)
    assert module.status_code == 200 and module.headers["cache-control"].endswith("immutable") and "DrishtiReport" in module.text
    record = c.get(f"/embed/v1/elements/{dist.VERSION}/integrity.json", headers=h)
    assert record.status_code == 200 and record.json() == dist.integrity() and record.text == dist.LOCK.read_text(encoding="utf-8")
    major, minor = dist.VERSION.split(".")[0], ".".join(dist.VERSION.split(".")[:2])
    for alias in (major, minor):
        r = c.get(f"/embed/v1/elements/{alias}/drishti-elements.js", headers=h, follow_redirects=False)
        assert r.status_code == 302 and r.headers["location"] == f"/embed/v1/elements/{dist.VERSION}/drishti-elements.js", alias
        assert r.headers["cache-control"] == "public, max-age=60" and r.headers["access-control-allow-origin"] == HOST
        assert c.get(f"/embed/v1/elements/{alias}/m/config.js", headers=h).status_code == 200                      # followed to the exact file
    assert c.get(f"/embed/v1/elements/{dist.VERSION}/m/nope.js", headers=h).status_code == 404


def test_a_version_this_console_does_not_hold_is_gone_and_a_bad_one_is_not_found(backend):
    c = make_client(backend)
    old = c.get("/embed/v1/elements/0.9.0/drishti-elements.js", headers={"Origin": HOST})
    assert old.status_code == 410 and old.json()["code"] == "DRS-8006" and dist.VERSION in old.json()["detail"]
    assert c.get("/embed/v1/elements/2/drishti-elements.js", headers={"Origin": HOST}).status_code == 410
    assert c.get("/embed/v1/elements/latest/drishti-elements.js", headers={"Origin": HOST}).status_code == 404


def test_the_hashed_sheet_url_still_works_beside_the_versioned_library(backend):
    from core import element_sheet as es

    c = make_client(backend)
    version = es.committed()[1]["version"]
    assert c.get(f"/embed/v1/elements/{version}/drishti-view.css", headers={"Origin": HOST}).status_code == 200


def test_the_element_api_data_matches_the_real_elements():
    view, report = (dist.EMBED / "element.js").read_text(encoding="utf-8"), (dist.EMBED / "report.js").read_text(encoding="utf-8")
    attrs = lambda src: set(re.findall(r"'([a-z-]+)'", re.search(r"const ATTRS = \[(.*?)\];", src).group(1)))  # noqa: E731
    assert {n for n, _t, _d in api.ELEMENTS["drishti-view"]["attributes"]} == attrs(view) - {"panel"}          # `panel` is the attribute of <drishti-panel>, which the view accepts and ignores
    assert {n for n, _t, _d in api.ELEMENTS["drishti-report"]["attributes"]} == attrs(report)
    for name in api.event_names("drishti-view"):
        assert f"'{name}'" in view, f"<drishti-view> never emits drishti:{name}"
    page = (dist.EMBED / "report-page.js").read_text(encoding="utf-8")
    for name in api.event_names("drishti-report"):
        assert f"'{name}'" in report + page, f"<drishti-report> never emits drishti:{name}"
    about, search = (dist.EMBED / "about.js").read_text(encoding="utf-8"), (dist.EMBED / "search.js").read_text(encoding="utf-8")
    assert {n for n, _t, _d in api.ELEMENTS["drishti-about"]["attributes"]} == attrs(about)
    assert {n for n, _t, _d in api.ELEMENTS["drishti-search"]["attributes"]} == attrs(search)
    for name in api.event_names("drishti-about"):
        assert f"'{name}'" in about, f"<drishti-about> never emits drishti:{name}"
    for name in api.event_names("drishti-search"):
        assert f"'{name}'" in search, f"<drishti-search> never emits drishti:{name}"
    for el, spec in api.ELEMENTS.items():
        src = {"drishti-view": view, "drishti-report": report, "drishti-panel": (dist.EMBED / "panel.js").read_text(encoding="utf-8"), "drishti-about": about, "drishti-search": search}[el]
        assert f"class {spec['cls']} extends" in src, el
        for name, _sig, _doc in spec["methods"]:
            assert re.search(rf"\b{name}\(", src) or el == "drishti-panel", f"{el} has no method {name}"
    entry = (dist.EMBED / dist.ENTRY).read_text(encoding="utf-8")
    assert all(f"./m/{m}.js" in entry for m in ("element", "report", "panel", "about", "search"))


def test_the_declarations_name_every_element_and_event():
    ts = api.typescript()
    for el, spec in api.ELEMENTS.items():
        assert f"'{el}': {spec['cls']};" in ts and f"export class {spec['cls']} extends" in ts
    for ev in ("loaded", "filter-changed", "page-changed", "navigate", "error", "token-needed"):
        assert f"'drishti:{ev}': CustomEvent<" in ts
    assert "interface HTMLElementTagNameMap" in ts and "interface Window { DrishtiElements" in ts
    react = api.react_types()
    assert "'drishti-report': Attrs<DrishtiReport" in react and "'report'?: string" in react
    assert ts.count("{") == ts.count("}") and react.count("{") == react.count("}")


def test_the_lifecycle_announces_a_deprecation_and_a_sunset_and_then_retires_the_major(backend):
    today = date(2026, 10, 11)
    assert dist.lifecycle({}, "1", today) == {"deprecated": False, "sunset": None, "retired": False, "headers": {}, "link": ""}
    life = dist.lifecycle({"lifecycle": {"1": {"deprecation": "2026-01-01", "sunset": "2027-07-01", "link": "https://d.example/migrate"}}}, "1", today)
    assert life["deprecated"] and not life["retired"] and life["headers"]["Sunset"] == "Thu, 01 Jul 2027 00:00:00 GMT" and life["headers"]["Deprecation"].startswith("@17")
    assert life["headers"]["Link"] == '<https://d.example/migrate>; rel="deprecation"; type="text/html"'
    assert not dist.lifecycle({"lifecycle": {"1": {"deprecation": "2027-01-01"}}}, "1", today)["deprecated"], "announced for later"
    assert dist.lifecycle({"lifecycle": {"1": {"sunset": "2026-10-11"}}}, "1", today)["retired"]

    c = make_client(backend)
    h = {"Authorization": f"Bearer {token()}", "Origin": HOST}
    assert "sunset" not in c.get("/embed/v1/views/trade/IRS-47102", headers=h).headers
    c.app.state.embed.settings["lifecycle"] = {"1": {"deprecation": "2020-01-01", "sunset": "2999-01-01"}}
    r = c.get("/embed/v1/views/trade/IRS-47102", headers=h)
    assert r.status_code == 200 and r.headers["sunset"].endswith("GMT") and r.headers["deprecation"].startswith("@") and r.headers["access-control-allow-origin"] == HOST
    assert "sunset" in c.get("/embed/v1/drishti-view.css", headers={"Origin": HOST}).headers
    c.app.state.embed.settings["lifecycle"] = {"1": {"deprecation": "2020-01-01", "sunset": "2021-01-01", "link": "https://d.example/migrate"}}
    gone = c.get("/embed/v1/views/trade/IRS-47102", headers=h)
    assert gone.status_code == 410 and gone.json()["code"] == "DRS-8006" and "retired" in gone.json()["detail"] and gone.headers["access-control-allow-origin"] == HOST


def test_the_developer_guide_holds_the_generated_reference_tables_and_names_every_attribute_and_event():
    guide = (dist.WEB.parent.parent / "docs" / "architecture" / "CLIENT_RENDERER.md").read_text(encoding="utf-8")
    for el in api.ELEMENTS:
        assert api.markdown_tables(el) in guide, f"the guide's table of <{el}> is stale: regenerate it with python3 tools/elements_package.py --docs"
    for name in {n for spec in api.ELEMENTS.values() for n, _t, _d in spec["attributes"]}:
        assert f"`{name}`" in guide, name
    for ev in {e for spec in api.ELEMENTS.values() for e in spec["events"]}:
        assert f"`{ev}`" in guide, ev
    assert dist.VERSION in guide and "integrity.json" in guide and "DRS-8005" in guide
