#!/usr/bin/env python3
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

"""`drishti.py pack make`: JSON Lines in, ONE folder out that you take and deploy.

    drishti.py pack make data/jsonl --kind trade --match productType --name my-bank

Writes <out>/ with README.txt, pack/<name>/ (Sutras, samples, tests, about), data/delta (or data/files), bundle/, config/, run-server.sh,
run-server.ps1 and MANIFEST.json. It reuses packgen/pack_from_jsonl.py (which uses sutragen.py and ingest_jsonl.py) and packbundle.py
through `drishti.py`; this module only chooses the key and date, lays the folder out and writes the instructions.
"""
from __future__ import annotations

import argparse
import datetime
import hashlib
import json
import pathlib
import re
import shutil
import stat

ISO_DATE = re.compile(r"^\d{4}-\d{2}-\d{2}(?:$|[T ])")
NAME_RE = re.compile(r"[a-z0-9][a-z0-9-]*")
DATE_PREFERRED = ("businessdate", "business_date", "asof", "asofdate", "as_of", "as_of_date", "reportingdate", "date", "valuationdate")
DATE_PRESENCE = 0.95


class MakeError(Exception):
    def __init__(self, message: str, code: int = 2):
        super().__init__(message)
        self.code = code


# ----------------------------------------------------------------------------------------------- key and date detection

def _norm(name: str) -> str:
    return re.sub(r"[^a-z0-9_]", "", name.lower())


def _plain(x) -> bool:
    return isinstance(x, (str, int)) and not isinstance(x, bool) and x != ""


def detect_key(docs: list[dict], kind: str) -> tuple[str, str]:
    """The field present and unique in every document, preferring <kind>Id/id; (field, why). Raises MakeError when none exists."""
    if not docs:
        raise MakeError("no documents to detect a key from")
    unique = []
    for f in docs[0]:
        seen = set()
        for d in docs:
            x = d.get(f)
            if not _plain(x) or x in seen:
                break
            seen.add(x)
        else:
            unique.append(f)
    if not unique:
        raise MakeError(f"no field is present and unique in all {len(docs)} {kind} documents, so no key can be chosen: pass --key FIELD "
                        f"(a field of every document whose values differ per document)")
    k = _norm(kind)
    tail = f"present and unique in all {len(docs)} documents"
    for want, why in ((f"{k}id", "named <kind>Id"), (f"{k}_id", "named <kind>_id"), ("id", "named id")):
        for f in unique:
            if _norm(f) == want:
                return f, f"{why}, {tail}"
    for f in unique:
        if _norm(f).endswith(("id", "key")):
            return f, f"an id-like name, {tail}"
    return unique[0], f"the first field {tail}"


def detect_date(docs: list[dict]) -> tuple[str | None, str]:
    """A date-valued field present in at least 95% of the documents (businessDate/asOf/date first); (field or None, why)."""
    n = len(docs)
    cands = {}
    for f in dict.fromkeys(f for d in docs for f in d):
        vals = [d[f] for d in docs if isinstance(d.get(f), str)]
        if len(vals) >= DATE_PRESENCE * n and all(ISO_DATE.match(v) for v in vals):
            cands[f] = len({v[:10] for v in vals})
    if not cands:
        return None, f"no date-valued field (YYYY-MM-DD) is present in {int(DATE_PRESENCE * 100)}% of the documents: no dated store is written"
    for want in DATE_PREFERRED:
        for f in cands:
            if _norm(f) == want:
                return f, f"date-valued, named like a business date, {cands[f]} distinct date(s)"
    f = min(cands, key=lambda c: (cands[c], list(cands).index(c)))
    return f, f"the date-valued field with the fewest distinct dates ({cands[f]}); pass --date to choose another"


# ----------------------------------------------------------------------------------------------- the folder

def sha256(p: pathlib.Path) -> str:
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


def tree_files(root: pathlib.Path) -> list[pathlib.Path]:
    return sorted(p for p in root.rglob("*") if p.is_file())


