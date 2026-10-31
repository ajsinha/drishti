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

"""Refresh the funds pack from its public sources: the SEC's Form N-PORT, Form N-CEN and prospectus risk/return summary data sets,
the SEC and Nasdaq Trader ticker files and GLEIF's level-2 relationship records.

    python3 tools/publicdata/funds.py status
    python3 tools/publicdata/funds.py refresh --dry-run
    python3 tools/publicdata/funds.py refresh                               the committed pack (data/testdata/funds and config/packs/funds)
    python3 tools/publicdata/funds.py refresh --offline                     rebuild the pack from the downloads only
    python3 tools/publicdata/funds.py refresh ncen rr --since 2025          more quarters of the census and the prospectus summaries
    python3 tools/publicdata/funds.py refresh --scope FUNDS:300,HOLDINGS:1000
                                                                            a widened build in <data>/publicdata/funds

Every source is openly licensed (SEC: U.S. public domain; GLEIF: CC0 1.0; the Nasdaq Trader directory is a public file) and the
built pack is committed. The downloads are large (an N-PORT quarter is about 450 MB, the four quarters of risk/return summaries about
200 MB) and stay in the download cache, <data>/publicdata/cache/funds, never in git; a file younger than --max-age hours (default a
week) is reused. SEC asks for a descriptive User-Agent with a contact and under 10 requests a second: the script uses exactly the
setting of the us-equities pack (tools/packgen/us_equities/fetch.py USER_AGENT, or DRISHTI_SEC_USER_AGENT) and one request at a time.

The N-PORT source reads the newest quarter the SEC has published (one quarter holds every fund's newest public report); ncen and rr
read the newest four quarters (a fund files its census once a year and its prospectus once a year), or the quarters from --since YEAR
(or the last --years N years). gleif reuses the gleif pack's golden copy when its cache has one. After the downloads it runs
tools/packgen/funds/make.py, then make.py --check. --scope widens the build (comma separated): FUNDS:<n> the n largest funds
(default 200), HOLDINGS:<n> the holdings kept a fund (default 600); a widened build goes to the data folder, never over the committed
pack unless --into-pack. Guide: docs/guides/DATA_GUIDE.md.
"""
from __future__ import annotations

import importlib.util
import os
import shutil
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import pdbase as B  # noqa: E402

GEN = B.ROOT / "tools" / "packgen" / "funds"
PACK = "funds"
COMMITTED = B.ROOT / "config" / "packs" / PACK
SEC_FILES = "https://www.sec.gov/files"
NPORT = SEC_FILES + "/dera/data/form-n-port-data-sets/{q}_nport.zip"
NCEN = SEC_FILES + "/dera/data/form-n-cen-data-sets/{q}_ncen.zip"
RR = SEC_FILES + "/dera/data/mutual-fund-prospectus-risk/return-summary-data-sets/{q}_rr1.zip"
GOLDEN = "https://goldencopy.gleif.org/api/v2/golden-copies/publishes/latest"


