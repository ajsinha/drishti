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
"""tools/build_demo_lakes.py in --dry-run: the whole lakes, one dataset only, and one system's files only."""
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "tools" / "build_demo_lakes.py"


def run(*args):
    return subprocess.run([sys.executable, str(SCRIPT), *args, "--dry-run"], capture_output=True, text=True)


def files(tmp_path):
    d = tmp_path / "src"
    d.mkdir()
    for day in ("2026-10-15", "2026-10-16"):
        (d / f"{day}.jsonl").write_text("{}\n")
    (d / "notes.txt").write_text("not a dated file")
    return d


def test_everything_ends_on_the_newest_file_and_loads_each_file(tmp_path):
    out = run("--endur-dir", str(files(tmp_path)), "--lake", str(tmp_path / "lake")).stdout
    assert "lake demo --as-of 2026-10-16" in out
    assert out.count("lake build --from") == 2 and "--connector trade-endur" in out
    assert "notes.txt" not in out


def test_files_only_keeps_the_lakes_and_loads_one_system(tmp_path):
    out = run("--source-dir", str(files(tmp_path)), "--system", "calypso", "--files-only", "--lake", str(tmp_path / "lake")).stdout
    assert "lake demo" not in out and "kept as they are" in out
    assert "--connector trade-calypso" in out and "trade_calypso" in out and out.count("lake build --from") == 2


def test_only_rebuilds_the_named_datasets(tmp_path):
    out = run("--only", "trades/endur,trades/murex", "--as-of", "2026-10-16", "--lake", str(tmp_path / "lake")).stdout
    assert "lake demo --as-of 2026-10-16" in out and "--only trades/endur,trades/murex" in out
    assert "lake build" not in out


def test_wrong_combinations_are_refused(tmp_path):
    assert "--files-only needs" in run("--files-only").stderr
    d = files(tmp_path)
    assert "not both" in run("--endur-dir", str(d), "--source-dir", str(d)).stderr
    assert "leave out --only" in run("--endur-dir", str(d), "--files-only", "--only", "trades/endur").stderr


def test_no_server_is_told_by_default_and_the_closing_lines_say_what_to_do(tmp_path):
    out = run("--only", "trades/murex", "--as-of", "2026-10-16", "--lake", str(tmp_path / "lake")).stdout
    assert "--no-signal" in out and "--server" not in out.split("$ ", 1)[1].split("\n")[0]
    assert "picks the new tables up by itself at its next refresh" in out and "restart it to be sure" in out


def test_server_tells_once_per_table_at_the_end(tmp_path):
    out = run("--only", "trades/murex", "--as-of", "2026-10-16", "--lake", str(tmp_path / "lake"), "--server", "http://10.0.0.5:18480").stdout
    cmd = [line for line in out.splitlines() if "lake demo" in line][0]
    assert "--server http://10.0.0.5:18480 --signal-once" in cmd and "--no-signal" not in cmd
    assert "would be told once per table" in out and "dry run: nothing was sent" in out     # a dry run tells nobody (QA4-06)


def test_the_closing_line_says_what_came_of_the_signals():
    """QA4-06: it said "The server ... was told" when nothing listened or every signal was refused."""
    import argparse
    import sys as _sys
    _sys.path.insert(0, str(ROOT / "tools"))
    import build_demo_lakes as b
    a = argparse.Namespace(server="http://10.0.0.5:18629", files_only=False)
    said = {
        "none": "no server at http://10.0.0.5:18629: left /x/bi-store/lake-rebuilt.json, so a server clears its stored BI columns and rollups at its next start",
        "refused": "http://10.0.0.5:18620 took 0 of 2 data-load signals in 0 s, refused 2 (403 {\"code\":\"DRS-5002\"}; a token with loads:write: "
                   "--token-file or DRISHTI_TOKEN): left /x/lake-rebuilt.json for its next start; a running server also re-reads a rebuilt table by itself",
        "told": "http://10.0.0.5:18620 told: 2 data-load signals in 0 s (each table once, its newest date); it reads them again",
        "partly": "http://10.0.0.5:18620 took 1 of 2 data-load signals in 0 s, refused 1 (403 x): left /x for its next start; a running server ...",
        "busy": "http://10.0.0.5:18620 took 0 of 2 data-load signals in 60 s, did not answer 2 within 30 s (timed out; the server is busy: it reads the new data)",
    }
    for want, line in said.items():
        assert b.outcome(line) == want, (want, line)
    assert b.outcome("refused: 403 nope") == "refused" and b.outcome("no answer: [Errno 111]") == "none" and b.outcome("told") == "told"
    assert b.server_said("Feeds: x\nServer: " + said["none"] + "\nConnector files:") == said["none"]

    def last(outcomes):
        return " ".join(b.closing(a, "endur", [], outcomes))
    assert "was not told: nothing answered there" in last(["none"]) and "was told" not in last(["none"]).replace("was not told", "")
    assert "was not told: it refused the signals" in last(["refused"]) and "loads:write" in last(["refused"])
    assert "was not told: it refused" in last(["refused", "none"])
    assert "was told once per table it reads" in last(["told"]) and "it reads the new data now" in last(["told"])
    assert "was told of only some tables" in last(["told", "refused"]) and "was told of only some tables" in last(["partly"])
    assert "did not answer the signals in time" in last(["busy"])


