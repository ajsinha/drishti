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

"""Writes the exposure engine's demo inputs (docs/guides/EXPOSURE_GUIDE.md section 23) into data/testdata/counterparty-risk/, from the committed
netting sets of the business date (their member trades, MtM, notional, maturity and asset class), as an upstream exposure simulator would
deliver them:

    simulation-run/   XR-<date>-01: the run's grid (13 primary dates 1W .. 10Y and their look-back dates), 200 paths, the model
    exposure-path/    one row per live member trade and grid date up to its maturity (+10 business days): MTM over the 200 paths
                      (the vector column MTM, list<double> in the lake, whole USD), sorted by netting set, trade and grid index

The committed run covers eight netting sets (EXPOSURE_GUIDE.md section 23): the four of CP-ALDERSHOT (a securities-financing set, a negative set, a
large positive set, a structured set), NS-HARBOURLINE-LDN-NY and NS-KESTREL-FRA (threshold-zero CSAs: fully margined, the second a
negative SFT set), NS-AURORA-TKY (the wrong-way set: every trade one way on one factor) and NS-CASCADIA-TKY. Every other netting set has
no exposure paths in the demo: its pages say so. No figure is written: every EE, PFE and EPE is the engine's, computed when asked.
Deterministic (Python's Random seeded by text, gzip mtime 0): the same netting sets give the same files, byte for byte.

    python3 tools/packgen/ccr/make_ccr_data.py                 write data/testdata/counterparty-risk/{simulation-run,exposure-path}, their
                                                               testdata.yaml entries and the engine's parity reference
    python3 tools/packgen/ccr/make_ccr_data.py --check         fail if they differ from what would be written
    python3 tools/packgen/ccr/make_ccr_data.py --full --out /path/outside/the/repo
                                                               every netting set that fits in 10,000 trades, 500 paths (local, benchmarks)
    python3 tools/packgen/ccr/make_ccr_data.py --reconcile 1/2 the stored exposure profiles (2.8.0) against the computed ones, half the
                                                               netting sets per run (at most 10,000 trades a run)
"""
from __future__ import annotations

import argparse
import gzip
import json
import math
import sys
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(ROOT / "tools"))
sys.path.insert(0, str(ROOT / "tools" / "packgen" / "var"))
import ccrmath as M  # noqa: E402
import paths as PT  # noqa: E402
from positions import replay  # noqa: E402  (a dataset's documents on its last day)

PACK, CONNECTOR = "counterparty-risk", "exposure-store"
KEYS = {"simulation-run": "RUN_ID", "exposure-path": "PATH_ROW_ID"}
#: the netting sets the committed run covers (EXPOSURE_GUIDE.md section 23)
COMMITTED = ("NS-ALDERSHOT-FRA", "NS-ALDERSHOT-LDN", "NS-ALDERSHOT-NY", "NS-ALDERSHOT-TKY", "NS-HARBOURLINE-LDN-NY", "NS-KESTREL-FRA",
             "NS-AURORA-TKY", "NS-CASCADIA-TKY")
WRONG_WAY = {"NS-AURORA-TKY"}
PATHS, FULL_PATHS = 200, 500
MAX_TRADES = 10_000                       # the local limit: never more trades in one run on a developer's machine
DEMO_TRADES = 9_500                       # the demo run (lake demo): whole counterparties, smallest first, within this many trades
PART_BYTES = 1_200_000                    # compressed bytes per file, about
QS = [0.95, 0.99]
REFERENCE = ROOT / "drishti-compute" / "src" / "test" / "resources" / "ccr" / "reference.json"
SYSTEMS = (("CLY-", "CALYPSO"), ("MX-", "MUREX"))


def source_system(trade_id: str) -> str:
    return next((s for p, s in SYSTEMS if trade_id.startswith(p)), "ENDUR")


