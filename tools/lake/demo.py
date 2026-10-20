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

For every dataset of data/testdata/testdata.yaml (written by tools/testdata/make_testdata.py) it rebuilds the seven daily snapshots
(the first day's snapshot, then each later day's changes applied by key) and writes each day with `lake build` (tools/lake/build.py)
into the table the dataset's Delta connector reads: <lake>/<domain>/<kind>/, the trades into trading/trade_endur, trade_calypso and
trade_murex, partitioned by business date (and PRODUCT_TYPE for trades). Each table is written afresh (its folder is removed first).
Datasets of static reference data (world-economy, news-and-markets) are built from their packs' samples, the same on every day.
The news-and-markets pack's captured market snapshot (data/testdata/news-and-markets/quote and news: real quotes and headlines,
written by `drishti.py testdata capture-market`) becomes news/quote and news/news, the same snapshot on every day and never re-dated,
so the pack's quote and headline pages work with no internet (the live connectors off, or DRISHTI_OFFLINE=true).

Connector files: the seven packs' Delta connectors read ${DRISHTI_DELTA_ROOT:<data>/delta}. With the default lake nothing needs
changing; a connector file (config/connectors/<name>.yaml) that points elsewhere, or any of them when --lake is another folder, is
written to point at the lake (its earlier text kept in <data>/connectors/history/). A missing file is left for the server to
generate at its first start, except with --lake elsewhere, where it is written from the pack's template.

--with-real FILE[,FILE]: LOCAL ONLY. Reads real trade extracts (a JSON array of trades, as endur.json) and adds, to the local lake
only, the trades themselves (BMO_LEGAL_ENTITY read as LEGAL_ENTITY), a counterparty record for each real counterparty, an ISDA
agreement and a netting set per counterparty and legal entity holding its real trades. Nothing is written to data/testdata, and a
real trade whose id the test data already uses is left out (the test data wins). Staging files live under <data>/build/lake-demo and
are removed at the end. The file connector's sample feed is written to <data>/feeds as well. Needs PyYAML, and deltalake and pyarrow (engine deltalake, the default; --engine auto uses the server jar).

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
import gzip
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

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parents[1]
DEFAULT_ROOT = "${DRISHTI_DELTA_ROOT:${drishti.data.dir:./data}/delta}"
DEFAULT_SERVER = "http://localhost:18480"
SIGNAL_TIMEOUT = 30.0                        # seconds a data-load signal waits for its answer
REBUILT_STAMP = "lake-rebuilt.json"          # read by the server at start (Layers.REBUILT_STAMP)
REDATE = re.compile(r'("(?:AS_OF|VALUATION_DATE|RUN_DATE|SOURCE_UPDATE_TIME|BUILT_AT)":")(\d{4}-\d{2}-\d{2})')
# A document's embedded market series (a list of {DATE, CLOSE ...}): moved with the business dates too, so a price history has no phantom
# missing days between its series and the stored days (QA1-10). Holidays and coupon schedules are real dates and stay.
SERIES = ("HISTORY", "OHLC", "FIXINGS", "PNL_SERIES")
SERIES_MARK = tuple(f'"{k}":[{{"DATE":"' for k in SERIES)


def data_dir() -> pathlib.Path:
    return pathlib.Path(os.environ.get("DRISHTI_DATA_DIR") or (ROOT / "data"))