def test_files_only_for_calypso_names_a_calypso_trade_and_needs_no_restart(tmp_path):
    d = tmp_path / "src"
    d.mkdir()
    for day, tid in (("2026-10-15", "CLY-3000157"), ("2026-10-16", "CLY-3000301")):
        (d / f"{day}.jsonl").write_text('{"SOURCE_SYSTEM": "CALYPSO", "TRADE_ID": "%s"}\n' % tid)
    out = run("--source-dir", str(d), "--system", "calypso", "--files-only", "--lake", str(tmp_path / "lake")).stdout
    closing = out.split("3/3 done", 1)[1]
    assert "TRD CLY-3000301 60" in closing and "1000279" not in closing          # an id from the newest file, not an ENDUR one
    assert "no restart is needed" in closing and "restart it" not in closing
    assert "EQ EQ-CSCA" not in closing                                              # the rest of the lakes was not built


def test_files_only_with_a_server_tells_it_once(tmp_path):
    out = run("--source-dir", str(files(tmp_path)), "--system", "murex", "--files-only", "--lake", str(tmp_path / "lake"),
              "--server", "http://10.0.0.5:18480").stdout
    assert out.count("tell http://10.0.0.5:18480/api/v1/packs/trading/loads: trade 2026-10-16") == 1
    assert "TRD MX-20000001 60" in out                                              # the files hold no TRADE_ID: the synthetic one


def test_token_file_needs_a_server(tmp_path):
    assert "--token-file goes with --server" in run("--token-file", str(tmp_path / "t.txt")).stderr


def test_the_heartbeat_says_waiting_when_nothing_is_written(tmp_path):
    import os
    import sys as _sys
    _sys.path.insert(0, str(ROOT / "tools"))
    import build_demo_lakes as b
    f = tmp_path / "trading" / "trade_murex" / "business_date=2026-10-16" / "part.parquet"
    f.parent.mkdir(parents=True)
    f.write_bytes(b"x" * 1000)
    assert "writing trading/trade_murex business_date=2026-10-16" in b.lake_progress(tmp_path)
    old = f.stat().st_mtime - 600
    os.utime(f, (old, old))
    line = b.lake_progress(tmp_path)
    assert "writing" not in line and "nothing written for 600 s (the last: trading/trade_murex" in line


def test_the_heartbeat_names_the_table_not_the_builds_working_folders(tmp_path):
    import sys as _sys
    _sys.path.insert(0, str(ROOT / "tools"))
    import build_demo_lakes as b
    table = tmp_path / "trading" / "trade_endur" / "business_date=2026-10-16" / "part.parquet"
    table.parent.mkdir(parents=True)
    table.write_bytes(b"x" * 1000)
    staging = tmp_path / "_drishti-lake" / "staging" / "trading-trade_endur.jsonl"
    staging.parent.mkdir(parents=True)
    staging.write_bytes(b"y" * 1000)                       # newer than the table's file, as during a build
    line = b.lake_progress(tmp_path)
    assert "writing trading/trade_endur business_date=2026-10-16" in line and "_drishti-lake" not in line


def test_a_python_without_deltalake_is_told_which_python_to_use(tmp_path):
    """QA (Fix-9a): run with a plain python3, every table failed with "no engine" and nothing said why."""
    import os
    stub = tmp_path / "stub"
    stub.mkdir()
    (stub / "deltalake.py").write_text("raise ImportError('no deltalake here', name='deltalake')\n")
    env = {**os.environ, "PYTHONPATH": str(stub)}
    r = subprocess.run([sys.executable, str(SCRIPT), "--dry-run"], capture_output=True, text=True, env=env)
    assert r.returncode != 0 and "lacks deltalake" in r.stderr and ".venv" in r.stderr