def mpor_days(csa: dict) -> int:
    v = csa.get("MPOR_DAYS") or str(csa.get("MPOR") or "10").split()[0]
    try:
        return int(float(v))
    except ValueError:
        return 10


class Inputs:
    """The committed reference data of the business date: netting sets with their members, CSAs, collateral held, the books' desks."""

    def __init__(self, testdata: Path, dates: list[str]):
        self.cob = dates[-1]
        self.sets = replay(testdata / PACK / "netting-set", "NETTING_SET_ID", dates)
        self.csas = replay(testdata / PACK / "csa", "CSA_ID", dates)
        self.balances = replay(testdata / PACK / "collateral-balance", "BALANCE_ID", dates)
        self.desk_of_book = {}
        for d in replay(testdata / "core" / "desk", "DESK_ID", dates).values():
            for b in d.get("BOOKS") or []:
                self.desk_of_book[b["ID"]] = d["DESK_ID"]

    def members(self, ns: str) -> list[dict]:
        """The set's live member trades on the business date (maturity on or after it), by trade id."""
        return sorted((t for t in self.sets[ns].get("TRADES") or [] if str(t.get("MATURITY") or "9999") >= self.cob),
                      key=lambda t: t["TRADE_ID"])

    def csa(self, ns: str) -> M.Csa | None:
        s = self.sets[ns]
        c = self.csas.get(s.get("CSA") or "")
        if c is None:
            return None
        bal = self.balances.get(s.get("COLLATERAL_BALANCE") or "") or {}
        return M.Csa(th_them=float(c.get("THRESHOLD_THEM") or 0), th_us=float(c.get("THRESHOLD_US") or 0),
                     mta_them=float(c.get("MTA_THEM", c.get("MTA")) or 0), mta_us=float(c.get("MTA_US", c.get("MTA")) or 0),
                     ia=float(c.get("INDEPENDENT_AMOUNT") or 0), rounding=float(c.get("ROUNDING") or 0), mpor_days=mpor_days(c),
                     margined="VM" in str(c.get("MARGIN_TYPE") or ""), held_today=float(bal.get("NET") or 0),
                     im_held=float(bal.get("IM_HELD") or 0))


