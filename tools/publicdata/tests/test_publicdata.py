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

"""Tests of the public-data refresh scripts (tools/publicdata): the shared library (cache, HTTP, manifest, the internal-only
guard), each source's normalise step against recorded responses, dry runs and status. No network, except the one live smoke
test, which runs only with DRISHTI_LIVE_FETCH=1."""
from __future__ import annotations

import argparse
import gzip
import io
import json
import os
import shutil
import sys
import urllib.error
from pathlib import Path

import pytest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
import pdbase as B  # noqa: E402
import official_market_data as MD  # noqa: E402
import news_and_markets as NM  # noqa: E402
import world_economy as WE  # noqa: E402

FX = HERE / "fixtures"
RD_FX = B.ROOT / "tools" / "realdata" / "tests" / "fixtures"


@pytest.fixture(autouse=True)
def data_dir(tmp_path, monkeypatch):
    d = tmp_path / "data"
    monkeypatch.setenv("DRISHTI_DATA_DIR", str(d))
    return d


def ns(**kw):
    base = dict(pause=0, max_age=24.0, offline=False, scope=None, cache=None, since=None, years=None, out=None, dry_run=False,
                internal_ok=False, no_check=False, sources=[])
    base.update(kw)
    return argparse.Namespace(**base)


class FakeResponse(io.BytesIO):
    def __enter__(self):
        return self

    def __exit__(self, *a):
        return False


class Opener:
    """Answers from a dict url -> bytes or an exception, and counts the calls."""

    def __init__(self, answers):
        self.answers, self.calls = answers, []

    def __call__(self, req, timeout=0):
        self.calls.append(req.full_url)
        a = self.answers[req.full_url]
        if isinstance(a, list):
            a = a.pop(0)
        if isinstance(a, Exception):
            raise a
        assert req.get_header("User-agent") == B.UA
        return FakeResponse(a)


# ---------------------------------------------------------------- the shared library

def test_committed_folders():
    assert B.is_committed(B.ROOT / "config" / "packs" / "world-economy")
    assert B.is_committed(B.ROOT / "data" / "testdata")
    assert not B.is_committed(B.ROOT / "data" / "publicdata")
    assert not B.is_committed(Path("/tmp/elsewhere"))


def test_cache_reuses_fresh_files_and_records_checksums(tmp_path):
    cache = B.Cache(tmp_path / "cache")
    op = Opener({"http://x/a": b"one", "http://x/b": b"two"})
    http = B.Http(cache, pause=0, opener=op, sleep=lambda s: None, log=lambda *a: None)
    dest = tmp_path / "cache" / "a.json"
    assert http.get("http://x/a", dest) == b"one" and http.get("http://x/a", dest) == b"one"
    assert op.calls == ["http://x/a"] and http.downloaded == 1 and http.reused == 1
    e = cache.index["a.json"]
    assert e["url"] == "http://x/a" and e["bytes"] == 3 and len(e["sha256"]) == 64
    assert http.get("http://x/b", dest) == b"two"                 # another URL for the same file (a wider --years) is fetched again
    cache.save()
    assert (tmp_path / "cache" / "cache-index.json").is_file()
    assert not [p for p in (tmp_path / "cache").rglob("*") if p.name.startswith(".")]   # visible names only
    assert B.Cache(tmp_path / "cache").index["a.json"]["url"] == "http://x/b"


def test_http_retries_then_gives_up_politely(tmp_path):
    cache = B.Cache(tmp_path)
    err = urllib.error.HTTPError("http://x/r", 503, "busy", None, None)
    op = Opener({"http://x/r": [err, b"ok"], "http://x/missing": urllib.error.HTTPError("http://x/missing", 404, "no", None, None)})
    slept = []
    http = B.Http(cache, pause=0, opener=op, sleep=slept.append, log=lambda *a: None)
    assert http.get("http://x/r", tmp_path / "r") == b"ok" and 2 in slept
    assert http.get("http://x/missing", tmp_path / "m") is None and op.calls.count("http://x/missing") == 1   # a 404 is not retried
    assert http.failed == ["http://x/missing"]


def test_offline_uses_the_cache_only(tmp_path):
    (tmp_path / "old.json").write_text("{}")
    http = B.Http(B.Cache(tmp_path), offline=True, opener=Opener({}), log=lambda *a: None)
    assert http.get("http://x/old", tmp_path / "old.json") == b"{}"
    assert http.get("http://x/new", tmp_path / "new.json") is None


