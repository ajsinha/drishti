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
"""The equality test of mixed storage: the same synthetic days built all-Delta and mixed (ENDUR in Delta, CALYPSO and MUREX in Iceberg)
give the same answers through a running server.

    drishti-console/.venv/bin/python tools/lake/stores_equality.py --work ~/drishti-scale-test/mixed --port 18760

1. Source files: the first ``--trades`` trades (default 1,000) of each system's committed test data (data/testdata/trades), five business
   days (the snapshot, then four days of changes), under ``<work>/src`` (synthetic only).
2. Lake A: every system in Delta; lake B: ``--store endur=delta``, ``calypso=iceberg``, ``murex=iceberg``; each built with
   ``build_demo_lakes.py --source-dir … --files-only``, each with its own connector folder and data folder under ``<work>``.
3. One server at a time on ``--port`` (the exec jar, ``-Xmx`` ``--heap``, packs core and trading, samples off) over A, then B: trade pages,
   history (``TRD <id> 10``), counterparty-trades pages, searches and one BI model query, each answer normalised (timings, data-version
   stamps and the provenance panel's generation are storage identifiers, not answers) and compared.

Exit status 0 when every answer is the same. ``--keep`` leaves ``<work>`` in place (it is removed at the end otherwise).
"""
from __future__ import annotations

import argparse
import gzip
import json
import os
import pathlib
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parents[1]
DATES = ["2026-10-12", "2026-10-13", "2026-10-14", "2026-10-15", "2026-10-16"]
DAY = DATES[-1]
MIXED = {"endur": "delta", "calypso": "iceberg", "murex": "iceberg"}
#: what differs by storage, not by answer: timings, data-version stamps, cache state, the table's generation in the provenance panel
VOLATILE = {"elapsedMs", "millis", "cached", "generation", "plan", "signature", "timestamp", "tookMs", "timings", "updatedAt", "dataVersion",
            "cache", "etag", "fetchedAt"}


def sources(work: pathlib.Path, n: int) -> None:
    for system in MIXED:
        folder = ROOT / "data" / "testdata" / "trades" / system
        files = sorted(folder.glob("*.jsonl.gz"))
        snap = next(f for f in files if f.name.startswith("snapshot-"))
        changes = sorted(f for f in files if f.name.startswith("changes-"))[:len(DATES) - 1]
        state: dict[str, str] = {}
        with gzip.open(snap, "rt", encoding="utf-8") as fh:
            for line in fh:
                if line.strip() and len(state) < n:
                    state[str(json.loads(line)["TRADE_ID"])] = line.strip()
        dest = work / "src" / system
        dest.mkdir(parents=True, exist_ok=True)
        for i, day in enumerate(DATES):
            if i:
                with gzip.open(changes[i - 1], "rt", encoding="utf-8") as fh:
                    for line in fh:
                        tid = str(json.loads(line)["TRADE_ID"]) if line.strip() else None
                        if tid in state:
                            state[tid] = line.strip()
            (dest / f"{day}.jsonl").write_text("\n".join(state.values()) + "\n", encoding="utf-8")


def build(work: pathlib.Path, name: str, stores: dict[str, str]) -> None:
    for system, store in stores.items():
        subprocess.run([sys.executable, str(ROOT / "tools" / "build_demo_lakes.py"), "--source-dir", str(work / "src" / system), "--system", system,
                        "--files-only", "--lake", str(work / name / "delta"), "--connectors-dir", str(work / name / "connectors"),
                        "--store", f"{system}={store}"], check=True, cwd=ROOT, stdout=subprocess.DEVNULL)


class Server:
    def __init__(self, work: pathlib.Path, name: str, port: int, heap: str):
        sys.path.insert(0, str(HERE))
        sys.path.insert(0, str(ROOT / "tools"))
        import writers
        from javacmd import java_cmd
        d = work / name
        (d / "logs").mkdir(parents=True, exist_ok=True)
        env = {**os.environ, "DRISHTI_PORT": str(port), "DRISHTI_DATA_DIR": str(d), "DRISHTI_CONNECTORS_DIR": str(d / "connectors"),
               "DRISHTI_DELTA_ROOT": str(d / "delta"), "DRISHTI_NET_DIR": str(d / "net"), "DRISHTI_MCP_DIR": str(d / "mcp"),
               "DRISHTI_DEMO_ENABLED": "false", "DRISHTI_OFFLINE": "true", "DRISHTI_PACKS": "core,trading"}
        env.pop("DRISHTI_ICEBERG_ROOT", None)
        cmd = java_cmd(writers.find_java(), [f"-Xmx{heap}", "-jar", writers.find_jar()], tmp=d / "tmp" / "server")
        self.base = f"http://127.0.0.1:{port}"
        self.log = open(d / "logs" / "server.log", "w")
        self.proc = subprocess.Popen(cmd, cwd=ROOT, env=env, stdout=self.log, stderr=subprocess.STDOUT)
        for _ in range(240):
            if self.proc.poll() is not None:
                raise SystemExit(f"the server over {name} stopped: see {d / 'logs' / 'server.log'}")
            try:
                urllib.request.urlopen(self.base + "/actuator/health", timeout=2).close()
                return
            except OSError:
                time.sleep(1)
        raise SystemExit(f"the server over {name} did not start")

    def get(self, path: str):
        req = urllib.request.Request(self.base + path, headers={"X-Drishti-As-Of": DAY})
        for attempt in range(3):                           # a first grouped page on a cold server may time out: ask again
            try:
                with urllib.request.urlopen(req, timeout=180) as r:
                    return json.loads(r.read())
            except urllib.error.HTTPError as e:
                if e.code != 504 or attempt == 2:
                    raise
        return None

    def post(self, path: str, body: dict):
        req = urllib.request.Request(self.base + path, data=json.dumps(body).encode(), headers={"Content-Type": "application/json"}, method="POST")
        with urllib.request.urlopen(req, timeout=300) as r:
            return json.loads(r.read())

    def stop(self) -> None:
        self.proc.terminate()
        try:
            self.proc.wait(timeout=60)
        except subprocess.TimeoutExpired:
            self.proc.kill()
        self.log.close()


