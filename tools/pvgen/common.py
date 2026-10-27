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

"""What the stand-in pricing system's scripts share (docs/guides/VAR_GUIDE.md section 15.12, "Producing P&L vectors and scenario PVs").

The rows a pricing system sends are the contract: one JSON object per line (JSONL, UTF-8; ``.jsonl``, or ``.jsonl.gz`` with
``--gzip``), each with exactly the fields of its kind's contract (``config/packs/market-risk/contracts/{scenario-pv,pnl-vector,
fx-scenario}.contract.yaml``), no other. The morning run comes from the demo's factor model (``tools/packgen/var``: ``make_var_data.Demo``,
``scenario_fx``), never a copy of it: P&L vectors for some systems, scenario PVs in each trade's own currency for others
(``--pv-systems``, ``--pnl-systems``, ``--all-vectors``), today's split by default. ``Updates`` makes the day's intraday updates from it
(amended, new and cancelled trades, a seed making them the same every run) for the intraday file script and the Kafka publisher alike.
Standard library only (the Kafka publisher adds the pure-Python kafka-python), so every script runs on Windows with the console's
``python.exe``."""
from __future__ import annotations

import gzip
import json
import os
import random
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "packgen" / "var"))
import make_var_data as M  # noqa: E402
import scenario_fx as SFX  # noqa: E402
import varmath as VM  # noqa: E402

CONTRACTS = ROOT / "config" / "packs" / "market-risk" / "contracts"
PACK = ROOT / "config" / "packs" / "market-risk"
#: each kind's lake key
KEYS = {"scenario-pv": "SCENARIO_PV_ID", "pnl-vector": "VECTOR_ID", "fx-scenario": "FX_SCENARIO_ID"}
#: the vector of each trades' kind (null on a cancel)
VECTORS = {"scenario-pv": "SCENARIO_PV", "pnl-vector": "PNL"}
LIVE = ("NEW", "VALIDATED", "AMENDED")
#: the fields that carry the business date (moved with --date)
DATED = ("BUSINESS_DATE", "VALUATION_DATE", "BASE_VALUATION_DATE", "COMPUTED_AT", "BATCH_ID")
#: the first id of a new trade per system (a range the demo's trades never use), so new ids keep each system's own pattern
NEW_IDS = {"ENDUR": (9_000_000, ""), "CALYPSO": (9_000_000, "CLY-"), "MUREX": (90_000_000, "MX-")}


def grain_args(p) -> None:
    """--grain and --position-systems: which systems send per position (GRAIN POSITION) rather than per trade."""
    p.add_argument("--grain", choices=("trade", "position"), default="trade",
                   help="position: every system sends per position (each record a position, summed from its trades, naming them)")
    p.add_argument("--position-systems", default="", metavar="SYS,SYS", help="these systems send per position, the rest per trade")


def grain_systems(a) -> tuple[str, ...]:
    """The systems sending per position: all with --grain position, else --position-systems (the morning run and its updates alike)."""
    if getattr(a, "grain", "trade") == "position":
        return ("ENDUR", "MUREX", "CALYPSO")
    return systems(getattr(a, "position_systems", ""))


def systems(text: str) -> tuple[str, ...]:
    return tuple(s.strip().upper() for s in (text or "").split(",") if s.strip())


def contract_fields(kind: str) -> list[tuple[str, str]]:
    """The kind's contract fields in order: (name, presence)."""
    import yaml
    c = yaml.safe_load((CONTRACTS / f"{kind}.contract.yaml").read_text(encoding="utf-8"))
    return [(f["name"], f["presence"]) for f in c["fields"]]


_FIELDS: dict[str, list[tuple[str, str]]] = {}


def contract_row(kind: str, row: dict) -> dict:
    """The row with exactly its contract's fields, in the contract's order (a field the row lacks: left out when optional)."""
    if kind not in _FIELDS:
        _FIELDS[kind] = contract_fields(kind)
    out = {}
    for name, presence in _FIELDS[kind]:
        if name in row:
            out[name] = row[name]
        elif presence == "required":
            raise ValueError(f"{kind} row {row.get(KEYS[kind])}: the contract's required field {name} is missing")
    return out


