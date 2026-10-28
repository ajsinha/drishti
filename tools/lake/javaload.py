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

"""``lake build --engine java``: the whole build done by the parallel lake loader (drishti-lakeload, ``com.ash.drishti.lakeload.LakeLoad``
in the server's exec jar) instead of Python staging plus a Java writer: JSON Lines read, parsed, sorted and written as Parquet by many
threads, one commit per business date, Delta or Iceberg, memory bounded by ``memory.budget-mb`` (docs/architecture/PARALLEL_LOADER.md).

The run's options become the loader's configuration (a JSON file in the run's staging folder; docs/guides/TOOLS.md section 4.13 lists
its keys); ``--loader-set KEY=VALUE`` adds any key (``memory.budget-mb=4096``, ``memory.spill-dir=/mnt/ebs/spill``,
``parallel.writers=16``, ``target.layout.sort-by=[LEGAL_ENTITY,DESK,id]``). This build holds the lake's lock and writes the run log and
summary as usual; the loader's summary (the same keys, plus throughput and memory) is merged into it.

The Python path is the default: ``--engine auto`` never picks the loader on its own. Only with ``--java-above-mb N`` does it pick the
loader for a large load: every input JSON Lines, at least N MB of them, mode overwrite-dates, and a jar (or DRISHTI_LAKE_CLASSPATH) that
has the loader; anything else goes the way it always went. Java is started
only through ``tools/javacmd.py``'s ``java_cmd``."""
from __future__ import annotations

import gzip
import json
import os
import pathlib
import subprocess
import sys
import zipfile

LOADER_MAIN = "com.ash.drishti.lakeload.LakeLoad"
LOADER_CLASS = "com/ash/drishti/lakeload/LakeLoad.class"
SECRET_KEYS = {"access-key", "secret-key", "s3.access-key", "s3.secret-key", "session-token", "s3.session-token", "credential", "token"}
ROOT = pathlib.Path(__file__).resolve().parents[2]
if str(ROOT / "tools") not in sys.path:
    sys.path.append(str(ROOT / "tools"))
from javacmd import java_cmd  # noqa: E402


class LoaderError(RuntimeError):
    pass


def jar_has_loader(jar: str) -> bool:
    try:
        with zipfile.ZipFile(jar) as z:
            names = set(z.namelist())
            if LOADER_CLASS in names:
                return True
            return any(n.startswith("BOOT-INF/lib/drishti-lakeload-") and n.endswith(".jar") for n in names)
    except (OSError, zipfile.BadZipFile):
        return False


def command(jar: str | None, java: str | None) -> tuple[str, list[str]] | None:
    """(java, arguments before the loader's own): DRISHTI_LAKE_CLASSPATH (development: ``-cp CP LakeLoad``), else the server's exec jar
    (``-Dloader.main=... -jar J``). The heap is DRISHTI_LAKE_LOADER_HEAP (default 4g); the loader's budget is half of it unless set."""
    import writers
    java_bin = writers.find_java(java)
    if not java_bin:
        return None
    heap = "-Xmx" + os.environ.get("DRISHTI_LAKE_LOADER_HEAP", "4g")
    cp = os.environ.get("DRISHTI_LAKE_CLASSPATH")
    if cp and not jar:
        return java_bin, [heap, "-cp", cp, LOADER_MAIN]
    j = writers.find_jar(jar)
    if j and jar_has_loader(j):
        return java_bin, [heap, f"-Dloader.main={LOADER_MAIN}", "-jar", j]
    return None


def is_jsonl(path: pathlib.Path) -> bool:
    """Whether a file holds JSON Lines (its first character that is not blank is ``{``), read through gzip when it is gzipped."""
    try:
        with open(path, "rb") as raw:
            head = raw.read(2)
            raw.seek(0)
            fh = gzip.open(raw) if head == b"\x1f\x8b" else raw
            text = fh.read(4096).lstrip(b"\xef\xbb\xbf \t\r\n")
            return text[:1] == b"{"
    except OSError:
        return False


def wanted(a, files: list[pathlib.Path], jar: str | None, java: str | None) -> tuple[bool, str]:
    """(use the loader, why) for ``--engine auto``."""
    above = getattr(a, "java_above_mb", None)
    if above is None:                         # the default: the Python path; the loader only when asked (--engine java or --java-above-mb)
        return False, "the Python path is the default (--engine java, or --java-above-mb N, for the parallel loader)"
    threshold = int(above) * 1048576
    size = sum(f.stat().st_size for f in files)
    if size < threshold:
        return False, f"{size / 1048576:.0f} MB of input is under --java-above-mb"
    if a.mode != "overwrite-dates":
        return False, f"--mode {a.mode} is the Python path's"
    if not all(is_jsonl(f) for f in files):
        return False, "an input is not JSON Lines"
    if not command(jar, java):
        return False, "no jar with the parallel loader"
    return True, f"{size / 1048576:,.0f} MB of JSON Lines (at least --java-above-mb): the parallel loader"


