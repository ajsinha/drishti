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

"""Download the gleif pack's public data into a cache folder (the network; make.py then builds the pack offline).

Everything is GLEIF's, CC0 1.0 (https://www.gleif.org/en/meta/lei-data-terms-of-use):
  golden copy, level 2   the relationship records (rr: who consolidates whom, direct and ultimate, with periods and status) and
                         the reporting exceptions (repex: why no parent is reported), the whole files (about 24 and 62 MB zipped)
  LEI API                the level 1 record of every entity of the pack (200 a request, filter[lei]), the entity legal form
                         (ELF) code list, the country names and the LEI issuers (LOUs)
  mapping files          BIC to LEI, ISIN to LEI, MIC to LEI and OpenCorporates to LEI (the ISIN file is about 33 MB zipped)

The pack's entities (the universe) are the ownership groups of its seeds: the LEIs of the us-equities companies, and the banks,
insurers, asset managers and market infrastructures of SEEDS (the FSB's G-SIBs and other large financial institutions). A group is
the seed's ultimate parent with every entity it consolidates (direct children, their children, and every entity naming it as
ultimate parent), at most GROUP_CAP of them a group (the nearest levels first).

    python3 tools/packgen/gleif/fetch.py [--cache DIR] [--only golden,mapping,api] [--extra LEI,LEI]

The default cache is data/cache/gleif (git-ignored). Files already in the cache are kept: delete one to fetch it again. The big
downloads are reduced into small files beside them (reduced/), which is all make.py reads. GLEIF's API allows 60 requests a minute;
this keeps a gap of just over a second.
"""
from __future__ import annotations

import argparse
import csv
import gzip
import hashlib
import io
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
DEFAULT_CACHE = ROOT / "data" / "cache" / "gleif"
USER_AGENT = os.environ.get("DRISHTI_GLEIF_USER_AGENT", "Drishti research ajsinha@gmail.com")
API = "https://api.gleif.org/api/v1"
GOLDEN = "https://goldencopy.gleif.org/api/v2/golden-copies/publishes/latest"
MAPPING = "https://mapping.gleif.org/api/v2/{name}/latest"
MAPPINGS = ("bic-lei", "isin-lei", "mic-lei", "oc-lei")
US_EQUITIES = ROOT / "config" / "packs" / "us-equities" / "samples" / "us-company.jsonl"
GROUP_CAP = 50           # entities a group at most (the ultimate parent and the nearest levels first)
ISIN_SAMPLE = 5          # ISINs kept a seed entity as lei-mapping documents (every entity carries its ISIN count)
BATCH = 200              # LEIs a lei-records request
CONSOLIDATION = ("IS_DIRECTLY_CONSOLIDATED_BY", "IS_ULTIMATELY_CONSOLIDATED_BY")

