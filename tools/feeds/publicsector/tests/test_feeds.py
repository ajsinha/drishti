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

"""The public-sector feeds on their recorded fixtures (never the network): each pull writes its packs' kinds exactly as the data
contracts say (every required field, the key unique, ids and links by their patterns, the types), the JSONL.gz and .ready files a load
needs (lake, pack, kind, connector, date, mode replace, key, sha256, rows), the second pull of the same day finds nothing new, a missing
key stops the feed in words, every source's licence is recorded, and the parsers read what the publishers really serve (RSS 2.0, RSS 1.0,
Atom, a CMS's debug comments before the XML, SDMX-JSON 2.0, GDELT's 58 columns)."""
from __future__ import annotations

import gzip
import hashlib
import json
import re
import sys
from pathlib import Path

import pytest
import yaml

HERE = Path(__file__).resolve().parent
FEEDS = HERE.parent
ROOT = FEEDS.parents[2]
sys.path.insert(0, str(FEEDS))
import base  # noqa: E402
import run as feedrun  # noqa: E402
from gdelt_events import aggregate  # noqa: E402
from oecd import parse_sdmx  # noqa: E402
from official_news import parse_feed, parse_federal_register, text_of, when  # noqa: E402

AS_OF = "2026-11-06"
NAMES = list(feedrun.MODULES)
SELECTION = yaml.safe_load((HERE / "selection.yaml").read_text(encoding="utf-8"))


def spec_of(feed: str) -> dict:
    s = base.load_spec(feed)
    s["selection"] = {**(s.get("selection") or {}), **(SELECTION.get(feed) or {})}
    return s


def pull(feed: str, state_dir: Path, state: dict | None = None):
    st = state if state is not None else {}
    st["dir"] = str(state_dir)
    f = feedrun.feed_class(feed)(spec_of(feed), base.Fetch(opener=base.Fixtures(HERE / "fixtures" / feed)), AS_OF, log=lambda *_: None)
    return f.pull(st), st


@pytest.fixture(scope="module")
def pulled(tmp_path_factory):
    return {feed: pull(feed, tmp_path_factory.mktemp(feed))[0] for feed in NAMES}


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
        if want and not isinstance(v, want) or (f["type"] in ("amount", "rate", "integer") and isinstance(v, bool)):
            bad.append(f"{where}.{f['name']}: {type(v).__name__} is not {f['type']}")
        if f.get("pattern") and isinstance(v, str) and not re.search(f["pattern"], v):
            bad.append(f"{where}.{f['name']}: {v} does not match {f['pattern']}")
        if f.get("enum") and v not in f["enum"]:
            bad.append(f"{where}.{f['name']}: {v} not in {f['enum']}")
    return bad


@pytest.mark.parametrize("feed", NAMES)
def test_every_document_follows_its_contract(pulled, feed):
    spec = spec_of(feed)
    for t in spec["tables"]:
        c = yaml.safe_load((ROOT / "config" / "packs" / spec["pack"] / "contracts" / f"{t['kind']}.contract.yaml").read_text(encoding="utf-8"))
        docs = pulled[feed][t["kind"]]
        assert docs, f"{feed}: no {t['kind']}"
        assert c["key"] == [t["key"]], t
        keys = [d[t["key"]] for d in docs]
        assert len(keys) == len(set(keys)), f"{t['kind']}: duplicate keys"
        problems = []
        for d in docs:
            problems += _check(d, c["fields"], d[t["key"]])
            for path, fs in (c.get("nested") or {}).items():
                name = path.rstrip("[]")
                inner = d.get(name)
                for row in (inner if isinstance(inner, list) else [inner] if isinstance(inner, dict) else []):
                    problems += _check(row, fs, f"{d[t['key']]}.{path}")
        assert not problems, "\n".join(problems[:20])


@pytest.mark.parametrize("feed", NAMES)
def test_the_files_carry_what_a_load_needs(pulled, feed, tmp_path):
    spec = spec_of(feed)
    written = base.write_outputs(spec, pulled[feed], tmp_path, AS_OF)
    assert {w["kind"] for w in written} == {t["kind"] for t in spec["tables"]}
    for w in written:
        f = Path(w["file"])
        assert f.name == f"{w['kind']}.{AS_OF}.jsonl.gz"
        ready = yaml.safe_load(Path(str(f) + ".ready").read_text(encoding="utf-8"))
        assert ready["lake"] == spec["lake"] and ready["pack"] == spec["pack"] and ready["kind"] == w["kind"]
        assert ready["business-date"] == AS_OF and ready["mode"] == "replace"
        assert ready["sha256"] == hashlib.sha256(f.read_bytes()).hexdigest()
        lines = gzip.decompress(f.read_bytes()).decode("utf-8").splitlines()
        assert ready["rows"] == len(lines) == len(pulled[feed][w["kind"]])
        assert all(json.loads(x)[ready["key"]] for x in lines)          # one JSON object a line, keyed


def test_a_rerun_of_the_day_writes_the_same_bytes(pulled, tmp_path):
    a = base.write_outputs(spec_of("oecd"), pulled["oecd"], tmp_path / "a", AS_OF)
    b = base.write_outputs(spec_of("oecd"), pulled["oecd"], tmp_path / "b", AS_OF)
    assert [w["sha256"] for w in a] == [w["sha256"] for w in b]


