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

"""Downloads the raw public data into a cache folder outside the repository (never committed). One request per World Bank
governance indicator, per IMF series and per Factbook file (the development indicators, WDI, are read from the world-development
feed's output, never downloaded here: that feed is their one fetcher), with a pause between requests; files already in the cache are kept unless
--refresh is given. The raw files are stored exactly as served, so a run can be repeated offline with `normalise`.

Cache layout:  wb_country.json  wb/<code>.json  imf/<code>.json  imf_indicators.json  undp_hdi.csv
               factbook/<gec>.json  factbook_tree.json  fetch.json (when and what was retrieved)
"""
from __future__ import annotations

import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "feeds"))
from pullkit import user_agent  # noqa: E402  (the one User-Agent of every feed: tools/feeds/pullkit.py)
UA = user_agent()   # DRISHTI_FEEDS_USER_AGENT, else the feeds' default


def _publicsector():
    """tools/feeds/sources/publicsector.py (wdi_rows), by its path: that folder is not put on sys.path, where its feed modules would
    shadow the pack generators' own modules of the same names."""
    if "publicsector" in sys.modules:
        return sys.modules["publicsector"]
    import importlib.util
    spec = importlib.util.spec_from_file_location("publicsector", Path(__file__).resolve().parents[1] / "feeds" / "sources" / "publicsector.py")
    mod = importlib.util.module_from_spec(spec)
    sys.modules["publicsector"] = mod
    spec.loader.exec_module(mod)
    return mod


class Fetcher:
    def __init__(self, cache: Path, cfg: dict, pause: float = 0.4, refresh: bool = False, log=print, no_imf: bool = False):
        self.cache, self.cfg, self.pause, self.refresh, self.log, self.no_imf = cache, cfg, pause, refresh, log, no_imf
        self.retrieved: dict[str, str] = {}
        cache.mkdir(parents=True, exist_ok=True)

    def get(self, url: str, dest: Path, binary: bool = False) -> bytes | None:
        if dest.exists() and not self.refresh:
            return dest.read_bytes()
        for attempt in range(4):
            try:
                req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "application/json, text/csv, */*"})
                with urllib.request.urlopen(req, timeout=90) as r:
                    body = r.read()
                dest.parent.mkdir(parents=True, exist_ok=True)
                dest.write_bytes(body)
                self.retrieved[str(dest.relative_to(self.cache))] = datetime.now(timezone.utc).strftime("%Y-%m-%d")
                time.sleep(self.pause)
                return body
            except (urllib.error.URLError, TimeoutError) as e:
                self.log(f"  retry {attempt + 1} {url}: {e}")
                time.sleep(2 * (attempt + 1))
        self.log(f"  FAILED {url}")
        return None

    # ---- World Bank
    def worldbank(self) -> None:
        """The country list and the governance indicators (WGI) from the World Bank's API; the development indicators (WDI) from the
        world-development feed's latest output (their one fetcher), written as wb/<code>.json in the API's shape."""
        api, yrs = self.cfg["sources"]["wdi"]["api"], self.cfg["years"]
        self.get(f"{api}/country?format=json&per_page=400", self.cache / "wb_country.json")
        self.wdi([i["code"] for i in self.cfg["indicators"] if i["kind"] == "wdi"], yrs["first"], yrs["last"])
        for ind in self.cfg["indicators"]:
            if ind["kind"] != "wgi":
                continue
            url = f"{api}/country/all/indicator/{ind['code']}?format=json&date={yrs['first']}:{yrs['last']}&per_page=20000&source=3"
            self.log(f"worldbank {ind['code']}")
            self.get(url, self.cache / "wb" / f"{ind['code']}.json")

    def wdi(self, codes: list[str], first: int, last: int) -> None:
        """wb/<code>.json for each WDI code from the world-development feed's output, as the API answers ([{page, pages, lastupdated},
        rows]), so the normaliser reads it unchanged. No output: the run fails in words (run feed run world-development first)."""
        if not codes:
            return
        PS = _publicsector()
        found: dict = {}
        rows = PS.wdi_rows(codes, first, last, out=found)
        missing = [c for c in codes if c not in rows]
        if missing:
            self.log(f"  WDI: the world-development output of {found['date']} has no {', '.join(missing)}: left out "
                     f"({PS.WDI_FIRST}; it pulls every kind: wdi code of tools/realdata/sources.yaml)")
        if found.get("first") and int(found["first"]) > first:
            self.log(f"  WDI: the world-development output starts in {found['first']}, not {first}: the years before are not in it")
        self.log(f"worldbank WDI: {len(rows)} indicator(s) from the world-development output of {found['date']}")
        for code, r in rows.items():
            dest = self.cache / "wb" / f"{code}.json"
            dest.parent.mkdir(parents=True, exist_ok=True)
            head = {"page": 1, "pages": 1, "per_page": len(r), "total": len(r), "sourceid": "2", "lastupdated": found["lastupdated"]}
            dest.write_text(json.dumps([head, r], separators=(",", ":")), encoding="utf-8")
            self.retrieved[str(dest.relative_to(self.cache))] = found["date"]

    # ---- IMF
    def imf(self) -> None:
        api = self.cfg["sources"]["imf"]["api"]
        self.get(f"{api}/indicators", self.cache / "imf_indicators.json")
        codes = {i["code"] for i in self.cfg["indicators"] if i["kind"] == "imf"} | {f["code"] for f in self.cfg["forecast"]}
        for c in sorted(codes):
            self.log(f"imf {c}")
            self.get(f"{api}/{c}", self.cache / "imf" / f"{c}.json")

    # ---- UNDP
    def undp(self) -> None:
        self.log("undp hdi")
        self.get(self.cfg["sources"]["undp"]["csv"], self.cache / "undp_hdi.csv")

    # ---- Factbook (a pinned commit of the mirror, so a run is repeatable)
    def factbook(self) -> None:
        s = self.cfg["sources"]["factbook"]
        sha = json.loads(self.get(f"{s['api']}/commits/master", self.cache / "factbook_commit.json"))["sha"]
        tree = json.loads(self.get(f"{s['api']}/git/trees/{sha}?recursive=1", self.cache / "factbook_tree.json"))
        files = [t["path"] for t in tree["tree"] if t["path"].endswith(".json") and t["path"].count("/") == 1
                 and t["path"].split("/")[0] not in ("meta", "world", "oceans", "antarctica") or t["path"] == "world/xx.json"]
        self.log(f"factbook {len(files)} files @ {sha[:10]}")
        for p in files:
            gec = p.split("/")[1][:-5]
            self.get(f"{s['raw']}/{sha}/{p}", self.cache / "factbook" / f"{gec}.json")
        (self.cache / "factbook_ref.json").write_text(json.dumps({"commit": sha, "files": files}), encoding="utf-8")

    def all(self) -> None:
        self.worldbank()
        if not self.no_imf:                  # --no-imf: the IMF is never contacted
            self.imf()
        self.undp()
        self.factbook()
        meta = self.cache / "fetch.json"
        old = json.loads(meta.read_text()) if meta.exists() else {}
        old.update(self.retrieved)
        meta.write_text(json.dumps(old, indent=1, sort_keys=True), encoding="utf-8")
