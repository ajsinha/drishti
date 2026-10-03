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

from qa import *
def show(name, docs):
    st, o = j("POST", "/api/v1/builder/shape", body={"samples": [{"name": f"s{i}", "document": d} for i, d in enumerate(docs)]})
    print(name, st, [(p["path"], p["type"]) for p in o["report"]["paths"]] if st == 200 else o)
show("optional-sections (4 object sections, 1 per doc)", [{"rates": {"dv01": 1}}, {"fx": {"delta": 2}}, {"credit": {"cs01": 3}}, {"equity": {"beta": 4}}])
show("optional-sections 2 per doc of 4", [{"rates": {"dv01": 1}, "fx": {"delta": 2}}, {"credit": {"cs01": 3}, "equity": {"beta": 4}}])
show("optional numbers", [{"jan": 1, "feb": 2}, {"mar": 3, "apr": 4}])
show("3 keys ids map", [{"m": {"T1": {"a": 1}, "T2": {"a": 2}, "T3": {"a": 3}}}])
show("5 patterned keys one doc", [{"m": {"T1": {"a": 1}, "T2": {"a": 2}, "T3": {"a": 3}, "T4": {"a": 2}, "T5": {"a": 3}}}])
show("5 patterned scalar keys one doc (a record of q1..q5)", [{"m": {"q1": 1, "q2": 2, "q3": 3, "q4": 4, "q5": 5}}])
show("record with 4 distinct sub-records, 1 doc", [{"m": {"a": {"x": 1}, "b": {"x": 2}, "c": {"x": 3}, "d": {"x": 4}}}])
show("map keys which are dates", [{"m": {"2026-01-01": 1, "2026-01-02": 2, "2026-01-03": 3, "2026-01-04": 4, "2026-01-05": 5}}])
show("map of mixed types", [{"m": {"a1": 1, "a2": "x", "a3": 3, "a4": 4, "a5": 5}}])
