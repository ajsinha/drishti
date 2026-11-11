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
``build_demo_lakes.py``, ``lake check``, ``lake show`` and ``lake migrate-config``.

There is ONE reader of the lake files, in Java (drishti-lakeconfig, ``com.ash.drishti.lakeconfig.LakesCli``): this module runs it and
reads its JSON, so the rules (defaults, ``${ENV}`` references, checks, the pack-entry rule) exist once. The Java is found as the parallel
loader is: ``DRISHTI_LAKE_CLASSPATH`` (development), else the server's exec jar (``-Dloader.main``), else this checkout's
``drishti-lakeconfig/target/classes`` with Jackson from the local Maven repository (after ``mvn compile``)."""
from __future__ import annotations

import functools
import json
import os
import pathlib
import subprocess
import sys

MAIN = "com.ash.drishti.lakeconfig.LakesCli"
ROOT = pathlib.Path(__file__).resolve().parents[2]
NAME_CHARS = set("abcdefghijklmnopqrstuvwxyz0123456789_-")
if str(ROOT / "tools" / "lake") not in sys.path:
    sys.path.insert(0, str(ROOT / "tools" / "lake"))


class LakeConfigError(RuntimeError):
    """A lake file refused (an unknown key, a lake not known, a table it has no entry for), or no Java to read it with."""


def lakes_dir() -> pathlib.Path:
    """The lake folder: DRISHTI_LAKES_DIR, else ./config/lakes (as the server's ``drishti.lakes.dir``)."""
    given = os.environ.get("DRISHTI_LAKES_DIR")
    if given:
        return pathlib.Path(given)
    here = pathlib.Path("./config/lakes")
    return here if here.is_dir() else ROOT / "config" / "lakes"      # a tool run from elsewhere: this checkout's lake files


def is_lake_name(ref: str | None, folder: pathlib.Path | None = None) -> bool:
    """Whether ``ref`` names a lake of the lake folder (a plain name with its file there) rather than a folder (the old form)."""
    if not ref or not set(ref) <= NAME_CHARS:
        return False
    d = folder or lakes_dir()                                     # the folder or one of its domain folders (Lakes.files)
    return (d / f"{ref}.yaml").is_file() or any(d.glob(f"*/{ref}.yaml"))


def _version_key(v: str) -> tuple:
    return tuple(int(x) if x.isdigit() else -1 for x in v.replace("-", ".").split("."))


def _jars(m2: pathlib.Path, group: str, art: str) -> dict[str, pathlib.Path]:
    """The versions of one artifact in the local Maven repository: version -> its jar."""
    out = {}
    for d in (m2 / group / art).glob("*"):
        j = d / f"{art}-{d.name}.jar"
        if j.is_file():
            out[d.name] = j
    return out


def _maven_classpath() -> str | None:
    """The compiled drishti-lakeconfig with Jackson from the local Maven repository: databind, core and the YAML format of ONE version
    (the newest present for all three; the annotations of that version or its minor line), never the newest of each (a mixed Jackson
    fails with NoSuchMethodError before a lake file is read)."""
    classes = ROOT / "drishti-lakeconfig" / "target" / "classes"
    if not (classes / "com" / "ash" / "drishti" / "lakeconfig" / "LakesCli.class").is_file():
        return None
    m2 = pathlib.Path(os.environ.get("MAVEN_REPO") or pathlib.Path.home() / ".m2" / "repository")
    core = "com/fasterxml/jackson/core"
    databind, jcore = _jars(m2, core, "jackson-databind"), _jars(m2, core, "jackson-core")
    yaml = _jars(m2, "com/fasterxml/jackson/dataformat", "jackson-dataformat-yaml")
    common = sorted(set(databind) & set(jcore) & set(yaml), key=_version_key)
    if not common:
        return None
    v = common[-1]
    annotations = _jars(m2, core, "jackson-annotations")
    minor = ".".join(v.split(".")[:2])
    ann = annotations.get(v) or annotations.get(minor) or annotations.get(minor + ".0")
    snake = _jars(m2, "org/yaml", "snakeyaml")
    if not ann or not snake:
        return None
    return os.pathsep.join([str(classes), str(databind[v]), str(jcore[v]), str(ann), str(yaml[v]),
                            str(snake[max(snake, key=_version_key)])])


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
        raise LakeConfigError("no java found to read the lake files (JAVA_HOME, or java on the PATH)")
    cp = os.environ.get("DRISHTI_LAKE_CLASSPATH")
    if cp and not jar:
        return java_cmd(java_bin, ["-cp", cp, MAIN])
    j = writers.find_jar(jar)
    if j:
        return java_cmd(java_bin, [f"-Dloader.main={MAIN}", "-jar", j])
    cp = _maven_classpath()
    if cp:
        return java_cmd(java_bin, ["-cp", cp, MAIN])
    raise LakeConfigError("cannot read the lake files: no server exec jar (DRISHTI_JAR, drishti-server/target) and no compiled "
                          "drishti-lakeconfig (mvn -pl drishti-lakeconfig compile); DRISHTI_LAKE_CLASSPATH names one")


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
DEMO_ROOT_REF = "${" + DEMO_ROOT_ENV + ":${drishti.data.dir:./data}/delta/demo}"
#: the official results' root as config/lakes/banking/var-official.yaml and ccr-official.yaml write it (the publisher's default)
OFFICIAL_ROOT_REF = "${" + DEMO_ROOT_ENV + ":${drishti.data.dir:./data}/delta}"


def data_dir() -> pathlib.Path:
    """The data folder: DRISHTI_DATA_DIR, else this checkout's data/."""
    return pathlib.Path(os.environ.get("DRISHTI_DATA_DIR") or (ROOT / "data"))


def lakes_parent(data: pathlib.Path | None = None, fmt: str = "delta") -> pathlib.Path:
    """The folder every lake's own folder is in: <data>/delta (<data>/iceberg for an Iceberg lake)."""
    return pathlib.Path(data or data_dir()) / ("iceberg" if fmt == "iceberg" else "delta")


def default_root(name: str, data: pathlib.Path | None = None, fmt: str = "delta") -> pathlib.Path:
    """A lake's folder when its lake file names no root: <data>/delta/<name> (the demo lake: DEMO_ROOT_ENV when set)."""
    if name == "demo" and os.environ.get(DEMO_ROOT_ENV):
        return pathlib.Path(os.environ[DEMO_ROOT_ENV])
    return lakes_parent(data, fmt) / name


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


# ------------------------------------------------------------------------------------------------ drishti.py lake show|check|migrate-config

def _connectors_dir(a) -> pathlib.Path:
    return pathlib.Path(getattr(a, "connectors_dir", None) or os.environ.get("DRISHTI_CONNECTORS_DIR") or "./config/connectors")


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
    """HOW_IT_FITS.md section D6: every connector names a known lake; every table on a local disk matches its lake file. Exit 1 on an error."""
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


def cmd_migrate(a) -> int:
    """L4: lake files written from the connector files that still describe their lake; the connectors rewritten to ``lake: <name>``.
    A dry run (the files printed) unless --write. Nothing under the data folder changes."""
    args = ["migrate-config", "--connectors", str(_connectors_dir(a).resolve())] + (["--write"] if a.write else [])
    try:
        out = run(args, pathlib.Path(a.dir) if a.dir else None)
    except LakeConfigError as e:
        print(f"lake migrate-config: {e}", file=sys.stderr)
        return 1
    if a.json:
        print(json.dumps(out, indent=2))
        return 0
    for n in out["notes"]:
        print("  " + n)
    if not out["written"]:
        for path, text in (out.get("texts") or {}).items():
            print(f"\n--- {path} (would be written)\n{text}")
        print("\nlake migrate-config: a dry run; nothing written (add --write)")
    else:
        print(f"lake migrate-config: wrote {len(out['lake-files'])} lake files and rewrote {len(out['connector-files'])} connector files; "
              "restart the server (or let it reload the connectors) and run drishti.py lake check")
    return 0


def register(sub, add) -> None:
    """``lake show``, ``lake check`` and ``lake migrate-config`` (cli.py adds them to the ``lake`` group)."""
    s = add(sub, "show", lambda a, extra: cmd_show(a), "lake show: every lake of config/lakes/ and its tables, as the server reads them", [],
            "example:\n  drishti.py lake show\nGuide: docs/guides/DATA_LAKE_GUIDE.md")
    s.add_argument("--dir", help="the lake folder (default DRISHTI_LAKES_DIR, else config/lakes)")
    s.add_argument("--json", action="store_true")
    c = add(sub, "check", lambda a, extra: cmd_check(a), "lake check: connectors name known lakes; each lake file matches its tables on disk "
            "(format, partition columns, promoted columns)", [], "example:\n  drishti.py lake check\nexit: 0 ok, 1 an error found. "
            "Guide: docs/guides/DATA_LAKE_GUIDE.md")
    c.add_argument("--dir", help="the lake folder (default DRISHTI_LAKES_DIR, else config/lakes)")
    c.add_argument("--connectors-dir", help="the connector files (default DRISHTI_CONNECTORS_DIR, else config/connectors)")
    c.add_argument("--json", action="store_true")
    m = add(sub, "migrate-config", lambda a, extra: cmd_migrate(a), "lake migrate-config: write lake files from connector files that "
            "describe their lake (root, domain, table.<kind>, layout.<kind>.*) and rewrite those connectors to lake: <name>", [],
            "examples:\n  drishti.py lake migrate-config                 (a dry run: prints the files)\n"
            "  drishti.py lake migrate-config --write\nNothing in the data folder changes. Guide: docs/guides/DATA_LAKE_GUIDE.md section 12")
    m.add_argument("--dir", help="the lake folder written (default DRISHTI_LAKES_DIR, else config/lakes)")
    m.add_argument("--connectors-dir", help="the connector files (default DRISHTI_CONNECTORS_DIR, else config/connectors)")
    m.add_argument("--write", action="store_true", help="write the files (default: a dry run)")
    m.add_argument("--json", action="store_true")
