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

"""Refresh the gleif pack from GLEIF: the golden copy (level 2 relationships and reporting exceptions), the LEI API and the mapping files.

    python3 tools/publicdata/gleif.py status
    python3 tools/publicdata/gleif.py refresh --dry-run
    python3 tools/publicdata/gleif.py refresh                               the committed pack (config/packs/gleif)
    python3 tools/publicdata/gleif.py refresh --offline                     rebuild the pack from the cache only
    python3 tools/publicdata/gleif.py refresh --scope GROUP:784F5XWPLTWKTBV3E584,CAP:600
                                                                            a widened build in <data>/publicdata/gleif
    python3 tools/publicdata/gleif.py refresh golden golden-lei api mapping --scope COUNTRY:LU --since 2025
                                                                            a full-country pull from the bulk file (data folder only)

It wraps tools/packgen/gleif/fetch.py (the network) and make.py (the build), so a refresh of the committed pack writes exactly what
`make.py` writes, then runs `make.py --check`. Everything GLEIF publishes is CC0 1.0 (attribution appreciated) and may be committed.
GLEIF gets a polite User-Agent ("Drishti research ajsinha@gmail.com", or DRISHTI_GLEIF_USER_AGENT) and at most one API call a second.

--scope widens the build (comma separated): LEI:<lei> adds that entity's ownership group; GROUP:<lei> the same (name the ultimate
parent, or any member); COUNTRY:<XX> every entity with a legal address in that country (the LEI API, at most --max-pages pages of 200,
or every one from the golden-copy bulk file with the golden-lei source); NAME:<text> the entities GLEIF's full-text search finds (at
most 200); TICKERS-FROM:us-equities the us-equities companies (the widened us-equities build's snapshot in the data folder when there is one,
else the committed one; or TICKERS-FROM:<path to a us-company .jsonl or .jsonl.gz>); CAP:<n> keeps up to n entities a group (default 50).
--years N / --since YEAR keep only the COUNTRY: and NAME: entities whose LEI record was updated in that period. A widened build goes
to the data folder; --into-pack writes it over the committed pack instead (on purpose only; never with golden-lei). Guide:
docs/guides/PUBLIC_DATA.md.
"""
from __future__ import annotations

import csv
import hashlib
import io
import json
import shutil
import sys
import time
import urllib.parse
import zipfile
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import pdbase as B  # noqa: E402

sys.path.insert(0, str(B.ROOT / "tools" / "testdata"))
import publicsnap as SNAP  # noqa: E402  (the pack's data: data/testdata/gleif/<kind>/)

GEN = B.ROOT / "tools" / "packgen" / "gleif"


def _load(name: str, path: Path):
    """The generator's fetch.py and make.py under names of their own (other packs have a fetch.py too), with `fetch` pointing at
    ours while make.py loads, then back."""
    import importlib.util
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    sys.modules[name] = mod
    spec.loader.exec_module(mod)
    return mod


F = _load("gleif_fetch", GEN / "fetch.py")
_other = sys.modules.get("fetch")
sys.modules["fetch"] = F
try:
    M = _load("gleif_make", GEN / "make.py")
finally:
    if _other is not None:
        sys.modules["fetch"] = _other
    else:
        sys.modules.pop("fetch", None)

PACK = "gleif"
COMMITTED = B.ROOT / "config" / "packs" / PACK
CC0, CC0_URL = "CC0 1.0 (attribution appreciated)", "https://www.gleif.org/en/meta/lei-data-terms-of-use"


class _Http(F.Http):
    """fetch.py's client (its User-Agent, one request at a time, about a second between GLEIF calls) with the shared library's
    offline mode, counters and log."""

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
        self.gap.setdefault(host, max(self.run.a.pause, 1.0))
        body = self.opener(url, accept) if self.opener else super().get(url, accept)
        if body is None:
            self.failed.append(url)
        else:
            self.downloaded += 1
        return body


class _Source(B.Source):
    pack, url, licence, licence_url = PACK, "https://www.gleif.org", CC0, CC0_URL

    def count(self, run: B.Run) -> int:
        return sum(1 for p in self.cache_prefixes for f in run.work.glob(p + "*") for _ in (f.rglob("*.*") if f.is_dir() else [f]))

    def normalise(self, run: B.Run) -> dict:
        return {"files": self.count(run)}


