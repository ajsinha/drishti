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

# "Drill it": verify everything, push develop, merge develop into main, push main.
# Nothing is pushed or merged unless the full Java build and the console tests pass.
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"

[[ "$(git rev-parse --abbrev-ref HEAD)" == "develop" ]] || { echo "drill: must be on develop" >&2; exit 1; }
[[ -z "$(git status --porcelain)" ]] || { echo "drill: commit your changes first" >&2; exit 1; }

python3 tools/license_headers.py
for gen in $(grep -l -- "--check" packs/*/tools/make_*.py); do python3 "$gen" --check; done   # generated pack content is current
./mvnw -q -o verify
console/.venv/bin/python -m pytest -q console/tests

git push -q origin develop
git checkout -q main
git merge -q --ff-only develop
git push -q origin main
git checkout -q develop
echo "drilled: $(git log --oneline -1)"
