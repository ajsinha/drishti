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
"""tools/lake/stores.py: --store values, the store each connector file reads, and the switch of trade-<system>.yaml."""
import pathlib
import sys

import pytest
import yaml

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import stores  # noqa: E402


def test_store_values_all_first_then_each_system():
    assert stores.parse(None) == {}
    assert stores.parse(["calypso=iceberg"]) == {"calypso": "iceberg"}
    assert stores.parse(["all=iceberg", "endur=delta"]) == {"endur": "delta", "calypso": "iceberg", "murex": "iceberg"}
    assert stores.parse(["endur=delta", "ALL=Iceberg"]) == {"endur": "delta", "calypso": "iceberg", "murex": "iceberg"}
    for bad in ("calypso", "calypso=parquet", "summit=iceberg", "=delta"):
        with pytest.raises(ValueError, match="--store"):
            stores.parse([bad])


def test_where_the_iceberg_tables_go(tmp_path, monkeypatch):
    monkeypatch.delenv("DRISHTI_ICEBERG_ROOT", raising=False)
    assert stores.iceberg_root(tmp_path / "data" / "delta") == tmp_path / "data" / "iceberg"
    monkeypatch.setenv("DRISHTI_ICEBERG_ROOT", str(tmp_path / "ice"))
    assert stores.iceberg_root(tmp_path / "data" / "delta") == (tmp_path / "ice").resolve()


def test_switching_a_system_writes_its_connector_file_and_keeps_the_old_text(tmp_path, monkeypatch):
    monkeypatch.delenv("DRISHTI_ICEBERG_ROOT", raising=False)
    monkeypatch.delenv("DRISHTI_DELTA_ROOT", raising=False)
    folder, data = tmp_path / "connectors", tmp_path / "data"
    lake, ice = data / "delta", data / "iceberg"
    assert stores.current(folder) == {"endur": "delta", "calypso": "delta", "murex": "delta"}     # no files: the packs' Delta templates
    assert "reads delta already" in stores.switch(folder, "calypso", "delta", lake, ice, data, dry=False)
    assert not folder.exists()                                                                     # nothing written for an unchanged store
    assert "would be written" in stores.switch(folder, "calypso", "iceberg", lake, ice, data, dry=True) and not folder.exists()

    assert "now reads iceberg, was delta" in stores.switch(folder, "calypso", "iceberg", lake, ice, data, dry=False)
    doc = yaml.safe_load((folder / "trade-calypso.yaml").read_text(encoding="utf-8"))
    assert doc["plugin"] == "iceberg" and doc["kinds"] == ["trade"]
    s = doc["settings"]
    assert s["engine"] == "native" and s["table"] == {"trade": "trade_calypso"} and s["union"] is True and s["source-name"] == "trade-calypso"
    assert s["root"] == stores.ICEBERG_DEFAULT                     # the default data folder: the portable form the server resolves
    assert "TRADE_ID" in s["layout"]["trade"]["columns"]
    assert stores.current(folder)["calypso"] == "iceberg" and stores.current(folder)["endur"] == "delta"

    assert "now reads delta, was iceberg" in stores.switch(folder, "calypso", "delta", lake, ice, data, dry=False)
    doc = yaml.safe_load((folder / "trade-calypso.yaml").read_text(encoding="utf-8"))
    assert doc["plugin"] == "delta" and doc["settings"]["root"] == stores.DELTA_DEFAULT and "engine" not in doc["settings"]
    kept = list((data / "connectors" / "history").glob("trade-calypso.*.yaml"))
    assert len(kept) == 1 and "plugin: iceberg" in kept[0].read_text(encoding="utf-8")


def test_a_lake_elsewhere_is_named_in_full(tmp_path, monkeypatch):
    monkeypatch.delenv("DRISHTI_ICEBERG_ROOT", raising=False)
    text = stores.connector_text("murex", "iceberg", tmp_path / "x" / "delta", tmp_path / "x" / "iceberg", tmp_path / "data")
    assert yaml.safe_load(text)["settings"]["root"] == str(tmp_path / "x" / "iceberg")


def test_the_risk_domain_switches_its_connector_file_and_back(tmp_path, monkeypatch):
    monkeypatch.delenv("DRISHTI_ICEBERG_ROOT", raising=False)
    monkeypatch.delenv("DRISHTI_DELTA_ROOT", raising=False)
    assert stores.split_domains(["calypso=iceberg", "risk=iceberg"]) == (["calypso=iceberg"], {"risk": "iceberg"})
    with pytest.raises(ValueError, match="risk=delta or risk=iceberg"):
        stores.split_domains(["risk=parquet"])
    folder, data = tmp_path / "connectors", tmp_path / "data"
    lake, ice = data / "delta", data / "iceberg"
    assert stores.current_domain(folder, "risk") == "delta"
    assert "now reads iceberg, was delta" in stores.switch_domain(folder, "risk", "iceberg", lake, ice, data, dry=False)
    doc = yaml.safe_load((folder / "risk-store.yaml").read_text(encoding="utf-8"))
    assert doc["plugin"] == "iceberg" and doc["settings"]["engine"] == "native" and doc["settings"]["domain"] == "risk"
    assert doc["settings"]["root"] == stores.ICEBERG_DEFAULT and "pnl-vector" in doc["kinds"]
    assert doc["settings"]["layout"]["pnl-vector"]["vectors"]          # the vector columns go with the layout
    ex = yaml.safe_load((folder / "exposure-store.yaml").read_text(encoding="utf-8"))   # the CCR paths of the domain switch with it
    assert ex["plugin"] == "iceberg" and "exposure-path" in ex["kinds"] and ex["settings"]["layout"]["exposure-path"]["vectors"]
    assert "reads iceberg already" in stores.switch_domain(folder, "risk", "iceberg", lake, ice, data, dry=False)
    assert "now reads delta, was iceberg" in stores.switch_domain(folder, "risk", "delta", lake, ice, data, dry=False)
    assert not (folder / "risk-store.yaml").exists()                     # the pack's own Delta connector serves again
    assert not (folder / "exposure-store.yaml").exists()
    assert len(list((data / "connectors" / "history").glob("risk-store.*.yaml"))) == 1
    assert len(list((data / "connectors" / "history").glob("exposure-store.*.yaml"))) == 1
