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

"""lake delete: remove whole business dates from one table of a lake (docs/guides/DATA_LAKE_GUIDE.md section 5.8).

The table is named by its kind (``--kind scenario-pv``: the pack's table of that kind, as lake build writes it) or by ``--table
domain/table``; the dates by ``--date`` (repeatable) or a range ``--from``/``--to``. The lake's own writer removes them: the lake loader's
``LakeRetention --dates`` (the machinery of row retention and undo), one commit for all the dates: a Delta remove commit, an Iceberg
partition delete, a files store's day files deleted. Delta and Iceberg keep the removed files until the table's vacuum or snapshot expiry
(time travel reads the table as it was until then).

It first says what it would delete (rows per date, a dry run), refuses a date the table does not hold, then asks for the table's name
to confirm (``--yes`` skips the question; without a terminal it is refused unless given). The run holds the table's lock and is logged
in ``<lake>/_drishti-lake/runs.log`` and ``runs/<run>.json``. Then it tells the server (``--server`` or DRISHTI_SERVER; ``--no-signal``
for none): a data-load signal with status ``deleted`` per date, and the server drops the dates at once (the VaR engine answers
"not loaded" for them; VAR_GUIDE.md section 15.8).

    drishti.py lake delete --lake risklake --kind scenario-pv --date 2026-11-16 --dry-run
    drishti.py lake delete --lake risklake --kind scenario-pv --date 2026-11-16 --server http://localhost:18480 --yes
    drishti.py lake delete --lake data/delta/demo --table risk/pnl-vector --from 2026-10-01 --to 2026-10-03

Exit codes: 0 deleted (or a dry run), 1 refused or failed (nothing changed), 2 deleted but the signal failed, 3 another run holds the lock."""
from __future__ import annotations

import argparse
import json
import os
import pathlib
import re
import sys
import time
import types
from datetime import date, datetime

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

RETENTION = "com.ash.lake.load.LakeRetention"
NAME = re.compile(r"[a-z0-9][a-z0-9_-]*")


class Refused(RuntimeError):
    """A delete that cannot be done as asked, and why (nothing changed)."""


# ------------------------------------------------------------------------------------------------ where the table is

def lake_of(given: str) -> tuple[str, str | None, str | None]:
    """(root, format or None when it is read from the table, the lake's name) of ``--lake``: a lake of config/lakes/ by its name, or a folder."""
    import lakeconfig
    p = pathlib.Path(given)
    if not p.is_dir() and "/" not in given and "\\" not in given and NAME.fullmatch(given):
        try:
            for lake in lakeconfig.dump().get("lakes") or []:
                if lake.get("lake") == given:
                    return lakeconfig.root(given), lake.get("format"), given
        except lakeconfig.LakeConfigError:
            pass
        return lakeconfig.root(given), None, given
    return str(p.resolve()), None, None


def format_of(root: str, name: str) -> str | None:
    """The store a table is in, from what is on disk: delta (a _delta_log), iceberg (metadata/), files (<domain>/<date>/<kind>.jsonl)."""
    import maintain_iceberg
    t = pathlib.Path(root) / name
    if (t / "_delta_log").is_dir():
        return "delta"
    if maintain_iceberg.is_table(t):
        return "iceberg"
    domain, _, kind = name.rpartition("/")
    import lakeconfig
    base = lakeconfig.locate_path(root, domain)
    if base.is_dir() and any(d.is_dir() and (d / f"{kind}.jsonl").is_file() for d in base.iterdir()):
        return "files"
    return None


def tables_on_disk(root: str) -> list[str]:
    """Every ``domain/table`` under a local lake root, whatever its store."""
    base = pathlib.Path(root)
    out: set[str] = set()
    if not base.is_dir():
        return []
    for d in sorted(p for p in base.iterdir() if p.is_dir() and not p.name.startswith("_")):
        for t in d.iterdir():
            if t.is_dir() and format_of(root, f"{d.name}/{t.name}") in ("delta", "iceberg"):
                out.add(f"{d.name}/{t.name}")
            elif t.is_dir():
                out.update(f"{d.name}/{f.name[:-len('.jsonl')]}" for f in t.glob("*.jsonl"))
    return sorted(out)