def config(a, p: dict, as_of, lake: str, iceberg: bool, workdir: pathlib.Path, env: dict) -> dict:
    """The loader's configuration for this build. A secret among --iceberg-set (a REST catalog's token or credential) goes to the
    loader through its environment, never into the file."""
    import writers
    partition = list(p["partition_by"])
    promote = list(p["promote"])
    if iceberg:                                    # an Iceberg table of the layout keeps partition fields as columns, as icebergwrite does
        promote = partition + [x for x in promote if x not in partition]
        partition = []
    src = {"files": [str(s) for s in a.src], "recursive": bool(a.recursive), "id": p["key"], "promote": promote,
           "vectors": list(p.get("vectors") or []), "flatten": dict(p.get("flatten") or {}), "partition-by": partition}
    if getattr(a, "pack", None):                   # the pack's layout.<kind>.sort-by too (the options above win over the rest of it)
        src["pack"] = str(pathlib.Path(a.pack).resolve())
        if p.get("connector"):
            src["connector"] = p["connector"]
    if as_of:
        src["as-of"] = as_of.isoformat()
    else:
        src["date"] = p["date"]
    target = {"format": "iceberg" if iceberg else "delta", "lake": lake, "domain": p["domain"], "kind": p["kind"]}
    if p.get("table"):
        target["table"] = p["table"]
    if not iceberg:
        props = writers.retention_properties(lake.rstrip("/") + "/" + p["domain"] + "/" + (p.get("table") or p["kind"]))
        if props:
            target["properties"] = props
    ice = {}
    for n, kv in enumerate(getattr(a, "iceberg_set", None) or []):
        if "=" not in kv:
            raise LoaderError(f"--iceberg-set {kv}: give KEY=VALUE")
        k, v = kv.split("=", 1)
        if k.strip() in SECRET_KEYS:
            name = f"DRISHTI_LOADER_SECRET_{n}"
            env[name] = v
            v = "${" + name + "}"
        ice[k.strip()] = v
    if ice:
        target["iceberg"] = ice
    return {"source": src, "target": target, "memory": {"spill-dir": str(workdir / "spill")},
            "rejects": {"file": str(workdir / "rejects.jsonl")}}


def run(a, p: dict, as_of, lake: str, iceberg: bool, workdir: pathlib.Path, progress_file: str | None = None) -> dict:
    """Runs the loader for this build (the lock is the caller's); returns its summary. Raises LoaderError with the loader's own words
    when it fails (nothing of a date that did not commit changed)."""
    base = command(getattr(a, "jar", None), getattr(a, "java", None))
    if not base:
        raise LoaderError("--engine java needs the server's exec jar with the parallel loader (--jar, DRISHTI_JAR or a built "
                          "drishti-server/target/drishti-server-*-exec.jar; or DRISHTI_LAKE_CLASSPATH) and Java 21")
    workdir.mkdir(parents=True, exist_ok=True)
    env = dict(os.environ)
    cfg = config(a, p, as_of, lake, iceberg, workdir, env)
    cfg_file = workdir / "loader.json"
    cfg_file.write_text(json.dumps(cfg, indent=1), encoding="utf-8")
    summary_file = workdir / "loader-summary.json"
    java_bin, args = base
    cmd = java_cmd(java_bin, [*args, "--config", str(cfg_file), "--summary", str(summary_file), "--no-lock", "--no-run-log", "-q"],
                   tmp=workdir / "tmp")
    for kv in getattr(a, "loader_set", None) or []:
        cmd += ["--set", kv]
    if progress_file:
        cmd += ["--progress", progress_file]
    proc = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace", env=env)
    said = [s for s in proc.stdout.splitlines() if s.strip() and not s.startswith("SLF4J")]
    if not summary_file.is_file():
        raise LoaderError("the parallel loader did not finish: " + (said[-1] if said else f"exit {proc.returncode}"))
    s = json.loads(summary_file.read_text(encoding="utf-8"))
    s["exitCode"] = proc.returncode
    if proc.returncode in (1, 3) or s.get("status") == "failed":
        raise LoaderError(s.get("error") or (said[-1] if said else f"the parallel loader failed (exit {proc.returncode})"))
    return s


MERGED = ("rows", "partitions", "documents", "accepted", "rejected", "rejects", "duplicates", "files", "sampleId", "sampleDate",
          "commit", "columns", "throughput", "memory", "sortBy", "manifestFile", "peakMemoryMb")


def merge(summary: dict, loaded: dict) -> None:
    """The loader's counts into lake build's summary (its own keys: the same words)."""
    for k in MERGED:
        if k in loaded:
            summary[k] = loaded[k]
    t = loaded.get("timings") or {}
    summary.setdefault("timings", {}).update({"readSeconds": t.get("readSeconds"), "writeSeconds": t.get("writeSeconds")})
