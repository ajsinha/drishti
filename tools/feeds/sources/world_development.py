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

"""The world-development feed (config/feeds/world-development.yaml): World Bank World Development Indicators (API v2) by country and
year, for the world-development pack. Incremental: the WDI source's last-updated date (one call) is compared with the last run's;
unchanged, nothing is emitted. Emits the development-profile (one a country: the latest value of each indicator) and the
development-series (one a country and indicator: the history by year) kinds."""
from __future__ import annotations

from feedbase import Feed, FeedError
from publicsector import change, ident, need, number, provenance

API = "https://api.worldbank.org/v2"


class WorldDevelopmentFeed(Feed):
    def plan(self, run):
        last = run.business_date.year
        first = last - int(run.option("years", 15)) + 1
        return [f"{API}/sources/2 (last updated)", f"{API}/country (economies)"] + \
            [f"{API}/country/{self._where(run)}/indicator/{i['code']}?date={first}:{last}" for i in run.option("indicators", [])]

    def pull(self, run):
        src = need(run.get_json(f"{API}/sources/2?format=json"), "World Bank sources")
        updated = ((src[1] or [{}])[0] if isinstance(src, list) and len(src) > 1 else {}).get("lastupdated")
        if updated and run.state.get("lastupdated") == updated and not run.since:
            run.log(f"  WDI unchanged since {updated}: nothing new")
            return
        countries = self._countries(run)
        last = run.business_date.year
        first = last - int(run.option("years", 15)) + 1
        series, profiles = [], {}
        for ind in run.option("indicators", []):
            meta = self._indicator(run, ind["code"])
            by: dict[str, list] = {}
            for r in self._data(run, ind["code"], first, last):
                iso3, v = r.get("countryiso3code"), number(r.get("value"))
                if iso3 in countries and v is not None:
                    by.setdefault(iso3, []).append({"YEAR": str(r["date"]), "VALUE": v})
            for iso3, hist in by.items():
                hist.sort(key=lambda h: h["YEAR"])
                c = countries[iso3]
                latest, prev = hist[-1], (hist[-2] if len(hist) > 1 else None)
                sid = f"WDIS-{iso3}-{ident(ind['code'])}"
                unit = ind.get("unit") or meta["unit"]
                series.append({
                    "SERIES_ID": sid, "NAME": f"{c['name']} · {meta['name']}", "WDI_PROFILE": f"WDI-{iso3}", "ISO3": iso3,
                    "COUNTRY_NAME": c["name"], "INDICATOR_CODE": ind["code"], "INDICATOR": meta["name"], "UNIT": unit,
                    "DEFINITION": meta["note"], "LATEST": latest["VALUE"], "LATEST_YEAR": latest["YEAR"],
                    "PREVIOUS": prev["VALUE"] if prev else None, "PREVIOUS_YEAR": prev["YEAR"] if prev else None,
                    "CHANGE": change(latest["VALUE"], prev["VALUE"] if prev else None), "HISTORY": hist,
                    "PROVENANCE": provenance(run, "wdi", f"World Development Indicators (updated {updated or 'unknown'})",
                                             f"https://data.worldbank.org/indicator/{ind['code']}?locations={c['iso2']}")})
                p = profiles.setdefault(iso3, self._profile(run, c, updated))
                p[ind["field"]] = latest["VALUE"]
                p["INDICATORS"].append({"INDICATOR": meta["name"], "UNIT": unit, "LATEST": latest["VALUE"], "YEAR": latest["YEAR"],
                                        "CODE": ind["code"], "WDI_SERIES": sid})
        if not series:
            raise FeedError("the World Bank answered no observation for the selection: check config/feeds/world-development.yaml")
        for p in profiles.values():
            p["DATA_YEAR"] = max((i["YEAR"] for i in p["INDICATORS"]), default=None)
            p["INDICATOR_COUNT"] = len(p["INDICATORS"])
        run.emit("development-profile", profiles.values())
        run.emit("development-series", series)
        run.state["lastupdated"] = updated
        for d in ("development-profile", "development-series"):
            run.mark(d, run.business_date)

    @staticmethod
    def _where(run) -> str:
        c = run.option("countries", "all")
        return "all" if c == "all" else ";".join(c)

    def _profile(self, run, c: dict, updated) -> dict:
        p = {"PROFILE_ID": f"WDI-{c['id']}", "NAME": c["name"], "ISO3": c["id"], "ISO2": c["iso2"], "COUNTRY_NAME": c["name"],
             "REGION": c["region"], "INCOME_GROUP": c["income"], "CAPITAL": c["capital"] or None}
        for ind in run.option("indicators", []):
            p[ind["field"]] = None
        p["INDICATORS"] = []
        p["PROVENANCE"] = provenance(run, "wdi", f"World Development Indicators (updated {updated or 'unknown'})",
                                     f"https://data.worldbank.org/country/{c['iso2']}")
        return p

    def _countries(self, run) -> dict[str, dict]:
        """Economies only (the API's regional and income aggregates left out), by ISO3."""
        wanted = run.option("countries", "all")
        data = need(run.get_json(f"{API}/country?format=json&per_page=400"), "World Bank countries")
        out = {}
        for c in (data[1] if isinstance(data, list) and len(data) > 1 else []) or []:
            if (c.get("region") or {}).get("value") in (None, "", "Aggregates"):
                continue
            if wanted != "all" and c["id"] not in wanted:
                continue
            out[c["id"]] = {"id": c["id"], "iso2": c.get("iso2Code"), "name": c.get("name"), "region": (c.get("region") or {}).get("value"),
                            "income": (c.get("incomeLevel") or {}).get("value"), "capital": c.get("capitalCity")}
        return out

    def _indicator(self, run, code: str) -> dict:
        data = need(run.get_json(f"{API}/indicator/{code}?format=json"), f"indicator {code}")
        m = (data[1] or [{}])[0] if isinstance(data, list) and len(data) > 1 else {}
        return {"name": m.get("name") or code, "unit": m.get("unit") or "", "note": " ".join((m.get("sourceNote") or "").split())[:600] or None}

    def _data(self, run, code: str, first: int, last: int) -> list[dict]:
        rows, page, pages = [], 1, 1
        while page <= pages:
            data = need(run.get_json(f"{API}/country/{self._where(run)}/indicator/{code}?format=json&per_page=20000&date={first}:{last}&page={page}"),
                        f"indicator {code}")
            if not isinstance(data, list) or len(data) < 2:
                msg = (data[0].get("message") if isinstance(data, list) and data else None) or data
                raise FeedError(f"the World Bank refused {code}: {msg}")
            pages = int(data[0].get("pages") or 1)
            rows += data[1] or []
            page += 1
        return rows
