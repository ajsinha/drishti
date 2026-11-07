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

"""The world-development feed: World Bank World Development Indicators (WDI, API v2) by country and year, for the world-development
pack (config/feeds/world-development.yaml). Incremental: the WDI source's last-updated date (one call) is compared with the last
pull's; unchanged, nothing is written. Writes the development-profile (one a country, the latest value of each indicator) and the
development-series (one a country and indicator, the history by year) kinds."""
from __future__ import annotations

from datetime import date

from base import FeedError, PublicFeed, change, ident, number

API = "https://api.worldbank.org/v2"


class WorldDevelopment(PublicFeed):
    name = "world-development"

    def pull(self, state: dict) -> dict[str, list[dict]]:
        sel = self.selection
        src = self.fetch.json(f"{API}/sources/2?format=json")
        updated = ((src[1] or [{}])[0] if isinstance(src, list) and len(src) > 1 else {}).get("lastupdated")
        if updated and state.get("lastupdated") == updated and not state.get("force"):
            self.log(f"  WDI unchanged since {updated}: nothing new")
            return {}
        countries = self._countries(sel.get("countries", "all"))
        last = date.fromisoformat(self.as_of).year
        first = last - int(sel.get("years", 15)) + 1
        where = "all" if sel.get("countries", "all") == "all" else ";".join(sel["countries"])
        series, profiles = [], {}
        for ind in sel.get("indicators") or []:
            meta = self._indicator(ind["code"])
            rows = self._data(where, ind["code"], first, last)
            by: dict[str, list] = {}
            for r in rows:
                iso3 = r.get("countryiso3code")
                v = number(r.get("value"))
                if iso3 in countries and v is not None:
                    by.setdefault(iso3, []).append({"YEAR": str(r["date"]), "VALUE": v})
            for iso3, hist in by.items():
                hist.sort(key=lambda h: h["YEAR"])
                c = countries[iso3]
                latest, prev = hist[-1], (hist[-2] if len(hist) > 1 else None)
                sid = f"WDIS-{iso3}-{ident(ind['code'])}"
                series.append({
                    "SERIES_ID": sid, "NAME": f"{c['name']} · {meta['name']}", "WDI_PROFILE": f"WDI-{iso3}", "ISO3": iso3,
                    "COUNTRY_NAME": c["name"], "INDICATOR_CODE": ind["code"], "INDICATOR": meta["name"], "UNIT": ind.get("unit") or meta["unit"],
                    "DEFINITION": meta["note"], "LATEST": latest["VALUE"], "LATEST_YEAR": latest["YEAR"],
                    "PREVIOUS": prev["VALUE"] if prev else None, "PREVIOUS_YEAR": prev["YEAR"] if prev else None,
                    "CHANGE": change(latest["VALUE"], prev["VALUE"] if prev else None), "HISTORY": hist,
                    "PROVENANCE": self.provenance(dataset=f"World Development Indicators (updated {updated or 'unknown'})",
                                                  url=f"https://data.worldbank.org/indicator/{ind['code']}?locations={c['iso2']}")})
                p = profiles.setdefault(iso3, self._profile(c, updated))
                p[ind["field"]] = latest["VALUE"]
                p["INDICATORS"].append({"INDICATOR": meta["name"], "UNIT": ind.get("unit") or meta["unit"], "LATEST": latest["VALUE"],
                                        "YEAR": latest["YEAR"], "CODE": ind["code"], "WDI_SERIES": sid})
        if not series:
            raise FeedError("the World Bank answered no observation for the selection: check config/feeds/world-development.yaml")
        for p in profiles.values():
            p["DATA_YEAR"] = max((i["YEAR"] for i in p["INDICATORS"]), default=None)
            p["INDICATOR_COUNT"] = len(p["INDICATORS"])
        state["lastupdated"] = updated
        state.pop("force", None)
        return {"development-profile": list(profiles.values()), "development-series": series}

    def _profile(self, c: dict, updated) -> dict:
        p = {"PROFILE_ID": f"WDI-{c['id']}", "NAME": c["name"], "ISO3": c["id"], "ISO2": c["iso2"], "COUNTRY_NAME": c["name"],
             "REGION": c["region"], "INCOME_GROUP": c["income"], "CAPITAL": c["capital"] or None}
        for ind in self.selection.get("indicators") or []:
            p[ind["field"]] = None
        p["INDICATORS"] = []
        p["PROVENANCE"] = self.provenance(dataset=f"World Development Indicators (updated {updated or 'unknown'})",
                                          url=f"https://data.worldbank.org/country/{c['iso2']}")
        return p

    def _countries(self, wanted) -> dict[str, dict]:
        """Economies only (the API's regional and income aggregates left out), by ISO3."""
        data = self.fetch.json(f"{API}/country?format=json&per_page=400")
        out = {}
        for c in (data[1] if isinstance(data, list) and len(data) > 1 else []) or []:
            if (c.get("region") or {}).get("value") in (None, "", "Aggregates"):
                continue
            if wanted != "all" and c["id"] not in wanted:
                continue
            out[c["id"]] = {"id": c["id"], "iso2": c.get("iso2Code"), "name": c.get("name"), "region": (c.get("region") or {}).get("value"),
                            "income": (c.get("incomeLevel") or {}).get("value"), "capital": c.get("capitalCity")}
        return out

    def _indicator(self, code: str) -> dict:
        data = self.fetch.json(f"{API}/indicator/{code}?format=json")
        m = (data[1] or [{}])[0] if isinstance(data, list) and len(data) > 1 else {}
        return {"name": m.get("name") or code, "unit": m.get("unit") or "", "note": " ".join((m.get("sourceNote") or "").split())[:600] or None}

    def _data(self, where: str, code: str, first: int, last: int) -> list[dict]:
        rows, page, pages = [], 1, 1
        while page <= pages:
            data = self.fetch.json(f"{API}/country/{where}/indicator/{code}?format=json&per_page=20000&date={first}:{last}&page={page}")
            if not isinstance(data, list) or len(data) < 2:
                msg = (data[0].get("message") if isinstance(data, list) and data else None) or data
                raise FeedError(f"the World Bank refused {code}: {msg}")
            pages = int(data[0].get("pages") or 1)
            rows += data[1] or []
            page += 1
        return rows
