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

"""Refresh the us-equities pack from its public sources: SEC EDGAR (with the index members from SPY's, QQQ's and DIA's Form N-PORT,
read from the funds pack's cache: refresh funds first), the Nasdaq Trader symbol directory, GLEIF and Wikidata.

    python3 tools/publicdata/us_equities.py status
    python3 tools/publicdata/us_equities.py refresh --dry-run
    python3 tools/publicdata/us_equities.py refresh                         the committed pack (config/packs/us-equities)
    python3 tools/publicdata/us_equities.py refresh sec gleif --offline     rebuild the pack from the cache only
    python3 tools/publicdata/us_equities.py refresh --scope TSLA,CIK:1318605,FILINGS:40,YEARS:15
                                                                            a widened build in <data>/publicdata/us-equities
    python3 tools/publicdata/us_equities.py refresh prices --server URL     internal only: a price snapshot in the data folder

It wraps tools/packgen/us_equities/fetch.py (the network) and make.py (the build), so a refresh of the committed pack writes
exactly what `make.py` writes, then runs `make.py --check`. The four company sources are openly licensed (SEC EDGAR: U.S. public
domain; GLEIF and Wikidata: CC0; the Nasdaq Trader directory is a public file) and go into the committed pack. SEC asks for a
descriptive User-Agent with a contact ("Drishti research ajsinha@gmail.com", or DRISHTI_SEC_USER_AGENT) and under 10 requests a
second; this stays under 8.

--scope widens the build (comma separated): a ticker (TSLA) or CIK:<n> adds a company, INDEX:<CODE>=<Wikidata QID>[=<name>] adds
an index's current members, FILINGS:<n> keeps more recent filings a company, YEARS:<n> (or --years) more fiscal years,
CONCEPT:<NAME>=<XBRL tag>[|<tag>...][@<unit>][@instant] another annual XBRL concept. A widened build goes to the data folder;
--into-pack writes it over the committed pack instead (on purpose only).

Prices (Yahoo Finance, read through the pack's us-equities-prices connector of a running server with it on, --server) are
internal only: Yahoo's terms forbid redistribution, so they are never written into a committed folder without --internal-ok.
They are a separate source, never part of the default refresh. Guide: docs/guides/PUBLIC_DATA.md.
"""
from __future__ import annotations

import gzip
import hashlib
import json
import os
import shutil
import sys
import time
import urllib.parse
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import pdbase as B  # noqa: E402

GEN = B.ROOT / "tools" / "packgen" / "us_equities"


def _load(name: str, path: Path):
    """The generator's fetch.py and make.py under names of their own: tools/realdata has a fetch.py too (world_economy.py), and make.py
    imports `fetch`, so that name points at ours while make.py loads, then back."""
    import importlib.util
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    sys.modules[name] = mod
    spec.loader.exec_module(mod)
    return mod


F = _load("us_equities_fetch", GEN / "fetch.py")
_other = sys.modules.get("fetch")
sys.modules["fetch"] = F
try:
    M = _load("us_equities_make", GEN / "make.py")
finally:
    if _other is not None:
        sys.modules["fetch"] = _other
    else:
        sys.modules.pop("fetch", None)

sys.path.insert(0, str(B.ROOT / "tools" / "testdata"))
sys.path.insert(0, str(B.ROOT / "tools"))

PACK = "us-equities"
COMMITTED = B.ROOT / "config" / "packs" / PACK
PRICE_KIND = "price-history"
FILINGS, YEARS = F.FILINGS, F.YEARS        # the committed pack: 20 recent filings a company, 10 fiscal years


class _Http(F.Http):
    """fetch.py's client (its User-Agent, one request at a time, its per-host gaps: SEC 0.13 s, GLEIF and Wikidata about 1 s) with
    the shared library's offline mode, counters and log; --pause is the gap for any other host."""

    def __init__(self, run: B.Run, opener=None):
        super().__init__()
        self.run, self.opener = run, opener
        self.downloaded, self.failed, self.last_url = 0, [], ""

    def get(self, url: str, accept: str = "*/*") -> bytes | None:
        self.last_url = url
        if self.run.a.offline:
            self.run.log(f"  offline: not in the cache: {url}")
            self.failed.append(url)
            return None
        host = urllib.parse.urlparse(url).hostname or ""
        self.gap.setdefault(host, max(self.run.a.pause, 0.13))
        if self.opener:
            body = self.opener(url, accept)
        else:
            body = super().get(url, accept)
        if body is None:
            self.failed.append(url)
        else:
            self.downloaded += 1
        return body


