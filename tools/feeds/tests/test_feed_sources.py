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

"""The shipped feeds (config/feeds/) run against their recorded caches (tools/feeds/tests/fixtures/), never the network: each writes
its datasets as dated JSONL.gz under data/datafeeds/<feed>/<date>/, a re-run gives the same bytes, and the internal-only ones refuse a
committed snapshot without --internal-ok. gleif's own feed test is in test_gleif.py."""
from __future__ import annotations

import gzip
import json
import shutil
import sys
import zipfile
from datetime import date
from pathlib import Path

import pytest

HERE = Path(__file__).resolve().parent
sys.path[:0] = [str(HERE.parent), str(HERE.parent / "sources")]
import feedbase as FB  # noqa: E402
import feedrunner as R  # noqa: E402

FX = HERE / "fixtures"
DAY = date(2026, 11, 7)


@pytest.fixture(autouse=True)
def data(tmp_path, monkeypatch):
    d = tmp_path / "data"
    monkeypatch.setenv("DRISHTI_DATA_DIR", str(d))
    return d


def run(name, *args, offline=True, **kw):
    logs = []
    o = R.RunOptions(business_date=DAY, load="none", offline=offline, extra_args=list(args), **kw)
    rc, entry = R.run_feed(FB.load_spec(name), o, log=logs.append)
    return rc, entry, logs


def rows(data, feed, table, day="2026-11-07"):
    with gzip.open(data / "datafeeds" / feed / day / f"{table}.{day}.jsonl.gz", "rt", encoding="utf-8") as fh:
        return [json.loads(x) for x in fh]


def test_us_equities_feed_from_the_recorded_cache(tmp_path, data):
    cache = tmp_path / "cache"
    shutil.copytree(FX / "us_equities", cache)
    rc, entry, logs = run("us-equities", "--cache", str(cache))
    assert rc == 0, logs
    assert {"us-company", "index-membership", "filing", "financial-fact"} <= set(entry["datasets"])
    assert {d["TICKER"] for d in rows(data, "us-equities", "us-company")} == {"AAPL", "MSFT"}
    first = (data / "datafeeds" / "us-equities" / "2026-11-07" / "us-company.2026-11-07.jsonl.gz").read_bytes()
    assert run("us-equities", "--cache", str(cache))[0] == 0
    assert (data / "datafeeds" / "us-equities" / "2026-11-07" / "us-company.2026-11-07.jsonl.gz").read_bytes() == first


def test_funds_feed_from_the_recorded_cache(tmp_path, data):
    cache = tmp_path / "cache"
    shutil.copytree(FX / "funds", cache)
    rc, entry, logs = run("funds", "--cache", str(cache), "--max-age", "100000")
    assert rc == 0, logs
    assert set(entry["datasets"]) == {"fund", "fund-holder", "index-funds"}
    assert {d["TICKER"] for d in rows(data, "funds", "fund")} == {"VOO", "QQQ", "DIA"}


def _market_cache(tmp_path) -> Path:
    c = tmp_path / "cache"
    (c / "ecb-fx").mkdir(parents=True)
    with zipfile.ZipFile(c / "ecb-fx" / "eurofxref-hist.zip", "w") as z:
        z.writestr("eurofxref-hist.csv", (FX / "ecb_hist.csv").read_text())
    for src, dest in (("sofr.json", "sofr/sofr.json"), ("estr.csv", "estr/estr.csv"), ("ust_2026.csv", "us-treasury/par-yield-2026.csv")):
        (c / dest).parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(FX / src, c / dest)
    return c


def test_market_data_feed_keeps_each_documents_own_date(tmp_path, data):
    rc, entry, logs = run("market-data", "--since", "2026", "--cache", str(_market_cache(tmp_path)))
    assert rc == 0, logs
    assert set(entry["datasets"]) == {"fx-spot", "ir-curve", "rate-fixing"}
    assert len(entry["dates"]) >= 1 and all(d <= "2026-11-07" for d in entry["dates"])           # dated: source (each AS_OF)
    day = entry["dates"][-1]
    man = json.loads((data / "datafeeds" / "market-data" / day / "manifest.json").read_text())
    assert man["licence"]["redistribution"] == "attribution" and all(f["rows"] > 0 for f in man["files"])


def test_market_data_has_no_snapshot(data):
    rc, entry, _ = run("market-data", snapshot=True)
    assert rc == FB.REFUSED and "synthetic" in entry["error"]


def test_yahoo_prices_feed_is_internal_only_and_off(tmp_path, data):
    spec = FB.load_spec("yahoo-prices")
    assert spec.internal_only and not spec.enabled
    cache = tmp_path / "cache"
    shutil.copytree(FX / "us_equities", cache)
    (cache / "prices").mkdir()
    (cache / "prices" / "PX-AAPL.json").write_text(json.dumps({"PRICE_ID": "PX-AAPL", "HISTORY": [{"DATE": "2026-11-06", "CLOSE": 1.0}]}))
    rc, entry, logs = run("yahoo-prices", "--cache", str(cache), "--scope", "AAPL")
    assert rc == 0, logs
    assert [d["PRICE_ID"] for d in rows(data, "yahoo-prices", "price-history")] == ["PX-AAPL"]
    assert entry["internalOnly"] is True
    rc, entry, _ = run("yahoo-prices", snapshot=True)
    assert rc == FB.REFUSED


def test_world_economy_feed_emits_each_kind_of_the_built_pack(tmp_path, data):
    """The world-economy build writes a pack whose samples are one undated <kind>.jsonl a kind; each becomes its dataset."""
    import feedbase
    spec = FB.load_spec("world-economy")
    feed = FB.feed_class(spec)(spec)
    r = feedbase.FeedRun(spec, DAY, log=lambda *_: None)
    samples = tmp_path / "samples"
    samples.mkdir()
    (samples / "economy.jsonl").write_text('{"ECONOMY_ID": "ECON-ABW", "NAME": "Aruba"}\n')
    (samples / "catalog.jsonl").write_text('{"x": 1}\n')                                      # not a dataset: left out
    feed.emit_undated(r, samples)
    files = r.finish()
    assert [(f["table"], f["rows"], f["businessDate"]) for f in files] == [("economy", 1, "2026-11-07")]
    assert spec.internal_only and spec.lake == "worldlake"


def test_internal_only_snapshots_need_internal_ok(data):
    rc, entry, _ = run("world-economy", snapshot=True)
    assert rc == FB.REFUSED and "--internal-ok" in entry["error"]


def test_news_and_markets_dry_run_names_its_sources(data):
    rc, entry, logs = run("news-and-markets", dry_run=True)
    assert rc == 0 and entry["status"] == "dry-run"
    assert any("internal only" in x for x in logs)
