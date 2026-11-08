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

"""drishti-feeds: the feed file, a run against recorded responses (never the network), incremental state, idempotent re-runs, retries,
the lock, the ledger, the inbox route, the CLI and the schedule output. The java and python routes into a temporary lake are in
test_feed_loads.py (they need the server's jar)."""
from __future__ import annotations

import gzip
import hashlib
import io
import json
import shutil
import subprocess
import sys
import tarfile
import urllib.error
from datetime import date
from pathlib import Path

import pytest
import yaml

HERE = Path(__file__).resolve().parent
sys.path[:0] = [str(HERE.parent), str(HERE.parent / "sources")]
import feedbase as FB  # noqa: E402
import feedrunner as R  # noqa: E402
import feedschedule as S  # noqa: E402

FX = HERE / "fixtures" / "feeds"
RESP = FX / "responses"
ROOT = FB.ROOT


class Recorded:
    """An opener for pullkit.Http serving the recorded responses by the URL's date; `fail` URLs answer 503 a number of times."""

    def __init__(self, fail: dict | None = None):
        self.calls: list[str] = []
        self.headers: list[dict] = []
        self.fail = dict(fail or {})

    def __call__(self, req, timeout=90):
        url = req.full_url
        self.calls.append(url)
        self.headers.append(dict(req.header_items()))
        if self.fail.get(url):
            self.fail[url] -= 1
            raise urllib.error.HTTPError(url, 503, "busy", {}, None)
        day = url.rsplit("date=", 1)[-1]
        f = RESP / f"mappings-{day}.json"
        if not f.is_file():
            raise urllib.error.HTTPError(url, 404, "not found", {}, None)
        return io.BytesIO(f.read_bytes())


@pytest.fixture(autouse=True)
def data(tmp_path, monkeypatch):
    d = tmp_path / "data"
    monkeypatch.setenv("DRISHTI_DATA_DIR", str(d))
    monkeypatch.delenv("SAMPLE_FEED_TOKEN", raising=False)
    monkeypatch.delenv("DRISHTI_DATALOAD_DIR", raising=False)
    return d


@pytest.fixture
def spec():
    return FB.load_spec("sample-mapping", FX)


def run(spec, opener=None, **kw):
    o = R.RunOptions(**kw)
    logs: list[str] = []
    rc, entry = R.run_feed(spec, o, log=logs.append, opener=opener or Recorded(), sleep=lambda s: None)
    return rc, entry, logs


def rows_of(path: Path) -> list[dict]:
    with gzip.open(path, "rt", encoding="utf-8") as fh:
        return [json.loads(x) for x in fh]


# ---------------------------------------------------------------- the feed file

def test_every_shipped_feed_file_parses_and_names_its_class():
    specs = FB.all_specs()
    assert specs, "config/feeds has feed files"
    for s in specs:
        assert issubclass(FB.feed_class(s), FB.Feed), s.name
        assert s.licence.url.startswith("http"), s.name
        assert (ROOT / "config" / "lakes" / f"{s.lake}.yaml").is_file(), f"{s.name}: lake {s.lake} has no config/lakes file"
        for d in s.datasets.values():
            assert (ROOT / "config" / "packs" / d.pack / "pack.yaml").is_file(), f"{s.name}: pack {d.pack}"


def test_internal_only_sources_say_so():
    by = {s.name: s for s in FB.all_specs()}
    for name in ("yahoo-prices", "world-economy", "news-and-markets"):
        if name in by:
            assert by[name].internal_only, name
    if "gleif" in by:
        assert not by["gleif"].internal_only and by["gleif"].lake == "referencelake"


def _doc(**over):
    d = yaml.safe_load((FX / "sample-mapping.yaml").read_text(encoding="utf-8"))
    d.update(over)
    return d


@pytest.mark.parametrize("over, words", [
    ({"keys": {"token": "abc123"}}, "environment variable"),
    ({"keys": {"token": {"env": "plain"}}}, "environment variable"),
    ({"load": "ftp"}, "load 'ftp'"),
    ({"schedule": {"at": "25:00"}}, "HH:MM"),
    ({"licence": {"name": "x", "url": "u", "redistribution": "maybe"}}, "redistribution"),
    ({"licence": {"name": "x", "url": "u", "redistribution": "internal-only", "internal-only": False}}, "internal-only: true"),
    ({"surprise": 1}, "unknown key"),
    ({"datasets": {}}, "datasets"),
    ({"lake": "Bad Lake"}, "not a lake name"),
])
def test_a_bad_feed_file_is_refused_in_words(over, words):
    with pytest.raises(FB.FeedRefused, match=words):
        FB.parse_spec(_doc(**over))