def validate(kind: str, row: dict) -> list[str]:
    """What is wrong with a row against its contract: a field it does not name, a required one missing (a cancel's vector may be null)."""
    fields = dict(contract_fields(kind)) if kind not in _FIELDS else dict(_FIELDS[kind])
    errs = [f"{k}: not a field of the {kind} contract" for k in row if k not in fields]
    for name, presence in fields.items():
        if presence == "required" and name not in row:
            errs.append(f"{name}: required by the {kind} contract")
    cancel = str(row.get("RUN_KIND") or "").upper() == "INTRADAY" and row.get("TRADE_STATUS") not in LIVE
    for name, presence in fields.items():
        if presence == "required" and row.get(name) is None and name in row and not (cancel and name in (VECTORS.get(kind), "BASE_PV", "PV")):
            if name not in ("SECURITY_ID", "STRATEGY", "TAGS"):
                errs.append(f"{name}: null")
    return errs


_DEMOS: dict = {}


def demo(testdata: Path, pv_systems: tuple[str, ...], position_systems: tuple[str, ...] = ()):
    """The demo's risk batch of the test data's business date (the factor model), the given systems as scenario PVs and the given ones
    per position (GRAIN POSITION; kept per process: the same inputs give the same batch)."""
    import yaml
    key = (str(Path(testdata).resolve()), tuple(pv_systems), tuple(position_systems))
    if key not in _DEMOS:
        dates = yaml.safe_load((testdata / "testdata.yaml").read_text(encoding="utf-8"))["business-dates"]
        _DEMOS[key] = M.Demo(testdata, dates, pv_systems=pv_systems, position_systems=position_systems)
    return _DEMOS[key]


def move(row: dict, old: str, new: str) -> dict:
    """A row's dates moved from business date ``old`` to ``new`` (every dated field; ids that carry the date)."""
    if old == new:
        return row
    r = dict(row)
    for f in DATED + tuple(KEYS.values()):
        if isinstance(r.get(f), str):
            r[f] = r[f].replace(old, new)
    return r


def write_jsonl(path: Path, rows: list[dict], gz: bool = False) -> Path:
    """One JSON object per line, UTF-8: the delivery format of the contract."""
    path = path.with_name(path.name + ".gz") if gz and not path.name.endswith(".gz") else path
    path.parent.mkdir(parents=True, exist_ok=True)
    text = "".join(json.dumps(r, ensure_ascii=False, separators=(",", ":")) + "\n" for r in rows)
    if gz:
        with gzip.open(path, "wt", encoding="utf-8") as fh:
            fh.write(text)
    else:
        path.write_text(text, encoding="utf-8")
    return path


def read_jsonl(path: Path) -> list[dict]:
    opener = gzip.open if str(path).endswith(".gz") else open
    with opener(path, "rt", encoding="utf-8") as fh:
        out = []
        for n, line in enumerate(fh, 1):
            if line.strip():
                d = json.loads(line)
                if not isinstance(d, dict):
                    raise ValueError(f"{path}:{n}: a line is one JSON object")
                out.append(d)
        return out


def kind_of(rows: list[dict]) -> str:
    kinds = {"scenario-pv" if "SCENARIO_PV_ID" in r else "pnl-vector" if "VECTOR_ID" in r else "fx-scenario" for r in rows}
    if len(kinds) != 1:
        raise ValueError(f"one kind a file, not {sorted(kinds)}")
    return kinds.pop()


def lake_build(path: Path, kind: str, lake: str, mode: str, signal: str | None, token: str | None, say) -> int:
    """Writes a JSONL file to the lake through the existing lake build (Drishti's Java writer when the jar is there, else deltalake),
    then raises the data-load signal when ``signal`` names a server."""
    cmd = [sys.executable, str(ROOT / "tools" / "drishti.py"), "lake", "build", "--from", str(path), "--pack", str(PACK), "--connector",
           "risk-store", "--kind", kind, "--key", KEYS[kind], "--date", "BUSINESS_DATE", "--mode", mode, "--lake", lake, "-q"]
    if signal:
        cmd += ["--signal", signal]
    env = dict(os.environ, **({"DRISHTI_TOKEN": token} if token else {}))
    r = subprocess.run(cmd, env=env, cwd=ROOT)
    if r.returncode:
        say(f"lake build of {path.name} failed (exit {r.returncode})")
    return r.returncode


def token_of(path: str | None) -> str | None:
    if path:
        return Path(path).read_text(encoding="utf-8").strip()
    return os.environ.get("DRISHTI_TOKEN")


