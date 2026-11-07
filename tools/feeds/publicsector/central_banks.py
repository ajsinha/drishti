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

"""The central-banks feed: FRED series (Federal Reserve Bank of St. Louis, API key in DRISHTI_FRED_API_KEY) and the Bank of Canada's
Valet API, for the central-banks pack (config/feeds/central-banks.yaml). Incremental: each series is asked only from a few days before
its last observation; the history window is kept in the feed's folder (history/<series>.json). Writes the rate-series kind (one a
series: the latest value, the change, the history) and the central-bank kind (one a bank: its policy rate, its last move and its series)."""
from __future__ import annotations

import json
import os
import urllib.parse
from datetime import date, timedelta
from pathlib import Path

from base import FeedError, PublicFeed, change, ident, number

FRED = "https://api.stlouisfed.org/fred"
VALET = "https://www.bankofcanada.ca/valet"
OVERLAP_DAYS = 10            # revised observations: ask again from this many days before the last one


class CentralBanks(PublicFeed):
    name = "central-banks"

    def pull(self, state: dict) -> dict[str, list[dict]]:
        sel = self.selection
        window = int(sel.get("history-days", 730))
        floor = (date.fromisoformat(self.as_of) - timedelta(days=window)).isoformat()
        home = Path(state["dir"]) / "history"
        known = state.setdefault("series", {})
        key = os.environ.get((self._key() or {}).get("env", ""), "")
        series = sel.get("series") or []
        new_obs, docs = 0, []
        boc = [s for s in series if s["source"] == "boc"]
        boc_data = self._valet(boc, known, floor) if boc else {}
        for s in series:
            sid = f"CBSER-{s['source'].upper()}-{ident(s['code'])}"
            last = (known.get(sid) or {}).get("last")
            start = max(floor, (date.fromisoformat(last) - timedelta(days=OVERLAP_DAYS)).isoformat()) if last and not state.get("force") else floor
            if s["source"] == "fred":
                meta, obs = self._fred(s["code"], start, key)
            elif s["source"] == "boc":
                meta, obs = boc_data.get(s["code"], ({}, []))
            else:
                raise FeedError(f"series {s['code']}: source '{s['source']}' is neither fred nor boc")
            hist = self._merge(home / f"{sid}.json", obs, floor)
            if not hist:
                self.log(f"  {sid}: no observation in the window")
                continue
            if hist[-1]["DATE"] != last:
                new_obs += 1
            known[sid] = {"last": hist[-1]["DATE"]}
            docs.append(self._series_doc(sid, s, meta, hist))
        if not docs:
            raise FeedError("no series answered: check the keys and config/feeds/central-banks.yaml")
        if new_obs == 0 and state.get("lastRun") and not state.get("force"):
            self.log("  no new observation since the last pull")
            return {}
        state.pop("force", None)
        banks = [self._bank_doc(b, docs) for b in sel.get("banks") or []]
        return {"rate-series": docs, "central-bank": banks}

    # -- sources
    def _key(self):
        for k in self.spec.get("keys") or []:
            if k.get("for") == "fred":
                return k
        return None

    def _fred(self, code: str, start: str, key: str):
        q = urllib.parse.urlencode({"series_id": code, "api_key": key, "file_type": "json"})
        meta = (self.fetch.json(f"{FRED}/series?{q}").get("seriess") or [{}])[0]
        obs = self.fetch.json(f"{FRED}/series/observations?{q}&observation_start={start}").get("observations") or []
        return ({"title": meta.get("title"), "unit": meta.get("units"), "frequency": meta.get("frequency"),
                 "description": " ".join((meta.get("notes") or "").split())[:600] or None, "source": "fred",
                 "url": f"https://fred.stlouisfed.org/series/{code}"},
                [{"DATE": o["date"], "VALUE": number(o.get("value"))} for o in obs])

    def _valet(self, wanted: list[dict], known: dict, floor: str) -> dict:
        lasts = [(known.get(f"CBSER-BOC-{ident(s['code'])}") or {}).get("last") for s in wanted]
        start = floor
        if all(lasts):
            start = max(floor, (date.fromisoformat(min(lasts)) - timedelta(days=OVERLAP_DAYS)).isoformat())
        codes = ",".join(s["code"] for s in wanted)
        data = self.fetch.json(f"{VALET}/observations/{codes}/json?start_date={start}")
        detail = data.get("seriesDetail") or {}
        out = {}
        for s in wanted:
            d = detail.get(s["code"]) or {}
            meta = {"title": d.get("label"), "unit": (d.get("metadata") or {}).get("unit"), "frequency": (d.get("metadata") or {}).get("frequency"),
                    "description": d.get("description"), "source": "boc", "url": f"https://www.bankofcanada.ca/valet/series/{s['code']}"}
            obs = [{"DATE": o["d"], "VALUE": number((o.get(s["code"]) or {}).get("v"))} for o in data.get("observations") or [] if s["code"] in o]
            out[s["code"]] = (meta, obs)
        return out

    @staticmethod
    def _merge(file: Path, obs: list[dict], floor: str) -> list[dict]:
        """The kept history with the new observations (a revised one replaces the old), trimmed to the window."""
        try:
            kept = {h["DATE"]: h["VALUE"] for h in json.loads(file.read_text(encoding="utf-8"))}
        except (OSError, ValueError):
            kept = {}
        for o in obs:
            if o["VALUE"] is not None:
                kept[o["DATE"]] = o["VALUE"]
        hist = [{"DATE": d, "VALUE": v} for d, v in sorted(kept.items()) if d >= floor]
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(json.dumps(hist, separators=(",", ":")) + "\n", encoding="utf-8")
        return hist

    # -- documents
    def _series_doc(self, sid: str, s: dict, meta: dict, hist: list[dict]) -> dict:
        latest, prev = hist[-1], (hist[-2] if len(hist) > 1 else None)
        year_ago = (date.fromisoformat(latest["DATE"]) - timedelta(days=365)).isoformat()
        then = next((h for h in reversed(hist) if h["DATE"] <= year_ago), None)
        values = [h["VALUE"] for h in hist]
        src = self.source(s["source"])
        title = s.get("title") or meta.get("title") or s["code"]
        return {"SERIES_ID": sid, "NAME": title, "SOURCE": src.get("title"), "CODE": s["code"], "BANK": f"CBANK-{s['bank']}",
                "TITLE": title, "DESCRIPTION": meta.get("description"), "UNIT": s.get("unit") or meta.get("unit"),
                "FREQUENCY": meta.get("frequency"), "LATEST": latest["VALUE"], "LATEST_DATE": latest["DATE"],
                "PREVIOUS": prev["VALUE"] if prev else None, "PREVIOUS_DATE": prev["DATE"] if prev else None,
                "CHANGE": change(latest["VALUE"], prev["VALUE"] if prev else None),
                "CHANGE_1Y": change(latest["VALUE"], then["VALUE"] if then else None),
                "HIGH": max(values), "LOW": min(values), "OBSERVATIONS": len(hist), "HISTORY": hist,
                "PROVENANCE": self.provenance(s["source"], dataset=f"{src.get('title')}: {s['code']}", url=meta.get("url"))}

    def _bank_doc(self, b: dict, docs: list[dict]) -> dict:
        mine = [d for d in docs if d["BANK"] == f"CBANK-{b['id']}"]
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
                "SERIES": [{"SERIES_ID": d["SERIES_ID"], "TITLE": d["TITLE"], "LATEST": d["LATEST"], "DATE": d["LATEST_DATE"],
                            "CHANGE": d["CHANGE"], "UNIT": d["UNIT"]} for d in mine],
                "PRESS": b.get("press"),
                "PROVENANCE": self.provenance(src, dataset=f"{b['name']}: policy rate and market series", url=b.get("website"))}