def write_scripts(out: pathlib.Path, name: str, store: str) -> None:
    root_var = "DRISHTI_DELTA_ROOT" if store == "delta" else "DRISHTI_FILES_ROOT"
    sub = "delta" if store == "delta" else "files"
    sh = f"""#!/usr/bin/env bash
# Starts the Drishti server on this folder alone: the pack from ./pack, the data from ./data/{sub}.
# Needs JDK 21+ and the server jar:  DRISHTI_JAR=/path/to/drishti-server-X.Y.Z-exec.jar ./run-server.sh
# Optional: DRISHTI_PORT (default 18480), {root_var} (default ./data/{sub}), JAVA_HOME.
set -euo pipefail
HERE="$(cd "$(dirname "${{BASH_SOURCE[0]}}")" && pwd)"
JAVA="${{JAVA_HOME:+$JAVA_HOME/bin/}}java"
JAR="${{DRISHTI_JAR:-}}"
if [ -z "$JAR" ]; then JAR="$(ls -t "$HERE"/drishti-server-*-exec.jar 2>/dev/null | head -1 || true)"; fi
if [ -z "$JAR" ] || [ ! -f "$JAR" ]; then echo "set DRISHTI_JAR to the drishti-server-*-exec.jar (see README.txt)" >&2; exit 2; fi
export DRISHTI_PACKS="{name}"
export DRISHTI_PACKS_DIR="$HERE/pack"
export {root_var}="${{{root_var}:-$HERE/data/{sub}}}"
export SPRING_CONFIG_ADDITIONAL_LOCATION="optional:file:$HERE/config/drishti-site.yaml"
cd "$HERE"
exec "$JAVA" -jar "$JAR" "$@"
"""
    ps = f"""# Starts the Drishti server on this folder alone: the pack from .\\pack, the data from .\\data\\{sub}.
# Needs JDK 21+ and the server jar:  $env:DRISHTI_JAR = 'C:\\path\\drishti-server-X.Y.Z-exec.jar'; .\\run-server.ps1
# Optional: DRISHTI_PORT (default 18480), {root_var}, JAVA_HOME.
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$java = if ($env:JAVA_HOME) {{ Join-Path $env:JAVA_HOME 'bin\\java.exe' }} else {{ 'java' }}
$jar = $env:DRISHTI_JAR
if (-not $jar) {{ $jar = (Get-ChildItem -Path $here -Filter 'drishti-server-*-exec.jar' -ErrorAction SilentlyContinue | Sort-Object LastWriteTime | Select-Object -Last 1).FullName }}
if (-not $jar -or -not (Test-Path $jar)) {{ Write-Error 'set DRISHTI_JAR to the drishti-server-*-exec.jar (see README.txt)'; exit 2 }}
$env:DRISHTI_PACKS = '{name}'
$env:DRISHTI_PACKS_DIR = Join-Path $here 'pack'
if (-not $env:{root_var}) {{ $env:{root_var} = Join-Path $here 'data\\{sub}' }}
$env:SPRING_CONFIG_ADDITIONAL_LOCATION = 'optional:file:' + ((Join-Path $here 'config\\drishti-site.yaml') -replace '\\\\', '/')
Set-Location $here
& $java -jar $jar @args
"""
    sp = out / "run-server.sh"
    sp.write_text(sh, encoding="utf-8", newline="\n")
    sp.chmod(sp.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)
    (out / "run-server.ps1").write_text(ps, encoding="utf-8")