def pack_route(kind: str) -> tuple[str | None, str | None]:
    """(pack, domain/table) a kind is loaded into by lake build (the pack's table of the kind), or (None, None) when no pack says."""
    try:
        import build
        import feedroute
        t = feedroute.resolve(kind, build.packs_root, kind=None)
        d = build.pack_defaults(t.pack, t.kind, t.connector)
        return d.get("pack"), f"{d.get('lake_domain') or d.get('domain')}/{d.get('table') or d.get('kind')}"
    except Exception:  # noqa: BLE001 - a kind no pack names: the table is looked for on disk
        return None, None


def find_table(root: str, kind: str | None, table: str | None) -> tuple[str, str | None]:
    """(domain/table, pack or None): ``--table`` as given; ``--kind`` the pack's table of the kind, else the one table of that name on disk."""
    if table:
        if table.count("/") != 1:
            raise Refused(f"--table {table}: give it as domain/table (risk/scenario-pv)")
        pack, routed = pack_route(table.split("/")[1])
        return table, pack if routed == table else None
    pack, routed = pack_route(kind)
    remote = "://" in root and not root.startswith("file:")
    if routed and (remote or format_of(root, routed)):
        return routed, pack
    on_disk = [t for t in tables_on_disk(root) if t.split("/")[1] == kind]
    if len(on_disk) == 1:
        return on_disk[0], pack
    if not on_disk:
        raise Refused(f"no table of kind {kind} in {root}" + (f" (looked for {routed})" if routed else "") + "; its tables: "
                      + (", ".join(tables_on_disk(root)) or "none"))
    raise Refused(f"more than one table of kind {kind} in {root} ({', '.join(on_disk)}): give --table domain/table")


# ------------------------------------------------------------------------------------------------ the dates and the Java writer

def date_spec(dates: list[str] | None, start: str | None, end: str | None) -> tuple[str, list[str]]:
    """The ``--dates`` argument of LakeRetention and the dates named one by one (each must be in the table)."""
    named = []
    for d in dates or []:
        for x in d.split(","):
            x = x.strip()
            if x:
                try:
                    named.append(date.fromisoformat(x).isoformat())
                except ValueError:
                    raise Refused(f"--date {x}: not a date (yyyy-mm-dd)") from None
    items = list(named)
    if start or end:
        try:
            lo = date.fromisoformat(start).isoformat() if start else "0001-01-01"
            hi = date.fromisoformat(end).isoformat() if end else "9999-12-31"
        except ValueError as e:
            raise Refused(f"--from/--to: {e}") from None
        if hi < lo:
            raise Refused(f"--to {hi} is before --from {lo}")
        items.append(f"{lo}..{hi}")
    if not items:
        raise Refused("name the dates: --date yyyy-mm-dd (repeatable) or --from/--to")
    return ",".join(items), sorted(set(named))


def plan(root: str, fmt: str, name: str, spec: str, dry_run: bool, jar=None, java=None, runner=None) -> dict:
    """LakeRetention's answer for the dates (``dry_run``: what it would delete): {table, format, held, dropped, dates: [{date, rows, dropped}],
    version}. ``runner(cmd)`` gives its JSON line (tests); by default it runs."""
    import lakeconfig
    import maintain_iceberg
    import stores
    # the Java tool's temporary folder: the lake's own state folder, or for a lake in object storage the configured data folder's
    data = pathlib.Path(root) / "_drishti-lake" if "://" not in root else lakeconfig.data_dir()
    cmd = stores.loader_command(data, jar, java, main=RETENTION) + ["--lake", root, "--format", fmt, "--table", name, "--dates", spec, "--json"]
    if dry_run:
        cmd.append("--dry-run")
    line = runner(cmd) if runner is not None else maintain_iceberg.json_line(cmd, "lake delete")
    return json.loads(line)


