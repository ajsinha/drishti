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

# Fetches the framework builds the three sample host pages run on into ./vendor/ (never committed: .gitignore). Needs node and npm and the npm registry once;
# afterwards the samples run offline. Pinned versions (all MIT): React 18.3.1 (UMD), Vue 3.5.43 (runtime-only global build), Angular 18.2.14 (ES modules),
# RxJS 7.8.2 and tslib 2.8.1 (Angular's two imports).
#
#     bash docs/guides/examples/embed/fetch-vendor.sh [--only react|vue|angular]
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VENDOR="$HERE/vendor"
ONLY="${2:-all}"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/drishti-embed-vendor.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

REACT=18.3.1
VUE=3.5.43
ANGULAR=18.2.14
RXJS=7.8.2
TSLIB=2.8.1

pack() {   # pack <package@version> <folder>: downloads the npm tarball and unpacks it as <folder>/package
  mkdir -p "$WORK/$2"
  (cd "$WORK/$2" && npm pack --silent "$1" >/dev/null && tar xzf ./*.tgz)
}

if [[ "$ONLY" == all || "$ONLY" == react ]]; then
  pack "react@$REACT" react && pack "react-dom@$REACT" react-dom
  mkdir -p "$VENDOR/react" "$VENDOR/react-dom"
  cp "$WORK/react/package/umd/react.production.min.js" "$VENDOR/react/"
  cp "$WORK/react-dom/package/umd/react-dom.production.min.js" "$VENDOR/react-dom/"
fi

if [[ "$ONLY" == all || "$ONLY" == vue ]]; then
  pack "vue@$VUE" vue
  mkdir -p "$VENDOR/vue"
  cp "$WORK/vue/package/dist/vue.runtime.global.prod.js" "$VENDOR/vue/"
fi

if [[ "$ONLY" == all || "$ONLY" == angular ]]; then
  for p in core common compiler platform-browser; do
    pack "@angular/$p@$ANGULAR" "ng-$p"
    mkdir -p "$VENDOR/angular/$p"
    cp -r "$WORK/ng-$p/package/fesm2022" "$VENDOR/angular/$p/"
  done
  pack "rxjs@$RXJS" rxjs && pack "tslib@$TSLIB" tslib
  mkdir -p "$VENDOR/rxjs/dist" "$VENDOR/tslib"
  cp -r "$WORK/rxjs/package/dist/esm5" "$VENDOR/rxjs/dist/"
  cp "$WORK/tslib/package/tslib.es6.mjs" "$VENDOR/tslib/"
fi

echo "vendor folder ready: $VENDOR"
