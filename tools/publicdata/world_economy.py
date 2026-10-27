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

"""Refresh the world-economy pack from its public sources (World Bank WDI and WGI, IMF WEO, UNDP HDI, CIA World Factbook).

    python3 tools/publicdata/world_economy.py status
    python3 tools/publicdata/world_economy.py refresh --dry-run
    python3 tools/publicdata/world_economy.py refresh                       a full build in <data>/publicdata/world-economy
    python3 tools/publicdata/world_economy.py refresh --internal-ok         refresh the committed dataset and pack (carries IMF data)
    python3 tools/publicdata/world_economy.py refresh --no-imf              a build without any IMF data, in the data folder
    python3 tools/publicdata/world_economy.py refresh worldbank --since 2000 --scope SP.DYN.LE00.IN,EN.ATM.CO2E.PC:social

It wraps tools/realdata (the fetcher's per-source downloads, the normaliser, the pack generator and its check), so a refresh
of the committed pack writes exactly what `tools/realdata/realdata.py all` writes. The IMF's terms allow reuse with
attribution but not commercial reuse without permission, so the IMF data is internal-demo only: the committed folders are
written only with --internal-ok, and every other build goes to the data folder. A widened build (--years, --since, --scope)
also goes to the data folder: to widen the committed pack, add the indicator to tools/realdata/sources.yaml and refresh.
Guide: docs/guides/PUBLIC_DATA.md.
"""
from __future__ import annotations

import copy
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import pdbase as B  # noqa: E402

REALDATA = B.ROOT / "tools" / "realdata"
sys.path.insert(0, str(REALDATA))
import fetch as F  # noqa: E402
import normalise as N  # noqa: E402
import realdata as RD  # noqa: E402

PACK = "world-economy"
GENERATOR = B.ROOT / "tools" / "packgen" / "world" / "make.py"
COMMITTED = [N.DATASET, B.ROOT / "config" / "packs" / PACK, B.ROOT / "tools" / "packgen" / "world"]
IMF_WHY = ("the IMF's terms allow reuse with attribution, but commercial reuse needs the IMF's permission; "
           "the owner kept it for internal demos only (2026-10-10)")


class _Fetcher(F.Fetcher):
    """tools/realdata/fetch.py with its downloads sent through the shared polite client and cache (same files, same layout)."""

    def __init__(self, run: B.Run, cfg: dict, no_imf: bool):
        super().__init__(run.cache.folder, cfg, run.a.pause, False, run.log, no_imf)
        self.http = run.http

    def get(self, url: str, dest: Path, binary: bool = False) -> bytes | None:
        fresh = not (dest.is_file() and (self.http.offline or self.http.cache.fresh(dest, self.http.max_age_h, url)))
        body = self.http.get(url, dest)
        if body is not None and fresh:
            from datetime import datetime, timezone
            self.retrieved[str(dest.relative_to(self.cache))] = datetime.now(timezone.utc).strftime("%Y-%m-%d")
        return body


def _cfg_sources(cfg: dict) -> dict:
    return cfg["sources"]


class WorldBank(B.Source):
    name, title, pack = "worldbank", "World Bank: World Development Indicators and Worldwide Governance Indicators", PACK
    url, licence = "https://data.worldbank.org", "CC BY 4.0"
    licence_url = "https://datacatalog.worldbank.org/public-licenses#cc-by"
    scope_help = "more WDI/WGI indicator codes, CODE or CODE:group (group economy|labour|fiscal|social|governance; default economy)"
    cache_prefixes = ("wb_country", "wb/", "wb_indicator/")

    def widen(self, run: B.Run, cfg: dict) -> None:
        api, have = cfg["sources"]["wdi"]["api"], {i["code"] for i in cfg["indicators"]}
        for item in run.scope:
            code, _, group = item.partition(":")
            if code in have or "." not in code:
                continue
            raw = run.http.get(f"{api}/indicator/{code}?format=json", run.cache.folder / "wb_indicator" / f"{code}.json")
            meta = (json.loads(raw)[1] or [{}])[0] if raw else {}
            if raw and len(json.loads(raw)) < 2:
                raise B.Refusal(f"the World Bank has no indicator {code}")
            cfg["indicators"].append(indicator(code, meta.get("name") or code, meta.get("unit") or "",
                                               meta.get("sourceNote") or meta.get("name") or code,
                                               "wgi" if (meta.get("source") or {}).get("id") == "3" else "wdi", group or "economy"))

    def plan(self, run: B.Run) -> list[str]:
        api, y = run.cfg["sources"]["wdi"]["api"], run.cfg["years"]
        return [f"{api}/country?format=json&per_page=400"] + [
            f"{api}/country/all/indicator/{i['code']}?format=json&date={y['first']}:{y['last']}&per_page=20000"
            for i in run.cfg["indicators"] if i["kind"] in ("wdi", "wgi")]

    def fetch(self, run: B.Run) -> None:
        run.fetcher.worldbank()

    def normalise(self, run: B.Run) -> dict:
        c = run.cache.folder
        countries, groups = N.wb_countries(json.loads((c / "wb_country.json").read_text(encoding="utf-8")))
        keep, rows = set(countries) | set(groups), 0
        for i in run.cfg["indicators"]:
            p = c / "wb" / f"{i['code']}.json"
            if i["kind"] in ("wdi", "wgi") and p.is_file():
                rows += sum(len(v) for v in N.wb_series(json.loads(p.read_text(encoding="utf-8")), i["scale"], keep)[0].values())
        return {"rows": rows, "countries": len(countries)}