class Run:
    """One simulation run over some netting sets: the grid, the classes' walks, every member trade's paths."""

    def __init__(self, inputs: Inputs, sets: list[str], paths: int = PATHS, run_no: int = 1):
        self.inputs, self.sets, self.paths = inputs, list(sets), paths
        self.cob = date.fromisoformat(inputs.cob)
        self.run_id = f"XR-{inputs.cob}-{run_no:02d}"
        self.batch = f"XB-{inputs.cob}-{run_no:02d}"
        self.grid = PT.grid(self.cob)
        self.walks = PT.Walks(self.run_id, self.grid["times"], paths)
        self.trades = {ns: inputs.members(ns) for ns in self.sets}
        n = sum(len(v) for v in self.trades.values())
        if n > MAX_TRADES:
            raise SystemExit(f"{n:,} trades: more than the local limit of {MAX_TRADES:,} in one run")

    def live_until(self, t: dict) -> date:
        return PT.CAL.add_business_days(date.fromisoformat(t["MATURITY"]), PT.LOOKBACK_DAYS)

    def set_paths(self, ns: str) -> dict[str, list[list[int]]]:
        return {t["TRADE_ID"]: PT.trade_paths(t, self.cob, self.grid, self.walks, self.paths, ns in WRONG_WAY) for t in self.trades[ns]}

    def run_row(self, rows: int) -> dict:
        g = self.grid
        return {"RUN_ID": self.run_id, "BUSINESS_DATE": self.inputs.cob, "MARKET_DATA_DATE": self.inputs.cob,
                "MODEL": "One correlated Brownian factor per asset class (rates, FX, credit, equity, commodity) and a walk of each trade's own; "
                         "values amortise to zero at maturity (tools/packgen/ccr/paths.py)",
                "MODEL_VERSION": "drishti-testgen-ccr-1.0", "CALIBRATION": "REAL_WORLD", "PATHS": self.paths, "SEED": f"ccr|{self.run_id}",
                "ANTITHETIC": False, "GRID_DATES": [d.isoformat() for d in g["dates"]], "GRID_ROLE": g["roles"], "LOOKBACK_OF": g["lookback_of"],
                "GRID_TENORS": g["tenors"], "LOOKBACK_DAYS": PT.LOOKBACK_DAYS, "VALUE_BASIS": "FUTURE", "CURRENCY": "USD", "PRECISION": "float64",
                "CREDIT_PATHS": False, "NETTING_SETS": len(self.sets), "TRADES": sum(len(v) for v in self.trades.values()), "ROWS": rows,
                "BATCH_ID": self.batch, "COMPUTED_AT": f"{self.inputs.cob}T23:40:00Z", "STATUS": "complete"}

    def path_rows(self, ns: str, mtm: dict[str, list[list[int]]]) -> list[dict]:
        s = self.inputs.sets[ns]
        out = []
        for t in self.trades[ns]:
            until = self.live_until(t)
            for k, d in enumerate(self.grid["dates"]):
                if d > until:
                    break
                out.append({"PATH_ROW_ID": f"{self.run_id}~{t['TRADE_ID']}~{k}", "BUSINESS_DATE": self.inputs.cob, "RUN_ID": self.run_id,
                            "TRADE_ID": t["TRADE_ID"], "SOURCE_SYSTEM": source_system(t["TRADE_ID"]), "NETTING_SET": ns,
                            "COUNTERPARTY": s.get("COUNTERPARTY"), "LEGAL_ENTITY": s.get("LEGAL_ENTITY"),
                            "DESK": self.inputs.desk_of_book.get(t.get("BOOK")), "TT_BOOK": t.get("BOOK"), "ASSET_CLASS": t.get("ASSET_CLASS"),
                            "PRODUCT": t.get("PRODUCT"), "NOTIONAL": t.get("NOTIONAL"), "MATURITY_DATE": t.get("MATURITY"),
                            "GRID_INDEX": k, "GRID_DATE": d.isoformat(), "MTM": mtm[t["TRADE_ID"]][k], "STATUS": "ok",
                            "PRICER": "testgen-factor-walk", "BATCH_ID": self.batch})
        return out

    # ---- the reference figures (ccrmath, the engine's rules) ------------------------------------------------------------------------
    def m_grid(self) -> M.Grid:
        g = self.grid
        return M.Grid(times=g["times"], primary=[r == "PRIMARY" for r in g["roles"]], lookback_of=g["lookback_of"])

    def set_matrix(self, ns: str, mtm: dict[str, list[list[int]]]) -> list[list[float]]:
        """V: the set's value on every path at every grid date (an absent row: 0)."""
        v = [[0.0] * self.paths for _ in self.grid["dates"]]
        for t in self.trades[ns]:
            for k, row in enumerate(mtm[t["TRADE_ID"]]):
                if self.grid["dates"][k] <= self.live_until(t):
                    vk = v[k]
                    for p, x in enumerate(row):
                        vk[p] += x
        return v

    def horizon(self, sets: list[str]) -> float:
        longest = max((PT.years(self.cob, date.fromisoformat(t["MATURITY"])) for ns in sets for t in self.trades[ns]), default=0.0)
        return min(1.0, longest)

    def v0(self, ns: str) -> float:
        return math.fsum(float(t.get("MTM") or 0) for t in self.trades[ns])

    def figures(self, ns: str, mtm: dict[str, list[list[int]]]) -> dict:
        c = self.inputs.csa(ns)
        v = self.set_matrix(ns, mtm)
        e, n, _ = M.exposure(v, self.m_grid(), c, "lagged")
        stats = M.statistics(e, n, v, self.m_grid(), self.horizon([ns]), QS, M.current_exposure(self.v0(ns), c))
        return {"v": v, "e": e, "n": n, "stats": stats, "csa": c}