class Golden(_Source):
    name, title = "golden", "GLEIF golden copy, level 2: relationship records and reporting exceptions (whole files)"
    url = "https://www.gleif.org/en/lei-data/gleif-golden-copy"
    cache_prefixes = ("golden/publish.json", "golden/rr", "golden/repex")

    def plan(self, run: B.Run) -> list[str]:
        return [F.GOLDEN, "rr full file, csv (about 24 MB zipped)", "repex full file, csv (about 62 MB zipped)"]

    def fetch(self, run: B.Run) -> None:
        F.golden(run.http_g, run.work)


class GoldenLei(_Source):
    name, title = "golden-lei", "GLEIF golden copy, level 1: every LEI record (the bulk file, for COUNTRY: pulls; data folder only)"
    url = "https://www.gleif.org/en/lei-data/gleif-golden-copy"
    scope_help = "COUNTRY:<XX> reads every entity of the country from the bulk file (about 480 MB zipped) instead of the LEI API"
    cache_prefixes = ("golden/lei2",)

    def plan(self, run: B.Run) -> list[str]:
        return ["lei2 full file, csv (about 480 MB zipped): read for " + (", ".join(run.countries) or "no COUNTRY: scope")]

    def fetch(self, run: B.Run) -> None:
        dest = run.work / "golden" / "lei2.csv.zip"
        if not dest.exists():
            meta = F._load(run.work / "golden" / "publish.json")
            if not meta:
                raise B.Refusal("golden-lei: fetch the golden source first (the latest publish names the bulk file)")
            run.log("GLEIF golden copy: lei2 (the bulk file; this takes a while)")
            body = run.http_g.get(meta["data"]["lei2"]["full_file"]["csv"]["url"], "application/zip")
            if body is None:
                raise B.Refusal("golden-lei: the bulk file could not be downloaded")
            F._save(dest, body)
        for cc in run.countries:
            out = run.work / "api" / "records" / f"golden-{cc}.json"
            if out.exists():
                continue
            items = [{"type": "lei-records", "id": rec["lei"], "attributes": rec} for rec in lei2_records(dest, cc)]
            F._save(out, {"data": items})
            run.log(f"  golden-lei {cc}: {len(items)} entities")


class Api(_Source):
    name, title = "api", "GLEIF LEI API: the LEI records of the pack's entities, legal forms, countries, LEI issuers"
    url = "https://api.gleif.org/api/v1"
    scope_help = ("LEI:<lei> or GROUP:<lei> adds a group; COUNTRY:<XX> a country's entities; NAME:<text> a full-text search; "
                  "TICKERS-FROM:us-equities the us-equities companies; CAP:<n> entities a group (default 50)")
    cache_prefixes = ("api",)

    def plan(self, run: B.Run) -> list[str]:
        many = (f"about {len(run.leis) // F.BATCH + 1} request(s)" if run.leis else
                "one request per 200 entities; the count is known once the golden copy is reduced")
        return [f"{F.API}/lei-records?filter[lei]=<200 LEIs> ({many})",
                f"{F.API}/entity-legal-forms, /countries, /lei-issuers (paged, about 20 requests)"] + \
               [f"{F.API}/lei-records?filter[fulltext]={t}" for t in run.names] + \
               [f"{F.API}/lei-records?filter[entity.legalAddress.country]={c} (at most {run.a.max_pages} pages of 200)"
                for c in run.countries if not run.golden_lei]

    def fetch(self, run: B.Run) -> None:
        extras(run)
        run.leis, _ = F.universe(run.work, run.extra, run.cap)
        F.api(run.http_g, run.work, run.leis)


class Mapping(_Source):
    name, title = "mapping", "GLEIF mapping files: BIC, ISIN, MIC and OpenCorporates to LEI"
    url = "https://www.gleif.org/en/lei-data/lei-mapping"
    licence = "CC0 1.0 (the BIC file with SWIFT, the ISIN file with ANNA)"
    cache_prefixes = ("mapping",)

    def plan(self, run: B.Run) -> list[str]:
        return [F.MAPPING.format(name=n) for n in F.MAPPINGS]

    def fetch(self, run: B.Run) -> None:
        F.mapping(run.http_g, run.work)