def seconds(text: str) -> float:
    """``30s``, ``5m``, ``1h`` or a number of seconds."""
    t = str(text).strip().lower()
    mult = {"s": 1, "m": 60, "h": 3600}.get(t[-1:], 1)
    return float(t[:-1] if t[-1:] in "smh" else t) * mult


# ---------------------------------------------------------------------------------------------------------------- the morning run

class Morning:
    """The demo's morning run as a pricing system sends it: the trades' rows (scenario PVs or P&L vectors), the scenario FX, moved to
    ``day``; each row exactly its contract's fields; ``RUN_KIND: FULL``."""

    def __init__(self, testdata: Path, pv_systems: tuple[str, ...], day: str | None = None, position_systems: tuple[str, ...] = ()):
        self.demo = demo(testdata, pv_systems, position_systems)
        self.position_systems = position_systems
        self.pv_systems = pv_systems
        self.cob = self.demo.cob
        self.day = day or self.cob
        b = self.demo.batch
        self.fx = self.demo.fx
        self.rows = {"scenario-pv": [contract_row("scenario-pv", move({**r, "RUN_KIND": "FULL"}, self.cob, self.day)) for r in b.pv_rows],
                     "pnl-vector": [contract_row("pnl-vector", move({**r, "RUN_KIND": "FULL"}, self.cob, self.day)) for r in b.vector_rows],
                     "fx-scenario": [contract_row("fx-scenario", move(r, self.cob, self.day))
                                     for r in self.fx.rows(self.cob, "HS-509", f"B-{self.cob}-01")]}
        self.at = max(str(r.get("COMPUTED_AT")) for k in ("scenario-pv", "pnl-vector") for r in self.rows[k])

    def trades(self) -> dict[str, tuple[str, dict]]:
        """Every record of the run, a trade or a supplied position: its id to (its kind, its row)."""
        out = {}
        for k in ("scenario-pv", "pnl-vector"):
            for r in self.rows[k]:
                out[ident(r)] = (k, r)
        return out


def ident(r: dict) -> str:
    """A record's id: its TRADE_ID, or its POSITION_ID at GRAIN POSITION."""
    return str(r["POSITION_ID"] if r.get("GRAIN") == "POSITION" else r["TRADE_ID"])


def id_field(r: dict) -> str:
    return "POSITION_ID" if r.get("GRAIN") == "POSITION" else "TRADE_ID"


# ---------------------------------------------------------------------------------------------------------------- intraday updates