def norm(v):
    if isinstance(v, dict):
        return {k: norm(x) for k, x in sorted(v.items()) if k not in VOLATILE}
    if isinstance(v, list):
        return [norm(x) for x in v]
    if isinstance(v, float):
        return float(f"{v:.9g}")                           # a sum's last bits depend on the order it was added in
    if isinstance(v, str) and ", gen " in v:
        return v.split(", gen ")[0]                        # the provenance panel's table generation (a Delta version, an Iceberg snapshot)
    return v


def answers(s: Server, n: int) -> dict:
    jpy = "/api/v1/search?q=" + urllib.parse.quote("TRD CURRENCY=JPY") + f"&asOf={DAY}"
    for _ in range(120):                                   # every trade connector has its newest day read
        try:
            if s.get(jpy).get("scanned") == 3 * n:
                break
        except OSError:
            pass
        time.sleep(2)
    out = {}
    for tid in ("1000001", "1000004", "CLY-3000001", "CLY-3000004", "MX-20000001", "MX-20000004"):
        out[f"trade page {tid}"] = s.get(f"/api/v1/views/trade/{tid}?asOf={DAY}")
        out[f"TRD {tid} 10"] = s.get(f"/api/v1/history/trade/{tid}?days=10&to={DAY}")
    for cp in ("CP-BRAMBLE", "CP-NORTHPLAINS"):
        out[f"counterparty-trades {cp}"] = s.get(f"/api/v1/views/counterparty-trades/{cp}?asOf={DAY}")
    for q in ("TRD CURRENCY=JPY", "TRD COUNTERPARTY=CP-BRAMBLE", "TRD SOURCE_SYSTEM=CALYPSO"):
        out[f"search {q}"] = s.get("/api/v1/search?q=" + urllib.parse.quote(q) + f"&asOf={DAY}&limit=5000")
    r = s.post("/api/v1/bi/mq", {"mq": 1, "model": "dataset:pack.trading.trade", "time": {"as-of": DAY}, "limit": 5000,
                                 "select": {"fields": ["trade.SOURCE_SYSTEM", "trade.CURRENCY", "trade.PRODUCT_TYPE"], "measures": ["Sum of QUANTITY"]}})
    r["rows"] = sorted(r["rows"], key=lambda x: json.dumps(x[:3]))
    out["BI sum of QUANTITY by source system, currency, product type"] = r
    return norm(out)


def differences(a, b, path="") -> list[str]:
    if type(a) is not type(b):
        return [f"{path}: {str(a)[:120]} | {str(b)[:120]}"]
    if isinstance(a, dict):
        return [d for k in sorted(set(a) | set(b)) for d in (differences(a.get(k), b.get(k), f"{path}.{k}") if k in a and k in b
                                                             else [f"{path}.{k}: only in {'A' if k in a else 'B'}"])]
    if isinstance(a, list):
        head = [] if len(a) == len(b) else [f"{path}: {len(a)} items | {len(b)}"]
        return head + [d for i, (x, y) in enumerate(zip(a, b)) for d in differences(x, y, f"{path}[{i}]")]
    return [] if a == b else [f"{path}: {str(a)[:120]} | {str(b)[:120]}"]


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0], formatter_class=argparse.RawDescriptionHelpFormatter,
                                 epilog=__doc__.split("\n\n", 1)[1])
    ap.add_argument("--work", type=pathlib.Path, required=True, help="an empty working folder, outside the repository's data/")
    ap.add_argument("--port", type=int, default=18760, help="the server's port (default 18760)")
    ap.add_argument("--trades", type=int, default=1000, help="trades per source system and day (default 1,000)")
    ap.add_argument("--heap", default="3g", help="the server's heap (default 3g)")
    ap.add_argument("--keep", action="store_true", help="leave the working folder in place")
    a = ap.parse_args(argv)
    work = a.work.expanduser().resolve()
    if work.exists() and any(work.iterdir()):
        ap.error(f"{work} is not empty")
    try:
        sources(work, a.trades)
        build(work, "A", {s: "delta" for s in MIXED})
        build(work, "B", MIXED)
        got = {}
        for name in ("A", "B"):
            s = Server(work, name, a.port, a.heap)
            try:
                got[name] = answers(s, a.trades)
            finally:
                s.stop()
        diffs = [d for k in got["A"] for d in differences(got["A"][k], got["B"][k], k)]
        for d in diffs[:40]:
            print("  differs: " + d)
        print(f"{len(got['A'])} answers compared (all-Delta against ENDUR Delta, CALYPSO and MUREX Iceberg): "
              + ("the same" if not diffs else f"{len(diffs)} differences"))
        return 1 if diffs else 0
    finally:
        if not a.keep:
            shutil.rmtree(work, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