GSIB, BANK, INSURER, MANAGER, INFRA = "G-SIB", "Bank", "Insurer", "Asset manager", "Market infrastructure"
# (segment, label, LEI): the FSB's list of global systemically important banks (2024) and other large financial institutions,
# resolved once by legal name and country through the LEI API and pinned here, so a refresh asks for exactly these.
SEEDS: list[tuple[str, str, str]] = [
    (GSIB, "JPMorgan Chase & Co.", "8I5DZWZKVSZI1NUHU748"), (GSIB, "Bank of America", "9DJT3UXIJIZJI4WXO774"),
    (GSIB, "Citigroup", "6SHGI4ZSSLCXXQSBB395"), (GSIB, "HSBC Holdings", "MLU0ZO3ML4LN2LL2TL39"),
    (GSIB, "Agricultural Bank of China", "549300E7TSGLCOVSY746"), (GSIB, "Bank of China", "54930053HGCFWVHYZX42"),
    (GSIB, "Barclays", "213800LBQA1Y9L22JB70"), (GSIB, "BNP Paribas", "R0MUWSFPU8MPRO8K5P83"),
    (GSIB, "China Construction Bank", "5493001KQW6DM7KEDR62"), (GSIB, "Deutsche Bank", "7LTWFZYICNSX8D621K86"),
    (GSIB, "Goldman Sachs", "784F5XWPLTWKTBV3E584"), (GSIB, "Industrial and Commercial Bank of China", "5493002ERZU2K9PZDL40"),
    (GSIB, "Mitsubishi UFJ Financial Group", "353800V2V8PUY9TK3E06"), (GSIB, "UBS Group", "549300SZJ9VS8SGXAN81"),
    (GSIB, "Bank of Communications", "549300AX1UM10U30HK09"), (GSIB, "Bank of New York Mellon", "WFLLPEPC7FZXENRZV188"),
    (GSIB, "Groupe BPCE", "9695005MSX1OYEMGDF46"), (GSIB, "Crédit Agricole", "969500TJ5KRTCJQWXH05"),
    (GSIB, "ING Groep", "549300NYKK9MWM7GGW15"), (GSIB, "Mizuho Financial Group", "353800CI5L6DDAN5XZ33"),
    (GSIB, "Morgan Stanley", "IGJSJL3JD5P30I6NJZ34"), (GSIB, "Royal Bank of Canada", "ES7IP3U3RHIGC71XBU11"),
    (GSIB, "Banco Santander", "5493006QMFDDMYWIAM13"), (GSIB, "Société Générale", "O2RNE8IBXP4R0TD8PU41"),
    (GSIB, "Standard Chartered", "U4LOSYZ7YG4W3S5F2G91"), (GSIB, "State Street", "549300ZFEEJ2IP5VME73"),
    (GSIB, "Sumitomo Mitsui Financial Group", "35380028MYWPB6AUO129"), (GSIB, "Toronto-Dominion Bank", "PT3QB789TSUIDF371261"),
    (GSIB, "Wells Fargo", "PBLD0EJDB5FWOLXP3B76"),
    (BANK, "NatWest Group", "2138005O9XJIJN4JPN90"), (BANK, "Lloyds Banking Group", "549300PPXHEU2JF0AM85"),
    (BANK, "Commerzbank", "851WYGNLUQLFZBSYGB56"), (BANK, "Intesa Sanpaolo", "2W8N8UU78PMDQKZENC08"),
    (BANK, "UniCredit", "549300TRUWO2CD2G5692"), (BANK, "BBVA", "K8MS7FD7N5Z2WQ51AZ71"), (BANK, "CaixaBank", "7CUNS533WID6K7DGFI87"),
    (BANK, "Nordea Bank", "529900ODI3047E2LIV03"), (BANK, "Danske Bank", "MAES062Z21O4RZ2U7M96"), (BANK, "ABN AMRO", "BFXS5XCH7N0Y05NIXW11"),
    (BANK, "Rabobank", "DG3RU1DBUFHT4ZF9WN62"), (BANK, "KBC Groep", "213800X3Q9LSAKRUWY91"), (BANK, "DBS Group", "5493007FKT78NKPM5V55"),
    (BANK, "OCBC", "5493007O3QFXCPOGWK22"), (BANK, "United Overseas Bank", "IO66REGK3RCBAMA8HR66"),
    (BANK, "Commonwealth Bank of Australia", "MSFSBD3QN1GSN7Q6C537"), (BANK, "Westpac", "EN5TNI6CI43VEPAMHL14"),
    (BANK, "ANZ", "JHE42UYNWWTJB8YTTU19"), (BANK, "National Australia Bank", "F8SB4JFBSYQFRQEH3Z21"),
    (BANK, "Bank of Montreal", "NQQ6HPCNCCU6TUTQYE16"), (BANK, "Scotiabank", "L3I9ZG2KFGXZ61BMYR72"), (BANK, "CIBC", "2IGI19DL77OX0HC3ZE78"),
    (BANK, "Nomura Holdings", "549300B3CEAHYG7K8164"), (BANK, "Itaú Unibanco", "5493002W2IVG62O3ZJ94"),
    (BANK, "State Bank of India", "5493001JZ37UBBZF6L49"), (BANK, "HDFC Bank", "335800ZQ6I4E2JXENC50"),
    (BANK, "Standard Bank Group", "2549003PEZXUT7MDBU41"), (BANK, "Capital One", "ZUE8T73ROZOF6FLBAR73"), (BANK, "PNC", "CFGNEKW0P8842LEUIA51"),
    (BANK, "U.S. Bancorp", "N1GZ7BBF3NP8GI976H15"), (BANK, "Truist", "549300DRQQI75D2JP341"), (BANK, "Charles Schwab", "549300VSGCJ7E698NM85"),
    (BANK, "Macquarie Group", "ACMHD8HWFMFUIQQ8Y590"), (BANK, "China Merchants Bank", "549300MKO5B60FFIHF58"),
    (BANK, "Postal Savings Bank of China", "300300C1040311005298"), (BANK, "Erste Group", "PQOH26KWDF7CG10L6792"),
    (BANK, "Handelsbanken", "NHBDILHZTYCNBV5UYZ31"), (BANK, "SEB", "F3JS33DEI6XQ4ZBPTN86"), (BANK, "DNB Bank", "549300GKFG0RYRRQ1414"),
    (BANK, "KB Financial Group", "529900TKE4MXG3Q6GW86"),
    (INSURER, "Allianz", "529900K9B0N5BT694847"), (INSURER, "AXA", "F5WCUMTUM4RKZ1MAIE39"), (INSURER, "Generali", "549300X5UKJVE386ZB61"),
    (INSURER, "Zurich Insurance Group", "529900QVNRBND50TXP03"), (INSURER, "Munich Re", "529900MUF4C20K50JS49"),
    (INSURER, "Swiss Re", "529900PS9YW3YY29F326"), (INSURER, "Prudential plc", "5493001Z3ZE83NGK8Y12"),
    (INSURER, "Legal & General", "213800JH9QQWHLO99821"), (INSURER, "Aviva", "YF0Y5B0IB8SM0ZFG9G81"), (INSURER, "MetLife", "C4BXATY60WC6XEOZDX54"),
    (INSURER, "Prudential Financial", "5PRBRS5FEH7NREC8OR45"), (INSURER, "AIG", "ODVCVCQG2BP6VHV36M30"), (INSURER, "Chubb", "E0JAN6VLUDI1HITHT809"),
    (INSURER, "Ping An", "529900M9MC28JLN35U89"), (INSURER, "China Life Insurance", "549300J6TFLDXGH6S048"), (INSURER, "AIA Group", "ZP5ILWVSYE4LJGMMVD57"),
    (INSURER, "Manulife", "5493007GBX87QOZACS27"), (INSURER, "Sun Life", "549300V040JZX5S66Z78"), (INSURER, "Tokio Marine", "5299009QN2NZ191KLS29"),
    (INSURER, "Hannover Re", "529900KIN5BE45V5KB18"), (INSURER, "Allstate", "OBT0W1ED8G0NWVOLOJ77"), (INSURER, "Travelers", "549300Y650407RU8B149"),
    (MANAGER, "BlackRock", "529900VBK42Y5HHRMD23"), (MANAGER, "Vanguard", "5493002789CX3L0CJP65"), (MANAGER, "Fidelity (FMR)", "6X064LF7Y6B4DKF2GZ26"),
    (MANAGER, "Amundi", "96950010FL2T1TJKR531"), (MANAGER, "Invesco", "ECPGFXU8A2SHKVVGJI15"), (MANAGER, "Franklin Templeton", "RIFQSET379FOGTEFKS80"),
    (MANAGER, "T. Rowe Price", "549300SIV6FPS9Y7IH33"), (MANAGER, "Schroders", "2138001YYBULX5SZ2H24"), (MANAGER, "Northern Trust", "549300GLF98S992BC502"),
    (MANAGER, "Blackstone", "5299004LW4QWGZUB8Y96"), (MANAGER, "KKR", "54930013V5I303TF9571"), (MANAGER, "Apollo", "254900LMFT1CY9IYB476"),
    (MANAGER, "Brookfield", "C6J3FGIWG6MBDGTE8F80"), (MANAGER, "TIAA", "O06XD7BJ4EPXNABVFN69"),
    (INFRA, "CME Group", "LCZ7XYGSLJUHFXXNXD88"), (INFRA, "Intercontinental Exchange", "5493000F4ZO33MV32P92"),
    (INFRA, "London Stock Exchange Group", "213800QAUUUP6I445N30"), (INFRA, "Deutsche Börse", "529900G3SW56SHYNPR95"),
    (INFRA, "Euroclear Holding", "549300IQZVZ949N37S44"), (INFRA, "DTCC", "MLDY5N6PZ58ZE60QU102"), (INFRA, "Nasdaq", "549300L8X1Q78ERXFD06"),
    (INFRA, "S&P Global", "Y6X4K52KMJMZE7I7MY94"), (INFRA, "Moody's", "549300GCEDD8YCF5WU84"),
]


