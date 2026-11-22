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

"""lake demo: build the demo lakes from the committed test data (data/testdata) in one command, then say what to start next.

    python3 tools/drishti.py lake demo                                  data/testdata -> data/delta, connector files checked
    python3 tools/drishti.py lake demo --lake /tmp/demo-lake            somewhere else (connector files then point there)
    python3 tools/drishti.py lake demo --with-real ~/Downloads/endur.json,~/Downloads/ComSwap.json      LOCAL ONLY (below)

For every dataset of data/testdata/testdata.yaml (written by tools/testdata/make_testdata.py) it rebuilds the daily snapshots (the
first day's file, then each later day's changes applied by key; one file a day, <table>.<YYYY-MM-DD>.jsonl.gz) and loads each day with
`lake build` (tools/lake/build.py) by the load rules (docs/guides/DATA_LAKE_GUIDE.md): the day staged as <table>.<YYYY-MM-DD>.jsonl,
its business date from that name, mode replace, into the table the dataset's Delta connector reads: <lake>/<domain>/<kind>/, the trades
into trading/trade_endur, trade_calypso and trade_murex, partitioned by business date (and PRODUCT_TYPE for trades). Each table is
written afresh (its folder is removed first). A dataset ``dated-by`` its BUSINESS_DATE (the market-risk pack's VaR data, tools/packgen/var/make_var_data.py:
the trades' P&L vectors of the last three business dates, the desks' 260-day P&L history; the exposure run) carries its dates in its
files (dated names, or tars of <table>/<YYYY-MM-DD>/ folders): each date staged under its moved date's name, every date in one run.
The exposure engine's paths are the exception: by default (`--exposure demo`) they are generated from the committed netting sets
and CSAs for every netting set of 23 of the 24 counterparties (8,022 live trades on the data's date, about a minute; the committed run of eight sets
is a subset with the same figures), `--exposure committed` loads the committed run alone.
Datasets of static reference data (world-economy, news-and-markets) are built from their packs' samples, the same on every day.
The news-and-markets pack's captured market snapshot (data/testdata/news-and-markets/quote and news: real quotes and headlines,
written by `drishti.py testdata capture-market`) becomes news/quote and news/news, the same snapshot on every day and never re-dated,
so the pack's quote and headline pages work with no internet (the live connectors off, or DRISHTI_OFFLINE=true). The us-equities
and gleif packs' public data (data/testdata/us-equities and gleif, one snapshot per kind) is built the same way into us-equities/<kind>
and gleif/<kind>, which their Delta connectors us-equities-store and gleif-store read, and the funds pack's (data/testdata/funds, a large
kind in parts listed under `parts:`) into funds/<kind> for funds-store: no pack needs the demo connector.

Connector files: the packs' Delta connectors read the demo lake (lake: demo), ${DRISHTI_DELTA_ROOT:<data>/delta/demo}. With the default lake nothing needs
changing; a connector file (config/connectors/<name>.yaml) that points elsewhere, or any of them when --lake is another folder, is
rewritten in place to point at the lake (its root only; git or a backup keeps the earlier text). A missing file is left for the server to
generate at its first start, except with --lake elsewhere, where it is written from the pack's template.

--with-real FILE[,FILE]: LOCAL ONLY. Reads real trade extracts (a JSON array of trades, as endur.json) and adds, to the local lake
only, the trades themselves (BMO_LEGAL_ENTITY read as LEGAL_ENTITY), a counterparty record for each real counterparty, an ISDA
agreement and a netting set per counterparty and legal entity holding its real trades. Nothing is written to data/testdata, and a
real trade whose id the test data already uses is left out (the test data wins). Staging files live under <data>/build/lake-demo and
are removed at the end. The file connector's sample feed is written to <data>/feeds as well. Needs PyYAML; the default writer (--engine auto) is the parallel Java loader of the server's exec jar when it is found (else Drishti's
Java writer, else deltalake and pyarrow). Independent tables load at the same time (--jobs, --memory-mb); a table the Java loader fails on is loaded again by Drishti's writer.

A server must not keep serving the old figures: its BI store (<data>/bi-store) holds columns and rollups read from the lake. After the
build, lake demo sends the data-load signal (POST /api/v1/packs/{pack}/loads) for every table and business date it wrote to the server
--server names (default DRISHTI_SERVER, then http://localhost:18480; the token from --token-file or DRISHTI_TOKEN). When no server is
reachable, or one refuses the signal, it leaves <bi-store>/lake-rebuilt.json and the server clears its columns and rollups at its next
start. A running server also notices a rebuilt table by itself (a date's Delta files changed) and reads it again. --no-signal skips both;
--signal-once tells each table once, for its newest date (a running server reads the older dates again by itself), not each table and
date: the right choice for a running server (QA3A-01/05).
"""
from __future__ import annotations

import argparse
import contextlib
import json
import os
import pathlib
import re
import shutil
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import date, datetime, timezone

HEAP_MB = 4096                                           # one Java loader's heap (the loader's own default)
HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(HERE))
import lakeconfig  # noqa: E402  (where the lakes are: config/lakes/<lake>.yaml)
if str(ROOT / "tools") not in sys.path:
    sys.path.insert(0, str(ROOT / "tools"))
import resolved as R  # noqa: E402  (configuration: the Java resolver's answer, tools/resolved.py)
from resolved import pack_dir, pack_dirs  # noqa: E402  (where a pack is: the Java resolver's answer, tools/resolved.py)

DEFAULT_ROOT = lakeconfig.DEMO_ROOT_REF   # config/lakes/demo.yaml: the demo lake's own folder
DEFAULT_SERVER = "http://localhost:18480"
SIGNAL_TIMEOUT = 30.0                        # seconds a data-load signal waits for its answer
REBUILT_STAMP = "lake-rebuilt.json"          # read by the server at start (Layers.REBUILT_STAMP)
REPLACE = "overwrite-dates"                  # the load rules' mode replace (lake build --mode): each date the demo writes replaced
REDATE = re.compile(r'("(?:AS_OF|VALUATION_DATE|RUN_DATE|SOURCE_UPDATE_TIME|BUILT_AT)":")(\d{4}-\d{2}-\d{2})')
# A document's embedded market series (a list of {DATE, CLOSE ...}): moved with the business dates too, so a price history has no phantom
# missing days between its series and the stored days (QA1-10). Holidays and coupon schedules are real dates and stay.
SERIES = ("HISTORY", "OHLC", "FIXINGS", "PNL_SERIES")
SERIES_MARK = tuple(f'"{k}":[{{"DATE":"' for k in SERIES)
# Dates a market document holds a fixed number of business days from its business date (an FX spot's value date, an IR curve's pillar
# maturities; tools/testdata/history.py TENOR_DATES and TENOR_POINTS): moved by the same number of business days as the dates.
TENOR_DATES = ("SPOT_DATE", "MATURITY_DATE")   # MATURITY_DATE: a trade's, so the trade feed stays in step with the VaR records and a date move matures nothing
TENOR_POINTS = {"POINTS": "MATURITY"}
TENOR_MARK = ('"SPOT_DATE":"', '"MATURITY_DATE":"', '"POINTS":[{')


def data_dir() -> pathlib.Path:
    return pathlib.Path(os.environ.get("DRISHTI_DATA_DIR") or (ROOT / "data"))


