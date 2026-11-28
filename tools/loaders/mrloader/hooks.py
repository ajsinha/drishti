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

"""The two optional hooks that end a run, after every file is written and checked (docs/guides/BANK_DATA_GUIDE.md section 6.5). Neither is a
loader: the data inbox, or Drishti's own ``tools/drishti.py lake build``, does the load.

  --inbox [DIR]        hand the files to the data inbox: every data file first (copied under a visible temporary name, renamed into
                       place), in dependency order, then the markers in the same order. One inbox run loads its batch by the data files'
                       modification times, so the copy order is the load order (CSV_LOADERS.md section 18.1).
  --load --lake NAME   run ``drishti.py lake build --engine java`` once per file, in dependency order; stop at the first failure and
                       return its exit code. ``--plan`` passes --plan: nothing is written.
"""
from __future__ import annotations

import os
import shutil
import subprocess
import sys
from pathlib import Path

from loaders.mrloader.common import DEFAULT_INBOX, ROOT, PvfError, log
from loaders.mrloader.package import read_marker

#: the default order of the kinds (CSV_LOADERS.md section 13) when the mapping files' ``after:`` lists do not decide
DEFAULT_ORDER = ("legal-entity", "desk", "book", "counterparty-group", "counterparty", "agreement", "csa", "netting-set",
                 "collateral-balance", "credit-limit", "risk-factor", "scenario-set", "fx-scenario", "scenario-pv", "pnl-vector",
                 "var-limit", "position-sensitivity", "trade-valuation", "simulation-run", "exposure-path", "desk-pnl")


def kind_of(path: Path) -> str:
    """The kind a loadable file holds, from its marker (``kind:``, or a tar's one folder)."""
    m = read_marker(Path(f"{path}.ready"))
    if m.get("kind"):
        return str(m["kind"])
    folders = m.get("folders") or {}
    if len(folders) == 1:
        return next(iter(folders))
    raise PvfError(f"{Path(path).name}.ready names no kind")


def pack_of(path: Path) -> str | None:
    m = read_marker(Path(f"{path}.ready"))
    return m.get("pack") or next(iter((m.get("folders") or {}).values()), None)


def ordered(files: list[Path], order: list[str] | tuple[str, ...] = DEFAULT_ORDER) -> list[Path]:
    """The files in delivery order: by the kind's place in ``order`` (unknown kinds last), then by name (a normal scenario set before
    a stress one, by name)."""
    rank = {k: i for i, k in enumerate(order)}
    return sorted((Path(f) for f in files), key=lambda f: (rank.get(kind_of(f), len(rank)), f.name))


def to_inbox(files: list[Path], inbox: Path, order: list[str] | tuple[str, ...] = DEFAULT_ORDER) -> list[str]:
    """Hand the files to the data inbox in two phases: every data file copied (under ``<name>.copying``, renamed into place) in delivery
    order, then every marker in the same order, so the inbox never sees a marker before its file and one cycle takes the day in order.
    Returns the names delivered."""
    inbox = Path(inbox)
    if not inbox.is_dir():
        raise PvfError(f"the data inbox {inbox} does not exist: is the server's data load on (DRISHTI_DATALOAD_ENABLED)?")
    files = ordered(files, order)
    for f in files:
        if not Path(f"{f}.ready").is_file():
            raise PvfError(f"{f.name} has no .ready marker")
    for f in files:
        part = inbox / f"{f.name}.copying"                    # no hidden names; the watcher reads only files with a marker
        shutil.copyfile(f, part)
        os.replace(part, inbox / f.name)
    for f in files:
        shutil.copyfile(f"{f}.ready", inbox / f"{f.name}.ready")
        log.info("handed %s and its .ready to the data inbox %s", f.name, inbox)
    return [f.name for f in files]


def lake_build_command(path: Path, lake: str, settings: str | None = None, server: str | None = None, token_file: str | None = None,
                       plan: bool = False) -> list[str]:
    """The one ``drishti.py lake build`` line that loads one file: Java engine, its kind, its date replaced as a whole."""
    cmd = [sys.executable, str(ROOT / "tools" / "drishti.py")]
    if settings:
        cmd += ["--settings", settings]
    cmd += ["lake", "build", "--engine", "java", "--from", str(path), "--lake", lake, "--kind", kind_of(path), "--mode", "overwrite-dates"]
    if server:
        cmd += ["--signal", server]
        if token_file:
            cmd += ["--token-file", token_file]
    if plan:
        cmd.append("--plan")
    return cmd


