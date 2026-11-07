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

"""PackFeed: a feed whose pull is one of the pack sources folded into drishti-feeds (tools/feeds/sources/gleif.py, us_equities.py,
funds.py, world_economy.py, news_and_markets.py, official_market_data.py; each keeps its own fetch, normalise and pack check).

A run builds the pack's data into the run's work folder (the source's ``refresh --out <work>``, which never touches the repository),
then reads the dated files the build wrote (``<work>/testdata/<pack>/<kind>/<kind>.<date>.jsonl.gz`` or tars; tools/lake/feedfiles.py)
and emits each kind's rows as the feed file's dataset of that kind. ``--snapshot`` runs the source's refresh into its committed place
(data/testdata and the pack's samples) exactly as the old refresh script did, nothing loaded. Arguments after ``--`` on the command
line go to the source (``feed run gleif -- --scope GROUP:<lei>``); the feed file's ``options.args`` are given first."""
from __future__ import annotations

import json
import sys
from pathlib import Path

import feedbase as FB
import pullkit as K

ROOT = K.ROOT
sys.path.insert(0, str(ROOT / "tools" / "lake"))
import feedfiles as FF  # noqa: E402


class PackFeed(FB.Feed):
    """Subclasses set ``module`` (the source module, imported), ``pack`` and ``prog``; ``out_args`` says how the source is told
    where to build (default ``--out <folder>``)."""
    module = None
    pack = ""
    prog = ""
    data_subdir = "testdata"            # where under --out the source writes <pack>/<kind>/ (None: directly under --out)
    snapshot_args: tuple = ()           # the arguments of a refresh of the committed snapshot (beside the feed file's snapshot.args)

    def namespace(self, run: FB.FeedRun, argv: list[str]):
        m = self.module
        ap = K.parser(self.prog, m.__doc__, m.SOURCES, extra=getattr(m, "extra_args", None))
        try:
            return ap.parse_args(argv)
        except SystemExit as e:
            raise FB.FeedRefused(f"feed {self.spec.name}: the source's arguments {' '.join(argv[1:])} were refused (exit {e.code})")

    def argv(self, run: FB.FeedRun, out: Path | None) -> list[str]:
        args = ["refresh"] + [str(x) for x in (run.option("args") or [])] + list(run.extra_args)
        if out is not None:
            args += ["--out", str(out)]
        if run.offline and "--offline" not in args:
            args.append("--offline")
        return args

    def call(self, run: FB.FeedRun, a) -> int:
        try:
            return self.module.refresh(a, log=run.log)
        except K.Refusal as e:
            raise FB.FeedRefused(str(e))

    def plan(self, run: FB.FeedRun) -> list[str]:
        a = self.namespace(run, self.argv(run, run.work / "build") + ["--dry-run"])
        lines: list[str] = []
        self.call(run_with_log(run, lines.append), a)
        return lines

    def built(self, out: Path) -> Path:
        return (out / self.data_subdir if self.data_subdir else out) / self.pack

    def pull(self, run: FB.FeedRun) -> None:
        out = run.work / "build"
        a = self.namespace(run, self.argv(run, out))
        rc = self.call(run, a)
        if rc != 0:
            raise FB.FeedError(f"the {self.pack} build exited {rc}")
        self.emit_built(run, self.built(out))

    def emit_built(self, run: FB.FeedRun, folder: Path) -> None:
        """The build's dated files (in ``folder`` itself or one folder a kind: ``<kind>.<YYYY-MM-DD>.jsonl[.gz]`` or tars), each table's
        rows emitted as the feed file's dataset of that kind (a kind the feed file does not list is left out, and said)."""
        if not folder.is_dir():
            raise FB.FeedError(f"the {self.pack} build wrote nothing under {folder}")
        by_table: dict[str, dict[str, list]] = {}
        for d in [folder] + sorted(p for p in folder.iterdir() if p.is_dir()):
            for day, parts in FF.read_table(d).items():
                for part in parts:
                    by_table.setdefault(part.table, {}).setdefault(day, []).append(part)
        if not by_table:
            raise FB.FeedError(f"the {self.pack} build wrote no dated files under {folder}")
        for table, days in sorted(by_table.items()):
            ds = self.spec.dataset_for_kind(table)
            if ds is None:
                run.log(f"  {table}: not a dataset of feed {self.spec.name}; left out")
                continue
            dates = sorted(days)
            for day in (dates if ds.dated == "source" else dates[-1:]):        # a snapshot: its newest date, under the run's date
                rows = (json.loads(line) for p in days[day] for line in p.lines() if line.strip())
                run.emit(ds.name, rows, business_date=day)
            run.mark(ds.name, run.business_date)

    def emit_undated(self, run: FB.FeedRun, folder: Path) -> None:
        """A build that writes one undated ``<kind>.jsonl`` a kind (a pack's samples): each under the run's business date."""
        for f in sorted(folder.glob("*.jsonl")):
            ds = self.spec.dataset_for_kind(f.name[:-len(".jsonl")])
            if ds is None:
                continue
            with open(f, encoding="utf-8") as fh:
                run.emit(ds.name, (json.loads(x) for x in fh if x.strip()))
            run.mark(ds.name, run.business_date)

    def snapshot(self, run: FB.FeedRun) -> int:
        args = ["refresh"] + [str(x) for x in (self.spec.snapshot.get("args") or self.snapshot_args)] + list(run.extra_args)
        if run.internal_ok and "--internal-ok" not in args:
            args.append("--internal-ok")
        if run.offline and "--offline" not in args:
            args.append("--offline")
        run.log(f"snapshot: {self.prog} {' '.join(args)}")
        return self.call(run, self.namespace(run, args))


def run_with_log(run: FB.FeedRun, log):
    """The same run, its log going elsewhere (a dry run's plan is collected, not printed twice)."""
    class _R:
        def __getattr__(self, k):
            return getattr(run, k)
    r = _R()
    r.log = log
    return r
