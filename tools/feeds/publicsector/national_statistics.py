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

"""The national-statistics feed: Statistics Canada's Web Data Service (WDS) by vector, for the national-statistics pack
(config/feeds/national-statistics.yaml). Incremental: the first pull asks each vector's last `history-periods` periods, later pulls the
last three, merged into the kept history (history/<vector>.json); when no vector has a newer release time, nothing is written. Writes
the statistics-series kind (one a vector: the latest value, the change on the period and on the year, the history)."""
from __future__ import annotations

import json
from pathlib import Path

from base import FeedError, PublicFeed, change, number

WDS = "https://www150.statcan.gc.ca/t1/wds/rest"
# WDS getCodeSets: frequency and scalar factor codes
FREQUENCY = {1: "daily", 2: "weekly", 4: "biweekly", 6: "monthly", 7: "bimonthly", 9: "quarterly", 11: "semi-annual", 12: "annual",
             13: "every 2 years", 14: "every 3 years", 15: "every 4 years", 16: "every 5 years", 17: "every 10 years", 18: "occasional",
             19: "occasional quarterly", 20: "occasional monthly", 21: "occasional daily"}
PER_YEAR = {"daily": 260, "weekly": 52, "biweekly": 26, "monthly": 12, "bimonthly": 6, "quarterly": 4, "semi-annual": 2, "annual": 1}
SCALE = {0: "units", 1: "tens", 2: "hundreds", 3: "thousands", 4: "tens of thousands", 5: "hundreds of thousands", 6: "millions",
         7: "tens of millions", 8: "hundreds of millions", 9: "billions"}


def table_id(pid: int) -> str:
    s = str(pid)
    return f"{s[0:2]}-{s[2:4]}-{s[4:8]}-01"


class NationalStatistics(PublicFeed):
    name = "national-statistics"

    def pull(self, state: dict) -> dict[str, list[dict]]:
        sel = self.selection
        vectors = sel.get("vectors") or []
        if not vectors:
            raise FeedError("no vectors in config/feeds/national-statistics.yaml selection")
        known = state.setdefault("vectors", {})
        first = not known or state.get("force")
        n = int(sel.get("history-periods", 60)) if first else 3
        info = {o["vectorId"]: o for o in self._post("getSeriesInfoFromVector", [{"vectorId": int(v["vector"])} for v in vectors])}
        data = {o["vectorId"]: o for o in self._post("getDataFromVectorsAndLatestNPeriods",
                                                      [{"vectorId": int(v["vector"]), "latestN": n} for v in vectors])}
        home = Path(state["dir"]) / "history"
        docs, newer = [], False
        for v in vectors:
            vid = int(v["vector"])
            d, i = data.get(vid), info.get(vid) or {}
            if not d:
                self.log(f"  v{vid}: no data answered")
                continue
            points = d.get("vectorDataPoint") or []
            released = max((p.get("releaseTime") or "" for p in points), default="")
            if released and released != (known.get(str(vid)) or {}).get("released"):
                newer = True
            hist = self._merge(home / f"v{vid}.json", points, int(sel.get("history-periods", 60)))
            if not hist:
                continue
            known[str(vid)] = {"released": released, "last": hist[-1]["PERIOD"]}
            docs.append(self._doc(v, d, i, hist, released))
        if not docs:
            raise FeedError("Statistics Canada answered no data for the selection")
        if not newer and not first:
            self.log("  no new release since the last pull")
            return {}
        state.pop("force", None)
        return {"statistics-series": docs}

    def _post(self, method: str, body: list) -> list[dict]:
        out = []
        for r in self.fetch.json(f"{WDS}/{method}", body=body):
            if r.get("status") == "SUCCESS" and isinstance(r.get("object"), dict):
                out.append(r["object"])
            else:
                self.log(f"  {method}: {r.get('object')}")
        return out

    @staticmethod
    def _merge(file: Path, points: list[dict], keep: int) -> list[dict]:
        try:
            kept = {h["PERIOD"]: h["VALUE"] for h in json.loads(file.read_text(encoding="utf-8"))}
        except (OSError, ValueError):
            kept = {}
        for p in points:
            v = number(p.get("value"))
            if v is not None:
                kept[p["refPer"]] = v
        hist = [{"PERIOD": k, "VALUE": v} for k, v in sorted(kept.items())][-keep:]
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(json.dumps(hist, separators=(",", ":")) + "\n", encoding="utf-8")
        return hist

    def _doc(self, v: dict, d: dict, i: dict, hist: list[dict], released: str) -> dict:
        vid = int(v["vector"])
        pid = d.get("productId") or i.get("productId")
        freq = d.get("frequencyCode") or i.get("frequencyCode") or ((d.get("vectorDataPoint") or [{}])[-1]).get("frequencyCode")
        latest, prev = hist[-1], (hist[-2] if len(hist) > 1 else None)
        frequency = v.get("frequency") or FREQUENCY.get(freq, str(freq))     # the YAML wins: WDS codes some series "occasional"
        lag = PER_YEAR.get(frequency)
        year_ago = hist[-1 - lag] if lag and len(hist) > lag else None
        pct = lambda a, b: None if a is None or b in (None, 0) else round((a / b - 1) * 100, 4)  # noqa: E731
        scale = (d.get("vectorDataPoint") or [{}])[-1].get("scalarFactorCode", i.get("scalarFactorCode"))
        url = f"https://www150.statcan.gc.ca/t1/tbl1/en/tv.action?pid={pid}01" if pid else None
        return {"SERIES_ID": f"NSTAT-V{vid}", "NAME": v["title"], "VECTOR": f"v{vid}", "TITLE": v["title"],
                "MEMBERS": i.get("SeriesTitleEn"), "GEOGRAPHY": (i.get("SeriesTitleEn") or "").split(";")[0] or None,
                "TABLE_ID": table_id(pid) if pid else None, "TABLE_TITLE": v.get("table-title"), "TOPIC": v.get("topic"),
                "FREQUENCY": frequency, "UNIT": v.get("unit"), "SCALE": SCALE.get(scale, str(scale)),
                "LATEST": latest["VALUE"], "LATEST_PERIOD": latest["PERIOD"], "PREVIOUS": prev["VALUE"] if prev else None,
                "PREVIOUS_PERIOD": prev["PERIOD"] if prev else None, "CHANGE": change(latest["VALUE"], prev["VALUE"] if prev else None),
                "CHANGE_PCT": pct(latest["VALUE"], prev["VALUE"] if prev else None),
                "YEAR_ON_YEAR_PCT": pct(latest["VALUE"], year_ago["VALUE"] if year_ago else None),
                "RELEASED": released or None, "HISTORY": hist,
                "PROVENANCE": self.provenance(dataset=f"Statistics Canada table {table_id(pid) if pid else '?'}, vector v{vid}", url=url)}
