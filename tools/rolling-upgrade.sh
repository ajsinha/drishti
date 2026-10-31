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

# The rolling upgrade runner (CD3, docs/architecture/CLUSTER_DEPLOYMENT.md section 10.1): one node at a time, consoles, BI and
# data-serving, coordinators, then owners one zone at a time; each drained, stopped, upgraded, started and waited for until ready.
#   tools/rolling-upgrade.sh --inventory my-cluster.yaml --version 2.9.7 --dry-run    every step printed, nothing called
#   tools/rolling-upgrade.sh --inventory my-cluster.yaml --version 2.9.7 [--from var-3] [--only var-1,var-2] [--token-file F]
# The inventory: deploy/rolling.example.yaml. The token: an administrator's API token with node:drain (DRISHTI_DRAIN_TOKEN).
set -euo pipefail
cd "$(dirname "$0")/.."
if command -v uv >/dev/null 2>&1; then
  exec uv run -q --with pyyaml python tools/rolling.py "$@"
fi
exec python3 tools/rolling.py "$@"