class Http:
    """A polite client: one request at a time, a minimum gap per host, gzip accepted, three tries."""

    def __init__(self) -> None:
        self.last: dict[str, float] = {}
        self.gap = {"api.gleif.org": 1.05, "goldencopy.gleif.org": 1.0, "mapping.gleif.org": 1.0}

    def get(self, url: str, accept: str = "*/*") -> bytes | None:
        host = urllib.parse.urlparse(url).hostname or ""
        for attempt in range(3):
            wait = self.last.get(host, 0) + self.gap.get(host, 1.0) - time.monotonic()
            if wait > 0:
                time.sleep(wait)
            self.last[host] = time.monotonic()
            req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Accept": accept, "Accept-Encoding": "gzip"})
            try:
                with urllib.request.urlopen(req, timeout=300) as r:
                    body = r.read()
                    return gzip.decompress(body) if r.headers.get("Content-Encoding") == "gzip" else body
            except urllib.error.HTTPError as e:
                if e.code == 404:
                    return None
                if e.code in (429, 500, 502, 503, 504):
                    time.sleep(10 * (attempt + 1))
                    continue
                print(f"  {url}: HTTP {e.code}", file=sys.stderr)
                return None
            except (urllib.error.URLError, TimeoutError, OSError) as e:
                print(f"  {url}: {e}", file=sys.stderr)
                time.sleep(3 * (attempt + 1))
        return None


