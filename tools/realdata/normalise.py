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

"""Turns the raw downloads into one small, deterministic dataset (tools/realdata/dataset/*.json): the pure parsing
functions here take raw text or parsed JSON and return plain dicts, so they are tested offline against recorded fixtures.

Dataset files:
  meta.json       sources (name, url, licence, attribution), retrieval dates, vintages
  countries.json  ISO3 -> {name, iso2, region, income, capital, lon, lat, factbook ... joined key facts}
  groups.json     region and income-group aggregates (World Bank aggregate codes) and the world
  series.json     indicator key -> code -> {year: value} (scaled; countries and groups)
  forecast.json   IMF WEO key -> code -> {year: value} (actuals and projections)
  factbook.json   ISO3 -> parsed Factbook facts (government, executive, legislature, parties, area, languages ...)
"""
from __future__ import annotations

import csv
import html
import io
import json
import re
from pathlib import Path

import yaml

HERE = Path(__file__).resolve().parent
DATASET = HERE / "dataset"
REGION_IDS = {"EAS", "ECS", "LCN", "MEA", "NAC", "SAS", "SSF"}
INCOME_IDS = {"HIC", "UMC", "LMC", "LIC"}
# Factbook internet codes that differ from the ISO 3166 alpha-2 code
INTERNET_TO_ISO2 = {"uk": "GB", "su": "RU", "an": "CW"}


def load_config() -> dict:
    return yaml.safe_load((HERE / "sources.yaml").read_text(encoding="utf-8"))


def sig(v: float, digits: int = 6) -> float:
    """A float rounded to significant digits, so the committed dataset is compact and stable."""
    if v == 0:
        return 0.0
    from math import floor, log10
    r = round(v, digits - 1 - floor(log10(abs(v))))
    return int(r) if r == int(r) and abs(r) < 1e15 else r


# ---------------------------------------------------------------- World Bank
def wb_countries(raw: list) -> tuple[dict, dict]:
    """(countries by ISO3, groups by id) from the /country response."""
    countries, groups = {}, {}
    for c in raw[1]:
        rid = c["region"]["id"]
        if rid == "NA":
            if c["id"] in REGION_IDS | INCOME_IDS | {"WLD"}:
                groups[c["id"]] = {"id": c["id"], "name": c["name"].strip(), "type": "region" if c["id"] in REGION_IDS else "income" if c["id"] in INCOME_IDS else "world"}
            continue
        countries[c["id"]] = {
            "iso3": c["id"], "iso2": c["iso2Code"], "name": c["name"].strip(),
            "region": c["region"]["id"], "regionName": c["region"]["value"].strip(),
            "income": c["incomeLevel"]["id"], "incomeName": c["incomeLevel"]["value"].strip(),
            "capital": c["capitalCity"].strip(), "lon": _f(c["longitude"]), "lat": _f(c["latitude"])}
    return countries, groups


def _f(s):
    try:
        return round(float(s), 3)
    except (TypeError, ValueError):
        return None


def wb_series(raw: list, scale: float, keep: set[str]) -> tuple[dict, str]:
    """({iso3: {year: value}}, lastupdated) from one indicator response, nulls dropped, only the ids in keep."""
    out: dict[str, dict] = {}
    for r in raw[1] or []:
        if r["value"] is None:
            continue
        code = r["countryiso3code"] or r["country"]["id"]
        if code in keep:
            out.setdefault(code, {})[r["date"]] = sig(r["value"] * scale)
    return {k: dict(sorted(v.items())) for k, v in sorted(out.items())}, raw[0].get("lastupdated", "")


# ---------------------------------------------------------------- IMF
def imf_series(raw: dict, code: str, keep: set[str], first: int, last: int) -> dict:
    out = {}
    for iso, by_year in (raw.get("values", {}).get(code) or {}).items():
        if iso in keep:
            ys = {y: sig(v) for y, v in by_year.items() if v is not None and first <= int(y) <= last}
            if ys:
                out[iso] = dict(sorted(ys.items()))
    return dict(sorted(out.items()))


# ---------------------------------------------------------------- UNDP
def decode(raw: bytes) -> str:
    """The UNDP CSV is UTF-8 in some releases and Windows-1252 in others (country names such as Cote d'Ivoire)."""
    try:
        return raw.decode("utf-8-sig")
    except UnicodeDecodeError:
        return raw.decode("cp1252")


def undp_hdi(text: str, keep: set[str], first: int, last: int) -> dict:
    out = {}
    for row in csv.DictReader(io.StringIO(text)):
        iso = row["iso3"]
        if iso not in keep:
            continue
        ys = {str(y): sig(float(row[f"hdi_{y}"])) for y in range(first, last + 1) if row.get(f"hdi_{y}") not in (None, "", "..")}
        if ys:
            out[iso] = ys
    return dict(sorted(out.items()))


# ---------------------------------------------------------------- Factbook
TAG = re.compile(r"<[^>]+>")


