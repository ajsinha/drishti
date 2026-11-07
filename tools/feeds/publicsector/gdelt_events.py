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

"""The gdelt-events feed: the GDELT 1.0 daily event files (one zipped tab-separated file a day, every event the world's news reported
that day, coded with CAMEO), for the gdelt-events pack (config/feeds/gdelt-events.yaml). Incremental: only the days after the last one
processed (at most `backfill-days`) are downloaded; each day is reduced at once to per-country aggregates (history/days/<day>.json),
and the raw file is not kept. Writes the event-country kind (one a country: the latest day's events, tone and Goldstein scale, the
share of conflict, the events by CAMEO category, the daily history over `history-days`, the most-mentioned stories' links)."""
from __future__ import annotations

import io
import json
import zipfile
from datetime import date, timedelta
from pathlib import Path

from base import FeedError, PublicFeed

DAILY = "https://data.gdeltproject.org/events/{day}.export.CSV.zip"
# the GDELT 1.0 event record's columns (the codebook, 58 tab-separated fields since 2013-04-01)
COL = {"ACTOR1_COUNTRY": 7, "ROOT_CODE": 28, "QUAD_CLASS": 29, "GOLDSTEIN": 30, "MENTIONS": 31, "TONE": 34, "SOURCE_URL": 57}
CAMEO_ROOT = {"01": "Make public statement", "02": "Appeal", "03": "Express intent to cooperate", "04": "Consult", "05": "Engage in diplomatic cooperation",
              "06": "Engage in material cooperation", "07": "Provide aid", "08": "Yield", "09": "Investigate", "10": "Demand", "11": "Disapprove",
              "12": "Reject", "13": "Threaten", "14": "Protest", "15": "Exhibit force posture", "16": "Reduce relations", "17": "Coerce",
              "18": "Assault", "19": "Fight", "20": "Use unconventional mass violence"}
NAMES = json.loads((Path(__file__).with_name("countries.json")).read_text(encoding="utf-8"))   # ISO3 -> name (Natural Earth, public domain)


def aggregate(lines, max_rows: int | None = None, top: int = 5) -> dict:
    """One day's event rows -> {ISO3: {events, mentions, tone_w, goldstein, quad{1..4}, cats{root: [events, tone_w, mentions]}, top[...]}}.
    Tone is weighted by mentions (a widely reported event counts more); events whose first actor has no country code are left out."""
    out: dict[str, dict] = {}
    for n, line in enumerate(lines):
        if max_rows and n >= max_rows:
            break
        c = line.rstrip("\n").split("\t")
        if len(c) < 58:
            continue
        iso3 = c[COL["ACTOR1_COUNTRY"]]
        if iso3 not in NAMES:
            continue
        try:
            mentions = max(int(c[COL["MENTIONS"]] or 0), 1)
            tone = float(c[COL["TONE"]] or 0)
            gold = float(c[COL["GOLDSTEIN"]] or 0)
        except ValueError:
            continue
        a = out.setdefault(iso3, {"events": 0, "mentions": 0, "tone_w": 0.0, "goldstein": 0.0, "quad": {"1": 0, "2": 0, "3": 0, "4": 0},
                                  "cats": {}, "top": []})
        a["events"] += 1
        a["mentions"] += mentions
        a["tone_w"] += tone * mentions
        a["goldstein"] += gold
        q = c[COL["QUAD_CLASS"]]
        if q in a["quad"]:
            a["quad"][q] += 1
        root = c[COL["ROOT_CODE"]].zfill(2)
        k = a["cats"].setdefault(root, [0, 0.0, 0])
        k[0] += 1
        k[1] += tone * mentions
        k[2] += mentions
        url = c[COL["SOURCE_URL"]]
        if url.startswith("http"):
            a["top"].append([mentions, url, root])
    for a in out.values():
        seen, best = set(), []
        for m, url, root in sorted(a["top"], key=lambda t: -t[0]):
            if url not in seen:
                seen.add(url)
                best.append([m, url, root])
            if len(best) >= top:
                break
        a["top"] = best
    return out