def write_site_yaml(out: pathlib.Path, name: str, store: str) -> None:
    root_var = "DRISHTI_DELTA_ROOT" if store == "delta" else "DRISHTI_FILES_ROOT"
    sub = "delta" if store == "delta" else "files"
    (out / "config").mkdir(exist_ok=True)
    (out / "config" / "drishti-site.yaml").write_text(f"""# A server configuration overlay for this folder (README.txt, step 3). run-server.sh passes it as
# SPRING_CONFIG_ADDITIONAL_LOCATION; on your own server copy these lines into application.local.yaml.
# Each ${{VAR:default}} is read from the environment first; the default after the colon is a PLACEHOLDER path
# (/PATH/TO/...): replace it with where you put the folder, or set the variable.
drishti:
  packs:
    enabled: ${{DRISHTI_PACKS:{name}}}                 # the pack to switch on
    dir: ${{DRISHTI_PACKS_DIR:/PATH/TO/this-folder/pack}}   # the folder that CONTAINS the pack folder "{name}"
# The data root is read by the pack's own connector as ${{{root_var}:./data/{sub}}}: set {root_var}
# to /PATH/TO/this-folder/data/{sub} (or to your lake elsewhere, e.g. s3a://bucket/lake) before starting the server.
""", encoding="utf-8")


# ----------------------------------------------------------------------------------------------- the README