class _Company(B.Source):
    pack = PACK
    steps: tuple = ()

    def fetch(self, run: B.Run) -> None:
        for step in self.steps:
            {"wikidata": lambda: F.wikidata(run.http_us, run.work),
             "nasdaq": lambda: F.nasdaq(run.http_us, run.work),
             "sec": lambda: F.sec(run.http_us, run.work, None),
             "gleif": lambda: F.gleif(run.http_us, run.work),
             "trackers": lambda: _trackers().extract(run.work.parent / "funds", run.work, run.log)}[step]()

    def count(self, run: B.Run) -> int:
        return sum(1 for p in self.cache_prefixes for f in run.work.glob(p + "*") for _ in (f.rglob("*.*") if f.is_dir() else [f]))

    def normalise(self, run: B.Run) -> dict:
        return {"files": self.count(run)}


class Wikidata(_Company):
    name, title, steps = "wikidata", "Wikidata: index membership (DJIA, Nasdaq-100, S&P 500; the bank lists by CIK), headquarters, founded, website, LEI", ("wikidata",)
    url, licence, licence_url = "https://www.wikidata.org", "CC0 1.0", "https://creativecommons.org/publicdomain/zero/1.0/"
    scope_help = "INDEX:<CODE>=<QID>[=<name>] adds an index's current members"
    cache_prefixes = ("wikidata.json",)

    def plan(self, run: B.Run) -> list[str]:
        return [f"https://query.wikidata.org/sparql (current members of {code}, {qid})" for code, qid in F.INDEXES.items()] + \
               ["https://query.wikidata.org/sparql (company facts, 150 a query)"]


class Trackers(_Company):
    name, title, steps = "trackers", "SEC Form N-PORT of SPY, QQQ and DIA: the index members (from the funds cache; no download)", ("trackers",)
    url, licence = "https://www.sec.gov/data-research/sec-markets-data/form-n-port-data-sets", "Public domain (U.S. Government work)"
    licence_url = "https://www.sec.gov/privacy#security"
    cache_prefixes = ("trackers.json",)

    def plan(self, run: B.Run) -> list[str]:
        return [f"{run.work.parent / 'funds' / 'nport'} (the newest N-PORT data set; tools/publicdata/funds.py refresh downloads it)"]


def _trackers():
    """trackers.py, loaded beside fetch.py (it imports `fetch`, which points at ours while it loads)."""
    other = sys.modules.get("fetch")
    sys.modules["fetch"] = F
    try:
        return _load("us_equities_trackers", GEN / "trackers.py")
    finally:
        if other is not None:
            sys.modules["fetch"] = other


class Nasdaq(_Company):
    name, title, steps = "nasdaq", "Nasdaq Trader symbol directory (listing market, ETF flag)", ("nasdaq",)
    url, licence = "https://www.nasdaqtrader.com/trader.aspx?id=symboldirdefs", "Public symbol directory files (Nasdaq Trader)"
    licence_url = "https://www.nasdaqtrader.com/trader.aspx?id=symboldirdefs"
    cache_prefixes = ("nasdaq",)

    def plan(self, run: B.Run) -> list[str]:
        return [f"https://www.nasdaqtrader.com/dynamic/SymDir/{n}" for n in ("nasdaqlisted.txt", "otherlisted.txt")]


class Sec(_Company):
    name, title, steps = "sec", "SEC EDGAR: company tickers, submissions (recent filings), XBRL company facts (annual)", ("sec",)
    url, licence = "https://www.sec.gov/edgar", "Public domain (U.S. Government work); EDGAR fair access policy"
    licence_url = "https://www.sec.gov/privacy#security"
    scope_help = ("a ticker (TSLA) or CIK:<n> adds a company; FILINGS:<n> more filings a company (default 20); YEARS:<n> more fiscal "
                  "years (default 10); CONCEPT:<NAME>=<tag>[|<tag>...][@<unit>][@instant] another XBRL concept")
    cache_prefixes = ("sec",)

    def plan(self, run: B.Run) -> list[str]:
        n = len(run.companies) if run.companies else "about 500"
        return ["https://www.sec.gov/files/company_tickers_exchange.json",
                f"https://data.sec.gov/submissions/CIK##########.json ({n} companies, {F.FILINGS} filings each)",
                f"https://data.sec.gov/api/xbrl/companyfacts/CIK##########.json ({len(F.CONCEPTS)} concepts, {F.YEARS} years)"]


