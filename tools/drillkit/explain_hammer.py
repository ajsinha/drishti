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

"""The Explain hammer: VaR's Explain (and the VaR headline beside it) asked without a pause, from a server's start through its first minutes and
through pack reloads and module switches, every answer that is not a 200 logged with its time, status, code and detail
(docs/architecture/TEST_HARNESS.md, "The Explain hammer").

    python3 tools/drillkit/explain_hammer.py --server http://127.0.0.1:PORT [--console URL] [--seconds 90] [--workers 6]
        [--scopes BANK,DESK:RATES] [--touch <a pack file>] [--switch <module>] [--token T] [--since-ready]

The server may still be starting: until it first answers, a refused connection is "not up yet", never a failure. ``--touch`` changes a pack
file's modification time every ``--every`` seconds (the packs watcher reloads that pack); ``--switch`` turns a module off and on again (the
resolution is rebuilt and followed). Exit 1 when any answer after the server first answered was not a 200 (with ``--since-ready``: after the
first 200 of every check)."""
from __future__ import annotations

import argparse
import json
import os
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path


def ask(url: str, token: str = "", data: bytes | None = None, timeout: float = 60) -> tuple[int, str]:
    headers = {"Accept": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    if data is not None:
        headers["Content-Type"] = "application/json"
    try:
        with urllib.request.urlopen(urllib.request.Request(url, data=data, headers=headers), timeout=timeout) as r:
            return r.status, r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")
    except (urllib.error.URLError, OSError, TimeoutError) as e:
        return 0, f"{type(e).__name__}: {e}"


def checks(a) -> list[tuple[str, str]]:
    out = []
    for scope in [s for s in a.scopes.split(",") if s]:
        q = urllib.parse.urlencode({"kind": "var", "measure": "var", "scope": scope, "part": "all"})
        out.append((f"server explain {scope}", f"{a.server}/api/v1/explain?{q}"))
        if a.console:
            out.append((f"console explain {scope}", f"{a.console}/api/explain?{q}&tab=why"))
    out.append(("server headline BANK", f"{a.server}/api/v1/risk/var/scope?scope=BANK"))
    for path in [x for x in a.beside.split(",") if x]:          # other VaR work asked in the same loop: it opens dates of its own
        out.append((f"beside {path.split('?')[0][-20:]}", a.server + path))
    return out


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--server", required=True)
    ap.add_argument("--console", default="")
    ap.add_argument("--token", default=os.environ.get("DRISHTI_HAMMER_TOKEN", ""))
    ap.add_argument("--seconds", type=float, default=90)
    ap.add_argument("--workers", type=int, default=6)
    ap.add_argument("--scopes", default="BANK")
    ap.add_argument("--touch", default="", help="a pack file whose modification time is changed every --every seconds")
    ap.add_argument("--switch", default="", help="a module switched off and on again every --every seconds")
    ap.add_argument("--every", type=float, default=12)
    ap.add_argument("--pause", type=float, default=0.02, help="seconds each worker waits between two asks")
    ap.add_argument("--beside", default="", help="server paths asked in the same loop as Explain, comma-separated (other VaR work: history, backtest)")
    ap.add_argument("--since-ready", action="store_true", help="count only answers after every check's first 200")
    ap.add_argument("--log", default="", help="every answer as JSON Lines")
    a = ap.parse_args(argv)
    a.server, a.console = a.server.rstrip("/"), a.console.rstrip("/")
    todo = checks(a)
    t0 = time.monotonic()
    stop = time.monotonic() + a.seconds
    up = threading.Event()
    ready: dict[str, float] = {}
    lock = threading.Lock()
    rows: list[dict] = []
    events: list[str] = []
    last: dict[str, tuple] = {}
    log = open(a.log, "w", encoding="utf-8") if a.log else None

    def one(i: int) -> None:
        n = i
        while time.monotonic() < stop:
            name, url = todo[n % len(todo)]
            n += 1
            s0 = time.monotonic()
            status, body = ask(url, a.token)
            row = {"t": round(s0 - t0, 3), "secs": round(time.monotonic() - s0, 3), "check": name, "status": status}
            if status == 0 and not up.is_set():
                time.sleep(0.2)
                continue
            up.set()
            if status == 200:
                ready.setdefault(name, s0 - t0)
            else:
                try:
                    got = json.loads(body)
                    row["code"], row["detail"] = got.get("code", ""), str(got.get("detail", got.get("message", "")))[:240]
                except (ValueError, AttributeError):
                    row["detail"] = body[:240]
            row["afterReady"] = name in ready and status != 200 and ready[name] < s0 - t0
            with lock:
                rows.append(row)
                if log:
                    log.write(json.dumps(row) + "\n")
                key = (name, status, row.get("code", ""))
                if status != 200 and key != last.get(name):
                    print(f"  {row['t']:8.2f}s {name:28} HTTP {status} {row.get('code', '')} {row.get('detail', '')[:160]}", file=sys.stderr, flush=True)
                if status == 200 and last.get(name, key)[1] != 200:
                    print(f"  {row['t']:8.2f}s {name:28} HTTP 200 again", file=sys.stderr, flush=True)
                last[name] = key
            time.sleep(a.pause)

    def churn() -> None:
        while time.monotonic() < stop:
            time.sleep(a.every)
            if not up.is_set() or time.monotonic() >= stop:
                continue
            if a.touch:
                Path(a.touch).touch()
                events.append(f"{time.monotonic() - t0:.2f}s touched {a.touch}")
            if a.switch:
                for on in (False, True):
                    body = json.dumps({"module": a.switch, "enabled": on, "reason": "explain hammer"}).encode()
                    status, text = ask(f"{a.server}/api/v1/admin/modules", a.token, body)
                    events.append(f"{time.monotonic() - t0:.2f}s module {a.switch} {'on' if on else 'off'}: HTTP {status}")
                    time.sleep(1)
            print("  " + " | ".join(events[-3:]), file=sys.stderr, flush=True)

    with ThreadPoolExecutor(a.workers + 1) as ex:
        ex.submit(churn)
        list(ex.map(one, range(a.workers)))
    if log:
        log.close()
    bad = [r for r in rows if r["status"] != 200 and (r["afterReady"] or not a.since_ready)]
    by = Counter((r["check"], r["status"], r.get("code", "")) for r in rows)
    print(f"explain hammer: {len(rows)} answers in {time.monotonic() - t0:.0f} s, {len(bad)} not 200"
          + (" after ready" if a.since_ready else ""))
    for (name, status, code), n in sorted(by.items()):
        print(f"  {n:6} {name:28} HTTP {status} {code}")
    for name, t in sorted(ready.items(), key=lambda kv: kv[1]):
        print(f"  first 200 of {name} at {t:.2f} s")
    for e in events:
        print("  " + e)
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
