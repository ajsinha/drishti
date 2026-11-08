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

"""The public-sector module's six feeds (world-development, central-banks, national-statistics, oecd, official-news, gdelt-events) on
drishti-feeds, against their recorded responses (tests/fixtures/publicsector; never the network): each run writes its packs' kinds exactly
as the data contracts say (every required field, the key unique, ids and links by their patterns, the types), one JSONL.gz a dataset
with its manifest and the inbox's .ready, the second run of the same day finds nothing new, a missing FRED key leaves FRED out and the
Bank of Canada in, every source's licence and terms are recorded, and the parsers read what the publishers really serve (RSS 2.0,
RSS 1.0, Atom, a CMS's debug comments before the XML, SDMX-JSON 2.0, GDELT's 58 columns)."""
from __future__ import annotations

import gzip
import json
import re
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
import feedload as L  # noqa: E402
import feedrunner as R  # noqa: E402
import publicsector as PS  # noqa: E402
from gdelt_events import aggregate  # noqa: E402
from oecd import parse_sdmx  # noqa: E402
from official_news import parse_feed, parse_federal_register, text_of, when  # noqa: E402

FIX = HERE / "fixtures" / "publicsector"
AS_OF = date(2026, 11, 6)
NAMES = ["world-development", "central-banks", "national-statistics", "oecd", "official-news", "gdelt-events"]
SELECTION = yaml.safe_load((FIX / "selection.yaml").read_text(encoding="utf-8"))


def run_once(feed: str, root: Path, log=None) -> tuple[FB.FeedRun, dict[str, list[dict]]]:
    """One run as the runner makes it (pull, finish, save the state), answered from the fixtures; the documents written, by dataset."""
    spec = FB.load_spec(feed)
    run = FB.FeedRun(spec, AS_OF, sets=SELECTION.get(feed) or {}, root=root, opener=PS.Fixtures(FIX / feed), log=log or (lambda *_: None),
                     sleep=lambda s: None)              # recorded answers: no pause between requests
    FB.feed_class(spec)(spec).pull(run)
    assert not run.http.failed, run.http.failed
    files = run.finish()
    run.save_state()
    docs = {}
    for f in files:
        with gzip.open(f["path"], "rt", encoding="utf-8") as fh:
            docs[f["dataset"]] = [json.loads(line) for line in fh if line.strip()]
    return run, docs


@pytest.fixture(scope="module", autouse=True)
def fred_key():
    mp = pytest.MonkeyPatch()
    mp.setenv("DRISHTI_FRED_API_KEY", "recorded")         # the fixtures were made without a key: any value asks the same URLs
    yield
    mp.undo()


@pytest.fixture(scope="module")
def pulled(tmp_path_factory):
    out = {}
    for feed in NAMES:
        run, docs = run_once(feed, tmp_path_factory.mktemp(feed))
        out[feed] = (run, docs)
    return out


TYPES = {"text": str, "id": str, "date": str, "timestamp": str, "boolean": bool, "integer": int, "amount": (int, float), "rate": (int, float),
         "record": dict, "record list": list, "text list": list, "id list": list, "list": list}


def _check(doc: dict, fields: list[dict], where: str) -> list[str]:
    bad = []
    for f in fields:
        v = doc.get(f["name"])
        if v is None:
            if f["presence"] == "required":
                bad.append(f"{where}.{f['name']}: required, missing")
            continue
        want = TYPES.get(f["type"])
        if (want and not isinstance(v, want)) or (f["type"] in ("amount", "rate", "integer") and isinstance(v, bool)):
            bad.append(f"{where}.{f['name']}: {type(v).__name__} is not {f['type']}")
        if f.get("pattern") and isinstance(v, str) and not re.search(f["pattern"], v):
            bad.append(f"{where}.{f['name']}: {v} does not match {f['pattern']}")
        if f.get("enum") and v not in f["enum"]:
            bad.append(f"{where}.{f['name']}: {v} not in {f['enum']}")
    return bad


@pytest.mark.parametrize("feed", NAMES)
def test_every_document_follows_its_contract(pulled, feed):
    run, docs = pulled[feed]
    for name, ds in run.spec.datasets.items():
        c = yaml.safe_load((ROOT / "config" / "packs" / ds.pack / "contracts" / f"{ds.kind}.contract.yaml").read_text(encoding="utf-8"))
        rows = docs.get(name)
        assert rows, f"{feed}: no {ds.kind}"
        assert c["key"] == [ds.key], ds
        keys = [d[ds.key] for d in rows]
        assert len(keys) == len(set(keys)), f"{ds.kind}: duplicate keys"
        problems = []
        for d in rows:
            problems += _check(d, c["fields"], d[ds.key])
            for path, fs in (c.get("nested") or {}).items():
                inner = d.get(path.removesuffix("[]"))
                for row in (inner if isinstance(inner, list) else [inner] if isinstance(inner, dict) else []):
                    problems += _check(row, fs, f"{d[ds.key]}.{path}")
        assert not problems, "\n".join(problems[:20])