def test_manifest_records_sources_and_files(tmp_path, data_dir):
    out = tmp_path / "build"
    out.mkdir()
    f = out / "fx-spot.jsonl"
    f.write_text('{"PAIR":"FX-EURUSD"}\n')
    m = B.Manifest("market-data")
    m.record(MD.SOURCES[0], ["http://x"], {"rows": 5, "documents": 1}, out)
    m.finish(out, [f], ["refresh"], True)
    saved = json.loads((data_dir / "publicdata" / "market-data" / "manifest.json").read_text())
    assert saved["sources"]["ecb-fx"]["rows"] == 5 and saved["sources"]["ecb-fx"]["licence"].startswith("ECB")
    assert saved["files"][0]["sha256"] == B.sha256(f) and saved["runs"][-1]["ok"] is True
    assert (out / "manifest.json").is_file()                       # a copy beside a build outside the repository


def test_internal_only_sources_are_refused_in_committed_folders(data_dir):
    imf = WE.SOURCES[1]
    with pytest.raises(B.Refusal) as e:
        B.guard_out([imf], B.ROOT / "config" / "packs" / "world-economy", internal_ok=False)
    assert "may not be redistributed" in str(e.value) and "--internal-ok" in str(e.value) and "commercial reuse" in str(e.value)
    B.guard_out([imf], B.ROOT / "config" / "packs" / "world-economy", internal_ok=True)
    B.guard_out([imf], data_dir / "publicdata", internal_ok=False)
    B.guard_out([WE.SOURCES[0]], B.ROOT / "config" / "packs" / "world-economy", internal_ok=False)   # World Bank: committable


# ---------------------------------------------------------------- world-economy

@pytest.fixture()
def we_run(tmp_path):
    c = tmp_path / "cache"
    for src, dest in (("wb_country.json", "wb_country.json"), ("wb_gdp.json", "wb/NY.GDP.MKTP.CD.json"),
                      ("imf_ngdp_rpch.json", "imf/NGDP_RPCH.json"), ("undp_hdi.csv", "undp_hdi.csv"), ("factbook_in.json", "factbook/in.json")):
        (c / dest).parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(RD_FX / src, c / dest)
    cfg = WE.N.load_config()
    cfg["indicators"] = [i for i in cfg["indicators"] if i["code"] in ("NY.GDP.MKTP.CD", "NGDP_RPCH")] + \
                        [i for i in cfg["indicators"] if i["kind"] == "undp"][:1]
    cfg["forecast"] = [f for f in cfg["forecast"] if f["code"] == "NGDP_RPCH"]
    run = B.Run(WE.PACK, ns(cache=str(c)), tmp_path / "out", log=lambda *a: None)
    run.cfg = cfg
    return run


def test_world_economy_sources_normalise_the_recorded_responses(we_run):
    wb, imf, undp, fb = WE.SOURCES
    assert wb.normalise(we_run)["rows"] > 0 and wb.normalise(we_run)["countries"] == 2
    assert imf.normalise(we_run)["rows"] == 3                      # IND 2015, 2025 and 2031 (1990 is before the window)
    assert undp.normalise(we_run)["rows"] > 0
    assert fb.normalise(we_run)["rows"] == 1


def test_world_economy_out_and_guard():
    out, committed = WE.resolve_out(ns(), no_imf=False, widened=False)
    assert not committed and out == B.data_dir() / "publicdata" / "world-economy"
    assert WE.resolve_out(ns(internal_ok=True), no_imf=False, widened=False) == (B.ROOT, True)
    assert WE.resolve_out(ns(internal_ok=True), no_imf=True, widened=False)[1] is False      # no IMF: always the data folder
    with pytest.raises(B.Refusal, match="sources.yaml"):
        WE.resolve_out(ns(out=str(B.ROOT / "tools" / "realdata" / "dataset")), no_imf=False, widened=True)
    with pytest.raises(B.Refusal, match="not where this pack lives"):
        WE.resolve_out(ns(out=str(B.ROOT / "docs")), no_imf=False, widened=False)


def test_world_economy_refuses_the_committed_pack_without_internal_ok(capsys):
    rc = WE.main(["refresh", "--dry-run", "--out", str(B.ROOT / "config" / "packs" / "world-economy")])
    assert rc == 3 and "IMF" in capsys.readouterr().err


def test_world_economy_dry_run_writes_nothing(capsys, data_dir):
    assert WE.main(["refresh", "--dry-run", "--since", "2005", "--scope", "SP.DYN.LE00.IN"]) == 0
    text = capsys.readouterr().out
    assert "years 2005-" in text and "widened" in text and "worldbank" in text and "date=2005:" in text
    assert not (data_dir / "publicdata" / "world-economy").exists()