SOURCES = [Golden(), Api(), Mapping(), GoldenLei()]
DEFAULT = ["golden", "api", "mapping"]


# ---------------------------------------------------------------- the bulk file

def lei2_records(zip_path: Path, country: str):
    """The bulk file's records of one legal-address country, as LEI API attributes (the fields make.py reads)."""
    with zipfile.ZipFile(zip_path) as z:
        name = next(n for n in z.namelist() if n.endswith(".csv"))
        with z.open(name) as raw:
            for r in csv.DictReader(io.TextIOWrapper(raw, encoding="utf-8-sig", newline="")):
                if r.get("Entity.LegalAddress.Country") == country:
                    yield lei2_attributes(r)


def _address(r: dict, p: str) -> dict:
    lines = [r.get(f"{p}.FirstAddressLine") or ""] + [r.get(f"{p}.AdditionalAddressLine.{i}") or "" for i in (1, 2, 3)]
    return {"addressLines": [x for x in lines if x], "city": r.get(f"{p}.City") or "", "region": r.get(f"{p}.Region") or "",
            "country": r.get(f"{p}.Country") or "", "postalCode": r.get(f"{p}.PostalCode") or ""}


def lei2_attributes(r: dict) -> dict:
    """One CSV row of the level 1 bulk file in the LEI API's shape."""
    others = [{"name": r[f"Entity.OtherEntityNames.OtherEntityName.{i}"], "type": r.get(f"Entity.OtherEntityNames.OtherEntityName.{i}.type") or ""}
              for i in range(1, 6) if r.get(f"Entity.OtherEntityNames.OtherEntityName.{i}")]
    return {"lei": r["LEI"], "entity": {
        "legalName": {"name": r["Entity.LegalName"]}, "otherNames": others, "transliteratedOtherNames": [],
        "legalAddress": _address(r, "Entity.LegalAddress"), "headquartersAddress": _address(r, "Entity.HeadquartersAddress"),
        "registeredAt": {"id": r.get("Entity.RegistrationAuthority.RegistrationAuthorityID") or None},
        "registeredAs": r.get("Entity.RegistrationAuthority.RegistrationAuthorityEntityID") or None,
        "jurisdiction": r.get("Entity.LegalJurisdiction") or None, "category": r.get("Entity.EntityCategory") or None,
        "legalForm": {"id": r.get("Entity.LegalForm.EntityLegalFormCode") or None, "other": r.get("Entity.LegalForm.OtherLegalForm") or None},
        "status": r.get("Entity.EntityStatus") or None, "creationDate": r.get("Entity.EntityCreationDate") or None,
        "successorEntity": {"lei": r.get("Entity.SuccessorEntity.1.SuccessorLEI") or None}},
        "registration": {"initialRegistrationDate": r.get("Registration.InitialRegistrationDate") or None,
                         "lastUpdateDate": r.get("Registration.LastUpdateDate") or None, "status": r.get("Registration.RegistrationStatus") or "",
                         "nextRenewalDate": r.get("Registration.NextRenewalDate") or None, "managingLou": r.get("Registration.ManagingLOU") or None,
                         "corroborationLevel": r.get("Registration.ValidationSources") or None}}


# ---------------------------------------------------------------- --scope

class Scope:
    """--scope parsed: extra LEIs and groups, countries, names, the us-equities seeds file, the group cap."""

    def __init__(self, items: list[str]):
        self.leis: list[str] = []
        self.countries: list[str] = []
        self.names: list[str] = []
        self.tickers_from: str | None = None
        self.cap: int | None = None
        for item in items:
            key, _, rest = item.partition(":")
            k = key.upper()
            if k in ("LEI", "GROUP"):
                if len(rest) != 20 or not rest.isalnum():
                    raise B.Refusal(f"--scope {item}: an LEI is 20 letters and digits")
                self.leis.append(rest.upper())
            elif k == "COUNTRY":
                if len(rest) != 2 or not rest.isalpha():
                    raise B.Refusal(f"--scope {item}: a country is its ISO 3166 two-letter code (COUNTRY:LU)")
                self.countries.append(rest.upper())
            elif k == "NAME":
                if not rest.strip():
                    raise B.Refusal(f"--scope {item}: give some text to search")
                self.names.append(rest.strip())
            elif k == "TICKERS-FROM":
                self.tickers_from = rest
            elif k == "CAP":
                if not rest.isdigit() or int(rest) < 1:
                    raise B.Refusal(f"--scope {item}: a positive number")
                self.cap = int(rest)
            else:
                raise B.Refusal(f"--scope {item}: not understood; see refresh --help")

    @property
    def widened(self) -> bool:
        return bool(self.leis or self.countries or self.names or self.tickers_from or self.cap)


