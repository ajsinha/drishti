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

"""Admin → BI usage (docs/architecture/BI_DESIGN.md sections 6.7 and 6.8, wave B console task 5): what BI is asked, by whom and how it is answered, from the
server's audit log (``bi-mq`` and ``bi-query`` per answer, ``bi-sql`` per SQL statement, with the rows, the layer and whether the cache answered), the engine's
own counters (``GET /bi/engine``) and the console's Python runner (fork-server and the L4 result cache). Wave C makes the audit log a dataset
(``pack.drishti.audit-bi``) and this a report over it; until then this module sums the latest events, nothing is stored."""
from __future__ import annotations

import re
from collections import Counter, defaultdict

QUERY_ACTIONS = ("bi-mq", "bi-query", "bi-sql")
ROWS = re.compile(r"(\d+) rows from (\S+)")
FIELDS = re.compile(r"(\d+) fields, (\d+) measures")


def summarise(events: list[dict]) -> dict:
    """The usage of the given audit events: per model (or dataset) the questions, cache hit rate, rows and layers; the busiest readers; SQL; other BI actions."""
    per: dict[str, dict] = defaultdict(lambda: {"questions": 0, "cached": 0, "rows": 0, "layers": Counter(), "readers": set(), "sql": 0, "fields": 0})
    readers: Counter = Counter()
    other: Counter = Counter()
    first, last = None, None
    for e in events or []:
        action = str(e.get("action") or "")
        if not action.startswith("bi-"):
            continue
        at = str(e.get("at") or "")
        first = at if first is None or at < first else first
        last = at if last is None or at > last else last
        if action not in QUERY_ACTIONS:
            other[action] += 1
            continue
        subject, detail, actor = str(e.get("subject") or "?"), str(e.get("detail") or ""), str(e.get("actor") or e.get("user") or "?")
        m = per[subject]
        readers[actor] += 1
        m["readers"].add(actor)
        if action == "bi-sql":
            m["sql"] += 1
            continue
        m["questions"] += 1
        if "(cached)" in detail:
            m["cached"] += 1
        r = ROWS.search(detail)
        if r:
            m["rows"] += int(r.group(1))
            m["layers"][r.group(2)] += 1
        f = FIELDS.search(detail)
        if f:
            m["fields"] += int(f.group(1))
    models = []
    for name, m in per.items():
        asked = m["questions"]
        models.append({"model": name, "questions": asked, "sql": m["sql"], "cached": m["cached"], "hitRate": round(100.0 * m["cached"] / asked, 1) if asked else 0.0,
                       "rows": m["rows"], "avgRows": round(m["rows"] / asked, 1) if asked else 0.0, "layers": dict(m["layers"].most_common()), "readers": len(m["readers"])})
    models.sort(key=lambda x: (-(x["questions"] + x["sql"]), x["model"]))
    total = sum(x["questions"] for x in models)
    cached = sum(x["cached"] for x in models)
    return {"models": models, "readers": [{"reader": k, "questions": v} for k, v in readers.most_common(20)], "other": [{"action": k, "count": v} for k, v in other.most_common()],
            "total": total, "sql": sum(x["sql"] for x in models), "hitRate": round(100.0 * cached / total, 1) if total else 0.0, "from": first, "to": last, "events": len(events or [])}
