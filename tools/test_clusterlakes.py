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


"""A cluster node's synthetic lake (tools/launch/clusterlakes.py): its off: list survives YAML, it ends on the cluster's business today,
and the connectors whose tables it does not hold start switched off (deploy/stack/stack.yaml's drishti-b and drishti-c were DOWN)."""
from __future__ import annotations

import sys
from pathlib import Path
from unittest import mock

TOOLS = Path(__file__).resolve().parent
ROOT = TOOLS.parent
for p in (str(TOOLS), str(TOOLS / "launch")):
    if p not in sys.path:
        sys.path.insert(0, p)
import clusterlakes  # noqa: E402

STACK = (ROOT / "deploy" / "stack" / "stack.yaml").read_text(encoding="utf-8")


def _table(lake: Path, domain: str, table: str, day: str = "2026-11-06") -> None:
    (lake / domain / table / "_delta_log").mkdir(parents=True, exist_ok=True)
    (lake / domain / table / f"business_date={day}").mkdir(exist_ok=True)


def test_a_nodes_off_list_is_read_as_a_list_not_as_a_yaml_boolean():
    """PyYAML reads ``off:`` as false: drishti-b's [trade-murex] never reached the server."""
    nodes = {n["name"]: n for n in clusterlakes.read_stack(STACK)["nodes"]}
    assert nodes["drishti-b"]["off"] == ["trade-murex"] and nodes["drishti-c"]["off"] == ["trade-endur", "trade-calypso"]
    assert clusterlakes.read_stack("a: true\nb: off\nc: yes\n") == {"a": True, "b": "off", "c": "yes"}


def test_the_lake_nodes_switch_off_every_connector_whose_tables_their_lake_does_not_hold(tmp_path):
    """Over the shipped modules and packs: drishti-b's lake holds ENDUR and CALYPSO trades, drishti-c's MUREX; nothing else."""
    nodes = {n["name"]: n for n in clusterlakes.read_stack(STACK)["nodes"]}
    shared, murex = tmp_path / "shared", tmp_path / "murex"
    for lake, tables in ((shared, ("trade_endur", "trade_calypso")), (murex, ("trade_murex",))):
        for t in tables:
            _table(lake, "trading", t)
        _table(lake, "reference", "counterparty")
        _table(lake, "credit", "netting-set")
    lakes = ROOT / "config" / "lakes"
    b = clusterlakes.switched_off(nodes["drishti-b"], shared, None, lakes)
    c = clusterlakes.switched_off(nodes["drishti-c"], murex, None, lakes)
    assert b[0] == "trade-murex" and {"trade-wss", "trade-ici", "trade-swapone"} <= set(b)
    assert not {"trade-endur", "trade-calypso", "trade-pages"} & set(b)
    assert c[:2] == ["trade-endur", "trade-calypso"] and {"risk-store", "exposure-store", "legal-store", "var-results", "trade-wss"} <= set(c)
    assert not {"trade-murex", "trade-pages"} & set(c)
    assert "funds-store" not in b, "a pack the node does not load is not named"
    main = nodes["drishti-main"]
    assert clusterlakes.switched_off(main, None, None, lakes) == list(main.get("off") or []), "the repository's own node: only its off: list"


def test_a_nodes_own_connector_files_count_in_both_forms(tmp_path):
    lake, own, modules, lakes = tmp_path / "lake", tmp_path / "connectors", tmp_path / "modules", tmp_path / "lakes"
    _table(lake, "trading", "trade_endur")
    own.mkdir()
    lakes.mkdir()
    (lakes / "demo.yaml").write_text("lake: demo\nroot: ${DRISHTI_DELTA_ROOT:./data/delta/demo}\n", encoding="utf-8")
    (lakes / "regulators.yaml").write_text("lake: regulators\nroot: ./data/delta/regulators\n", encoding="utf-8")
    (own / "funds-store.yaml").write_text("plugin: delta\nsettings: {lake: demo, lake-tables: 'funds.fund,funds.fund-holder'}\n", encoding="utf-8")
    (own / "gleif-store.yaml").write_text("plugin: delta\nsettings: {root: '${DRISHTI_DELTA_ROOT:./data/delta}', domain: gleif}\n", encoding="utf-8")
    (own / "trade-endur.yaml").write_text("plugin: delta\nsettings: {lake: demo, lake-tables: trading.trade_endur}\n", encoding="utf-8")
    (own / "rules.yaml").write_text("plugin: delta\nsettings: {lake: regulators, lake-tables: rules.rule}\n", encoding="utf-8")
    off = clusterlakes.switched_off({"name": "n", "packs": "trading", "off": ["x"]}, lake, own, lakes, modules)
    assert off == ["x", "funds-store", "gleif-store"], "a lake of its own root (regulators) is not the synthetic lake's business"


def test_the_lake_ends_on_the_clusters_business_today_and_an_older_one_is_built_again(tmp_path):
    stack = clusterlakes.read_stack(STACK)
    assert clusterlakes.wanted_end(stack, stack["lakes"]["shared"]) == stack["settings"]["DRISHTI_BUSINESS_TODAY"]
    assert clusterlakes.wanted_end({}, {"end": "2026-11-08"}) == "2026-11-06", "a Sunday rolls back to the Friday"
    lake = tmp_path / "lakes" / "shared"
    _table(lake, "trading", "trade_endur", "2026-10-16")
    (lake / "trading" / "old-only").mkdir()
    assert clusterlakes.last_day(lake) == "2026-10-16"
    calls = []

    def run(cmd, check, env):
        target = Path(cmd[cmd.index("--lake") + 1])
        calls.append((target.name, cmd[cmd.index("--end") + 1]))
        _table(target, "trading", "trade_endur", cmd[cmd.index("--end") + 1])

    said = []
    with mock.patch.object(clusterlakes.subprocess, "run", run):
        clusterlakes.build_lake(stack, "shared", lake, tmp_path / "tmp", "python", said.append)
        assert calls == [("shared-next", "2026-11-06")] and "it ended on 2026-10-16" in said[0]
        assert clusterlakes.last_day(lake) == "2026-11-06" and not (lake / "trading" / "old-only").exists()
        assert sorted(p.name for p in lake.parent.iterdir()) == ["shared"], "no -next or -old folder is left"
        clusterlakes.build_lake(stack, "shared", lake, tmp_path / "tmp", "python", said.append)
        assert len(calls) == 1, "a lake that ends on the day is kept"
        fresh = tmp_path / "lakes" / "murex"
        clusterlakes.build_lake(stack, "murex", fresh, tmp_path / "tmp", "python", said.append)
        assert calls[-1] == ("murex", "2026-11-06")


def test_start_node_hands_the_off_list_to_the_server(tmp_path):
    import clusterctl
    at = clusterctl.Layout(str(tmp_path / "stack.yaml"), str(tmp_path / "data"))
    at.file.write_text("nodes:\n  - {name: n, port: 18999, packs: trading, off: [trade-murex], args: [--x=y]}\n", encoding="utf-8")
    with mock.patch.object(clusterctl, "at", at), mock.patch.object(clusterctl, "running", lambda n: None), \
            mock.patch.object(clusterctl, "listening", lambda p: False), mock.patch.object(clusterctl, "run_command", return_value=0) as run:
        stack = clusterctl.load_stack()
        assert clusterctl.start_node(stack, stack["nodes"][0], tmp_path / "server.jar")
    args = run.call_args[0][2]
    assert "--server-arg=--drishti.sources.connectors.trade-murex.enabled=false" in args and "--server-arg=--x=y" in args