class Updates:
    """The day's intraday updates (docs/guides/VAR_GUIDE.md section 8.4), batch after batch, the same for a seed: amended trades (their
    quantity, and so every PV, moved by a factor of 0.8 to 1.2), new trades (a copy of a live trade in its book, a new id in its
    system's range) and cancels (``TRADE_STATUS: CANCELLED``, the vector null), each row marked ``RUN_KIND: INTRADAY`` with its batch's
    ``BATCH_ID`` and a ``COMPUTED_AT`` after the morning run's. A trade's update is sent as scenario PVs when its system is in
    ``pv_systems`` (and its currency has scenario FX), else as a P&L vector, whatever form the morning run sent it in."""

    def __init__(self, morning: Morning, seed: int = 7, mix: dict[str, int] | None = None, pv_systems: tuple[str, ...] | None = None,
                 start: str | None = None, step_seconds: int = 60, only: tuple[str, ...] = (), first: int = 1):
        self.m = morning
        self.rnd = random.Random(seed)
        self.mix = mix or {"amend": 70, "new": 15, "cancel": 15}
        self.pv_systems = morning.pv_systems if pv_systems is None else pv_systems
        self.live = morning.trades()                       # trade id -> (kind, row) as it stands now
        if only:                                           # the updates touch these systems' trades only
            self.live = {t: kr for t, kr in self.live.items() if str(kr[1].get("SOURCE_SYSTEM")) in only}
        self.ids = sorted(self.live)
        self.next_new = {s: n for s, (n, _) in NEW_IDS.items()}
        from datetime import datetime, timedelta, timezone
        t0 = datetime.fromisoformat((start or morning.at).replace("Z", "+00:00"))
        self.t0 = t0 if start else t0 + timedelta(minutes=5)
        self.step = timedelta(seconds=step_seconds)
        self.tz = timezone.utc
        self.n = max(1, first) - 1                         # a second run on the same date goes on numbering (--first)
        self.first = self.n

    def _at(self, batch: int, k: int) -> str:
        return (self.t0 + self.step * (batch - self.first) + __import__("datetime").timedelta(seconds=k)).astimezone(self.tz).strftime("%Y-%m-%dT%H:%M:%SZ")

    def _usd(self, kind: str, row: dict) -> tuple[float, list[float], str]:
        """A row's USD value today and USD P&L (as the engine computes it), and its currency."""
        if kind == "pnl-vector":
            return float(row.get("PV") or 0.0), [float(x) for x in row["PNL"]], str(row.get("CURRENCY") or "USD")
        ccy = row["CURRENCY"]
        return row["BASE_PV"] * self.m.fx.today(ccy), SFX.engine_pnl(self.m.fx, ccy, row["BASE_PV"], row["SCENARIO_PV"]), ccy

    def _row(self, src_kind: str, src: dict, f: float, tid: str, batch_id: str, at: str, status: str | None = None) -> tuple[str, dict]:
        """The update of a trade from its current row: scaled by ``f``, in the form its system sends, marked intraday."""
        pv_usd, pnl, ccy = self._usd(src_kind, src)
        system = str(src.get("SOURCE_SYSTEM") or "")
        base = {k: v for k, v in src.items() if k not in ("SCENARIO_PV_ID", "VECTOR_ID", "SCENARIO_PV", "PNL", "BASE_PV", "PV", "REPORTING_CCY",
                                                       "VALUATION_DATE", "BASE_VALUATION_DATE", "TRADE_ID", "POSITION_ID")}
        base.update({id_field(src): tid, "BATCH_ID": batch_id, "COMPUTED_AT": at, "RUN_KIND": "INTRADAY", "QUANTITY": round(float(src.get("QUANTITY")
                     or 0) * f, 6), "TRADE_STATUS": status or ("AMENDED" if tid in self.live else "NEW"), "STATUS": "ok"})
        day = self.m.day
        if system in self.pv_systems and self.m.fx.has(ccy):
            b, pvs = SFX.scenario_pvs(self.m.fx, ccy, pv_usd * f, [x * f for x in pnl])
            row = {**base, "VALUE_TYPE": "PV", "SCENARIO_PV_ID": f"SPV-{tid}-HS-509-{batch_id}", "BASE_PV": b, "SCENARIO_PV": pvs,
                   "VALUATION_DATE": day, "BASE_VALUATION_DATE": day, "CURRENCY": ccy}
            return "scenario-pv", contract_row("scenario-pv", row)
        row = {**base, "VALUE_TYPE": "PNL", "VECTOR_ID": f"PV-{tid}-HS-509-{batch_id}", "PV": round(pv_usd * f, 2),
               "PNL": [round(x * f, 2) for x in pnl], "REPORTING_CCY": "USD"}
        return "pnl-vector", contract_row("pnl-vector", row)

    def _cancel(self, kind: str, src: dict, batch_id: str, at: str) -> tuple[str, dict]:
        row = {**src, "BATCH_ID": batch_id, "COMPUTED_AT": at, "RUN_KIND": "INTRADAY", "TRADE_STATUS": "CANCELLED", "STATUS": "ok",
               VECTORS[kind]: None}
        row[KEYS[kind]] = f"{'SPV' if kind == 'scenario-pv' else 'PV'}-{ident(src)}-HS-509-{batch_id}"
        return kind, contract_row(kind, row)

    def batch(self, size: int, plan: list[str] | None = None) -> tuple[str, list[tuple[str, dict]]]:
        """The next batch: ``size`` updates (each trade once), as (kind, row) in the order made; ``plan`` names each update's kind
        (amend, new, cancel) instead of drawing it from the mix."""
        b = self.n
        self.n += 1
        batch_id = f"I-{self.m.day}-{b + 1:03d}"
        out: list[tuple[str, dict]] = []
        used: set[str] = set()
        kinds = [k for k, w in self.mix.items() for _ in range(max(0, int(w)))]
        for k in range(len(plan) if plan else size):
            what = plan[k] if plan else self.rnd.choice(kinds)
            at = self._at(b, k)
            if what == "new":
                kind, src = self.live[self.rnd.choice(self.ids)]
                system = str(src.get("SOURCE_SYSTEM") or "ENDUR")
                n = self.next_new.get(system, 9_000_000)
                self.next_new[system] = n + 1
                tid = f"POS-{system}-NEW-{n}" if src.get("GRAIN") == "POSITION" else f"{NEW_IDS.get(system, (0, ''))[1]}{n}"
                kind2, row = self._row(kind, src, round(self.rnd.uniform(0.5, 1.5), 2), tid, batch_id, at)
                self.live[tid] = (kind2, row)
                self.ids.append(tid)
                out.append((kind2, row))
                used.add(tid)
                continue
            choices = [t for t in self.ids if t not in used]
            if not choices:
                break
            tid = self.rnd.choice(choices)
            used.add(tid)
            kind, src = self.live[tid]
            if what == "cancel":
                out.append(self._cancel(kind, src, batch_id, at))
                del self.live[tid]
                self.ids.remove(tid)
                continue
            f = round(self.rnd.uniform(0.8, 1.2), 2)
            kind2, row = self._row(kind, src, f, tid, batch_id, at)
            self.live[tid] = (kind2, {**row, "TRADE_STATUS": src.get("TRADE_STATUS") if src.get("TRADE_STATUS") in LIVE else "AMENDED"})
            out.append((kind2, row))
        return batch_id, out