def _save(path: Path, data) -> None:
    """Write atomically: bytes as they are, anything else as compact JSON."""
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(path.name + ".part")
    if isinstance(data, bytes):
        tmp.write_bytes(data)
    else:
        tmp.write_text(data if isinstance(data, str) else json.dumps(data, ensure_ascii=False, separators=(",", ":"), sort_keys=True),
                       encoding="utf-8")
    tmp.replace(path)


def _load(path: Path, default=None):
    return json.loads(path.read_text(encoding="utf-8")) if path.is_file() else default


def _csv(zip_path: Path):
    """The rows of the one CSV inside a GLEIF zip, as dicts, streamed."""
    with zipfile.ZipFile(zip_path) as z:
        name = next(n for n in z.namelist() if n.endswith(".csv"))
        with z.open(name) as raw:
            yield from csv.DictReader(io.TextIOWrapper(raw, encoding="utf-8-sig", newline=""))


# ---------------------------------------------------------------- the golden copy: relationships and reporting exceptions

def golden(http: Http, cache: Path) -> None:
    meta = cache / "golden" / "publish.json"
    if not meta.exists():
        body = http.get(GOLDEN, "application/json")
        if body is None:
            raise SystemExit("GLEIF golden copy: the latest publish could not be read")
        _save(meta, body)
    pub = _load(meta)["data"]
    for kind in ("rr", "repex"):
        dest = cache / "golden" / f"{kind}.csv.zip"
        if not dest.exists():
            url = pub[kind]["full_file"]["csv"]["url"]
            print(f"GLEIF golden copy: {kind} ({pub[kind]['full_file']['csv']['size_human_readable']})")
            body = http.get(url, "application/zip")
            if body is None:
                raise SystemExit(f"GLEIF golden copy: {kind} could not be downloaded")
            _save(dest, body)
    reduce_rr(cache)