def _sec_user_agent() -> str:
    """The us-equities pack's SEC User-Agent (its fetch.py reads DRISHTI_SEC_USER_AGENT, else its default): one setting for every
    SEC download Drishti makes."""
    spec = importlib.util.spec_from_file_location("us_equities_fetch_ua", B.ROOT / "tools" / "packgen" / "us_equities" / "fetch.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod.USER_AGENT


def _quarters(first_year: int | None, n: int, today: datetime | None = None) -> list[str]:
    """The quarters to try, newest first: the n before today's (a quarter's data set is published after it ends), or every one from
    first_year."""
    t = today or datetime.now(timezone.utc)
    y, q = t.year, (t.month - 1) // 3 + 1
    out = []
    while True:
        q -= 1
        if q == 0:
            y, q = y - 1, 4
        if first_year is not None and y < first_year:
            break
        out.append(f"{y}q{q}")
        if first_year is None and len(out) >= n:
            break
        if len(out) >= 40:
            break
    return out


class Downloader:
    """Streamed GETs to the cache (an N-PORT quarter is ~450 MB: never held in memory): the SEC User-Agent, one request at a time
    with a pause, retries after 2, 4, 8 s on 429, 5xx and network errors; a file younger than --max-age is reused; --offline uses
    the cache only. A 404 is an answer (that quarter is not published), not a failure."""
    RETRY = (2, 4, 8)

    def __init__(self, run: B.Run, opener=urllib.request.urlopen, sleep=time.sleep):
        self.run, self.opener, self.sleep = run, opener, sleep
        self.ua = _sec_user_agent()
        self.downloaded, self.reused, self.failed = 0, 0, []
        self._last = 0.0

    def get(self, url: str, dest: Path, missing_ok: bool = False) -> Path | None:
        a, cache = self.run.a, self.run.cache
        if dest.is_file() and (a.offline or cache.fresh(dest, a.max_age, url)):
            self.reused += 1
            return dest
        if a.offline:
            if not missing_ok:
                self.run.log(f"  offline: not in the cache: {url}")
                self.failed.append(url)
            return None
        why = ""
        for attempt in range(len(self.RETRY) + 1):
            gap = self._last + max(a.pause, 0.15) - time.monotonic()
            if gap > 0:
                self.sleep(gap)
            self._last = time.monotonic()
            try:
                req = urllib.request.Request(url, headers={"User-Agent": self.ua, "Accept": "*/*"})
                dest.parent.mkdir(parents=True, exist_ok=True)
                part = dest.with_name(dest.name + ".part")
                import hashlib
                h, size = hashlib.sha256(), 0
                with self.opener(req, timeout=300) as r, open(part, "wb") as out:
                    for block in iter(lambda: r.read(1 << 20), b""):
                        out.write(block)
                        h.update(block)
                        size += len(block)
                part.replace(dest)
                cache.index[cache.rel(dest)] = {"url": url, "fetched": B.now(), "sha256": h.hexdigest(), "bytes": size}
                cache.save()
                self.downloaded += 1
                self.run.log(f"  {dest.relative_to(cache.folder)}: {B.human(size)}")
                return dest
            except urllib.error.HTTPError as e:
                why = f"HTTP {e.code}"
                if e.code in (403, 404) and missing_ok:
                    return None
                if e.code not in (429, 500, 502, 503, 504):
                    break
            except (urllib.error.URLError, TimeoutError, ConnectionError, OSError) as e:
                why = str(e)
            if attempt < len(self.RETRY):
                self.run.log(f"  retry {attempt + 1} {url}: {why}")
                self.sleep(self.RETRY[attempt])
        self.run.log(f"  FAILED {url}: {why}")
        self.failed.append(url)
        return None


class _Sec(B.Source):
    pack = PACK
    licence = "Public domain (U.S. Government work); SEC fair access policy"
    licence_url = "https://www.sec.gov/privacy#security"


class Tickers(_Sec):
    name, title = "tickers", "SEC ticker files: fund share classes (company_tickers_mf.json) and listed trusts (company_tickers_exchange.json)"
    url = "https://www.sec.gov/file/company-tickers"
    cache_prefixes = ("sec",)

    def plan(self, run):
        return [f"{SEC_FILES}/company_tickers_mf.json", f"{SEC_FILES}/company_tickers_exchange.json"]

    def fetch(self, run):
        for u in self.plan(run):
            run.dl.get(u, run.cache.folder / "sec" / u.rsplit("/", 1)[1])

    def normalise(self, run):
        return {"files": len(list((run.cache.folder / "sec").glob("*.json")))}


class Nasdaq(B.Source):
    name, title, pack = "nasdaq", "Nasdaq Trader symbol directory (which tickers are ETFs, and where they list)", PACK
    url = licence_url = "https://www.nasdaqtrader.com/trader.aspx?id=symboldirdefs"
    licence = "Public symbol directory files (Nasdaq Trader)"
    cache_prefixes = ("nasdaq",)

    def plan(self, run):
        return [f"https://www.nasdaqtrader.com/dynamic/SymDir/{n}" for n in ("nasdaqlisted.txt", "otherlisted.txt")]

    def fetch(self, run):
        for u in self.plan(run):
            run.dl.get(u, run.cache.folder / "nasdaq" / u.rsplit("/", 1)[1])

    def normalise(self, run):
        return {"files": len(list((run.cache.folder / "nasdaq").glob("*.txt")))}


class _Quarterly(_Sec):
    pattern, folder, suffix, count = "", "", "", 4

    def quarters(self, run) -> list[str]:
        """The newest count published quarters are wanted; two more are tried, as the newest may not be published yet."""
        return _quarters(run.first_year, self.count + 2)

    def plan(self, run):
        return [self.pattern.format(q=q) for q in self.quarters(run)]

    def fetch(self, run):
        got = 0
        for q in self.quarters(run):
            dest = run.cache.folder / self.folder / f"{q}{self.suffix}"
            if run.dl.get(self.pattern.format(q=q), dest, missing_ok=True):
                got += 1
                if got >= self.count and run.first_year is None:
                    break                             # enough: N-PORT needs the newest published quarter, the others four
            elif not run.a.offline:
                run.log(f"  {self.name}: {q} is not published (yet)")
        if not got and not any((run.cache.folder / self.folder).glob(f"*{self.suffix}")):
            run.dl.failed.append(f"{self.name}: no quarter")

    def normalise(self, run):
        return {"quarters": sorted(p.name for p in (run.cache.folder / self.folder).glob(f"*{self.suffix}"))}


class Nport(_Quarterly):
    name, title = "nport", "SEC Form N-PORT data sets (holdings, derivatives, counterparties, flows, monthly returns; about 450 MB a quarter)"
    url = "https://www.sec.gov/data-research/sec-markets-data/form-n-port-data-sets"
    pattern, folder, suffix, count = NPORT, "nport", "_nport.zip", 1
    cache_prefixes = ("nport",)
    scope_help = "FUNDS:<n> the n largest funds (default 200); HOLDINGS:<n> the holdings kept a fund (default 600)"


class Ncen(_Quarterly):
    name, title = "ncen", "SEC Form N-CEN data sets (ETF facts, service providers, authorised participants; 6 to 16 MB a quarter)"
    url = "https://www.sec.gov/data-research/sec-markets-data/form-n-cen-data-sets"
    pattern, folder, suffix = NCEN, "ncen", "_ncen.zip"
    cache_prefixes = ("ncen",)


class RiskReturn(_Quarterly):
    name, title = "rr", "SEC mutual fund prospectus risk/return summary data sets (expense ratio, turnover, calendar-year returns)"
    url = "https://www.sec.gov/data-research/sec-markets-data/mutual-fund-prospectus-riskreturn-summary-data-sets"
    pattern, folder, suffix = RR, "rr", "_rr1.zip"
    cache_prefixes = ("rr",)


class Gleif(B.Source):
    name, title, pack = "gleif", "GLEIF golden copy, level 2 relationship records (managed by, sub-fund of, feeder to)", PACK
    url, licence, licence_url = "https://www.gleif.org", "CC0 1.0", "https://www.gleif.org/en/meta/lei-data-terms-of-use"
    cache_prefixes = ("gleif",)

    def plan(self, run):
        return [GOLDEN + " (the latest publish, then its rr CSV; the gleif pack's cached copy is reused when there is one)"]

    def fetch(self, run):
        dest = run.cache.folder / "gleif" / "rr.csv.zip"
        shared = B.data_dir() / "publicdata" / "cache" / "gleif" / "golden" / "rr.csv.zip"
        if shared.is_file() and (run.a.offline or time.time() - shared.stat().st_mtime < run.a.max_age * 3600) and not dest.is_file():
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(shared, dest)
            run.log(f"  gleif: the gleif pack's golden copy reused ({shared})")
            return
        if run.a.offline and not dest.is_file():
            run.log("  gleif: not in the cache; the funds are built without GLEIF's relationships")
            return
        meta = run.dl.get(GOLDEN, run.cache.folder / "gleif" / "publish.json")
        if meta is None:
            return
        import json
        try:
            url = json.loads(meta.read_text(encoding="utf-8"))["data"]["rr"]["full_file"]["csv"]["url"]
        except (KeyError, ValueError, TypeError):
            run.dl.failed.append("gleif: the publish record names no rr file")
            return
        run.dl.get(url, dest)

    def normalise(self, run):
        return {"files": len(list((run.cache.folder / "gleif").glob("*.zip")))}


SOURCES = [Tickers(), Nasdaq(), Nport(), Ncen(), RiskReturn(), Gleif()]


def _make():
    spec = importlib.util.spec_from_file_location("funds_make", GEN / "make.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def _scope(items: list[str]) -> dict[str, int]:
    out = {}
    for item in items:
        k, _, v = item.partition(":")
        if k.upper() not in ("FUNDS", "HOLDINGS") or not v.isdigit() or int(v) < 1:
            raise B.Refusal(f"--scope {item}: use FUNDS:<n> or HOLDINGS:<n> (positive numbers)")
        out[k.upper()] = int(v)
    return out


def refresh(a, log=print, opener=None) -> int:
    selected = B.pick(SOURCES, a.sources)
    scope = _scope([x.strip() for x in (a.scope or "").split(",") if x.strip()])
    widened = bool(scope) and scope != {"FUNDS": 200, "HOLDINGS": 600}
    if widened and not a.into_pack:
        out = Path(a.out).expanduser().resolve() if a.out else B.data_dir() / "publicdata" / PACK
        if B.is_committed(out):
            raise B.Refusal(f"refused: a widened build goes to the data folder, not {out}; pass --into-pack to write it over the pack on purpose")
        pack_dir, testdata = out / "packs" / PACK, out / "testdata"
    else:
        if a.out:
            raise B.Refusal("refused: the committed pack is written where it lives; --out is for a widened build (--scope)")
        out, pack_dir, testdata = COMMITTED, COMMITTED, B.ROOT / "data" / "testdata"
    run = B.Run(PACK, a, out, log)
    run.first_year = a.since or (datetime.now(timezone.utc).year - a.years + 1 if a.years else None)
    run.dl = Downloader(run, **({"opener": opener} if opener else {}))
    if a.dry_run:
        log(f"dry run: {PACK}, sources {', '.join(s.name for s in selected)}" + (f", widened {scope}" if widened else ""))
        for s in selected:
            urls = s.plan(run)
            log(f"  {s.name} ({s.licence}; {s.use}): {len(urls)} request(s), cache {run.cache.folder}")
            for u in urls[:4]:
                log(f"    {u}")
        log(f"  then: tools/packgen/funds/make.py -> {pack_dir.relative_to(B.ROOT) if pack_dir.is_relative_to(B.ROOT) else pack_dir}"
            f" and {testdata}; then make.py --check")
        return 0
    man = B.Manifest(PACK)
    for s in selected:
        log(f"fetch {s.name}")
        s.fetch(run)
    for s in selected:
        man.record(s, s.plan(run), s.normalise(run), out)
    M = _make()
    M.BUILD.FUNDS, M.BUILD.TOP_N = scope.get("FUNDS", 200), scope.get("HOLDINGS", 600)
    args = ["--cache", str(run.cache.folder)]
    if pack_dir != COMMITTED:
        for f in COMMITTED.rglob("*"):
            rel = f.relative_to(COMMITTED)
            if f.is_file() and rel.parts[0] != "samples":
                (pack_dir / rel).parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(f, pack_dir / rel)
        args += ["--pack", str(pack_dir), "--testdata", str(testdata)]
    rc = M.main(args)
    if not rc and not a.no_check:
        rc = M.main(["--check"] + args)
    written = sorted(testdata.joinpath(PACK).rglob("*.jsonl.gz")) + sorted((pack_dir / "samples").glob("*"))
    man.finish(out, written, sys.argv[1:], rc == 0 and not run.dl.failed)
    log(f"downloads: {run.dl.downloaded} new, {run.dl.reused} reused" + (f", {len(run.dl.failed)} failed" if run.dl.failed else ""))
    return rc or (2 if run.dl.failed else 0)


def extra_args(r) -> None:
    r.set_defaults(max_age=168.0)
    r.add_argument("--into-pack", action="store_true", help="write a widened (--scope) build over the committed pack, on purpose")


def main(argv: list[str] | None = None) -> int:
    ap = B.parser("funds.py", __doc__, SOURCES, extra=extra_args)
    a = ap.parse_args(argv)
    try:
        if a.command == "status":
            import gzip
            import json
            parts = sorted((B.ROOT / "data" / "testdata" / PACK / "fund").glob("snapshot-*.jsonl.gz"))
            n = sum(1 for p in parts for line in gzip.open(p, "rt", encoding="utf-8") if line.strip()) if parts else 0
            first = json.loads(gzip.open(parts[0], "rt", encoding="utf-8").readline()) if parts else {}
            print(f"committed pack: {n} funds in {len(parts)} parts, data retrieved {first.get('PROVENANCE', {}).get('RETRIEVED_AT', '-')}")
            return B.status(PACK, SOURCES, Path(a.cache) if a.cache else B.data_dir() / "publicdata" / "cache" / PACK,
                            "data/testdata/funds and config/packs/funds; a widened build <data>/publicdata/funds")
        return refresh(a)
    except B.Refusal as e:
        print(e, file=sys.stderr)
        return 3


if __name__ == "__main__":
    sys.exit(main())
