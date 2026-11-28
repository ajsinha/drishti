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

"""The lake files (``config/lakes/<lake>.yaml``, docs/guides/DATA_LAKE_GUIDE.md) for the Python tools: ``lake build``, ``lake demo``,
``build_demo_lakes.py``, ``lake check`` and ``lake show``.

There is ONE reader of the lake files, in Java (Drishti Lake's ``com.ash.lake.config.LakesCli``): this module runs it and
reads its JSON, so the rules (defaults, ``${ENV}`` references, checks, the pack-entry rule) exist once. The Java is found as the parallel
loader is: ``LAKE_CLASSPATH`` (development), else the server's exec jar (``-Dloader.main``)."""
from __future__ import annotations

import functools
import json
import os
import pathlib
import subprocess
import sys

MAIN = "com.ash.drishti.lakehost.DrishtiLakeCli"   # Drishti Lake's lake-file reader (LakesCli) with Drishti's catalog: `DrishtiLakeCli lakes ...`
ROOT = pathlib.Path(__file__).resolve().parents[2]
NAME_CHARS = set("abcdefghijklmnopqrstuvwxyz0123456789_-")
if str(ROOT / "tools" / "lake") not in sys.path:
    sys.path.insert(0, str(ROOT / "tools" / "lake"))


class LakeConfigError(RuntimeError):
    """A lake file refused (an unknown key, a lake not known, a table it has no entry for), or no Java to read it with."""


def lakes_dir() -> pathlib.Path:
    """The lake folder: LAKE_LAKES_DIR, else <Drishti folder>/config/lakes (as the server's ``lake.lakes.dir``)."""
    given = os.environ.get("LAKE_LAKES_DIR")
    if given:
        return pathlib.Path(given)
    return ROOT / "config" / "lakes"                                 # this checkout's lake files, wherever the tool is run from


def is_lake_name(ref: str | None, folder: pathlib.Path | None = None) -> bool:
    """Whether ``ref`` names a lake of the lake folder (a plain name with its file there) rather than a folder (the old form)."""
    if not ref or not set(ref) <= NAME_CHARS:
        return False
    d = folder or lakes_dir()                                     # the folder or one of its domain folders (Lakes.files)
    return (d / f"{ref}.yaml").is_file() or any(d.glob(f"*/{ref}.yaml"))


def _version_key(v: str) -> tuple:
    return tuple(int(x) if x.isdigit() else -1 for x in v.replace("-", ".").split("."))


def command(jar: str | None = None, java: str | None = None) -> list[str]:
    import writers
    if str(pathlib.Path(__file__).resolve().parents[1]) not in sys.path:
        sys.path.append(str(pathlib.Path(__file__).resolve().parents[1]))
    from javacmd import java_cmd as _java_cmd         # every java we start names its temp folder (tools/javacmd.py)
    import tempfile

    def java_cmd(java_bin, args):                     # a short reader of YAML: the command line's temp folder, never the site's data folder
        return _java_cmd(java_bin, args, tmp=os.environ.get("DRISHTI_TMP_DIR") or tempfile.gettempdir())
    java_bin = writers.find_java(java)
    if not java_bin:
        raise LakeConfigError("no java found to read the lake files (drishti.java.home in the settings file, DRISHTI_JAVA_HOME, JAVA_HOME, or java on the PATH)")
    d = str(data_dir())                               # the lake files' ${drishti.data.dir}: the data folder of this run (--data-dir, settings, env)
    folders = [f"-Ddrishti.data.dir={d}", f"-Dlake.data.dir={d}"]
    cp = os.environ.get("LAKE_CLASSPATH")
    if cp and not jar:
        return java_cmd(java_bin, [*folders, "-cp", cp, MAIN, "lakes"])
    j = writers.find_jar(jar)
    if j:
        return java_cmd(java_bin, [*folders, f"-Dloader.main={MAIN}", "-jar", j, "lakes"])
    raise LakeConfigError("cannot read the lake files: no server exec jar in <home>/lib (python tools/drishti.py build jar) or LAKE_CLASSPATH names Drishti Lake's jars (lake-config and its dependencies)")


