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
"""Every demo lake from start to finish, in one command (Windows, Linux, macOS).

1. ``drishti.py lake demo``: every dataset of the seven packs, every business day of the committed test data (data/testdata),
   moved to end on ``--as-of`` (default: the date of your newest ENDUR file when ``--endur-dir`` is given, else today's business
   date), and the connector files pointed at the lake.
2. With ``--endur-dir``: the synthetic ENDUR trades are replaced by your files, one ``YYYY-MM-DD.jsonl`` (or ``.json``, ``.gz``)
   per business date, each loaded with ``drishti.py lake build`` exactly as the Windows QA guide's step 3.2 does.
3. A summary: the tables, the dates, your files and what to start next.

Examples::

    python tools/build_demo_lakes.py
    python tools/build_demo_lakes.py --endur-dir C:\\Users\\me\\drishti-private\\endur
    python tools/build_demo_lakes.py --endur-dir ~/private/endur --as-of 2026-10-16 --dry-run

One part only, the rest of the lakes kept as they are::

    python tools/build_demo_lakes.py --endur-dir C:\\…\\endur --files-only        # only your ENDUR files, nothing rebuilt
    python tools/build_demo_lakes.py --source-dir C:\\…\\calypso --system calypso --files-only   # another system's files
    python tools/build_demo_lakes.py --only trades/endur --as-of 2026-10-16          # only the synthetic ENDUR table
    python tools/build_demo_lakes.py --only trades/calypso,trades/murex             # several datasets (folders of data/testdata)

A server that reads the lake::

    python tools/build_demo_lakes.py --only trades/murex --server http://192.168.2.77:18480 --token-file ~/drishti-token.txt

No server is told by default (a build ran into the server on localhost:18480 whatever lake it built, QA3A-05): restart yours after a
build, or let it pick the tables up at its next refresh. ``--server`` names the server that reads this lake; it is told once at the end,
one data-load signal per table (its newest date), not one per table and day.

Your ENDUR files are read where they are and never copied into the project.
"""
from __future__ import annotations

import argparse
import gzip
import json
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DRISHTI = ROOT / "tools" / "drishti.py"
DATED = re.compile(r"^(\d{4}-\d{2}-\d{2})\.(jsonl|json)(\.gz)?$")


def endur_files(folder: Path) -> list[tuple[str, Path]]:
    """Your ENDUR files, oldest first: (business date, path) for every file named by its date."""
    out = []
    for p in sorted(folder.iterdir()):
        m = DATED.match(p.name)
        if p.is_file() and m:
            out.append((m.group(1), p))
    return out


HEARTBEAT_SECONDS = int(os.environ.get("DRISHTI_HEARTBEAT_SECONDS", "30"))
#: a trade each system's synthetic trades hold, for the closing lines' example commands
SAMPLE_TRADE = {"endur": "1000279", "calypso": "CLY-3000157", "murex": "MX-20000001"}
TRADE_ID = re.compile(rb'"TRADE_ID"\s*:\s*"?([A-Za-z0-9._-]+)')


def lake_progress(lake: Path, now: float | None = None) -> str:
    """The lake's size on disk and the table folder written most recently: what a heartbeat line says. A table is said to be
    being written only while files still land in it; past a heartbeat with nothing new the line says how long nothing was written
    (the step is then waiting, for instance on the server it tells; QA3A-07)."""
    total, newest, newest_t = 0, None, 0.0
    if lake.is_dir():
        for f in lake.rglob("*"):
            try:
                st = f.stat()
            except OSError:
                continue
            if f.is_file():
                total += st.st_size
                # the build's own working folders (_drishti-lake/staging, locks) change all the time: name the table, not them (DOCS2-01)
                if st.st_mtime > newest_t and not f.relative_to(lake).parts[0].startswith("_"):
                    newest_t, newest = st.st_mtime, f
    where = ""
    if newest is not None:
        rel = newest.relative_to(lake).parts
        table = '/'.join(rel[:2]) + (f" {rel[2]}" if len(rel) > 2 and rel[2].startswith("business_date=") else "")
        idle = (time.time() if now is None else now) - newest_t
        where = f", writing {table}" if idle <= HEARTBEAT_SECONDS else f", nothing written for {idle:,.0f} s (the last: {table})"
    return f"{total / 1e6:,.0f} MB in the lake{where}"


