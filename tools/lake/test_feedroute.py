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
"""tools/lake/feedroute.py: the .ready file as the command-line loaders read it, with the same keys as the data inbox (DATA_LOAD_RULES.md
section 5): one list of keys, compared with drishti-dataload's ReadyNote.KEYS; ``lake:`` read and checked; a command-line --lake wins."""
import argparse
import pathlib
import re
import sys

import pytest

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import feedroute  # noqa: E402

ROOT = HERE.parents[1]


def test_the_ready_keys_are_the_inbox_s_keys_in_the_same_order():
    java = (ROOT / "drishti-dataload/src/main/java/com/ash/drishti/dataload/ReadyNote.java").read_text(encoding="utf-8")
    m = re.search(r"KEYS = List\.of\(([^;]*)\);", java)
    assert m, "ReadyNote.KEYS not found"
    assert tuple(re.findall(r'"([^"]+)"', m.group(1))) == feedroute.READY_KEYS


def test_a_ready_file_names_its_lake(tmp_path):
    f = tmp_path / "trades.2026-11-03.jsonl"
    f.write_text("{}\n", encoding="utf-8")
    (tmp_path / "trades.2026-11-03.jsonl.ready").write_text("lake: risklake\nmode: replace\n", encoding="utf-8")
    m = feedroute.read_marker(f)
    assert m["lake"] == "risklake" and m["mode"] == "overwrite-dates"
    (tmp_path / "trades.2026-11-03.jsonl.ready").write_text("lake: Risk Lake\n", encoding="utf-8")
    with pytest.raises(feedroute.RouteError, match="a lake's name"):
        feedroute.read_marker(f)
    (tmp_path / "trades.2026-11-03.jsonl.ready").write_text("lakes: risklake\n", encoding="utf-8")
    with pytest.raises(feedroute.RouteError, match="is not a key"):
        feedroute.read_marker(f)


def test_lake_build_takes_the_ready_lake_and_the_command_line_wins(tmp_path, monkeypatch):
    import build
    lakes = tmp_path / "lakes"
    lakes.mkdir()
    (lakes / "risklake.yaml").write_text("lake: risklake\nformat: delta\n", encoding="utf-8")
    monkeypatch.setenv("DRISHTI_LAKES_DIR", str(lakes))
    src = tmp_path / "in"
    src.mkdir()
    f = src / "trades.2026-11-03.jsonl"
    f.write_text("{}\n", encoding="utf-8")
    (src / "trades.2026-11-03.jsonl.ready").write_text("lake: risklake\n", encoding="utf-8")

    def args(**kw):
        base = dict(src=[str(f)], pack=None, kind="trade", connector=None, table=None, mode=None, as_of=None, lake=None, format="delta", recursive=False)
        base.update(kw)
        return argparse.Namespace(**base)
    a = args()
    build._feed(a)
    assert a.lake == "risklake"
    a = args(lake="demo")
    build._feed(a)
    assert a.lake == "demo"                                             # --lake wins over the .ready file
    (src / "trades.2026-11-03.jsonl.ready").write_text("lake: nolake\n", encoding="utf-8")
    with pytest.raises(build.BuildError, match="has no file"):
        build._feed(args())


def _book(tmp_path):
    """A pack in the lake form, as the shipped trading pack: one kind with a table per source system, no id: and no ingest key."""
    pack = tmp_path / "packs" / "book"
    (pack / "contracts").mkdir(parents=True)
    (pack / "pack.yaml").write_text("""pack: book
kinds: [trade, risk-hierarchy]
connectors:
  trade-endur: {plugin: delta, kinds: [trade], settings: {lake: demo, lake-tables: book.trade_endur}}
  trade-calypso: {plugin: delta, kinds: [trade], settings: {lake: demo, lake-tables: book.trade_calypso}}
  risk: {plugin: delta, kinds: [risk-hierarchy], settings: {lake: demo, lake-tables: book.risk-hierarchy}}
lake:
  tables:
    book.trade_endur: {kind: trade, promote: [TRADE_ID]}
    book.trade_calypso: {kind: trade, promote: [TRADE_ID]}
    book.risk-hierarchy: {promote: [NODE_ID]}
""", encoding="utf-8")
    (pack / "contracts" / "trade.contract.yaml").write_text("kind: trade\nkey:\n- TRADE_ID\n", encoding="utf-8")
    (pack / "contracts" / "risk-hierarchy.contract.yaml").write_text("key: [NODE_ID]\n", encoding="utf-8")
    (pack / "contracts" / "two.contract.yaml").write_text("key: [A, B]\n", encoding="utf-8")
    return pack


def test_a_table_name_resolves_to_its_kind_and_connector_and_underscores_match_hyphens(tmp_path):
    pack = _book(tmp_path)
    t = feedroute.resolve("trade_endur", tmp_path / "packs")
    assert (t.pack.name, t.kind, t.connector, t.table) == ("book", "trade", "trade-endur", "trade_endur")
    assert feedroute.resolve("trade_calypso", tmp_path / "packs", pack).connector == "trade-calypso"
    for name in ("risk_hierarchy", "risk-hierarchy", "RISK_HIERARCHY"):
        r = feedroute.resolve(name, tmp_path / "packs")
        assert (r.kind, r.table) == ("risk-hierarchy", None)
    with pytest.raises(feedroute.RouteError, match="trade_murex: no pack"):
        feedroute.resolve("trade_murex", tmp_path / "packs")


def test_the_kinds_key_comes_from_the_contract(tmp_path):
    import build
    pack = _book(tmp_path)
    assert feedroute.contract_key(pack, "trade") == "TRADE_ID"
    assert feedroute.contract_key(pack, "two") is None                  # two fields are no row id
    assert feedroute.contract_key(pack, "nothing") is None
    d = build.pack_defaults(pack, "trade", "trade-calypso")
    assert d["key"] == "TRADE_ID" and d["table"] == "trade_calypso"
    assert build.pack_defaults(pack, "risk-hierarchy")["key"] == "NODE_ID"