def check(found: dict, named: list[str], spec: str, name: str) -> None:
    """Refuses a date named that the table does not hold (and a range that holds none), in words."""
    held = found.get("held") or []
    span = f"{held[0]} .. {held[-1]}" if held else "none"
    missing = [d for d in named if d not in held]
    if missing:
        raise Refused(f"{', '.join(missing)} {'is' if len(missing) == 1 else 'are'} not in {name}: it holds {len(held)} business "
                      f"date{'s' if len(held) != 1 else ''} ({span}); nothing deleted")
    if not found.get("dates"):
        raise Refused(f"no business date of {name} in {spec.replace('..', ' .. ')}: it holds {len(held)} ({span}); nothing deleted")


def rows_text(n) -> str:
    return "rows not counted" if n is None or n < 0 else f"{n:,} rows"


def words(found: dict, dry_run: bool) -> list[str]:
    verb = "would delete" if dry_run else "deleted"
    out = [f"{verb} {found['table']} {x['date']}: {rows_text(x.get('rows'))}" for x in found.get("dates") or []]
    left = [d for d in found.get("held") or [] if d not in {x["date"] for x in found.get("dates") or []}]
    out.append(f"{found['table']} {'would keep' if dry_run else 'keeps'} {len(left)} business date{'s' if len(left) != 1 else ''}"
               + (f" (newest {left[-1]})" if left else ""))
    return out


# ------------------------------------------------------------------------------------------------ the run

def run(a, say=print, ask=input) -> int:
    started = time.monotonic()
    try:
        root, fmt, lake_name = lake_of(a.lake)
        spec, named = date_spec(a.date, a.date_from, a.date_to)
        if not a.kind and not a.table:
            raise Refused("name the table: --kind KIND (scenario-pv) or --table domain/table")
        name, pack = find_table(root, a.kind, a.table)
        fmt = a.format or format_of(root, name) or fmt or "delta"
        before = plan(root, fmt, name, spec, True, a.jar, a.java)
        check(before, named, spec, name)
    except (Refused, RuntimeError, SystemExit) as e:
        say(f"lake delete: {e}")
        return 1
    if a.dry_run:
        say(json.dumps(before, indent=2) if a.json else "\n".join(words(before, True)))
        return 0
    if not a.yes:
        say("\n".join(words(before, True)))
        if not sys.stdin.isatty():
            say("lake delete: give --yes to delete without a terminal (the lines above are what it would do)")
            return 1
        if ask(f"type the table ({name}) to delete these dates: ").strip() != name:
            say("lake delete: not confirmed; nothing changed")
            return 1
    run_id = datetime.now().strftime("%Y%m%dT%H%M%S.%f")[:-3] + f"-{os.getpid()}"
    local = "://" not in root or root.startswith("file:")
    summary = {"run": run_id, "command": "lake-delete", "startedAt": _now(), "lake": lake_name or root, "table": name, "format": fmt,
               "dates": spec, "status": "failed"}
    import build
    try:
        lock = build.LakeLock(pathlib.Path(root), name) if local else _NoLock()
        with lock:
            done = plan(root, fmt, name, spec, False, a.jar, a.java)
    except build.Locked as e:
        say(f"lake delete: {e}")
        return 3
    except (RuntimeError, SystemExit) as e:
        summary["error"] = str(e)
        _log(root, local, summary, started)
        say(f"lake delete: {e}")
        return 1
    summary.update(status="ok", deleted={x["date"]: x.get("rows") for x in done.get("dates") or []}, rows=done.get("dropped"),
                   version=done.get("version"))
    code = 0
    server = None if a.no_signal else (a.server or os.environ.get("DRISHTI_SERVER"))
    if server:
        sent = signal(a, server, pack or a.pack, a.kind or name.split("/")[1], done, run_id)
        summary["signal"] = sent
        code = 0 if sent and all(s.get("ok") for s in sent) else 2
    _log(root, local, summary, started)
    if a.json:
        say(json.dumps({**done, "run": run_id, "signal": summary.get("signal")}, indent=2))
        return code
    for line in words(done, False):
        say(line)
    if done.get("version") is not None:
        say(f"one commit, version {done['version']} (the files stay for time travel until the table's vacuum)")
    if a.no_signal:
        say("no server told (--no-signal): a running server drops the dates within drishti.var.version-check (30 s)")
    elif not server:
        say("no server told (give --server URL or set DRISHTI_SERVER): a running server drops the dates within drishti.var.version-check (30 s)")
    for s in summary.get("signal") or []:
        say(f"  signal {s.get('date', '')}: " + ("sent: the server dropped the date" if s.get("ok") else f"FAILED {s.get('status', '')} {s.get('error', '')}"))
    say(f"run {run_id} logged in {pathlib.Path(root) / build.STATE / 'runs.log'}" if local else f"run {run_id}")
    return code