def run(cmd: list[str], dry: bool, step: str = "", lake: Path | None = None, capture: bool = False) -> str:
    """Runs one command; while it runs, a heartbeat every HEARTBEAT_SECONDS says it is alive and how far the lake has grown. With
    {capture}, its output is shown as it comes and also returned (what lake demo says of the server: QA4-06)."""
    print("  $ " + " ".join(f'"{c}"' if " " in c else c for c in cmd), flush=True)
    if dry:
        return ""
    import threading
    t0, done = time.time(), threading.Event()

    def beat():
        while not done.wait(HEARTBEAT_SECONDS):
            extra = f"; {lake_progress(lake)}" if lake else ""
            print(f"  … {step} still running: {time.time() - t0:,.0f} s{extra}", flush=True)
    threading.Thread(target=beat, daemon=True).start()
    try:
        if not capture:
            subprocess.run(cmd, check=True, cwd=ROOT)
            return ""
        lines = []
        with subprocess.Popen(cmd, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding="utf-8",
                              errors="replace", bufsize=1) as proc:
            for line in proc.stdout:
                print(line, end="", flush=True)
                lines.append(line)
        if proc.returncode:
            raise subprocess.CalledProcessError(proc.returncode, cmd)
        return "".join(lines)
    finally:
        done.set()


def outcome(said: str) -> str:
    """What a line about the server says happened: told, dry (a dry run), none (nothing listening), refused, busy (no answer in time),
    partly (some taken), or ''."""
    s = said.strip()
    if not s:
        return ""
    if s.startswith("not sent (dry run)"):
        return "dry"
    if s == "told" or " told: " in s:
        return "told"
    if s.startswith("no server at") or s.startswith("no answer"):
        return "none"
    if s.startswith("refused"):
        return "refused"
    m = re.search(r"took (\d+) of (\d+) data-load signals", s)
    if m:
        if m.group(1) == "0":
            return "refused" if "refused" in s else "busy"            # reachable, but no answer in time
        return "partly"
    return ""


def server_said(out: str) -> str:
    """lake demo's line about the server (its summary's "Server: ..."), or ''."""
    for line in out.splitlines():
        if line.startswith("Server: "):
            return line[len("Server: "):]
    return ""


def first_trade(path: Path) -> str | None:
    """The first TRADE_ID in one of your files (its first megabyte), for the closing lines' example command."""
    try:
        opener = gzip.open if path.name.endswith(".gz") else open
        with opener(path, "rb") as f:
            m = TRADE_ID.search(f.read(1 << 20))
        return m.group(1).decode() if m else None
    except (OSError, EOFError):
        return None


