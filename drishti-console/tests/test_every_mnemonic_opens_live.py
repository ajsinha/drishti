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
"""Every mnemonic of every pack that is on opens as a bare ``<MNEMONIC> <GO>`` (a list, never a DRS error), the kinds the demo holds data for
show rows, and one record of each opens; ``/forms`` lists every pack of every module that is on and each pack's FORM page opens. A real
server on the demo with the All distribution (the default), the console in front of it; no browser."""
from __future__ import annotations

import html
import json
import os
import re
import urllib.error
import urllib.request
from pathlib import Path
from urllib.parse import quote

import pytest

from wb_live import ROOT, _stack


@pytest.fixture(scope="module")
def live_console(tmp_path_factory):
    """A real server on the demo with every module (the All distribution), its modules/ folder beside its config/, and a console in front."""
    work = tmp_path_factory.mktemp("wb-mnemonics-server")
    (work / "modules").symlink_to(ROOT / "modules")
    lake = os.environ.get("DRISHTI_DEMO_LAKE")             # a demo lake the drill (or you) built: its kinds must then show rows
    if lake and Path(lake).is_dir():
        (work / "data" / "delta").mkdir(parents=True)
        (work / "data" / "delta" / "demo").symlink_to(lake)
    yield from _stack(tmp_path_factory, {}, work, heap="1536m")

#: kinds computed or kept on request, not loaded as demo data: their list may be empty on the demo (they must still open)
MAY_BE_EMPTY = {"var-result", "var-official", "stress-scenario-pnl", "system-basket", "position"}
#: vector-heavy inputs never listed whole (their contract's list: requires): bare, the server refuses with 400 naming the fields to narrow by
NARROWED = {"SPV": "POSITION_ID", "PNLV": "POSITION_ID", "FXS": "CURRENCY", "XPATH": "TRADE_ID"}


def get(url: str):
    try:
        with urllib.request.urlopen(url, timeout=120) as r:
            return r.status, r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")


def packs_on(server: str) -> list[dict]:
    status, body = get(server + "/api/v1/admin/packs")
    assert status == 200, body[:300]
    rows = json.loads(body)
    rows = rows if isinstance(rows, list) else rows.get("packs", [])
    return [p for p in rows if p.get("loaded") and p.get("enabled", True)]


def test_a_bare_mnemonic_opens_a_list_and_a_known_id_its_record(live_console):
    words = {w: p["name"] for p in packs_on(live_console.server) for w in p.get("mnemonics") or []}
    assert {"POS", "NSET", "PORT", "TRD"} <= set(words), sorted(words)
    failed, empty = [], []
    for w in sorted(words):
        status, body = get(f"{live_console.server}/api/v1/live/kinds/{quote(w)}/rows?limit=5")
        if w in NARROWED:                                  # never a scan: refused in words that name a field to give
            if status != 400 or NARROWED[w] not in body:
                failed.append(f"{w}: listed whole, not refused naming {NARROWED[w]}: {status} {body[:160]}")
            continue
        if status != 200 or "DRS-" in body[:200]:
            failed.append(f"{w}: {status} {body[:160]}")
            continue
        page = json.loads(body)
        spec = page.get("set") or {}
        if page.get("total", 0) == 0 and spec.get("kind") not in MAY_BE_EMPTY:
            empty.append(f"{w} ({spec.get('kind')})")
            continue
        rows = page.get("rows") or []
        if rows and isinstance(rows[0], dict):
            key = spec.get("key") or "id"
            rid = rows[0].get(key) or rows[0].get("id")
            if rid:
                st, rec = get(f"{live_console.server}/api/v1/live/{spec['id']}/rows/{quote(str(rid), safe='')}")
                if st != 200:
                    failed.append(f"{w}: the record {rid} did not open: {st} {rec[:160]}")
    assert not failed, "\n".join(failed)
    if os.environ.get("DRISHTI_DEMO_LAKE"):                # no lake built: every mnemonic still opened; with one, each kind with data shows it
        assert not empty, "mnemonics whose demo data is empty: " + ", ".join(empty)


def test_forms_lists_every_pack_of_every_module_that_is_on_and_each_form_opens(live_console):
    on = {p["name"] for p in packs_on(live_console.server)}
    status, index = get(str(live_console) + "/forms")
    assert status == 200
    listed = set(re.findall(r'href="/form/([^"?/#]+)', html.unescape(index)))
    for must in ("core", "market-risk", "trading"):
        assert must in on and must in listed, f"/forms does not list {must}: {sorted(listed)}"
    assert listed <= on
    for name in sorted(listed):
        st, _ = get(f"{live_console}/form/{quote(name)}")
        assert st == 200, f"the form page of {name}: {st}"
