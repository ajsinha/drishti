#!/usr/bin/env bash
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

# Installs the Python runtime of Calc (PYTHON_CALC.md): Pyodide, CPython compiled to WebAssembly, which the console
# serves from its own origin (never a CDN). Downloads the pinned release once, checks its SHA-256, and unpacks only
# the core runtime and numpy, pandas, scipy, statsmodels and matplotlib with what they depend on into
# console/web/static/vendor/pyodide/ (git-ignored). Run it again and it does nothing unless the pin changed.
#
#   tools/fetch-pyodide.sh                        download (cached in ~/.cache/drishti), verify, unpack
#   tools/fetch-pyodide.sh --tarball FILE         an already downloaded pyodide-<version>.tar.bz2 (offline machines)
#   tools/fetch-pyodide.sh --dest DIR --force     somewhere else; unpack again even if installed
#
# Needs bash and python3 (and curl or wget, or python3 alone, to download). The console Docker image runs it at build
# time, so a running console needs no internet.
set -euo pipefail

VERSION="314.0.7"
SHA256="192b5864e6e6d30ab074861af800cb8b4acb0998ef0f9342c3367448aeb86645"
URL="https://github.com/pyodide/pyodide/releases/download/${VERSION}/pyodide-${VERSION}.tar.bz2"
PACKAGES="numpy pandas scipy statsmodels matplotlib"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="${ROOT}/console/web/static/vendor/pyodide"
CACHE="${XDG_CACHE_HOME:-${HOME:-/tmp}/.cache}/drishti"
TARBALL=""
FORCE=0

while [ $# -gt 0 ]; do
  case "$1" in
    --dest) DEST="$2"; shift 2 ;;
    --tarball) TARBALL="$2"; shift 2 ;;
    --cache) CACHE="$2"; shift 2 ;;
    --force) FORCE=1; shift ;;
    -h|--help) sed -n '2,13p' "$0"; exit 0 ;;
    *) echo "fetch-pyodide: unknown option $1 (see --help)" >&2; exit 2 ;;
  esac
done

STAMP="${DEST}/.drishti-pyodide"
if [ "$FORCE" = 0 ] && [ -f "$STAMP" ] && grep -q "^version=${VERSION}$" "$STAMP" && grep -q "^sha256=${SHA256}$" "$STAMP"; then
  echo "Pyodide ${VERSION} is already installed in ${DEST} ($(du -sh "$DEST" | cut -f1)); --force unpacks it again"
  exit 0
fi

sha_of() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1
  elif command -v shasum >/dev/null 2>&1; then shasum -a 256 "$1" | cut -d' ' -f1
  else python3 -c 'import hashlib,sys;h=hashlib.sha256();f=open(sys.argv[1],"rb");[h.update(b) for b in iter(lambda:f.read(1<<20),b"")];print(h.hexdigest())' "$1"
  fi
}

if [ -z "$TARBALL" ]; then
  TARBALL="${CACHE}/pyodide-${VERSION}.tar.bz2"
  if [ ! -f "$TARBALL" ] || [ "$(sha_of "$TARBALL")" != "$SHA256" ]; then
    mkdir -p "$CACHE"
    echo "downloading ${URL} (about 340 MB, once)"
    if command -v curl >/dev/null 2>&1; then curl -fL --retry 3 -o "${TARBALL}.part" "$URL"
    elif command -v wget >/dev/null 2>&1; then wget -q -O "${TARBALL}.part" "$URL"
    else python3 -c 'import sys,urllib.request;urllib.request.urlretrieve(sys.argv[1],sys.argv[2])' "$URL" "${TARBALL}.part"
    fi
    mv "${TARBALL}.part" "$TARBALL"
  fi
fi
GOT="$(sha_of "$TARBALL")"
if [ "$GOT" != "$SHA256" ]; then
  echo "fetch-pyodide: ${TARBALL} has SHA-256 ${GOT}, expected ${SHA256}: refusing it" >&2
  exit 1
fi
echo "verified ${TARBALL} (SHA-256 ${SHA256})"

WORK="$(mktemp -d "${TMPDIR:-/tmp}/drishti-pyodide.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
python3 - "$TARBALL" "$WORK/pyodide" "$PACKAGES" <<'PY'
"""Unpacks the core runtime and the closure of the wanted packages, and writes a lock file listing only those, so
Pyodide never looks for anything that is not here (an import of anything else fails at once, with no request)."""
import json, sys, tarfile
from pathlib import Path

tarball, out, wanted = sys.argv[1], Path(sys.argv[2]), sys.argv[3].split()
CORE = {"pyodide.mjs", "pyodide.asm.mjs", "pyodide.asm.wasm", "python_stdlib.zip", "package.json"}

def members():
    with tarfile.open(tarball, "r|bz2") as tar:          # streamed: the archive is read front to back
        for m in tar:
            yield tar, m

lock = None
for tar, m in members():                                   # pass 1: the lock file, to know what the packages need
    if m.name == "pyodide/pyodide-lock.json":
        lock = json.load(tar.extractfile(m))
        break
if lock is None:
    sys.exit("fetch-pyodide: no pyodide-lock.json in the archive")
packages = lock["packages"]
keep, todo = set(), list(wanted)
while todo:
    name = todo.pop()
    if name in keep:
        continue
    if name not in packages:
        sys.exit(f"fetch-pyodide: the release has no package {name}")
    keep.add(name)
    todo.extend(packages[name].get("depends", []))
files = {packages[n]["file_name"] for n in keep} | CORE
out.mkdir(parents=True)
found = set()
for tar, m in members():                                   # pass 2: only those files
    name = m.name.removeprefix("pyodide/")
    if m.isfile() and name in files and "/" not in name:
        (out / name).write_bytes(tar.extractfile(m).read())
        found.add(name)
missing = files - found
if missing:
    sys.exit(f"fetch-pyodide: missing from the archive: {sorted(missing)}")
lock["packages"] = {n: p for n, p in packages.items() if n in keep}
(out / "pyodide-lock.json").write_text(json.dumps(lock, indent=1), encoding="utf-8")
print("packages: " + ", ".join(sorted(keep)))
PY

cat > "$WORK/pyodide/.drishti-pyodide" <<EOF
version=${VERSION}
sha256=${SHA256}
packages=${PACKAGES}
source=${URL}
installed=$(date -u +%Y-%m-%dT%H:%M:%SZ)
EOF
mkdir -p "$(dirname "$DEST")"
rm -rf "${DEST}.old"
if [ -d "$DEST" ]; then mv "$DEST" "${DEST}.old"; fi
mv "$WORK/pyodide" "$DEST"
rm -rf "${DEST}.old"
echo "installed Pyodide ${VERSION} in ${DEST}: $(du -sh "$DEST" | cut -f1) on disk, $(find "$DEST" -type f | wc -l | tr -d ' ') files"
