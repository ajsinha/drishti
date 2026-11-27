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
year, for the world-development pack. It is the ONE fetcher of WDI: the world-economy pack reads its output (publicsector.wdi_rows).
Incremental: the WDI source's last-updated date (one call) is compared with the last run's; unchanged (and no new code or earlier year
asked for), nothing is emitted. Emits the development-profile (one a country: the latest value of each headline indicator) and the
development-series (one a country, or a World Bank aggregate, and indicator: the history by year) kinds. The series cover the headline
indicators and every `kind: wdi` code of options.series-from (tools/realdata/sources.yaml), back to that file's first year."""
from __future__ import annotations

from pathlib import Path

import yaml

from feedbase import Feed, FeedError
from publicsector import change, ident, need, number, provenance

API = "https://api.worldbank.org/v2"
ROOT = Path(__file__).resolve().parents[3]


class WorldDevelopmentFeed(Feed):
    def plan(self, run):
        first, last = self._years(run)
        return [f"{API}/sources/2 (last updated)", f"{API}/country (economies and aggregates)"] + \
            [f"{API}/country/{self._where(run)}/indicator/{i['code']}?date={first}:{last}" for i in self._indicators(run)]

    def pull(self, run):
        src = need(run.get_json(f"{API}/sources/2?format=json"), "World Bank sources")
        updated = ((src[1] or [{}])[0] if isinstance(src, list) and len(src) > 1 else {}).get("lastupdated")
        first, last = self._years(run)
        indicators = self._indicators(run)
        codes = sorted(i["code"] for i in indicators)
        if updated and run.state.get("lastupdated") == updated and not run.since \
                and set(codes) <= set(run.state.get("series-codes") or []) and int(run.state.get("first-year") or 9999) <= first:
            run.log(f"  WDI unchanged since {updated}: nothing new")
            return
        countries, aggregates = self._countries(run)
        series, profiles = [], {}
        for ind in indicators:
            meta = self._indicator(run, ind["code"])
            by: dict[str, list] = {}
            for r in self._data(run, ind["code"], first, last):
                iso3, v = r.get("countryiso3code"), number(r.get("value"))
                if (iso3 in countries or iso3 in aggregates) and v is not None:
                    by.setdefault(iso3, []).append({"YEAR": str(r["date"]), "VALUE": v})
            for iso3, hist in by.items():
                hist.sort(key=lambda h: h["YEAR"])
                c = countries.get(iso3) or aggregates[iso3]
                latest, prev = hist[-1], (hist[-2] if len(hist) > 1 else None)
                sid = f"WDIS-{iso3}-{ident(ind['code'])}"
                unit = ind.get("unit") or meta["unit"] or "as published"
                series.append({
                    "SERIES_ID": sid, "NAME": f"{c['name']} · {meta['name']}", "WDI_PROFILE": f"WDI-{iso3}" if iso3 in countries else None,
                    "ISO3": iso3, "COUNTRY_NAME": c["name"], "INDICATOR_CODE": ind["code"], "INDICATOR": meta["name"], "UNIT": unit,
                    "DEFINITION": meta["note"], "LATEST": latest["VALUE"], "LATEST_YEAR": latest["YEAR"],
                    "PREVIOUS": prev["VALUE"] if prev else None, "PREVIOUS_YEAR": prev["YEAR"] if prev else None,
                    "CHANGE": change(latest["VALUE"], prev["VALUE"] if prev else None), "HISTORY": hist,
                    "PROVENANCE": provenance(run, "wdi", f"World Development Indicators (updated {updated or 'unknown'})",
                                             f"https://data.worldbank.org/indicator/{ind['code']}?locations={c['iso2']}")})
                if not ind.get("field") or iso3 not in countries:
                    continue                    # a series-only code (options.series-from) or an aggregate: no profile field
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
        run.state["series-codes"] = codes           # the codes this output answers for (publicsector.wdi_rows reads them)
        run.state["first-year"] = first
        for d in ("development-profile", "development-series"):
            run.mark(d, run.business_date)

    @staticmethod
    def _series_from(run) -> dict:
        """options.series-from (a sources.yaml, relative to the repository): its indicators and years; {} when not set."""
        rel = run.option("series-from")
        if not rel:
            return {}
        p = Path(rel) if Path(rel).is_absolute() else ROOT / rel
        try:
            return yaml.safe_load(p.read_text(encoding="utf-8")) or {}
        except OSError as e:
            raise FeedError(f"options.series-from {rel}: {e}") from e

    def _indicators(self, run) -> list[dict]:
        """The headline indicators (options.indicators: series and the profile's fields), then every `kind: wdi` code of
        options.series-from not among them (series only; the unit is the World Bank's unless the file's value is unscaled)."""
        out = [dict(i) for i in run.option("indicators", [])]
        have = {i["code"] for i in out}
        for i in self._series_from(run).get("indicators") or []:
            if i.get("kind") == "wdi" and i["code"] not in have:
                have.add(i["code"])
                out.append({"code": i["code"], "unit": i.get("unit") if float(i.get("scale", 1)) == 1 else None})
        return out

    def _years(self, run) -> tuple[int, int]:
        """(first, last): options.years back from the business year, reaching series-from's years.first when that is earlier."""
        last = run.business_date.year
        first = last - int(run.option("years", 15)) + 1
        floor = (self._series_from(run).get("years") or {}).get("first")
        return (min(first, int(floor)) if floor else first), last

    @staticmethod
    def _where(run) -> str:
        c = run.option("countries", "all")
        return "all" if c == "all" else ";".join(list(c) + list(run.option("aggregates") or []))

    def _profile(self, run, c: dict, updated) -> dict:
        p = {"PROFILE_ID": f"WDI-{c['id']}", "NAME": c["name"], "ISO3": c["id"], "ISO2": c["iso2"], "COUNTRY_NAME": c["name"],
             "REGION": c["region"], "INCOME_GROUP": c["income"], "CAPITAL": c["capital"] or None}
        for ind in run.option("indicators", []):
            p[ind["field"]] = None
        p["INDICATORS"] = []
        p["PROVENANCE"] = provenance(run, "wdi", f"World Development Indicators (updated {updated or 'unknown'})",
                                     f"https://data.worldbank.org/country/{c['iso2']}")
        return p

    def _countries(self, run) -> tuple[dict[str, dict], dict[str, dict]]:
        """(economies, the aggregates options.aggregates names), by ISO3: an economy has a profile and series, an aggregate (the
        world, a region, an income group) series only."""
        wanted, agg = run.option("countries", "all"), set(run.option("aggregates") or [])
        data = need(run.get_json(f"{API}/country?format=json&per_page=400"), "World Bank countries")
        out, groups = {}, {}
        for c in (data[1] if isinstance(data, list) and len(data) > 1 else []) or []:
            rec = {"id": c["id"], "iso2": c.get("iso2Code"), "name": c.get("name"), "region": (c.get("region") or {}).get("value"),
                   "income": (c.get("incomeLevel") or {}).get("value"), "capital": c.get("capitalCity")}
            if (c.get("region") or {}).get("value") in (None, "", "Aggregates"):
                if c["id"] in agg:
                    groups[c["id"]] = rec
                continue
            if wanted != "all" and c["id"] not in wanted:
                continue
            out[c["id"]] = rec
        return out, groups

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
