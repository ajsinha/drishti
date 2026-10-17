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

Connector files: the seven packs' Delta connectors read ${DRISHTI_DELTA_ROOT:<data>/delta}. With the default lake nothing needs
changing; a connector file (config/connectors/<name>.yaml) that points elsewhere, or any of them when --lake is another folder, is
written to point at the lake (its earlier text kept in <data>/connectors/history/). A missing file is left for the server to
generate at its first start, except with --lake elsewhere, where it is written from the pack's template.

--with-real FILE[,FILE]: LOCAL ONLY. Reads real trade extracts (a JSON array of trades, as endur.json) and adds, to the local lake
only, the trades themselves (BMO_LEGAL_ENTITY read as LEGAL_ENTITY), a counterparty record for each real counterparty, an ISDA
agreement and a netting set per counterparty and legal entity holding its real trades. Nothing is written to data/testdata, and a
real trade whose id the test data already uses is left out (the test data wins). Staging files live under <data>/build/lake-demo and
are removed at the end. Needs PyYAML, and deltalake and pyarrow (engine deltalake, the default; --engine auto uses the server jar).
"""
from __future__ import annotations

import argparse
import gzip
import json
import os
import pathlib
import re
import shutil
import sys
import time
from datetime import datetime

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parents[1]
DEFAULT_ROOT = "${DRISHTI_DELTA_ROOT:${drishti.data.dir:./data}/delta}"


def data_dir() -> pathlib.Path:
    return pathlib.Path(os.environ.get("DRISHTI_DATA_DIR") or (ROOT / "data"))


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
    return str(doc.get(key))


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
                d = TS.rename(t)
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
        self.data = data_dir().resolve()
        self.staging = self.data / "build" / "lake-demo"
        self.packs = Packs(pathlib.Path(a.packs))
        self.manifest = Manifest(self.testdata)
        self.extras = RealExtras(a.with_real.split(",")).build() if a.with_real else {}
        self.tables: list[str] = []
        self.left_out: list[str] = []

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
        elapsed = time.monotonic() - started
        notes = self.connector_files()
        self.report(elapsed, notes)
        return worst

    def wanted(self, ds: dict) -> bool:
        only = [x.strip() for x in (self.a.only or "").split(",") if x.strip()]
        name = ds.get("folder") or f"{ds['pack']}/{ds['kind']}"
        return not only or any(name.startswith(x) or ds["pack"] == x for x in only)

    # ---- one dataset -------------------------------------------------------------------------------------------------------------------
    def states(self, ds: dict):
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
        for day, state in zip(self.manifest.dates, self.states(ds)):
            if key is None:
                key = next((k for k in json.loads(next(iter(state.values()))) if k.endswith("_ID")), "id") if state else "id"
            with stage.open("w", encoding="utf-8") as fh:
                for line in state.values():
                    fh.write(line + "\n")
            argv = ["--from", str(stage), "--pack", str(self.packs.dir / pack), "--kind", kind, "--connector", connector, "--key", key,
                    "--as-of", day, "--lake", str(self.lake), "--engine", self.a.engine, "-q"]
            for p in ds.get("partition-by") or []:
                argv += ["--partition-by", p]
            code, summary = self.lb.run(self.lb.build_parser().parse_args(argv), say=lambda *x, **k: None)
            if code:
                print(f"  {domain}/{table} {day}: {summary.get('status')} {summary.get('error', '')}", file=sys.stderr)
            worst = max(worst, code)
            rows += len(state)
        stage.unlink(missing_ok=True)
        self.tables.append(f"{domain}/{table}")
        if not self.a.quiet:
            print(f"  {domain}/{table}: {rows:,} rows over {len(self.manifest.dates)} days" + (" (failed)" if worst == 1 else ""))
        return worst

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
        d = self.manifest.dates
        print(f"\nDemo lake ready: {self.lake}: {len(self.tables)} tables, {len(d)} business days ({d[0]} to {d[-1]}), {elapsed:.0f} s"
              + (f"; real extracts added locally ({sum(len(v) for v in self.extras.values())} documents)" if self.extras else ""))
        if self.left_out:
            print(f"  left out {len(self.left_out)} real document(s) whose id the test data already uses")
        print("Connector files:")
        for n in notes:
            print("  " + n)
        env = "" if self.lake == self.resolve(DEFAULT_ROOT) else f"DRISHTI_DELTA_ROOT={self.lake} "
        print("\nNext:\n"
              "  1. Start the server: IDE run configuration 'Drishti server' (packs and ports set there), or\n"
              f"     {env}DRISHTI_PACKS=core,market-data,trading,counterparty-risk,market-risk,world-economy,news-and-markets "
              "java -jar drishti-server/target/drishti-server-*-exec.jar\n"
              "  2. Start the console: run configuration 'Drishti console', or\n"
              "     drishti-console/.venv/bin/python drishti-console/run_drishti_web.py --server.port=17480 --backend.url=http://127.0.0.1:18480\n"
              "  3. Open (console port 17480 by default):\n"
              "     /v/trade/1000001             an ENDUR ComSwap (legs side by side, cash flows and resets)\n"
              "     /v/trade/CLY-3000157         a CALYPSO government bond;   /v/trade/MX-20000001   a MUREX swap\n"
              "     /v/counterparty-trades/CP-NORTHPLAINS   a counterparty's live trades, payments and maturity ladder\n"
              "     /v/netting-set/NS-NORTHPLAINS-NY        a netting set and its trades\n"
              f"     Compare a trade across two days ({d[0]} and {d[-1]}): the date picker, then Compare on the trade page\n"
              "  data/testdata/README.md has the rest (the BI samples over the seven days, the manual way).")


def add_arguments(p: argparse.ArgumentParser) -> argparse.ArgumentParser:
    p.add_argument("--testdata", default=str(ROOT / "data" / "testdata"), help="the committed test data (default data/testdata)")
    p.add_argument("--lake", default=str(data_dir() / "delta"), help="the Delta root to build (default <data>/delta, what the packs read)")
    p.add_argument("--packs", default=str(ROOT / "config" / "packs"), help="the packs folder (default config/packs)")
    p.add_argument("--connectors-dir", help="the connector files' folder (default DRISHTI_CONNECTORS_DIR, else config/connectors)")
    p.add_argument("--engine", choices=("auto", "drishti", "deltalake"), default="deltalake", help="the lake writer (default deltalake)")
    p.add_argument("--with-real", metavar="FILE[,FILE]", help="LOCAL ONLY: real trade extracts (JSON) whose counterparties, agreements and "
                   "netting sets are added to the local lake, never to data/testdata")
    p.add_argument("--only", metavar="PREFIX[,PREFIX]", help="only these datasets: a folder prefix (trades/, core/counterparty) or a pack name")
    p.add_argument("-q", "--quiet", action="store_true", help="only the summary")
    return p


def build_parser(add_help: bool = True) -> argparse.ArgumentParser:
    return add_arguments(argparse.ArgumentParser(prog="lake demo", description=__doc__.split("\n\n")[0], add_help=add_help,
                                                 formatter_class=argparse.RawDescriptionHelpFormatter, epilog=__doc__.split("\n\n", 1)[1]))


def run(a) -> int:
    return DemoLake(a).run()


if __name__ == "__main__":
    sys.exit(run(build_parser().parse_args()))