class Gleif(_Company):
    name, title, steps = "gleif", "GLEIF LEI records (by the LEI Wikidata gives, else by legal name)", ("gleif",)
    url, licence, licence_url = "https://www.gleif.org", "CC0 1.0", "https://www.gleif.org/en/meta/lei-data-terms-of-use"
    cache_prefixes = ("gleif",)

    def plan(self, run: B.Run) -> list[str]:
        return ["https://api.gleif.org/api/v1/lei-records/<LEI> (or ?filter[entity.legalName]=<name>), one a company"]


class Prices(B.Source):
    name, title, pack = "prices", "Yahoo Finance daily prices (the pack's us-equities-prices connector)", PACK
    url, licence = "https://finance.yahoo.com", "Yahoo terms of service: personal use, no redistribution"
    licence_url, use = "https://legal.yahoo.com/us/en/yahoo/terms/otos/index.html", B.INTERNAL
    why_internal = "Yahoo's terms forbid redistributing its prices; the pack ships none and keeps the connector off (internal demos only)"
    scope_help = "tickers (TSLA) or PX- ids: prices for these only (default: every company of the pack)"
    cache_prefixes = ("prices",)

    def ids(self, run: B.Run) -> list[str]:
        mine = [x for x in run.scope if ":" not in x]
        if mine:
            return sorted({x if x.startswith("PX-") else f"PX-{x.upper()}" for x in mine})
        return sorted(d.get("PRICE_HISTORY") or f"PX-{d['TICKER']}" for d in _companies(run.pack_dir))

    def plan(self, run: B.Run) -> list[str]:
        server = run.a.server or "<--server>"
        return [f"{server}/api/v1/entities/{PRICE_KIND}/{i}/raw" for i in self.ids(run)]

    def fetch(self, run: B.Run) -> None:
        if not run.a.server and not run.a.offline:
            raise B.Refusal("refused: prices are read through the us-equities-prices connector of a running server; start one with "
                            "DRISHTI_US_EQUITIES_PRICES_ENABLED=true (internal use only) and pass --server URL")
        import capture_market as CM
        token = Path(run.a.token_file).expanduser().read_text(encoding="utf-8").strip() if run.a.token_file else os.environ.get("DRISHTI_TOKEN")
        getter = None if run.a.offline else (run.price_getter or CM.Fetcher(run.a.server, max(2.0, run.a.delay), token, say=run.log).get)
        ids = self.ids(run)
        run.prices = []
        for n, id_ in enumerate(ids[:run.a.limit] if run.a.limit else ids, 1):
            dest = run.cache.folder / "prices" / f"{id_}.json"
            if dest.is_file() and (run.a.offline or run.cache.fresh(dest, run.a.max_age if run.a.max_age is not None else 24.0)):
                run.prices.append(json.loads(dest.read_text(encoding="utf-8")))
                continue
            d = getter(PRICE_KIND, id_) if getter else None
            if d is None:
                run.http_us.failed.append(f"{PRICE_KIND} {id_}")
                continue
            body = json.dumps(d, ensure_ascii=False, sort_keys=True).encode("utf-8")
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_bytes(body)
            run.cache.record(dest, f"{run.a.server}/api/v1/entities/{PRICE_KIND}/{id_}/raw", body)
            run.prices.append(d)
            run.log(f"  [{n}/{len(ids)}] {id_}: {len(d.get('HISTORY') or [])} bars")

    def normalise(self, run: B.Run) -> dict:
        return {"rows": len(run.prices), "bars": sum(len(d.get("HISTORY") or []) for d in run.prices)}

    def write(self, run: B.Run) -> list[Path]:
        if not run.prices:
            return []
        captured = datetime.now(timezone.utc).replace(microsecond=0)
        folder = run.prices_out / PRICE_KIND
        folder.mkdir(parents=True, exist_ok=True)
        for old in folder.glob("snapshot-*.jsonl.gz"):
            old.unlink()
        f = folder / f"snapshot-{captured:%Y-%m-%d}.jsonl.gz"
        docs = sorted(({**d, "SNAPSHOT": True, "SNAPSHOT_CAPTURED_AT": captured.isoformat().replace("+00:00", "Z"),
                        "SNAPSHOT_NOTE": "internal use only: Yahoo Finance prices may not be redistributed"} for d in run.prices),
                      key=lambda d: str(d.get("PRICE_ID") or d.get("ID") or d.get("TICKER") or ""))
        with gzip.open(f, "wt", encoding="utf-8") as fh:
            for d in docs:
                fh.write(json.dumps(d, ensure_ascii=False, sort_keys=True) + "\n")
        run.log(f"prices: {len(docs)} documents -> {f}")
        return [f]