class Imf(B.Source):
    name, title, pack = "imf", "IMF World Economic Outlook (DataMapper)", PACK
    url, licence = "https://www.imf.org/external/datamapper", "IMF terms: attribution; no commercial reuse without permission"
    licence_url, use, why_internal = "https://www.imf.org/en/About/copyright-and-terms", B.INTERNAL, IMF_WHY
    scope_help = "more WEO series codes, CODE or CODE:group (see imf_indicators.json in the cache for the codes)"
    cache_prefixes = ("imf",)

    def widen(self, run: B.Run, cfg: dict) -> None:
        api, have = cfg["sources"]["imf"]["api"], {i["code"] for i in cfg["indicators"]}
        raw = run.http.get(f"{api}/indicators", run.cache.folder / "imf_indicators.json")
        known = json.loads(raw).get("indicators", {}) if raw else {}
        for item in run.scope:
            code, _, group = item.partition(":")
            if code in have or "." in code:
                continue
            if known and code not in known:
                raise B.Refusal(f"the IMF DataMapper has no series {code}")
            m = known.get(code) or {}
            cfg["indicators"].append(indicator(code, m.get("label") or code, m.get("unit") or "", m.get("description") or code, "imf",
                                               group or "economy"))

    def plan(self, run: B.Run) -> list[str]:
        api = run.cfg["sources"]["imf"]["api"]
        codes = sorted({i["code"] for i in run.cfg["indicators"] if i["kind"] == "imf"} | {f["code"] for f in run.cfg["forecast"]})
        return [f"{api}/indicators"] + [f"{api}/{c}" for c in codes]

    def fetch(self, run: B.Run) -> None:
        run.fetcher.imf()

    def normalise(self, run: B.Run) -> dict:
        c, iy, rows = run.cache.folder, run.cfg["imf_years"], 0
        keep = set(N.wb_countries(json.loads((c / "wb_country.json").read_text(encoding="utf-8")))[0])
        for code in {i["code"] for i in run.cfg["indicators"] if i["kind"] == "imf"} | {f["code"] for f in run.cfg["forecast"]}:
            p = c / "imf" / f"{code}.json"
            if p.is_file():
                rows += sum(len(v) for v in N.imf_series(json.loads(p.read_text(encoding="utf-8")), code, keep, run.cfg["years"]["first"], iy["last"]).values())
        return {"rows": rows}


class Undp(B.Source):
    name, title, pack = "undp", "UNDP Human Development Report: HDI time series", PACK
    url, licence, licence_url = "https://hdr.undp.org/data-center", "CC BY 3.0 IGO", "https://creativecommons.org/licenses/by/3.0/igo/"
    cache_prefixes = ("undp",)

    def plan(self, run: B.Run) -> list[str]:
        return [run.cfg["sources"]["undp"]["csv"]]

    def fetch(self, run: B.Run) -> None:
        run.fetcher.undp()

    def normalise(self, run: B.Run) -> dict:
        p, y = run.cache.folder / "undp_hdi.csv", run.cfg["years"]
        if not p.is_file():
            return {"rows": 0}
        keep = {r["iso3"] for r in __import__("csv").DictReader(N.decode(p.read_bytes()).splitlines())}
        return {"rows": sum(len(v) for v in N.undp_hdi(N.decode(p.read_bytes()), keep, y["first"], y["last"]).values())}


class Factbook(B.Source):
    name, title, pack = "factbook", "CIA World Factbook (factbook.json mirror, pinned commit)", PACK
    url, licence = "https://www.cia.gov/the-world-factbook/", "Public domain; the factbook.json mirror is CC0 1.0"
    licence_url = "https://github.com/factbook/factbook.json/blob/master/LICENSE.md"
    cache_prefixes = ("factbook",)

    def plan(self, run: B.Run) -> list[str]:
        s = run.cfg["sources"]["factbook"]
        return [f"{s['api']}/commits/master", f"{s['api']}/git/trees/<sha>?recursive=1", f"{s['raw']}/<sha>/<region>/<gec>.json (about 260 files)"]

    def fetch(self, run: B.Run) -> None:
        run.fetcher.factbook()

    def normalise(self, run: B.Run) -> dict:
        files = sorted((run.cache.folder / "factbook").glob("*.json"))
        return {"rows": sum(1 for p in files if N.factbook_profile(json.loads(p.read_text(encoding="utf-8"))))}