def summary(stats: dict, grid: dict) -> dict:
    """A scope's reference figures as the engine's answer names them (the parity test reads these)."""
    return {"CE": stats["ce"], "EPE": stats["epe"], "EEPE": stats["eepe"], "PEAK_EE": stats["peak_ee"],
            "PEAK_PFE_95": stats["peak_pfe"][0.95][0], "PEAK_PFE_95_DATE": grid["dates"][stats["peak_pfe"][0.95][1]].isoformat(),
            "PEAK_PFE_99": stats["peak_pfe"][0.99][0],
            "PROFILE": [{"TENOR": grid["tenors"][r["k"]], "DATE": grid["dates"][r["k"]].isoformat(), "T": r["t"], "EE": r["ee"], "ENE": r["ene"],
                         "EEE": r["eee"], "PFE_95": r["pfe"][0.95], "PFE_99": r["pfe"][0.99], "EE_UNCOLL": r["ee_uncoll"]}
                        for r in stats["profile"]]}


def build(run: Run) -> tuple[dict[str, list[dict]], dict]:
    """The run's documents and the reference figures of each netting set and of CP-ALDERSHOT (its four sets summed per path)."""
    rows: list[dict] = []
    ref: dict = {"runId": run.run_id, "businessDate": run.inputs.cob, "paths": run.paths, "sets": {}, "scopes": {},
                 "mtm0": {t["TRADE_ID"]: float(t.get("MTM") or 0) for ns in run.sets for t in run.trades[ns]}}
    e_cp = n_cp = None
    v_cp = None
    ce_cp = 0.0
    cp_sets = [ns for ns in run.sets if run.inputs.sets[ns].get("COUNTERPARTY") == "CP-ALDERSHOT"]
    for ns in run.sets:
        mtm = run.set_paths(ns)
        rows += run.path_rows(ns, mtm)
        f = run.figures(ns, mtm)
        c = f["csa"]
        ref["sets"][ns] = {"csa": None if c is None else c.__dict__, "v0": run.v0(ns), "horizon": run.horizon([ns]), "trades": len(run.trades[ns]),
                           **summary(f["stats"], run.grid)}
        if ns in cp_sets:
            e_cp = M.add_rows(e_cp, f["e"])
            n_cp = M.add_rows(n_cp, f["n"])
            v_cp = M.add_rows(v_cp, {k: [max(x, 0.0) for x in row] for k, row in enumerate(f["v"])})
            ce_cp += f["stats"]["ce"]
    if cp_sets:
        g = run.m_grid()
        # EE_UNCOLL of a counterparty is the sum over its sets of max(V, 0): the summed positive parts stand in for V
        stats = M.statistics(e_cp, n_cp, [v_cp[k] for k in range(len(run.grid["dates"]))], g, run.horizon(cp_sets), QS, ce_cp)
        ref["scopes"]["CPTY:CP-ALDERSHOT"] = summary(stats, run.grid)
    rows.sort(key=lambda r: (r["NETTING_SET"], r["TRADE_ID"], r["GRID_INDEX"]))
    return {"simulation-run": [run.run_row(len(rows))], "exposure-path": rows}, ref


# ---------------------------------------------------------------------------------------------------------------------- writing
def dumps(d: dict) -> str:
    return json.dumps(d, separators=(",", ":"), ensure_ascii=False)


def _gz_files(folder: Path, stem: str, lines: list[str]) -> list[Path]:
    """Deterministic gzip, split into files of about PART_BYTES compressed."""
    folder.mkdir(parents=True, exist_ok=True)
    for old in folder.glob("*.jsonl.gz"):
        old.unlink()
    parts: list[list[str]] = [[]]
    size = 0
    for ln in lines:
        if size > PART_BYTES * 3 and parts[-1]:                    # about 3:1 compression on these files
            parts.append([])
            size = 0
        parts[-1].append(ln)
        size += len(ln)
    out = []
    for i, chunk in enumerate(parts, 1):
        name = f"{stem}.jsonl.gz" if len(parts) == 1 else f"{stem}.part-{i:03d}.jsonl.gz"
        path = folder / name
        with path.open("wb") as raw, gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0, compresslevel=9) as gz:
            gz.write(("\n".join(chunk) + "\n").encode("utf-8"))
        out.append(path)
    return out