COMPANY_SOURCES = [Wikidata(), Trackers(), Nasdaq(), Sec(), Gleif()]
SOURCES = COMPANY_SOURCES + [Prices()]


def _companies(pack_dir: Path) -> list[dict]:
    """The pack's companies from its us-company snapshot (a widened build's beside its pack folder, else the committed one)."""
    import publicsnap as SNAP
    return SNAP.kind_docs(PACK, "us-company", SNAP.default_testdata(pack_dir, COMMITTED)) or SNAP.kind_docs(PACK, "us-company")


# ---------------------------------------------------------------- --scope

class Scope:
    """--scope (and --years) parsed: extra companies, extra indexes, filings a company, fiscal years, extra XBRL concepts."""

    def __init__(self, items: list[str], years: int | None):
        self.companies: list[str] = []          # tickers, or CIK:<n>
        self.indexes: dict[str, tuple[str, str]] = {}
        self.filings, self.years = FILINGS, years or YEARS
        self.concepts: dict[str, tuple[list[str], str, bool]] = {}
        for item in items:
            key, _, rest = item.partition(":")
            k = key.upper()
            if not rest:
                self.companies.append(item.upper())
            elif k == "CIK":
                if not rest.isdigit():
                    raise B.Refusal(f"--scope {item}: a CIK is a number")
                self.companies.append(f"CIK:{int(rest)}")
            elif k == "INDEX":
                code, _, more = rest.partition("=")
                qid, _, label = more.partition("=")
                if not code or not qid.startswith("Q"):
                    raise B.Refusal(f"--scope {item}: use INDEX:<CODE>=<Wikidata QID>[=<name>]")
                self.indexes[code.upper()] = (qid, label or code.upper())
            elif k in ("FILINGS", "YEARS"):
                if not rest.isdigit() or int(rest) < 1:
                    raise B.Refusal(f"--scope {item}: a positive number")
                setattr(self, k.lower(), int(rest))
            elif k == "CONCEPT":
                name, _, spec = rest.partition("=")
                parts = spec.split("@")
                tags = [t for t in parts[0].split("|") if t]
                if not name or not tags:
                    raise B.Refusal(f"--scope {item}: use CONCEPT:<NAME>=<XBRL tag>[|<tag>...][@<unit>][@instant]")
                unit = next((p for p in parts[1:] if p and p != "instant"), "USD")
                self.concepts[name.upper().replace("-", "_")] = (tags, unit, "instant" in parts[1:])
            else:
                raise B.Refusal(f"--scope {item}: not understood; see refresh --help")

    @property
    def widened(self) -> bool:
        return bool(self.companies or self.indexes or self.concepts) or self.filings != FILINGS or self.years != YEARS

    def key(self) -> str:
        """The scope's cache folder: what changes the cached files (the indexes and companies, filings, years, concepts)."""
        spec = json.dumps([sorted(self.companies), sorted(self.indexes.items()), self.filings, self.years, sorted(self.concepts.items())])
        return hashlib.sha256(spec.encode()).hexdigest()[:12]


