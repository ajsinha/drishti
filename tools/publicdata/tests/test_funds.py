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

"""Tests of tools/publicdata/funds.py on a recorded cache cut from the real SEC data sets (fixtures/funds: Invesco QQQ, the Vanguard
500 Index Fund and the SPDR DJIA trust, 30 holdings each; their N-CEN and risk/return summary rows): the build, an offline widened
refresh into the data folder, the dry run, the refusals, the quarters asked and the polite downloader. No network."""
from __future__ import annotations

import argparse
import gzip
import io
import json
import shutil
import sys
import urllib.error
from datetime import datetime, timezone
from pathlib import Path

import pytest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
import funds as FU  # noqa: E402
import pdbase as B  # noqa: E402

FX = HERE / "fixtures" / "funds"


@pytest.fixture(autouse=True)
def data_dir(tmp_path, monkeypatch):
    d = tmp_path / "data"
    monkeypatch.setenv("DRISHTI_DATA_DIR", str(d))
    return d


@pytest.fixture
def cache(tmp_path):
    c = tmp_path / "cache"
    shutil.copytree(FX, c)
    return c


def ns(cache, **kw):
    base = dict(command="refresh", pause=0, max_age=168.0, offline=True, scope=None, cache=str(cache), since=None, years=None, out=None,
                dry_run=False, internal_ok=False, no_check=False, sources=[], into_pack=False)
    base.update(kw)
    return argparse.Namespace(**base)


def test_the_build_chooses_by_net_assets_and_links_holdings_to_companies(cache):
    M = FU._make()
    docs = M.build(cache, "2026-10-26")
    funds = docs["fund"]
    assert list(funds) == ["VOO", "QQQ", "DIA"]                     # by net assets; VOO is Vanguard 500's ETF share class
    voo, qqq, dia = funds["VOO"], funds["QQQ"], funds["DIA"]
    assert voo["TYPE"] == "Mutual fund with an ETF share class" and "VFIAX" in voo["TICKERS"]
    assert dia["TYPE"] == "ETF (unit investment trust)" and dia["FUND_KEY"] == "CIK0001041130" and dia["INDEX_ID"] == "DJIA"
    assert qqq["INDEX_ID"] == "NDX" and qqq["EXPENSE_RATIO"] is not None and qqq["ANNUAL_RETURNS"]
    assert voo["ADVISER"] and voo["CUSTODIAN"], "the census (N-CEN) facts"
    assert sum(1 for h in qqq["HOLDINGS"] if h.get("COMPANY")) >= 20, "a holding opens its us-equities company"
    assert all(h["PCT"] is not None for h in qqq["HOLDINGS"]) and qqq["HOLDING_COUNT"] == 30
    both = [c for c, d in docs["fund-holder"].items() if {r["FUND"] for r in d["HOLDERS"]} >= {"QQQ", "VOO"}]
    assert both, "a company two of the funds hold lists both"
    assert qqq["VIEWS"][0]["VIEW"] == "LOOK_THROUGH"
    # 30 holdings are under 40% of a fund in us-equities companies: too few to set it against its index (the full build does)
    assert all("VS_INDEX" not in d or d["VS_INDEX"]["WEIGHTING"] in ("market capitalisation computed from SEC data", "equal weights",
                                                                       "share price (the index's own method)")
               or d["VS_INDEX"]["WEIGHTING"].startswith("market capitalisation computed from SEC data, capped")
               for d in funds.values())


def test_an_offline_widened_refresh_writes_a_pack_in_the_data_folder(cache, data_dir, capsys):
    rc = FU.refresh(ns(cache, scope="FUNDS:2,HOLDINGS:20"))
    assert rc == 0, capsys.readouterr().out
    parts = sorted((data_dir / "publicdata" / "funds" / "testdata" / "funds" / "fund").glob("snapshot-*.jsonl.gz"))
    assert parts, "the widened build's snapshot"
    docs = [json.loads(x) for p in parts for x in gzip.open(p, "rt", encoding="utf-8") if x.strip()]
    assert [d["TICKER"] for d in docs] == ["VOO", "QQQ"] and all(len(d["HOLDINGS"]) <= 20 for d in docs)
    assert (data_dir / "publicdata" / "funds" / "packs" / "funds" / "pack.yaml").is_file()
    man = json.loads((data_dir / "publicdata" / "funds" / "manifest.json").read_text(encoding="utf-8"))
    assert set(man["sources"]) == {s.name for s in FU.SOURCES} and man["runs"][-1]["ok"] is True


def test_the_dry_run_names_the_quarters_and_writes_nothing(cache, data_dir, capsys):
    assert FU.refresh(ns(cache, dry_run=True, offline=False)) == 0
    out = capsys.readouterr().out
    assert "form-n-port-data-sets" in out and "form-n-cen-data-sets" in out and "return-summary-data-sets" in out
    assert not (data_dir / "publicdata" / "funds").exists()


def test_refusals(cache):
    with pytest.raises(B.Refusal):
        FU.refresh(ns(cache, scope="TICKERS:5"))
    with pytest.raises(B.Refusal):
        FU.refresh(ns(cache, out=str(B.ROOT / "config" / "packs" / "funds"), scope="FUNDS:3"))
    with pytest.raises(B.Refusal):
        FU.refresh(ns(cache, out="/tmp/elsewhere"))                  # the committed pack is written where it lives


def test_the_quarters_asked_are_the_published_ones_newest_first():
    t = datetime(2026, 10, 26, tzinfo=timezone.utc)
    assert FU._quarters(None, 4, t) == ["2026q3", "2026q2", "2026q1", "2025q4"]
    assert FU._quarters(2026, 4, t) == ["2026q3", "2026q2", "2026q1"]


class _Resp(io.BytesIO):
    def __enter__(self):
        return self

    def __exit__(self, *a):
        return False


def test_the_downloader_streams_with_the_sec_user_agent_and_takes_a_404_as_not_published(tmp_path, cache):
    seen = []

    def opener(req, timeout=0):
        seen.append((req.full_url, req.get_header("User-agent")))
        if "2026q3" in req.full_url:
            raise urllib.error.HTTPError(req.full_url, 404, "Not Found", {}, None)
        return _Resp(b"PK zip bytes")
    run = B.Run("funds", ns(cache, offline=False, max_age=0.0), tmp_path, log=lambda *a: None)
    dl = FU.Downloader(run, opener=opener, sleep=lambda s: None)
    assert dl.get(FU.NPORT.format(q="2026q3"), run.cache.folder / "nport" / "2026q3_nport.zip", missing_ok=True) is None
    got = dl.get(FU.NPORT.format(q="2026q2"), run.cache.folder / "nport" / "2026q2_nport.zip")
    assert got.read_bytes() == b"PK zip bytes" and not dl.failed
    assert seen[0][1] == FU._sec_user_agent() and "@" in seen[0][1]          # the us-equities pack's SEC User-Agent setting
    assert run.cache.index["nport/2026q2_nport.zip"]["bytes"] == len(b"PK zip bytes")