class GdeltEvents(PublicFeed):
    name = "gdelt-events"

    def pull(self, state: dict) -> dict[str, list[dict]]:
        sel = self.selection
        days_dir = Path(state["dir"]) / "history" / "days"
        last_day = date.fromisoformat(self.as_of) - timedelta(days=1)          # a day's file is complete the morning after
        backfill = int(sel.get("backfill-days", 7))
        done = state.get("lastDay") if not state.get("force") else None
        start = max(date.fromisoformat(done) + timedelta(days=1), last_day - timedelta(days=backfill - 1)) if done else last_day - timedelta(days=backfill - 1)
        fetched = []
        d = start
        while d <= last_day:
            day = d.strftime("%Y%m%d")
            try:
                raw = self.fetch.raw(DAILY.format(day=day))
            except FeedError as e:
                self.log(f"  {day}: {e}")            # GDELT skips a day now and then: the next pull tries it no more
                d += timedelta(days=1)
                continue
            with zipfile.ZipFile(io.BytesIO(raw)) as z:
                name = z.namelist()[0]
                with z.open(name) as fh:
                    agg = aggregate(io.TextIOWrapper(fh, encoding="utf-8", errors="replace"), sel.get("rows-per-day"), int(sel.get("top-stories", 5)))
            days_dir.mkdir(parents=True, exist_ok=True)
            (days_dir / f"{d.isoformat()}.json").write_text(json.dumps(agg, separators=(",", ":")) + "\n", encoding="utf-8")
            fetched.append(d.isoformat())
            d += timedelta(days=1)
        if not fetched:
            if done:
                self.log("  no new GDELT day since the last pull")
                return {}
            raise FeedError("no GDELT daily file could be read for the backfill window")
        state["lastDay"] = fetched[-1]
        state.pop("force", None)
        window = int(sel.get("history-days", 30))
        floor = (date.fromisoformat(fetched[-1]) - timedelta(days=window - 1)).isoformat()
        days = {}
        for f in sorted(days_dir.glob("*.json")):
            if f.stem < floor:
                f.unlink()                            # outside the window: the feed's folder stays small
                continue
            days[f.stem] = json.loads(f.read_text(encoding="utf-8"))
        return {"event-country": self._docs(days, fetched[-1], int(sel.get("min-events", 20)))}

    def _docs(self, days: dict, latest: str, min_events: int) -> list[dict]:
        today_ = days.get(latest) or {}
        docs = []
        for iso3, a in today_.items():
            if a["events"] < min_events:
                continue
            hist, ev30, tw30, m30 = [], 0, 0.0, 0
            for day in sorted(days):
                x = days[day].get(iso3)
                if x:
                    hist.append({"DATE": day, "EVENTS": x["events"], "AVG_TONE": round(x["tone_w"] / x["mentions"], 4)})
                    ev30 += x["events"]
                    tw30 += x["tone_w"]
                    m30 += x["mentions"]
            tone = round(a["tone_w"] / a["mentions"], 4)
            tone30 = round(tw30 / m30, 4) if m30 else None
            q = a["quad"]
            cats = sorted(a["cats"].items(), key=lambda kv: -kv[1][0])
            docs.append({
                "EVENT_COUNTRY_ID": f"GDELT-{iso3}", "NAME": NAMES[iso3], "ISO3": iso3, "COUNTRY_NAME": NAMES[iso3], "DATE": latest,
                "EVENTS": a["events"], "MENTIONS": a["mentions"], "AVG_TONE": tone, "AVG_GOLDSTEIN": round(a["goldstein"] / a["events"], 4),
                "CONFLICT_SHARE": round((q["3"] + q["4"]) / a["events"] * 100, 2), "COOPERATION_SHARE": round((q["1"] + q["2"]) / a["events"] * 100, 2),
                "EVENTS_WINDOW": ev30, "AVG_TONE_WINDOW": tone30, "TONE_CHANGE": round(tone - tone30, 4) if tone30 is not None else None,
                "WINDOW_DAYS": len(hist),
                "BY_CATEGORY": [{"CODE": k, "CATEGORY": CAMEO_ROOT.get(k, k), "EVENTS": v[0], "AVG_TONE": round(v[1] / v[2], 4) if v[2] else None}
                                for k, v in cats],
                "HISTORY": hist,
                "TOP_STORIES": [{"MENTIONS": m, "URL": url, "CATEGORY": CAMEO_ROOT.get(root, root)} for m, url, root in a["top"]],
                "PROVENANCE": self.provenance(dataset=f"GDELT 1.0 daily events {latest}", url="https://www.gdeltproject.org/data.html")})
        return docs