class _Patched:
    """fetch.py's and make.py's module settings widened for one run, then put back."""

    def __init__(self, scope: Scope):
        self.scope = scope

    def __enter__(self):
        self.saved = (F.FILINGS, F.YEARS, dict(F.CONCEPTS), dict(M.CONCEPTS), dict(M.INDEX_NAMES), dict(F.INDEXES))
        s = self.scope
        F.FILINGS, F.YEARS = s.filings, s.years
        for name, (tags, unit, instant) in s.concepts.items():
            F.CONCEPTS[name] = (tags, unit, instant)
            M.CONCEPTS[name] = (name.replace("_", " ").capitalize(), unit, f"{name} (XBRL {', '.join(tags)}), added with --scope.")
        for code, (qid, label) in s.indexes.items():
            F.INDEXES[code] = qid
            M.INDEX_NAMES[code] = label
        if s.companies:
            M.INDEX_NAMES["SCOPE"] = "Added with --scope (not an index)"
        return self

    def __exit__(self, *exc):
        F.FILINGS, F.YEARS = self.saved[0], self.saved[1]
        for target, saved in zip((F.CONCEPTS, M.CONCEPTS, M.INDEX_NAMES, F.INDEXES), self.saved[2:]):
            target.clear()
            target.update(saved)
        return False


def seed(base: Path, work: Path, scope: Scope) -> None:
    """A widened build's cache: a copy of the base cache, less what the scope changes (submissions for FILINGS, facts for YEARS and
    CONCEPT, wikidata.json for INDEX: it is fetched again with the extra index)."""
    if not base.is_dir():
        return
    skip = set()
    if scope.filings != FILINGS:
        skip.add("sec/submissions")
    if scope.years != YEARS or scope.concepts:
        skip.add("sec/facts")
    if scope.indexes:
        skip.add("wikidata.json")
    for f in base.rglob("*"):
        rel = f.relative_to(base).as_posix()
        if not f.is_file() or rel.startswith("scoped/") or rel.startswith("prices/") or rel == "cache-index.json" \
                or any(rel == s or rel.startswith(s + "/") for s in skip):
            continue
        dest = work / rel
        if not dest.exists():
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(f, dest)