def test_store_puts_a_system_in_iceberg_and_says_where_each_system_is(tmp_path):
    """2.6.7 mixed storage: CALYPSO copied into Iceberg after lake demo, its connector file switched; the closing lines name every store."""
    out = run("--store", "endur=delta", "--store", "calypso=iceberg", "--as-of", "2026-10-16", "--lake", str(tmp_path / "data" / "delta"),
              "--connectors-dir", str(tmp_path / "connectors")).stdout
    assert "lake demo --as-of 2026-10-16" in out
    assert "CALYPSO into Iceberg" in out and "stores.py --from" in out and str(tmp_path / "data" / "iceberg") in out
    assert "ENDUR into Iceberg" not in out and "MUREX into Iceberg" not in out
    stores_part = out.split("Stores (each source system's trades):", 1)[1]
    assert "CALYPSO  iceberg" in stores_part and "ENDUR    delta" in stores_part and "MUREX    delta" in stores_part
    assert "trade-calypso: would be written to read iceberg" in out and "trade-endur: reads delta already" in out
    assert not (tmp_path / "connectors").exists()                                      # a dry run writes nothing


def test_store_all_and_wrong_values(tmp_path):
    out = run("--store", "all=iceberg", "--only", "trades/endur", "--as-of", "2026-10-16", "--lake", str(tmp_path / "data" / "delta"),
              "--connectors-dir", str(tmp_path / "connectors")).stdout
    # a partial build moving systems it does not rebuild rebuilds them too
    assert "--only trades/endur,trades/calypso,trades/murex" in out
    assert all(f"{s} into Iceberg" in out for s in ("ENDUR", "CALYPSO", "MUREX"))
    assert "--store" in run("--store", "calypso=parquet").stderr and "delta or iceberg" in run("--store", "calypso=parquet").stderr


def test_store_with_files_only_loads_the_files_into_iceberg_through_a_staging_lake(tmp_path):
    out = run("--source-dir", str(files(tmp_path)), "--system", "murex", "--files-only", "--store", "murex=iceberg",
              "--lake", str(tmp_path / "data" / "delta"), "--connectors-dir", str(tmp_path / "connectors")).stdout
    assert "lake demo" not in out and out.count("lake build --from") == 2
    assert str(tmp_path / "data" / "build" / "iceberg-stage") in out and "MUREX into Iceberg" in out
    assert "trade-murex: would be written to read iceberg" in out
    # a system the run does not rebuild is not moved by --files-only: it says how
    err = run("--source-dir", str(tmp_path / "src"), "--system", "murex", "--files-only", "--store", "calypso=iceberg",
              "--lake", str(tmp_path / "data" / "delta"), "--connectors-dir", str(tmp_path / "connectors")).stderr
    assert "--only trades/calypso --store calypso=iceberg" in err


def test_a_partial_build_keeps_the_store_each_system_has(tmp_path):
    conn = tmp_path / "connectors"
    conn.mkdir()
    (conn / "trade-calypso.yaml").write_text("plugin: iceberg\nkinds: [trade]\n")
    out = run("--only", "trades/endur", "--as-of", "2026-10-16", "--lake", str(tmp_path / "data" / "delta"), "--connectors-dir", str(conn)).stdout
    assert "--only trades/endur " in out + " " and "into Iceberg" not in out
    assert "CALYPSO  iceberg" in out and "trade-calypso: reads iceberg already" in out
    full = run("--as-of", "2026-10-16", "--lake", str(tmp_path / "data" / "delta"), "--connectors-dir", str(conn)).stdout
    assert "CALYPSO  delta" in full and "trade-calypso: would be written to read delta" in full    # a full build: Delta unless named


def test_store_risk_iceberg_moves_the_risk_domain_and_switches_risk_store(tmp_path):
    """2.9.5: the risk domain (P&L vectors and the rest of risk-store) in Iceberg by --store risk=iceberg; Delta stays the default."""
    lake = tmp_path / "data" / "delta"
    (lake / "risk" / "pnl-vector" / "_delta_log").mkdir(parents=True)
    out = run("--store", "risk=iceberg", "--as-of", "2026-10-16", "--lake", str(lake), "--connectors-dir", str(tmp_path / "connectors")).stdout
    assert "risk/pnl-vector into Iceberg" in out and "--domain risk" in out and str(tmp_path / "data" / "iceberg") in out
    assert "risk-store: would be written to read iceberg" in out
    assert "CALYPSO  delta" in out                                       # the trading systems stay where they were
    assert not (tmp_path / "connectors").exists()                        # a dry run writes nothing
    assert "risk=delta or risk=iceberg" in run("--store", "risk=parquet").stderr


def test_a_full_build_puts_the_risk_domain_back_in_delta_unless_named(tmp_path):
    conn = tmp_path / "connectors"
    conn.mkdir()
    (conn / "risk-store.yaml").write_text("plugin: iceberg\nkinds: [pnl-vector]\n")
    full = run("--as-of", "2026-10-16", "--lake", str(tmp_path / "data" / "delta"), "--connectors-dir", str(conn)).stdout
    assert "risk-store: would be written to read delta" in full
    partial = run("--only", "trades/endur", "--as-of", "2026-10-16", "--lake", str(tmp_path / "data" / "delta"), "--connectors-dir", str(conn)).stdout
    assert "risk-store" not in partial
