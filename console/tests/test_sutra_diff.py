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
"""A Sutra review's diff: moved panel blocks said in words, only real edits as line diffs, the full diff kept."""
from pathlib import Path

from core import sutra_diff

IRS = (Path(__file__).resolve().parents[2] / "packs" / "finance" / "sutras" / "rates" / "irs-vanilla.v3.sutra.yaml").read_text()

OLD = """rachana: 1
sutra: s-test
version: 3
match: { kind: trade }
panels:
  # the legs come first
  - id: legs
    kind: table
    title: Legs
    rows: $.legs
  - id: cash
    kind: table
    title: Cashflows
    rows: $.cash
  - { id: built, kind: provenance, title: How this view was built }
  - id: dv01
    kind: hbar
    title: DV01 by tenor
    area: right
    rows: $.dv01
keys: { F9: raw }
"""


def _blocks(text):
    """The panel blocks of OLD by id (the comment above legs travels with it), for building the new versions."""
    head, rest = text.split("panels:\n", 1)
    body, tail = rest.split("keys:", 1)
    parts, cur = {}, []
    for line in body.splitlines(keepends=True):
        if line.startswith("  - ") and cur and not cur[-1].startswith("  #"):
            parts[_id(cur)] = "".join(cur)
            cur = []
        cur.append(line)
    parts[_id(cur)] = "".join(cur)
    return head + "panels:\n", parts, "keys:" + tail


def _id(lines):
    first = next(x for x in lines if x.startswith("  - "))
    return first.split("id:", 1)[1].split(",")[0].split()[0].strip()


def _new(order, edit=lambda b: b, version=4):
    head, parts, tail = _blocks(OLD)
    return edit(head.replace("version: 3", f"version: {version}") + "".join(parts[i] for i in order) + tail)


def test_a_moved_block_is_said_in_words_not_shown_as_a_delete_and_an_add():
    d = sutra_diff.review(OLD, _new(["cash", "legs", "built", "dv01"]))
    assert [m["text"] for m in d["moves"]] == ["moved: Cashflows from position 2 to 1"]
    assert [x for x in d["edits"] if x[0] in ("add", "del")] == [("del", "-version: 3"), ("add", "+version: 4")]
    full = [line for kind, line in d["full"] if kind in ("add", "del")]
    assert "+  - id: cash" in full and "-  - id: cash" in full                     # the plain diff, kept: a delete and an add


def test_a_move_to_the_side_column_says_so_and_real_edits_stay_line_diffs():
    def edit(t):
        t = t.replace("    title: Cashflows\n", "    title: Cashflows (USD)\n")              # a real edit in a panel that stays
        return t.replace("    title: Legs\n    rows: $.legs\n", "    title: Legs\n    area: right\n    rows: $.legs\n")
    d = sutra_diff.review(OLD, _new(["cash", "built", "dv01", "legs"], edit))
    assert [m["text"] for m in d["moves"]] == ["moved: Legs from position 1 to 2 (main → side)"]
    m = d["moves"][0]
    assert (m["id"], m["from"], m["to"], m["fromArea"], m["toArea"]) == ("legs", 1, 2, "main", "side")
    changed = [line for kind, line in d["edits"] if kind in ("add", "del")]
    assert changed == ["-version: 3", "+version: 4", "-    title: Cashflows", "+    title: Cashflows (USD)", "+    area: right"]


def test_panels_shifted_by_a_removal_or_a_move_are_not_reported_as_moved():
    d = sutra_diff.review(OLD, _new(["legs", "cash", "dv01"]))                 # built removed: dv01 and the rest keep their order
    assert d["moves"] == [] and d["edits"] == d["full"]
    assert "-  - { id: built, kind: provenance, title: How this view was built }" in [line for _, line in d["edits"]]
    d = sutra_diff.review(OLD, _new(["built", "legs", "cash", "dv01"]))        # one panel up: only it moved, not the two below
    assert [m["text"] for m in d["moves"]] == ["moved: How this view was built from position 3 to 1"]


def test_a_removed_panel_stays_a_deletion_beside_a_move():
    d = sutra_diff.review(OLD, _new(["cash", "legs", "dv01"]))
    assert [m["text"] for m in d["moves"]] == ["moved: Cashflows from position 2 to 1"]
    changed = [line for kind, line in d["edits"] if kind in ("add", "del")]
    assert changed == ["-version: 3", "+version: 4", "-  - { id: built, kind: provenance, title: How this view was built }"]