def tell(server: str, token: str | None, pack: str, kind: str, day: str, dry: bool) -> str:
    """One data-load signal to the server --server names; the line that says what came of it."""
    url = server.rstrip("/") + f"/api/v1/packs/{urllib.parse.quote(pack, safe='')}/loads"
    print(f"  tell {url}: {kind} {day}", flush=True)
    if dry:
        return "not sent (dry run)"
    headers = {"Content-Type": "application/json", "Accept": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    body = {"kind": kind, "businessDate": day, "status": "ready", "source": "build_demo_lakes", "batchId": f"build-demo-lakes-{int(time.time())}"}
    try:
        with urllib.request.urlopen(urllib.request.Request(url, data=json.dumps(body).encode(), headers=headers, method="POST"), timeout=60):  # noqa: S310
            return "told"
    except urllib.error.HTTPError as e:
        hint = " (a token with loads:write: --token-file or DRISHTI_TOKEN)" if e.code in (401, 403) else ""
        return f"refused: {e.code} {e.read().decode('utf-8', 'replace')[:160]}{hint}"
    except (urllib.error.URLError, OSError) as e:
        return f"no answer: {getattr(e, 'reason', e)}"


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0], formatter_class=argparse.RawDescriptionHelpFormatter,
                                 epilog=__doc__.split("\n\n", 1)[1])
    ap.add_argument("--endur-dir", type=Path, help="a folder of your ENDUR files, one per business date, named YYYY-MM-DD.jsonl")
    ap.add_argument("--source-dir", type=Path, help="the same for the system --system names (the same as --endur-dir with --system endur)")
    ap.add_argument("--system", default="endur", choices=("endur", "calypso", "murex"),
                    help="whose files --source-dir holds: they go into trading/trade_<system> through connector trade-<system> (default endur)")
    ap.add_argument("--files-only", action="store_true",
                    help="load only your files into their system's table; every other table of the lake stays as it is (no lake demo)")
    ap.add_argument("--only", help="rebuild only these datasets of the test data (lake demo --only: folders such as trades/endur, "
                                   "trades/calypso, core/counterparty, or a pack name); the other tables stay as they are")
    ap.add_argument("--as-of", help="the last business date of the lakes (default: your newest ENDUR file's date, else today)")
    ap.add_argument("--lake", type=Path, default=ROOT / "data" / "delta", help="the Delta root the server reads (default data/delta)")
    ap.add_argument("--connectors-dir", type=Path, help="the connector files' folder (default: as lake demo, config/connectors)")
    ap.add_argument("--keep-synthetic-endur", "--keep-synthetic", dest="keep_synthetic_endur", action="store_true",
                    help="add your files beside the system's synthetic trades instead of replacing them")
    ap.add_argument("--server", help="the server that reads this lake: told once at the end, one data-load signal per table (its newest "
                                     "date), not per table and day. Default: no server is told; restart yours, or let it pick the "
                                     "tables up at its next refresh")
    ap.add_argument("--token-file", type=Path, help="with --server: the API token (loads:write) for the signals (default: DRISHTI_TOKEN)")
    ap.add_argument("--dry-run", action="store_true", help="say every step, change nothing")
    a = ap.parse_args(argv)

    py = sys.executable
    try:                                              # lake demo writes with the deltalake package (the console's environment has it)
        import deltalake  # noqa: F401
        import pyarrow  # noqa: F401
    except ImportError as e:
        ap.error(f"this Python ({py}) lacks {e.name}: run the script with the console's environment, "
                 "drishti-console/.venv/bin/python (Windows: drishti-console\\.venv\\Scripts\\python.exe), as the Windows QA guide shows")
    files: list[tuple[str, Path]] = []
    if a.endur_dir and a.source_dir:
        ap.error("give --endur-dir or --source-dir, not both")
    src = a.endur_dir or a.source_dir
    system = "endur" if a.endur_dir else a.system
    if a.files_only and not src:
        ap.error("--files-only needs --endur-dir or --source-dir")
    if a.files_only and a.only:
        ap.error("--files-only loads only your files; leave out --only")
    if src:
        folder = src.expanduser().resolve()
        if not folder.is_dir():
            ap.error(f"{'--endur-dir' if a.endur_dir else '--source-dir'} {folder} is not a folder")
        files = endur_files(folder)
        if not files:
            ap.error(f"no file named YYYY-MM-DD.jsonl (or .json, .gz) in {folder}")
    if a.token_file and not a.server:
        ap.error("--token-file goes with --server")
    token = None
    if a.server:
        token = (a.token_file.expanduser().read_text(encoding="utf-8").strip() if a.token_file else None) or os.environ.get("DRISHTI_TOKEN")
    as_of = a.as_of or (files[-1][0] if files else "today")
    lake = a.lake.expanduser().resolve()
    t0 = time.time()
    outcomes: list[str] = []                             # what each step's signals came to (QA4-06): the closing line says it

    if a.files_only:
        print(f"1/3 the demo lakes: kept as they are (--files-only)")
    else:
        what = f"only {a.only}" if a.only else "every dataset"
        print(f"1/3 the demo lakes: {what}, every business day, ending {as_of} → {lake}")
        # lake demo would otherwise tell a running server of every table and date (66 tables x 60 days = 3,960 signals), each making it
        # read the data again (QA 2026-10-20, QA3A-01/05): no server is told unless --server names one, and then once per table
        cmd = [py, str(DRISHTI), "lake", "demo", "--as-of", as_of, "--lake", str(lake)]
        cmd += ["--server", a.server, "--signal-once"] if a.server else ["--no-signal"]
        if a.server and a.token_file:
            cmd += ["--token-file", str(a.token_file.expanduser().resolve())]
        if a.only:
            cmd += ["--only", a.only]
        if a.connectors_dir:
            cmd += ["--connectors-dir", str(a.connectors_dir.expanduser().resolve())]
        said = server_said(run(cmd, a.dry_run, "1/3 lake demo", lake, capture=bool(a.server)))
        if a.server:
            outcomes.append(outcome(said) or ("dry" if a.dry_run else ""))

    if files:
        table = lake / "trading" / f"trade_{system}"
        print(f"2/3 your {system.upper()} files ({len(files)}: {files[0][0]} … {files[-1][0]}) into {table}")
        if not a.keep_synthetic_endur:
            print(f"  the synthetic {system.upper()} table is removed first: {table}")
            if not a.dry_run and table.exists():
                shutil.rmtree(table)
        for day, path in files:
            run([py, str(DRISHTI), "lake", "build", "--from", str(path), "--as-of", day,
                 "--pack", str(ROOT / "config" / "packs" / "trading"), "--connector", f"trade-{system}", "--key", "TRADE_ID",
                 "--partition-by", "PRODUCT_TYPE", "--mode", "overwrite-dates", "--lake", str(lake)], a.dry_run,
                f"2/3 {path.name} ({day})", lake / "trading" / f"trade_{system}")
    else:
        print("2/3 no --endur-dir or --source-dir: the synthetic trades stay")

    told = None
    if files and a.server:                               # your files: one signal, their table on its newest date
        told = tell(a.server, token, "trading", "trade", files[-1][0], a.dry_run)
        print(f"  the server: {told}")
        outcomes.append(outcome(told))
    print(f"3/3 done in {time.time() - t0:.0f} s{' (dry run: nothing changed)' if a.dry_run else ''}.")
    for line in closing(a, system, files, outcomes):
        print(line)
    return 0


