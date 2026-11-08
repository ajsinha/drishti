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

"""The World Bank's WDI has one fetcher, the world-development feed; world-economy reads that feed's output (publicsector.wdi_rows,
tools/realdata/fetch.py Fetcher.wdi) and never the API. The world-development output these tests read is written by the feed itself
from its recorded responses (tests/fixtures/publicsector/world-development; never the network)."""
from __future__ import annotations

import argparse
import json
import shutil
import sys
from datetime import date
from pathlib import Path

import pytest
import yaml

HERE = Path(__file__).resolve().parent
FEEDS = HERE.parent
ROOT = FEEDS.parents[1]
for p in (FEEDS, FEEDS / "sources"):
    if str(p) not in sys.path:
        sys.path.insert(0, str(p))
import feedbase as FB  # noqa: E402
import publicsector as PS  # noqa: E402
import pullkit as B  # noqa: E402
import world_economy as WE  # noqa: E402
from world_development import WorldDevelopmentFeed  # noqa: E402

FIX = HERE / "fixtures" / "publicsector"
RD_FX = ROOT / "tools" / "realdata" / "tests" / "fixtures"
AS_OF = date(2026, 11, 6)
SELECTION = yaml.safe_load((FIX / "selection.yaml").read_text(encoding="utf-8"))["world-development"]
CODES = ["NY.GDP.PCAP.CD", "NY.GDP.MKTP.KD.ZG", "SP.POP.TOTL", "SI.POV.DDAY"]     # headline codes the world-economy pack uses too


def _run(root: Path, sets: dict | None = None) -> FB.FeedRun:
    spec = FB.load_spec("world-development")
    return FB.FeedRun(spec, AS_OF, sets=dict(SELECTION, **(sets or {})), root=root, opener=PS.Fixtures(FIX / "world-development"),
                      log=lambda *_: None, sleep=lambda s: None)


@pytest.fixture(scope="module")
def wd_root(tmp_path_factory) -> Path:
    """<root>/world-development/<date>/: one run of the world-development feed on its recorded responses."""
    root = tmp_path_factory.mktemp("datafeeds")
    run = _run(root)
    WorldDevelopmentFeed(run.spec).pull(run)
    run.finish()
    run.save_state()
    return root


def test_wdi_rows_answer_in_the_world_bank_api_shape(wd_root):
    rows = PS.wdi_rows(CODES, 2015, 2025, root=wd_root)
    assert set(rows) == set(CODES)
    can = [r for r in rows["SP.POP.TOTL"] if r["countryiso3code"] == "CAN"]
    assert can and all(set(r) == {"indicator", "country", "countryiso3code", "date", "value"} for r in can)
    assert can[0]["country"] == {"id": "CA", "value": "Canada"} and can[0]["indicator"]["id"] == "SP.POP.TOTL"
    assert [r["date"] for r in can] == sorted(r["date"] for r in can) and all(2015 <= int(r["date"]) <= 2025 for r in can)
    assert not [r for r in rows["SP.POP.TOTL"] if r["date"] == "2026"]            # the years asked for only
    assert "EN.GHG.CO2.PC.CE.AR5" not in PS.wdi_rows(["EN.GHG.CO2.PC.CE.AR5"], 2015, 2025, root=wd_root)   # not pulled: left out


def test_without_world_development_output_the_run_fails_in_words(tmp_path):
    with pytest.raises(FB.FeedError, match="run feed run world-development first"):
        PS.wdi_rows(CODES, 2015, 2025, root=tmp_path)


def test_world_economy_reads_wdi_from_world_development_never_the_api(wd_root, tmp_path, monkeypatch):
    """Fetcher.worldbank writes wb/<code>.json from the world-development output and the normaliser reads it unchanged: the same
    values, scaled as sources.yaml says. The only World Bank request is the country list (answered from the cache here)."""
    monkeypatch.setattr(FB, "feeds_dir", lambda: wd_root)
    cache = tmp_path / "cache"
    cache.mkdir()
    shutil.copyfile(RD_FX / "wb_country.json", cache / "wb_country.json")
    cfg = WE.N.load_config()
    cfg["indicators"] = [i for i in cfg["indicators"] if i["code"] in CODES + ["EN.GHG.CO2.PC.CE.AR5"]]
    logs: list[str] = []
    asked: list[str] = []
    f = WE.F.Fetcher(cache, cfg, pause=0, log=logs.append)
    real_get = f.get
    f.get = lambda url, dest, binary=False: asked.append(url) or real_get(url, dest, binary)
    f.worldbank()
    assert asked == [f"{cfg['sources']['wdi']['api']}/country?format=json&per_page=400"]
    assert any("EN.GHG.CO2.PC.CE.AR5" in x and "world-development" in x for x in logs)      # not in the output: said, left out
    raw = json.loads((cache / "wb" / "SP.POP.TOTL.json").read_text())
    assert raw[0]["pages"] == 1 and raw[0]["lastupdated"]
    pop = next(i for i in cfg["indicators"] if i["code"] == "SP.POP.TOTL")
    series, vintage = WE.N.wb_series(raw, pop["scale"], {"IND", "USA"})
    hist = {}
    for s in PS._jsonl_gz(PS.wdi_output(wd_root)["series"]):
        if s["SERIES_ID"] == "WDIS-USA-SP_POP_TOTL":
            hist = {h["YEAR"]: WE.N.sig(h["VALUE"] * pop["scale"]) for h in s["HISTORY"] if 2015 <= int(h["YEAR"]) <= 2025}
    assert series["USA"] == hist and vintage == raw[0]["lastupdated"]
    assert f.retrieved["wb/SP.POP.TOTL.json"] == AS_OF.isoformat()