def load(files: list[Path], lake: str, order: list[str] | tuple[str, ...] = DEFAULT_ORDER, settings: str | None = None,
         server: str | None = None, token_file: str | None = None, plan: bool = False, runner=None) -> int:
    """Run ``lake build`` for each file in delivery order; the first non-zero exit code stops the run and is returned (0: all loaded).
    ``runner`` is replaced by a recorder in the tests."""
    if not lake:
        raise PvfError("--load needs --lake NAME (the lake of config/lakes/ the files go to)")
    for f in ordered(files, order):
        cmd = lake_build_command(f, lake, settings, server, token_file, plan)
        log.info("running: %s", " ".join(cmd[1:]))
        rc = (runner or subprocess.run)(cmd, check=False).returncode
        if rc:
            log.error("lake build of %s exited %s: the files after it were not loaded", f.name, rc)
            return rc
    return 0


def hand_by_hand(files: list[Path], lake: str | None, inbox: Path, order: list[str] | tuple[str, ...] = DEFAULT_ORDER) -> list[str]:
    """The lines to load the files by hand, in order (printed when neither hook was asked for)."""
    try:
        rel = lambda p: os.path.relpath(p, ROOT) if Path(p).resolve().is_relative_to(ROOT) else str(p)  # noqa: E731
        lines = [f"copy into {rel(inbox)}: " + ", ".join(f"{f.name} then {f.name}.ready" for f in ordered(files, order))]
        lines += [" ".join(["python3", "tools/drishti.py", *lake_build_command(f, lake or "<lake>")[2:]]).replace(str(f), rel(f))
                  for f in ordered(files, order)]
        return lines
    except PvfError:
        return []


def _shown(p: Path) -> Path:
    """A folder as the help shows it: relative to the install when it is in it, else as it is (a data folder named elsewhere)."""
    return p.relative_to(ROOT) if p.is_relative_to(ROOT) else p


def add_arguments(group, shared: bool = True) -> None:
    """--inbox and --load (with --plan): the two optional hooks that end a run; ``shared`` also adds --server, --token-file and
    --settings (a script whose remove options already have them passes False). --lake comes from the script's own options."""
    group.add_argument("--inbox", nargs="?", type=Path, const=DEFAULT_INBOX, metavar="DIR",
                       help="after the files are written, copy them and then their markers into the data inbox the running server watches "
                            f"(default {_shown(DEFAULT_INBOX)}), in dependency order")
    group.add_argument("--load", action="store_true", help="instead: load the files now, one `drishti.py lake build --engine java` each, "
                       "in dependency order, into --lake; stops at the first failure")
    group.add_argument("--plan", action="store_true", help="with --load: say what each load would do (lake build --plan); write nothing")
    group.add_argument("--data-dir", metavar="DIR", help="the data folder (default drishti.data.dir of the settings file, else DRISHTI_DATA_DIR, else "
                       "data/): the lake NAME's root, the inbox and the loads are under it")
    if shared:
        group.add_argument("--server", help="with --load: the server to signal after each load (lake build --signal URL)")
        group.add_argument("--token-file", help="with --load --server: a file holding the API token (never printed)")
        group.add_argument("--settings", help="with --load: the settings file drishti.py takes (--settings FILE)")


def deliver(a, files: list, order: list[str] | tuple[str, ...] = DEFAULT_ORDER) -> dict | None:
    """The hook the run asked for (--inbox or --load); without either, the lines to do it by hand are logged. With --load the result
    carries lake build's exit code (EXIT)."""
    if getattr(a, "inbox", None) and getattr(a, "load", False):
        raise PvfError("--inbox and --load are two ways to the same end: give one")
    files = [Path(f) for f in files]
    if getattr(a, "data_dir", None):                          # --data-dir: this run and the lake build it starts (the environment) use it
        sys.path.insert(0, str(ROOT / "tools" / "lake"))
        import lakeconfig
        lakeconfig.use_data_dir(a.data_dir)
        if a.inbox == DEFAULT_INBOX:
            a.inbox = lakeconfig.data_dir() / "dataload" / "inbox"
    if a.inbox:
        return {"FOLDER": str(a.inbox), "DELIVERED": to_inbox(files, a.inbox, order)}
    if a.load:
        rc = load(files, a.lake, order, settings=getattr(a, "settings", None), server=getattr(a, "server", None),
                  token_file=getattr(a, "token_file", None), plan=a.plan)
        return {"LAKE": a.lake, "EXIT": rc}
    for line in hand_by_hand(files, getattr(a, "lake", None), DEFAULT_INBOX, order):
        log.info("to load: %s", line)
    return None
