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

"""The lake-maintenance YAML (one file for ``drishti.py lake maintain --config`` and Admin → Lakes) and the history of runs.

    schedule: {at: "02:30", zone: America/New_York}      # --daemon with no schedules: every table once a day at this time
    history: data/lakes/maintenance-history.jsonl         # optional; default <data>/lakes/maintenance-history.jsonl
    lakes:
      - root: data/delta
        domains: ["*"]
        keep-business-days: null      # lake defaults; null keeps every business date
        target-file-mb: 128
        checkpoint: true
        vacuum-hours: 168
        tables:                       # per table, over the lake's defaults
          trading/trade_endur: {keep-business-days: 260, target-file-mb: 64, checkpoint: true, vacuum-hours: 168}
        schedules:
          - name: nightly
            when: "30 2 * * 2-6"      # cron (minute hour day month weekday), or after-build
            tables: [trading/trade_endur]
            paused: false
            by: admin

The default file is ``<data>/lakes/lake-maintenance.yaml`` (``<data>`` is DRISHTI_DATA_DIR or ./data). Saving writes a new file
and renames it over the old one, keeping every key this module does not know."""
from __future__ import annotations

import json
import os
import pathlib
import shlex
from datetime import datetime

import yaml

SETTINGS = ("keep-business-days", "target-file-mb", "checkpoint", "vacuum-hours")
TABLE_DEFAULTS = {"keep-business-days": None, "target-file-mb": 128, "checkpoint": True, "vacuum-hours": 168}
VACUUM_SAFE_HOURS = 168
HISTORY_KEEP = 2000


def data_dir() -> pathlib.Path:
    return pathlib.Path(os.environ.get("DRISHTI_DATA_DIR") or "data")


def default_path(data: pathlib.Path | None = None) -> pathlib.Path:
    return (data or data_dir()) / "lakes" / "lake-maintenance.yaml"


def history_path(config: dict, data: pathlib.Path | None = None) -> pathlib.Path:
    h = config.get("history")
    return pathlib.Path(str(h)) if h else (data or data_dir()) / "lakes" / "maintenance-history.jsonl"


def state_path(config: dict, data: pathlib.Path | None = None) -> pathlib.Path:
    """What the schedules last did (lake maintain --daemon and the console share it), beside the history."""
    return history_path(config, data).parent / "maintenance-schedule-state.json"


def load(path: pathlib.Path) -> dict:
    if not path.is_file():
        return {}
    doc = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    if not isinstance(doc, dict):
        raise ValueError(f"{path} is not a lake-maintenance file (a mapping with lakes:)")
    return doc


HEADER = ("# Delta Lake maintenance: drishti.py lake maintain --config <this file>, and Admin -> Lakes (both read and write it).\n"
          "# Guide: docs/admin/LAKE_MAINTENANCE.md\n")


