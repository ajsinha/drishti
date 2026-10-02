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

"""More hostile days, on business days this time (09-05 was a Saturday and reads roll back to Friday)."""
import json, pathlib

S = pathlib.Path("/tmp/claude-1000/-home-ashutosh-IdeaProjects-drishti/efa68c11-fb98-4f9a-8faa-96a952090c0e/scratchpad/qa-data")
src = open(S / "scripts/hostile_files.py").read().split("docs = [(r")[0]
src = src.replace('shutil.rmtree(S / "hostile", ignore_errors=True)', "").replace("shutil.rmtree(TRUTH, ignore_errors=True)", "")
src = src.replace("OUT.mkdir(parents=True)", "OUT.mkdir(parents=True, exist_ok=True)").replace("TRUTH.mkdir()", "TRUTH.mkdir(exist_ok=True)")
exec(src)
docs = [(r["id"], json.loads(r["doc"])) for r in rows]
# 09-15: envelope rows, doc as a JSON object, no columns
write("2026-09-15", [env(i, d, "2026-09-15", columns=False, doc_as_object=True) for i, d in docs], docs)
# 09-16: one document nested 1500 deep, the others normal
deep = {}
cur = deep
for _ in range(1500):
    cur["n"] = {}
    cur = cur["n"]
write("2026-09-16", [env(docs[0][0], dict(docs[0][1], deep=deep), "2026-09-16")] + [env(i, d, "2026-09-16") for i, d in docs[1:]], docs)
# 09-17: one document with a 25 MB string, the others normal
big = dict(docs[0][1], blob="x" * 25_000_000)
write("2026-09-17", [env(docs[0][0], big, "2026-09-17")] + [env(i, d, "2026-09-17") for i, d in docs[1:]], [(docs[0][0], big)] + docs[1:])
# 09-18: a normal day (the last one), for lookback tests
write("2026-09-18", [env(i, d, "2026-09-18") for i, d in docs], docs)
print("more written")