def run(args: list[str], folder: pathlib.Path | None = None, jar: str | None = None, java: str | None = None) -> dict:
    """One LakesCli command; its JSON. A refusal raises LakeConfigError with the reader's message."""
    argv = command(jar, java) + list(args) + ["--dir", str((folder or lakes_dir()).resolve())]
    proc = subprocess.run(argv, capture_output=True, text=True)
    try:
        out = json.loads(proc.stdout or "{}")
    except json.JSONDecodeError:
        raise LakeConfigError(f"the lake reader said: {(proc.stdout or proc.stderr).strip()[:400]}")
    if proc.returncode != 0 or "error" in out:
        raise LakeConfigError(out.get("error") or proc.stderr.strip()[:400])
    return out


@functools.lru_cache(maxsize=64)
def _table(lake: str, kind: str, domain: str, table: str, folder: str) -> str:
    args = ["table", "--lake", lake, "--kind", kind]
    if domain:
        args += ["--domain", domain]
    if table:
        args += ["--table", table]
    return json.dumps(run(args, pathlib.Path(folder)))


def table(lake: str, kind: str, domain: str | None = None, table_name: str | None = None, folder: pathlib.Path | None = None) -> dict:
    """The lake file's entry for a kind (``table_name`` picks among several tables of one kind): path, location, partition-by, promote,
    vectors, flatten, id, date, latest-by, load-mode, sort-by, layout, the lake's root, storage and catalog."""
    return json.loads(_table(lake, kind, domain or "", table_name or "", str((folder or lakes_dir()).resolve())))


def dump(folder: pathlib.Path | None = None) -> dict:
    return run(["dump"], folder)


# ---- where lakes are ---------------------------------------------------------------------------------------------------------------------
# The one place the tools know the lakes' folders (DATA_LAKE_GUIDE.md section 2): every lake in its own folder, <data>/delta/<lake> (an Iceberg
# lake <data>/iceberg/<lake>), unless its lake file names a root; the demo lake's root may be named by DEMO_ROOT_ENV, as config/lakes/demo.yaml
# says. A tool that needs a lake's folder asks root(); nothing else spells data/delta.

#: the environment variable that names the demo lake's root (config/lakes/demo.yaml: ${DRISHTI_DELTA_ROOT:<data>/delta/demo})
DEMO_ROOT_ENV = "DRISHTI_DELTA_ROOT"
#: the demo lake's root as config/lakes/demo.yaml writes it (a connector or a lake file that names the demo lake's folder)
DEMO_ROOT_REF = "${" + DEMO_ROOT_ENV + ":${lake.data.dir:${drishti.data.dir}}/delta/demo}"
#: the official results' root as config/lakes/banking/var-official.yaml and ccr-official.yaml write it (the publisher's default)
OFFICIAL_ROOT_REF = "${" + DEMO_ROOT_ENV + ":${lake.data.dir:${drishti.data.dir}}/delta}"


def data_dir() -> pathlib.Path:
    """The data folder, the ONE answer of every lake tool (the Java order of javacmd.launch_roots): --data-dir (use_data_dir), else
    ``drishti.data.dir`` of the settings file, else DRISHTI_DATA_DIR, else this checkout's data/."""
    import javacmd
    return javacmd.launch_roots(data_dir=_OVERRIDE or None)["data"][0]


_OVERRIDE: str = ""


def data_named() -> bool:
    """Whether a data folder was named at all (--data-dir, the settings file or DRISHTI_DATA_DIR) rather than this checkout's default."""
    import javacmd
    return bool(_OVERRIDE or (javacmd.settings()[1].get("drishti.data.dir") or "").strip() or (os.environ.get("DRISHTI_DATA_DIR") or "").strip())


def use_data_dir(path) -> pathlib.Path | None:
    """A command's ``--data-dir``: from now on this process, the lake reader it starts (``drishti.data.dir``, ``lake.data.dir``) and every
    child (a lake build, a loader) take THIS folder as the data folder. Without one (None or empty) nothing changes."""
    global _OVERRIDE
    if not path:
        return None
    p = pathlib.Path(path).expanduser().resolve()
    _OVERRIDE = str(p)
    os.environ["DRISHTI_DATA_DIR"] = str(p)
    _table.cache_clear()
    return p


