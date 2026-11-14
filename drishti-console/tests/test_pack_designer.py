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
"""The Pack designer's server side (core/packdesigner.py): what a workspace takes, how a request becomes a job, and that the old page is gone."""
import pytest

from core import packdesigner as PD

from conftest import CONSOLE

WEB = CONSOLE / "web"


def ws(tmp_path):
    return PD.Workspaces(tmp_path / "pd", CONSOLE.parent / "tools", PD.DesignerLimits(max_files=3))


def test_a_path_is_below_the_workspace_and_a_jsonl_file():
    assert PD.clean_path("folder\\trade.2026-07-08.jsonl.gz") == "folder/trade.2026-07-08.jsonl.gz"
    for bad in ("../x.jsonl", "a/../../b.jsonl", "C:/x.jsonl", "x.csv", "", "a/b/c/d/e/f/g/h.jsonl"):
        with pytest.raises(PD.DesignerError):
            PD.clean_path(bad)


def test_a_workspace_is_the_users_own_and_files_are_counted(tmp_path):
    w = ws(tmp_path)
    wid = w.create("desk\x1fann")
    d = w.path("desk\x1fann", wid)
    with pytest.raises(PD.DesignerError):
        w.path("desk\x1fbob", wid)                      # one name on two servers or two people is two scopes
    with pytest.raises(PD.DesignerError):
        w.path("desk\x1fann", "../../etc")
    for n in ("a", "b", "c"):
        f = w.target(d, f"{n}.jsonl")
        f.write_text("{}\n")
    with pytest.raises(PD.DesignerError):
        w.target(d, "d.jsonl")                          # the file limit
    assert [x["path"] for x in w.files(d)] == ["a.jsonl", "b.jsonl", "c.jsonl"]
    w.remove_file(d, "b.jsonl")
    assert len(w.files(d)) == 2
    w.remove("desk\x1fann", wid)
    assert not d.exists()


def test_a_request_is_checked_and_a_job_needs_files(tmp_path):
    w = ws(tmp_path)
    r = w.request_for({"name": "my-bank", "kind": "trade", "kindPrefix": "my-", "match": "A,B", "key": "ID", "edits": {"s": "sutra: s\n"}})
    assert r["name"] == "my-bank" and r["match"] == "A,B" and r["version"] == "1.0.0" and r["edits"] == {"s": "sutra: s\n"} and r["minDocs"] == 1
    for bad in ({"name": "Bad Name"}, {"name": "ok", "version": "1.0"}, {"name": "ok", "kind": "a b"}, {"name": "ok", "kindPrefix": "A_"}):
        with pytest.raises(PD.DesignerError):
            w.request_for(bad)
    wid = w.create("s")
    with pytest.raises(PD.DesignerError, match="upload at least one"):
        w.start("s", w.path("s", wid), "analyse", r)


def test_the_old_page_is_gone_and_the_designer_is_built_from_elements_only():
    gone = ["templates/build/pack_new.html", "static/js/drishti_pack-new.js", "static/js/drishti_pack-ui.js", "static/js/drishti_pack-profile.js",
            "static/js/drishti_pack-kinds.js", "static/js/drishti_pack-preview.js", "static/js/drishti_pack-read.js", "static/css/drishti_build-pack.css"]
    assert not [g for g in gone if (WEB / g).exists()]
    assert not (CONSOLE / "core" / "packwizard.py").exists()
    page = (WEB / "templates/build/pack_designer.html").read_text(encoding="utf-8")
    js = (WEB / "static/js/drishti_pack-designer.js").read_text(encoding="utf-8") + (WEB / "static/js/drishti_pack-designer-steps.js").read_text(encoding="utf-8")
    assert "<select" not in page and "createElementNS" not in js and ".toFixed(" not in js and "echarts" not in js
    for element in ("FileDrop", "MultiSelect", "Select", "Grid", "CodeEditor", "Switch"):
        assert f"I.{element}" in js or f"G.{element}" in js, element