def signal(a, server: str, pack: str | None, kind: str, done: dict, run_id: str) -> list[dict]:
    """The data-load signal with status ``deleted``, one per date deleted (lake build's signal, its token rules)."""
    if not pack:
        return [{"ok": False, "error": f"no pack names the kind {kind}: give --pack NAME for the signal"}]
    import build
    args = types.SimpleNamespace(signal=server, token_file=a.token_file, user=getattr(a, "user", None))
    rows = {x["date"]: max(0, x.get("rows") or 0) for x in done.get("dates") or []}
    return build.signal(args, {"pack": pack, "kind": kind}, {"rows": rows, "rejected": 0, "run": run_id}, status="deleted", source="lake delete")


class _NoLock:
    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False


def _now() -> str:
    import build
    return build.now_iso()


def _log(root: str, local: bool, summary: dict, started: float) -> None:
    """The run's line in <lake>/_drishti-lake/runs.log and its summary in runs/<run>.json, as lake build's."""
    if not local:
        return
    import build
    summary["finishedAt"] = _now()
    seconds = round(time.monotonic() - started, 3)
    state = pathlib.Path(root) / build.STATE
    (state / "runs").mkdir(parents=True, exist_ok=True)
    (state / "runs" / f"{summary['run']}.json").write_text(json.dumps(summary, indent=2, default=str), encoding="utf-8")
    line = " ".join([_now(), "lake-delete", summary["status"], f"run={summary['run']}", f"table={summary['table']}", f"format={summary['format']}",
                     f"dates={','.join(summary.get('deleted') or {}) or summary['dates']}", f"rows={summary.get('rows', 0)}", f"seconds={seconds}"]
                    + ([f"error={json.dumps(summary['error'])}"] if summary.get("error") else []))
    with open(state / "runs.log", "a", encoding="utf-8") as fh:
        fh.write(line + "\n")


def add_arguments(p: argparse.ArgumentParser) -> argparse.ArgumentParser:
    p.add_argument("--lake", required=True, metavar="NAME|DIR", help="a lake of config/lakes/ by its name (risklake), or its folder")
    t = p.add_mutually_exclusive_group()
    t.add_argument("--kind", help="the kind whose table loses the dates (scenario-pv): the pack's table of the kind")
    t.add_argument("--table", metavar="DOMAIN/TABLE", help="the table by its path in the lake (risk/scenario-pv)")
    p.add_argument("--date", action="append", metavar="YYYY-MM-DD", help="a business date to delete (repeatable, or comma separated)")
    p.add_argument("--from", dest="date_from", metavar="YYYY-MM-DD", help="delete every business date from this one (with --to: a range)")
    p.add_argument("--to", dest="date_to", metavar="YYYY-MM-DD", help="... up to and including this one")
    p.add_argument("--format", choices=["delta", "iceberg", "files"], help="the table's store (default: read from the table on disk)")
    p.add_argument("--dry-run", action="store_true", help="say what would be deleted (rows per date), change nothing")
    p.add_argument("--yes", action="store_true", help="delete without asking for the table's name (scripts)")
    p.add_argument("--no-signal", action="store_true", help="tell no server (a running server notices within its version check)")
    p.add_argument("--pack", help="the pack for the signal (default: the pack whose kind it is)")
    p.add_argument("--jar", help="the drishti-server exec jar (default: the newest in <home>/lib)")
    p.add_argument("--java", help="the java binary")
    return p
