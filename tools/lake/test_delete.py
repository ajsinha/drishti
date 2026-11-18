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

"""lake delete (tools/lake/delete.py): the dates asked, the Java writer's answer read, a missing date refused in words, the dry run, the
confirmation (the table's name typed, --yes for scripts, refused without a terminal), the run log, and the data-load signal with status
deleted. The delete itself in the three stores is tested in Java (drishti-lakeload DeleteDatesParityTest); a files store end to end here
when the server jar is built."""
import argparse
import http.server
import json
import pathlib
import sys
import threading
import types

import pytest

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent))
import delete  # noqa: E402
import stores  # noqa: E402

ANSWER = {"table": "risk/scenario-pv", "format": "delta", "dryRun": True, "held": ["2026-11-13", "2026-11-16"], "dropped": 4,
          "dates": [{"date": "2026-11-16", "rows": 4, "dropped": 4}]}


def args(tmp_path, **kw):
    p = argparse.ArgumentParser()
    p.add_argument("--server")
    p.add_argument("--token-file")
    p.add_argument("--user")
    p.add_argument("--json", action="store_true")
    delete.add_arguments(p)
    a = p.parse_args(["--lake", str(tmp_path), "--table", "risk/scenario-pv", "--date", "2026-11-16"])
    for k, v in kw.items():
        setattr(a, k, v)
    return a


@pytest.fixture
def lake(tmp_path, monkeypatch):
    """A Delta-looking table and a fake Java writer that answers ANSWER (dry runs) and records the real run."""
    (tmp_path / "risk" / "scenario-pv" / "_delta_log").mkdir(parents=True)
    calls = []

    def fake(root, fmt, name, spec, dry_run, jar=None, java=None, runner=None):
        calls.append((name, fmt, spec, dry_run))
        return {**ANSWER, "dryRun": dry_run, **({} if dry_run else {"version": 7})}
    monkeypatch.setattr(delete, "plan", fake)
    monkeypatch.setattr(delete, "pack_route", lambda kind: ("market-risk", "risk/scenario-pv"))
    return tmp_path, calls


def test_the_dates_asked():
    assert delete.date_spec(["2026-11-16", "2026-11-17,2026-11-18"], None, None) == ("2026-11-16,2026-11-17,2026-11-18",
                                                                                       ["2026-11-16", "2026-11-17", "2026-11-18"])
    assert delete.date_spec(None, "2026-11-01", "2026-11-10") == ("2026-11-01..2026-11-10", [])
    assert delete.date_spec(None, "2026-11-01", None)[0] == "2026-11-01..9999-12-31"
    for bad in ((["2026-11-31"], None, None), (None, "2026-11-10", "2026-11-01"), (None, None, None)):
        with pytest.raises(delete.Refused):
            delete.date_spec(*bad)


def test_the_java_command(tmp_path, monkeypatch):
    monkeypatch.setattr(stores, "loader_command", lambda data, jar, java, main: ["fake-jvm", main])
    seen = []
    got = delete.plan(str(tmp_path), "iceberg", "risk/scenario-pv", "2026-11-16", True, runner=lambda cmd: seen.append(cmd) or json.dumps(ANSWER))
    assert got == ANSWER
    cmd = seen[0]
    assert cmd[:2] == ["fake-jvm", delete.RETENTION] and "--dry-run" in cmd
    assert cmd[cmd.index("--dates") + 1] == "2026-11-16" and cmd[cmd.index("--format") + 1] == "iceberg"


def test_a_date_not_in_the_table_is_refused_in_words(lake):
    root, calls = lake
    said = []
    assert delete.run(args(root, date=["2026-11-14"], yes=True), said.append) == 1
    assert said[-1] == ("lake delete: 2026-11-14 is not in risk/scenario-pv: it holds 2 business dates (2026-11-13 .. 2026-11-16); "
                        "nothing deleted")
    assert all(dry for *_, dry in calls)                                                     # nothing deleted


def test_the_dry_run_counts_rows_per_date_and_changes_nothing(lake):
    root, calls = lake
    said = []
    assert delete.run(args(root, dry_run=True), said.append) == 0
    assert said[-1].splitlines() == ["would delete risk/scenario-pv 2026-11-16: 4 rows", "risk/scenario-pv would keep 1 business date (newest 2026-11-13)"]
    assert [c[3] for c in calls] == [True]
    assert not (root / "_drishti-lake" / "runs.log").exists()


def test_the_delete_is_confirmed_by_the_tables_name(lake, monkeypatch):
    root, calls = lake
    said = []
    monkeypatch.setattr(sys.stdin, "isatty", lambda: True, raising=False)
    assert delete.run(args(root, no_signal=True), said.append, ask=lambda q: "risk/pnl-vector") == 1
    assert "not confirmed" in said[-1] and [c[3] for c in calls] == [True]
    assert delete.run(args(root, no_signal=True), said.append, ask=lambda q: "risk/scenario-pv") == 0
    assert [c[3] for c in calls] == [True, True, False]
    monkeypatch.setattr(sys.stdin, "isatty", lambda: False, raising=False)
    calls.clear()
    assert delete.run(args(root), said.append) == 1 and "--yes" in said[-1] and [c[3] for c in calls] == [True]