def mix_of(text: str) -> dict[str, int]:
    """``amend=70,new=15,cancel=15``."""
    out = {}
    for part in (text or "").split(","):
        if part.strip():
            k, _, v = part.partition("=")
            if k.strip() not in ("amend", "new", "cancel"):
                raise ValueError(f"--mix names amend, new and cancel, not {k}")
            out[k.strip()] = int(v)
    return out or {"amend": 70, "new": 15, "cancel": 15}


# ---------------------------------------------------------------------------------------------------------------- what the engine shows

def expected(morning_rows: dict[str, list[dict]], intraday_rows: list[dict], business_date: str) -> dict[str, list[float]]:
    """What the VaR engine should show after the morning run and every intraday row (docs/guides/VAR_GUIDE.md section 8.4): per scope
    (``BANK`` and ``DESK:<id>``), the USD P&L under each scenario: each live trade's USD P&L (a P&L vector as sent; scenario PVs through
    ``engine_pnl`` with the supplied scenario FX), the latest intraday row per trade by ``COMPUTED_AT`` replacing it (an equal one keeps
    the first; one before the morning run ignored), a cancel removing it."""
    fx = _Fx(morning_rows.get("fx-scenario") or [])
    trades: dict[str, dict] = {}
    morning_at = ""
    for k in ("pnl-vector", "scenario-pv"):
        for r in morning_rows.get(k) or []:
            trades[ident(r)] = {**r, "_kind": k}
            morning_at = max(morning_at, str(r.get("COMPUTED_AT") or ""))
    latest: dict[str, dict] = {}
    for r in intraday_rows:
        tid, at = ident(r), str(r.get("COMPUTED_AT") or "")
        if at and morning_at and at < morning_at:
            continue
        if tid in latest and at <= str(latest[tid].get("COMPUTED_AT") or ""):
            continue
        latest[tid] = {**r, "_kind": "scenario-pv" if "SCENARIO_PV_ID" in r else "pnl-vector"}
    trades.update(latest)
    out: dict[str, list[float]] = {}
    for t in trades.values():
        if t.get("TRADE_STATUS") not in LIVE or str(t.get("MATURITY_DATE") or "9999")[:10] < business_date:
            continue
        if t["_kind"] == "pnl-vector":
            v = [float(x) for x in t["PNL"]]
        else:
            v = SFX.engine_pnl(fx, t["CURRENCY"], t["BASE_PV"], t["SCENARIO_PV"])
        for scope in ("BANK", f"DESK:{t.get('DESK')}"):
            acc = out.setdefault(scope, [0.0] * len(v))
            for j, x in enumerate(v):
                acc[j] += x
    return out


class _Fx:
    """Scenario FX read from fx-scenario rows (USD per unit), as ``scenario_fx.Fx`` answers it."""

    def __init__(self, rows: list[dict]):
        self.rate = {r["CURRENCY"]: float(r["RATE"]) for r in rows}
        self.rates = {r["CURRENCY"]: [float(x) for x in r["RATES"]] for r in rows}

    def has(self, ccy: str) -> bool:
        return ccy == "USD" or ccy in self.rate

    def today(self, ccy: str) -> float:
        return 1.0 if ccy == "USD" else self.rate[ccy]

    def at(self, ccy: str, j: int) -> float:
        return 1.0 if ccy == "USD" else self.rates[ccy][j]


def measures(v: list[float]) -> tuple[float, float]:
    return VM.var(v, 0.99), VM.es(v, 0.975)
