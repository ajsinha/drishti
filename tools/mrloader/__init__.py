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

"""mrloader: market-risk loaders: per-path PVs (PVF extracts), the daily scenario set and scenario FX, into Drishti's market-risk kinds.

A one-off tool the owner runs by path from the repository root (README.md beside this file):

    python3 tools/mrloader/pvf_run.py         the driver: join, map, check, write the loadable layout (start here)
    python3 tools/mrloader/pvf_gen.py         synthetic inputs (portfolio, positions, per-path PVs) in the extract's CSV shape
    python3 tools/mrloader/pvf_join.py        portfolio + positions + per-path PVs -> one JSONL row per position, an audit of bad positions
    python3 tools/mrloader/pvf_export_gz.py   the joined JSONL -> scenario-pv records (kinds/scenario-pv.pvf.yaml) as .jsonl.gz parts + a .tar per kind to load
    python3 tools/mrloader/pvf_validate.py    every record of an output checked against the scenario-pv (and scenario-set) contract

Every attribute name these tools write is UPPER_SNAKE (common.upper_keys); values are never changed by that rule.
"""
