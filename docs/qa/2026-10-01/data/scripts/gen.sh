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

# generate the samples (jsonl + lake) and an 8000-trade book into the scratch dir
set -euo pipefail
W=/home/ashutosh/IdeaProjects/drishti/.claude/worktrees/agent-a3a597410e145c292
S=/tmp/claude-1000/-home-ashutosh-IdeaProjects-drishti/efa68c11-fb98-4f9a-8faa-96a952090c0e/scratchpad/qa-data
cd "$W"
mkdir -p $S/gen
time uv run -q --with deltalake --with pyarrow --with pyyaml python tools/packgen/banking/make_data.py --jsonl $S/gen/banking.jsonl --lake $S/delta
time uv run -q --with deltalake --with pyarrow --with pyyaml python tools/samplegen/bulk_trades.py --trades 8000 --days 3 --workers 4 --jsonl $S/gen/bulk.jsonl
time uv run -q --with deltalake --with pyarrow --with pyyaml python tools/samplegen/bulk_trades.py --trades 8000 --days 3 --workers 4 --root $S/delta
