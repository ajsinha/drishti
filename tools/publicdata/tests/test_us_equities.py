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

"""Tests of tools/publicdata/us_equities.py on a recorded two-company cache (Apple and Microsoft, cut from real SEC EDGAR, Wikidata,
GLEIF and Nasdaq Trader files): an offline build, --scope widening through a fake opener, the refusals and the internal-only prices.
No network."""
from __future__ import annotations

import argparse
import gzip
import json
import shutil
import sys
from pathlib import Path

import pytest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
import pdbase as B  # noqa: E402
import us_equities as UE  # noqa: E402

FX = HERE / "fixtures" / "us_equities"
GME = 1326380


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
    base = dict(command="refresh", pause=0, max_age=None, offline=False, scope=None, cache=str(cache), since=None, years=None, out=None,
                dry_run=False, internal_ok=False, no_check=False, sources=[], into_pack=False, server=None, token_file=None, delay=2.0,
                limit=None)
    base.update(kw)
    return argparse.Namespace(**base)


def lines(f: Path) -> list[dict]:
    return [json.loads(x) for x in f.read_text(encoding="utf-8").splitlines() if x.strip()]


def sec_opener(calls):
    """Answers SEC submissions and companyfacts for any CIK (GameStop for 1326380) and an empty GLEIF name search."""
    def answer(url, accept):
        calls.append(url)
        if "/submissions/CIK" in url:
            cik = int(url.rsplit("CIK", 1)[1].split(".")[0])
            gme = cik == GME
            n = 3
            return json.dumps({"cik": str(cik), "name": "GameStop Corp." if gme else "Company " + str(cik), "tickers": ["GME"] if gme else [],
                               "exchanges": ["NYSE"], "sic": "5734", "sicDescription": "Retail-Computer & Software Stores",
                               "fiscalYearEnd": "0201", "addresses": {"business": {"city": "GRAPEVINE", "stateOrCountry": "TX"}},
                               "filings": {"recent": {"accessionNumber": [f"0001326380-26-00000{i}" for i in range(n)],
                                                      "filingDate": ["2026-03-2%d" % i for i in range(n)], "reportDate": [""] * n,
                                                      "form": ["10-K", "8-K", "4"], "primaryDocument": ["d.htm"] * n,
                                                      "primaryDocDescription": [""] * n, "items": ["", "2.02,9.01", ""], "size": [1024] * n}}}).encode()
        if "/companyfacts/CIK" in url:
            usd = lambda v, s, e, instant=False: {**({} if instant else {"start": s}), "end": e, "val": v, "form": "10-K", "filed": "2026-03-25", "accn": "x"}  # noqa: E731
            return json.dumps({"facts": {"us-gaap": {
                "Revenues": {"units": {"USD": [usd(3823000000, "2025-02-02", "2026-01-31"), usd(5273000000, "2024-02-04", "2025-02-01")]}},
                "NetIncomeLoss": {"units": {"USD": [usd(131000000, "2025-02-02", "2026-01-31")]}},
                "Assets": {"units": {"USD": [usd(5880000000, "", "2026-01-31", True)]}},
                "CashAndCashEquivalentsAtCarryingValue": {"units": {"USD": [usd(4760000000, "", "2026-01-31", True)]}}}}}).encode()
        if "api.gleif.org" in url:
            return json.dumps({"data": []}).encode()
        raise AssertionError("unexpected " + url)
    return answer


def test_offline_build_from_the_recorded_cache(tmp_path, cache, data_dir):
    out = tmp_path / "build"
    assert UE.refresh(ns(cache, offline=True, out=str(out)), log=lambda *_: None) == 0
    pack = out / "packs" / "us-equities"
    cos = {d["TICKER"]: d for d in lines(pack / "samples" / "us-company.jsonl")}
    assert set(cos) == {"AAPL", "MSFT"}
    assert cos["AAPL"]["LEI"] and cos["AAPL"]["LISTING"] == "Nasdaq Global Select Market"
    assert (pack / "pack.yaml").is_file() and (pack / "guides" / "us-equities.md").is_file()
    first = {f.name: f.read_bytes() for f in (pack / "samples").glob("*")}
    assert UE.refresh(ns(cache, offline=True, out=str(out)), log=lambda *_: None) == 0
    assert first == {f.name: f.read_bytes() for f in (pack / "samples").glob("*")}          # deterministic
    man = json.loads((data_dir / "publicdata" / "us-equities" / "manifest.json").read_text())
    assert set(man["sources"]) == {"wikidata", "nasdaq", "sec", "gleif"} and man["runs"][-1]["ok"]
    assert (out / "manifest.json").is_file()