def demo_data_dir(a, lake: pathlib.Path) -> pathlib.Path:
    """The data folder the demo writes beside its lake (the feeds, the staging): --data-dir, else DRISHTI_DATA_DIR, else the folder holding
    the lake when it is a data folder's delta/<lake> or delta/ (--lake /srv/data/delta/demo or /srv/data/delta: /srv/data), else the
    repository's data/. QA2-21: a server on another
    data folder found no feeds there (its file connector DOWN) because they went to the repository."""
    if getattr(a, "data_dir", None):
        return pathlib.Path(a.data_dir).resolve()
    if os.environ.get("DRISHTI_DATA_DIR"):
        return pathlib.Path(os.environ["DRISHTI_DATA_DIR"]).resolve()
    if lake.parent.name == "delta" and lake.name != "delta":
        return lake.parent.parent.resolve()
    if lake.name == "delta":
        return lake.parent.resolve()
    return (ROOT / "data").resolve()


class Manifest:
    def __init__(self, testdata: pathlib.Path):
        import yaml
        self.dir = testdata
        path = testdata / "testdata.yaml"
        if not path.is_file():
            raise SystemExit(f"lake demo: {path} not found (the committed test data; tools/testdata/make_testdata.py writes it)")
        m = yaml.safe_load(path.read_text(encoding="utf-8"))
        self.dates: list[str] = m["business-dates"]
        self.datasets: list[dict] = m["datasets"]


class Packs:
    """The seven packs' manifests: where each connector's tables live (domain, table name) and its template."""

    def __init__(self, packs_dir: pathlib.Path):
        import yaml
        self.dir = packs_dir
        sys.path.insert(0, str(ROOT / "tools"))
        import packlake                               # a lake-form template read with the pack's lake: entries
        raw = {name: yaml.safe_load((p / "pack.yaml").read_text(encoding="utf-8")) for name, p in pack_dirs(packs_dir).items()}
        self.specs = {name: packlake.view(m) for name, m in raw.items()}
        # the connectors a source module brings (modules/source-<system>/connectors/delta.yaml): the server does not generate them
        self.module_connectors = {n for name, m in raw.items() for n in (self.specs[name].get("connectors") or {})
                                  if n not in (m.get("connectors") or {})}

    def connector(self, pack: str, name: str) -> dict:
        return self.specs[pack]["connectors"][name]

    def table(self, pack: str, connector: str, kind: str) -> tuple[str, str]:
        s = self.connector(pack, connector).get("settings") or {}
        return s.get("domain", pack), ((s.get("table") or {}).get(kind) or kind)

    def delta_connectors(self, packs) -> dict[str, tuple[str, dict]]:
        return {name: (pack, c) for pack in packs for name, c in (self.specs[pack].get("connectors") or {}).items() if c.get("plugin") == "delta"}


def _key(doc: dict, key: str) -> str:
    v = doc.get(key)
    return str(int(v)) if isinstance(v, float) and v.is_integer() else str(v)     # ids are text: 1000008, never 1000008.0


class RealExtras:
    """LOCAL ONLY: documents for the real counterparties of real trade extracts, with netting sets holding the real trades."""

    def __init__(self, paths: list[str]):
        self.paths = [pathlib.Path(os.path.expanduser(p.strip())) for p in paths if p.strip()]

    def trades(self) -> list[dict]:
        sys.path.insert(0, str(ROOT / "tools" / "packgen" / "trades"))
        import schema as TS
        out: dict[str, dict] = {}
        for p in self.paths:
            data = json.loads(p.read_text(encoding="utf-8"))
            rows = data if isinstance(data, list) else (data.get("TRADES") or data.get("trades") or [data])
            for t in rows:
                d = TS.text_ids(TS.rename(t))                # a real extract's numeric ids (TRADE_ID, LEG_ID ...) become their text
                out[str(d.get("TRADE_ID"))] = d
        return list(out.values())

    @staticmethod
    def slug(name: str) -> str:
        return re.sub(r"[^A-Z0-9]+", "-", str(name).upper()).strip("-") or "UNNAMED"

    def build(self) -> dict[str, dict[str, str]]:
        """folder -> {id: json}: the extra documents per test-data folder."""
        trades = self.trades()
        extras: dict[str, dict[str, str]] = {}
        by_cp: dict[str, list[dict]] = {}
        for t in trades:
            system = str(t.get("SOURCE_SYSTEM") or "ENDUR").lower()
            folder = f"trades/{system if system in ('endur', 'calypso', 'murex') else 'endur'}"
            extras.setdefault(folder, {})[str(t["TRADE_ID"])] = json.dumps(t, separators=(",", ":"))
            by_cp.setdefault(str(t.get("COUNTERPARTY") or ""), []).append(t)
        for cp, ts in sorted(by_cp.items()):
            if not cp:
                continue
            first = lambda f: next((t.get(f) for t in ts if t.get(f) not in (None, "")), "")  # noqa: E731
            slug = self.slug(cp)
            agr = f"AGR-{slug}-ISDA"
            sets: dict[str, list[dict]] = {}
            for t in ts:
                le = t.get("LEGAL_ENTITY") or ""
                sets.setdefault(f"NS-{slug}-{self.slug(le) if le else 'ALL'}", []).append(t)
            for ns, members in sets.items():
                rows = []
                for t in members:
                    leg = (t.get("LEGS") or [{}])[0]
                    rows.append({"TRADE_ID": t["TRADE_ID"], "PRODUCT": t.get("PRODUCT_SUB_TYPE") or t.get("PRODUCT_TYPE"),
                                 "ASSET_CLASS": t.get("PRODUCT_GROUP") or "Commodity", "CURRENCY": t.get("CURRENCY") or "", "BOOK": t.get("PORTFOLIO") or "",
                                 "NOTIONAL": leg.get("NOTIONAL") or 0, "MATURITY": t.get("MATURITY_DATE") or "", "MTM": 0})
                extras.setdefault("counterparty-risk/netting-set", {})[ns] = json.dumps({
                    "NETTING_SET_ID": ns, "TRADE_COUNT": len(members), "NET_MTM": 0, "TRADES": rows, "TRADE_IDS": [str(t["TRADE_ID"]) for t in members],
                    "COUNTERPARTY": cp, "AGREEMENT": agr, "LEGAL_ENTITY": members[0].get("LEGAL_ENTITY") or "", "NETTING_ENFORCEABLE": True,
                    "SOURCE": "local extract (lake demo --with-real)"}, separators=(",", ":"))
            extras.setdefault("counterparty-risk/agreement", {})[agr] = json.dumps({
                "AGREEMENT_ID": agr, "TYPE": "ISDA 2002 Master", "COUNTERPARTY_NAME": cp, "CLOSE_OUT_NETTING": "Enforceable", "COUNTERPARTY": cp,
                "SOURCE": "local extract (lake demo --with-real)"}, separators=(",", ":"))
            extras.setdefault("core/counterparty", {})[cp] = json.dumps({
                "COUNTERPARTY_ID": cp, "NAME": cp, "COUNTRY_OF_RISK": first("COUNTERPARTY_COUNTRY_OF_RISK"), "UEN": first("COUNTERPARTY_UEN"),
                "LEGAL_ENTITY": first("LEGAL_ENTITY"), "INTERNAL_EXTERNAL_CODE": first("INTERNAL_EXTERNAL_CODE"),
                "NETTING_SETS": [{"ID": ns, "AGREEMENT": agr, "TRADES": len(m), "NET_MTM": 0} for ns, m in sets.items()],
                "SOURCE": "local extract (lake demo --with-real)"}, separators=(",", ":"))
        return extras