def readme_text(v: dict) -> str:
    n, kind, store = v["name"], v["kind"], v["store"]
    root_var = "DRISHTI_DELTA_ROOT" if store == "delta" else "DRISHTI_FILES_ROOT"
    sub = "delta" if store == "delta" else "files"
    ids = v["sample_ids"]
    d1, d2 = v["dates"][0], v["dates"][-1]
    dated = bool(v["date_field"])
    pkgs = "pyyaml" + (" --with deltalake --with pyarrow" if store == "delta" else "")
    L = []
    w = L.append
    w(f"{v['title']}  ({n} {v['version']})")
    w("=" * 72)
    w(f"Made {v['made']} by `drishti.py pack make` from {v['input']}.")
    w("This folder is everything you need to deploy: take it as it is.\n")
    w("1. WHAT IS INSIDE")
    w("-" * 72)
    w(f"  pack/{n}/".ljust(18) + f"the pack: pack.yaml, sutras/ ({v['sutras']} Sutra group(s) for kind '{kind}'), tests/, samples/, config/about.yaml, README.md")
    if dated:
        w(f"  data/{sub}/".ljust(18) + f"the {v['docs']} documents, partitioned by {v['date_field']} ({len(v['dates'])} date(s): {', '.join(v['dates'][:5])}{' ...' if len(v['dates']) > 5 else ''})")
    else:
        w("  (no data/)       no date field was found or given, so no dated store was written; the pack serves its samples only")
    w(f"  bundle/          {n}-{v['version']}.tar.gz + .sha256: the pack alone, versioned and checksummed, for servers that install by copying")
    w("  config/          drishti-site.yaml: a ready server configuration overlay")
    w("  run-server.sh    run-server.ps1: start a server on this folder only (JDK 21+ and the server jar needed)")
    w("  MANIFEST.json    what was generated: counts, the chosen key and date, tool versions, checksums\n")
    w("What was chosen for you")
    w(f"  kind          {kind}")
    w(f"  key           {v['key']}   ({v['key_why']})")
    w(f"  date          {v['date_field'] or '(none)'}   ({v['date_why']})")
    w(f"  match         {', '.join(v['match'])}  ->  one Sutra per combination ({v['sutras']} groups" + (", the last a fallback for everything else)" if v["fallback"] else ")"))
    w(f"  documents     {v['docs']}   ids such as {', '.join(ids)}\n")
    w("2. PREREQUISITES")
    w("-" * 72)
    w(f"  * A Drishti server, version {v['server_version']} or newer, as the exec jar (drishti-server-X.Y.Z-exec.jar), and JDK 21 or newer.")
    w("  * To re-check or re-ingest (steps 4 to 6): Python 3.10+ and the Drishti repository's tools/ folder:")
    w(f"        uv run --with {pkgs} python tools/drishti.py ...\n")
    w("3. DEPLOY")
    w("-" * 72)
    w("  Quickest, on this machine (nothing is copied):")
    w("      DRISHTI_JAR=/path/to/drishti-server-X.Y.Z-exec.jar DRISHTI_PORT=18480 ./run-server.sh        (Windows: .\\run-server.ps1)")
    w("  On an existing server:")
    w(f"    a. The pack. Copy pack/{n}/ into the server's packs folder (the one DRISHTI_PACKS_DIR / drishti.packs.dir names), e.g.")
    w(f"           cp -r pack/{n} /opt/drishti/packs/")
    w("       or into drishti.packs.installed-dir (default ./data/packs/installed), which is looked in first. Or, with the repository's tools,")
    w(f"           python3 tools/drishti.py pack deploy bundle/{n}-{v['version']}.tar.gz --to /opt/drishti/packs   (verifies, copies atomically, keeps the old version)")
    if dated:
        w(f"    b. The data. Copy data/{sub}/ to the server's lake root, or keep it where it is and point the server at it:")
        w(f"           export {root_var}=/srv/drishti/{sub}      (the folder that CONTAINS the '{n}' folder)")
        w(f"       The layout under it is explained in step 5. If the lake already holds other packs' data, copy only data/{sub}/{n}/.")
    w("    c. Switch the pack on, any one of:")
    w(f"           DRISHTI_PACKS={n}[,other packs]  and restart the server;")
    w(f"           Admin -> Packs -> Load '{n}' (or: python3 tools/drishti.py server packs load {n} --server http://HOST:18480 --token ...);")
    w("           or copy the lines of config/drishti-site.yaml into the server's application.local.yaml.\n")
    w("4. VERIFY")
    w("-" * 72)
    w("  Offline, before or after copying (from the Drishti repository):")
    w(f"      python3 tools/drishti.py pack check pack/{n}                         expect: 'sutra lint: exit 0' and 'sutra test: exit 0'")
    w(f"      python3 tools/drishti.py pack verify bundle/{n}-{v['version']}.tar.gz     expect: the last line 'verified'")
    w(f"      sha256sum -c bundle/{n}-{v['version']}.tar.gz.sha256                   (checks the archive alone)")
    w("  Against the running server:")
    if dated:
        w(f"      curl -s 'http://localhost:18480/api/v1/views/{kind}/{ids[0]}?asOf={d1}' | head -c 200")
        w(f"      expect JSON starting {{\"ref\":{{\"kind\":\"{kind}\",\"id\":\"{ids[0]}\"}}... ; repeat with asOf={d2} for another date.")
        w(f"      Console: type  {v['mnemonic']} {ids[0]}  and press Enter; pick the business date in the top bar.\n")
    else:
        w(f"      curl -s http://localhost:18480/api/v1/views/{kind}/{ids[0]} | head -c 200     (served from the pack's samples)\n")
    if dated:
        w("5. LIFT THE DATA FROM DELTA" if store == "delta" else "5. THE DATA (FILES STORE)")
        w("-" * 72)
        if store == "delta":
            w(f"  Where the lake is.  data/delta/ here; on a server, whatever {root_var} says (default ./data/delta).")
            w("  The layout, one Delta table per kind, partitioned by the business date:")
            w(f"      <{root_var}>/{n}/{kind}/business_date=YYYY-MM-DD/part-*.parquet")
            w(f"      <{root_var}>/{n}/{kind}/_delta_log/            (the log decides what is visible; never copy it half-way)")
            w(f"  Here: data/delta/{n}/{kind}/business_date={d1}/ ... business_date={d2}/   (the {v['date_field']} of each document is its partition)")
            w(f"  How the server reads it.  The pack's connector (pack/{n}/pack.yaml, connectors:) is the 'delta' plugin with root ${{DRISHTI_DELTA_ROOT:./data/delta}}")
            w(f"  and domain {n}. A view asks for (kind, id, asOf): the connector takes the newest business_date <= asOf that holds the id.")
            w("  The reader is the Delta Kernel; DRISHTI_DELTA_ENGINE chooses its file access: 'native' (default; local disk and s3:// / s3a://, works on Windows)")
            w("  or 'hadoop' (also abfs://, gs://, HDFS). `server health` shows 'UP (engine: native)' when the lake is reachable.")
            w("  Add new days.  Re-run the ingest tool on the new JSON Lines, into the SAME lake; the default mode 'overwrite-dates' replaces only the dates")
            w("  present in the input and commits atomically through Delta, so readers never see a half-written day:")
            w(f"      uv run --with {pkgs} python tools/drishti.py data ingest \\")
            w(f"          --from /path/to/new-days --pack pack/{n} --lake /path/to/lake      (add --dry-run first to see what it would write)")
            w("  Point at a lake that already exists elsewhere.  Do not copy anything: set DRISHTI_DELTA_ROOT=/mnt/lake, or s3a://bucket/prefix, before starting")
            w(f"  the server (for S3 also AWS_REGION and credentials). The tables must be at <root>/{n}/{kind}/ with the business_date partition;")
            w("  the surest way to get that layout is to ingest with the command above.")
            w("  Check it is served.  With the server running on this lake:")
            w(f"      curl -s 'http://localhost:18480/api/v1/views/{kind}/{ids[0]}?asOf={d1}' | head -c 200")
            w(f"      curl -s 'http://localhost:18480/api/v1/views/{kind}/{ids[0]}?asOf={d2}' | head -c 200")
            w(f"  and the connector itself: python3 tools/drishti.py server health --server http://localhost:18480   ('{n}-store' should be UP).\n")
        else:
            w(f"  The documents are JSON Lines under data/files/{n}/<date>/{kind}.jsonl (one folder per business date), read by the 'file' connector")
            w(f"  with root ${{DRISHTI_FILES_ROOT:./data/files}}. New days: python3 tools/drishti.py data ingest --from NEW --pack pack/{n} --store files --root /path/to/files")
            w("  (the server rescans every 30 seconds). Point at another folder by setting DRISHTI_FILES_ROOT. Check as in step 4.\n")
    w("6. UPDATE AND ROLL BACK")
    w("-" * 72)
    w("  New days:    step 5. The server picks new partitions up within seconds.")
    w(f"  Changed Sutras or about text:  edit pack/{n}/..., run `pack check`, raise version in pack/{n}/pack.yaml, `pack bundle`, `pack deploy`")
    w("               (or re-run this command with --version and --force on the same input).")
    w(f"  Roll back:   python3 tools/drishti.py pack rollback {n} --to /opt/drishti/packs      (puts the previous version back; deploy kept it)")
    if store == "delta":
        w("  Data:        Delta keeps its history: the previous version of a table stays in _delta_log until vacuumed.")
    w("")
    w("7. TROUBLESHOOTING")
    w("-" * 72)
    w(f"  * The view is empty or 404: the pack is not on (DRISHTI_PACKS), or the packs folder is wrong (drishti.packs.dir must CONTAIN the folder '{n}').")
    if dated:
        w(f"  * The view shows a sample but not your id: the data root is wrong. Check {root_var} and that <root>/{n}/ exists; Health shows the connector DOWN with the path it tried.")
        w("  * Another date shows nothing: that date has no document for the id; the connector reads the newest date at or before asOf.")
    w("  * 'no drishti-server-*-exec.jar': set DRISHTI_JAR (or build it with ./mvnw -q -DskipTests package).")
    w("  * 'port already in use': set DRISHTI_PORT to a free port.")
    w(f"  * pack check reports help warnings (DRS-2045..2047): fill the TODO texts in pack/{n}/config/about.yaml (`pack about-check` lists them).")
    w("  * Delta on Windows without winutils: keep DRISHTI_DELTA_ENGINE=native (the default there).")
    w("  * More: docs/guides/CLI_GUIDE.md (pack make, data ingest) and docs/guides/OPERATIONALISING.md in the Drishti repository.\n")
    return "\n".join(L)