def tickers_file(spec: str | None) -> Path | None:
    """TICKERS-FROM: the us-equities companies file (the widened build's snapshot in the data folder first, else the committed one)."""
    if spec is None:
        return None
    if spec.lower() == "us-equities":
        wide = F.newest_snapshot(B.data_dir() / "publicdata" / "us-equities" / "testdata", "us-equities", "us-company")
        return wide if wide.is_file() else F.US_EQUITIES
    p = Path(spec).expanduser()
    if not p.is_file():
        raise B.Refusal(f"--scope TICKERS-FROM:{spec}: no such file (a us-company .jsonl or .jsonl.gz, or us-equities)")
    return p


def _search(run: B.Run, kind: str, value: str) -> list[str]:
    """LEIs from the LEI API for NAME: (full text, one page of 200) or COUNTRY: (up to --max-pages pages of 200); each page is cached
    under api/search and its records kept like the batches."""
    leis: list[str] = []
    pages = 1 if kind == "NAME" else run.a.max_pages
    flt = "filter[fulltext]" if kind == "NAME" else "filter[entity.legalAddress.country]"
    for page in range(1, pages + 1):
        url = f"{F.API}/lei-records?{flt}={urllib.parse.quote(value)}&page[size]={F.BATCH}&page[number]={page}"
        dest = run.work / "api" / "search" / (hashlib.sha256(url.encode()).hexdigest()[:16] + ".json")
        body = dest.read_bytes() if dest.is_file() else run.http_g.get(url, "application/vnd.api+json")
        if body is None:
            break
        if not dest.is_file():
            F._save(dest, body)
            F._save(run.work / "api" / "records" / ("search-" + dest.name), body)
        d = json.loads(body)
        leis += [x["id"] for x in d.get("data", [])]
        if page >= d.get("meta", {}).get("pagination", {}).get("lastPage", page):
            break
    return leis


def extras(run: B.Run) -> None:
    """run.extra: the --scope LEIs (LEI:, GROUP:, NAME:, COUNTRY:), the last two kept only when updated within --years/--since."""
    s = run.scope_obj
    found: list[str] = []
    for text in s.names:
        found += _search(run, "NAME", text)
    for cc in s.countries:
        if run.golden_lei:
            f = run.work / "api" / "records" / f"golden-{cc}.json"
            found += [x["id"] for x in (F._load(f) or {}).get("data", [])]
        else:
            found += _search(run, "COUNTRY", cc)
    if run.since:
        recs = F.cached_records(run.work)
        found = [x for x in found if ((recs.get(x) or {}).get("attributes", {}).get("registration", {}).get("lastUpdateDate") or "")[:4] >= str(run.since)]
    run.extra = sorted(set(s.leis) | set(found))


# ---------------------------------------------------------------- the run

def resolve_out(a, widened: bool) -> tuple[Path, bool]:
    """(the pack folder, True when it is the committed pack)."""
    if a.out:
        out = Path(a.out).expanduser().resolve()
        if B.is_committed(out) and out != COMMITTED.resolve():
            raise B.Refusal(f"refused: {out} is inside the repository but is not the pack ({COMMITTED.relative_to(B.ROOT)}); leave out --out")
        committed = out == COMMITTED.resolve()
        if committed and widened and not a.into_pack:
            raise B.Refusal("refused: --scope, --years and --since widen a build in the data folder only; pass --into-pack to write the "
                            "widened build over the committed pack on purpose")
        return (out if committed else out / "packs" / PACK), committed
    if widened and not a.into_pack:
        return B.data_dir() / "publicdata" / PACK / "packs" / PACK, False
    return COMMITTED, True