def reduce_rr(cache: Path) -> None:
    """reduced/rr.json: every consolidation relationship of the golden copy (direct and ultimate, any status), one compact row each:
    [child, parent, D|U, relationship status, registration status, start, end, periods, qualifiers, initial, last update,
    next renewal, managing LOU, validation sources]. Fund and branch relationships are left out (they are not ownership)."""
    out = cache / "reduced" / "rr.json"
    src = cache / "golden" / "rr.csv.zip"
    if out.exists() or not src.exists():
        return
    rows = []
    for r in _csv(src):
        t = r["Relationship.RelationshipType"]
        if t not in CONSOLIDATION:
            continue
        periods, start, end = [], "", ""
        for i in range(1, 6):
            pt = r.get(f"Relationship.Period.{i}.periodType") or ""
            if not pt:
                continue
            s, e = (r.get(f"Relationship.Period.{i}.startDate") or "")[:10], (r.get(f"Relationship.Period.{i}.endDate") or "")[:10]
            periods.append([pt, s, e])
            if pt == "RELATIONSHIP_PERIOD":
                start, end = s, e
        quals = [[r.get(f"Relationship.Qualifiers.{i}.QualifierDimension") or "", r.get(f"Relationship.Qualifiers.{i}.QualifierCategory") or ""]
                 for i in range(1, 6) if r.get(f"Relationship.Qualifiers.{i}.QualifierDimension")]
        rows.append([r["Relationship.StartNode.NodeID"], r["Relationship.EndNode.NodeID"], "D" if t == CONSOLIDATION[0] else "U",
                     r["Relationship.RelationshipStatus"], r["Registration.RegistrationStatus"], start, end, periods, quals,
                     (r["Registration.InitialRegistrationDate"] or "")[:10], (r["Registration.LastUpdateDate"] or "")[:10],
                     (r["Registration.NextRenewalDate"] or "")[:10], r["Registration.ManagingLOU"], r["Registration.ValidationSources"]])
    rows.sort()
    _save(out, rows)


# ---------------------------------------------------------------- the universe: seeds and their groups

def us_equities_seeds() -> list[dict]:
    """The us-equities companies with an LEI: {lei, ticker, name, cik, indexes}."""
    if not US_EQUITIES.is_file():
        return []
    out = []
    for line in US_EQUITIES.read_text(encoding="utf-8").splitlines():
        if line.strip():
            d = json.loads(line)
            if d.get("LEI"):
                out.append({"lei": d["LEI"], "ticker": d["TICKER"], "name": d.get("NAME", ""), "cik": d.get("CIK", ""),
                            "indexes": d.get("INDEX_TEXT", ""), "sic": d.get("SIC_DESCRIPTION", "")})
    return out


class Graph:
    """The consolidation relationships of reduced/rr.json: direct and ultimate parent of each child (ACTIVE relationships only),
    their children, and every row by (child, D|U) for the relationship documents."""

    def __init__(self, rows: list[list]):
        self.direct: dict[str, str] = {}
        self.ultimate: dict[str, str] = {}
        self.kids: dict[str, list[str]] = {}
        self.ukids: dict[str, list[str]] = {}
        self.rows: dict[tuple[str, str], list] = {}
        for r in rows:
            child, parent, level, status = r[0], r[1], r[2], r[3]
            if status != "ACTIVE":
                self.rows.setdefault((child, level), r)
                continue
            self.rows[(child, level)] = r
            if level == "D":
                self.direct[child] = parent
                self.kids.setdefault(parent, []).append(child)
            else:
                self.ultimate[child] = parent
                self.ukids.setdefault(parent, []).append(child)

    def root(self, lei: str) -> str:
        """The ultimate parent: the reported one, else the top of the direct chain, else the entity itself."""
        if lei in self.ultimate:
            return self.ultimate[lei]
        seen, x = {lei}, lei
        while x in self.direct and self.direct[x] not in seen:
            x = self.direct[x]
            seen.add(x)
        return x

    def members(self, root: str, cap: int) -> tuple[list[str], int]:
        """(the group's members, nearest levels first, at most cap; every member the group has in GLEIF). Breadth first over the
        direct children from the root, then the entities that name the root as ultimate parent but whose direct parent is not
        reported, in LEI order."""
        order, seen, level = [root], {root}, [root]
        while level:
            nxt = []
            for x in level:
                for k in sorted(self.kids.get(x, [])):
                    if k not in seen:
                        seen.add(k)
                        order.append(k)
                        nxt.append(k)
            level = nxt
        for k in sorted(self.ukids.get(root, [])):
            if k not in seen:
                seen.add(k)
                order.append(k)
        return order[:cap], len(order)


