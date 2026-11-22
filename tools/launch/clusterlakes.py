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


"""The synthetic lakes of a cluster file (``lakes:``) and what a node reading one switches off (tools/launch/clusterctl.py).

A node with ``lake: <name>`` reads the demo lake file with its root, ``DRISHTI_DELTA_ROOT``, set to ``<data>/stack/lakes/<name>``: the
synthetic trades of tools/bench/trades.py (trading, with the counterparties and netting sets they name). Two rules keep such a node
healthy:

* **The lake ends on the cluster's business today.** Its last day is the lake entry's ``end:``, else the cluster's
  ``DRISHTI_BUSINESS_TODAY`` (the day every server treats as today), else this machine's date, rolled back to a business day. A lake
  that ends on another day is built again beside the old one (``<name>-next``) and swapped in, so a node never reads a lake whose days
  its servers do not ask for (the node holds no trade of "today", and the cluster's grouped pages miss it).
* **A connector whose tables the lake does not hold is off on the node.** Every connector the node may run (the modules' templates,
  its packs' connectors and the node's own connector files) that reads a lake rooted at ``DRISHTI_DELTA_ROOT`` (the demo lake and the
  official results lakes) with none of its ``lake-tables`` in the synthetic lake is switched off, with the cluster file's ``off:``
  list: the kinds are served by the nodes that hold them (docs/guides/CLUSTER_GUIDE.md, "Is the pack here but not the data?"),
  never reported DOWN. A new module's connector needs no new line in the cluster file.
"""
from __future__ import annotations

import datetime
import importlib.util
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Callable, Iterable

import yaml

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "lake"))
import lakeconfig  # noqa: E402 - the one name of the demo lake's root variable

ROOT_ENV = lakeconfig.DEMO_ROOT_ENV
TODAY_ENV = "DRISHTI_BUSINESS_TODAY"
PART = re.compile(r"business_date=(\d{4}-\d{2}-\d{2})$")


class StackLoader(yaml.SafeLoader):
    """YAML 1.2 booleans only: ``off:`` and ``on:`` stay words (PyYAML's YAML 1.1 reads them as false and true, and ``off: [x]`` was lost)."""


StackLoader.yaml_implicit_resolvers = {k: [(tag, rx) for tag, rx in v if tag != "tag:yaml.org,2002:bool"]
                                       for k, v in yaml.SafeLoader.yaml_implicit_resolvers.items()}
StackLoader.add_implicit_resolver("tag:yaml.org,2002:bool", re.compile(r"^(?:true|True|TRUE|false|False|FALSE)$"), list("tTfF"))


def read_stack(text: str) -> dict:
    return yaml.load(text, Loader=StackLoader) or {}