def test_world_economy_without_world_development_output_says_what_to_run(tmp_path, monkeypatch):
    monkeypatch.setattr(FB, "feeds_dir", lambda: tmp_path / "datafeeds")
    cache = tmp_path / "cache"
    cache.mkdir()
    shutil.copyfile(RD_FX / "wb_country.json", cache / "wb_country.json")
    cfg = WE.N.load_config()
    cfg["indicators"] = [i for i in cfg["indicators"] if i["kind"] == "wdi"][:2]
    with pytest.raises(FB.FeedError, match="run feed run world-development first"):
        WE.F.Fetcher(cache, cfg, pause=0, log=lambda *_: None).worldbank()


def test_world_development_pulls_every_wdi_code_of_sources_yaml_back_to_its_first_year(tmp_path):
    run = _run(tmp_path, {"series-from": "tools/realdata/sources.yaml"})
    feed = WorldDevelopmentFeed(run.spec)
    src = yaml.safe_load((ROOT / "tools" / "realdata" / "sources.yaml").read_text(encoding="utf-8"))
    codes = [i["code"] for i in feed._indicators(run)]
    assert {i["code"] for i in src["indicators"] if i["kind"] == "wdi"} <= set(codes) and len(codes) == len(set(codes))
    assert not {i["code"] for i in src["indicators"] if i["kind"] != "wdi"} & set(codes)
    headline = [i["code"] for i in run.spec.options["indicators"]]
    assert codes[:len(headline)] == headline                                                  # the profile's fields first
    first, last = feed._years(run)
    assert first <= src["years"]["first"] and last == AS_OF.year
    spec = FB.load_spec("world-development")
    assert spec.options["series-from"] == "tools/realdata/sources.yaml" and "WLD" in spec.options["aggregates"]


def test_world_development_keeps_the_aggregates_it_is_asked_for(tmp_path):
    run = _run(tmp_path, {"aggregates": ["WLD", "SAS", "HIC"]})
    countries, aggregates = WorldDevelopmentFeed(run.spec)._countries(run)
    assert set(aggregates) == {"WLD", "SAS", "HIC"} and not set(aggregates) & set(countries) and "CAN" in countries
    assert WorldDevelopmentFeed._where(run).endswith(";WLD;SAS;HIC")


def test_a_new_code_or_an_earlier_year_pulls_again(wd_root, tmp_path):
    """Unchanged WDI is skipped only when the last run covered every code and year asked for."""
    shutil.copytree(wd_root / "world-development", tmp_path / "world-development")
    state = json.loads((tmp_path / "world-development" / "state.json").read_text())
    assert state["series-codes"] and state["first-year"] == AS_OF.year - SELECTION["years"] + 1

    class Pulled(Exception):
        pass

    def pulls(sets):
        run = _run(tmp_path, sets)
        feed = WorldDevelopmentFeed(run.spec)
        feed._countries = lambda r: (_ for _ in ()).throw(Pulled())
        try:
            feed.pull(run)
        except Pulled:
            return True
        return False

    assert pulls({}) is False
    assert pulls({"indicators": SELECTION.get("indicators") or FB.load_spec("world-development").options["indicators"] + [
        {"code": "SP.DYN.TFRT.IN", "field": "FERTILITY"}]}) is True
    assert pulls({"years": SELECTION["years"] + 2}) is True


def test_the_world_economy_feed_carries_every_kind_and_no_second_wdi_path():
    spec = FB.load_spec("world-economy")
    assert {"economy", "macro-indicator", "economic-forecast", "labour-market", "fiscal-position"} <= set(spec.datasets)
    assert "--no-wdi" not in (spec.options.get("args") or [])
    assert spec.schedule_at > FB.load_spec("world-development").schedule_at                  # world-development runs first
    a = argparse.Namespace(pause=0, max_age=24.0, offline=False, scope=None, cache=None, since=None, years=None, out=None, dry_run=True,
                           internal_ok=False, no_check=False, sources=[], no_imf=False)
    run = B.Run(WE.PACK, a, B.data_dir(), log=lambda *_: None)
    cfg, widened = WE.plan_cfg(run, WE.SOURCES, True)
    assert not widened and any(i["kind"] == "wdi" for i in cfg["indicators"])
    run.cfg = cfg
    plan = WE.WorldBank().plan(run)
    assert all("source=3" in u for u in plan if "/indicator/" in u) and "world-development" in plan[-1]
    with pytest.raises(SystemExit):
        WE.main(["refresh", "--no-wdi", "--dry-run"])