def write(out: Path, docs: dict[str, list[dict]]) -> list[dict]:
    """Writes the folders; returns their testdata.yaml entries."""
    entries = []
    for kind, rows in docs.items():
        cob = rows[0]["BUSINESS_DATE"]
        files = _gz_files(out / PACK / kind, f"cob-{cob}", [dumps(r) for r in rows])
        entries.append({"folder": f"{PACK}/{kind}", "pack": PACK, "kind": kind, "connector": CONNECTOR, "key": KEYS[kind],
                        "dated-by": "BUSINESS_DATE", "first-date": cob, "last-date": cob, "documents": len(rows),
                        "files": [f.name for f in files], "bytes": sum(f.stat().st_size for f in files)})
    return entries


def reference_text(ref: dict) -> str:
    def rounded(x):
        if isinstance(x, float):
            return round(x, 6)
        if isinstance(x, dict):
            return {k: rounded(v) for k, v in x.items()}
        if isinstance(x, list):
            return [rounded(v) for v in x]
        return x
    return json.dumps(rounded(ref), indent=1, ensure_ascii=False) + "\n"


FOLDERS = tuple(f"{PACK}/{k}" for k in KEYS)


def update_manifest(testdata: Path, entries: list[dict]) -> None:
    """testdata.yaml with these entries in place of earlier ones (after the pack's other datasets), its header and the rest kept."""
    import yaml
    path = testdata / "testdata.yaml"
    text = path.read_text(encoding="utf-8")
    head = []
    for ln in text.splitlines():
        if ln.startswith("#") or not ln.strip():
            head.append(ln)
        else:
            break
    m = yaml.safe_load(text)
    keep = [d for d in m["datasets"] if d.get("folder") not in FOLDERS]
    at = max((i for i, d in enumerate(keep) if d.get("pack") == PACK), default=len(keep) - 1) + 1
    m["datasets"] = keep[:at] + entries + keep[at:]
    path.write_text("\n".join(head) + "\n" + yaml.safe_dump(m, sort_keys=False, width=140), encoding="utf-8")


SAMPLES = ROOT / "config" / "packs" / PACK / "samples"


def samples(docs: dict[str, list[dict]]) -> dict[Path, str]:
    """The pack's reference samples (its README and contracts read them; never served from there: the lake is): the run, and the first
    row of the first committed netting set's first trade."""
    picks = {"simulation-run": docs["simulation-run"][:1], "exposure-path": docs["exposure-path"][:1]}
    return {SAMPLES / f"{k}.jsonl": "".join(dumps(d) + "\n" for d in v) for k, v in picks.items()}


def write_side_files(docs: dict[str, list[dict]], ref: dict) -> None:
    """The parity reference of drishti-compute's test and the pack's samples, beside the committed test data."""
    REFERENCE.parent.mkdir(parents=True, exist_ok=True)
    REFERENCE.write_text(reference_text(ref), encoding="utf-8")
    for f, t in samples(docs).items():
        f.parent.mkdir(parents=True, exist_ok=True)
        f.write_text(t, encoding="utf-8")


def write_testdata(out: Path, dates: list[str], side_files: bool) -> list[dict]:
    """The committed run into a test-data folder whose netting sets were just written (tools/testdata/make_testdata.py); its entries."""
    inputs = Inputs(out, dates)
    sets = [ns for ns in COMMITTED if ns in inputs.sets and inputs.members(ns)]     # a small test universe may hold fewer
    if not sets:
        return []
    run = Run(inputs, sets)
    docs, ref = build(run)
    entries = write(out, docs)
    if side_files:
        write_side_files(docs, ref)
    return entries