def add_companies(run: B.Run, scope: Scope) -> list[str]:
    """Write the --scope companies into the build's wikidata.json as members of SCOPE (make.py builds index members only); returns
    the CIKs. Needs the SEC ticker file for tickers."""
    wd_path = run.work / "wikidata.json"
    wd = json.loads(wd_path.read_text(encoding="utf-8"))
    by_ticker, _ = F.sec_maps(run.work)
    ciks = []
    for c in scope.companies:
        cik = c[4:] if c.startswith("CIK:") else by_ticker.get(c.replace(".", "-"))
        if not cik:
            raise B.Refusal(f"--scope {c}: the SEC ticker file has no such ticker")
        ciks.append(str(int(cik)))
    wd["members"] = [m for m in wd["members"] if m.get("index") != "SCOPE"]
    wd["members"] += [{"index": "SCOPE", "qid": f"SCOPE-{c}", "label": "", "cik": c, "since": ""} for c in ciks]
    wd_path.write_text(json.dumps(wd, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")   # not a download: not indexed
    return ciks


def _scope_companies(run: B.Run, scope: Scope, widened: bool) -> None:
    """Before SEC and GLEIF read the universe: the SEC ticker file, then the --scope companies as SCOPE members."""
    if not (widened and scope.companies) or not (run.work / "wikidata.json").is_file():
        return
    tick = run.work / "sec" / "company_tickers_exchange.json"
    if not tick.is_file():
        body = run.http_us.get("https://www.sec.gov/files/company_tickers_exchange.json")
        if body is None:
            raise B.Refusal("the SEC ticker file could not be fetched (needed for the --scope tickers)")
        F._save(tick, body)
    add_companies(run, scope)


# ---------------------------------------------------------------- the run

def resolve_out(a, widened: bool, prices_only: bool) -> tuple[Path, bool]:
    """(the output folder, True when it is the committed pack)."""
    default = B.data_dir() / "publicdata" / PACK
    if a.out:
        out = Path(a.out).expanduser().resolve()
        if B.is_committed(out) and not prices_only and out != COMMITTED.resolve():
            raise B.Refusal(f"refused: {out} is inside the repository but is not the pack ({COMMITTED.relative_to(B.ROOT)}); leave out --out")
        committed = out == COMMITTED.resolve()
    elif prices_only:
        out, committed = default, False
    else:
        committed = not widened or a.into_pack
        out = COMMITTED if committed else default
    if committed and widened and not a.into_pack:
        raise B.Refusal("refused: --scope and --years widen a build in the data folder only; pass --into-pack to write the widened "
                        "build over the committed pack on purpose")
    return out, committed


def refresh(a, log=print, opener=None, price_getter=None) -> int:
    names = a.sources or [s.name for s in COMPANY_SOURCES]
    selected = B.pick(SOURCES, names)
    companies_on = [s for s in COMPANY_SOURCES if s in selected]
    prices_on = any(s.name == "prices" for s in selected)
    scope = Scope([x.strip() for x in (a.scope or "").split(",") if x.strip()],
                  a.years or (datetime.now(timezone.utc).year - a.since + 1 if a.since else None))   # --since YEAR: the fiscal years from YEAR
    widened = scope.widened and bool(companies_on)
    out, committed = resolve_out(a, widened, not companies_on)
    if not companies_on:
        prices_out = out if a.out else out / "prices"
    else:
        prices_out = (out / "prices") if not committed else B.data_dir() / "publicdata" / PACK / "prices"
    if prices_on:
        B.guard_out([SOURCES[-1]], prices_out, a.internal_ok)
    run = B.Run(PACK, a, out, log)
    run.scope = [x.strip() for x in (a.scope or "").split(",") if x.strip()]
    run.http_us, run.price_getter, run.prices, run.prices_out = _Http(run, opener), price_getter, [], prices_out
    run.pack_dir = COMMITTED if committed else out / "packs" / PACK
    run.work = run.cache.folder / "scoped" / scope.key() if widened else run.cache.folder
    run.companies = scope.companies
    where = f"the committed pack ({COMMITTED.relative_to(B.ROOT)})" if committed else str(run.pack_dir)
    with _Patched(scope):
        if a.dry_run:
            log(f"dry run: {PACK}, sources {', '.join(s.name for s in selected)}" + (", widened" if widened else ""))
            for s in selected:
                urls = s.plan(run)
                log(f"  {s.name} ({s.licence}; {s.use}): {len(urls)} request(s)" + (f", cache {run.work}" if s in companies_on else ""))
                for u in urls[:3]:
                    log(f"    {u}")
            if widened:
                log(f"  --scope: companies {scope.companies or '-'}, indexes {list(scope.indexes) or '-'}, filings {scope.filings}, "
                    f"years {scope.years}, concepts {list(scope.concepts) or '-'}")
            if companies_on:
                log(f"  then: make.py -> {where}; then make.py --check")
            if prices_on:
                log(f"  prices (internal only) -> {prices_out / PRICE_KIND}")
            return 0
        return _refresh(a, run, selected, companies_on, prices_on, scope, widened, out, committed, log)


def _refresh(a, run, selected, companies_on, prices_on, scope, widened, out, committed, log) -> int:
    man = B.Manifest(PACK)
    stale = _expire(run) if companies_on and a.max_age is not None and not a.offline else []
    orig_save = F._save

    def save(path: Path, data) -> None:          # every file fetch.py keeps goes into the shared cache index
        orig_save(path, data)
        p = Path(path)
        if p.resolve().is_relative_to(run.cache.folder.resolve()):
            run.cache.record(p, run.http_us.last_url, p.read_bytes())
    F._save = save
    rc = 0
    try:
        run.work.mkdir(parents=True, exist_ok=True)
        if widened:
            seed(run.cache.folder, run.work, scope)
        try:
            _scope_companies(run, scope, widened)
        except SystemExit as e:
            raise B.Refusal(f"sec: {e}") from None
        for s in companies_on:
            log(f"fetch {s.name}")
            try:
                s.fetch(run)
                if s.name == "wikidata":
                    _scope_companies(run, scope, widened)
            except SystemExit as e:               # fetch.py stops on a missing essential file (offline, or the source is down)
                raise B.Refusal(f"{s.name}: {e}") from None
        if prices_on:
            log("fetch prices (internal only)")
            SOURCES[-1].fetch(run)
    finally:
        F._save = orig_save
        _restore(stale)
        run.cache.save()
    for s in selected:
        counts = s.normalise(run)
        man.record(s, s.plan(run), counts, out)
    written: list[Path] = []
    if companies_on:
        if not (run.work / "wikidata.json").is_file():
            raise B.Refusal(f"the cache {run.work} has no wikidata.json; refresh wikidata first (the pack is built from every source)")
        missing = [s.name for s in COMPANY_SOURCES if not s.count(run)]
        if committed and missing:
            raise B.Refusal(f"refused: the cache {run.work} has nothing from {', '.join(missing)}; refresh those too so the committed "
                            "pack loses nothing")
        pack_args = [] if committed else ["--pack", str(run.pack_dir)]
        if not committed:
            _copy_pack(run.pack_dir)
        with _Patched(scope):
            rc = M.main(["--cache", str(run.work)] + pack_args)
            if not rc and not a.no_check:
                rc = M.main(["--check", "--cache", str(run.work)] + pack_args)
        written += sorted((run.pack_dir / "samples").glob("*")) + [run.pack_dir / "guides" / "us-equities.md"]
        log(f"pack: {run.pack_dir}" if not rc else "the pack generator failed")
    if prices_on:
        written += SOURCES[-1].write(run)
    failed = run.http_us.failed
    man.finish(out, written, sys.argv[1:], rc == 0 and not failed)
    log(f"downloads: {run.http_us.downloaded} new" + (f", {len(failed)} failed" if failed else ""))
    return rc or (2 if failed else 0)


def _copy_pack(dest: Path) -> None:
    """A widened build is a whole pack: the hand-written parts (pack.yaml, Sutras, config, guide images) copied, samples generated."""
    for f in COMMITTED.rglob("*"):
        rel = f.relative_to(COMMITTED)
        if f.is_file() and rel.parts[0] != "samples":
            (dest / rel).parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(f, dest / rel)


def _expire(run: B.Run) -> list[tuple[Path, Path]]:
    """fetch.py keeps a cached file until it is removed; with --max-age HOURS, files older than that are set aside (and put back if
    the new fetch fails) so they are fetched again."""
    moved, limit = [], time.time() - run.a.max_age * 3600
    for f in list(run.work.rglob("*.json")) + list(run.work.glob("nasdaq/*.txt")):
        rel = f.relative_to(run.work).as_posix()
        if rel.startswith(("scoped/", "prices/")) or rel == "cache-index.json":
            continue
        e = run.cache.index.get(run.cache.rel(f)) if f.resolve().is_relative_to(run.cache.folder.resolve()) else None
        when = datetime.fromisoformat(e["fetched"].replace("Z", "+00:00")).timestamp() if e else f.stat().st_mtime
        if when < limit:
            aside = f.with_name(f.name + ".old")
            f.replace(aside)
            moved.append((f, aside))
    return moved


def _restore(moved: list[tuple[Path, Path]]) -> None:
    for f, aside in moved:
        if f.exists():
            aside.unlink(missing_ok=True)
        else:
            aside.replace(f)


def extra_args(r) -> None:
    r.set_defaults(max_age=None)
    r.add_argument("--into-pack", action="store_true", help="write a widened (--scope, --years) build over the committed pack, on purpose")
    r.add_argument("--server", help="prices: a running server with the us-equities-prices connector on (internal use only)")
    r.add_argument("--token-file", help="prices: an API token for --server with security on (default DRISHTI_TOKEN)")
    r.add_argument("--delay", type=float, default=2.0, help="prices: seconds between calls, at least 2 (default 2)")
    r.add_argument("--limit", type=int, help="prices: the first N tickers only (a quick try)")


def main(argv: list[str] | None = None) -> int:
    ap = B.parser("us_equities.py", __doc__, SOURCES, extra=extra_args)
    a = ap.parse_args(argv)
    try:
        if a.command == "status":
            cos = _companies(COMMITTED)
            print(f"committed pack: {len(cos)} companies, data retrieved {cos[0].get('PROVENANCE', {}).get('RETRIEVED_AT', '?') if cos else '-'}")
            return B.status(PACK, SOURCES, Path(a.cache) if a.cache else B.data_dir() / "publicdata" / "cache" / PACK,
                            "config/packs/us-equities; a widened build <data>/publicdata/us-equities/packs/us-equities; prices "
                            "<data>/publicdata/us-equities/prices")
        return refresh(a)
    except B.Refusal as e:
        print(e, file=sys.stderr)
        return 3


if __name__ == "__main__":
    sys.exit(main())