def refresh(a, log=print, opener=None) -> int:
    names = a.sources or list(DEFAULT)
    selected = B.pick(SOURCES, names)
    scope = Scope([x.strip() for x in (a.scope or "").split(",") if x.strip()])
    since = a.since or (datetime.now(timezone.utc).year - a.years + 1 if a.years else None)
    golden_lei = any(s.name == "golden-lei" for s in selected)
    widened = scope.widened or bool(since) or golden_lei
    if golden_lei and a.into_pack:
        raise B.Refusal("refused: the golden-lei bulk file feeds wide pulls into the data folder only, never the committed pack")
    pack_dir, committed = resolve_out(a, widened)
    run = B.Run(PACK, a, pack_dir, log)
    run.http_g = _Http(run, opener)
    run.work = run.cache.folder
    run.scope_obj, run.countries, run.names, run.golden_lei, run.since = scope, scope.countries, scope.names, golden_lei, since
    run.cap, run.extra, run.leis = scope.cap, sorted(scope.leis), []
    where = f"the committed pack ({COMMITTED.relative_to(B.ROOT)})" if committed else str(pack_dir)
    seeds = tickers_file(scope.tickers_from)
    saved_seeds, saved_cap = F.US_EQUITIES, F.GROUP_CAP
    if scope.cap:
        F.GROUP_CAP = scope.cap                          # the cap the group pages quote, for this run only
    if seeds:
        F.US_EQUITIES = seeds
    try:
        if a.dry_run:
            log(f"dry run: {PACK}, sources {', '.join(s.name for s in selected)}" + (", widened" if widened else ""))
            if (run.work / "reduced" / "rr.json").is_file():
                run.leis, groups = F.universe(run.work, run.extra, run.cap)
                log(f"  universe today: {len(groups)} groups, {len(run.leis)} entities (cap {run.cap or F.GROUP_CAP} a group)")
            for s in selected:
                urls = s.plan(run)
                log(f"  {s.name} ({s.licence}; {s.use}): {len(urls)} request(s) or file(s), cache {run.work}")
                for u in urls[:4]:
                    log(f"    {u}")
            if widened:
                log(f"  --scope: LEIs {scope.leis or '-'}, countries {scope.countries or '-'}, names {scope.names or '-'}, "
                    f"seeds {seeds or 'the committed us-equities pack'}, cap {scope.cap or F.GROUP_CAP}, updated since {since or '-'}")
            log(f"  then: make.py -> {where}; then make.py --check")
            return 0
        return _refresh(a, run, selected, committed, pack_dir, log)
    finally:
        F.US_EQUITIES, F.GROUP_CAP = saved_seeds, saved_cap


def _refresh(a, run, selected, committed, pack_dir, log) -> int:
    man = B.Manifest(PACK)
    stale = _expire(run) if a.max_age is not None and not a.offline else []
    orig_save = F._save

    def save(path: Path, data) -> None:              # every file fetch.py keeps goes into the shared cache index
        orig_save(path, data)
        p = Path(path)
        if p.resolve().is_relative_to(run.cache.folder.resolve()) and run.http_g.last_url:
            run.cache.record(p, run.http_g.last_url, p.read_bytes())
    F._save = save
    order = [s for s in SOURCES if s in selected]
    order.sort(key=lambda s: ["golden", "golden-lei", "api", "mapping"].index(s.name))
    try:
        run.work.mkdir(parents=True, exist_ok=True)
        F.reduce_rr(run.work)                             # a cache with the golden copy but not yet its reduced file
        for s in order:
            log(f"fetch {s.name}")
            try:
                s.fetch(run)
            except SystemExit as e:                       # fetch.py stops on a missing essential file (offline, or GLEIF is down)
                raise B.Refusal(f"{s.name}: {e}") from None
        if not any(s.name == "api" for s in order):
            extras(run)
    finally:
        F._save = orig_save
        _restore(stale)
        run.cache.save()
    for s in selected:
        man.record(s, s.plan(run), s.normalise(run), pack_dir)
    if not (run.work / "reduced" / "rr.json").is_file():
        raise B.Refusal(f"the cache {run.work} has no relationship records; refresh golden first (the pack is built from every source)")
    missing = [s.name for s in SOURCES if s.name in DEFAULT and not s.count(run)]
    if committed and missing:
        raise B.Refusal(f"refused: the cache {run.work} has nothing from {', '.join(missing)}; refresh those too so the committed pack "
                        "loses nothing")
    pack_args = [] if committed else ["--pack", str(pack_dir)]
    if not committed:
        _copy_pack(pack_dir)
    rc = M.main(["--cache", str(run.work)] + pack_args, run.extra, run.cap)
    if not rc and not a.no_check:
        rc = M.main(["--check", "--cache", str(run.work)] + pack_args, run.extra, run.cap)
    written = sorted((pack_dir / "samples").glob("*")) + [pack_dir / "guides" / "gleif.md"]
    log(f"pack: {pack_dir}" if not rc else "the pack generator failed")
    failed = run.http_g.failed
    man.finish(pack_dir if committed else pack_dir.parent.parent, written, sys.argv[1:], rc == 0 and not failed)
    log(f"downloads: {run.http_g.downloaded} new" + (f", {len(failed)} failed" if failed else ""))
    return rc or (2 if failed else 0)