# ----------------------------------------------------------------------------------------------- the command

def add_arguments(p: argparse.ArgumentParser) -> None:
    p.add_argument("inputs", type=pathlib.Path, nargs="+", help="folder(s) of *.jsonl and/or single .jsonl file(s)")
    p.add_argument("--kind", required=True, help="the kind of every document (e.g. trade)")
    p.add_argument("--match", required=True, metavar="COL[,COL]", help="column(s) whose values split the kind: one Sutra per combination (e.g. productType)")
    p.add_argument("--name", required=True, help="pack name (lower-case letters, digits, '-')")
    p.add_argument("--key", help="the id field (default: auto-detected: present and unique in every document, <kind>Id/id first)")
    p.add_argument("--date", help="the business-date field (default: auto-detected; none found: no dated store)")
    p.add_argument("--title", help="pack title (default: the name)")
    p.add_argument("--version", default="1.0.0", help="pack version (default 1.0.0)")
    p.add_argument("--store", choices=("delta", "files"), default="delta", help="the dated store: delta (default) or the File connector's files")
    p.add_argument("--out", type=pathlib.Path, help="the folder to create (default build/<name>-<version>)")
    p.add_argument("--fallback", dest="fallback", action="store_true", default=True, help="also write <kind>-default for documents matching no group (default)")
    p.add_argument("--no-fallback", dest="fallback", action="store_false", help="no catch-all Sutra")
    p.add_argument("--force", action="store_true", help="replace --out when it exists")
    p.add_argument("--samples", type=int, default=5, help="samples per group (default 5)")
    p.add_argument("--jar", help="the drishti-server exec jar (default: DRISHTI_JAR, then drishti-server/target)")
    p.add_argument("--java", help="java binary (default: JAVA_HOME, then JDK 21, then java)")
    p.add_argument("-r", "--recursive", action="store_true", help="read *.jsonl in subfolders too")