def seeds() -> list[dict]:
    """Every seed: the us-equities companies, then SEEDS. {lei, segment, label, ticker?}."""
    out = [{"lei": s["lei"], "segment": "US equities", "label": s["name"], "ticker": s["ticker"]} for s in us_equities_seeds()]
    out += [{"lei": lei, "segment": seg, "label": label} for seg, label, lei in SEEDS]
    return out


def universe(cache: Path, extra: list[str] | None = None, cap: int | None = None) -> tuple[list[str], dict[str, dict]]:
    """(every LEI of the pack, sorted; {root LEI: {members, total, seeds}}): the groups of the seeds (and of extra LEIs)."""
    g = Graph(_load(cache / "reduced" / "rr.json", []))
    groups: dict[str, dict] = {}
    for s in seeds() + [{"lei": x, "segment": "Added with --scope", "label": x} for x in extra or []]:
        root = g.root(s["lei"])
        grp = groups.get(root)
        if grp is None:
            members, total = g.members(root, cap or GROUP_CAP)
            grp = groups[root] = {"members": members, "total": total, "seeds": []}
            if s["lei"] not in members:            # a seed beyond the cap is kept
                members.append(s["lei"])
        elif s["lei"] not in grp["members"]:
            grp["members"].append(s["lei"])
        grp["seeds"].append(s)
    leis = sorted({m for grp in groups.values() for m in grp["members"]})
    return leis, groups


# ---------------------------------------------------------------- the LEI API

def _pages(http: Http, cache: Path, name: str, path: str) -> None:
    """A whole paged API list (ELF codes, countries, LOUs) into api/<name>.json as served, page by page."""
    out = cache / "api" / f"{name}.json"
    if out.exists():
        return
    data, page = [], 1
    while True:
        body = http.get(f"{API}/{path}?page[size]=200&page[number]={page}", "application/vnd.api+json")
        if body is None:
            raise SystemExit(f"GLEIF API: {path} page {page} failed")
        d = json.loads(body)
        data += d.get("data", [])
        last = d.get("meta", {}).get("pagination", {}).get("lastPage", page)
        if page >= last:
            break
        page += 1
    _save(out, {"data": data})


def cached_records(cache: Path) -> dict[str, dict]:
    """{LEI: the lei-records item as served} from every api/records/*.json batch."""
    out: dict[str, dict] = {}
    for f in sorted((cache / "api" / "records").glob("*.json")):
        for item in (_load(f) or {}).get("data", []):
            out[item["id"]] = item
    return out


