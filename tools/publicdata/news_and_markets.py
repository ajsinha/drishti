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

"""Refresh the news-and-markets pack's offline snapshot: listed companies' quotes (Yahoo Finance) and headlines (Google News).

    python3 tools/publicdata/news_and_markets.py status
    python3 tools/publicdata/news_and_markets.py refresh --dry-run
    python3 tools/publicdata/news_and_markets.py refresh                    a snapshot in <data>/publicdata/news-and-markets/testdata
    python3 tools/publicdata/news_and_markets.py refresh --internal-ok      replace the committed snapshot in data/testdata
    python3 tools/publicdata/news_and_markets.py refresh quote --years 5 --scope TSLA,NVDA,NEWS-TESLA

It wraps tools/testdata/capture_market.py: the documents are fetched through Drishti's own live connectors (a scratch server
from the built jar, or --server), so they have exactly the shape the pack's Sutras read. Both sources forbid redistribution
(Yahoo's terms; headlines belong to their publishers), so they are internal only: the committed data/testdata is written only
with --internal-ok (the owner chose a committed snapshot for the offline demo machine, 2026-10-17). After a refresh into
data/testdata, run `python3 tools/drishti.py lake demo`. Guide: docs/guides/DATA_GUIDE.md.
"""
from __future__ import annotations

import argparse
import gzip
import json
import sys
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import pdbase as B  # noqa: E402

sys.path.insert(0, str(B.ROOT / "tools" / "testdata"))
sys.path.insert(0, str(B.ROOT / "tools"))
import capture_market as CM  # noqa: E402

PACK = CM.PACK
COMMITTED = B.ROOT / "data" / "testdata"
RANGES = (1, 2, 5, 10)          # the history ranges the yfinance connector accepts (years)


class Quotes(B.Source):
    name, title, pack = "quote", "Yahoo Finance quotes and daily history (yfinance connector)", PACK
    url, licence = "https://finance.yahoo.com", "Yahoo terms of service: personal use, no redistribution"
    licence_url, use = "https://legal.yahoo.com/us/en/yahoo/terms/otos/index.html", B.INTERNAL
    why_internal = "Yahoo's terms forbid redistributing its prices; kept as a dated demo snapshot only"
    scope_help = "more tickers (TSLA) or quote ids (QUOTE-TSLA)"

    def ids(self, run: B.Run) -> list[str]:
        return [x if x.startswith("QUOTE-") else f"QUOTE-{x.upper()}" for x in run.scope if not x.startswith("NEWS-")]


class News(B.Source):
    name, title, pack = "news", "Google News RSS headlines (news connector)", PACK
    url, licence = "https://news.google.com", "Headlines and links belong to their publishers"
    licence_url, use = "https://policies.google.com/terms", B.INTERNAL
    why_internal = "the headlines belong to their publishers; shown for demonstration only"
    scope_help = "more topics or companies as news ids (NEWS-TESLA, NEWS-TESLA~all)"

    def ids(self, run: B.Run) -> list[str]:
        return [x for x in run.scope if x.startswith("NEWS-")]


SOURCES = [Quotes(), News()]


def history_range(run: B.Run) -> str:
    """--years / --since as the connector's range (the smallest it accepts that covers it): 1y, 2y, 5y, 10y, else max."""
    years = run.a.years or ((date.today().year - run.a.since + 1) if run.a.since else 1)
    return next((f"{r}y" for r in RANGES if years <= r), "max")


def snapshot_counts(testdata: Path) -> dict:
    """kind -> (file, documents, problems): the written snapshot read back; ids unique and sorted, every document stamped."""
    out = {}
    for kind, (key, _src) in CM.KINDS.items():
        files = sorted((testdata / PACK / kind).glob("snapshot-*.jsonl.gz"))
        if not files:
            continue
        with gzip.open(files[-1], "rt", encoding="utf-8") as fh:
            docs = [json.loads(line) for line in fh if line.strip()]
        ids = [str(d.get(key)) for d in docs]
        problems = []
        if ids != sorted(set(ids)):
            problems.append("ids not unique and sorted")
        if any(not d.get("SNAPSHOT") for d in docs):
            problems.append("a document is not stamped as a snapshot")
        out[kind] = (files[-1], len(docs), problems)
    return out