def test_the_run_is_logged(lake, monkeypatch):
    root, _ = lake
    monkeypatch.delenv("DRISHTI_SERVER", raising=False)
    said = []
    assert delete.run(args(root, yes=True), said.append) == 0
    assert "deleted risk/scenario-pv 2026-11-16: 4 rows" in said
    assert any("no server told" in s for s in said)
    log = (root / "_drishti-lake" / "runs.log").read_text().splitlines()
    assert len(log) == 1 and " lake-delete ok " in log[0] and "table=risk/scenario-pv" in log[0] and "dates=2026-11-16" in log[0] and "rows=4" in log[0]
    run = json.loads(next((root / "_drishti-lake" / "runs").glob("*.json")).read_text())
    assert run["status"] == "ok" and run["deleted"] == {"2026-11-16": 4} and run["version"] == 7


class _Loads(http.server.BaseHTTPRequestHandler):
    got: list = []

    def do_POST(self):  # noqa: N802
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        _Loads.got.append((self.path, self.headers.get("Authorization"), body))
        out = json.dumps({"id": "x", "status": body["status"]}).encode()
        self.send_response(201)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(out)))
        self.end_headers()
        self.wfile.write(out)

    def log_message(self, *a):
        pass


def test_the_server_is_told_with_status_deleted(lake, tmp_path):
    root, _ = lake
    srv = http.server.ThreadingHTTPServer(("127.0.0.1", 0), _Loads)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    token = tmp_path / "token.txt"
    token.write_text("tok-1\n")
    try:
        said = []
        _Loads.got.clear()
        code = delete.run(args(root, yes=True, server=f"http://127.0.0.1:{srv.server_address[1]}", token_file=str(token)), said.append)
        assert code == 0, said
        path, auth, body = _Loads.got[0]
        assert path == "/api/v1/packs/market-risk/loads" and auth == "Bearer tok-1"
        assert body["status"] == "deleted" and body["kind"] == "scenario-pv" and body["businessDate"] == "2026-11-16" and body["rows"] == 4
        assert body["source"] == "lake delete"
        assert any("signal 2026-11-16: sent" in s for s in said)
        _Loads.got.clear()
        assert delete.run(args(root, yes=True, server=f"http://127.0.0.1:{srv.server_address[1]}", no_signal=True), said.append) == 0
        assert _Loads.got == []                                                              # --no-signal: no server told
    finally:
        srv.shutdown()
    said = []
    assert delete.run(args(root, yes=True, server="http://127.0.0.1:9"), said.append) == 2     # deleted, the signal failed
    assert any("FAILED" in s for s in said)


def test_the_table_is_found_by_its_kind(tmp_path, monkeypatch):
    monkeypatch.setattr(delete, "pack_route", lambda kind: (None, None))
    (tmp_path / "risk" / "scenario-pv" / "_delta_log").mkdir(parents=True)
    day = tmp_path / "trading" / "2026-11-16"
    day.mkdir(parents=True)
    (day / "trade.jsonl").write_text("{}\n")
    assert delete.find_table(str(tmp_path), "scenario-pv", None) == ("risk/scenario-pv", None)
    assert delete.find_table(str(tmp_path), "trade", None) == ("trading/trade", None)
    assert delete.format_of(str(tmp_path), "trading/trade") == "files"
    with pytest.raises(delete.Refused, match="no table of kind var-limit"):
        delete.find_table(str(tmp_path), "var-limit", None)


def _jar_ready() -> bool:
    try:
        stores.loader_command(pathlib.Path("."), None, None, main=delete.RETENTION)
        return True
    except (SystemExit, Exception):  # noqa: BLE001
        return False


@pytest.mark.skipif(not _jar_ready(), reason="no server exec jar in lib/ (drishti.py build jar)")
def test_a_files_store_end_to_end(tmp_path, monkeypatch):
    """The real Java writer on a files store: a dry run, then the date's day file gone, the other kept."""
    monkeypatch.setattr(delete, "pack_route", lambda kind: (None, None))
    for d in ("2026-11-13", "2026-11-16"):
        day = tmp_path / "risk" / d
        day.mkdir(parents=True)
        (day / "scenario-pv.jsonl").write_text('{"id":"A"}\n{"id":"B"}\n')
    said = []
    a = args(tmp_path, table=None, kind="scenario-pv", yes=True, no_signal=True)
    assert delete.run(types.SimpleNamespace(**{**vars(a), "dry_run": True}), said.append) == 0
    assert said[-1].splitlines()[0] == "would delete risk/scenario-pv 2026-11-16: 2 rows"
    assert delete.run(a, said.append) == 0, said
    assert not (tmp_path / "risk" / "2026-11-16" / "scenario-pv.jsonl").exists()
    assert (tmp_path / "risk" / "2026-11-13" / "scenario-pv.jsonl").exists()