def lakes_parent(data: pathlib.Path | None = None, fmt: str = "delta") -> pathlib.Path:
    """The folder every lake's own folder is in: <data>/delta (<data>/iceberg for an Iceberg lake)."""
    return pathlib.Path(data or data_dir()) / ("iceberg" if fmt == "iceberg" else "delta")


def default_root(name: str, data: pathlib.Path | None = None, fmt: str = "delta") -> pathlib.Path:
    """A lake's folder when its lake file names no root: <data>/delta/<name> (the demo lake: DEMO_ROOT_ENV when set)."""
    if name == "demo" and os.environ.get(DEMO_ROOT_ENV):
        return pathlib.Path(os.environ[DEMO_ROOT_ENV])
    return lakes_parent(data, fmt) / name


def locate(root, domain: str, table: str) -> str:
    """Where a table is when the lake's layout puts it: ``<root>/<domain>/<table>``. The one join of the Python tools (the Java side's is
    TableCatalogue.join); a table a lake file gives its own ``uri:`` is found by ``table()['location']`` instead."""
    return "/".join(p for p in (str(root).rstrip("/"), str(domain).strip("/"), str(table).strip("/")) if p)


def locate_path(root, domain: str, table: str = "") -> pathlib.Path:
    """:func:`locate` as a Path (a domain's own folder when ``table`` is empty): the join of a lake on a local disk."""
    return pathlib.Path(locate(root, domain, table))


def root(name: str = "demo", folder: pathlib.Path | None = None) -> str:
    """Lake ``name``'s root as its lake file says, read by the one reader (a folder, s3://bucket/prefix or mem://store); a relative folder is
    this checkout's. Without Java to read the lake files, the documented default (default_root)."""
    try:
        for lake in dump(folder).get("lakes") or []:
            if lake.get("lake") == name:
                r = str(lake.get("root") or "")
                if "://" in r or not r:
                    return r
                p = pathlib.Path(r)
                return str(p if p.is_absolute() else (ROOT / p).resolve())
        raise LakeConfigError(f"no lake named {name} in {folder or lakes_dir()}")
    except LakeConfigError:
        if (folder or lakes_dir()).joinpath(f"{name}.yaml").is_file() or name == "demo":
            return str(default_root(name))
        raise


def entry(lake: str, name: str, folder: pathlib.Path | None = None) -> dict:
    """The lake file's entry of table ``name`` (``<domain>.<table>``, the symbolic name) in lake ``lake``, as the one reader describes it:
    ``location``, ``format``, ``contract``, ``how`` (configured or fallback), partition-columns, promote, vectors, id, load-mode."""
    for lk in dump(folder).get("lakes") or []:
        if lk.get("lake") == lake:
            for t in lk.get("tables") or []:
                if t.get("name") == name:
                    return t
            raise LakeConfigError(f"lake {lake} has no table {name} ({', '.join(t['name'] for t in lk.get('tables') or [])})")
    raise LakeConfigError(f"no lake named {lake} in {folder or lakes_dir()}")


def bind(lake: str, name: str, folder: pathlib.Path | None = None) -> dict:
    """A table by its symbolic name for a reader or writer that wants ``(root, domain, table)``: the entry plus the three, its location cut at
    its last two folders (as the server's connectors read it), wherever the table is and whatever its depth."""
    e = dict(entry(lake, name, folder))
    loc = str(e["location"]).rstrip("/")
    head, _, table = loc.rpartition("/")
    root, _, domain = head.rpartition("/")
    if not (root and domain and table):
        raise LakeConfigError(f"table {name}: location {loc} needs a folder and a parent folder (s3://bucket/area/table)")
    e.update({"root": root, "domain": domain, "table": table})
    return e


def demo_root() -> pathlib.Path:
    """The demo lake's folder (config/lakes/demo.yaml): DEMO_ROOT_ENV, else <data>/delta/demo."""
    r = root("demo")
    return pathlib.Path(r)


def disagree(what: str, given, lakes, entry: dict) -> None:
    """A command-line value the lake file also decides: the same, or the run is refused naming both."""
    if not given:
        return
    differs = list(given) != list(lakes or []) if isinstance(given, (list, tuple)) else given != lakes
    if differs:
        raise LakeConfigError(f"{what} {given} differs from {lakes}, the {what} of table {entry['name']} in lake {entry['lake']} "
                              f"(config/lakes/{entry['lake']}.yaml): the lake file decides; drop {what} or correct the lake file")