def test_a_key_comes_from_its_environment_variable_only(spec, monkeypatch):
    r = FB.FeedRun(spec, date(2026, 11, 7))
    assert r.key("token") == ""                                 # optional: not set is fine
    monkeypatch.setenv("SAMPLE_FEED_TOKEN", "s3cret")
    assert r.key("token") == "s3cret"
    strict = FB.parse_spec(_doc(keys={"token": "${SAMPLE_FEED_TOKEN_X}"}))
    with pytest.raises(FB.FeedRefused, match="SAMPLE_FEED_TOKEN_X"):
        FB.FeedRun(strict, date(2026, 11, 7)).key("token")


# ---------------------------------------------------------------- a run

def test_a_run_writes_dated_jsonl_gz_with_manifest_state_and_ledger(spec, data):
    rc, entry, logs = run(spec, business_date=date(2026, 11, 7))
    assert rc == 0, logs
    assert entry["status"] == "ok" and entry["dates"] == ["2026-11-05", "2026-11-06", "2026-11-07"]      # first-run-days: 2
    folder = data / "datafeeds" / "sample-mapping"
    f = folder / "2026-11-06" / "lei-mapping.2026-11-06.jsonl.gz"
    assert [r["IDENTIFIER"] for r in rows_of(f)] == ["WABTUS3WXXX", "US0378331005"]
    man = json.loads((folder / "2026-11-06" / "manifest.json").read_text())
    by = {m["table"]: m for m in man["files"]}
    assert by["lei-mapping"]["rows"] == 2 and by["lei-mapping"]["sha256"] == hashlib.sha256(f.read_bytes()).hexdigest()
    assert (folder / "2026-11-07" / "lei-country.2026-11-07.jsonl.gz").is_file()
    assert man["licence"]["name"] == "Test data" and man["lake"] == "feedtestlake"
    state = json.loads((folder / "state.json").read_text())
    assert state["datasets"]["mapping"]["last"] == "2026-11-07" and state["lastBusinessDate"] == "2026-11-07"
    led = R.Ledger().entries()
    assert len(led) == 1 and led[0]["feed"] == "sample-mapping" and led[0]["rows"] == entry["rows"] > 0
    assert logs[-1].split()[1:4] == ["feed", "ok", "exit=0"] and "feed=sample-mapping" in logs[-1]
    assert not (folder / "work").exists() and not (folder / "run.lock").exists()


def test_the_next_run_pulls_only_what_is_new(spec):
    first = Recorded()
    assert run(spec, first, business_date=date(2026, 11, 6))[0] == 0
    second = Recorded()
    rc, entry, _ = run(spec, second, business_date=date(2026, 11, 7))
    assert rc == 0 and [c.rsplit("=", 1)[1] for c in second.calls] == ["2026-11-07"]
    assert entry["dates"] == ["2026-11-07"]


def test_a_rerun_of_the_same_date_is_idempotent(spec, data):
    assert run(spec, business_date=date(2026, 11, 7), since=date(2026, 11, 7))[0] == 0
    f = data / "datafeeds" / "sample-mapping" / "2026-11-07" / "lei-mapping.2026-11-07.jsonl.gz"
    before = f.read_bytes()
    assert run(spec, business_date=date(2026, 11, 7), since=date(2026, 11, 7))[0] == 0
    assert f.read_bytes() == before                               # replaced, never appended: the same bytes
    assert sorted(p.name for p in f.parent.iterdir()) == ["lei-mapping.2026-11-07.jsonl.gz", "manifest.json"]


def test_since_pulls_history_and_resumes_from_the_cache(spec, data):
    rec = Recorded()
    rc, entry, _ = run(spec, rec, business_date=date(2026, 11, 7), since=date(2026, 11, 3))
    assert rc == 0 and len(rec.calls) == 5 and len(entry["dates"]) == 5
    again = Recorded()
    spec.cache_hours = 24
    assert run(spec, again, business_date=date(2026, 11, 7), since=date(2026, 11, 3))[0] == 0
    assert again.calls == []                                      # everything from the download cache


def test_retries_with_back_off_then_succeeds(spec):
    url = "https://mappings.example.test/api/v1/mappings?date=2026-11-07"
    rec = Recorded(fail={url: 2})
    rc, _, logs = run(spec, rec, business_date=date(2026, 11, 7), since=date(2026, 11, 7))
    assert rc == 0 and rec.calls.count(url) == 3
    assert sum("retry" in x for x in logs) == 2