def _copy_pack(dest: Path) -> None:
    """A widened build is a whole pack: the hand-written parts (pack.yaml, Sutras, config, guide images) copied, samples generated."""
    for f in COMMITTED.rglob("*"):
        rel = f.relative_to(COMMITTED)
        if f.is_file() and rel.parts[0] != "samples":
            (dest / rel).parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(f, dest / rel)


def _expire(run: B.Run) -> list[tuple[Path, Path]]:
    """fetch.py keeps a cached download until it is removed; with --max-age HOURS, the downloads older than that (the golden copy,
    the mapping files, the code lists) are set aside, and put back if the new fetch fails, so they are fetched again. The LEI records
    are kept: they are fetched for new LEIs only."""
    moved, limit = [], time.time() - run.a.max_age * 3600
    for f in list((run.work / "golden").glob("*")) + list((run.work / "mapping").glob("*")) + list((run.work / "api").glob("*.json")):
        if not f.is_file() or f.name.endswith(".old") or f.name == "not-found.json":
            continue
        e = run.cache.index.get(run.cache.rel(f))
        when = datetime.fromisoformat(e["fetched"].replace("Z", "+00:00")).timestamp() if e else f.stat().st_mtime
        if when < limit:
            aside = f.with_name(f.name + ".old")
            f.replace(aside)
            moved.append((f, aside))
    if any(f.parent.name == "golden" and f.name == "rr.csv.zip" for f, _ in moved):
        (run.work / "reduced" / "rr.json").unlink(missing_ok=True)
    return moved


def _restore(moved: list[tuple[Path, Path]]) -> None:
    for f, aside in moved:
        if f.exists():
            aside.unlink(missing_ok=True)
        else:
            aside.replace(f)


def extra_args(r) -> None:
    r.set_defaults(max_age=None)
    r.add_argument("--into-pack", action="store_true", help="write a widened (--scope, --years, --since) build over the committed pack, on purpose")
    r.add_argument("--max-pages", type=int, default=10, help="COUNTRY: through the LEI API: at most this many pages of 200 (default 10)")


def main(argv: list[str] | None = None) -> int:
    ap = B.parser("gleif.py", __doc__, SOURCES, extra=extra_args)
    a = ap.parse_args(argv)
    try:
        if a.command == "status":
            ents = SNAP.kind_docs(PACK, "lei-entity")                           # the committed data: data/testdata/gleif
            n = len(ents)
            when = ents[0].get("PROVENANCE", {}).get("RETRIEVED_AT", "?") if ents else "-"
            print(f"committed pack: {n} entities, golden copy of {when}")
            return B.status(PACK, SOURCES, Path(a.cache) if a.cache else B.data_dir() / "publicdata" / "cache" / PACK,
                            "config/packs/gleif; a widened build <data>/publicdata/gleif/packs/gleif")
        return refresh(a)
    except B.Refusal as e:
        print(e, file=sys.stderr)
        return 3


if __name__ == "__main__":
    sys.exit(main())