def refresh(a, log=print) -> int:
    selected = B.pick(SOURCES, a.sources)
    if a.out:
        out = Path(a.out).expanduser().resolve()
    else:
        out = COMMITTED if a.internal_ok else B.data_dir() / "publicdata" / PACK / "testdata"
    B.guard_out(selected, out, a.internal_ok)
    if B.is_committed(out) and out != COMMITTED.resolve():
        raise B.Refusal(f"refused: {out} is inside the repository; the committed snapshot lives in {COMMITTED} only")
    run = B.Run(PACK, a, out, log)
    if not a.out and not a.internal_ok:
        log(f"note: quotes and headlines may not be redistributed, so the snapshot goes to {out}; --internal-ok replaces data/testdata")
    extra = {s.name: s.ids(run) for s in selected}
    cap = argparse.Namespace(server=a.server, jar=a.jar, port=a.port, range=history_range(run), delay=a.delay,
                             only=",".join(s.name for s in selected), testdata=str(out), packs=str(B.ROOT / "config" / "packs"),
                             token_file=a.token_file, dry_run=a.dry_run, quiet=False, extra=extra)
    if a.dry_run:
        log(f"dry run: {PACK}, sources {cap.only}, history {cap.range}, -> {out}" + (f", --scope adds {extra}" if any(extra.values()) else ""))
        return CM.run(cap, say=log)
    rc = CM.run(cap, say=log)
    if rc == 1:
        return 1
    man, counts = B.Manifest(PACK), snapshot_counts(out)
    bad = []
    for s in selected:
        f, n, problems = counts.get(s.name, (None, 0, ["nothing written"]))
        man.record(s, [f"{a.server or 'scratch server'} /api/v1/entities/{s.name}/<id>/raw"], {"rows": n, "range": cap.range}, out)
        bad += [f"{s.name}: {p}" for p in problems] if not a.no_check else []
        log(f"{s.name}: {n} documents in {f}")
    man.finish(out, [c[0] for c in counts.values()], sys.argv[1:], rc == 0 and not bad)
    for b in bad:
        log(f"check: {b}")
    if out == COMMITTED.resolve():
        log("next: python3 tools/drishti.py lake demo (rebuilds news/quote and news/news from the snapshot)")
    return 2 if bad else rc


def extra_args(r: argparse.ArgumentParser) -> None:
    r.add_argument("--server", help="a running server with the live connectors on (default: a scratch server from the built jar)")
    r.add_argument("--jar", help="the exec jar for the scratch server (default DRISHTI_JAR, else the newest in drishti-server/target)")
    r.add_argument("--port", type=int, default=18977, help="the scratch server's port (default 18977)")
    r.add_argument("--delay", type=float, default=2.0, help="seconds between calls, at least 2 (default 2)")
    r.add_argument("--token-file", help="an API token for --server with security on (default DRISHTI_TOKEN)")


def main(argv: list[str] | None = None) -> int:
    a = B.parser("news_and_markets.py", __doc__, SOURCES, extra=extra_args).parse_args(argv)
    try:
        if a.command == "status":
            for kind, (f, n, _p) in snapshot_counts(COMMITTED).items():
                print(f"committed snapshot {kind}: {f.name}, {n} documents, {B.human(f.stat().st_size)}")
            return B.status(PACK, SOURCES, Path(a.cache) if a.cache else B.data_dir() / "publicdata" / "cache" / PACK,
                            "data/testdata with --internal-ok; else <data>/publicdata/news-and-markets/testdata")
        return refresh(a)
    except B.Refusal as e:
        print(e, file=sys.stderr)
        return 3


if __name__ == "__main__":
    sys.exit(main())