def clean(s) -> str:
    """Factbook text with its HTML removed: <br> becomes '; ', entities are decoded, spaces collapsed."""
    if not isinstance(s, str):
        return ""
    s = re.sub(r"<br\s*/?>|</p>|</li>", "; ", s, flags=re.I)
    s = html.unescape(TAG.sub("", s)).replace("\xa0", " ")
    s = re.sub(r"\s+", " ", s).strip()
    s = re.sub(r"(;\s*)+", "; ", s).strip("; ").strip()
    return s


def txt(d, *path) -> str:
    for p in path:
        d = d.get(p) if isinstance(d, dict) else None
        if d is None:
            return ""
    return clean(d.get("text") if isinstance(d, dict) else d)


def parse_seats(s: str) -> list[dict]:
    """'Bharatiya Janata Party (BJP) (240); Indian National Congress (INC) (99); Other (138)' -> party/seats rows."""
    rows = []
    for part in re.split(r";\s*", s):
        m = re.match(r"^(.*?)\s*\((\d[\d,]*)\)\s*$", part.strip())
        if m:
            rows.append({"party": m.group(1).strip(), "seats": int(m.group(2).replace(",", ""))})
    return rows


def parse_parties(raw: str) -> list[str]:
    """The Factbook 'Political parties' text as a list of party names (one per <br> line)."""
    if not isinstance(raw, str):
        return []
    lines = [clean(x) for x in re.split(r"<br\s*/?>", raw, flags=re.I)]
    return [x for x in lines if x and not x.lower().startswith("note")][:40]


def parse_area(d: dict) -> float | None:
    s = txt(d, "Geography", "Area", "total ") or txt(d, "Geography", "Area", "total")
    m = re.match(r"([\d,\.]+)\s*sq km", s)
    return float(m.group(1).replace(",", "")) if m else None


def parse_date(s: str) -> str:
    """The first date in a Factbook phrase, as ISO when it is a plain 'D Month YYYY' / 'M/D/YYYY', else ''."""
    from datetime import datetime
    m = re.search(r"\b(\d{1,2}) (January|February|March|April|May|June|July|August|September|October|November|December) (\d{4})\b", s)
    if m:
        return datetime.strptime(" ".join(m.groups()), "%d %B %Y").date().isoformat()
    m = re.search(r"\b(\d{1,2})/(\d{1,2})/(\d{4})\b", s)
    if m:
        return f"{m.group(3)}-{int(m.group(1)):02d}-{int(m.group(2)):02d}"
    return ""


def chamber(d: dict | None) -> dict | None:
    if not isinstance(d, dict) or not d:
        return None
    seats = txt(d, "parties elected and seats per party")
    return {"name": txt(d, "chamber name"), "seatsText": txt(d, "number of seats"), "system": txt(d, "electoral system"),
            "term": txt(d, "term in office"), "lastElection": txt(d, "most recent election date"),
            "lastElectionIso": parse_date(txt(d, "most recent election date")), "nextElection": txt(d, "expected date of next election"),
            "women": txt(d, "percentage of women in chamber"), "parties": parse_seats(seats)}


def factbook_profile(d: dict) -> dict:
    g = d.get("Government", {})
    ex = g.get("Executive branch", {}) if isinstance(g.get("Executive branch"), dict) else {}
    leg = g.get("Legislative branch", {}) if isinstance(g.get("Legislative branch"), dict) else {}
    cur = txt(d, "Economy", "Exchange rates", "Currency")
    code = re.search(r"\(([A-Z]{3})\)", cur)
    res = {
        "shortName": txt(g, "Country name", "conventional short form"), "longName": txt(g, "Country name", "conventional long form"),
        "capital": txt(g, "Capital", "name"), "governmentType": txt(g, "Government type"),
        "independence": txt(g, "Independence"), "legalSystem": txt(g, "Legal system"), "suffrage": txt(g, "Suffrage"),
        "chiefOfState": txt(ex, "chief of state"), "headOfGovernment": txt(ex, "head of government"), "cabinet": txt(ex, "cabinet"),
        "execProcess": txt(ex, "election/appointment process"), "execLastElection": txt(ex, "most recent election date"),
        "execNextElection": txt(ex, "expected date of next election"), "execResults": txt(ex, "election results")[:600],
        "legislatureName": txt(leg, "legislature name"), "legislatureStructure": txt(leg, "legislative structure"),
        "lowerChamber": chamber(g.get("Legislative branch - lower chamber")), "upperChamber": chamber(g.get("Legislative branch - upper chamber")),
        "parties": parse_parties((g.get("Political parties") or {}).get("text")),
        "areaKm2": parse_area(d), "languages": txt(d, "People and Society", "Languages", "Languages")[:400],
        "religions": txt(d, "People and Society", "Religions")[:300], "currency": code.group(1) if code else "",
        "currencyText": cur[:120], "internetCode": txt(d, "Communications", "Internet country code"),
        "location": txt(d, "Geography", "Location")[:300], "nationalHoliday": txt(g, "National holiday")[:200]}
    if not res["languages"]:
        res["languages"] = clean(next((v.get("text") for k, v in (d.get("People and Society", {}).get("Languages", {}) or {}).items() if isinstance(v, dict)
                                       and k.lower().startswith("languages")), ""))[:400]
    return {k: v for k, v in res.items() if v not in ("", None, [])}