@pytest.mark.parametrize("feed", NAMES)
def test_the_second_pull_finds_nothing_new(feed, tmp_path):
    docs, state = pull(feed, tmp_path)
    assert any(docs.values())
    state["lastRun"] = AS_OF
    again, _ = pull(feed, tmp_path, state)
    assert not any(again.values()), f"{feed}: the same day pulled twice wrote again"


def test_every_source_records_its_licence_and_terms():
    for feed in NAMES:
        s = base.load_spec(feed, env={})
        assert s["module"] == "publicsector" and s["lake"] in ("worldlake", "newslake") and s["schedule"]["cadence"] == "daily", feed
        for src in s["sources"]:
            assert src["licence"] and src["terms"].startswith("https://") and src["attribution"], (feed, src.get("id"))
            assert isinstance(src["internal-only"], bool) and src["redistributable"] is (not src["internal-only"]), (feed, src["id"])
            if src["internal-only"]:
                assert src.get("why-internal"), (feed, src["id"])
    internal = {src["id"] for src in base.load_spec("official-news", env={})["sources"] if src["internal-only"]}
    assert internal == {"BOE", "OSFI", "GAZETTE1", "GAZETTE2"}


def test_internal_only_releases_say_so(pulled):
    rel = pulled["official-news"]["press-release"]
    assert {r["INTERNAL_ONLY"] for r in rel if r["RELEASE_SOURCE"] == "PRESS-BOE"} == {True}
    assert {r["INTERNAL_ONLY"] for r in rel if r["RELEASE_SOURCE"] == "PRESS-FED"} == {False}
    assert all(r["PROVENANCE"]["INTERNAL_ONLY"] == r["INTERNAL_ONLY"] for r in rel)


def test_a_missing_key_stops_the_feed_in_words(monkeypatch, tmp_path, capsys):
    monkeypatch.delenv("DRISHTI_FRED_API_KEY", raising=False)
    rc = feedrun.main(["run", "central-banks", "--state-dir", str(tmp_path), "--out", str(tmp_path / "out")])
    assert rc == 3 and "DRISHTI_FRED_API_KEY" in capsys.readouterr().out
    assert not (tmp_path / "out").exists()


def test_the_command_line_writes_from_fixtures(tmp_path, capsys):
    rc = feedrun.main(["run", "national-statistics", "--fixtures", str(HERE / "fixtures"), "--selection", str(HERE / "selection.yaml"),
                       "--state-dir", str(tmp_path / "st"), "--out", str(tmp_path / "out"), "--as-of", AS_OF])
    assert rc == 0
    assert (tmp_path / "out" / f"statistics-series.{AS_OF}.jsonl.gz.ready").is_file()
    state = json.loads((tmp_path / "st" / "national-statistics" / "state.json").read_text())
    assert state["lastRun"] == AS_OF and state["vectors"]
    rc = feedrun.main(["run", "national-statistics", "--fixtures", str(HERE / "fixtures"), "--selection", str(HERE / "selection.yaml"),
                       "--state-dir", str(tmp_path / "st"), "--out", str(tmp_path / "out2"), "--as-of", AS_OF])
    assert rc == 0 and "nothing new" in capsys.readouterr().out and not (tmp_path / "out2").exists()


def test_a_request_with_no_recording_never_reaches_the_network(tmp_path):
    fx = base.Fixtures(HERE / "fixtures" / "oecd")
    f = base.Fetch(opener=fx)
    with pytest.raises(base.FeedError, match="no recorded response"):
        f.raw("https://example.org/never-recorded")


def test_an_api_key_is_never_in_a_fixture_name_or_a_log():
    assert "SECRET" not in base.request_key("GET", "https://api.stlouisfed.org/fred/series?series_id=DGS10&api_key=SECRET", None)
    assert base.safe_url("https://x/y?series_id=A&api_key=SECRET") == "https://x/y?series_id=A&api_key=***"
    for f in (HERE / "fixtures").rglob("index.json"):
        assert "api_key=KEY" in f.read_text() or "api_key" not in f.read_text(), f


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
        "dataSets": [{"observations": {"0:0": [2.1, 0], "0:1": [2.4, 0], "0:2": [None]}}]}}
    rows = parse_sdmx(msg)
    assert [(r["period"], r["value"]) for r in rows] == [("2026-08", 2.1), ("2026-09", 2.4)]
    cols = [""] * 58
    cols[7], cols[28], cols[29], cols[30], cols[31], cols[34], cols[57] = "CAN", "14", "3", "-6.5", "10", "-4.0", "https://n.example/s"
    agg = aggregate(["\t".join(cols) + "\n", "\t".join(cols[:57]) + "\n"])        # the short row is skipped
    assert agg["CAN"]["events"] == 1 and agg["CAN"]["quad"]["3"] == 1 and agg["CAN"]["tone_w"] == -40.0
    assert agg["CAN"]["top"] == [[10, "https://n.example/s", "14"]]


def test_the_packs_samples_and_readmes_are_what_the_generator_writes():
    mk = ROOT / "tools" / "packgen" / "publicsector" / "make.py"
    sys.path.insert(0, str(mk.parent))
    import importlib.util
    spec = importlib.util.spec_from_file_location("drishti_publicsector_make", mk)
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    assert m.main(["--check"]) == 0
