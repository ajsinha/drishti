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

"""Admin → Lakes → Maintain: retention, compaction, checkpoint and vacuum of the lake tables from the browser, now or on schedules, with
the engine of ``drishti.py lake maintain`` (tools/lake/maintain.py, imported, never re-implemented) and its YAML file.

* The settings and schedules live in ``<data>/lakes/lake-maintenance.yaml``, the file ``lake maintain`` reads by default, so the page,
  the command line and cron share one file; the history in ``<data>/lakes/maintenance-history.jsonl`` (``lake maintain --history``).
* Only tables the lake tool built can be maintained (tools/lake/ownership.py); the others are listed as maintained by their publisher.
* A run is a background thread of this console: one at a time per lake (and the lake tool's lock keeps it apart from builds and from
  the command line), cancellable between tables. Afterwards the data-load signal goes out for each changed table of a pack, as the
  administrator who started it (a schedule: the administrator who made it).
* The schedules tick here every 30 seconds with the lake tool's own scheduler (tools/lake/maintschedule.py, also behind
  ``lake maintain --daemon``); a time missed while the console was down runs once at start, and the history says so.
"""
from __future__ import annotations

import asyncio
import logging
import re
import secrets
import threading
import time
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo

from core.lakes import LakeError, _tool

log = logging.getLogger(__name__)
TABLE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}/[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$")
SCHEDULE_NAME = re.compile(r"^[A-Za-z0-9][A-Za-z0-9 _.-]{0,47}$")
TICK_SECONDS = 30


@dataclass
class MaintRun:
    id: str
    by: str
    dry_run: bool
    selection: dict
    schedule: str | None = None
    note: str | None = None
    started: float = field(default_factory=time.time)
    state: str = "running"                      # running, done, failed, cancelled
    current: str | None = None
    results: list = field(default_factory=list)
    entry: dict | None = None
    error: str | None = None
    cancel: threading.Event = field(default_factory=threading.Event)
    ident_for: object = None
    signals: list = field(default_factory=list)              # (pack, table, body) still to send
    signalled: bool = False

    def view(self) -> dict:
        total = sum(len(v) for v in self.selection.values())
        return {"id": self.id, "by": self.by, "dryRun": self.dry_run, "schedule": self.schedule, "note": self.note, "state": self.state,
                "current": self.current, "done": len(self.results), "total": total, "results": self.results, "entry": self.entry,
                "error": self.error, "cancelling": self.cancel.is_set() and self.state == "running", "seconds": round(time.time() - self.started, 1)}


class LakeMaintenance:
    """The maintenance runs, settings and schedules of this console process."""

    def __init__(self, lakes):
        self.lakes = lakes
        cfg = lakes.cfg
        self.data = Path(cfg.data_dir) if getattr(cfg, "data_dir", None) else (cfg.roots[0].parent if cfg.roots else Path("data"))
        self.runs: dict[str, MaintRun] = {}
        self.guard = threading.Lock()                # the runs dict and the YAML's read-modify-write
        self.scheduler = None

    # -------------------------------------------------------------------------------------------- the engine's modules

    def mod(self, name: str):
        return _tool(self.lakes.cfg.tools_dir, name)

    @property
    def config_file(self) -> Path:
        return self.mod("maintconfig").default_path(self.data)

    def config(self) -> dict:
        try:
            return self.mod("maintconfig").load(self.config_file)
        except ValueError as e:
            raise LakeError(409, f"{self.config_file}: {e}") from e

    def history_file(self, config: dict | None = None) -> Path:
        return self.mod("maintconfig").history_path(config if config is not None else self.config(), self.data)

    def zone(self, config: dict) -> ZoneInfo:
        try:
            return ZoneInfo(str((config.get("schedule") or {}).get("zone") or "America/New_York"))
        except Exception:  # noqa: BLE001 - a bad zone name in the file: the default
            return ZoneInfo("America/New_York")

    # -------------------------------------------------------------------------------------------- what the page shows

    def overview(self, figures: list[dict]) -> dict:
        mc, ms, own = self.mod("maintconfig"), self.mod("maintschedule"), self.mod("ownership")
        config = self.config()
        built = {str(r): own.built(r) for r in self.lakes.cfg.roots}
        rows = []
        for t in figures:
            name, root = t.get("table"), t.get("root")
            rec = (built.get(root) or {}).get(name)
            rows.append({"root": root, "table": name, "domain": t.get("domain"), "format": t.get("format") or "delta", "owned": rec is not None, "owner": rec,
                         "reason": None if rec else own.FOREIGN, "settings": mc.table_settings(config, root, name),
                         "files": t.get("files"), "size": t.get("size"), "bytes": t.get("bytes"), "error": t.get("error")})
        zone = self.zone(config)
        now = datetime.now(zone)
        sched = self._scheduler(config)
        schedules = []
        for s in mc.schedules(config):
            nxt = None
            if s.get("when") and s["when"] != ms.AFTER_BUILD and not s.get("paused"):
                try:
                    n = ms.Cron(s["when"]).next(now)
                    nxt = n.strftime("%a %Y-%m-%d %H:%M") if n else None
                except ms.CronError:
                    nxt = None
            schedules.append({**s, "friendly": ms.friendly(s.get("when") or ""), "next": nxt, "lastRun": sched.view(s).get("lastRun")})
        return {"tables": rows, "schedules": schedules, "history": mc.history(self.history_file(config), 50), "configFile": str(self.config_file),
                "historyFile": str(self.history_file(config)), "zone": str(zone), "running": [r.view() for r in self.runs.values() if r.state == "running"],
                "vacuumSafeHours": mc.VACUUM_SAFE_HOURS, "defaults": mc.TABLE_DEFAULTS}

    def command(self, selection: dict, dry_run: bool = False) -> str:
        return self.mod("maintconfig").command(self.config_file, selection, dry_run)

    # -------------------------------------------------------------------------------------------- selections

    def check_selection(self, selection, owned_only: bool = True) -> dict[str, list[str]]:
        """{root: [domain/table]} limited to this console's roots and, for a run, to the tables the lake tool built."""
        if not isinstance(selection, dict) or not selection:
            raise LakeError(400, "pick at least one table")
        roots = {str(r): r for r in self.lakes.cfg.roots}
        own = self.mod("ownership")
        out = {}
        for root, names in selection.items():
            if str(root) not in roots:
                raise LakeError(400, "choose tables under the lake roots this console may write (lakes.roots)")
            if not isinstance(names, list) or not names or not all(isinstance(n, str) and TABLE.match(n) for n in names):
                raise LakeError(400, "tables are named domain/table")
            if owned_only:
                built = own.built(root)
                foreign = [n for n in names if n not in built]
                if foreign:
                    raise LakeError(403, f"{', '.join(foreign)}: {own.FOREIGN}; this console never compacts or vacuums such a table", "DRS-5002")
            out[str(root)] = sorted(set(names))
        return out

    # -------------------------------------------------------------------------------------------- runs

    def start(self, selection: dict, dry_run: bool, by: str, ident_for, schedule: str | None = None, note: str | None = None) -> MaintRun:
        """Starts a run in a thread; ``ident_for()`` gives the identity the data-load signal is sent as (called when it is sent, by
        :meth:`after`: on the admin's next poll, or the schedules' next tick)."""
        sel = self.check_selection(selection)
        with self.guard:
            busy = [r for r in self.runs.values() if r.state == "running" and set(r.selection) & set(sel)]
            if busy:
                raise LakeError(409, f"a maintenance run is already going on this lake (started by {busy[0].by}): wait for it, or cancel it", "DRS-5032")
            self._expire()
            run = MaintRun(secrets.token_hex(6), by, bool(dry_run), sel, schedule, note)
            run.ident_for = ident_for
            self.runs[run.id] = run
        threading.Thread(target=self._run, args=(run,), name=f"lake-maintain-{run.id}", daemon=True).start()
        return run

    def _expire(self) -> None:
        done = sorted((r for r in self.runs.values() if r.state != "running"), key=lambda r: r.started)
        for r in done[:-20]:
            del self.runs[r.id]

    def cancel(self, rid: str) -> MaintRun:
        run = self.runs.get(rid)
        if run is None:
            raise LakeError(404, "no such maintenance run", "DRS-5033")
        run.cancel.set()
        return run

    def _run(self, run: MaintRun) -> None:
        maintain, mc = self.mod("maintain"), self.mod("maintconfig")
        started = mc.now_iso()
        try:
            config = self.config()
            for root in run.selection:
                mc.lake_entry(config, root, create=True)             # a root the file does not list yet: its defaults

            def heard(event, res):
                if event == "start":
                    run.current = res["name"]
                elif event in ("done", "skipped"):
                    run.results.append(_slim(res))
                    run.current = None
            report = maintain.run_detailed(config, run.dry_run, None, False, tables_spec=run.selection, cancel=run.cancel.is_set,
                                           on_event=heard, quiet=True)
            run.results = [_slim(t) for t in report["tables"]]
            if not run.dry_run:
                run.signals = self.mod("maintain").signal_bodies(report["tables"], "maintain-" + run.id)
            entry = maintain.history_entry(report, run.dry_run, run.by, run.schedule, run.note, started)
            run.entry = entry
            mc.record(self.history_file(config), entry)
            run.state = "cancelled" if report.get("cancelled") else "failed" if entry["outcome"] == "failed" else "done"
        except Exception as e:  # noqa: BLE001 - the page shows why
            log.exception("lake maintenance run %s failed", run.id)
            run.error, run.state = f"{type(e).__name__}: {e}", "failed"

    async def after(self, backend) -> None:
        """The data-load signal of each finished run's changed tables, once, as the run's administrator (lake build's signal)."""
        for run in list(self.runs.values()):
            if run.state == "running" or not run.signals or run.signalled:
                continue
            run.signalled = True
            by_table = {t["name"]: t for t in run.results}
            for pack, table, body in run.signals:
                try:
                    answer = await backend.announce_load(pack, body, run.ident_for())
                    sig = {"ok": True, "date": body["businessDate"], "verified": (answer or {}).get("verified")}
                except Exception as e:  # noqa: BLE001 - a failed signal is reported, the maintenance stands
                    sig = {"ok": False, "date": body["businessDate"], "error": str(getattr(e, "detail", e))[:300]}
                if table in by_table:
                    by_table[table]["signal"] = [sig]

    # -------------------------------------------------------------------------------------------- settings and schedules

    def _edit(self, change) -> dict:
        mc = self.mod("maintconfig")
        with self.guard:
            config = self.config()
            out = change(config)
            mc.save(self.config_file, config)
        return out or {}

    def save_settings(self, selection: dict, settings: dict) -> dict:
        mc = self.mod("maintconfig")
        sel = self.check_selection(selection)
        try:
            clean, warn = mc.clean_settings(settings or {})
        except (TypeError, ValueError) as e:
            raise LakeError(400, str(e)) from e
        if not clean:
            raise LakeError(400, "send at least one setting")

        def change(config):
            for root, names in sel.items():
                for n in names:
                    mc.set_table_settings(config, root, n, clean)
        self._edit(change)
        return {"saved": clean, "warnings": warn, "tables": sum(len(v) for v in sel.values())}

    def add_schedule(self, body: dict, by: str, roles) -> dict:
        ms, mc = self.mod("maintschedule"), self.mod("maintconfig")
        sel = self.check_selection(body.get("tables"))
        if len(sel) != 1:
            raise LakeError(400, "a schedule maintains tables of one lake: pick tables of one root")
        name = str(body.get("name") or "").strip()
        if not SCHEDULE_NAME.match(name):
            raise LakeError(400, "name the schedule (letters, digits, spaces, - _ .)")
        try:
            if body.get("afterBuild"):
                when = ms.AFTER_BUILD
            elif body.get("cron"):
                when = ms.check(body["cron"])
            else:
                when = ms.cron_of(list(body.get("days") or []), str(body.get("at") or ""))
        except (ValueError, TypeError) as e:
            raise LakeError(400, f"the time: {e}") from e
        (root, names), = sel.items()

        def change(config):
            if any(s.get("name") == name for s in mc.schedules(config) if mc.same_root(s["root"], root)):
                raise LakeError(409, f"this lake already has a schedule named {name!r}", "DRS-5032")
            lake = mc.lake_entry(config, root, create=True)
            lake.setdefault("schedules", []).append({"name": name, "when": when, "tables": names, "paused": False, "by": by,
                                                     "roles": sorted(roles or []), "created": mc.now_iso()})
        self._edit(change)
        return {"name": name, "when": when, "friendly": ms.friendly(when), "root": root, "tables": names}

    def _find(self, config: dict, root: str, name: str) -> tuple[dict, dict]:
        mc = self.mod("maintconfig")
        lake = mc.lake_entry(config, root)
        for s in (lake or {}).get("schedules") or []:
            if s.get("name") == name:
                return lake, s
        raise LakeError(404, f"no schedule named {name!r} on this lake", "DRS-5033")

    def pause_schedule(self, root: str, name: str, paused: bool) -> dict:
        def change(config):
            _, s = self._find(config, root, name)
            s["paused"] = bool(paused)
            return {"name": name, "paused": bool(paused)}
        return self._edit(change)

    def delete_schedule(self, root: str, name: str) -> dict:
        def change(config):
            lake, s = self._find(config, root, name)
            lake["schedules"].remove(s)
            if not lake["schedules"]:
                del lake["schedules"]
            return {"name": name, "deleted": True}
        return self._edit(change)

    # -------------------------------------------------------------------------------------------- the scheduler

    def _scheduler(self, config: dict):
        if self.scheduler is None:
            self.scheduler = self.mod("maintschedule").Scheduler(self.mod("maintconfig").state_path(config, self.data))
        return self.scheduler

    def tick(self, ident_for_schedule, now: datetime | None = None) -> list[MaintRun]:
        """Starts the schedules that are due (one tick of the loop; tests call it with ``now``)."""
        mc, own = self.mod("maintconfig"), self.mod("ownership")
        config = self.config()
        schedules = [s for s in mc.schedules(config) if any(mc.same_root(s["root"], str(r)) for r in self.lakes.cfg.roots)]
        if not schedules:
            return []
        now = now or datetime.now(self.zone(config))
        built = {s["root"]: {k: v.get("lastBuild") for k, v in own.built(s["root"]).items()} for s in schedules}
        sched = self._scheduler(config)
        started = []
        for s, why in sched.due(schedules, now, built):
            root = next(str(r) for r in self.lakes.cfg.roots if mc.same_root(s["root"], str(r)))
            names = [n for n in s.get("tables") or [] if n in built.get(s["root"], {})]
            if not names:
                sched.ran(s, now, built)
                continue
            try:
                run = self.start({root: names}, False, s.get("by") or "schedule", lambda s=s: ident_for_schedule(s),
                                 schedule=s.get("name"), note=why if why.startswith("catch-up") else None)
            except LakeError as e:
                if e.status == 409 and "already going" in e.detail:
                    continue                                          # the next tick tries again
                log.warning("schedule %s could not start: %s", s.get("name"), e.detail)
            else:
                started.append(run)
            sched.ran(s, now, built)
        return started

    async def loop_forever(self, app) -> None:
        """The lifespan's task: a tick every TICK_SECONDS; a failing tick is logged and the loop goes on."""
        told = False
        while True:
            m = maintenance(app)                                      # the app's current one (tests replace the Lakes)
            try:
                await m.after(app.state.backend)                     # the signals of runs finished since the last tick
                await asyncio.to_thread(m.tick, lambda s: schedule_identity(app, s))
                told = False
            except LakeError as e:
                if not told:                                          # no tools folder, a broken file: said once, not every tick
                    log.warning("lake maintenance schedules: %s", e.detail)
                    told = True
            except Exception:  # noqa: BLE001 - never stop the schedules for one bad tick
                log.exception("lake maintenance schedules: a tick failed")
            await asyncio.sleep(TICK_SECONDS)


def _slim(res: dict) -> dict:
    keep = ("name", "root", "before", "after", "estimate", "retention", "compact", "checkpoint", "vacuum", "error", "skipped", "forced", "signal", "dryRun")
    return {k: v for k, v in res.items() if k in keep}


def identity_for(app, user: str, roles) -> object:
    """The identity a signal is sent as, with a fresh token when the console signs its calls."""
    from core.auth import Identity
    ident = Identity(user, user, "", tuple(roles or ("admin",)))
    auth = getattr(app.state, "auth", None)
    mint = getattr(auth, "_with_token", None)
    return mint(ident) if callable(mint) else ident


def schedule_identity(app, s: dict):
    return identity_for(app, s.get("by") or "admin", s.get("roles") or ("admin",))


def maintenance(app) -> LakeMaintenance:
    """The app's LakeMaintenance over its Lakes (made on first use, and again when the Lakes object is replaced)."""
    from routes.lake_routes import lakes_of
    lakes = lakes_of(app)
    m = getattr(app.state, "lake_maint", None)
    if m is None or m.lakes is not lakes:
        m = app.state.lake_maint = LakeMaintenance(lakes)
    return m
