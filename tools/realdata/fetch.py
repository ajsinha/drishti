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
indicator, per IMF series and per Factbook file, with a pause between requests; files already in the cache are kept unless
--refresh is given. The raw files are stored exactly as served, so a run can be repeated offline with `normalise`.

Cache layout:  wb_country.json  wb/<code>.json  imf/<code>.json  imf_indicators.json  undp_hdi.csv
               factbook/<gec>.json  factbook_tree.json  fetch.json (when and what was retrieved)
"""
from __future__ import annotations

import json
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

UA = "drishti-realdata/1.0 (public-data demo loader; contact ajsinha@gmail.com)"


class Fetcher:
    def __init__(self, cache: Path, cfg: dict, pause: float = 0.4, refresh: bool = False, log=print):
        self.cache, self.cfg, self.pause, self.refresh, self.log = cache, cfg, pause, refresh, log
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
        api, yrs = self.cfg["sources"]["wdi"]["api"], self.cfg["years"]
        self.get(f"{api}/country?format=json&per_page=400", self.cache / "wb_country.json")
        for ind in self.cfg["indicators"]:
            if ind["kind"] not in ("wdi", "wgi"):
                continue
            src = "&source=3" if ind["kind"] == "wgi" else ""
            url = f"{api}/country/all/indicator/{ind['code']}?format=json&date={yrs['first']}:{yrs['last']}&per_page=20000{src}"
            self.log(f"worldbank {ind['code']}")
            self.get(url, self.cache / "wb" / f"{ind['code']}.json")

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
        self.imf()
        self.undp()
        self.factbook()
        meta = self.cache / "fetch.json"
        old = json.loads(meta.read_text()) if meta.exists() else {}
        old.update(self.retrieved)
        meta.write_text(json.dumps(old, indent=1, sort_keys=True), encoding="utf-8")
