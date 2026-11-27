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

"""The central-banks feed (config/feeds/central-banks.yaml): FRED series (the Federal Reserve Bank of St. Louis; a free key in
DRISHTI_FRED_API_KEY) and the Bank of Canada's Valet API, for the central-banks pack. Incremental: each series is asked only from a few
days before its last observation, merged into its kept history (history/<series>.jsonl); no new observation anywhere, nothing emitted.
Without the FRED key the FRED series are left out, said in words, and the Bank of Canada's still run. Emits the rate-series kind (one a
series: the latest value, the changes, the history) and the central-bank kind (one a bank: its policy rate, its last move, its series)."""
from __future__ import annotations

import os
import urllib.parse
from datetime import timedelta

from feedbase import Feed, FeedError
from publicsector import History, change, ident, need, number, provenance

FRED = "https://api.stlouisfed.org/fred"
VALET = "https://www.bankofcanada.ca/valet"
OVERLAP_DAYS = 10            # revised observations: asked again from this many days before the last one


class CentralBanksFeed(Feed):
    def plan(self, run):
        return [f"{FRED}/series/observations?series_id={s['code']}" if s["source"] == "fred" else f"{VALET}/observations/{s['code']}/json"
                for s in run.option("series", [])]

    def pull(self, run):
        window = int(run.option("history-days", 730))
        floor = (run.business_date - timedelta(days=window)).isoformat()
        known = run.state.setdefault("series", {})
        env = run.spec.keys.get("api_key")
        key = os.environ.get(env or "", "")
        series = list(run.option("series", []))
        if not key:
            run.log(f"  FRED left out: set {env} (a free key: https://fred.stlouisfed.org/docs/api/api_key.html); the Bank of Canada runs")
            series = [s for s in series if s["source"] != "fred"]
        boc = [s for s in series if s["source"] == "boc"]
        boc_data = self._valet(run, boc, known, floor) if boc else {}
        new_obs, docs = 0, []
        for s in series:
            sid = f"CBSER-{s['source'].upper()}-{ident(s['code'])}"
            last = (known.get(sid) or {}).get("last")
            if s["source"] == "fred":
                start = self._start(last, floor, run)
                meta, obs = self._fred(run, s["code"], start, key)
            elif s["source"] == "boc":
                meta, obs = boc_data.get(s["code"], ({}, []))
            else:
                raise FeedError(f"series {s['code']}: source '{s['source']}' is neither fred nor boc")
            hist = History(run, sid).merge({o["DATE"]: o["VALUE"] for o in obs}, floor=floor)
            if not hist:
                run.log(f"  {sid}: no observation in the window")
                continue
            if hist[-1]["DATE"] != last:
                new_obs += 1
            known[sid] = {"last": hist[-1]["DATE"]}
            docs.append(self._series_doc(run, sid, s, meta, hist))
        if not docs:
            raise FeedError("no series answered: check the keys and config/feeds/central-banks.yaml")
        if new_obs == 0 and run.state.get("lastRun") and not run.since:
            run.log("  no new observation since the last run")
            return
        run.emit("rate-series", docs)
        run.emit("central-bank", [self._bank_doc(run, b, docs) for b in run.option("banks", [])])
        for d in ("rate-series", "central-bank"):
            run.mark(d, run.business_date)

    @staticmethod
    def _start(last, floor, run):
        if last and not run.since:
            from datetime import date
            return max(floor, (date.fromisoformat(last) - timedelta(days=OVERLAP_DAYS)).isoformat())
        return floor

    # -- the sources
    def _fred(self, run, code: str, start: str, key: str):
        q = urllib.parse.urlencode({"series_id": code, "api_key": key, "file_type": "json"})
        meta = (need(run.get_json(f"{FRED}/series?{q}", name=f"fred-series-{code}.json"), f"FRED {code}").get("seriess") or [{}])[0]
        obs = need(run.get_json(f"{FRED}/series/observations?{q}&observation_start={start}", name=f"fred-obs-{code}-{start}.json"),
                   f"FRED {code}").get("observations") or []
        return ({"title": meta.get("title"), "unit": meta.get("units"), "frequency": meta.get("frequency"),
                 "description": " ".join((meta.get("notes") or "").split())[:600] or None, "url": f"https://fred.stlouisfed.org/series/{code}"},
                [{"DATE": o["date"], "VALUE": number(o.get("value"))} for o in obs])

    def _valet(self, run, wanted: list[dict], known: dict, floor: str) -> dict:
        lasts = [(known.get(f"CBSER-BOC-{ident(s['code'])}") or {}).get("last") for s in wanted]
        start = self._start(min(lasts), floor, run) if all(lasts) else floor
        codes = ",".join(s["code"] for s in wanted)
        data = need(run.get_json(f"{VALET}/observations/{codes}/json?start_date={start}"), "Bank of Canada Valet")
        detail = data.get("seriesDetail") or {}
        out = {}
        for s in wanted:
            d = detail.get(s["code"]) or {}
            meta = {"title": d.get("label"), "unit": (d.get("metadata") or {}).get("unit"), "frequency": (d.get("metadata") or {}).get("frequency"),
                    "description": d.get("description"), "url": f"https://www.bankofcanada.ca/valet/series/{s['code']}"}
            obs = [{"DATE": o["d"], "VALUE": number((o.get(s["code"]) or {}).get("v"))} for o in data.get("observations") or [] if s["code"] in o]
            out[s["code"]] = (meta, obs)
        return out

    # -- the documents
    def _series_doc(self, run, sid: str, s: dict, meta: dict, hist: list[dict]) -> dict:
        from datetime import date
        latest, prev = hist[-1], (hist[-2] if len(hist) > 1 else None)
        year_ago = (date.fromisoformat(latest["DATE"]) - timedelta(days=365)).isoformat()
        then = next((h for h in reversed(hist) if h["DATE"] <= year_ago), None)
        values = [h["VALUE"] for h in hist]
        title = s.get("title") or meta.get("title") or s["code"]
        src = next(x for x in run.option("sources") if x["id"] == s["source"])
        return {"SERIES_ID": sid, "NAME": title, "SOURCE": src.get("title"), "CODE": s["code"], "CENTRAL_BANK": f"CBANK-{s['bank']}",
                "TITLE": title, "DESCRIPTION": meta.get("description"), "UNIT": s.get("unit") or meta.get("unit"),
                "FREQUENCY": meta.get("frequency"), "LATEST": latest["VALUE"], "LATEST_DATE": latest["DATE"],
                "PREVIOUS": prev["VALUE"] if prev else None, "PREVIOUS_DATE": prev["DATE"] if prev else None,
                "CHANGE": change(latest["VALUE"], prev["VALUE"] if prev else None),
                "CHANGE_1Y": change(latest["VALUE"], then["VALUE"] if then else None),
                "HIGH": max(values), "LOW": min(values), "OBSERVATIONS": len(hist), "HISTORY": hist,
                "PROVENANCE": provenance(run, s["source"], f"{src.get('title')}: {s['code']}", meta.get("url"))}

    def _bank_doc(self, run, b: dict, docs: list[dict]) -> dict:
        mine = [d for d in docs if d["CENTRAL_BANK"] == f"CBANK-{b['id']}"]
        src, code = b["policy-series"].split(":", 1)
        policy = next((d for d in mine if d["SERIES_ID"] == f"CBSER-{src.upper()}-{ident(code)}"), None)
        move_date = move = None
        if policy:
            h = policy["HISTORY"]
            for i in range(len(h) - 1, 0, -1):
                if h[i]["VALUE"] != h[i - 1]["VALUE"]:
                    move_date, move = h[i]["DATE"], round((h[i]["VALUE"] - h[i - 1]["VALUE"]) * 100, 2)
                    break
        return {"BANK_ID": f"CBANK-{b['id']}", "NAME": b["name"], "COUNTRY": b["country"], "COUNTRY_NAME": b.get("country-name"),
                "CURRENCY": b.get("currency"), "WEBSITE": b.get("website"),
                "POLICY_RATE": policy["LATEST"] if policy else None, "POLICY_RATE_DATE": policy["LATEST_DATE"] if policy else None,
                "POLICY_SERIES": policy["SERIES_ID"] if policy else None, "LAST_MOVE_DATE": move_date, "LAST_MOVE_BP": move,
                "POLICY_HISTORY": policy["HISTORY"] if policy else [],
                "SERIES": [{"RATE_SERIES": d["SERIES_ID"], "TITLE": d["TITLE"], "LATEST": d["LATEST"], "DATE": d["LATEST_DATE"],
                            "CHANGE": d["CHANGE"], "UNIT": d["UNIT"]} for d in mine],
                "PRESS": b.get("press"),
                "PROVENANCE": provenance(run, src, f"{b['name']}: policy rate and market series", b.get("website"))}