def save(path: pathlib.Path, config: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(path.name + ".writing")
    tmp.write_text(HEADER + yaml.safe_dump(config, sort_keys=False, default_flow_style=False, allow_unicode=True), encoding="utf-8")
    os.replace(tmp, path)


def lake_entry(config: dict, root: str, create: bool = False) -> dict | None:
    """The lakes[] entry whose root is ``root`` (paths compared resolved); with ``create`` a new one is added."""
    for lake in config.setdefault("lakes", []) if create else config.get("lakes") or []:
        if same_root(str(lake.get("root")), root):
            return lake
    if not create:
        return None
    lake = {"root": root, "domains": ["*"]}
    config["lakes"].append(lake)
    return lake


def same_root(a: str, b: str) -> bool:
    if "://" in a or "://" in b:
        return a.rstrip("/") == b.rstrip("/")
    return pathlib.Path(a).expanduser().resolve() == pathlib.Path(b).expanduser().resolve()


def table_settings(config: dict, root: str, table: str) -> dict:
    """The settings a table is maintained with: its own over its lake's over the defaults (``source`` says which were its own)."""
    lake = lake_entry(config, root) or {}
    own = ((lake.get("tables") or {}).get(table) or {})
    out = {k: own[k] if k in own else lake.get(k, TABLE_DEFAULTS[k]) for k in SETTINGS}
    out["own"] = sorted(k for k in SETTINGS if k in own)
    return out


def clean_settings(s: dict) -> tuple[dict, list[str]]:
    """Validated settings from a form ({setting: value}; blank retention keeps all) and the warnings to show beside them."""
    out, warn = {}, []
    if "keep-business-days" in s:
        v = s["keep-business-days"]
        if v in (None, ""):
            out["keep-business-days"] = None
        else:
            n = int(v)
            if n < 1:
                raise ValueError("retention is a number of business days, 1 or more (blank keeps every date)")
            out["keep-business-days"] = n
    if "target-file-mb" in s:
        n = int(s["target-file-mb"] or TABLE_DEFAULTS["target-file-mb"])
        if not 1 <= n <= 4096:
            raise ValueError("the target file size is 1 to 4,096 MB")
        out["target-file-mb"] = n
    if "checkpoint" in s:
        out["checkpoint"] = bool(s["checkpoint"]) and str(s["checkpoint"]).lower() not in ("false", "0", "off", "no")
    if "vacuum-hours" in s:
        v = s["vacuum-hours"]
        if v in (None, ""):
            out["vacuum-hours"] = None
        else:
            n = int(v)
            if n < 0:
                raise ValueError("vacuum hours are 0 or more (blank: no vacuum)")
            out["vacuum-hours"] = n
            if n < VACUUM_SAFE_HOURS:
                warn.append(f"vacuum below {VACUUM_SAFE_HOURS} hours (7 days) removes files a reader or a 'known at' view may still need")
    return out, warn


def set_table_settings(config: dict, root: str, table: str, settings: dict) -> None:
    lake = lake_entry(config, root, create=True)
    tables = lake.setdefault("tables", {})
    cur = tables.get(table) or {}
    cur.update(settings)
    tables[table] = cur


def schedules(config: dict) -> list[dict]:
    """Every schedule with its lake root."""
    return [{**s, "root": str(lake.get("root"))} for lake in config.get("lakes") or [] for s in lake.get("schedules") or []]


def command(config_file: pathlib.Path, selection: dict[str, list[str]], dry_run: bool = False) -> str:
    """The CLI line doing what the page does for these tables ({root: [domain/table]})."""
    parts = ["python3", "tools/drishti.py", "lake", "maintain", "--config", str(config_file), "--once"]
    names = [t if len(selection) == 1 else f"{r}|{t}" for r, ts in selection.items() for t in ts]
    if names:
        parts += ["--tables", ",".join(names)]
    if dry_run:
        parts.append("--dry-run")
    return " ".join(shlex.quote(p) for p in parts)


# ------------------------------------------------------------------------------------------------ history

def record(path: pathlib.Path, entry: dict) -> None:
    """Appends one run to the history (JSON Lines), keeping the last HISTORY_KEEP runs."""
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "a", encoding="utf-8") as fh:
        fh.write(json.dumps(entry, default=str) + "\n")
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError:
        return
    if len(lines) > HISTORY_KEEP * 1.2:
        tmp = path.with_name(path.name + ".writing")
        tmp.write_text("\n".join(lines[-HISTORY_KEEP:]) + "\n", encoding="utf-8")
        os.replace(tmp, path)


def history(path: pathlib.Path, limit: int = 50) -> list[dict]:
    """The last ``limit`` runs, newest first."""
    if not path.is_file():
        return []
    out = []
    for line in path.read_text(encoding="utf-8").splitlines()[-limit:]:
        try:
            out.append(json.loads(line))
        except ValueError:
            continue
    return out[::-1]


def history_text(rows: list[dict]) -> str:
    if not rows:
        return "no maintenance run yet"
    lines = []
    for r in rows:
        tabs = r.get("tables") or []
        after = "estimate" if r.get("dryRun") else "after"
        mb = [(t.get("before") or {}).get("mb", 0) for t in tabs], [(t.get(after) or {}).get("mb", 0) for t in tabs]
        files = [(t.get("before") or {}).get("files", 0) for t in tabs], [(t.get(after) or {}).get("files", 0) for t in tabs]
        lines.append(f"{r.get('at', '')}  {r.get('outcome', ''):<9} {'dry run' if r.get('dryRun') else 'run':<7} by {r.get('by') or '-'}"
                     + (f" (schedule {r['schedule']})" if r.get("schedule") else "")
                     + f"  {len(tabs)} table(s)  files {sum(files[0])} -> {sum(files[1])}  MB {sum(mb[0]):.2f} -> {sum(mb[1]):.2f}"
                     + (f"  note: {r['note']}" if r.get("note") else ""))
        for t in tabs:
            if t.get("error"):
                lines.append(f"    {t.get('name')}: {t['error']}")
    return "\n".join(lines)


def now_iso() -> str:
    return datetime.now().astimezone().isoformat(timespec="seconds")