@pytest.mark.parametrize("feed", NAMES)
def test_the_files_carry_what_a_load_needs(pulled, feed, tmp_path):
    run, docs = pulled[feed]
    assert {f["dataset"] for f in run.files} == set(run.spec.datasets)
    man = json.loads((run.folder / AS_OF.isoformat() / "manifest.json").read_text())
    assert man["lake"] == run.spec.lake and man["licence"]["name"] == run.spec.licence.name
    for f in run.files:
        assert Path(f["path"]).name == f"{f['table']}.{AS_OF}.jsonl.gz" and f["rows"] == len(docs[f["dataset"]])
    res = L.load("inbox", run.files, run.spec.lake, run.spec.name, run.run_id, tmp_path / "work", {"inbox": str(tmp_path / "inbox")}, log=lambda *_: None)
    assert res["status"] == "ok"
    ready = [yaml.safe_load(p.read_text()) for p in (tmp_path / "inbox").glob("*.ready")]
    assert len(ready) == 1 and ready[0]["lake"] == run.spec.lake and ready[0]["mode"] == "replace"
    assert ready[0]["rows"] == sum(f["rows"] for f in run.files)


@pytest.mark.parametrize("feed", NAMES)
def test_the_second_run_of_the_day_finds_nothing_new(feed, tmp_path):
    first, docs = run_once(feed, tmp_path)
    assert any(docs.values())
    again, docs2 = run_once(feed, tmp_path)
    assert not docs2, f"{feed}: the same day pulled twice emitted again"


def test_the_runner_runs_a_feed_and_records_it(tmp_path):
    rc, entry = R.run_feed(FB.load_spec("oecd"), R.RunOptions(business_date=AS_OF, load="none", sets=SELECTION["oecd"]),
                           log=lambda *_: None, root=tmp_path, opener=PS.Fixtures(FIX / "oecd"), sleep=lambda s: None)
    assert rc == FB.OK and entry["status"] == "ok" and entry["datasets"]["oecd-country"]["rows"] == 10
    assert entry["lake"] == "worldlake" and not entry["internalOnly"]
    assert json.loads((tmp_path / "ledger.jsonl").read_text().splitlines()[-1])["feed"] == "oecd"


def test_without_the_fred_key_the_bank_of_canada_still_loads(tmp_path, monkeypatch):
    monkeypatch.delenv("DRISHTI_FRED_API_KEY", raising=False)
    logs: list[str] = []
    run, docs = run_once("central-banks", tmp_path, log=logs.append)
    assert {d["SERIES_ID"].split("-")[1] for d in docs["rate-series"]} == {"BOC"}
    assert any("DRISHTI_FRED_API_KEY" in line for line in logs)
    fed = next(d for d in docs["central-bank"] if d["BANK_ID"] == "CBANK-FED")
    assert fed["POLICY_RATE"] is None and fed["SERIES"] == []


def test_every_source_records_its_licence_and_terms():
    for feed in NAMES:
        spec = FB.load_spec(feed)
        assert spec.options["module"] == "economy" and spec.lake in ("worldlake", "newslake") and spec.load == "java", feed
        for src in spec.options["sources"]:
            assert src["licence"] and src["terms"].startswith("https://") and src["attribution"], (feed, src.get("id"))
            assert isinstance(src["internal-only"], bool) and src["redistributable"] is (not src["internal-only"]), (feed, src["id"])
            if src["internal-only"]:
                assert src.get("why-internal"), (feed, src["id"])
        for ds in spec.datasets.values():
            assert (ROOT / "config" / "packs" / ds.pack / "contracts" / f"{ds.kind}.contract.yaml").is_file(), ds
    internal = {s["id"] for s in FB.load_spec("official-news").options["sources"] if s["internal-only"]}
    assert internal == {"BOE", "OSFI", "GAZETTE1", "GAZETTE2"}


def test_internal_only_releases_say_so(pulled):
    rel = pulled["official-news"][1]["press-release"]
    assert {r["INTERNAL_ONLY"] for r in rel if r["RELEASE_SOURCE"] == "PRESS-BOE"} == {True}
    assert {r["INTERNAL_ONLY"] for r in rel if r["RELEASE_SOURCE"] == "PRESS-FED"} == {False}
    assert all(r["PROVENANCE"]["INTERNAL_ONLY"] == r["INTERNAL_ONLY"] for r in rel)