def test_a_failed_pull_fails_the_run_keeps_the_state_and_says_why(spec, data):
    assert run(spec, business_date=date(2026, 11, 6))[0] == 0
    state = (data / "datafeeds" / "sample-mapping" / "state.json").read_text()
    url = "https://mappings.example.test/api/v1/mappings?date=2026-11-07"
    rc, entry, logs = run(spec, Recorded(fail={url: 9}), business_date=date(2026, 11, 7))
    assert rc == FB.FAILED and entry["status"] == "failed" and "run again to resume" in entry["error"]
    assert (data / "datafeeds" / "sample-mapping" / "state.json").read_text() == state
    assert not (data / "datafeeds" / "sample-mapping" / "2026-11-07").exists()
    assert "error=" in logs[-1] and R.Ledger().entries()[-1]["status"] == "failed"


def test_a_second_run_while_one_holds_the_lock_exits_3(spec, data):
    folder = data / "datafeeds" / "sample-mapping"
    with FB.Lock(folder):
        rc, entry, _ = run(spec, business_date=date(2026, 11, 7))
    assert rc == FB.LOCKED and entry["status"] == "locked"


def test_a_stale_lock_is_taken_over(spec, data):
    folder = data / "datafeeds" / "sample-mapping"
    folder.mkdir(parents=True)
    import socket
    (folder / "run.lock").write_text(json.dumps({"pid": 999999999, "host": socket.gethostname(), "since": "x"}))
    assert run(spec, business_date=date(2026, 11, 7))[0] == 0


def test_dry_run_says_what_it_would_fetch_and_changes_nothing(spec, data):
    rec = Recorded()
    rc, entry, logs = run(spec, rec, business_date=date(2026, 11, 7), dry_run=True)
    assert rc == 0 and rec.calls == [] and entry["status"] == "dry-run"
    assert any("mappings?date=2026-11-05" in x for x in logs)
    assert not (data / "datafeeds" / "sample-mapping").exists() and R.Ledger().entries() == []


def test_the_api_key_header_is_sent_and_never_written(spec, data, monkeypatch):
    monkeypatch.setenv("SAMPLE_FEED_TOKEN", "tok-123")
    rec = Recorded()
    assert run(spec, rec, business_date=date(2026, 11, 7), since=date(2026, 11, 7))[0] == 0
    assert rec.headers[0].get("Authorization") == "Bearer tok-123"
    assert rec.headers[0].get("User-agent", "").startswith("Drishti ")
    for f in (data / "datafeeds").rglob("*"):
        if f.is_file() and not f.name.endswith(".gz"):
            assert b"tok-123" not in f.read_bytes(), f


def test_the_user_agent_has_a_contact_and_is_configurable(monkeypatch):
    import pullkit
    assert pullkit.DEFAULT_UA == "Drishti ajsinha@gmail.com"                    # the owner's contact, as the SEC asks (owner, 2026-11-07)
    monkeypatch.setenv("DRISHTI_FEEDS_USER_AGENT", "Bank data team data@bank.example")
    assert pullkit.user_agent() == "Bank data team data@bank.example"


# ---------------------------------------------------------------- the inbox route

def test_inbox_one_file_gets_its_ready_file(spec, data, tmp_path):
    inbox = tmp_path / "dl" / "inbox"
    rc, entry, _ = run(spec, business_date=date(2026, 11, 7), since=date(2026, 11, 7), load="inbox", load_opts={"inbox": str(inbox)})
    assert rc == 0 and entry["load"]["route"] == "inbox"
    data_file = inbox / "lei-mapping.2026-11-07.jsonl.gz"
    ready = yaml.safe_load((inbox / "lei-mapping.2026-11-07.jsonl.gz.ready").read_text())
    assert ready == {"lake": "feedtestlake", "pack": "gleif", "kind": "lei-mapping", "table": "lei-mapping", "business-date": "2026-11-07",
                     "mode": "replace", "sha256": hashlib.sha256(data_file.read_bytes()).hexdigest(), "rows": 3}
    sys.path.insert(0, str(ROOT / "tools" / "lake"))
    import feedroute
    assert set(ready) <= set(feedroute.READY_KEYS)
    assert not list(inbox.glob("*.part"))


