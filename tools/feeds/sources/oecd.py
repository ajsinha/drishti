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

"""The oecd feed (config/feeds/oecd.yaml): the OECD Data Explorer's SDMX REST API (SDMX-JSON 2.0), for the oecd pack. One request a
measure for every selected country. Incremental: later runs ask from three periods before each measure's last one, merged into the kept
history (history/<measure>-<country>.json); nothing newer, nothing emitted. Emits the oecd-country kind (one a country: the latest of
each measure) and the oecd-series kind (one a country and measure: the history)."""
from __future__ import annotations

import json
import urllib.parse

from feedbase import Feed, FeedError
from publicsector import History, change, ident, need, number, provenance

API = "https://sdmx.oecd.org/public/rest/data"
ACCEPT = "application/vnd.sdmx.data+json; charset=utf-8; version=2"


def parse_sdmx(msg: dict) -> list[dict]:
    """SDMX-JSON (2.0, or 1.0) with dimensionAtObservation=AllDimensions -> [{dims: {id: (code, name)}, period, value}]."""
    data = msg.get("data") or msg
    structure = (data.get("structures") or [None])[0] or data.get("structure") or {}
    dims = (structure.get("dimensions") or {}).get("observation") or []
    out = []
    for ds in data.get("dataSets") or []:
        for key, vals in (ds.get("observations") or {}).items():
            row = {}
            for d, i in zip(dims, (int(x) for x in key.split(":"))):
                values = d.get("values") or []
                if i >= len(values):
                    row = {}
                    break                                   # an index the structure does not list: not an observation we can name
                v = values[i]
                row[d["id"]] = (v.get("id"), v.get("name") or (v.get("names") or {}).get("en"))
            value = number(vals[0] if vals else None)
            if value is not None and "TIME_PERIOD" in row:
                out.append({"dims": row, "period": row["TIME_PERIOD"][0], "value": value})
    return out


class OecdFeed(Feed):
    def _url(self, run, m: dict, start: str) -> str:
        key = m["key"].replace("{countries}", "+".join(run.option("countries", [])))
        return f"{API}/{urllib.parse.quote(m['dataflow'], safe=',@')}/{key}?startPeriod={start}&dimensionAtObservation=AllDimensions"

    def _start(self, run, m: dict) -> str:
        restart = (run.state.get("measures", {}).get(m["id"]) or {}).get("restart")
        return restart if restart and not run.since else (run.since.strftime("%Y-%m") if run.since else run.option("start-period", "2020-01"))

    def plan(self, run):
        return [self._url(run, m, self._start(run, m)) for m in run.option("measures", [])]

    def pull(self, run):
        countries = run.option("countries", [])
        keep = int(run.option("history-periods", 72))
        known = run.state.setdefault("measures", {})
        series, profiles, newer = [], {}, False
        for m in run.option("measures", []):
            raw = need(run.get_bytes(self._url(run, m, self._start(run, m)), accept=ACCEPT), f"OECD {m['id']}")
            obs = parse_sdmx(json.loads(raw))
            by: dict[str, dict] = {}
            for o in obs:
                area = o["dims"].get("REF_AREA", (None, None))
                skey = tuple(sorted((k, v[0]) for k, v in o["dims"].items() if k != "TIME_PERIOD"))
                c = by.setdefault(area[0], {"name": area[1], "series": {}})
                c["series"].setdefault(skey, {})[o["period"]] = o["value"]
            lasts = []
            for iso3, c in by.items():
                if countries and iso3 not in countries:
                    continue
                pts = max(c["series"].values(), key=len)            # a wildcard key may answer several series: the fullest one
                hist = History(run, f"{ident(m['id'])}-{iso3}").merge(pts, keep=keep, key="PERIOD")
                if not hist:
                    continue
                newer = newer or hist[-1]["PERIOD"] != (known.get(m["id"]) or {}).get("last", {}).get(iso3)
                lasts.append((iso3, hist[-1]["PERIOD"], hist[-4]["PERIOD"] if len(hist) > 3 else hist[0]["PERIOD"]))
                sid = f"OECDS-{iso3}-{ident(m['id'])}"
                latest, prev = hist[-1], (hist[-2] if len(hist) > 1 else None)
                flow = m["dataflow"].split(",")[1]
                series.append({"SERIES_ID": sid, "NAME": f"{c['name']} · {m['title']}", "OECD_COUNTRY": f"OECD-{iso3}", "ISO3": iso3,
                               "COUNTRY_NAME": c["name"], "MEASURE": m["id"], "TITLE": m["title"], "UNIT": m.get("unit"),
                               "DATAFLOW": m["dataflow"], "FREQUENCY": m.get("frequency"), "LATEST": latest["VALUE"],
                               "LATEST_PERIOD": latest["PERIOD"], "PREVIOUS": prev["VALUE"] if prev else None,
                               "PREVIOUS_PERIOD": prev["PERIOD"] if prev else None,
                               "CHANGE": change(latest["VALUE"], prev["VALUE"] if prev else None), "HISTORY": hist,
                               "PROVENANCE": provenance(run, "oecd", f"OECD {flow}",
                                                        f"https://data-explorer.oecd.org/?lc=en&df[ds]=dsDisseminateFinalDMZ&df[id]={urllib.parse.quote(flow)}")})
                p = profiles.setdefault(iso3, self._country(run, iso3, c["name"]))
                p[m["field"]] = latest["VALUE"]
                p[m["field"] + "_PERIOD"] = latest["PERIOD"]
                p["MEASURES"].append({"MEASURE": m["id"], "TITLE": m["title"], "UNIT": m.get("unit"), "LATEST": latest["VALUE"],
                                      "PERIOD": latest["PERIOD"], "CHANGE": change(latest["VALUE"], prev["VALUE"] if prev else None),
                                      "OECD_SERIES": sid})
            if lasts:
                known[m["id"]] = {"last": {i: p for i, p, _ in lasts}, "restart": min(r for _, _, r in lasts)}
        if not series:
            raise FeedError("the OECD answered no observation for the selection: check config/feeds/oecd.yaml")
        if not newer and run.state.get("lastRun") and not run.since:
            run.log("  no newer period since the last run")
            return
        for p in profiles.values():
            p["MEASURE_COUNT"] = len(p["MEASURES"])
        run.emit("oecd-country", profiles.values())
        run.emit("oecd-series", series)
        for d in ("oecd-country", "oecd-series"):
            run.mark(d, run.business_date)

    def _country(self, run, iso3: str, name: str) -> dict:
        p = {"COUNTRY_ID": f"OECD-{iso3}", "NAME": name, "ISO3": iso3, "COUNTRY_NAME": name}
        for m in run.option("measures", []):
            p[m["field"]] = None
            p[m["field"] + "_PERIOD"] = None
        p["MEASURES"] = []
        p["PROVENANCE"] = provenance(run, "oecd", "OECD Data Explorer (SDMX)", "https://data-explorer.oecd.org/")
        return p