def make(a, cli) -> int:
    """Runs the whole flow; `cli` is the drishti.py module (for pack_from_jsonl, pack check, bundle and verify)."""
    if not NAME_RE.fullmatch(a.name):
        raise MakeError("--name is lower-case letters, digits and '-'")
    if not re.fullmatch(r"[0-9A-Za-z][0-9A-Za-z._-]*", a.version):
        raise MakeError("--version is a plain version such as 1.0.0")
    out = (a.out or pathlib.Path("build") / f"{a.name}-{a.version}").resolve()
    SG = cli.sutragen()
    kinds = SG.load_folder(a.inputs, a.recursive, a.kind)
    docs = kinds.get(a.kind, [])
    if not docs:
        raise MakeError("no documents found in " + ", ".join(str(i) for i in a.inputs))
    match = [m.strip() for m in a.match.split(",") if m.strip()]
    print(f"read {len(docs)} {a.kind} document(s)")
    key, key_why = (a.key, "given with --key") if a.key else detect_key(docs, a.kind)
    print(f"key:   {key}  ({key_why})")
    date, date_why = (a.date, "given with --date") if a.date else detect_date(docs)
    print(f"date:  {date or '(none)'}  ({date_why})")
    missing = [m for m in match + [key] + ([date] if date else []) if not any(SG.get(d, m) is not SG.MISSING for d in docs)]
    if missing:
        raise MakeError("field(s) not found in any document: " + ", ".join(missing))
    if out.exists() and any(out.iterdir()):
        if not a.force:
            raise MakeError(f"{out} exists; pass --force to replace it, or --out elsewhere")
        shutil.rmtree(out)

    pack_dir = out / "pack" / a.name
    data_dir = out / "data" / ("delta" if a.store == "delta" else "files")
    pfj = cli.load_module("pack_from_jsonl", "packgen/pack_from_jsonl.py")
    ns = argparse.Namespace(inputs=a.inputs, name=a.name, title=a.title or a.name, description=None, version=a.version, mnemonic=None, out=pack_dir,
                            force=True, catalog_samples=25, lake=data_dir if (date and a.store == "delta") else None, store=a.store,
                            files_root=data_dir if (date and a.store == "files") else None, kind=a.kind, key=f"{a.kind}={key}",
                            date=f"{a.kind}={date}" if date else None, match=a.match, skip_missing=False, samples=a.samples, min_docs=1, priority=10,
                            fallback=a.fallback, name_prefix="", jar=cli.find_jar(a.jar), java=cli.find_java(a.java), recursive=a.recursive)
    cli.need_yaml()
    if pfj.run(ns):
        raise MakeError("pack generation failed (see above)", 1)

    tool_args = ["--jar", ns.jar, "--java", ns.java]
    print("\n== pack check")
    chk = cli.main(["pack", "check", str(pack_dir), *tool_args])
    bundle_dir = out / "bundle"
    print("\n== pack bundle")
    if cli.main(["pack", "bundle", str(pack_dir), "--out", str(bundle_dir), "--no-check", *tool_args]):
        raise MakeError("bundling failed", 1)
    archive = bundle_dir / f"{a.name}-{a.version}.tar.gz"
    print("\n== pack verify")
    ver = cli.main(["pack", "verify", str(archive), *tool_args])

    write_site_yaml(out, a.name, a.store)
    write_scripts(out, a.name, a.store)
    groups = sorted(p.name for p in (pack_dir / "tests").iterdir() if p.is_dir()) if (pack_dir / "tests").is_dir() else []
    by_date: dict[str, int] = {}
    if date:
        for d in docs:
            x = SG.get(d, date)
            if isinstance(x, str):
                by_date[x[:10]] = by_date.get(x[:10], 0) + 1
    ids = [str(SG.get(d, key)) for d in docs[:3]]
    mnemonics = cli.need_yaml().safe_load((pack_dir / "pack.yaml").read_text(encoding="utf-8")).get("mnemonics") or {}
    vals = dict(name=a.name, kind=a.kind, store=a.store, title=a.title or a.name, version=a.version, key=key, key_why=key_why, date_field=date, date_why=date_why,
                match=match, fallback=a.fallback, sutras=len(groups), docs=len(docs), sample_ids=ids, dates=sorted(by_date) or [""],
                mnemonic=next(iter(mnemonics), ""), made=datetime.date.today().isoformat(), input=", ".join(str(i) for i in a.inputs),
                server_version=cli.pack_bundle().server_version(cli.ROOT) or "the current")
    (out / "README.txt").write_text(readme_text(vals), encoding="utf-8")
    files = {str(p.relative_to(out)): sha256(p) for p in tree_files(out) if p.relative_to(out).parts[0] != "data"}
    manifest = {"name": a.name, "version": a.version, "kind": a.kind, "store": a.store, "key": {"field": key, "why": key_why},
                "date": {"field": date, "why": date_why}, "match": match, "fallback": a.fallback, "documents": {a.kind: len(docs)},
                "sutraGroups": groups, "documentsPerDate": by_date, "bundle": {"file": f"bundle/{archive.name}", "sha256": sha256(archive)},
                "tools": cli.tool_versions(), "madeAt": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds"),
                "checks": {"packCheck": chk == 0, "packVerify": ver == 0}, "files": files}
    if date:
        manifest["dataFiles"] = len(tree_files(data_dir))
    (out / "MANIFEST.json").write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

    print(f"\npack make: {out}")
    print(f"  {len(groups)} Sutra group(s) for {len(docs)} {a.kind} document(s)" + (f", {len(by_date)} date(s) in data/{data_dir.name}" if date else ", no dated store")
          + f"; pack check {'passed' if chk == 0 else 'FAILED'}, pack verify {'passed' if ver == 0 else 'FAILED'}")
    print(f"next: read {out / 'README.txt'}, then  DRISHTI_JAR=<exec jar> DRISHTI_PORT=18480 {out / 'run-server.sh'}")
    return 1 if (chk or ver) else 0