# ---------------------------------------------------------------------------------------------------------------------- full, reconcile
def fitting(inputs: Inputs, limit: int = MAX_TRADES) -> list[str]:
    """The netting sets, smallest first, that fit in the local limit together."""
    out, n = [], 0
    for ns in sorted(inputs.sets, key=lambda s: (len(inputs.members(s)), s)):
        k = len(inputs.members(ns))
        if n + k <= limit:
            out.append(ns)
            n += k
    return sorted(out)


def reconcile(inputs: Inputs, stored: dict[str, dict], part: str) -> list[dict]:
    """Stored (2.8.0's exposure-profile) against computed (the committed run's model, 200 paths) for half the netting sets."""
    i, n = (int(x) for x in part.split("/"))
    names = sorted(inputs.sets)
    mine, budget = [], 0
    for j, ns in enumerate(names):
        if j % n == i - 1:
            mine.append(ns)
            budget += len(inputs.members(ns))
    if budget > MAX_TRADES:
        raise SystemExit(f"part {part} holds {budget:,} trades: more than {MAX_TRADES:,}; use more parts")
    out = []
    for ns in mine:
        run = Run(inputs, [ns])                                     # the same paths as in any run: seeded by the trade and the run id
        mtm = run.set_paths(ns)
        s = run.figures(ns, mtm)["stats"]
        st = stored.get("EXP-" + ns[3:], {})
        out.append({"NETTING_SET": ns, "TRADES": len(run.trades[ns]), "STORED_EPE": st.get("EPE"), "EPE": round(s["epe"]),
                    "STORED_EEPE": st.get("EEPE"), "EEPE": round(s["eepe"]), "STORED_PFE_PEAK": st.get("PFE_PEAK"),
                    "PEAK_PFE_95": round(s["peak_pfe"][0.95][0])})
    return out


def demo_sets(inputs: Inputs, limit: int = DEMO_TRADES) -> list[str]:
    """The demo run's netting sets: every set of each counterparty, counterparties smallest first, as many as fit in ``limit`` trades
    together, so a counterparty, its group and its legal entities are whole (never some of a counterparty's sets). The committed run's
    sets are among them on the test data (its counterparties are small), and a trade's paths do not depend on which sets a run holds
    (paths.py seeds each trade by its id), so the committed figures are the demo's figures for those sets."""
    by_cp: dict[str, list[str]] = {}
    for ns, s in inputs.sets.items():
        if inputs.members(ns):
            by_cp.setdefault(s.get("COUNTERPARTY") or ns, []).append(ns)
    out, n = [], 0
    for cp, sets in sorted(by_cp.items(), key=lambda kv: (sum(len(inputs.members(ns)) for ns in kv[1]), kv[0])):
        k = sum(len(inputs.members(ns)) for ns in sets)
        if n + k <= limit:
            out += sets
            n += k
    return sorted(out)