class DemoLake:
    def __init__(self, a):
        self.a = a
        self.testdata = pathlib.Path(a.testdata)
        self.lake_name = getattr(a, "lake_name", None) or "demo"      # the lake file whose layout the demo tables take (config/lakes/demo.yaml)
        self.lake = pathlib.Path(a.lake or lakeconfig.root(self.lake_name)).resolve()   # unset: where the lake file says
        self.data = demo_data_dir(a, self.lake)
        self.staging = self.data / "build" / "lake-demo"
        self.packs = Packs(pathlib.Path(a.packs))
        self.manifest = Manifest(self.testdata)
        self.extras = RealExtras(a.with_real.split(",")).build() if a.with_real else {}
        self.tables: list[str] = []
        self.written: list[tuple[str, str, str]] = []    # (pack, kind, table) of every table written
        self.left_out: list[str] = []
        self.staged: dict[tuple[pathlib.Path, str], tuple[int, str | None]] = {}   # day folders staged and kept for a retry: (rows, key)
        self.cleared: set[pathlib.Path] = set()          # tables already cleared in this run (a shared table has several datasets)
        self.dates = self.target_dates(a.as_of)
        self.shift = {o: n for o, n in zip(self.manifest.dates, self.dates) if o != n}
        self.run_id = datetime.now().strftime("%Y%m%dT%H%M%S")
        self.server_note = ""
        self.jobs = max(1, getattr(a, "jobs", None) or self.default_jobs())
        if not getattr(a, "jobs", None):                    # the default jobs: as many as the memory has 4 GB heaps for
            self.jobs = max(1, min(self.jobs, self.default_memory_mb() // HEAP_MB))
        self.memory_mb = getattr(a, "memory_mb", None) or self.jobs * HEAP_MB
        self.engine = getattr(a, "engine", "auto")
        sys.path.insert(0, str(ROOT / "tools"))
        import demonames
        # the bank's own names (config/demo/names.yaml, --names): rewritten as the documents are staged, never in data/testdata
        self.names = demonames.DemoNames.load(getattr(a, "names", None))

    def target_dates(self, as_of: str) -> list[str]:
        """The business dates the lake is written for: the test data's own (``data``), or the same number of USNY business days
        ending on ``today`` (the server's current business date, so the demo opens on data) or on a given date."""
        if as_of == "data":
            return list(self.manifest.dates)
        sys.path.insert(0, str(ROOT / "tools"))
        from datetime import date
        from zoneinfo import ZoneInfo
        from samplegen.dates import Calendar
        from samplegen.lake import business_days
        cal = Calendar.of("USNY")
        end = datetime.now(ZoneInfo("America/New_York")).date() if as_of == "today" else date.fromisoformat(as_of)
        return [d.isoformat() for d in business_days(end, len(self.manifest.dates), cal)]

    def redate(self, line: str) -> str:
        """A document's own dates of the day (AS_OF, VALUATION_DATE, RUN_DATE, SOURCE_UPDATE_TIME, a curve's BUILT_AT) moved with the business dates, and
        the DATE of each point of its embedded series (HISTORY, OHLC, FIXINGS, PNL_SERIES), an FX spot's SPOT_DATE and an IR curve's pillar
        maturities (TENOR_DATES, TENOR_POINTS) by the same number of business days."""
        if not self.shift:
            return line
        line = REDATE.sub(lambda m: m.group(1) + self.shift.get(m.group(2), m.group(2)), line)
        if not any(mark in line for mark in SERIES_MARK + TENOR_MARK):
            return line
        doc = json.loads(line)
        for k in SERIES:
            points = doc.get(k)
            if isinstance(points, list):
                for pt in points:
                    if isinstance(pt, dict) and isinstance(pt.get("DATE"), str) and len(pt["DATE"]) == 10:
                        pt["DATE"] = self.move_day(pt["DATE"])
        for k in TENOR_DATES:                               # an FX spot's value date: T+2 of the moved date, not of the data's
            if isinstance(doc.get(k), str) and len(doc[k]) == 10:
                doc[k] = self.move_day(doc[k])
        for k, f in TENOR_POINTS.items():                   # an IR curve's pillar maturities
            points = doc.get(k)
            if isinstance(points, list):
                for pt in points:
                    if isinstance(pt, dict) and isinstance(pt.get(f), str) and len(pt[f]) == 10:
                        pt[f] = self.move_day(pt[f])
        return json.dumps(doc, separators=(",", ":"), ensure_ascii=False)

    def move_day(self, day: str) -> str:
        """A date moved as the business dates were: a test-data date to its lake date, any other by the same number of USNY business
        days (a series point before the test data's first day keeps its distance from it)."""
        if day in self.shift:
            return self.shift[day]
        if not hasattr(self, "_cal"):
            sys.path.insert(0, str(ROOT / "tools"))
            from samplegen.dates import Calendar
            self._cal = Calendar.of("USNY")
            first_old, first_new = self.manifest.dates[0], self.dates[0]
            a, b = date.fromisoformat(first_old), date.fromisoformat(first_new)
            n, d, step = 0, a, (1 if b >= a else -1)
            while d != b:                                   # business days from the old first day to the new one
                d = self._cal.add_business_days(d, step)
                n += step
            self._offset = n
            self._moved_days: dict[str, str] = {}
        got = self._moved_days.get(day)                     # the same few hundred dates over millions of rows: worked out once
        if got is None:
            try:
                got = self._cal.add_business_days(date.fromisoformat(day), self._offset).isoformat()
            except ValueError:
                got = day
            self._moved_days[day] = got
        return got

    def run(self) -> int:
        if getattr(self.a, "dry_run", False):              # say the names it would apply, build nothing
            print("\n".join(self.names.lines()))
            print("dry run: no lake written")
            return 0
        if self.names and not self.a.quiet:
            print("\n".join(self.names.lines()))
        sys.path.insert(0, str(HERE))
        import stores
        older = stores.older_layout(self.lake)
        if older:
            print("lake demo: " + stores.older_layout_words(self.lake, older), file=sys.stderr)
            return 1
        started = time.monotonic()
        import build as LB
        self.lb = LB
        self.staging.mkdir(parents=True, exist_ok=True)
        self.engine, why = self.choose_engine()
        print(f"  lake writer: {self.engine} ({why}); {self.jobs} dataset{'s' if self.jobs != 1 else ''} at a time")
        worst = 0
        try:
            worst = self.run_groups()
        finally:
            shutil.rmtree(self.staging, ignore_errors=True)
            with contextlib.suppress(OSError):
                self.staging.parent.rmdir()                   # <data>/build, when nothing else is in it
        self.feeds = self.write_feeds()
        elapsed = time.monotonic() - started
        notes = self.connector_files()
        self.server_note = self.tell_server()
        self.report(elapsed, notes)
        return worst

    # ---- engine, concurrency ---------------------------------------------------------------------------------------------------------
    def choose_engine(self) -> tuple[str, str]:
        """The lake writer: the parallel Java loader when the server's exec jar is found (--engine auto, the default), else Drishti's
        Java writer, else the Python deltalake package, the last fallback; an explicit --engine is taken as given."""
        eng = self.a.engine
        if eng != "auto":
            return eng, "asked for"
        sys.path.insert(0, str(HERE))
        import javaload
        import writers
        if javaload.command(getattr(self.a, "jar", None), getattr(self.a, "java", None)):
            return "java", "the parallel lake loader in the server's exec jar"
        name, why = writers.choose("auto", getattr(self.a, "jar", None), getattr(self.a, "java", None))
        return name, (f"the parallel loader was not found: looked for {javaload.looked_for(getattr(self.a, 'jar', None))}, or Java 21; "
                      f"fell back to the {name} engine ({why})")

    @staticmethod
    def default_jobs() -> int:
        return max(1, min(6, (os.cpu_count() or 4) // 4))

    @staticmethod
    def default_memory_mb() -> int:
        """The memory a build may use for its loaders: three quarters of what is available."""
        try:
            with open("/proc/meminfo", encoding="utf-8") as fh:
                for line in fh:
                    if line.startswith("MemAvailable:"):
                        return max(2048, int(line.split()[1]) * 3 // 4096)
        except (OSError, ValueError):
            pass
        return 4 * HEAP_MB

    def engine_args(self) -> list[str]:
        """The loader's own settings for this job: its share of the cores (a table is written by this many threads)."""
        out = ["--engine", self.engine]
        if self.engine == "java":
            out += ["--loader-set", f"parallel.writers={max(1, (os.cpu_count() or 4) // self.jobs)}"]
        for k in ("jar", "java"):
            if getattr(self.a, k, None):
                out += [f"--{k}", getattr(self.a, k)]
        return out

    def group_key(self, ds: dict) -> str:
        """Datasets that write one table (trades_shared) or share a staged folder (the exposure run) form a group, loaded in order, one
        after the other; the groups are independent of each other."""
        if ds.get("folder") in self.EXPOSURE_FOLDERS:
            return "exposure"
        domain, table = self.packs.table(ds["pack"], ds["connector"], ds["kind"])
        return f"{domain}/{table}"

    def size_of(self, ds: dict) -> int:
        folder = self.testdata / (ds.get("folder") or "")
        try:
            return sum(f.stat().st_size for f in folder.rglob("*") if f.is_file()) if ds.get("folder") else 0
        except OSError:
            return 0

    def run_groups(self) -> int:
        """Every wanted dataset, the independent groups at once in a pool of processes (a process each: the staging is Python, so threads
        would share one core), the largest first. The results are merged in the manifest's order, so the lake and its report do not
        depend on who finished first."""
        wanted = [ds for ds in self.manifest.datasets if self.wanted(ds)]
        groups: dict[str, list[dict]] = {}
        for ds in wanted:
            groups.setdefault(self.group_key(ds), []).append(ds)
        order = list(groups)
        self.groups = [groups[k] for k in order]
        self.heap_mb = max(1024, self.memory_mb // self.jobs)
        results: dict[int, tuple] = {}
        by_size = sorted(range(len(self.groups)), key=lambda i: -sum(self.size_of(d) for d in self.groups[i]))
        import multiprocessing
        if self.jobs > 1 and len(self.groups) > 1 and self.engine != "deltalake" and "fork" in multiprocessing.get_all_start_methods():   # (deltalake's runtime does not survive a fork)
            results = self.run_in_processes(by_size)
        else:
            for i in by_size:
                results[i] = self.run_group(i)[1]
        worst = 0
        for i in range(len(self.groups)):                 # the manifest's order
            code, tables, written, left_out, cleared = results[i]
            worst = max(worst, code)
            self.tables += [t for t in tables if t not in self.tables]
            self.written += [w for w in written if w not in self.written]
            self.left_out += left_out
            self.cleared |= cleared
        return worst

    def run_in_processes(self, order: list[int]) -> dict[int, tuple]:
        """The groups, ``jobs`` at a time, each in a forked process of its own (not a daemon: a dated dataset stages its dates in
        processes too); the result of each comes back through a queue."""
        import multiprocessing
        import queue
        ctx = multiprocessing.get_context("fork")
        q = ctx.Queue()
        pending, running, results = list(order), {}, {}
        while pending or running:
            while pending and len(running) < self.jobs:
                i = pending.pop(0)
                running[i] = ctx.Process(target=_group_child, args=(self, i, q), daemon=False)
                running[i].start()
            try:
                i, r = q.get(timeout=2)
                results[i] = r
                running.pop(i).join()
            except queue.Empty:
                for i, pr in list(running.items()):       # one that died without an answer (killed): failed, the rest go on
                    if not pr.is_alive() and pr.exitcode:
                        print(f"  {self.group_key(self.groups[i][0])}: the process ended with code {pr.exitcode}", file=sys.stderr)
                        results[i] = (1, [], [], [], set())
                        running.pop(i)
        return results

    def run_group(self, i: int) -> tuple[int, tuple]:
        started = time.monotonic()
        self.run_engine = self.engine
        i, r = self.run_group_inner(i)
        if not self.a.quiet:
            print(f"  [{self.group_key(self.groups[i][0])} took {time.monotonic() - started:.0f} s]", flush=True)
        return i, r

    def run_group_inner(self, i: int) -> tuple[int, tuple]:
        """One group's datasets in a process of its own (or here, with one job). With the Java loader a table that failed there (a
        table too large for its share of the memory, say) is loaded again, in full, by Drishti's own writer: that table only."""
        os.environ.setdefault("DRISHTI_LAKE_LOADER_HEAP", f"{self.heap_mb}m")
        eng = self.engine
        if eng == "java" and len(self.groups[i]) > 1 and self.group_key(self.groups[i][0]) != "exposure":
            eng = "drishti"        # several systems writing partitions of one table (trades_shared): the Java loader's overwrite-partitions replaces the other systems' too
        for attempt in (eng, "drishti") if eng == "java" else (eng,):
            self.engine = attempt
            self.tables, self.written, self.left_out, self.cleared = [], [], [], set()
            for ds in self.groups[i]:
                code = self.dataset(ds)
                if code:
                    break
            if not code or eng != "java" or attempt == "drishti":
                break
            print(f"  {self.group_key(self.groups[i][0])}: the Java loader failed: loading it again with Drishti's writer", file=sys.stderr, flush=True)
        self.engine = self.run_engine
        for t in list(self.staged):                          # a folder kept for a retry
            shutil.rmtree(t[0], ignore_errors=True)
            self.staged.pop(t)
        return i, (code, self.tables, self.written, self.left_out, self.cleared)

    # ---- the server's BI store ---------------------------------------------------------------------------------------------------------
    def bi_store(self) -> pathlib.Path:
        return pathlib.Path(os.environ.get("DRISHTI_BI_STORE") or (self.data / "bi-store"))

    def tell_server(self) -> str:
        """The data-load signal for every table and date written; else the stamp the server reads at its next start."""
        if self.a.no_signal or not self.written:
            return "not told (--no-signal)" if self.a.no_signal else ""
        base = (self.a.server or os.environ.get("DRISHTI_SERVER") or DEFAULT_SERVER).rstrip("/")
        token = None
        if self.a.token_file:
            token = pathlib.Path(os.path.expanduser(self.a.token_file)).read_text(encoding="utf-8").strip()
        token = token or os.environ.get("DRISHTI_TOKEN")
        headers = {"Content-Type": "application/json", "Accept": "application/json"}
        if token:
            headers["Authorization"] = "Bearer " + token
        elif os.environ.get("DRISHTI_USER"):
            headers["X-Drishti-User"] = os.environ["DRISHTI_USER"]
        sent, refused, unanswered, why, slow = 0, 0, 0, "", ""
        once = bool(getattr(self.a, "signal_once", False))
        signals = [(pack, kind, day) for pack, kind, _table in self.written for day in (self.dates[-1:] if once else self.dates)]
        t0 = time.monotonic()
        try:
            with urllib.request.urlopen(urllib.request.Request(base + "/actuator/health"), timeout=3):   # noqa: S310 - the operator's --server
                reachable = True
        except urllib.error.HTTPError:
            reachable = True                                  # it answered: it is there
        except (urllib.error.URLError, OSError):
            reachable = False
        if reachable:
            if not getattr(self.a, "quiet", False):
                print(f"Telling {base}: {len(signals)} data-load signal(s) ({'each table once, its newest date' if once else 'each table, each date'})",
                      flush=True)
            for pack, kind, day in signals:
                body = {"kind": kind, "businessDate": day, "status": "ready", "source": "lake demo", "batchId": f"lake-demo-{self.run_id}"}
                url = base + f"/api/v1/packs/{urllib.parse.quote(pack, safe='')}/loads"
                req = urllib.request.Request(url, data=json.dumps(body).encode(), headers=headers, method="POST")
                try:
                    with urllib.request.urlopen(req, timeout=SIGNAL_TIMEOUT):     # noqa: S310 - the operator's --server
                        sent += 1
                except urllib.error.HTTPError as e:               # the server answered no: refused
                    refused += 1
                    why = why or f"{e.code} {e.read().decode('utf-8', 'replace')[:160]}"
                    e.close()
                except (urllib.error.URLError, OSError) as e:     # no answer in time, or the connection dropped: not a refusal
                    unanswered += 1
                    slow = slow or str(getattr(e, "reason", e))
            took = f" in {time.monotonic() - t0:,.0f} s"
            self.signal_seconds = time.monotonic() - t0
            if not refused and not unanswered:
                return f"{base} told: {sent} data-load signals{took} ({'each table once, its newest date' if once else 'each table, each date'}); it reads them again"
        stamp = self.bi_store() / REBUILT_STAMP
        stamp.parent.mkdir(parents=True, exist_ok=True)
        stamp.write_text(json.dumps({"at": datetime.now(timezone.utc).isoformat(timespec="seconds"), "by": "drishti.py lake demo", "lake": str(self.lake),
                                     "dates": self.dates, "tables": [t for _, _, t in self.written]}, indent=1) + "\n", encoding="utf-8")
        if not reachable:
            return (f"no server at {base}: left {stamp}, so a server clears its stored BI columns and rollups at its next start")
        parts = []
        if refused:
            hint = "; a token with loads:write: --token-file or DRISHTI_TOKEN" if why[:3] in ("401", "403") else ""
            parts.append(f"refused {refused} ({why.strip()}{hint})")
        if unanswered:
            parts.append(f"did not answer {unanswered} within {SIGNAL_TIMEOUT:g} s ({slow.strip()}; the server is busy: it reads the new data)")
        return (f"{base} took {sent} of {len(signals)} data-load signals{took}, " + ", ".join(parts)
                + f": left {stamp} for its next start; a running server also re-reads a rebuilt table by itself")

    def wanted(self, ds: dict) -> bool:
        only = [x.strip() for x in (self.a.only or "").split(",") if x.strip()]
        name = ds.get("folder") or f"{ds['pack']}/{ds['kind']}"
        return not only or any(name.startswith(x) or ds["pack"] == x for x in only)

    # ---- one dataset -------------------------------------------------------------------------------------------------------------------
    def states(self, ds: dict):
        import feedfiles
        if "snapshot" in ds:                                  # a captured snapshot (real, dated): the same documents every day
            state = {}
            got = feedfiles.read_table(self.testdata / ds["folder"])
            for part in got[max(got)] if got else []:          # the newest snapshot's files (a large one a tar of parts: the funds' holdings)
                for line in part.lines():
                    state[_key(json.loads(line), ds["key"])] = line
            for _ in self.manifest.dates:
                yield state
            return
        if "samples" in ds:
            sys.path.insert(0, str(ROOT / "tools" / "packgen" / "common"))
            import packsamples
            docs = packsamples.read(ROOT / ds["samples"]).get(ds["kind"], {})
            state = {}
            for id_, d in docs.items():
                d = {k: v for k, v in d.items() if k != "_meta"}
                state[str(id_)] = json.dumps(d, separators=(",", ":"), ensure_ascii=False)
            for _ in self.manifest.dates:
                yield state
            return
        folder, key = self.testdata / ds["folder"], ds["key"]
        state: dict[str, str] = {}
        extra = self.extras.get(ds["folder"], {})
        parts = feedfiles.read_table(folder)                  # <table>.<YYYY-MM-DD>.jsonl.gz: the first day's snapshot, then each day's changes
        for day in self.manifest.dates:
            for part in parts.get(day, []):
                for line in part.lines():
                    state[_key(json.loads(line), key)] = line
            if extra:
                clash = [k for k in extra if k in state and k not in self.left_out]
                self.left_out += clash
                yield {**{k: v for k, v in extra.items() if k not in state}, **state}
            else:
                yield state

    #: the fields of a dated dataset's documents (``dated-by``) that hold a date of the data, moved with the business dates
    DATED_FIELDS = ("BUSINESS_DATE", "COMPUTED_AT", "BATCH_ID", "SOURCE_BATCH", "WINDOW_START", "WINDOW_END",
                    # an exposure run's and its paths' (docs/guides/EXPOSURE_GUIDE.md section 15): the run id, a row's id and grid date carry dates
                    "RUN_ID", "PATH_ROW_ID", "GRID_DATE", "MARKET_DATA_DATE",
                    # scenario PVs (VAR_GUIDE.md section 15.9) are valued on their business date: moved with it, or the engine rejects them
                    "VALUATION_DATE", "BASE_VALUATION_DATE",
                    # a record's maturity moves with its valuation date, or a date move would mature records (and leave matured trades inside live
                    # positions) that are live on the test data's own date: the demo gives the same figures whatever day it is built
                    "MATURITY_DATE")
    #: the date lists moved the same way: a scenario set's days, an exposure run's grid
    DATED_LISTS = ("SCENARIO_DATES", "GRID_DATES")
    #: a stress scenario set's fields that name its historical period: kept as they are
    STRESS_WINDOW = ("WINDOW_START", "WINDOW_END", "SCENARIO_DATES")
    _ISO = re.compile(r"\d{4}-\d{2}-\d{2}")

    def redate_dated(self, doc: dict) -> dict:
        """A dated document's own dates moved by as many business days as the test data's (its BUSINESS_DATE, the batch that wrote it,
        a scenario set's window and its SCENARIO_DATES, an exposure run's id and grid), so a 250-day history, a 509-day scenario window
        or an exposure grid stays whole and in order."""
        if not self.shift:
            return doc
        # a stress set's window is a fixed period of market history (HS-STRESS-2008: 2008-07-01 to 2009-06-30), the same days the long
        # history (pnl-history's HISTORY_DATES, never moved) holds: only its business date moves, or the stress window page would read
        # a window a month later than the set's own (VAR_GUIDE.md section 18.9)
        fixed = self.STRESS_WINDOW if doc.get("SET_TYPE") == "STRESS" else ()
        for f in self.DATED_FIELDS:
            if isinstance(doc.get(f), str) and f not in fixed:
                doc[f] = self._ISO.sub(lambda m: self.move_day(m.group(0)), doc[f])
        for f in self.DATED_LISTS:
            if isinstance(doc.get(f), list) and f not in fixed:
                doc[f] = [self.move_day(d) if isinstance(d, str) else d for d in doc[f]]
        return doc

    #: the exposure run's folders: generated for the demo in place of the committed run (``--exposure demo``, the default)
    EXPOSURE_FOLDERS = ("counterparty-risk/simulation-run", "counterparty-risk/exposure-path")

    def dated_source(self, ds: dict) -> pathlib.Path:
        """Where a dated dataset's files are read: the committed folder, except the exposure run with ``--exposure demo``: the demo run
        (tools/packgen/ccr/make_ccr_data.py ``write_demo``: every netting set of 23 of the 24 counterparties, 8,022 live trades, the
        committed run's paths and grid, so the committed sets' figures are the same), written once into the staging folder from the
        committed netting sets and CSAs: about a minute and 100 MB, never committed, nothing downloaded."""
        if self.a.exposure != "demo" or ds["folder"] not in self.EXPOSURE_FOLDERS:
            return self.testdata / ds["folder"]
        out = self.staging / "exposure-demo"
        if not (out / ds["folder"]).is_dir() or not any((out / ds["folder"]).iterdir()):
            sys.path.insert(0, str(ROOT / "tools" / "packgen" / "ccr"))
            import make_ccr_data
            started = time.monotonic()
            # members live on the lake's own business date: a trade that matured between the data's date and it has no paths
            n_sets, n_trades, _ = make_ccr_data.write_demo(self.testdata, out, self.manifest.dates, self.dates[-1])
            if not self.a.quiet:
                print(f"  exposure demo run: {n_sets} netting sets, {n_trades:,} trades generated in {time.monotonic() - started:.0f} s")
        return out / ds["folder"]

    def dated_dataset(self, ds: dict, path: pathlib.Path, domain: str, table: str) -> int:
        """A dataset whose files carry their business dates (testdata.yaml ``dated-by``: the VaR engine's vectors of the last business
        dates, the desks' 260-day P&L, the exposure run): each date's files (a dated file, or a tar's date folder; feedfiles.py) staged as
        one file named by its moved date, <table>.<YYYY-MM-DD>.jsonl, every date loaded in one run by the load rules (the date from the
        name, mode replace). A row's own dates move with it (redate_dated), so its BUSINESS_DATE stays its file's."""
        import feedfiles
        folder = self.staging / f"{domain}-{table}"
        shutil.rmtree(folder, ignore_errors=True)
        folder.mkdir(parents=True)
        self._dated = feedfiles.read_table(self.dated_source(ds))
        self._dated_folder, self._dated_table = folder, table
        days = list(self._dated)
        global _STAGER
        _STAGER = self
        import multiprocessing
        if self.engine != "deltalake" and len(days) > 1 and "fork" in multiprocessing.get_all_start_methods():
            # each date is its own file: staged by a process each (a date's rows are moved by Python, one core a date)
            with multiprocessing.get_context("fork").Pool(min(len(days), max(2, (os.cpu_count() or 4) // 2))) as pool:
                done = pool.map(_stage_dated_day, days)
        else:
            done = [self.stage_dated_day(day) for day in days]
        rows, other = sum(r for r, _ in done), sum(o for _, o in done)
        self._dated = None
        argv = ["--from", str(folder), "--pack", str(pack_dir(ds["pack"], self.packs.dir)), "--kind", ds["kind"], "--connector", ds["connector"],
                "--key", ds["key"], "--mode", REPLACE, "--lake", self.lake_name, "--lake-root", str(self.lake), *self.engine_args(),
                "-q", "--no-stamp"]
        code, summary = self.lb.run(self.lb.build_parser().parse_args(argv), say=lambda *x, **k: None)
        if code:
            print(f"  {domain}/{table}: {summary.get('status')} {summary.get('error', '')}", file=sys.stderr)
        if other:
            print(f"  {domain}/{table}: {other:,} rows whose BUSINESS_DATE is not their file's date (loaded under the file's)", file=sys.stderr)
        shutil.rmtree(folder, ignore_errors=True)
        self.checkpoint(path)
        self.tables.append(f"{domain}/{table}")
        self.written.append((ds["pack"], ds["kind"], f"{domain}/{table}"))
        if not self.a.quiet:
            days = len(summary.get("rows") or {})
            print(f"  {domain}/{table}: {rows:,} rows over {days} day{'s' if days != 1 else ''} (dated by their files)"
                  + (" (failed)" if code == 1 else ""))
        return code

    def stage_dated_day(self, day: str) -> tuple[int, int]:
        """One date of a dated dataset staged as <table>.<moved date>.jsonl; (rows, rows whose BUSINESS_DATE is not the file's)."""
        moved = self.move_day(day) if self.shift else day
        rows, other = 0, 0
        with (self._dated_folder / f"{self._dated_table}.{moved}.jsonl").open("w", encoding="utf-8") as fh:
            for part in self._dated[day]:
                for line in part.lines():
                    doc = self.names.rewrite(self.redate_dated(json.loads(line)))
                    if doc.get("BUSINESS_DATE") not in (None, moved):
                        other += 1                        # counted only: the file's date is the row's (the rules, section 1)
                    fh.write(json.dumps(doc, separators=(",", ":"), ensure_ascii=False) + "\n")
                    rows += 1
        return rows, other

    def stage_days(self, ds: dict, table: str, days_dir: pathlib.Path, key: str | None) -> tuple[int, str | None]:
        """Every day of an undated dataset staged as <table>.<YYYY-MM-DD>.jsonl in one folder, its documents moved to the lake's dates
        and renamed by the configured names; (rows, the key)."""
        shutil.rmtree(days_dir, ignore_errors=True)
        days_dir.mkdir(parents=True)
        rows = 0
        moved: dict[str, tuple[str, str]] = {}               # key -> (the line, its moved and renamed text): a document that did not change
        snapshot = "snapshot" in ds                           # between two days is moved once, not once a day
        for day, state in zip(self.dates, self.states(ds)):
            if key is None:
                key = next((k for k in json.loads(next(iter(state.values()))) if k.endswith("_ID")), "id") if state else "id"
            out = []
            for k, line in state.items():
                got = moved.get(k)
                if got is None or got[0] != line:
                    got = moved[k] = (line, self.names.rewrite_line(line if snapshot else self.redate(line)))   # the configured names
                out.append(got[1])
            with (days_dir / f"{table}.{day}.jsonl").open("w", encoding="utf-8") as fh:
                fh.write("\n".join(out) + "\n" if out else "")
            rows += len(state)
        return rows, key

    def dataset(self, ds: dict) -> int:
        pack, kind, connector = ds["pack"], ds["kind"], ds["connector"]
        domain, table = self.packs.table(pack, connector, kind)
        path = self.lake / domain / table
        mode = ds.get("mode", REPLACE)                    # a shared table: each system replaces only its own partitions (SOURCE_SYSTEM)
        if not (getattr(self.a, "keep_other_dates", False) and ds.get("dated-by") and path.is_dir()):
            if path not in self.cleared:                  # a table several datasets share (trades_shared) is cleared once, by the first
                shutil.rmtree(path, ignore_errors=True)   # --keep-other-dates: a dated table's other business dates stay (each date replaced)
        if ds.get("dated-by"):
            return self.dated_dataset(ds, path, domain, table)
        key = ds.get("key")
        worst, rows = 0, 0
        # every day staged under its name into one folder, then one load of the folder: the load takes each file's date from its name and
        # replaces each date (one commit a date), one run (one JVM with --engine java) for the table instead of one a day
        days_dir = self.staging / f"{table}.days"
        tag = (days_dir, ds.get("folder") or ds["kind"])      # a table several datasets share has one folder, one dataset at a time
        if tag not in self.staged:                            # (kept when the Java loader failed on it: Drishti's writer loads the same files)
            for old in [t for t in self.staged if t[0] == days_dir]:
                self.staged.pop(old)                          # another dataset's, kept for a retry: replaced
            self.staged[tag] = self.stage_days(ds, table, days_dir, key)
        rows, key = self.staged[tag]
        argv = ["--from", str(days_dir), "--pack", str(pack_dir(pack, self.packs.dir)), "--kind", kind, "--connector", connector, "--key", key or "id",
                "--mode", mode, "--lake", self.lake_name, "--lake-root", str(self.lake), *self.engine_args(), "-q", "--no-stamp"]
        code, summary = self.lb.run(self.lb.build_parser().parse_args(argv), say=lambda *x, **k: None)
        if code:
            print(f"  {domain}/{table}: {summary.get('status')} {summary.get('error', '')}", file=sys.stderr)
        worst = max(worst, code)
        if not (code and self.engine == "java"):             # kept for the retry of a failed Java load
            shutil.rmtree(days_dir, ignore_errors=True)
            self.staged.pop(tag, None)
        self.cleared.add(path)
        self.checkpoint(path)
        if f"{domain}/{table}" not in self.tables:      # a table several datasets share is listed, and the server told, once
            self.tables.append(f"{domain}/{table}")
            self.written.append((pack, kind, f"{domain}/{table}"))
        if not self.a.quiet:
            note = f" (snapshot captured {ds.get('captured') or ds['snapshot']})" if "snapshot" in ds else ""
            print(f"  {domain}/{table}: {rows:,} rows over {len(self.manifest.dates)} days{note}" + (" (failed)" if worst == 1 else ""))
        return worst

    def write_feeds(self) -> pathlib.Path:
        """<data>/feeds: the sample feed the file connector serves (SOFR history), as tools/packgen/banking/make_data.py writes it."""
        sys.path.insert(0, str(ROOT / "tools" / "packgen" / "banking"))
        import make_data
        return make_data.write_feeds(self.data / "feeds")

    @staticmethod
    def checkpoint(path: pathlib.Path) -> None:
        """A checkpoint after the table's commits (a partitioned table takes one a partition a day), so a reader replays one file."""
        try:
            from deltalake import DeltaTable
            DeltaTable(str(path)).create_checkpoint()
        except BaseException as e:                            # noqa: BLE001 - an optimisation: the table reads without it
            if isinstance(e, KeyboardInterrupt):
                raise
            pass

    # ---- connector files -----------------------------------------------------------------------------------------------------------------
    def resolve(self, root: str) -> pathlib.Path:
        m = re.fullmatch(r"\$\{" + lakeconfig.DEMO_ROOT_ENV + r":(.*)\}", root.strip())
        if m:
            if os.environ.get(lakeconfig.DEMO_ROOT_ENV):
                return pathlib.Path(os.environ[lakeconfig.DEMO_ROOT_ENV]).resolve()
            root = m.group(1)
        root = root.replace("${lake.data.dir:./data}", str(self.data)).replace("${drishti.data.dir:./data}", str(self.data))
        p = pathlib.Path(root)
        return (p if p.is_absolute() else ROOT / p).resolve()

    def connector_files(self) -> list[str]:
        """The connectors that read the demo lake, through their layers (the Java resolver's answer, tools/resolved.py): a template that reads the
        lake already needs no file; another lake folder is a partial override of the site (``template:`` and ``settings.root``), never a
        whole connector; a site file of another plugin is left as it is. No bindings are written: the trade kind reads what the source
        modules that are on declare (KERNEL_AND_MODULES.md 18.6)."""
        import yaml
        folder = pathlib.Path(self.a.connectors_dir) if self.a.connectors_dir else R.current().connectors_dir
        layered = R.repository(connectors=folder).connectors           # each connector as the Java resolver merges it over the folder's files
        templates = layered.templates
        default = self.lake == self.resolve(DEFAULT_ROOT)
        notes = []
        packs = sorted({ds["pack"] for ds in self.manifest.datasets if self.wanted(ds)})
        for name, (pack, template) in sorted(self.packs.delta_connectors(packs).items()):
            f = folder / f"{name}.yaml"
            want = DEFAULT_ROOT if default else str(self.lake)
            if (template.get("settings") or {}).get("domain") == "risk-results":
                notes.append(self.results_reader(name, f))
                continue
            text = f.read_text(encoding="utf-8") if f.is_file() else None
            e = layered.find(name)
            if e is None:
                notes.append(f"{name}: {layered.unused.get(name) or 'no template and no file'}")
                continue
            if e.plugin != "delta":                              # a system stored elsewhere (build_demo_lakes.py --store calypso=iceberg)
                notes.append(f"{name}: reads {e.plugin}, left as it is ({f})")
                continue
            root = e.settings.get("root", DEFAULT_ROOT)
            if self.resolve(root) == self.lake:
                notes.append(f"{name}: already reads {self.lake}" + ("" if text is not None else " (its template)"))
                continue
            if text is None:
                t = templates.get(name)
                body = {"template": t.owner if t else f"pack {pack}", "settings": {"root": want}}
                folder.mkdir(parents=True, exist_ok=True)
                f.write_text(f"# Written by drishti.py lake demo: reads the demo lake {self.lake}; every other key is the template's.\n"
                             + yaml.safe_dump(body, sort_keys=False), encoding="utf-8")
                notes.append(f"{name}: override written ({f})")
                continue
            new, n = re.subn(r"(?m)^(\s+root:\s*).*$", lambda m: m.group(1) + json.dumps(want), text, count=1)
            if not n:
                doc = yaml.safe_load(text) or {}
                doc.setdefault("settings", {})["root"] = want
                new = yaml.safe_dump(doc, sort_keys=False)
            f.write_text(new, encoding="utf-8")
            notes.append(f"{name}: now reads {self.lake} (was {root})")
        return notes

    def results_reader(self, name: str, f: pathlib.Path) -> str:
        """A reader of the official results (var-results, exposure-results: domain risk-results) reads where the server publishes them
        (drishti.var.official.root, drishti.ccr.exposure.official.root), not the demo lake, and follows the publishing format
        (OfficialReaders): it is left to the server, which generates it at start with its template stamp. A file an earlier lake demo
        wrote (no stamp: it counted as edited and never followed the format) is moved to the history, so the server writes the stamped one."""
        if not f.is_file():
            return f"{name}: generated by the server at its start (reads the official results where the server publishes them)"
        text = f.read_text(encoding="utf-8")
        if not text.startswith("# Written by drishti.py lake demo"):
            return f"{name}: left as it is ({f})"
        hist = self.data / "connectors" / "history"
        hist.mkdir(parents=True, exist_ok=True)
        (hist / f"{name}.{datetime.now().strftime('%Y%m%dT%H%M%S%f')[:-3]}.yaml").write_text(text, encoding="utf-8")
        f.unlink()
        return (f"{name}: the file an earlier lake demo wrote is removed (kept in {hist}); the server generates it at its next start, "
                "stamped, so it follows the official format")

    # ---- what next -----------------------------------------------------------------------------------------------------------------------
    def report(self, elapsed: float, notes: list[str]) -> None:
        d = self.dates
        moved = f" (the test data's {self.manifest.dates[0]} to {self.manifest.dates[-1]}, moved to end on {d[-1]})" if self.shift else ""
        sig = getattr(self, "signal_seconds", 0.0)
        took = f"built in {elapsed:.0f} s" + (f", the server told in {sig:.0f} s ({elapsed + sig:.0f} s in all)" if sig >= 1 else "")
        print(f"\nDemo lake ready: {self.lake}: {len(self.tables)} tables, {len(d)} business days {d[0]} to {d[-1]}{moved}, {took}"
              + (f"; real extracts added locally ({sum(len(v) for v in self.extras.values())} documents)" if self.extras else ""))
        if self.left_out:
            print(f"  left out {len(self.left_out)} real document(s) whose id the test data already uses")
        print(f"Feeds: {self.feeds} (the file connector's sample feed)")
        if self.server_note:
            print(f"Server: {self.server_note}")
        print("Connector files:")
        for n in notes:
            print("  " + n)
        env = "" if self.lake == self.resolve(DEFAULT_ROOT) else f"{lakeconfig.DEMO_ROOT_ENV}={self.lake} "
        win = os.name == "nt"
        start = "py tools\\drishti.py start all --demo" if win else "python tools/drishti.py start all --demo"
        print("\nNext:\n"
              "  1. Start the server and the console on the lakes (samples off, offline, sign-in on, the development secrets: never for production):\n"
              f"     {start}   (from {ROOT}; docs/DEPLOYMENT_AND_QA_GUIDE.md section 7)\n"
              + (f"     this lake is not the default one: start the server with --lake-root {self.lake} (drishti.py start server)\n" if env else "")
              +
              "     or the IDE run configurations 'Drishti server (demo lakes)' and 'Drishti console'\n"
              "  2. Sign in as drishti-dev-admin / drishti-dev-admin123\n"
              "  3. Open (console port 17480 by default):\n"
              "     /v/trade/1000001             an ENDUR ComSwap (legs side by side, cash flows and resets)\n"
              "     /v/trade/CLY-3000157         a CALYPSO government bond;   /v/trade/MX-20000001   a MUREX swap\n"
              "     /v/counterparty-trades/CP-NORTHPLAINS   a counterparty's live trades, payments and maturity ladder\n"
              "     /v/netting-set/NS-NORTHPLAINS-NY        a netting set and its trades\n"
              f"     Compare a trade across two days ({d[0]} and {d[-1]}): the date picker, then Compare on the trade page\n"
              "  data/testdata/README.md has the rest (the BI samples over the sixty days, the manual way).")


def add_arguments(p: argparse.ArgumentParser) -> argparse.ArgumentParser:
    p.add_argument("--testdata", default=str(ROOT / "data" / "testdata"), help="the committed test data (default data/testdata)")
    p.add_argument("--lake-name", default="demo", help="the lake file (config/lakes/<name>.yaml) that gives every demo table its layout (default demo)")
    p.add_argument("--lake", help="the folder to build the lake into (default: the root of --lake-name's lake file, config/lakes/demo.yaml: "
                   "<data>/delta/demo, what the packs read)")
    p.add_argument("--data-dir", help="the data folder the feeds and the staging go to (default DRISHTI_DATA_DIR, else the folder holding "
                   "--lake when it is in a delta/ folder, else data/): the server's own data folder")
    p.add_argument("--packs", default=str(R.REPO_PACKS), help="the site's packs folder (default config/packs; the modules' packs are found by the resolver)")
    p.add_argument("--connectors-dir", help="the connector files' folder (default DRISHTI_CONNECTORS_DIR, else config/connectors)")
    p.add_argument("--as-of", default="today", metavar="today|data|YYYY-MM-DD", help="the last business date of the lake: today (default: "
                   "the server's current business date, so the demo opens on data), data (the test data's own dates) or a date")
    p.add_argument("--engine", choices=("auto", "drishti", "deltalake", "java"), default="auto", help="the lake writer (default auto: the parallel lake loader "
                   "when the server's exec jar is found, else Drishti's Java writer, else the Python deltalake package; a table the Java loader fails on is loaded again by Drishti's writer)")
    p.add_argument("--jobs", type=int, metavar="N", help="datasets loaded at the same time (default: a quarter of the cores, at most 6)")
    p.add_argument("--memory-mb", type=int, metavar="MB", help="the loaders' heaps together, shared by the jobs (default: 4 GB a job)")
    p.add_argument("--jar", help="the drishti-server exec jar for the Java loader (default: the newest in <home>/lib, made by drishti.py build jar)")
    p.add_argument("--java", help="the java executable (default: drishti.java.home of --settings, DRISHTI_JAVA_HOME, then JAVA_HOME, then java on the PATH; Java 21+)")
    p.add_argument("--with-real", metavar="FILE[,FILE]", help="LOCAL ONLY: real trade extracts (JSON) whose counterparties, agreements and "
                   "netting sets are added to the local lake, never to data/testdata")
    p.add_argument("--only", metavar="PREFIX[,PREFIX]", help="only these datasets: a folder prefix (trades/, core/counterparty) or a pack name")
    p.add_argument("--server", help=f"the server told of the new data (default: DRISHTI_SERVER, then {DEFAULT_SERVER})")
    p.add_argument("--token-file", help="the API token for the data-load signal (default: DRISHTI_TOKEN); never printed")
    p.add_argument("--no-signal", action="store_true", help="tell no server, and leave no stamp for one")
    p.add_argument("--signal-once", action="store_true", help="tell each table once, for its newest date, not each table and date "
                   "(a running server reads the older dates again by itself)")
    p.add_argument("--exposure", choices=("demo", "committed"), default="demo", help="the exposure paths: demo (default) generates every "
                   "netting set of 23 of the 24 counterparties (about 8,000 live trades, a minute, nothing downloaded), committed loads the "
                   "committed run of eight sets")
    p.add_argument("--names", metavar="FILE", help="the bank's own names (default config/demo/names.yaml): every value that is a default name, "
                   "or a default name and a comma (a branch), is renamed as it is loaded; LEIs and BICs stay")
    p.add_argument("--keep-other-dates", action="store_true", help="add the dated tables' dates to the lake, keeping the dates already "
                   "there (each date written is replaced): the VaR data's three dates are 2026-09-28 to 2026-09-30, `--as-of 2026-09-25 "
                   "--keep-other-dates` adds them again as 2026-09-23 to 2026-09-25, the scenario window moved with them (the scenario matrix, "
                   "the VaR tutorial, step 16); an undated table is rebuilt as always")
    p.add_argument("--dry-run", action="store_true", help="print the names mapping it would apply and build nothing")
    p.add_argument("-q", "--quiet", action="store_true", help="only the summary")
    return p


def build_parser(add_help: bool = True) -> argparse.ArgumentParser:
    return add_arguments(argparse.ArgumentParser(prog="lake demo", description=__doc__.split("\n\n")[0], add_help=add_help,
                                                 formatter_class=argparse.RawDescriptionHelpFormatter, epilog=__doc__.split("\n\n", 1)[1]))


def _group_child(lake: "DemoLake", i: int, q) -> None:
    q.put(lake.run_group(i))


_STAGER = None


def _stage_dated_day(day: str) -> tuple[int, int]:
    return _STAGER.stage_dated_day(day)


def run(a) -> int:
    return DemoLake(a).run()


if __name__ == "__main__":
    sys.exit(run(build_parser().parse_args()))