SOURCES = [WorldBank(), Imf(), Undp(), Factbook()]


def indicator(code: str, name: str, unit: str, definition: str, kind: str, group: str) -> dict:
    """A sources.yaml indicator entry for a code added with --scope (key: the code in lower case, dots as underscores)."""
    if group not in ("economy", "labour", "fiscal", "social", "governance"):
        raise B.Refusal(f"{code}: unknown group {group!r}; use economy, labour, fiscal, social or governance")
    key = code.lower().replace(".", "_")
    return {"key": key, "kind": kind, "code": code, "group": group, "name": name.strip(), "unit": unit or "value", "scale": 1, "dp": 2,
            "def": " ".join(definition.split())[:600]}


# ---------------------------------------------------------------- the run

def plan_cfg(run: B.Run, selected: list[B.Source], dry: bool) -> tuple[dict, bool]:
    """The configuration of this run: the committed sources.yaml, widened by --years/--since/--scope (scope metadata is fetched)."""
    base = N.load_config()
    cfg = copy.deepcopy(base)
    y = cfg["years"]
    y["first"] = run.first_year(y["first"], y["last"])
    cfg["imf_years"]["first"] = min(cfg["imf_years"]["first"], y["first"])
    if run.scope and not dry:
        for s in selected:
            if hasattr(s, "widen"):
                s.widen(run, cfg)
    widened = cfg != base or bool(run.scope)
    return cfg, widened


def resolve_out(a, no_imf: bool, widened: bool) -> tuple[Path, bool]:
    """(the output folder, True when it is the committed dataset and pack)."""
    default = B.data_dir() / "publicdata" / PACK
    if a.out:
        out = Path(a.out).expanduser().resolve()
        if B.is_committed(out):
            if out not in [p.resolve() for p in COMMITTED] + [B.ROOT.resolve()]:
                raise B.Refusal(f"refused: {out} is inside the repository but is not where this pack lives; the committed refresh writes "
                                f"{', '.join(str(p.relative_to(B.ROOT)) for p in COMMITTED)} (leave out --out and pass --internal-ok)")
            committed = True
        else:
            committed = False
    else:
        committed = a.internal_ok and not no_imf and not widened
        out = B.ROOT if committed else default
    if committed and no_imf:
        raise B.Refusal("refused: a build without IMF data would drop the forecasts the committed pack carries; it goes to the data folder "
                        f"({default}). Leave out --out.")
    if committed and widened:
        raise B.Refusal("refused: --years, --since and --scope widen a build in the data folder only. To widen the committed pack, add the "
                        "indicators to tools/realdata/sources.yaml (years, indicators) and run refresh --internal-ok again.")
    return out, committed