def test_one_publisher_down_never_fails_the_others(tmp_path):
    spec = FB.load_spec("official-news")
    fx = PS.Fixtures(FIX / "official-news")

    def opener(req, timeout=0):
        if "bankofengland" in req.full_url:
            import urllib.error
            raise urllib.error.HTTPError(req.full_url, 404, "gone", {}, None)
        return fx(req, timeout)

    run = FB.FeedRun(spec, AS_OF, sets=SELECTION["official-news"], root=tmp_path, opener=opener, log=lambda *_: None, sleep=lambda s: None)
    FB.feed_class(spec)(spec).pull(run)
    assert not run.http.failed
    files = run.finish()
    with gzip.open(next(f["path"] for f in files if f["dataset"] == "release-source"), "rt") as fh:
        boe = next(json.loads(x) for x in fh if '"PRESS-BOE"' in x)
    assert boe["LAST_PULL"].startswith("failed") and boe["RELEASES"] == 0


def test_a_request_with_no_recording_never_reaches_the_network():
    import urllib.request
    with pytest.raises(AssertionError, match="no recorded response"):
        PS.Fixtures(FIX / "oecd")(urllib.request.Request("https://example.org/never-recorded"))


def test_an_api_key_is_never_in_a_fixture_name_or_url():
    assert "SECRET" not in PS.request_key("GET", "https://api.stlouisfed.org/fred/series?series_id=DGS10&api_key=SECRET", None)
    for f in FIX.rglob("index.json"):
        for e in json.loads(f.read_text()).values():
            assert "api_key=" not in e["url"] or "api_key=KEY" in e["url"], e["url"]


def test_the_feed_parsers_read_what_publishers_serve():
    rss1 = b"""<?xml version="1.0"?><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns="http://purl.org/rss/1.0/"
      xmlns:dc="http://purl.org/dc/elements/1.1/"><item rdf:about="https://b.example/1"><title>Rate held</title>
      <link>https://b.example/1</link><dc:date>2026-10-28T09:45:00-04:00</dc:date></item></rdf:RDF>"""
    atom = b"""<feed xmlns="http://www.w3.org/2005/Atom"><entry><title>A notice</title><link rel="alternate" href="https://a.example/n"/>
      <updated>2026-11-01T12:00:00Z</updated><summary>&lt;p&gt;Short &amp;amp; plain&lt;/p&gt;</summary></entry></feed>"""
    junk = b"<!-- THEME DEBUG -->\n<!-- x -->\n" + b"""<?xml version="1.0"?><rss version="2.0"><channel><item><title>OSFI note</title>
      <link>https://o.example/a</link><description>&lt;!-- debug --&gt;&lt;span&gt;Body&lt;/span&gt;</description>
      <pubDate>Thu, 5 Nov 2026 16:00:00 GMT</pubDate></item></channel></rss>"""
    assert parse_feed(rss1) == [{"title": "Rate held", "link": "https://b.example/1", "published": "2026-10-28T13:45:00Z", "summary": None, "category": None}]
    a = parse_feed(atom)[0]
    assert a["link"] == "https://a.example/n" and a["summary"] == "Short & plain" and a["published"] == "2026-11-01T12:00:00Z"
    o = parse_feed(junk)[0]
    assert o["title"] == "OSFI note" and o["summary"] == "Body" and o["published"] == "2026-11-05T16:00:00Z"
    fr = parse_federal_register(json.dumps({"results": [{"title": "A rule", "html_url": "https://f.example/r", "publication_date": "2026-11-06",
                                                        "abstract": None, "type": "Rule", "agencies": [{"name": "Treasury"}]}]}).encode())
    assert fr[0]["category"] == "Rule · Treasury" and fr[0]["published"] == "2026-11-06T00:00:00Z"
    assert text_of("x" * 500, 10) == "xxxxxxxxx…" and when("not a date") is None


def test_sdmx_json_and_gdelt_rows_are_read_by_position():
    msg = {"data": {"structures": [{"dimensions": {"observation": [
        {"id": "REF_AREA", "values": [{"id": "CAN", "name": "Canada"}]},
        {"id": "TIME_PERIOD", "values": [{"id": "2026-08"}, {"id": "2026-09"}]}]}}],
        "dataSets": [{"observations": {"0:0": [2.1, 0], "0:1": [2.4, 0], "0:2": [9.9]}}]}}
    assert [(r["period"], r["value"]) for r in parse_sdmx(msg)] == [("2026-08", 2.1), ("2026-09", 2.4)]
    cols = [""] * 58
    cols[7], cols[28], cols[29], cols[30], cols[31], cols[34], cols[57] = "CAN", "14", "3", "-6.5", "10", "-4.0", "https://n.example/s"
    agg = aggregate(["\t".join(cols) + "\n", "\t".join(cols[:57]) + "\n"])        # the short row is skipped
    assert agg["CAN"]["events"] == 1 and agg["CAN"]["quad"]["3"] == 1 and agg["CAN"]["tone_w"] == -40.0
    assert agg["CAN"]["top"] == [[10, "https://n.example/s", "14"]]


def test_the_packs_samples_and_readmes_are_what_the_generator_writes():
    import importlib.util
    spec = importlib.util.spec_from_file_location("drishti_publicsector_make", ROOT / "tools" / "packgen" / "publicsector" / "make.py")
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    assert m.main(["--check"]) == 0
