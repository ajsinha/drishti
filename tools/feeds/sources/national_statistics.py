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

"""The national-statistics feed (config/feeds/national-statistics.yaml): Statistics Canada's Web Data Service by vector, for the
national-statistics pack. One GET for every vector (getDataFromVectorByReferencePeriodRange). Incremental: the first run asks
`history-years`, later runs from a quarter before each vector's last period, merged into its kept history (history/v<vector>.json); no
vector with a newer release, nothing emitted. Emits the statistics-series kind (one a vector: the latest value, the change on the period
and on the year, the history)."""
from __future__ import annotations

import urllib.parse
from datetime import date, timedelta

from feedbase import Feed, FeedError
from publicsector import History, change, need, number, provenance

WDS = "https://www150.statcan.gc.ca/t1/wds/rest"
# WDS getCodeSets: frequency and scalar factor codes
FREQUENCY = {1: "daily", 2: "weekly", 4: "biweekly", 6: "monthly", 7: "bimonthly", 9: "quarterly", 11: "semi-annual", 12: "annual",
             13: "every 2 years", 14: "every 3 years", 15: "every 4 years", 16: "every 5 years", 17: "every 10 years", 18: "occasional",
             19: "occasional quarterly", 20: "occasional monthly", 21: "occasional daily"}
PER_YEAR = {"daily": 260, "weekly": 52, "biweekly": 26, "monthly": 12, "bimonthly": 6, "quarterly": 4, "semi-annual": 2, "annual": 1}
SCALE = {0: "units", 1: "tens", 2: "hundreds", 3: "thousands", 4: "tens of thousands", 5: "hundreds of thousands", 6: "millions",
         7: "tens of millions", 8: "hundreds of millions", 9: "billions"}
REVISION_DAYS = 92           # revised periods: asked again from about a quarter before the last one


def table_id(pid: int) -> str:
    s = str(pid)
    return f"{s[0:2]}-{s[2:4]}-{s[4:8]}-01"


class NationalStatisticsFeed(Feed):
    def plan(self, run):
        return [self._url(run, self._start(run))]

    def _start(self, run) -> str:
        known = run.state.get("vectors") or {}
        lasts = [(known.get(str(v["vector"])) or {}).get("last") for v in run.option("vectors", [])]
        if all(lasts) and lasts and not run.since:
            return (date.fromisoformat(min(lasts)) - timedelta(days=REVISION_DAYS)).isoformat()
        if run.since:
            return run.since.isoformat()
        return date(run.business_date.year - int(run.option("history-years", 5)), 1, 1).isoformat()

    def _url(self, run, start: str) -> str:
        ids = ",".join(f'"{int(v["vector"])}"' for v in run.option("vectors", []))
        return (f"{WDS}/getDataFromVectorByReferencePeriodRange?vectorIds={urllib.parse.quote(ids, safe=',')}"
                f"&startRefPeriod={start}&endReferencePeriod={run.business_date.isoformat()}")

    def pull(self, run):
        vectors = run.option("vectors", [])
        if not vectors:
            raise FeedError("no vectors in config/feeds/national-statistics.yaml options")
        known = run.state.setdefault("vectors", {})
        data = {}
        for r in need(run.get_json(self._url(run, self._start(run))), "Statistics Canada WDS"):
            if r.get("status") == "SUCCESS" and isinstance(r.get("object"), dict):
                data[r["object"]["vectorId"]] = r["object"]
            else:
                run.log(f"  WDS: {r.get('object')}")
        keep = int(run.option("history-periods", 60))
        docs, newer = [], False
        for v in vectors:
            vid = int(v["vector"])
            d = data.get(vid)
            if not d:
                run.log(f"  v{vid}: no data answered")
                continue
            points = d.get("vectorDataPoint") or []
            released = max((p.get("releaseTime") or "" for p in points), default="")
            if released and released != (known.get(str(vid)) or {}).get("released"):
                newer = True
            hist = History(run, f"v{vid}").merge({p["refPer"]: number(p.get("value")) for p in points}, keep=keep, key="PERIOD")
            if not hist:
                continue
            known[str(vid)] = {"released": released or (known.get(str(vid)) or {}).get("released"), "last": hist[-1]["PERIOD"]}
            docs.append(self._doc(run, v, d, hist, known[str(vid)]["released"]))
        if not docs:
            raise FeedError("Statistics Canada answered no data for the selection")
        if not newer and run.state.get("lastRun") and not run.since:
            run.log("  no new release since the last run")
            return
        run.emit("statistics-series", docs)
        run.mark("statistics-series", run.business_date)

    def _doc(self, run, v: dict, d: dict, hist: list[dict], released) -> dict:
        vid = int(v["vector"])
        pid = d.get("productId")
        last_point = (d.get("vectorDataPoint") or [{}])[-1]
        frequency = v.get("frequency") or FREQUENCY.get(last_point.get("frequencyCode"), str(last_point.get("frequencyCode")))
        latest, prev = hist[-1], (hist[-2] if len(hist) > 1 else None)
        lag = PER_YEAR.get(frequency)
        year_ago = hist[-1 - lag] if lag and len(hist) > lag else None
        pct = lambda a, b: None if a is None or b in (None, 0) else round((a / b - 1) * 100, 4)  # noqa: E731
        scale = last_point.get("scalarFactorCode", 0)
        url = f"https://www150.statcan.gc.ca/t1/tbl1/en/tv.action?pid={pid}01" if pid else None
        return {"SERIES_ID": f"NSTAT-V{vid}", "NAME": v["title"], "VECTOR": f"v{vid}", "TITLE": v["title"],
                "MEMBERS": v.get("members"), "GEOGRAPHY": v.get("geography", "Canada"),
                "TABLE_ID": table_id(pid) if pid else None, "TABLE_TITLE": v.get("table-title"), "TOPIC": v.get("topic"),
                "FREQUENCY": frequency, "UNIT": v.get("unit"), "SCALE": SCALE.get(scale, str(scale)),
                "LATEST": latest["VALUE"], "LATEST_PERIOD": latest["PERIOD"], "PREVIOUS": prev["VALUE"] if prev else None,
                "PREVIOUS_PERIOD": prev["PERIOD"] if prev else None, "CHANGE": change(latest["VALUE"], prev["VALUE"] if prev else None),
                "CHANGE_PCT": pct(latest["VALUE"], prev["VALUE"] if prev else None),
                "YEAR_ON_YEAR_PCT": pct(latest["VALUE"], year_ago["VALUE"] if year_ago else None),
                "RELEASED": released or None, "HISTORY": hist,
                "PROVENANCE": provenance(run, "statcan", f"Statistics Canada table {table_id(pid) if pid else '?'}, vector v{vid}", url)}