def closing(a, system: str, files: list[tuple[str, Path]], outcomes: list[str] | None = None) -> list[str]:
    """What to do next, for the run that was made (QA3A-08): the system loaded, what came of telling the server (QA4-06: it said "was told"
    when nothing listened or every signal was refused), and ids that exist."""
    out = []
    got = [o for o in (outcomes or []) if o]
    if a.server and got and all(o == "dry" for o in got):
        out.append(f"  The server at {a.server} would be told once per table it reads (dry run: nothing was sent).")
    elif a.server and got and all(o == "told" for o in got):
        out.append(f"  The server at {a.server} was told once per table it reads (see the lines above); it reads the new data now.")
    elif a.server and got and all(o == "none" for o in got):
        out.append(f"  The server at {a.server} was not told: nothing answered there. Start it (it clears its stored BI columns at start, from the")
        out.append("  stamp left above) or check --server; a running server picks the new tables up by itself at its next refresh.")
    elif a.server and got and all(o == "busy" for o in got):
        out.append(f"  The server at {a.server} did not answer the signals in time (see the lines above): it is busy, and re-reads a rebuilt")
        out.append("  table by itself; restart it to be sure it reads all the new data.")
    elif a.server and got and all(o in ("refused", "none") for o in got):
        out.append(f"  The server at {a.server} was not told: it refused the signals (see the lines above). Give a token with loads:write")
        out.append("  (--token-file or DRISHTI_TOKEN) and run again, or restart the server; a running server also re-reads a rebuilt table by itself.")
    elif a.server and got:
        out.append(f"  The server at {a.server} was told of only some tables (see the lines above): restart it to be sure it reads all the")
        out.append("  new data; a running server also re-reads a rebuilt table by itself.")
    elif a.server:
        out.append(f"  The server at {a.server}: see the lines above for what it was told; restart it to be sure it reads the new data.")
    elif a.files_only:
        out.append("  A running server reads the replaced table by itself within about 10 s (its refresh-seconds): no restart is needed.")
    else:
        out.append("  Next: start the server (Drishti server (demo lakes), or tools\\windows\\start-server.ps1 -DemoLakes) and the console.")
        out.append("  A server already running picks the new tables up by itself at its next refresh; restart it to be sure, or pass --server next time.")
    trade = (first_trade(files[-1][1]) if files else None) or SAMPLE_TRADE.get(system, SAMPLE_TRADE["endur"])
    if a.files_only:
        out.append(f"  Then try:  TRD {trade} 60  ·  TRD {trade} 60 DIFF  ({system.upper()} trades, from your files)")
    else:
        out.append(f"  Then try:  TRD {trade} 60  ·  TRD {trade} 60 DIFF  ·  EQ EQ-CSCA 3M  ·  CPTY CP-NORTHPLAINS 30")
    return out


if __name__ == "__main__":
    sys.exit(main())