def test_scope_widens_into_the_data_folder(cache, data_dir):
    calls, logs = [], []
    a = ns(cache, scope="GME,FILINGS:1,CONCEPT:CASH=CashAndCashEquivalentsAtCarryingValue@USD@instant")
    assert UE.refresh(a, log=logs.append, opener=sec_opener(calls)) == 0
    pack = data_dir / "publicdata" / "us-equities" / "packs" / "us-equities"
    cos = {d["TICKER"]: d for d in lines(pack / "samples" / "us-company.jsonl")}
    assert set(cos) == {"AAPL", "MSFT", "GME"}
    assert all(len(d["FILINGS"]) == 1 for d in cos.values())
    assert cos["GME"]["INDEX_TEXT"] == "Added with --scope (not an index)"
    facts = {d["FACT_ID"] for d in lines(pack / "samples" / "financial-fact.jsonl")}
    assert {"GME-CASH", "GME-REVENUE"} <= facts
    assert any("CIK0001326380" in u for u in calls) and all("sec.gov/files" not in u for u in calls)
    assert UE.F.FILINGS == 20 and "CASH" not in UE.F.CONCEPTS and "SCOPE" not in UE.M.INDEX_NAMES   # put back
    assert not (cache / "sec" / "facts" / f"{GME:010d}.json").exists()                              # the base cache is kept as it was
    idx = json.loads((cache / "cache-index.json").read_text())
    assert any(k.startswith("scoped/") and k.endswith(f"{GME:010d}.json") for k in idx)


def test_widened_builds_never_go_into_the_pack_by_accident(cache, capsys):
    with pytest.raises(B.Refusal, match="--into-pack"):
        UE.refresh(ns(cache, scope="GME", out=str(UE.COMMITTED)), log=lambda *_: None)
    with pytest.raises(B.Refusal, match="not the pack"):
        UE.refresh(ns(cache, out=str(B.ROOT / "docs")), log=lambda *_: None)
    with pytest.raises(B.Refusal, match="not understood"):
        UE.refresh(ns(cache, scope="WHAT:1"), log=lambda *_: None)
    assert UE.main(["refresh", "--scope", "INDEX:X=nope", "--dry-run"]) == 3
    assert "INDEX:<CODE>" in capsys.readouterr().err


def test_resolve_out():
    a = argparse.Namespace(out=None, into_pack=False)
    assert UE.resolve_out(a, False, False) == (UE.COMMITTED, True)
    assert UE.resolve_out(a, True, False)[1] is False
    a.into_pack = True
    assert UE.resolve_out(a, True, False) == (UE.COMMITTED, True)


def test_dry_run_changes_nothing(cache, data_dir):
    logs = []
    assert UE.refresh(ns(cache, dry_run=True, scope="TSLA,YEARS:15"), log=logs.append) == 0
    text = "\n".join(logs)
    assert "widened" in text and "years 15" in text and "15 years" in text
    assert not data_dir.exists()


def test_prices_are_internal_only(cache, data_dir):
    with pytest.raises(B.Refusal, match="--server"):
        UE.refresh(ns(cache, sources=["prices"], scope="AAPL"), log=lambda *_: None)
    with pytest.raises(B.Refusal, match="may not be redistributed"):
        UE.refresh(ns(cache, sources=["prices"], out=str(B.ROOT / "data" / "testdata" / "us-equities"), server="http://x"), log=lambda *_: None)
    got = []

    def getter(kind, id_):
        got.append((kind, id_))
        return {"PRICE_ID": id_, "HISTORY": [{"DATE": "2026-10-23", "CLOSE": 1.0}]}
    a = ns(cache, sources=["prices"], scope="AAPL,MSFT", server="http://x")
    assert UE.refresh(a, log=lambda *_: None, price_getter=getter) == 0
    assert got == [("price-history", "PX-AAPL"), ("price-history", "PX-MSFT")]
    f = next((data_dir / "publicdata" / "us-equities" / "prices" / "price-history").glob("snapshot-*.jsonl.gz"))
    with gzip.open(f, "rt") as fh:
        docs = [json.loads(x) for x in fh]
    assert [d["PRICE_ID"] for d in docs] == ["PX-AAPL", "PX-MSFT"] and all(d["SNAPSHOT"] for d in docs)
    assert UE.refresh(ns(cache, sources=["prices"], scope="AAPL", offline=True), log=lambda *_: None) == 0   # from the cache


def test_status(cache, capsys):
    assert UE.main(["status", "--cache", str(cache)]) == 0
    out = capsys.readouterr().out
    assert "committed pack:" in out and "internal only: prices" in out and "CC0 1.0" in out