def test_inbox_several_tables_and_dates_go_as_one_tar(spec, data, monkeypatch):
    monkeypatch.setenv("DRISHTI_DATALOAD_DIR", str(data / "dataload"))
    rc, _, _ = run(spec, business_date=date(2026, 11, 7), since=date(2026, 11, 5), load="inbox")
    assert rc == 0
    inbox = data / "dataload" / "inbox"
    tar = inbox / "sample-mapping-2026-11-05-to-2026-11-07.tar"
    ready = yaml.safe_load((inbox / (tar.name + ".ready")).read_text())
    assert ready["lake"] == "feedtestlake" and ready["folders"] == {"lei-mapping": "gleif", "lei-country": "gleif"}
    assert "business-date" not in ready and "table" not in ready                     # a tar names its own tables and dates
    with tarfile.open(tar) as t:
        names = sorted(t.getnames())
    assert names == ["lei-country/2026-11-07/lei-country.2026-11-07.jsonl.gz", "lei-mapping/2026-11-05/lei-mapping.2026-11-05.jsonl.gz",
                     "lei-mapping/2026-11-06/lei-mapping.2026-11-06.jsonl.gz", "lei-mapping/2026-11-07/lei-mapping.2026-11-07.jsonl.gz"]
    assert ready["rows"] == 3 + 2 + 4 + 2 and ready["sha256"] == hashlib.sha256(tar.read_bytes()).hexdigest()
    sys.path.insert(0, str(ROOT / "tools" / "lake"))
    import archives
    plan = archives.plan(tar, layout="feed")
    assert sorted(plan.datasets) == ["lei-country", "lei-mapping"]


# ---------------------------------------------------------------- the ledger and status

def test_status_summarises_the_ledger(spec):
    run(spec, business_date=date(2026, 11, 6))
    url = "https://mappings.example.test/api/v1/mappings?date=2026-11-07"
    run(spec, Recorded(fail={url: 9}), business_date=date(2026, 11, 7))
    (row,) = R.Ledger().summary([spec])
    assert row["lastStatus"] == "failed" and row["lastSuccess"] and row["failuresSinceSuccess"] == 1
    assert row["businessDate"] == "2026-11-06" and "503" in (row["lastError"] or "") or "failed" in (row["lastError"] or "")


# ---------------------------------------------------------------- the command line and the schedule

def cli(*args, env=None):
    import os
    e = dict(os.environ, **(env or {}))
    return subprocess.run([sys.executable, str(ROOT / "tools" / "drishti.py"), "feed", *args], capture_output=True, text=True, env=e, cwd=ROOT)


def test_cli_list_status_and_unknown_feed(data):
    p = cli("list", "--config", str(FX))
    assert p.returncode == 0 and "sample-mapping" in p.stdout and "feedtestlake" in p.stdout
    p = cli("status", "--config", str(FX))
    assert p.returncode == 0 and "never" in p.stdout
    p = cli("run", "nope", "--config", str(FX))
    assert p.returncode == FB.REFUSED and "no feed 'nope'" in p.stderr
    p = cli("run", "--config", str(FX))
    assert p.returncode == FB.REFUSED


def test_cli_dry_run_and_source_arguments(data):
    p = cli("run", "sample-mapping", "--config", str(FX), "--dry-run", "--date", "2026-11-07")
    assert p.returncode == 0, p.stderr
    assert "mappings?date=2026-11-07" in p.stdout and p.stdout.strip().splitlines()[-1].split()[1:3] == ["feed", "dry-run"]
    p = cli("run", "--all", "--config", str(FX), "--dry-run", "--", "--x")
    assert p.returncode == FB.REFUSED and "need one feed" in p.stderr


def test_schedule_entries_for_cron_systemd_and_windows(spec):
    cron = S.cron([spec], Path("/opt/drishti"))
    assert "15 7 * * 1-5 /opt/drishti/tools/feeds/feed.sh run sample-mapping --trigger schedule >> $HOME/drishti-feeds-logs/sample-mapping.log 2>&1" in cron
    unit = S.systemd([spec], Path("/opt/drishti"))
    assert "OnCalendar=Mon..Fri *-*-* 07:15:00" in unit and "ExecStart=/opt/drishti/tools/feeds/feed.sh run sample-mapping --trigger schedule" in unit
    assert "Persistent=true" in unit and "Type=oneshot" in unit
    win = S.windows([spec], Path("C:/drishti"))
    assert 'schtasks /Create /F /TN "Drishti feed sample-mapping" /SC WEEKLY /D MON,TUE,WED,THU,FRI /ST 07:15' in win
    assert "C:\\drishti\\tools\\windows\\feed.ps1" in win
    p = cli("schedule", "--cron", "--config", str(FX))
    assert p.returncode == 0 and "sample-mapping" in p.stdout


def test_the_wrappers_exist_and_run_the_feed_command():
    sh = (ROOT / "tools" / "feeds" / "feed.sh").read_text()
    ps = (ROOT / "tools" / "windows" / "feed.ps1").read_text()
    assert "tools/drishti.py feed" in sh and "drishti-console/.venv" in sh
    assert "tools\\drishti.py" in ps and "feed" in ps and "exit $LASTEXITCODE" in ps
    assert not list((ROOT / "tools").glob("publicdata")), "tools/publicdata folded into tools/feeds"


def test_every_feed_has_its_generated_readme():
    import feedreadme
    assert feedreadme.main(["--check"]) == 0, "run python3 tools/feeds/feedreadme.py"
