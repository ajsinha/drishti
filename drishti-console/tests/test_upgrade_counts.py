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

"""The upgrade from the last release keeps every record (docs/architecture/pack-resolution-inventory.md 8.3 and 8.4): one demo lake, the demo
off, this release started twice: on an empty site (fresh) and on what a site of v2.9.11.i_2_3 holds (its generated connector files with their
markers, tools/upgrade_fixture.py, built from the tag's own files). Every kind the bindings serve on the fresh server has the same record count
on the upgraded one, the trades of each source system among them, and no site file is changed by the start. The count table (kind, fresh,
upgraded) is written to $DRISHTI_COUNT_TABLE when set."""
from __future__ import annotations

import hashlib
import json
import os
import subprocess
import sys
import urllib.parse
import urllib.request
from pathlib import Path

import pytest

from wb_live import _stack

ROOT = Path(__file__).resolve().parents[2]
FROM = "v2.9.11.i_2_3"
ONLY = "trading,core,market-data,counterparty-risk"     # the demo packs whose connectors the tag generated (trade per system among them)
SYSTEMS = ("ENDUR", "CALYPSO", "MUREX", "WSS", "SWAPONE", "ICI")


def _get(base: str, path: str):
    with urllib.request.urlopen(base + path, timeout=120) as r:
        return json.loads(r.read().decode("utf-8"))


def _counts(server: str) -> dict[str, object]:
    """Every kind the bindings serve (BOUND or DEMO) and its record count on the newest date (None: the Data view does not open it)."""
    out: dict[str, object] = {}
    for row in _get(server, "/api/v1/admin/bindings").get("kinds", []):
        if row.get("state") not in ("BOUND", "DEMO", "STALE"):
            continue
        try:
            c = _get(server, f"/api/v1/data/{row['kind']}/count")
            out[row["kind"]] = c.get("total", c.get("rows"))
        except Exception:                              # noqa: BLE001  (a kind the Data view does not serve: listed without a count)
            out[row["kind"]] = None
    for system in SYSTEMS:                             # the trades of each source system: the regression of 2026-11-14 lost them all
        q = urllib.parse.quote(f"SOURCE_SYSTEM = {system}")
        try:
            c = _get(server, f"/api/v1/data/trade/count?where={q}")
            out[f"trade[{system}]"] = c.get("rows", c.get("total"))
        except Exception:                              # noqa: BLE001
            out[f"trade[{system}]"] = None
    return out


def _hashes(folder: Path) -> dict[str, str]:
    return {str(f.relative_to(folder)): hashlib.sha256(f.read_bytes()).hexdigest() for f in sorted(folder.rglob("*")) if f.is_file()}


@pytest.fixture(scope="module")
def lake():
    sys.path.insert(0, str(ROOT / "tools" / "drillkit"))
    import lake_cache
    try:
        return lake_cache.demo_lake(ONLY, "today") / "delta"
    except RuntimeError as e:
        pytest.skip("could not build the demo lake: " + str(e))


@pytest.fixture(scope="module")
def site(tmp_path_factory) -> Path:
    out = tmp_path_factory.mktemp("upgrade-site")
    subprocess.run([sys.executable, str(ROOT / "tools" / "upgrade_fixture.py"), "--from", FROM, "--out", str(out / "fixture")], check=True,
                   cwd=ROOT, capture_output=True)
    return out / "fixture"


def test_every_demo_kind_keeps_its_record_count_after_the_upgrade(tmp_path_factory, lake, site):
    env = {"DRISHTI_DELTA_ROOT": str(lake), "DRISHTI_DEMO_ENABLED": "false", "DRISHTI_OFFLINE": "true"}
    gen = _stack(tmp_path_factory, env)
    st = next(gen)
    try:
        fresh = _counts(st.server)
    finally:
        next(gen, None)

    work = tmp_path_factory.mktemp("upgraded-server")
    (work / "data" / "connectors").mkdir(parents=True)
    for f in (site / "data" / "connectors" / "generated").iterdir():        # the markers the earlier server kept beside its data
        (work / "data" / "connectors" / "generated").mkdir(exist_ok=True)
        (work / "data" / "connectors" / "generated" / f.name).write_bytes(f.read_bytes())
    before = _hashes(site / "connectors")
    up_env = {**env, "DRISHTI_CONNECTORS_DIR": str(site / "connectors")}
    if (site / "bindings.yaml").is_file():
        up_env["DRISHTI_BINDINGS_FILE"] = str(site / "bindings.yaml")
    gen = _stack(tmp_path_factory, up_env, work=work)
    st = next(gen)
    try:
        upgraded = _counts(st.server)
    finally:
        next(gen, None)

    table = [{"kind": k, "fresh": fresh.get(k), "upgraded": upgraded.get(k)} for k in sorted(set(fresh) | set(upgraded))]
    if os.environ.get("DRISHTI_COUNT_TABLE"):
        Path(os.environ["DRISHTI_COUNT_TABLE"]).write_text(json.dumps(table, indent=1) + "\n", encoding="utf-8")
    assert _hashes(site / "connectors") == before, "the server changed a site connector file at start"
    assert fresh.get("trade"), f"no trades on a fresh site: {fresh}"
    lost = [r for r in table if (r["fresh"] or 0) != (r["upgraded"] or 0)]
    assert not lost, "record counts differ after the upgrade:\n" + "\n".join(f"  {r}" for r in lost)
