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

# Installs DuckDB-Wasm for the Rupaka proof of concept (docs/architecture/RUPAKA_POC.md): the npm package, pinned and
# checked by SHA-256, from which only the "eh" browser build (the API module, the worker and the 36 MB wasm) is unpacked
# into drishti-console/web/static/vendor/duckdb-wasm/<version>/ (ignored by the repository: too big to commit; the console
# serves it from its own origin, never a CDN). Same pattern as tools/fetch-pyodide.sh.
#
#   tools/fetch-duckdb-wasm.sh                    download (cached in ~/.cache/drishti), verify, unpack
#   tools/fetch-duckdb-wasm.sh --tarball FILE     an already downloaded duckdb-duckdb-wasm-<version>.tgz
#   tools/fetch-duckdb-wasm.sh --dest DIR --force somewhere else; unpack again even if installed
set -euo pipefail

VERSION="1.33.1-dev57.0"
SHA256="a3f36b6430dcc40677a343ddcf0baefe8940398ece3dba29ce64a923576d1398"
URL="https://registry.npmjs.org/@duckdb/duckdb-wasm/-/duckdb-wasm-${VERSION}.tgz"
FILES="duckdb-browser.mjs duckdb-browser-eh.worker.js duckdb-eh.wasm"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="${ROOT}/drishti-console/web/static/vendor/duckdb-wasm/${VERSION}"
CACHE="${XDG_CACHE_HOME:-${HOME:-/tmp}/.cache}/drishti"
TARBALL=""
FORCE=0
while [ $# -gt 0 ]; do
  case "$1" in
    --dest) DEST="$2"; shift 2 ;;
    --tarball) TARBALL="$2"; shift 2 ;;
    --cache) CACHE="$2"; shift 2 ;;
    --force) FORCE=1; shift ;;
    -h|--help) sed -n '2,9p' "$0"; exit 0 ;;
    *) echo "fetch-duckdb-wasm: unknown option $1 (see --help)" >&2; exit 2 ;;
  esac
done

STAMP="${DEST}/.drishti-duckdb-wasm"
if [ "$FORCE" = 0 ] && [ -f "$STAMP" ] && grep -q "^sha256=${SHA256}$" "$STAMP"; then
  echo "DuckDB-Wasm ${VERSION} is already installed in ${DEST}; --force unpacks it again"
  exit 0
fi
sha_of() { python3 -c 'import hashlib,sys;print(hashlib.sha256(open(sys.argv[1],"rb").read()).hexdigest())' "$1"; }
if [ -z "$TARBALL" ]; then
  TARBALL="${CACHE}/duckdb-wasm-${VERSION}.tgz"
  if [ ! -f "$TARBALL" ] || [ "$(sha_of "$TARBALL")" != "$SHA256" ]; then
    mkdir -p "$CACHE"
    echo "downloading ${URL} (about 12 MB, once)"
    python3 -c 'import sys,urllib.request;urllib.request.urlretrieve(sys.argv[1],sys.argv[2])' "$URL" "${TARBALL}.part"
    mv "${TARBALL}.part" "$TARBALL"
  fi
fi
GOT="$(sha_of "$TARBALL")"
if [ "$GOT" != "$SHA256" ]; then
  echo "fetch-duckdb-wasm: ${TARBALL} has SHA-256 ${GOT}, expected ${SHA256}: refusing it" >&2
  exit 1
fi
mkdir -p "$DEST"
python3 - "$TARBALL" "$DEST" $FILES <<'PY'
import sys, tarfile
from pathlib import Path
tarball, out, wanted = sys.argv[1], Path(sys.argv[2]), set(sys.argv[3:])
found = set()
with tarfile.open(tarball, "r:gz") as tar:
    for m in tar:
        name = m.name.removeprefix("package/dist/")
        if m.isfile() and name in wanted and "/" not in name:
            data = tar.extractfile(m).read()
            if name == "duckdb-browser.mjs":      # a bare "apache-arrow" import needs an import map (inline script: not allowed): use the vendored bundle
                data = data.replace(b'from"apache-arrow"', b'from"../../apache-arrow/17.0.0/apache-arrow.mjs"')
            (out / name).write_bytes(data)
            found.add(name)
if found != wanted:
    sys.exit(f"fetch-duckdb-wasm: missing from the archive: {sorted(wanted - found)}")
PY
cat > "$(dirname "$DEST")/LICENSE" <<'EOT'
DuckDB-Wasm (https://github.com/duckdb/duckdb-wasm), MIT License

Copyright 2018-2025 Stichting DuckDB Foundation

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to
whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the
Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
EOT
cat > "$STAMP" <<EOT
version=${VERSION}
sha256=${SHA256}
source=${URL}
installed=$(date -u +%Y-%m-%dT%H:%M:%SZ)
EOT
echo "installed DuckDB-Wasm ${VERSION} in ${DEST} ($(du -sh "$DEST" | cut -f1))"
