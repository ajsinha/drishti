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

"""delta_codec.py <lake root> <jsonl> <date> <codec>: rewrites one business date of trading/trade in the pack's layout but
with the given Parquet compression codec (LZ4, LZ4_RAW, BROTLI, ...) as a new Delta version."""
import json, sys, pathlib

W = "/home/ashutosh/IdeaProjects/drishti/.claude/worktrees/agent-a3a597410e145c292"
sys.path.insert(0, W + "/tools")
import samplegen.layout as L  # noqa
from datetime import date
from deltalake import WriterProperties

root, src, day, codec = sys.argv[1], sys.argv[2], date.fromisoformat(sys.argv[3]), sys.argv[4]
orig = L.WriterProperties if hasattr(L, "WriterProperties") else None


def props(**kw):
    kw["compression"] = codec
    return WriterProperties(**kw)


import deltalake  # noqa
deltalake.WriterProperties = props            # layout.write_file imports it from deltalake at call time
lay = L.layouts_for_domain("trading", pathlib.Path(W) / "packs")["trade"]
rows = []
for l in open(src, encoding="utf-8"):
    r = json.loads(l)
    if r["kind"] == "trade" and r["date"] == day.isoformat():
        rows.append((r["id"], r["doc"], json.loads(r["doc"])))
L.write_partition(root + "/trading/trade", day, rows, lay, "overwrite-partition")
print(f"wrote {len(rows)} rows for {day} with {codec}")