def refresh(a, log=print) -> int:
    no_imf = a.no_imf
    selected = [s for s in B.pick(SOURCES, a.sources) if not (no_imf and s.name == "imf")]
    probe = B.Run(PACK, a, B.data_dir(), log)
    cfg, widened = plan_cfg(probe, selected, a.dry_run)
    out, committed = resolve_out(a, no_imf, widened)
    carried = selected + ([] if no_imf or any(s.name == "imf" for s in selected) else [SOURCES[1]])   # the dataset joins every cached source
    B.guard_out(carried, B.ROOT / "config" / "packs" / PACK if committed else out, a.internal_ok)
    run = B.Run(PACK, a, out, log)
    run.cfg = cfg
    where = "the committed dataset and pack (" + ", ".join(str(p.relative_to(B.ROOT)) for p in COMMITTED) + ")" if committed else str(out)
    if not a.out and not committed and not no_imf and not a.internal_ok:
        log(f"note: the build carries IMF data ({IMF_WHY}), so it goes to the data folder; --internal-ok refreshes the committed pack")
    if a.dry_run:
        log(f"dry run: {PACK}, sources {', '.join(s.name for s in selected)}, years {cfg['years']['first']}-{cfg['years']['last']}"
            + (", without IMF" if no_imf else "") + (", widened" if widened else ""))
        for s in selected:
            urls = s.plan(run)
            hits = sum(1 for p in s.cache_prefixes for _ in run.cache.folder.glob(p + "*"))
            log(f"  {s.name} ({s.licence}; {s.use}): {len(urls)} request(s), cache {run.cache.folder} ({'has files' if hits else 'empty'})")
            for u in urls[:3]:
                log(f"    {u}")
            if run.scope and s.scope_help:
                log(f"    --scope adds: {', '.join(run.scope)}")
        log(f"  then: normalise -> {where}; run the pack generator; then its --check")
        return 0
    run.fetcher = _Fetcher(run, cfg, no_imf)
    man = B.Manifest(PACK)
    for s in selected:
        log(f"fetch {s.name}")
        s.fetch(run)
    run.cache.save()
    fetch_log = run.cache.folder / "fetch.json"
    old = json.loads(fetch_log.read_text()) if fetch_log.exists() else {}
    old.update(run.fetcher.retrieved)
    fetch_log.write_text(json.dumps(old, indent=1, sort_keys=True), encoding="utf-8")
    if not (run.cache.folder / "wb_country.json").is_file():
        raise B.Refusal(f"the cache {run.cache.folder} has no World Bank country list; refresh worldbank first (the dataset joins every source)")
    missing = [s.name for s in SOURCES if not (no_imf and s.name == "imf") and not s.cache_size(run.cache.folder)]
    if committed and missing:
        raise B.Refusal(f"refused: the committed dataset joins every source, and the cache {run.cache.folder} has nothing from "
                        f"{', '.join(missing)}; refresh those too (or all of them) so the committed pack loses nothing")
    for s in selected:
        counts = s.normalise(run)
        man.record(s, s.plan(run), counts, out)
        log(f"normalise {s.name}: {counts.get('rows', 0)} rows")
    rc = write_committed(run, a) if committed else write_build(run, a, cfg, no_imf)
    files = sorted((N.DATASET if committed else out / "dataset").glob("*.json*"))   # the .jsonl records and meta.json
    man.finish(out, files, sys.argv[1:], rc == 0 and not run.http.failed)
    log(f"downloads: {run.http.downloaded} new, {run.http.reused} reused" + (f", {len(run.http.failed)} failed" if run.http.failed else ""))
    return rc or (2 if run.http.failed else 0)


def write_committed(run: B.Run, a) -> int:
    """What realdata.py all writes after its fetch: normalise, about, write, docs; then its check."""
    c = ["--cache", str(run.cache.folder)]
    rc = RD.main(["normalise"] + c) | RD.main(["about"]) | RD.main(["write"]) | RD.main(["docs"])
    if not a.no_check and not rc:
        rc |= RD.main(["check"] + c)
    return rc


def write_build(run: B.Run, a, cfg: dict, no_imf: bool) -> int:
    """A build outside the repository: <out>/sources.yaml, dataset/, packs/world-economy/, about/; then the generator's --check."""
    import yaml
    out = run.out
    (out / "dataset").mkdir(parents=True, exist_ok=True)
    head = "\n".join((REALDATA / "sources.yaml").read_text(encoding="utf-8").split("\n")[:13])
    (out / "sources.yaml").write_text(head + "\n# written by tools/publicdata/world_economy.py for this build\n"
                                      + yaml.safe_dump(cfg, sort_keys=False, allow_unicode=True, width=200), encoding="utf-8")
    env = dict(os.environ, **{N.ENV_SOURCES: str(out / "sources.yaml"), N.ENV_DATASET: str(out / "dataset"),
                              "DRISHTI_PACKS_OUT": str(out / "packs"), "DRISHTI_ABOUT_DIR": str(out / "about")})
    if no_imf:
        env[RD.BD.ENV_NO_IMF] = "1"
    n = N.run(run.cache.folder, cfg, out / "dataset", no_imf)
    for name in RD.HAND_KEPT:
        shutil.copyfile(N.DATASET / name, out / "dataset" / name)
    run.log(f"dataset: {n['countries']} countries, {n['series']} series, {n['factbook']} factbook profiles -> {out / 'dataset'}")
    rc = subprocess.call([sys.executable, str(GENERATOR)], cwd=B.ROOT, env=env)
    if not a.no_check and not rc:
        rc = subprocess.call([sys.executable, str(GENERATOR), "--check"], cwd=B.ROOT, env=env)
    run.log(f"pack: {out / 'packs' / PACK}" if not rc else "the pack generator failed")
    return rc


def main(argv: list[str] | None = None) -> int:
    ap = B.parser("world_economy.py", __doc__, SOURCES,
                  extra=lambda r: r.add_argument("--no-imf", action="store_true", help="no IMF data at all (fetch, dataset, pack); always the data folder"))
    a = ap.parse_args(argv)
    try:
        if a.command == "status":
            return B.status(PACK, SOURCES, Path(a.cache) if a.cache else B.data_dir() / "publicdata" / "cache" / PACK,
                            "the committed dataset and pack with --internal-ok; else <data>/publicdata/world-economy")
        return refresh(a)
    except B.Refusal as e:
        print(e, file=sys.stderr)
        return 3


if __name__ == "__main__":
    sys.exit(main())