def test_world_economy_scope_adds_an_indicator(we_run):
    meta = [{"page": 1}, [{"id": "SP.DYN.LE00.IN", "name": "Life expectancy at birth, total (years)", "unit": "",
                           "source": {"id": "2"}, "sourceNote": "Life expectancy at birth indicates ..."}]]
    url = "https://api.worldbank.org/v2/indicator/SP.DYN.LE00.IN?format=json"
    we_run.http = B.Http(we_run.cache, pause=0, opener=Opener({url: json.dumps(meta).encode()}), log=lambda *a: None)
    we_run.scope = ["SP.DYN.LE00.IN:social"]
    cfg = {"sources": we_run.cfg["sources"], "indicators": []}
    WE.WorldBank().widen(we_run, cfg)
    i = cfg["indicators"][0]
    assert (i["key"], i["kind"], i["group"]) == ("sp_dyn_le00_in", "wdi", "social") and i["name"].startswith("Life expectancy")
    with pytest.raises(B.Refusal, match="unknown group"):
        WE.indicator("X.Y", "x", "", "x", "wdi", "weather")


def test_world_economy_status(capsys):
    assert WE.main(["status"]) == 0
    text = capsys.readouterr().out
    assert "worldbank" in text and "CC BY 4.0" in text and "internal only" in text and "factbook" in text


# ---------------------------------------------------------------- news-and-markets

def test_news_history_range():
    run = argparse.Namespace(a=ns(years=3))
    assert NM.history_range(run) == "5y"
    run.a = ns(years=30)
    assert NM.history_range(run) == "max"
    run.a = ns()
    assert NM.history_range(run) == "1y"


def test_news_refuses_the_committed_snapshot_without_internal_ok(capsys):
    assert NM.main(["refresh", "--dry-run", "--out", str(B.ROOT / "data" / "testdata")]) == 3
    err = capsys.readouterr().err
    assert "Yahoo" in err and "publishers" in err


def test_news_dry_run_lists_the_scope(capsys, data_dir):
    assert NM.main(["refresh", "quote", "--dry-run", "--scope", "ZZZZ"]) == 0
    text = capsys.readouterr().out
    assert "QUOTE-ZZZZ" in text and "QUOTE-AAPL" in text and "capture-market" in text
    assert not (data_dir / "publicdata").exists()


def test_news_snapshot_check(tmp_path):
    folder = tmp_path / NM.PACK / "quote"
    folder.mkdir(parents=True)
    docs = [{"QUOTE_ID": "QUOTE-B", "SNAPSHOT": True}, {"QUOTE_ID": "QUOTE-A"}]
    with gzip.open(folder / "snapshot-2026-10-24.jsonl.gz", "wt", encoding="utf-8") as fh:
        fh.write("".join(json.dumps(d) + "\n" for d in docs))
    f, n, problems = NM.snapshot_counts(tmp_path)["quote"]
    assert n == 2 and "ids not unique and sorted" in problems and any("stamped" in p for p in problems)


# ---------------------------------------------------------------- market-data

def test_ecb_fx_documents_and_crosses():
    table = MD.ecb_table((FX / "ecb_hist.csv").read_text())
    assert len(table) == 3 and "CYP" not in table["2026-10-23"]    # N/A dropped
    docs = {d["PAIR"]: d for d in MD.fx_docs(table, ["EURUSD", "USDJPY", "GBPUSD", "USDXXX"], "2000-01-01", MD.SOURCES[0], "u", "t")}
    assert set(docs) == {"FX-EURUSD", "FX-USDJPY", "FX-GBPUSD"}    # an unknown currency is left out
    usd = docs["FX-EURUSD"]
    assert usd["MID"] == 1.1193 and usd["SPOT_DATE"] == usd["AS_OF"] == "2026-10-23" and len(usd["HISTORY"]) == 3
    assert docs["FX-USDJPY"]["MID"] == MD.sig(175.51 / 1.1193)
    assert usd["PROVENANCE"]["LICENCE"].startswith("ECB")


def test_us_treasury_curve():
    rows = MD.ust_rows((FX / "ust_2026.csv").read_text(encoding="utf-8-sig"))
    doc = MD.ust_doc(rows, "2026-01-01", MD.SOURCES[1], "u", "t")
    assert doc["AS_OF"] == "2026-10-23" and doc["TEN_Y"] == 0.05 and doc["SLOPE_2S_10S"] == 31.0
    assert [p["TENOR"] for p in doc["POINTS"]][:3] == ["1M", "2M", "3M"] and len(doc["HISTORY"]) == 3
    assert MD.ust_doc(rows, "2030-01-01", MD.SOURCES[1], "u", "t") is None