def demo_data_dir(a, lake: pathlib.Path) -> pathlib.Path:
    """The data folder the demo writes beside its lake (the feeds, the staging): --data-dir, else DRISHTI_DATA_DIR, else the folder holding
    the lake when it is a data folder's delta/ (--lake /srv/data/delta: /srv/data), else the repository's data/. QA2-21: a server on another
    data folder found no feeds there (its file connector DOWN) because they went to the repository."""
    if getattr(a, "data_dir", None):
        return pathlib.Path(a.data_dir).resolve()
    if os.environ.get("DRISHTI_DATA_DIR"):
        return pathlib.Path(os.environ["DRISHTI_DATA_DIR"]).resolve()
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
        self.specs = {p.name: yaml.safe_load((p / "pack.yaml").read_text(encoding="utf-8"))
                      for p in sorted(packs_dir.iterdir()) if (p / "pack.yaml").is_file()}

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
        self.lake = pathlib.Path(a.lake).resolve()
        self.data = demo_data_dir(a, self.lake)
        self.staging = self.data / "build" / "lake-demo"
        self.packs = Packs(pathlib.Path(a.packs))
        self.manifest = Manifest(self.testdata)
        self.extras = RealExtras(a.with_real.split(",")).build() if a.with_real else {}
        self.tables: list[str] = []
        self.written: list[tuple[str, str, str]] = []    # (pack, kind, table) of every table written
        self.left_out: list[str] = []
        self.dates = self.target_dates(a.as_of)
        self.shift = {o: n for o, n in zip(self.manifest.dates, self.dates) if o != n}
        self.run_id = datetime.now().strftime("%Y%m%dT%H%M%S")
        self.server_note = ""

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
        the DATE of each point of its embedded series (HISTORY, OHLC, FIXINGS, PNL_SERIES) by the same number of business days."""
        if not self.shift:
            return line
        line = REDATE.sub(lambda m: m.group(1) + self.shift.get(m.group(2), m.group(2)), line)
        if not any(mark in line for mark in SERIES_MARK):
            return line
        doc = json.loads(line)
        for k in SERIES:
            points = doc.get(k)
            if isinstance(points, list):
                for pt in points:
                    if isinstance(pt, dict) and isinstance(pt.get("DATE"), str) and len(pt["DATE"]) == 10:
                        pt["DATE"] = self.move_day(pt["DATE"])
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
        try:
            return self._cal.add_business_days(date.fromisoformat(day), self._offset).isoformat()
        except ValueError:
            return day

    def run(self) -> int:
        started = time.monotonic()
        sys.path.insert(0, str(HERE))
        import build as LB
        self.lb = LB
        self.staging.mkdir(parents=True, exist_ok=True)
        worst = 0
        try:
            for ds in self.manifest.datasets:
                if self.wanted(ds):
                    worst = max(worst, self.dataset(ds))
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
        if "snapshot" in ds:                                  # a captured snapshot (real, dated): the same documents every day
            state = {}
            with gzip.open(self.testdata / ds["folder"] / ds["snapshot"], "rt", encoding="utf-8") as fh:
                for line in fh:
                    if line.strip():
                        state[_key(json.loads(line), ds["key"])] = line.rstrip("\n")
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
        for i, day in enumerate(self.manifest.dates):
            for f in sorted(folder.glob(f"{'snapshot' if i == 0 else 'changes'}-{day}*.jsonl.gz")):
                with gzip.open(f, "rt", encoding="utf-8") as fh:
                    for line in fh:
                        if line.strip():
                            state[_key(json.loads(line), key)] = line.rstrip("\n")
            if extra:
                clash = [k for k in extra if k in state and k not in self.left_out]
                self.left_out += clash
                yield {**{k: v for k, v in extra.items() if k not in state}, **state}
            else:
                yield state

    def dataset(self, ds: dict) -> int:
        pack, kind, connector = ds["pack"], ds["kind"], ds["connector"]
        domain, table = self.packs.table(pack, connector, kind)
        path = self.lake / domain / table
        shutil.rmtree(path, ignore_errors=True)
        key = ds.get("key")
        worst, rows = 0, 0
        stage = self.staging / f"{domain}-{table}.jsonl"
        n_days = len(self.dates)
        for i, (day, state) in enumerate(zip(self.dates, self.states(ds)), 1):
            if key is None:
                key = next((k for k in json.loads(next(iter(state.values()))) if k.endswith("_ID")), "id") if state else "id"
            with stage.open("w", encoding="utf-8") as fh:
                for line in state.values():
                    fh.write((line if "snapshot" in ds else self.redate(line)) + "\n")
            argv = ["--from", str(stage), "--pack", str(self.packs.dir / pack), "--kind", kind, "--connector", connector, "--key", key,
                    "--as-of", day, "--lake", str(self.lake), "--engine", self.a.engine, "-q", "--no-stamp"]
            for p in ds.get("partition-by") or []:
                argv += ["--partition-by", p]
            code, summary = self.lb.run(self.lb.build_parser().parse_args(argv), say=lambda *x, **k: None)
            if code:
                print(f"  {domain}/{table} {day}: {summary.get('status')} {summary.get('error', '')}", file=sys.stderr)
            worst = max(worst, code)
            rows += len(state)
            if not self.a.quiet and n_days > 10 and (i % 10 == 0 or i == n_days):
                print(f"    {domain}/{table}: committed {i}/{n_days} days, {rows:,} rows so far (through {day})", flush=True)
        stage.unlink(missing_ok=True)
        self.checkpoint(path)
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
        except Exception:                                     # noqa: BLE001 - an optimisation: the table reads without it
            pass

    # ---- connector files -----------------------------------------------------------------------------------------------------------------
    def resolve(self, root: str) -> pathlib.Path:
        m = re.fullmatch(r"\$\{DRISHTI_DELTA_ROOT:(.*)\}", root.strip())
        if m:
            if os.environ.get("DRISHTI_DELTA_ROOT"):
                return pathlib.Path(os.environ["DRISHTI_DELTA_ROOT"]).resolve()
            root = m.group(1)
        root = root.replace("${drishti.data.dir:./data}", str(self.data))
        p = pathlib.Path(root)
        return (p if p.is_absolute() else ROOT / p).resolve()

    def connector_files(self) -> list[str]:
        import yaml
        folder = pathlib.Path(os.environ.get("DRISHTI_CONNECTORS_DIR") or (ROOT / "config" / "connectors"))
        if self.a.connectors_dir:
            folder = pathlib.Path(self.a.connectors_dir)
        default = self.lake == self.resolve(DEFAULT_ROOT)
        notes = []
        packs = sorted({ds["pack"] for ds in self.manifest.datasets if self.wanted(ds)})
        for name, (pack, template) in sorted(self.packs.delta_connectors(packs).items()):
            f = folder / f"{name}.yaml"
            want = DEFAULT_ROOT if default else str(self.lake)
            if not f.is_file():
                if default:
                    notes.append(f"{name}: generated by the server at its first start (reads {self.lake})")
                    continue
                body = {**template, "settings": {**(template.get("settings") or {}), "root": want}}
                folder.mkdir(parents=True, exist_ok=True)
                f.write_text(f"# Written by drishti.py lake demo from the connector template of pack '{pack}': reads the demo lake {self.lake}.\n"
                             + yaml.safe_dump(body, sort_keys=False), encoding="utf-8")
                notes.append(f"{name}: written ({f})")
                continue
            text = f.read_text(encoding="utf-8")
            doc = yaml.safe_load(text) or {}
            root = str((doc.get("settings") or {}).get("root", DEFAULT_ROOT))
            if self.resolve(root) == self.lake:
                notes.append(f"{name}: already reads {self.lake}")
                continue
            hist = self.data / "connectors" / "history"
            hist.mkdir(parents=True, exist_ok=True)
            (hist / f"{name}.{datetime.now().strftime('%Y%m%dT%H%M%S%f')[:-3]}.yaml").write_text(text, encoding="utf-8")
            new, n = re.subn(r"(?m)^(\s+root:\s*).*$", lambda m: m.group(1) + json.dumps(want), text, count=1)
            if not n:
                doc.setdefault("settings", {})["root"] = want
                new = yaml.safe_dump(doc, sort_keys=False)
            f.write_text(new, encoding="utf-8")
            notes.append(f"{name}: now reads {self.lake} (was {root}; the earlier text is in {hist})")
        return notes

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
        env = "" if self.lake == self.resolve(DEFAULT_ROOT) else f"DRISHTI_DELTA_ROOT={self.lake} "
        print("\nNext:\n"
              "  1. Start the server on the lakes: IDE run configuration 'Drishti server (demo lakes)' (the samples off, so every page reads\n"
              "     the lakes; offline mode on, so quotes and headlines come from the captured snapshot and nothing reaches the internet), or\n"
              f"     {env}DRISHTI_DEMO_ENABLED=false DRISHTI_OFFLINE=true DRISHTI_PACKS=core,market-data,trading,counterparty-risk,market-risk,world-economy,news-and-markets "
              "java -Djava.io.tmpdir=data/tmp/server -jar drishti-server/target/drishti-server-*-exec.jar\n"
              "  2. Start the console: run configuration 'Drishti console' (sign in as drishti-dev-admin / drishti-dev-admin123), or\n"
              "     drishti-console/.venv/bin/python drishti-console/run_drishti_web.py --server.port=17480 --backend.url=http://127.0.0.1:18480\n"
              "     (these two commands run with sign-in off on both; the run configurations turn it on, with the same secrets on both:\n"
              "     the server's DRISHTI_SECURITY_ENABLED and DRISHTI_TOKEN_SECRET, the console's DRISHTI_AUTH_ENABLED, DRISHTI_TOKEN_SECRET,\n"
              "     DRISHTI_SESSION_SECRET; docs/guides/GETTING_STARTED.md)\n"
              "  3. Open (console port 17480 by default):\n"
              "     /v/trade/1000001             an ENDUR ComSwap (legs side by side, cash flows and resets)\n"
              "     /v/trade/CLY-3000157         a CALYPSO government bond;   /v/trade/MX-20000001   a MUREX swap\n"
              "     /v/counterparty-trades/CP-NORTHPLAINS   a counterparty's live trades, payments and maturity ladder\n"
              "     /v/netting-set/NS-NORTHPLAINS-NY        a netting set and its trades\n"
              f"     Compare a trade across two days ({d[0]} and {d[-1]}): the date picker, then Compare on the trade page\n"
              "  data/testdata/README.md has the rest (the BI samples over the sixty days, the manual way).")


def add_arguments(p: argparse.ArgumentParser) -> argparse.ArgumentParser:
    p.add_argument("--testdata", default=str(ROOT / "data" / "testdata"), help="the committed test data (default data/testdata)")
    p.add_argument("--lake", default=str(data_dir() / "delta"), help="the Delta root to build (default <data>/delta, what the packs read)")
    p.add_argument("--data-dir", help="the data folder the feeds and the staging go to (default DRISHTI_DATA_DIR, else the folder holding "
                   "--lake when it is a delta/ folder, else data/): the server's own data folder")
    p.add_argument("--packs", default=str(ROOT / "config" / "packs"), help="the packs folder (default config/packs)")
    p.add_argument("--connectors-dir", help="the connector files' folder (default DRISHTI_CONNECTORS_DIR, else config/connectors)")
    p.add_argument("--as-of", default="today", metavar="today|data|YYYY-MM-DD", help="the last business date of the lake: today (default: "
                   "the server's current business date, so the demo opens on data), data (the test data's own dates) or a date")
    p.add_argument("--engine", choices=("auto", "drishti", "deltalake"), default="deltalake", help="the lake writer (default deltalake)")
    p.add_argument("--with-real", metavar="FILE[,FILE]", help="LOCAL ONLY: real trade extracts (JSON) whose counterparties, agreements and "
                   "netting sets are added to the local lake, never to data/testdata")
    p.add_argument("--only", metavar="PREFIX[,PREFIX]", help="only these datasets: a folder prefix (trades/, core/counterparty) or a pack name")
    p.add_argument("--server", help=f"the server told of the new data (default: DRISHTI_SERVER, then {DEFAULT_SERVER})")
    p.add_argument("--token-file", help="the API token for the data-load signal (default: DRISHTI_TOKEN); never printed")
    p.add_argument("--no-signal", action="store_true", help="tell no server, and leave no stamp for one")
    p.add_argument("--signal-once", action="store_true", help="tell each table once, for its newest date, not each table and date "
                   "(a running server reads the older dates again by itself)")
    p.add_argument("-q", "--quiet", action="store_true", help="only the summary")
    return p


def build_parser(add_help: bool = True) -> argparse.ArgumentParser:
    return add_arguments(argparse.ArgumentParser(prog="lake demo", description=__doc__.split("\n\n")[0], add_help=add_help,
                                                 formatter_class=argparse.RawDescriptionHelpFormatter, epilog=__doc__.split("\n\n", 1)[1]))


def run(a) -> int:
    return DemoLake(a).run()


if __name__ == "__main__":
    sys.exit(run(build_parser().parse_args()))
