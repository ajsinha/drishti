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

"""``drishti.py upgrade check <new-release-dir>``: read-only. Before the switch of an upgrade, what the operator must still do, said in
words with the exact command: the new release's folder is complete; its Python pins against the running release's (unchanged: the same
Python serves both; changed: the new release needs a Python of its own, and the running release's must not be touched); the Python named
for the new release has every library at the pinned version (checked, never installed: the pip commands are printed for the operator); the
site's settings keys the new release does not know (a retired key refuses the start, naming its replacement); the modules added and
removed. Nothing is installed, written, started or stopped (docs/admin/PRODUCTION_STANDARD.md, Upgrades).

Exit 0: nothing for the operator to do before the switch; 1: at least one [FAIL] line (the switch must wait); 2: a wrong option."""
from __future__ import annotations

import json
import os
import re
import sys
from pathlib import Path

import procs
import pylibs
from procs import LaunchError, say

OK, TODO, FAIL = "ok", "todo", "fail"
_TAG = {OK: "[ OK ]", TODO: "[TODO]", FAIL: "[FAIL]"}
MUST = (Path("drishti-console") / "requirements.txt", pylibs.LOCK, Path("lib") / "lake-wheel.pin", Path("tools") / "drishti.py", Path("modules"))


def release_home(path: str | Path) -> Path:
    """The folder holding drishti-console/ and tools/: the folder named, or its ``drishti`` subfolder (a release unpacked whole)."""
    p = Path(path).expanduser().resolve()
    for c in (p, p / "drishti"):
        if (c / "drishti-console" / "requirements.txt").is_file():
            return c
    return p


def version_of(home: Path) -> str:
    try:
        return (re.search(r"-Drevision=(\S+)", (home / ".mvn" / "maven.config").read_text(encoding="utf-8")) or [None, ""])[1]
    except OSError:
        return ""


def pins(home: Path) -> dict[str, str]:
    """name -> spec of every requirement and lock pin, plus the Lake wheel's pin: what a release asks of its Python."""
    out = {pylibs.norm(n): f"{n}{sp}" for n, sp, _ in pylibs.all_requirements(home)}
    lake = pylibs.lake_pin(home).get("version")
    if lake:
        out["drishti-lake@pin"] = f"drishti-lake=={lake} (lib/lake-wheel.pin)"
    return out


def pin_diff(old: dict[str, str], new: dict[str, str]) -> dict[str, list[str]]:
    return {"added": sorted(new[k] for k in new.keys() - old.keys()), "removed": sorted(old[k] for k in old.keys() - new.keys()),
            "changed": sorted(f"{old[k]}  ->  {new[k]}" for k in old.keys() & new.keys() if old[k] != new[k])}


def settings_keys(files) -> dict[str, str]:
    """key -> file of every ``key=value`` line of the settings files (sections are labels; comments skipped; a value is never read)."""
    out = {}
    for f in files:
        p = Path(f)
        if not p.is_file():
            continue
        for raw in p.read_text(encoding="utf-8", errors="replace").splitlines():
            line = raw.strip()
            if line and line[0] not in "#;[" and "=" in line:
                out.setdefault(line.split("=", 1)[0].strip(), p.name)
    return out


def modules(home: Path) -> set[str]:
    """The module names of <home>/modules: the packages (``banking-1.0.0.dmod`` is ``banking``), else the folders."""
    d = home / "modules"
    if not d.is_dir():
        return set()
    pk = {re.sub(r"-\d[\w.]*$", "", x.name.removesuffix(".dmod")) for x in d.glob("*.dmod")}
    return pk or {x.name for x in d.iterdir() if x.is_dir() and not x.name.startswith(".")}


def same_prefix(a: str, b: str) -> bool:
    """Whether two interpreters are one environment: the folders holding their bin/ are one folder (a symlinked prefix is followed; the
    interpreter file itself is not, a virtual environment's python links to its base)."""
    prefix = lambda p: os.path.realpath(os.path.dirname(os.path.dirname(os.path.abspath(p))))      # noqa: E731
    return prefix(a) == prefix(b)