def factbook_to_iso3(profiles: dict[str, dict], countries: dict) -> tuple[dict, list]:
    """Match each Factbook profile to a World Bank country by internet code (ISO2), then by name. Returns ({iso3: profile}, unmatched)."""
    by_iso2 = {c["iso2"].upper(): iso for iso, c in countries.items()}
    by_name = {_norm(c["name"]): iso for iso, c in countries.items()}
    out, unmatched = {}, []
    for gec, p in profiles.items():
        code = p.get("internetCode", "").lstrip(".").lower()
        iso = by_iso2.get(INTERNET_TO_ISO2.get(code, code.upper())) if code else None
        iso = iso or by_name.get(_norm(p.get("shortName", ""))) or by_name.get(_norm(p.get("longName", "")))
        if iso and iso not in out:
            out[iso] = {**p, "gec": gec.upper()}
        else:
            unmatched.append(gec)
    return out, unmatched


def _norm(s: str) -> str:
    s = s.lower().replace("&", "and")
    return re.sub(r"[^a-z]", "", re.sub(r"\(.*?\)|,.*$", "", s))


# ---------------------------------------------------------------- the run
def run(cache: Path, cfg: dict) -> dict:
    DATASET.mkdir(exist_ok=True)
    yrs, iy = cfg["years"], cfg["imf_years"]
    countries, groups = wb_countries(json.loads((cache / "wb_country.json").read_text(encoding="utf-8")))
    keep = set(countries) | set(groups)
    series, vintages = {}, {}
    for ind in cfg["indicators"]:
        k, kind = ind["key"], ind["kind"]
        if kind in ("wdi", "wgi"):
            p = cache / "wb" / f"{ind['code']}.json"
            if not p.exists():
                continue
            series[k], vintages[ind["kind"]] = wb_series(json.loads(p.read_text(encoding="utf-8")), ind["scale"], keep)
        elif kind == "imf":
            p = cache / "imf" / f"{ind['code']}.json"
            if p.exists():
                series[k] = imf_series(json.loads(p.read_text(encoding="utf-8")), ind["code"], keep, yrs["first"], iy["last"])
        elif kind == "undp":
            p = cache / "undp_hdi.csv"
            if p.exists():
                series[k] = undp_hdi(decode(p.read_bytes()), keep, yrs["first"], yrs["last"])
    forecast = {}
    for f in cfg["forecast"]:
        p = cache / "imf" / f"{f['code']}.json"
        if p.exists():
            forecast[f["key"]] = imf_series(json.loads(p.read_text(encoding="utf-8")), f["code"], set(countries), iy["first"], iy["last"])
    profiles = {p.stem: factbook_profile(json.loads(p.read_text(encoding="utf-8"))) for p in sorted((cache / "factbook").glob("*.json"))} \
        if (cache / "factbook").exists() else {}
    factbook, unmatched = factbook_to_iso3(profiles, countries)
    imf_ind = json.loads((cache / "imf_indicators.json").read_text(encoding="utf-8")).get("indicators", {}) if (cache / "imf_indicators.json").exists() else {}
    fetched = json.loads((cache / "fetch.json").read_text()) if (cache / "fetch.json").exists() else {}
    ref = json.loads((cache / "factbook_ref.json").read_text()) if (cache / "factbook_ref.json").exists() else {}
    meta = {"sources": cfg["sources"], "years": yrs, "imf_years": iy,
            "vintage": {"wdi": "updated " + vintages.get("wdi", ""), "wgi": "updated " + vintages.get("wgi", ""),
                        "imf": ((re.search(r"\(([^)]+)\)", (imf_ind.get("NGDP_RPCH") or {}).get("source", "")) or [None, ""])[1]) + " edition",
                        "undp": "2025 report", "factbook": "mirror commit " + ref.get("commit", "")[:10]},
            "retrieved": _retrieved(fetched), "factbookUnmatched": sorted(unmatched)}
    series = {k: {c: v for c, v in s.items()} for k, s in series.items()}
    for name, obj in (("meta", meta), ("countries", countries), ("groups", groups), ("series", series), ("forecast", forecast), ("factbook", factbook)):
        (DATASET / f"{name}.json").write_text(json.dumps(obj, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n", encoding="utf-8")
    return {"countries": len(countries), "series": sum(len(s) for s in series.values()), "factbook": len(factbook)}


def _retrieved(fetched: dict) -> dict:
    """The latest retrieval date per source from the fetch log's {relative path: date}."""
    out: dict[str, str] = {}
    for path, d in fetched.items():
        src = "wgi" if path.startswith("wb/GOV_WGI") else "wdi" if path.startswith("wb") else "imf" if path.startswith("imf") else "undp" if path.startswith("undp") else "factbook" if path.startswith("factbook") else ""
        if src and d > out.get(src, ""):
            out[src] = d
    return out


def load() -> dict:
    """The committed dataset, for the pack generators."""
    return {n: json.loads((DATASET / f"{n}.json").read_text(encoding="utf-8")) for n in ("meta", "countries", "groups", "series", "forecast", "factbook")}