def test_what_cannot_be_read_as_a_panel_list_gets_the_full_diff():
    flow = "rachana: 1\nsutra: f\nversion: 1\npanels: [ { id: a, kind: kv }, { id: b, kind: kv } ]\n"
    d = sutra_diff.review(flow, flow.replace("version: 1", "version: 2").replace("id: a, kind: kv }, { id: b", "id: b, kind: kv }, { id: a"))
    assert d["moves"] == [] and d["edits"] == d["full"] and d["full"]
    twice = OLD.replace("  - id: cash", "  - id: legs")                       # duplicate ids: not matched by id
    assert sutra_diff.review(twice, _new(["cash", "legs", "built", "dv01"]))["moves"] == []
    assert sutra_diff.review("", "rachana: 1\n")["moves"] == []


def test_a_real_sutra_reordered_as_layout_mode_would_reports_its_moves():
    lines = IRS.splitlines(keepends=True)
    start = next(i for i, x in enumerate(lines) if x.startswith("panels:"))
    stop = next(i for i, x in enumerate(lines) if x.startswith("keys:"))
    items = [i for i in range(start + 1, stop) if lines[i].startswith("  - ")]
    blocks = ["".join(lines[a:b]) for a, b in zip(items, items[1:] + [stop])]
    by_id = {b.split("id:", 1)[1].split(",")[0].split()[0]: b for b in blocks}
    order = ["cashflows", "legs", "leg2", "built", "dv01", "curve", "refs"]
    new = "".join(lines[:start + 1]).replace("version: 3", "version: 4") + "".join(by_id[i] for i in order) + "".join(lines[stop:])
    d = sutra_diff.review(IRS, new)
    assert [m["text"] for m in d["moves"]] == ["moved: Cashflows · Leg 1 from position 2 to 1", "moved: DV01 by tenor (USD) from position 3 to 1"]
    assert [line for kind, line in d["edits"] if kind in ("add", "del")] == ["-version: 3", "+version: 4"]


def test_the_review_page_lists_moves_and_keeps_the_full_diff_behind_a_toggle(client, backend, monkeypatch):
    new = _new(["cash", "legs", "built", "dv01"])

    async def proposal(id_, ident=None):
        return {"id": id_, "name": "s-test", "version": 4, "note": "", "author": "ana", "createdAt": "2026-10-01T09:00:00Z",
                "status": "pending", "newVersion": True, "stale": False, "mayApprove": True, "mayWithdraw": False,
                "text": new, "baseText": "", "liveText": "", "previousText": OLD}
    monkeypatch.setattr(backend, "proposal", proposal, raising=False)
    html = client.get("/build/reviews/P-000042").text
    assert '<ul class="diff-moves"' in html and "moved: Cashflows from position 2 to 1</li>" in html
    edits = html.split('aria-label="Edits besides the moves">', 1)[1].split("</pre>", 1)[0]
    assert '<span class="d-add">+version: 4</span>' in edits and "d-add\">+  - id:" not in edits and "d-del\">-  - id:" not in edits
    assert '<details class="diff-full"><summary>Full line diff' in html
    full = html.split('aria-label="Full line diff">', 1)[1].split("</pre>", 1)[0]
    assert '<span class="d-del">-  - id: cash</span>' in full or '<span class="d-add">+  - id: cash</span>' in full


def test_a_review_without_moves_shows_the_plain_diff_and_no_toggle(client, backend, monkeypatch):
    async def proposal(id_, ident=None):
        return {"id": id_, "name": "s-test", "version": 4, "note": "", "author": "ana", "createdAt": "2026-10-01T09:00:00Z",
                "status": "pending", "newVersion": True, "stale": False, "mayApprove": True, "mayWithdraw": False,
                "text": OLD.replace("version: 3", "version: 4"), "baseText": "", "liveText": "", "previousText": OLD}
    monkeypatch.setattr(backend, "proposal", proposal, raising=False)
    html = client.get("/build/reviews/P-000042").text
    assert "diff-moves" not in html.split("<main", 1)[-1].split("</main>")[0] or '<ul class="diff-moves"' not in html
    assert '<details class="diff-full">' not in html and '<span class="d-add">+version: 4</span>' in html