# ------------------------------------------------------------------------------------------------ drishti.py lake show|check

def _connectors_dir(a) -> pathlib.Path:
    return pathlib.Path(getattr(a, "connectors_dir", None) or os.environ.get("DRISHTI_CONNECTORS_DIR") or ROOT / "config" / "connectors")


def cmd_show(a) -> int:
    """Every lake and its tables, as the server reads them (``--json`` for the whole description)."""
    try:
        out = dump(pathlib.Path(a.dir) if a.dir else None)
    except LakeConfigError as e:
        print(f"lake show: {e}", file=sys.stderr)
        return 1
    if a.json:
        print(json.dumps(out, indent=2))
        return 0
    print(f"lakes in {out['dir']}: {len(out['lakes'])}")
    for lake in out["lakes"]:
        print(f"  {lake['lake']}: {lake['format']} at {lake['root']}" + (" (in memory)" if lake["in-memory"] else "")
              + f", {len(lake['tables'])} tables")
        for t in lake["tables"]:
            part = ", ".join(t["partition-columns"])
            print(f"    {t['name']:<40} {t['path']:<40} partitioned by {part}; {len(t['promote'])} promoted"
                  + (f", vectors {', '.join(t['vectors'])}" if t["vectors"] else "") + (", append" if t["load-mode"] == "append" else ""))
    return 0


def cmd_check(a) -> int:
    """docs/architecture/DATA_ARCHITECTURE.md section 6: every connector names a known lake; every table on a local disk matches its lake file. Exit 1 on an error."""
    args = ["check", "--connectors", str(_connectors_dir(a).resolve())]
    try:
        out = run(args, pathlib.Path(a.dir) if a.dir else None)
    except LakeConfigError as e:
        print(f"lake check: {e}", file=sys.stderr)
        return 1
    if a.json:
        print(json.dumps(out, indent=2))
    else:
        for f in out["findings"]:
            where = f["lake"] + ("/" + f["table"] if f["table"] else "")
            print(f"  {f['level']:<5} {where + ': ' if where else ''}{f['message']}")
        print(f"lake check: {len(out['lakes'])} lakes, {out['errors']} errors")
    return 1 if out["errors"] else 0


def register(sub, add) -> None:
    """``lake show`` and ``lake check`` (cli.py adds them to the ``lake`` group)."""
    s = add(sub, "show", lambda a, extra: cmd_show(a), "lake show: every lake of config/lakes/ and its tables, as the server reads them", [],
            "example:\n  drishti.py lake show\nGuide: docs/guides/DATA_LAKE_GUIDE.md")
    s.add_argument("--dir", help="the lake folder (default LAKE_LAKES_DIR, else config/lakes)")
    s.add_argument("--json", action="store_true")
    c = add(sub, "check", lambda a, extra: cmd_check(a), "lake check: connectors name known lakes; each lake file matches its tables on disk "
            "(format, partition columns, promoted columns)", [], "example:\n  drishti.py lake check\nexit: 0 ok, 1 an error found. "
            "Guide: docs/guides/DATA_LAKE_GUIDE.md")
    c.add_argument("--dir", help="the lake folder (default LAKE_LAKES_DIR, else config/lakes)")
    c.add_argument("--connectors-dir", help="the connector files (default DRISHTI_CONNECTORS_DIR, else config/connectors)")
    c.add_argument("--json", action="store_true")
    e = add(sub, "entries", lambda a, extra: _entries(a), "lake entries: write every table of the lake files as an explicit entry (uri, format, contract) "
            "from where the lake's layout puts it today; a bank then edits each uri to the real location", [],
            "example:\n  drishti.py lake entries --write\nWithout --write it lists what it would add. Guide: docs/admin/TABLE_CATALOGUE.md")
    e.add_argument("--dir", help="the lake folder (default LAKE_LAKES_DIR, else config/lakes)")
    e.add_argument("--write", action="store_true")


def _entries(a) -> int:
    import catalogue_entries
    argv = ["--lakes-dir", str(a.dir or lakes_dir())] + (["--write"] if a.write else [])
    return catalogue_entries.main(argv)