def check(new_dir: str, current: str | None = None, python: str | None = None, settings: list[str] | None = None, keys: dict | None = None,
          run=None) -> list[tuple[str, str]]:
    """The lines of the report: (ok|todo|fail, text)."""
    run = run or pylibs.subprocess.run
    rep: list[tuple[str, str]] = []
    add = lambda lvl, text: rep.append((lvl, text))                      # noqa: E731
    new = release_home(new_dir)
    keys = keys or {}
    missing = [str(m) for m in MUST if not (new / m).exists()]
    if missing:
        add(FAIL, f"{new} is not a complete release: {', '.join(missing)} not found; unpack the whole release archive (or point at the folder holding drishti-console/ and tools/)")
        return rep
    add(OK, f"the new release is {version_of(new) or '(no version file)'} at {new}")
    if os.access(new, os.W_OK) and hasattr(os, "geteuid") and os.geteuid() != 0:
        add(TODO, f"{new} is writable by this user: after the Python libraries are in place make the release read-only (root-owned, `chmod -R go-w`)")
    # the running release
    cur_arg = current or (keys.get("drishti.home") or "").strip() or os.environ.get("DRISHTI_HOME", "")
    old = release_home(cur_arg) if cur_arg else None
    if old is None or not (old / "drishti-console" / "requirements.txt").is_file():
        add(TODO, "the running release was not found (--current <folder>, or drishti.home in --settings): its pins were not compared")
        old = None
    else:
        add(OK, f"the running release is {version_of(old) or '(no version file)'} at {old}")
        if old == new:
            add(FAIL, "the new release folder is the running one: unpack the new release into its own folder beside it, never over it")
    # Python
    py_old = (keys.get("drishti.python") or os.environ.get("DRISHTI_PYTHON") or "").strip()
    py_new = (python or "").strip()
    try:
        newpins = pins(new)
        diff = pin_diff(pins(old), newpins) if old else None
    except LaunchError as e:
        add(FAIL, str(e))
        return rep
    changed = bool(diff and (diff["added"] or diff["removed"] or diff["changed"]))
    if diff is not None and not changed:
        add(OK, "the Python pins are unchanged: the running release's Python can serve the new release as it is (still verified below)")
    elif diff is not None:
        add(TODO, "the Python pins changed; the new release needs a Python of its own, made and filled by you BEFORE the switch, and the running "
                  "release's Python must not be touched (it is what a rollback starts):")
        for lbl in ("added", "removed", "changed"):
            for line in diff[lbl]:
                add(TODO, f"    {lbl}: {line}")
    if not py_new:
        py_new = py_old
        add(TODO, f"--python not given: the new release is checked against drishti.python of the settings ({py_old or 'none: this Python, ' + sys.executable})")
        py_new = py_new or sys.executable
    if changed and py_old and same_prefix(py_new, py_old):
        add(FAIL, f"the Python for the new release ({py_new}) is the running release's ({py_old}): its libraries would change under the running "
                  "console. Make a separate Python (its own prefix), install the new pins there, and give that one with --python")
    if not Path(py_new).is_file():
        add(FAIL, f"the Python {py_new} is not a file: make it first (your own Python or a virtual environment you create; Drishti makes none) and name it with --python")
    else:
        try:
            bad = pylibs.verify(py_new, new, run)
        except LaunchError as e:
            bad = [str(e)]
        if bad:
            for b in bad:
                add(FAIL, f"{py_new}: {b}")
            for c in pylibs.install_commands(py_new, new):
                add(TODO, f"run yourself (Drishti installs nothing): {c}")
        else:
            add(OK, f"{py_new} has every library of the new release at the pinned version (requirements.txt, python.lock, the Lake wheel)")
    # settings
    sk = settings_keys(settings or [])
    cat = new / "docs" / "admin" / "CONFIGURATION.md"
    if sk and cat.is_file():
        text = cat.read_text(encoding="utf-8", errors="replace")
        odd = sorted(k for k in sk if k not in text and not re.match(r"^(lake|db|broker|node|cluster)\.", k))
        if odd:
            for k in odd:
                add(TODO, f"{k} (in {sk[k]}) is not in the new release's CONFIGURATION.md: if the new release retired it the server REFUSES to start and names the key to use instead; fix it in the site settings before the switch")
        else:
            add(OK, f"every key of the {len(sk)} site settings keys is in the new release's configuration catalogue (a retired key would refuse the start)")
    elif sk:
        add(TODO, "the new release carries no docs/admin/CONFIGURATION.md: the site settings keys were not compared")
    # modules
    if old:
        mo, mn = modules(old), modules(new)
        if mo - mn:
            add(TODO, f"modules the running release has and the new one lacks: {', '.join(sorted(mo - mn))} (a module the site switched on and the new release dropped stops the start: switch it off first)")
        if mn - mo:
            add(OK, f"modules new in the release: {', '.join(sorted(mn - mo))} (off until you switch them on)")
        if mo == mn:
            add(OK, f"the same {len(mn)} modules in both")
    add(TODO, "before the switch: back up the identity database and the content store (OPERATIONS_GUIDE, Upgrades), and keep the previous release folder and its Python")
    return rep


def render(rep) -> int:
    for lvl, text in rep:
        say(f"{_TAG[lvl]} {text}")
    bad = sum(1 for lvl, _ in rep if lvl == FAIL)
    todo = sum(1 for lvl, t in rep if lvl == TODO and not t.startswith("    "))
    say(f"upgrade check: {bad} to fix before the switch, {todo} to do or confirm" + ("" if bad else "; no switch is blocked"))
    return 1 if bad else 0


def main(a) -> int:
    import javacmd
    given, keys = javacmd.settings()
    files = [s for s in given.split(",") if s] if given else []
    rep = check(a.new_release, a.current, a.python, files, keys)
    if a.json:
        say(json.dumps({"ok": not any(l == FAIL for l, _ in rep), "lines": [{"level": l, "text": t} for l, t in rep]}, indent=2))
        return 1 if any(l == FAIL for l, _ in rep) else 0
    return render(rep)
