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
"""The shared read-only servers' guard (docs/architecture/TEST_HARNESS.md, "Shared read-only servers"): what a server writes while it only
serves reads is left out of the comparison, and anything a test writes to the server's data is caught. No server: a folder stands in."""
import wb_live


def test_what_a_reading_server_writes_is_left_out(tmp_path):
    before = wb_live.fingerprint(tmp_path)
    for p in ("data/var/official/risk-results/part-0.parquet", "server.log", "jvm-tmp/duckdb.so", "data/tmp/server/x", "data/logs/a.log", "data/var/hot/2026-11-06/v.bin", "data/cache/c", "x.lock"):
        (tmp_path / p).parent.mkdir(parents=True, exist_ok=True)
        (tmp_path / p).write_text("derived")
    assert wb_live.written(before, wb_live.fingerprint(tmp_path)) == []


def test_a_write_to_the_servers_data_is_caught(tmp_path):
    (tmp_path / "data" / "identity").mkdir(parents=True)
    (tmp_path / "data" / "identity" / "users.db").write_bytes(b"1")
    before = wb_live.fingerprint(tmp_path)
    (tmp_path / "data" / "identity" / "users.db").write_bytes(b"12")                   # changed
    (tmp_path / "data" / "baskets").mkdir()
    (tmp_path / "data" / "baskets" / "mine.json").write_text("{}")                     # added
    (tmp_path / "config" / "connectors").mkdir(parents=True)
    (tmp_path / "config" / "connectors" / "new.yaml").write_text("a: 1")               # a connector file the server wrote
    assert wb_live.written(before, wb_live.fingerprint(tmp_path)) == ["config/connectors/new.yaml", "data/baskets/mine.json", "data/identity/users.db"]


def _notice(tmp_path, rows):
    import json
    box = tmp_path / "data/fstore/items/collab/inbox/f0.json"
    box.parent.mkdir(parents=True, exist_ok=True)
    box.write_text(json.dumps({"username": "drishti-dev-admin", "rows": rows}))
    for p in ("data/fstore/items/collab/counters/inbox", "data/fstore/items/collab/inbox-seq/0000000000000000001"):
        (tmp_path / p).parent.mkdir(parents=True, exist_ok=True)
        (tmp_path / p).write_text("1")


def test_a_platform_alert_the_server_raised_is_left_out_and_any_other_notice_is_caught(tmp_path):
    before = wb_live.fingerprint(tmp_path)
    alert = {"seq": 1, "type": "alert", "kind": "platform", "entityId": "/admin/health#alerts", "panelId": "[warn] the memory budget was squeezed"}
    _notice(tmp_path, [alert])
    assert wb_live.without_platform_alerts(tmp_path, wb_live.written(before, wb_live.fingerprint(tmp_path))) == []
    _notice(tmp_path, [alert, {"seq": 2, "type": "share", "kind": "var", "entityId": "BANK"}])            # a test shared something
    assert wb_live.without_platform_alerts(tmp_path, wb_live.written(before, wb_live.fingerprint(tmp_path))) == [
        "data/fstore/items/collab/counters/inbox", "data/fstore/items/collab/inbox-seq/0000000000000000001", "data/fstore/items/collab/inbox/f0.json"]