def test_overnight_fixings():
    sofr = MD.fixing_doc("FIX-SOFR", "SOFR", "NY Fed", "USD", MD.sofr_obs(json.loads((FX / "sofr.json").read_text())), MD.SOURCES[2], "u", "t")
    assert sofr["AS_OF"] == sofr["FIXINGS"][0]["DATE"] and sofr["FIXINGS"][0]["DATE"] > sofr["FIXINGS"][-1]["DATE"]
    assert sofr["LATEST"] == sofr["FIXINGS"][0]["RATE"] and "VOLUME_BN" in sofr["FIXINGS"][0]
    estr = MD.fixing_doc("FIX-ESTR", "ESTR", "ECB", "EUR", MD.estr_obs((FX / "estr.csv").read_text()), MD.SOURCES[3], "u", "t")
    assert len(estr["FIXINGS"]) == 3 and estr["FIXINGS"][-1]["RATE_PCT"] == 1.928


def test_market_data_write_is_idempotent_and_checked(tmp_path):
    fx, sofr = MD.EcbFx(), MD.Sofr()
    fx.docs = [{"PAIR": "FX-EURUSD", "MID": 1.1}]
    sofr.docs = [{"INDEX_ID": "FIX-SOFR", "LATEST": 0.039}]
    MD.write(tmp_path, [fx, sofr])
    estr = MD.Estr()
    estr.docs = [{"INDEX_ID": "FIX-ESTR", "LATEST": 0.019}]
    files = MD.write(tmp_path, [estr])                             # SOFR, written by an earlier run, is kept
    lines = (tmp_path / "rate-fixing.jsonl").read_text().splitlines()
    assert [json.loads(x)["INDEX_ID"] for x in lines] == ["FIX-ESTR", "FIX-SOFR"] and MD.check(files) == []
    first = (tmp_path / "rate-fixing.jsonl").read_bytes()
    MD.write(tmp_path, [estr])
    assert (tmp_path / "rate-fixing.jsonl").read_bytes() == first
    (tmp_path / "fx-spot.jsonl").write_text('{"PAIR":"FX-A"}\n{"PAIR":"FX-A"}\n')
    assert MD.check([tmp_path / "fx-spot.jsonl"])


def test_market_data_refuses_the_repository_and_dry_runs(capsys, data_dir):
    assert MD.main(["refresh", "--out", str(B.ROOT / "config" / "packs" / "market-data" / "samples")]) == 3
    assert "synthetic" in capsys.readouterr().err
    assert MD.main(["refresh", "--dry-run", "--since", "2024"]) == 0
    text = capsys.readouterr().out
    assert "ecb-fx" in text and "2024-01-01" in text and "daily-treasury-rates.csv/2024" in text
    assert not (data_dir / "publicdata").exists()
    assert MD.main(["status"]) == 0 and "Public domain" in capsys.readouterr().out


def test_market_data_refresh_from_the_cache(tmp_path, data_dir, capsys):
    """A full offline refresh from a cache of the recorded responses: files, manifest, check."""
    import zipfile
    c = tmp_path / "cache"
    (c / "ecb-fx").mkdir(parents=True)
    with zipfile.ZipFile(c / "ecb-fx" / "eurofxref-hist.zip", "w") as z:
        z.writestr("eurofxref-hist.csv", (FX / "ecb_hist.csv").read_text())
    for src, dest in (("sofr.json", "sofr/sofr.json"), ("estr.csv", "estr/estr.csv"), ("ust_2026.csv", "us-treasury/par-yield-2026.csv")):
        (c / dest).parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(FX / src, c / dest)
    out = tmp_path / "out"
    rc = MD.main(["refresh", "--offline", "--since", "2026", "--cache", str(c), "--out", str(out)])
    assert rc == 0, capsys.readouterr().out
    assert sorted(p.name for p in out.iterdir()) == ["fx-spot.jsonl", "ir-curve.jsonl", "manifest.json", "rate-fixing.jsonl"]
    man = json.loads((data_dir / "publicdata" / "market-data" / "manifest.json").read_text())
    assert man["sources"]["sofr"]["rows"] == 3 and len(man["files"]) == 3


@pytest.mark.skipif(os.environ.get("DRISHTI_LIVE_FETCH") != "1", reason="live fetch: set DRISHTI_LIVE_FETCH=1")
def test_live_smoke_estr(tmp_path):
    assert MD.main(["refresh", "estr", "--out", str(tmp_path / "out"), "--pause", "1"]) == 0
    assert (tmp_path / "out" / "rate-fixing.jsonl").read_text().count("FIX-ESTR") == 1