def write_demo(testdata: Path, out: Path, dates: list[str]) -> tuple[int, int, list[dict]]:
    """The demo run (``demo_sets``, the committed run's paths and grid) as test-data files under ``out``: (sets, trades, entries).
    tools/lake/demo.py loads it in place of the committed run, so the demo lake has exposure for most counterparties; nothing of it is
    committed (about 100 MB)."""
    inputs = Inputs(testdata, dates)
    run = Run(inputs, demo_sets(inputs))
    docs, _ = build(run)
    return len(run.sets), sum(len(v) for v in run.trades.values()), write(out, docs)


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0], formatter_class=argparse.RawDescriptionHelpFormatter,
                                epilog=__doc__.split("\n\n", 1)[1])
    p.add_argument("--testdata", default=str(ROOT / "data" / "testdata"), help="the test data the netting sets and CSAs are read from")
    p.add_argument("--out", help="where to write (default: --testdata, with its testdata.yaml entries and the parity reference)")
    p.add_argument("--check", action="store_true", help="fail if the committed files differ from what would be written")
    p.add_argument("--full", action="store_true", help=f"every netting set that fits in {MAX_TRADES:,} trades, {FULL_PATHS} paths (needs --out)")
    p.add_argument("--demo", action="store_true", help=f"the demo run: whole counterparties within {DEMO_TRADES:,} trades, {PATHS} paths "
                   "(needs --out; tools/lake/demo.py runs it)")
    p.add_argument("--reconcile", metavar="I/N", help="stored against computed for part I of N of the netting sets (JSON lines to stdout); "
                   "reads --stored")
    p.add_argument("--stored", help="the retired exposure-profile folder (2.8.0's data/testdata/counterparty-risk/exposure-profile)")
    a = p.parse_args(argv)
    import yaml
    td = Path(a.testdata)
    dates = yaml.safe_load((td / "testdata.yaml").read_text(encoding="utf-8"))["business-dates"]
    inputs = Inputs(td, dates)
    if a.reconcile:
        stored = replay(Path(a.stored), "PROFILE_ID", dates) if a.stored else {}
        for r in reconcile(inputs, stored, a.reconcile):
            print(dumps(r), flush=True)
        return 0
    if a.demo:
        if not a.out:
            raise SystemExit("--demo writes outside the repository: give --out")
        n_sets, n_trades, entries = write_demo(td, Path(a.out), dates)
        print(f"wrote the demo run: {n_sets} netting sets, {n_trades:,} trades, {PATHS} paths: "
              f"{sum(e['bytes'] for e in entries) / 1e6:.1f} MB in {a.out}")
        return 0
    if a.full:
        if not a.out:
            raise SystemExit("--full writes outside the repository: give --out")
        run = Run(inputs, fitting(inputs), FULL_PATHS)
        docs, _ = build(run)
        entries = write(Path(a.out), docs)
        print(f"wrote {len(run.sets)} netting sets, {sum(len(v) for v in run.trades.values()):,} trades, {run.paths} paths: "
              f"{sum(e['bytes'] for e in entries) / 1e6:.1f} MB in {a.out}")
        return 0
    run = Run(inputs, list(COMMITTED))
    docs, ref = build(run)
    if a.check:
        import tempfile
        with tempfile.TemporaryDirectory(prefix="drishti-ccr-check-") as tmp:
            write(Path(tmp), docs)
            stale = [f"{k}/{f.name}" for k in FOLDERS for f in sorted((Path(tmp) / k).glob("*.jsonl.gz"))
                     if not (td / k / f.name).is_file() or gzip.decompress((td / k / f.name).read_bytes()) != gzip.decompress(f.read_bytes())]
        if not REFERENCE.is_file() or REFERENCE.read_text(encoding="utf-8") != reference_text(ref):
            stale.append(str(REFERENCE.relative_to(ROOT)))
        stale += [str(f.relative_to(ROOT)) for f, t in samples(docs).items() if not f.is_file() or f.read_text(encoding="utf-8") != t]
        if stale:
            print(f"exposure demo data out of date ({stale[:3]}); run tools/packgen/ccr/make_ccr_data.py")
            return 1
        print("exposure demo data up to date")
        return 0
    out = Path(a.out) if a.out else td
    entries = write(out, docs)
    if not a.out:
        update_manifest(td, entries)
        write_side_files(docs, ref)
    print(f"wrote {len(run.sets)} netting sets, {sum(len(v) for v in run.trades.values()):,} trades, {len(docs['exposure-path']):,} rows of "
          f"{run.paths} paths over {len(run.grid['dates'])} grid dates: {sum(e['bytes'] for e in entries) / 1e6:.2f} MB")
    return 0


if __name__ == "__main__":
    sys.exit(main())