def api(http: Http, cache: Path, leis: list[str]) -> None:
    for name, path in (("elf", "entity-legal-forms"), ("countries", "countries"), ("lous", "lei-issuers")):
        _pages(http, cache, name, path)
    have = cached_records(cache)
    missing = sorted(set(leis) - set(have) - set((_load(cache / "api" / "not-found.json") or [])))
    print(f"GLEIF API: {len(leis)} entities, {len(missing)} to fetch ({(len(missing) + BATCH - 1) // BATCH} requests)")
    not_found = set(_load(cache / "api" / "not-found.json") or [])
    for i in range(0, len(missing), BATCH):
        chunk = missing[i:i + BATCH]
        url = f"{API}/lei-records?filter[lei]={','.join(chunk)}&page[size]={BATCH}"
        body = http.get(url, "application/vnd.api+json")
        if body is None:
            print(f"  batch {i // BATCH + 1} failed", file=sys.stderr)
            continue
        d = json.loads(body)
        got = {x["id"] for x in d.get("data", [])}
        not_found |= set(chunk) - got
        _save(cache / "api" / "records" / (hashlib.sha256(",".join(chunk).encode()).hexdigest()[:16] + ".json"), body)
        if (i // BATCH) % 10 == 9:
            print(f"  {i + len(chunk)}/{len(missing)}")
    _save(cache / "api" / "not-found.json", sorted(not_found))


# ---------------------------------------------------------------- the mapping files

def mapping(http: Http, cache: Path) -> None:
    for name in MAPPINGS:
        meta = cache / "mapping" / f"{name}.json"
        if not meta.exists():
            body = http.get(MAPPING.format(name=name), "application/vnd.api+json, */*")
            if body is None:
                print(f"  mapping {name}: the latest file could not be read", file=sys.stderr)
                continue
            _save(meta, body)
        dest = cache / "mapping" / f"{name}.zip"
        if not dest.exists():
            link = _load(meta)["data"]["attributes"]["downloadLink"]
            print(f"GLEIF mapping: {name}")
            body = http.get(link, "application/zip")
            if body is None:
                print(f"  mapping {name}: download failed", file=sys.stderr)
                continue
            _save(dest, body)


def reduce_mappings(cache: Path, leis: list[str]) -> None:
    """reduced/<name>.json: {LEI: [identifiers, sorted]} for the universe's LEIs (ISINs: the count and the first ISIN_SAMPLE)."""
    want = set(leis)
    for name in MAPPINGS:
        src, out = cache / "mapping" / f"{name}.zip", cache / "reduced" / f"{name}.json"
        fname = ((_load(cache / "mapping" / f"{name}.json") or {}).get("data") or {}).get("attributes", {}).get("fileName", "")
        key = hashlib.sha256((",".join(sorted(want)) + "|" + fname).encode()).hexdigest()[:16]
        if not src.exists() or (out.exists() and (_load(out) or {}).get("universe") == key):
            continue
        found: dict[str, list[str]] = {}
        for r in _csv(src):
            lei = r.get("LEI")
            if lei in want:
                found.setdefault(lei, []).append(next(v for k, v in r.items() if k != "LEI"))
        if name == "isin-lei":
            red = {k: {"count": len(v), "sample": sorted(v)[:ISIN_SAMPLE]} for k, v in sorted(found.items())}
        else:
            red = {k: sorted(set(v)) for k, v in sorted(found.items())}
        _save(out, {"universe": key, "file": fname, "data": red})


def reduce_repex(cache: Path, leis: list[str]) -> None:
    """reduced/repex.json: {LEI: [[category, reason], ...]} for the universe's LEIs (why no parent is reported)."""
    src, out = cache / "golden" / "repex.csv.zip", cache / "reduced" / "repex.json"
    want = set(leis)
    key = hashlib.sha256(",".join(sorted(want)).encode()).hexdigest()[:16]
    if not src.exists() or (out.exists() and (_load(out) or {}).get("universe") == key):
        return
    found: dict[str, list] = {}
    for r in _csv(src):
        if r["LEI"] in want:
            found.setdefault(r["LEI"], []).append([r["Exception.Category"], r["Exception.Reason.1"]])
    _save(out, {"universe": key, "data": {k: sorted(v) for k, v in sorted(found.items())}})


def main(argv: list[str]) -> int:
    p = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    p.add_argument("--cache", type=Path, default=DEFAULT_CACHE)
    p.add_argument("--only", default="golden,api,mapping")
    p.add_argument("--extra", default="", help="more LEIs whose groups to add (comma separated)")
    a = p.parse_args(argv)
    a.cache.mkdir(parents=True, exist_ok=True)
    http = Http()
    steps = a.only.split(",")
    extra = [x for x in a.extra.split(",") if x]
    if "golden" in steps:
        golden(http, a.cache)
    leis, groups = universe(a.cache, extra)
    print(f"universe: {len(groups)} groups, {len(leis)} entities")
    if "api" in steps:
        api(http, a.cache, leis)
    if "mapping" in steps:
        mapping(http, a.cache)
    reduce_mappings(a.cache, leis)
    reduce_repex(a.cache, leis)
    print("cache:", a.cache)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