# -- the lake's days ---------------------------------------------------------------------------------------------------------------
def _trades():
    spec = importlib.util.spec_from_file_location("drishti_bench_trades", ROOT / "tools" / "bench" / "trades.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def wanted_end(stack: dict, spec: dict, today: Callable[[], datetime.date] = datetime.date.today) -> str:
    """The lake's last business day: the entry's end:, else the cluster's business today, else this machine's date (a business day)."""
    end = spec.get("end") or (stack.get("settings") or {}).get(TODAY_ENV) or os.environ.get(TODAY_ENV) or today().isoformat()
    cal = (stack.get("settings") or {}).get("DRISHTI_CALENDAR") or os.environ.get("DRISHTI_CALENDAR", "USNY")
    return _trades().business_days(str(end), 1, cal)[0]


def last_day(lake: Path) -> str | None:
    """The newest business_date partition of the lake's trade tables; None when it has none."""
    days = [m.group(1) for t in (lake / "trading").glob("*/business_date=*") if (m := PART.search(t.name))]
    return max(days) if days else None


def build_lake(stack: dict, name: str, lake: Path, tmp_root: Path, python: str, say: Callable[[str], None]) -> None:
    """<data>/stack/lakes/<name> as stack.yaml's lakes entry says, ending on wanted_end: built when missing, built again (beside, then
    swapped in) when it ends on another day."""
    spec = (stack.get("lakes") or {}).get(name)
    have = last_day(lake)
    if not spec:
        if have:
            return
        raise SystemExit(f"lake {name} is not in stack.yaml (lakes) and {lake} has no trades")
    end = wanted_end(stack, spec)
    if have == end:
        return
    target = lake if have is None else lake.with_name(f"{name}-next")
    shutil.rmtree(target, ignore_errors=True) if target != lake else None
    tmp = tmp_root / f"lake-{name}"
    tmp.mkdir(parents=True, exist_ok=True)
    n, days = str(spec.get("per-day", 1500)), str(spec.get("days", 2))
    why = "" if have is None else f" (it ended on {have}; the cluster's today is {end})"
    say(f"building lake {name}: {spec['systems']}, {n} trades a day for {days} days to {end}{why} ({lake})")
    subprocess.run([python, str(ROOT / "tools" / "bench" / "trades.py"), "--lake", str(target), "--tmp", str(tmp), "--days", days, "--end", end,
                    "--min", n, "--max", n, "--systems", spec["systems"], "--counterparties", str(spec.get("counterparties", 300))],
                   check=True, env={**os.environ, "TMPDIR": str(tmp)})
    shutil.rmtree(tmp, ignore_errors=True)
    if target != lake:
        old = lake.with_name(f"{name}-old")
        shutil.rmtree(old, ignore_errors=True)
        lake.rename(old)
        target.rename(lake)
        shutil.rmtree(old, ignore_errors=True)


# -- the connectors a lake node switches off ---------------------------------------------------------------------------------------
def root_lakes(lakes_dir: Path) -> set[str]:
    """The lakes whose root is DRISHTI_DELTA_ROOT's (the demo lake, the official results lakes): a node's synthetic lake replaces them."""
    out = set()
    for f in lakes_dir.rglob("*.yaml"):
        doc = yaml.safe_load(f.read_text(encoding="utf-8")) or {}
        if isinstance(doc, dict) and doc.get("lake") and ROOT_ENV in str(doc.get("root", "")):
            out.add(str(doc["lake"]))
    return out


def _yaml(path: Path):
    try:
        return yaml.load(path.read_text(encoding="utf-8"), Loader=StackLoader)
    except (OSError, yaml.YAMLError):
        return None


def module_homes() -> list[Path]:
    """The folder of every module the server boots (the Java resolver's answer, read through tools/resolved.py), by id."""
    import resolved
    return [m.home for _, m in sorted(resolved.module_list().items())]


def pack_manifests(packs: Iterable[str]) -> list[Path]:
    """The pack.yaml of each named pack (the Java resolver's answer, read through tools/resolved.py)."""
    import resolved
    found = (resolved.current().packs.find(n) for n in packs)
    return [p.manifest for p in found if p is not None]


def definitions(modules: Iterable[Path], packs: Iterable[str], own: Path | None, manifests: Iterable[Path] = ()) -> dict[str, dict]:
    """Every connector a node may run, by name: the modules' templates (``modules``: each module's folder), the connectors of its packs
    (pack.yaml connectors:) and its own connector files (the node's connectors folder), the later winning."""
    out: dict[str, dict] = {}
    modules = list(modules)
    for f in sorted(f for m in modules for f in m.glob("connectors/*.yaml")):
        d = _yaml(f)
        if isinstance(d, dict):
            out[str(d.get("name") or f.stem)] = d
    for f in manifests:
        d = _yaml(f)
        if isinstance(d, dict):
            out.update({str(k): v for k, v in (d.get("connectors") or {}).items() if isinstance(v, dict)})
    for f in sorted(own.glob("*.yaml")) if own and own.is_dir() else []:
        if OFF_MARK in f.read_text(encoding="utf-8"):
            continue                                       # the launcher's own switch-off file: not a definition
        d = _yaml(f)
        if isinstance(d, dict):
            out[f.stem] = d
    return out


def _held(lake: Path, table: str) -> bool:
    domain, _, name = table.partition(".")
    return (lake / domain / name / "_delta_log").is_dir()


def tables_missing(defs: dict[str, dict], lake: Path, lakes: set[str]) -> list[str]:
    """The connectors reading a DRISHTI_DELTA_ROOT lake with none of their tables in the node's lake, sorted."""
    out = []
    for name, d in defs.items():
        s = d.get("settings") or {}
        tables = [t.strip() for t in str(s.get("lake-tables") or "").split(",") if t.strip()]
        if str(s.get("lake") or "") in lakes and tables and not any(_held(lake, t) for t in tables):
            out.append(name)
        elif not tables and ROOT_ENV in str(s.get("root") or "") and s.get("domain") \
                and not any((lake / str(s["domain"])).glob("*/_delta_log")):   # the older form of a connector file: root: and domain:
            out.append(name)
    return sorted(out)


OFF_MARK = "# written by the cluster launcher: the connector is off on this node"


def write_off_files(directory: Path, off: Iterable[str]) -> None:
    """The one switch of a connector, its file's ``enabled: false`` (docs/guides/DATA_GUIDE.md, "Connector files"): one override file
    ``<connector>.yaml`` in the node's own connectors folder for each connector the node starts switched off. A file the launcher wrote on
    an earlier start for a connector no longer off is removed; a connector file of the site's own gets its ``enabled`` key set."""
    off = list(dict.fromkeys(off))
    directory.mkdir(parents=True, exist_ok=True)
    for f in directory.glob("*.yaml"):
        if f.stem not in off and OFF_MARK in f.read_text(encoding="utf-8"):
            f.unlink()
    for name in off:
        f = directory / f"{name}.yaml"
        text = f.read_text(encoding="utf-8") if f.exists() else ""
        if not text or OFF_MARK in text:
            f.write_text(OFF_MARK + "\nenabled: false\n", encoding="utf-8")
        elif re.search(r"(?m)^enabled:", text):
            f.write_text(re.sub(r"(?m)^enabled:.*$", "enabled: false", text), encoding="utf-8")
        else:
            f.write_text(text.rstrip("\n") + "\nenabled: false\n", encoding="utf-8")


def switched_off(node: dict, lake: Path | None, own: Path | None, lakes_dir: Path, modules: Iterable[Path] | None = None) -> list[str]:
    """The connectors a node starts switched off: the cluster file's off: list, and, for a node with a synthetic lake, every connector
    whose tables that lake does not hold."""
    off = list(node.get("off") or [])
    if lake is not None:
        packs = [p.strip() for p in str(node.get("packs") or "").split(",") if p.strip()]
        off += [c for c in tables_missing(definitions(module_homes(), packs, own, pack_manifests(packs)) if modules is None else definitions(modules, packs, own), lake, root_lakes(lakes_dir)) if c not in off]
    return off
