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

"""The oecd feed: the OECD Data Explorer's SDMX REST API (SDMX-JSON 2.0), for the oecd pack (config/feeds/oecd.yaml). One request a
measure for every selected country. Incremental: later pulls ask from three periods before each measure's last one, merged into the
kept history (history/<measure>-<country>.json); nothing newer, nothing written. Writes the oecd-country kind (one a country: the latest
of each measure) and the oecd-series kind (one a country and measure: the history)."""
from __future__ import annotations

import json
import urllib.parse
from pathlib import Path

from base import FeedError, PublicFeed, change, ident, number

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
            idx = [int(i) for i in key.split(":")]
            row = {}
            for d, i in zip(dims, idx):
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


class Oecd(PublicFeed):
    name = "oecd"

    def pull(self, state: dict) -> dict[str, list[dict]]:
        sel = self.selection
        countries = sel.get("countries") or []
        keep = int(sel.get("history-periods", 60))
        known = state.setdefault("measures", {})
        home = Path(state["dir"]) / "history"
        series, profiles, newer = [], {}, False
        for m in sel.get("measures") or []:
            start = (known.get(m["id"]) or {}).get("restart") if not state.get("force") else None
            start = start or sel.get("start-period", "2020-01")
            key = m["key"].replace("{countries}", "+".join(countries))
            url = f"{API}/{urllib.parse.quote(m['dataflow'], safe=',@')}/{key}?startPeriod={start}&dimensionAtObservation=AllDimensions"
            obs = parse_sdmx(json.loads(self.fetch.raw(url, headers={"Accept": ACCEPT})))
            by: dict[str, dict] = {}
            for o in obs:
                area = o["dims"].get("REF_AREA", (None, None))
                skey = tuple(sorted((k, v[0]) for k, v in o["dims"].items() if k != "TIME_PERIOD"))
                c = by.setdefault(area[0], {"name": area[1], "series": {}})
                c["series"].setdefault(skey, []).append((o["period"], o["value"]))
            last_periods = []
            for iso3, c in by.items():
                if countries and iso3 not in countries:
                    continue
                pts = max(c["series"].values(), key=len)            # a wildcard key may answer several series: the fullest one
                file = home / f"{ident(m['id'])}-{iso3}.json"
                hist = self._merge(file, pts, keep)
                if not hist:
                    continue
                prior = (known.get(m["id"]) or {}).get("last", {}).get(iso3)
                newer = newer or hist[-1]["PERIOD"] != prior
                last_periods.append((iso3, hist[-1]["PERIOD"], hist[-4]["PERIOD"] if len(hist) > 3 else hist[0]["PERIOD"]))
                sid = f"OECDS-{iso3}-{ident(m['id'])}"
                latest, prev = hist[-1], (hist[-2] if len(hist) > 1 else None)
                series.append({"SERIES_ID": sid, "NAME": f"{c['name']} · {m['title']}", "OECD_COUNTRY": f"OECD-{iso3}", "ISO3": iso3,
                               "COUNTRY_NAME": c["name"], "MEASURE": m["id"], "TITLE": m["title"], "UNIT": m.get("unit"),
                               "DATAFLOW": m["dataflow"], "FREQUENCY": m.get("frequency"), "LATEST": latest["VALUE"],
                               "LATEST_PERIOD": latest["PERIOD"], "PREVIOUS": prev["VALUE"] if prev else None,
                               "PREVIOUS_PERIOD": prev["PERIOD"] if prev else None,
                               "CHANGE": change(latest["VALUE"], prev["VALUE"] if prev else None), "HISTORY": hist,
                               "PROVENANCE": self.provenance(dataset=f"OECD {m['dataflow'].split(',')[1]}",
                                                             url=f"https://data-explorer.oecd.org/?lc=en&df[ds]=dsDisseminateFinalDMZ&df[id]={urllib.parse.quote(m['dataflow'].split(',')[1])}")})
                p = profiles.setdefault(iso3, self._country(iso3, c["name"]))
                p[m["field"]] = latest["VALUE"]
                p[m["field"] + "_PERIOD"] = latest["PERIOD"]
                p["MEASURES"].append({"MEASURE": m["id"], "TITLE": m["title"], "UNIT": m.get("unit"), "LATEST": latest["VALUE"],
                                      "PERIOD": latest["PERIOD"], "CHANGE": change(latest["VALUE"], prev["VALUE"] if prev else None),
                                      "OECD_SERIES": sid})
            if last_periods:
                known[m["id"]] = {"last": {i: p for i, p, _ in last_periods}, "restart": min(r for _, _, r in last_periods)}
        if not series:
            raise FeedError("the OECD answered no observation for the selection: check config/feeds/oecd.yaml")
        if not newer and state.get("lastRun") and not state.get("force"):
            self.log("  no newer period since the last pull")
            return {}
        state.pop("force", None)
        for p in profiles.values():
            p["MEASURE_COUNT"] = len(p["MEASURES"])
        return {"oecd-country": list(profiles.values()), "oecd-series": series}

    def _country(self, iso3: str, name: str) -> dict:
        p = {"COUNTRY_ID": f"OECD-{iso3}", "NAME": name, "ISO3": iso3, "COUNTRY_NAME": name}
        for m in self.selection.get("measures") or []:
            p[m["field"]] = None
            p[m["field"] + "_PERIOD"] = None
        p["MEASURES"] = []
        p["PROVENANCE"] = self.provenance(dataset="OECD Data Explorer (SDMX)", url="https://data-explorer.oecd.org/")
        return p

    @staticmethod
    def _merge(file: Path, pts: list, keep: int) -> list[dict]:
        try:
            kept = {h["PERIOD"]: h["VALUE"] for h in json.loads(file.read_text(encoding="utf-8"))}
        except (OSError, ValueError):
            kept = {}
        kept.update(dict(pts))
        hist = [{"PERIOD": k, "VALUE": v} for k, v in sorted(kept.items())][-keep:]
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(json.dumps(hist, separators=(",", ":")) + "\n", encoding="utf-8")
        return hist
